package com.raven.app.core.designsystem.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Calibrated micro-feedback semantics pairing tactile haptics with optional, subtle procedural
 * UI sound effects (`USAGE_ASSISTANCE_SONIFICATION`).
 */
enum class RavenFeedbackEffect(
    val hapticType: HapticFeedbackType,
    val volume: Float,
    internal val fileName: String,
    internal val durationMs: Int,
    internal val startHz: Float,
    internal val endHz: Float,
    internal val harmonicHz: Float = 0f,
    internal val harmonicMix: Float = 0f,
    internal val decayExponent: Float = 7.5f,
    internal val isDoublePulse: Boolean = false,
) {
    TAP(
        hapticType = HapticFeedbackType.ContextClick,
        volume = 0.22f,
        fileName = "sfx_tap.wav",
        durationMs = 18,
        startHz = 920f,
        endHz = 520f,
        decayExponent = 8.5f,
    ),
    TOGGLE_ON(
        hapticType = HapticFeedbackType.ToggleOn,
        volume = 0.25f,
        fileName = "sfx_toggle_on.wav",
        durationMs = 24,
        startHz = 660f,
        endHz = 980f,
        harmonicHz = 1320f,
        harmonicMix = 0.22f,
        decayExponent = 6.5f,
    ),
    TOGGLE_OFF(
        hapticType = HapticFeedbackType.ToggleOff,
        volume = 0.22f,
        fileName = "sfx_toggle_off.wav",
        durationMs = 22,
        startHz = 760f,
        endHz = 460f,
        decayExponent = 7.0f,
    ),
    TAB_SWITCH(
        hapticType = HapticFeedbackType.SegmentTick,
        volume = 0.18f,
        fileName = "sfx_tab.wav",
        durationMs = 16,
        startHz = 580f,
        endHz = 440f,
        decayExponent = 9.0f,
    ),
    FAVORITE(
        hapticType = HapticFeedbackType.Confirm,
        volume = 0.26f,
        fileName = "sfx_favorite.wav",
        durationMs = 62,
        startHz = 784f,
        endHz = 1175f,
        harmonicHz = 1568f,
        harmonicMix = 0.28f,
        decayExponent = 5.2f,
        isDoublePulse = true,
    ),
    SHEET_OPEN(
        hapticType = HapticFeedbackType.ContextClick,
        volume = 0.20f,
        fileName = "sfx_sheet.wav",
        durationMs = 28,
        startHz = 320f,
        endHz = 480f,
        decayExponent = 6.2f,
    ),
    ERROR(
        hapticType = HapticFeedbackType.Reject,
        volume = 0.24f,
        fileName = "sfx_error.wav",
        durationMs = 48,
        startHz = 240f,
        endHz = 170f,
        decayExponent = 5.5f,
        isDoublePulse = true,
    ),
}

/**
 * Pure Kotlin 16-bit mono PCM + RIFF/WAVE synthesizer for zero-dependency, low-footprint UI sounds.
 * Smooth cosine attack and exponential+cosine release guarantee zero speaker click/pop artifacts.
 */
object RavenSoundSynthesizer {
    const val SAMPLE_RATE_HZ: Int = 24_000
    private const val PEAK_AMPLITUDE: Double = 0.72 * Short.MAX_VALUE

    fun synthesizePcm16(effect: RavenFeedbackEffect): ShortArray {
        val sampleCount = ((SAMPLE_RATE_HZ * effect.durationMs) / 1_000).coerceAtLeast(32)
        val out = ShortArray(sampleCount)
        val attackSamples = ((SAMPLE_RATE_HZ * 1.5) / 1_000.0).coerceAtLeast(4.0)
        var phase = 0.0
        var harmonicPhase = 0.0

        for (i in 0 until sampleCount) {
            val t = i.toDouble() / (sampleCount - 1).coerceAtLeast(1)
            val freq = effect.startHz + (effect.endHz - effect.startHz) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE_HZ

            var signal = sin(phase)
            if (effect.harmonicHz > 0f && effect.harmonicMix > 0f) {
                val harmonicFreq = effect.harmonicHz * (1.0 + 0.15 * t)
                harmonicPhase += 2.0 * PI * harmonicFreq / SAMPLE_RATE_HZ
                signal += effect.harmonicMix * sin(harmonicPhase)
            }

            // Cosine fade-in over first ~1.5ms to prevent DC step click
            val attackEnv = if (i < attackSamples) {
                0.5 * (1.0 - cos(PI * i / attackSamples))
            } else {
                1.0
            }
            // Exponential decay multiplied by gentle raised-cosine tail so final samples settle to ~0
            val expDecay = exp(-effect.decayExponent * t)
            val tailWindow = 0.5 * (1.0 + cos(PI * t))
            val pulseMod = if (effect.isDoublePulse) {
                // Subtle two-pulse bell / thud articulation
                val split = 0.45
                if (t < split) {
                    val u = t / split
                    0.5 * (1.0 + cos(PI * u)) * 0.78 + 0.22
                } else {
                    val u = (t - split) / (1.0 - split)
                    sin(PI * u.coerceIn(0.0, 1.0)) * 0.85 + 0.15
                }
            } else {
                1.0
            }

            val normalizedHarmonicScale = 1.0 / (1.0 + effect.harmonicMix.toDouble().coerceAtLeast(0.0))
            val sampleVal = signal * normalizedHarmonicScale * attackEnv * expDecay * tailWindow * pulseMod * PEAK_AMPLITUDE
            out[i] = sampleVal.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    fun synthesizeWav(effect: RavenFeedbackEffect): ByteArray {
        val pcm = synthesizePcm16(effect)
        val dataBytes = pcm.size * 2
        val totalChunkSize = 36 + dataBytes
        val out = ByteArrayOutputStream(44 + dataBytes)

        // RIFF header
        out.writeAscii("RIFF")
        out.writeIntLE(totalChunkSize)
        out.writeAscii("WAVE")

        // fmt subchunk (PCM = 1, Mono = 1, 24kHz, 16-bit)
        out.writeAscii("fmt ")
        out.writeIntLE(16)
        out.writeShortLE(1)
        out.writeShortLE(1)
        out.writeIntLE(SAMPLE_RATE_HZ)
        out.writeIntLE(SAMPLE_RATE_HZ * 2)
        out.writeShortLE(2)
        out.writeShortLE(16)

        // data subchunk
        out.writeAscii("data")
        out.writeIntLE(dataBytes)
        for (sample in pcm) {
            out.writeShortLE(sample.toInt())
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(text: String) {
        for (ch in text) {
            write(ch.code and 0xFF)
        }
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}

/**
 * Unified UI feedback dispatcher combining immediate haptic vibration with optional
 * low-latency short UI sound effects.
 */
@Stable
interface RavenFeedbackController {
    fun perform(effect: RavenFeedbackEffect, hapticOnly: Boolean = false)
}

/** Safe no-op controller for previews and pure-JVM unit tests. */
object NoOpRavenFeedbackController : RavenFeedbackController {
    override fun perform(effect: RavenFeedbackEffect, hapticOnly: Boolean) = Unit
}

/**
 * Android [SoundPool]-backed implementation of [RavenFeedbackController].
 * Synthesizes micro-WAV assets lazily onto `cacheDir/raven_sfx_v1` off the main thread, respects
 * user preference + system ringer mode (`RINGER_MODE_NORMAL`), and safely releases native handles.
 */
class AndroidRavenFeedbackController(
    context: Context,
    private val hapticProvider: () -> HapticFeedback,
    private val soundEnabledProvider: () -> Boolean,
) : RavenFeedbackController {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val released = AtomicBoolean(false)
    private val soundIds = ConcurrentHashMap<RavenFeedbackEffect, Int>()
    private val loadedIds = ConcurrentHashMap.newKeySet<Int>()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "raven-ui-sfx").apply { isDaemon = true }
    }

    private val soundPool: SoundPool? = runCatching {
        SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
            .apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0 && !released.get()) {
                        loadedIds.add(sampleId)
                    }
                }
            }
    }.getOrNull()

    init {
        if (soundPool != null) {
            runCatching {
                executor.execute { preloadAllSounds() }
            }
        }
    }

    private fun preloadAllSounds() {
        val pool = soundPool ?: return
        if (released.get()) return
        val dir = File(appContext.cacheDir, SFX_CACHE_DIR)
        if (!dir.exists() && !dir.mkdirs()) return

        for (effect in RavenFeedbackEffect.entries) {
            if (released.get()) return
            runCatching {
                val wavBytes = RavenSoundSynthesizer.synthesizeWav(effect)
                val targetFile = File(dir, effect.fileName)
                if (!targetFile.isFile || targetFile.length() != wavBytes.size.toLong()) {
                    targetFile.writeBytes(wavBytes)
                }
                if (!released.get()) {
                    val id = pool.load(targetFile.absolutePath, 1)
                    if (id > 0) {
                        soundIds[effect] = id
                    }
                }
            }
        }
    }

    override fun perform(effect: RavenFeedbackEffect, hapticOnly: Boolean) {
        runCatching {
            hapticProvider().performHapticFeedback(effect.hapticType)
        }
        if (released.get() || hapticOnly || !soundEnabledProvider() || !canPlaySound()) return

        val pool = soundPool ?: return
        val soundId = soundIds[effect] ?: return
        if (soundId !in loadedIds) return

        runCatching {
            pool.play(soundId, effect.volume, effect.volume, 1, 0, 1.0f)
        }
    }

    private fun canPlaySound(): Boolean {
        val manager = audioManager ?: return false
        return runCatching {
            if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                false
            } else {
                manager.getStreamVolume(AudioManager.STREAM_SYSTEM) > 0 ||
                    manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
            }
        }.getOrDefault(false)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        loadedIds.clear()
        soundIds.clear()
        runCatching { soundPool?.release() }
        runCatching { executor.shutdownNow() }
    }

    private companion object {
        const val SFX_CACHE_DIR: String = "raven_sfx_v1"
    }
}

/** CompositionLocal providing unified tactile + short UI sound effect feedback across Raven. */
val LocalRavenFeedback = staticCompositionLocalOf<RavenFeedbackController> { NoOpRavenFeedbackController }
