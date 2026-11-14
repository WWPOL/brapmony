package lol.mony.harmony.data.voice

import android.content.Context
import android.content.Intent
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.VoiceToken

/** What one person in the call is doing, as the call screen draws them. */
data class CallParticipant(
    val identity: String,
    val name: String,
    val isLocal: Boolean = false,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false,
    val isDeafened: Boolean = false,
    val isCameraOn: Boolean = false,
    val isSharingScreen: Boolean = false,
    /** True while this client holds a subscribed audio publication for the person. */
    val audioSubscribed: Boolean = false,
)

/** The call's own state: idle, connecting, in a room, or ended with a reason. */
sealed interface CallState {
    data object Idle : CallState
    data object Connecting : CallState
    data class Connected(val roomName: String, val participants: List<CallParticipant>) : CallState
    data class Ended(val reason: String) : CallState
}

/** The controls the call screen has, and whether each is currently on. */
data class CallControls(
    val microphoneOn: Boolean = true,
    val deafened: Boolean = false,
    val cameraOn: Boolean = false,
    val screenSharing: Boolean = false,
    val speakerOn: Boolean = true,
)

/**
 * A voice, video and screen-share call over LiveKit, the transport Harmony's own voice stack
 * uses.
 *
 * This is not a simulation: joining publishes the microphone as a real WebRTC audio track and
 * subscribes to everyone else's, so a web client in the same room hears this client and this
 * client hears the web client. Camera and screen capture publish real video tracks, and a remote
 * video track is handed to the screen as a [VideoTrack] to render.
 *
 * Every exit path releases what it took. [leave] stops capture, unpublishes, disconnects the
 * room and stops the audio handler, so leaving a call never leaves the microphone hot.
 */
class VoiceEngine(private val context: Context) {
    private var room: Room? = null
    private var audioHandler: AudioSwitchHandler? = null
    private var roomScope: CoroutineScope? = null

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _controls = MutableStateFlow(CallControls())
    val controls: StateFlow<CallControls> = _controls.asStateFlow()

    private val _spatial = MutableStateFlow(SpatialAudioSettings.DEFAULT)
    val spatial: StateFlow<SpatialAudioSettings> = _spatial.asStateFlow()

    private val _positions = MutableStateFlow<Map<String, UserPosition>>(emptyMap())
    val positions: StateFlow<Map<String, UserPosition>> = _positions.asStateFlow()

    /**
     * The EGL context the room's video pipeline draws on. A texture renderer must init with the
     * same context, or the frames never reach the view.
     */
    val eglContext: livekit.org.webrtc.EglBase.Context?
        get() = runCatching { room?.lkObjects?.eglBase?.eglBaseContext }.getOrNull()

    private val _remoteVideo = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())

    /** Remote camera tracks, by participant identity, for the call screen's video tiles. */
    val remoteVideo: StateFlow<Map<String, VideoTrack>> = _remoteVideo.asStateFlow()

    private val _screenShares = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())

    /** Remote screen-share tracks, by participant identity. */
    val screenShares: StateFlow<Map<String, VideoTrack>> = _screenShares.asStateFlow()

    /**
     * Joins [token]'s room and publishes the microphone.
     *
     * Requesting the microphone permission is the caller's job; this method reports a refused
     * track as a visible state rather than silently pretending to transmit.
     */
    suspend fun join(token: VoiceToken, displayName: String, publishCamera: Boolean = false): Result<Unit> = runCatching {
        leave()
        _state.value = CallState.Connecting
        val liveKitRoom = LiveKit.create(
            appContext = context,
            options = RoomOptions(adaptiveStream = true, dynacast = true),
        )
        room = liveKitRoom
        val handler = AudioSwitchHandler(context)
        audioHandler = handler
        handler.start()
        handler.selectDevice(handler.availableAudioDevices.firstOrNull { it is AudioDevice.Speakerphone }
            ?: handler.availableAudioDevices.firstOrNull())

        val events = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        roomScope = events
        events.launch {
            liveKitRoom.events.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        val local = liveKitRoom.localParticipant
                        val microphone = runCatching { local.setMicrophoneEnabled(true) }.getOrDefault(false)
                        _controls.update { it.copy(microphoneOn = microphone == true) }
                        if (publishCamera) {
                            val camera = runCatching { local.setCameraEnabled(true) }.getOrDefault(false)
                            _controls.update { it.copy(cameraOn = camera == true) }
                        }
                        refreshParticipants(liveKitRoom)
                    }
                    is RoomEvent.Disconnected -> {
                        _state.value = CallState.Ended(reasonOf(event.reason))
                        refreshParticipants(liveKitRoom)
                    }
                    is RoomEvent.FailedToConnect -> _state.value =
                        CallState.Ended(event.error.message ?: "The call could not be joined.")
                    is RoomEvent.Reconnecting -> _state.value = CallState.Connecting
                    is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackSubscribed, is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.TrackPublished, is RoomEvent.TrackUnpublished,
                    is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted,
                    is RoomEvent.ActiveSpeakersChanged, is RoomEvent.Reconnected,
                    is RoomEvent.LocalTrackSubscribed -> refreshParticipants(liveKitRoom)
                    else -> Unit
                }
            }
        }
        liveKitRoom.connect(token.wsUrl, token.token)
        refreshParticipants(liveKitRoom)
        Unit
    }

    suspend fun setMicrophone(on: Boolean): Result<Unit> = runCatching {
        val local = room?.localParticipant ?: error("You are not in a call.")
        local.setMicrophoneEnabled(on)
        _controls.update { it.copy(microphoneOn = on) }
        refreshParticipants()
    }

    /**
     * Deafen stops hearing everyone without changing what this person publishes: the reference
     * treats it as a listening choice, and the roster row reports it so other clients show it.
     */
    suspend fun setDeafened(deafened: Boolean): Result<Unit> = runCatching {
        _controls.update { it.copy(deafened = deafened) }
        val liveKitRoom = room ?: return@runCatching
        for (participant in liveKitRoom.remoteParticipants.values) {
            for (publication in participant.trackPublications.values) {
                (publication as? io.livekit.android.room.track.RemoteTrackPublication)
                    ?.takeIf { it.kind == Track.Kind.AUDIO }?.setSubscribed(!deafened)
            }
        }
        refreshParticipants()
    }

    suspend fun setCamera(on: Boolean): Result<Unit> = runCatching {
        val local = room?.localParticipant ?: error("You are not in a call.")
        local.setCameraEnabled(on)
        _controls.update { it.copy(cameraOn = on) }
        refreshParticipants()
    }

    /**
     * Starts or stops publishing a screen capture.
     *
     * The system's own capture consent is a separate, per-session grant: [mediaProjectionPermission]
     * is the `Intent` that consent returned, and it is required to start. Without it nothing is
     * captured and the caller is told why.
     */
    suspend fun setScreenSharing(on: Boolean, mediaProjectionPermission: Intent? = null,
                                 stopWhenSystemStops: (() -> Unit)? = null): Result<Unit> = runCatching {
        val local = room?.localParticipant ?: error("You are not in a call.")
        if (!on) {
            local.setScreenShareEnabled(false)
            _controls.update { it.copy(screenSharing = false) }
            refreshParticipants()
            return@runCatching
        }
        val permission = mediaProjectionPermission
            ?: error("Screen sharing needs the system's screen-capture consent.")
        val params = ScreenCaptureParams(
            mediaProjectionPermissionResultData = permission,
            notification = null,
            onStop = { stopWhenSystemStops?.invoke() },
        )
        local.setScreenShareEnabled(true, params)
        _controls.update { it.copy(screenSharing = true) }
        refreshParticipants()
    }

    /** Routes audio to the speaker or the earpiece, as the reference's output control does. */
    fun setSpeaker(on: Boolean) {
        val handler = audioHandler
        if (handler != null) {
            val wanted = handler.availableAudioDevices.firstOrNull {
                if (on) it is AudioDevice.Speakerphone else it is AudioDevice.Earpiece
            } ?: handler.availableAudioDevices.firstOrNull()
            if (wanted != null) handler.selectDevice(wanted)
        }
        _controls.update { it.copy(speakerOn = on) }
    }

    fun setSpatialSettings(settings: SpatialAudioSettings) { _spatial.value = settings }

    fun setPosition(position: UserPosition) { _positions.update { it + (position.userId to position) } }

    /** The mix this listener applies to a speaker, from the current positions. */
    fun mixFor(listenerId: String, speakerId: String): SpatialMix =
        SpatialAudio.mix(_positions.value[listenerId], _positions.value[speakerId], _spatial.value)

    private suspend fun refreshParticipants(liveKitRoom: Room? = room) {
        if (liveKitRoom == null) return
        val participants = participants(liveKitRoom)
        val connected = _state.value
        _state.value = when (connected) {
            is CallState.Connected -> connected.copy(participants = participants)
            CallState.Connecting -> CallState.Connected(liveKitRoom.name ?: "", participants)
            else -> connected
        }
        val video = mutableMapOf<String, VideoTrack>()
        val screens = mutableMapOf<String, VideoTrack>()
        for (participant in liveKitRoom.remoteParticipants.values) {
            val who = participant.identity?.value ?: continue
            for (publication in participant.trackPublications.values) {
                val track = publication.track as? VideoTrack ?: continue
                if (publication.isScreenShareTrack()) screens[who] = track
                else if (publication.source == Track.Source.CAMERA) video[who] = track
            }
        }
        _remoteVideo.value = video
        _screenShares.value = screens
    }

    private fun participants(liveKitRoom: Room): List<CallParticipant> {
        val out = mutableListOf<CallParticipant>()
        out += participantOf(liveKitRoom.localParticipant, isLocal = true)
        for (remote in liveKitRoom.remoteParticipants.values) out += participantOf(remote, isLocal = false)
        return out.sortedWith(compareBy({ !it.isLocal }, { it.name.lowercase() }))
    }

    private fun participantOf(participant: Participant, isLocal: Boolean): CallParticipant {
        val who = participant.identity?.value ?: "unknown"
        val publications = participant.trackPublications.values
        val microphone = publications.firstOrNull { it.kind == Track.Kind.AUDIO }
        val camera = publications.firstOrNull { it.source == Track.Source.CAMERA }
        val screen = publications.firstOrNull { it.source == Track.Source.SCREEN_SHARE || it.isScreenShareTrack() }
        val label = participant.name?.takeIf { it.isNotBlank() }
            ?: participant.metadata?.takeIf { it.isNotBlank() }
            ?: who
        return CallParticipant(
            identity = who,
            name = label,
            isLocal = isLocal,
            isSpeaking = participant.isSpeaking,
            isMuted = microphone?.muted ?: true,
            isDeafened = isLocal && _controls.value.deafened,
            isCameraOn = camera != null && !camera.muted,
            isSharingScreen = screen != null,
            audioSubscribed = microphone?.subscribed == true,
        )
    }

    /** True for a publication the SDK marks as a screen share (its source or its name). */
    private fun TrackPublication.isScreenShareTrack(): Boolean =
        source == Track.Source.SCREEN_SHARE || source == Track.Source.SCREEN_SHARE_AUDIO ||
            name?.contains("screen", ignoreCase = true) == true

    /** Leaves the room and releases every resource it took. */
    suspend fun leave() {
        val liveKitRoom = room
        room = null
        roomScope?.cancel()
        roomScope = null
        _remoteVideo.value = emptyMap()
        _screenShares.value = emptyMap()
        _positions.value = emptyMap()
        _controls.value = CallControls()
        if (liveKitRoom != null) {
            runCatching { liveKitRoom.localParticipant.setCameraEnabled(false) }
            runCatching { liveKitRoom.localParticipant.setScreenShareEnabled(false) }
            runCatching { liveKitRoom.localParticipant.setMicrophoneEnabled(false) }
            runCatching { liveKitRoom.disconnect() }
            runCatching { liveKitRoom.release() }
        }
        runCatching { audioHandler?.stop() }
        audioHandler = null
        if (_state.value !is CallState.Ended) _state.value = CallState.Idle
    }

    private fun reasonOf(reason: DisconnectReason): String = when (reason) {
        DisconnectReason.CLIENT_INITIATED -> "You left the call."
        DisconnectReason.DUPLICATE_IDENTITY -> "This account joined the call somewhere else."
        DisconnectReason.SERVER_SHUTDOWN -> "The call server stopped."
        DisconnectReason.PARTICIPANT_REMOVED -> "You were removed from the call."
        DisconnectReason.ROOM_DELETED, DisconnectReason.ROOM_CLOSED -> "The call ended."
        DisconnectReason.STATE_MISMATCH -> "The call lost sync. Join again."
        DisconnectReason.JOIN_FAILURE -> "The call could not be joined."
        DisconnectReason.SIGNAL_CLOSE, DisconnectReason.CONNECTION_TIMEOUT -> "The connection to the call closed."
        DisconnectReason.MIGRATION -> "The call moved to another server. Rejoin to continue."
        DisconnectReason.USER_UNAVAILABLE -> "The other person is unavailable."
        DisconnectReason.USER_REJECTED -> "The call was declined."
        DisconnectReason.SIP_TRUNK_FAILURE -> "The phone bridge failed."
        DisconnectReason.UNKNOWN_REASON -> "The call ended unexpectedly."
    }
}
