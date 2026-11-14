@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package lol.mony.harmony.ui.screens.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.VoiceParticipant
import lol.mony.harmony.data.model.VoiceToken
import lol.mony.harmony.data.repo.VoiceRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.data.voice.CallParticipant
import lol.mony.harmony.data.voice.CallState
import lol.mony.harmony.data.voice.SpatialAudio
import lol.mony.harmony.data.voice.SpatialAudioSettings
import lol.mony.harmony.data.voice.UserPosition
import lol.mony.harmony.data.voice.VoiceEngine
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.Avatar
import lol.mony.harmony.ui.components.ErrorBanner
import java.time.Instant

/**
 * A voice channel's call: joining, the roster, the controls, video tiles and screen share.
 *
 * Permissions are requested here, at the point the person asks to speak or to show their
 * camera, never at launch. A refusal is answered with what the person can still do rather than
 * a dead screen: a microphone-less join is a listen-only join, and the screen says so.
 *
 * Screen sharing is the one capture that needs the system's own consent. That consent is a
 * separate per-session grant, so the capture session starts only after the system's dialog
 * returns it, and stopping is what tells the engine to unpublish.
 */
@Composable
fun VoiceChannelScreen(state: AppState, channel: Channel, onClose: () -> Unit) {
    val session = state.auth.state.collectAsState().value as? AuthState.SignedIn ?: return
    val myId = session.profile?.id ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { VoiceEngine(context) }
    val callState by engine.state.collectAsState()
    val controls by engine.controls.collectAsState()
    val remoteVideo by engine.remoteVideo.collectAsState()
    val screens by engine.screenShares.collectAsState()
    val spatial by engine.spatial.collectAsState()
    val positions by engine.positions.collectAsState()
    val repository = remember(state.container.rest) { VoiceRepository(state.container.rest, lol.mony.harmony.data.api.Http.client) }

    var config by remember { mutableStateOf<lol.mony.harmony.data.model.VoiceConfig?>(null) }
    var configLoaded by remember { mutableStateOf(false) }
    var roster by remember { mutableStateOf<List<VoiceParticipant>>(emptyList()) }
    var joined by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var microphoneGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var pendingCamera by remember { mutableStateOf(false) }
    var spatialOpen by remember { mutableStateOf(false) }
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    val people by state.people.collectAsState()

    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        if (!granted) permissionNotice = "Microphone access is off. You can listen, but nobody can hear you. Allow it in system settings to speak."
        else if (pendingCamera) { pendingCamera = false }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (granted) scope.launch { engine.setCamera(true) }
        else permissionNotice = "Camera access is off, so your camera stays unpublished."
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data: Intent? = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            permissionNotice = "Screen sharing was not allowed, so nothing is being shared."
        } else scope.launch {
            engine.setScreenSharing(true, data) {
                scope.launch { engine.setScreenSharing(false) }
            }.onFailure { failure = it.message }
        }
    }

    suspend fun refreshRoster() {
        runCatching { repository.participants(session.endpoint, state.auth.freshToken() ?: return, channel.id).getOrThrow() }
            .onSuccess { roster = it }
    }

    LaunchedEffect(channel.id) {
        runCatching { repository.config(session.endpoint, state.auth.freshToken() ?: "").getOrThrow() }
            .onSuccess { config = it }
        configLoaded = true
        refreshRoster()
        state.resolveAuthors(session.endpoint, state.auth.freshToken() ?: "", emptyList())
        while (true) { kotlinx.coroutines.delay(8_000); refreshRoster() }
    }
    // Every leave path releases the media. Closing the screen, backing out or the process going
    // away must never leave the microphone publishing.
    DisposableEffect(channel.id) { onDispose { scope.launch { engine.leave() } } }
    BackHandler {
        scope.launch {
            if (joined) {
                runCatching { repository.leaveRoster(session.endpoint, state.auth.freshToken() ?: "", channel.id, myId) }
                engine.leave()
            }
            onClose()
        }
    }

    fun join() {
        joining = true
        failure = null
        scope.launch {
            try {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                val liveKit = repository.token(session.endpoint, token,
                    VoiceRepository.roomName(channel.id, direct = false), VoiceRepository.roomType(direct = false)).getOrThrow()
                engine.join(liveKit, session.profile?.shownName ?: "You").getOrThrow()
                engine.setPosition(UserPosition(myId, 0.0, 0.0))
                joined = true
                if (channel.serverId.isNotBlank()) {
                    runCatching { repository.joinRoster(session.endpoint, token, channel.id, channel.serverId, myId) }
                }
                refreshRoster()
            } catch (error: Exception) {
                failure = error.message ?: "The call could not be joined."
            } finally { joining = false }
        }
    }

    fun leave() {
        scope.launch {
            val token = state.auth.freshToken()
            engine.leave()
            joined = false
            if (token != null) runCatching { repository.leaveRoster(session.endpoint, token, channel.id, myId) }
            refreshRoster()
            onClose()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Column {
            Text(channel.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
            Text(when {
                !configLoaded -> "Voice"
                config?.enabled != true -> "Voice is not available on this instance"
                config?.mode == "p2p" -> "Peer-to-peer calls are not supported by this client"
                joined -> "In the call"
                else -> "Voice channel"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = {
            IconButton(onClick = { if (joined) leave() else { scope.launch { engine.leave() }; onClose() } }) {
                Icon(Icons.Default.Close, "Close")
            }
        }, actions = {
            IconButton(onClick = { spatialOpen = true }) { Icon(Icons.Default.SurroundSound, "Spatial audio") }
        })
        val banner = failure
        if (banner != null) ErrorBanner(banner) { failure = null }
        val notice = permissionNotice
        if (notice != null) ErrorBanner(notice) { permissionNotice = null }

        if (joined) {
            // Screen shares take the top of the screen: a shared screen is the thing being
            // watched, so it is drawn larger than any camera tile.
            val shared = screens.entries.firstOrNull()
            if (shared != null) {
                Surface(color = Color.Black, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp)) {
                    VideoTile(shared.value, "Shared screen from " + (people[shared.key]?.shownName ?: shared.key),
                        Modifier.fillMaxWidth().height(220.dp), engine)
                }
            }
            val cameras = remoteVideo.entries.toList()
            if (cameras.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cameras, key = { it.key }) { (identity, track) ->
                        VideoTile(track, people[identity]?.shownName ?: identity, Modifier.fillMaxWidth().height(160.dp), engine)
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val connected = callState
            when {
                joining -> LoadingBlock("Joining")
                connected is CallState.Connecting -> LoadingBlock("Connecting")
                connected is CallState.Ended -> Column(Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(connected.reason, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { join() }, modifier = Modifier.testTag("call-rejoin")) { Text("Rejoin") }
                }
                joined -> RosterList(connected as? CallState.Connected, roster, people, myId, positions, spatial, session.endpoint.baseUrl)
                else -> JoinPrompt(config, configLoaded, channel, onJoin = {
                    if (!microphoneGranted) microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    join()
                })
            }
        }

        if (joined) {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallToggle(Icons.Default.Mic, if (controls.microphoneOn) "Mute" else "Unmute",
                        controls.microphoneOn, "call-mic", enabled = true) {
                        scope.launch {
                            if (!microphoneGranted) {
                                pendingCamera = false
                                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else engine.setMicrophone(!controls.microphoneOn).onFailure { failure = it.message }
                        }
                    }
                    CallToggle(Icons.Default.HeadsetOff, if (controls.deafened) "Undeafen" else "Deafen",
                        !controls.deafened, "call-deafen") {
                        scope.launch { engine.setDeafened(!controls.deafened).onFailure { failure = it.message } }
                    }
                    CallToggle(Icons.Default.Videocam, if (controls.cameraOn) "Stop video" else "Start video",
                        controls.cameraOn, "call-camera") {
                        scope.launch {
                            if (!controls.cameraOn && !cameraGranted) cameraLauncher.launch(Manifest.permission.CAMERA)
                            else engine.setCamera(!controls.cameraOn).onFailure { failure = it.message }
                        }
                    }
                    CallToggle(Icons.Default.ScreenShare, if (controls.screenSharing) "Stop sharing" else "Share screen",
                        controls.screenSharing, "call-screen") {
                        if (controls.screenSharing) scope.launch { engine.setScreenSharing(false) }
                        else {
                            val manager = context.getSystemService(MediaProjectionManager::class.java)
                            val intent = manager?.createScreenCaptureIntent()
                            if (intent != null) projectionLauncher.launch(intent)
                            else failure = "This device cannot share its screen."
                        }
                    }
                    CallToggle(Icons.Default.VolumeUp, if (controls.speakerOn) "Earpiece" else "Speaker",
                        controls.speakerOn, "call-speaker") { engine.setSpeaker(!controls.speakerOn) }
                }
            }
        }
    }

    if (spatialOpen) {
        SpatialAudioSheet(spatial, onDismiss = { spatialOpen = false }, onChange = { engine.setSpatialSettings(it) })
    }
}

@Composable
private fun JoinPrompt(config: lol.mony.harmony.data.model.VoiceConfig?, loaded: Boolean, channel: Channel, onJoin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(channel.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(when {
            !loaded -> "Checking whether this instance offers voice…"
            config?.enabled != true -> "This instance has no voice server configured, so there is nothing to join."
            config.mode == "p2p" -> "This instance runs peer-to-peer calls, which this client cannot join yet."
            else -> "Nobody is talking here until someone joins."
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loaded && config?.supportsThisClient == true) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onJoin, modifier = Modifier.testTag("call-join")) { Text("Join") }
        }
    }
}

@Composable
private fun RosterList(connected: CallState.Connected?, roster: List<VoiceParticipant>, people: Map<String, lol.mony.harmony.data.model.Profile>,
                       myId: String, positions: Map<String, UserPosition>, spatial: SpatialAudioSettings, baseUrl: String) {
    val participants = connected?.participants.orEmpty()
    LazyColumn(Modifier.fillMaxSize().testTag("call-roster"), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (participants.isEmpty()) item { Text("Joining the room…", Modifier.padding(16.dp)) }
        items(participants, key = { it.identity }) { participant ->
            val person = people[participant.identity]
            val mix = SpatialAudio.mix(positions[myId], positions[participant.identity], spatial)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Avatar(person?.shownName ?: participant.name, person?.avatarUrl, size = 36.dp, baseUrl = baseUrl)
                    if (participant.isSpeaking) Box(Modifier.size(10.dp).background(Color(0xFF43B581), CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(participant.name + (if (participant.isLocal) " (you)" else ""),
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    if (spatial.enabled && !participant.isLocal) Text(
                        SpatialAudio.describe(mix) + " · " + String.format(java.util.Locale.ROOT, "%.0f%%", mix.gain * 100),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (participant.isSharingScreen) Icon(Icons.Default.ScreenShare, "Sharing screen", Modifier.size(16.dp))
                if (participant.isCameraOn) Icon(Icons.Default.Videocam, "Camera on", Modifier.size(16.dp))
                if (participant.isMuted) Icon(Icons.Default.MicOff, "Muted", Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun VideoTile(track: VideoTrack, label: String, modifier: Modifier, engine: VoiceEngine) {
    Box(modifier.background(Color.Black, RoundedCornerShape(12.dp))) {
        AndroidView(
            factory = { context ->
                TextureViewRenderer(context).also { renderer ->
                    // A texture renderer with the default GL context: the track draws into it
                    // and the view composites it, so a remote camera or screen really appears.
                    renderer.init(engine.eglContext, null)
                    track.addRenderer(renderer)
                }
            },
            onRelease = { renderer -> track.removeRenderer(renderer); renderer.release() },
            modifier = Modifier.fillMaxSize().testTag("call-video"),
        )
        Text(label, Modifier.align(Alignment.BottomStart).padding(8.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CallToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean,
                       tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Icon(icon, label, Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
    }
}

@Composable
private fun LoadingBlock(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(label, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun SpatialAudioSheet(settings: SpatialAudioSettings, onDismiss: () -> Unit, onChange: (SpatialAudioSettings) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Spatial audio", style = MaterialTheme.typography.titleLarge)
            Text("People further away sound quieter and off to one side. Drag someone in the room to move them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", Modifier.weight(1f))
                Switch(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) },
                    modifier = Modifier.testTag("spatial-enabled"))
            }
            Text("Distance model", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lol.mony.harmony.data.voice.DistanceModel.entries.forEach { model ->
                    FilterChip(selected = settings.distanceModel == model, onClick = { onChange(settings.copy(distanceModel = model)) },
                        label = { Text(model.wire.replaceFirstChar { it.uppercase() }) })
                }
            }
            Text("Hearing range", style = MaterialTheme.typography.labelLarge)
            Slider(value = settings.maxDistance.toFloat(), onValueChange = { onChange(settings.copy(maxDistance = it.toDouble())) },
                valueRange = 50f..600f)
            Spacer(Modifier.height(12.dp))
        }
    }
}
