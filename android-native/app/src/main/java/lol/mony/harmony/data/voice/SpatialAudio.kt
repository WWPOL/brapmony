package lol.mony.harmony.data.voice

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Where one participant stands in the room's 2D grid, in the reference's units. */
data class UserPosition(val userId: String, val x: Double, val y: Double, val z: Double = 0.0)

/**
 * The reference's spatial-audio model, carried over exactly.
 *
 * `src/stores/spatialAudio.ts` computes a gain and a pan from two participants' positions:
 *
 *  - distance is Euclidean in the 2D grid, `sqrt(dx^2 + dy^2)`;
 *  - beyond `maxDistance` the gain is 0, so a person far away is silent;
 *  - `linear`, `inverse` and `exponential` are the three distance models, with `inverse`
 *    dividing by 50 (the reference's own constant);
 *  - pan is the horizontal offset divided by half the maximum distance, clamped to [-1, 1].
 *
 * Keeping the numbers identical matters: the same two people standing in the same places must
 * hear each other the same way in this client and in the web client.
 */
data class SpatialAudioSettings(
    val enabled: Boolean = false,
    val maxDistance: Double = 300.0,
    val rolloffFactor: Double = 1.0,
    val distanceModel: DistanceModel = DistanceModel.Inverse,
    val panningModel: PanningModel = PanningModel.Hrtf,
    val enableReverb: Boolean = false,
    val roomSize: Double = 0.5,
    val binauralIntensity: Double = 0.7,
) {
    companion object {
        /** The reference's defaults, so a fresh install sounds the same as the web client. */
        val DEFAULT = SpatialAudioSettings()
    }
}

enum class DistanceModel(val wire: String) {
    Linear("linear"), Inverse("inverse"), Exponential("exponential");

    companion object {
        fun of(wire: String?): DistanceModel = entries.firstOrNull { it.wire == wire } ?: Inverse
    }
}

enum class PanningModel(val wire: String) {
    EqualPower("equalpower"), Hrtf("HRTF");

    companion object {
        fun of(wire: String?): PanningModel = entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: Hrtf
    }
}

/** What one listener should apply to one speaker: a linear gain and a stereo pan. */
data class SpatialMix(val gain: Double, val pan: Double)

object SpatialAudio {
    /** The reference's `inverse` distance constant, in the same units as the positions. */
    private const val INVERSE_REFERENCE_DISTANCE = 50.0

    fun distance(a: UserPosition?, b: UserPosition?): Double {
        if (a == null || b == null) return Double.POSITIVE_INFINITY
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * The gain a listener at [listener] applies to a speaker at [speaker].
     *
     * Mirrors `getAudioGain`: 1 when spatial audio is off or either position is unknown, 0 past
     * `maxDistance`, and otherwise the chosen distance model's curve.
     */
    fun gain(listener: UserPosition?, speaker: UserPosition?, settings: SpatialAudioSettings): Double {
        if (!settings.enabled) return 1.0
        if (listener == null || speaker == null) return 1.0
        val d = distance(listener, speaker)
        val max = settings.maxDistance
        if (d >= max) return 0.0
        return when (settings.distanceModel) {
            DistanceModel.Linear -> (1.0 - d / max).coerceAtLeast(0.0)
            DistanceModel.Inverse -> 1.0 / (1.0 + settings.rolloffFactor * d / INVERSE_REFERENCE_DISTANCE)
            DistanceModel.Exponential -> (1.0 - d / max).coerceAtLeast(0.0).pow(settings.rolloffFactor)
        }
    }

    /**
     * The stereo pan a listener applies to a speaker: `dx` over half the maximum distance,
     * clamped to [-1, 1], as `getPanning` computes it.
     */
    fun pan(listener: UserPosition?, speaker: UserPosition?, settings: SpatialAudioSettings): Double {
        if (!settings.enabled) return 0.0
        if (listener == null || speaker == null) return 0.0
        val dx = speaker.x - listener.x
        val maxPan = settings.maxDistance / 2.0
        if (maxPan <= 0.0) return 0.0
        return (dx / maxPan).coerceIn(-1.0, 1.0)
    }

    fun mix(listener: UserPosition?, speaker: UserPosition?, settings: SpatialAudioSettings): SpatialMix =
        SpatialMix(gain(listener, speaker, settings), pan(listener, speaker, settings))

    /**
     * The per-channel volume multipliers a pan produces for a stereo pair.
     *
     * The web client hands the pan to Web Audio's `StereoPannerNode`, which uses equal-power
     * panning: at pan -1 only the left channel carries the signal, at 0 both carry it at
     * `1/sqrt(2)`, and at +1 only the right does. ExoPlayer has no panner, so the same curve is
     * applied to a two-channel volume here.
     */
    fun channelGains(pan: Double): Pair<Double, Double> {
        val angle = ((pan.coerceIn(-1.0, 1.0) + 1.0) / 2.0) * (Math.PI / 2.0)
        return Math.cos(angle) to Math.sin(angle)
    }

    /**
     * A readable name for the current mix, for the call screen's per-person volume label.
     * A muted or distant participant reads as such rather than as an unexplained zero.
     */
    fun describe(mix: SpatialMix): String = when {
        mix.gain <= 0.001 -> "Out of range"
        abs(mix.pan) < 0.05 -> "Centered"
        mix.pan < 0 -> "Left"
        else -> "Right"
    }
}
