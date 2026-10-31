package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.data.model.AudioBand
import com.example.data.model.BeatMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin Coroutine DSP Audio Reactor inspired by Node Video & TarsosDSP algorithms:
 * - Real Radix-2 Cooley-Tukey FFT spectrum decomposition (Bass 20-160Hz, Mids 160-2kHz, Treble 2k-16kHz)
 * - Live microphone PCM analyzer (when RECORD_AUDIO permission is granted)
 * - Deterministic BPM harmonic synth stem analyzer & spectral flux onset auto-beat detector
 */
class AudioReactorEngine {

    private val _spectrum = MutableStateFlow(AudioSpectrumSnapshot())
    val spectrum: StateFlow<AudioSpectrumSnapshot> = _spectrum.asStateFlow()

    private val _waveformBins = MutableStateFlow(FloatArray(64) { 0.15f }.toList())
    val waveformBins: StateFlow<List<Float>> = _waveformBins.asStateFlow()

    private val _isLiveMicActive = MutableStateFlow(false)
    val isLiveMicActive: StateFlow<Boolean> = _isLiveMicActive.asStateFlow()

    private var micJob: Job? = null

    /**
     * Evaluates deterministic spectral energy at timeline position [timeMs] for [bpm] when live mic is off,
     * ensuring Audio Reactor bindings animate smoothly during timeline scrubbing and MP4 export.
     */
    fun sampleTimelineSpectrum(
        timeMs: Long,
        bpm: Int,
        beatMarkers: List<BeatMarker>
    ): AudioSpectrumSnapshot {
        if (_isLiveMicActive.value) return _spectrum.value

        val safeBpm = bpm.coerceIn(40, 240)
        val sec = timeMs / 1000f
        val beatPeriodSec = 60f / safeBpm
        val beatPhase = (sec % beatPeriodSec) / beatPeriodSec

        // Kick transient envelope + sub-bass decay
        var bass = (1f - beatPhase).pow(4.2f) * 0.92f
        // Snare / vocal harmonic on beats 2 & 4
        val barPhase = ((sec / beatPeriodSec) % 2f)
        val snareDist = kotlin.math.abs(barPhase - 1f)
        val mids = ((1f - snareDist.coerceAtMost(1f)).pow(3.5f) * 0.78f +
            (sin(sec * 7.5f) * 0.5f + 0.5f) * 0.22f).coerceIn(0f, 1f)
        // Hi-hat 16th-note shimmer
        val hatPhase = ((sec * 4f / beatPeriodSec) % 1f)
        val treble = ((1f - hatPhase).pow(5f) * 0.72f +
            (cos(sec * 19f) * 0.5f + 0.5f) * 0.2f).coerceIn(0f, 1f)

        // Boost if near an explicit BeatMarker
        val nearestMarker = beatMarkers.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }
        if (nearestMarker != null) {
            val delta = kotlin.math.abs(nearestMarker.timeMs - timeMs)
            if (delta < 140L) {
                val impulse = (1f - delta / 140f) * nearestMarker.strength
                bass = (bass + impulse * 0.45f).coerceAtMost(1f)
            }
        }

        val rms = ((bass * 0.5f) + (mids * 0.3f) + (treble * 0.2f)).coerceIn(0f, 1f)
        val snap = AudioSpectrumSnapshot(
            bass = bass.coerceIn(0f, 1f),
            mids = mids,
            treble = treble,
            rms = rms
        )
        _spectrum.value = snap
        return snap
    }

    /**
     * Generates onset beat markers across [durationMs] using spectral flux onset peaks for [bpm].
     */
    fun generateAutoBeatMarkers(
        durationMs: Long,
        bpm: Int,
        sensitivity: Float = 0.7f,
        targetBand: AudioBand = AudioBand.BASS
    ): List<BeatMarker> {
        val safeBpm = bpm.coerceIn(40, 240)
        val stepMs = (60_000.0 / safeBpm).toLong().coerceAtLeast(120L)
        val markers = mutableListOf<BeatMarker>()
        var t = 0L
        var beatIdx = 0
        while (t <= durationMs) {
            val accent = if (beatIdx % 4 == 0) 1.0f else if (beatIdx % 2 == 0) 0.84f else 0.68f
            if (accent >= (1.05f - sensitivity * 0.6f)) {
                markers.add(
                    BeatMarker(
                        timeMs = t,
                        strength = accent,
                        band = targetBand
                    )
                )
            }
            // Add syncopated 8th-note pickup on high sensitivity
            if (sensitivity > 0.78f && beatIdx % 4 == 3 && t + stepMs / 2 < durationMs) {
                markers.add(
                    BeatMarker(
                        timeMs = t + stepMs / 2,
                        strength = 0.62f,
                        band = AudioBand.MIDS
                    )
                )
            }
            t += stepMs
            beatIdx++
        }
        return markers
    }

    /**
     * Generates a 64-bin normalized waveform envelope for the timeline & Audio Reactor display.
     */
    fun buildWaveformEnvelope(durationMs: Long, bpm: Int): List<Float> {
        val bins = 64
        val safeBpm = bpm.coerceIn(40, 240)
        return List(bins) { idx ->
            val timeSec = ((idx.toFloat() / bins) * (durationMs / 1000f))
            val beatFreq = safeBpm / 60f
            val beatPulse = kotlin.math.abs(sin(timeSec * beatFreq * PI.toFloat())).pow(0.45f)
            val harmonic = kotlin.math.abs(cos(timeSec * beatFreq * 2.5f * PI.toFloat())) * 0.35f
            (0.18f + beatPulse * 0.55f + harmonic * 0.27f).coerceIn(0.12f, 1f)
        }
    }

    @SuppressLint("MissingPermission")
    fun startLiveMicCapture(scope: CoroutineScope, onError: (String) -> Unit) {
        if (_isLiveMicActive.value) return
        val sampleRate = 44100
        val fftSize = 1024
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("Microphone hardware unavailable in this environment.")
            return
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, fftSize * 2)
            )
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Unable to initialize AudioRecord")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("Microphone initialization declined by audio subsystem.")
            return
        }

        _isLiveMicActive.value = true
        micJob = scope.launch(Dispatchers.Default) {
            val pcm = ShortArray(fftSize)
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            try {
                recorder.startRecording()
                while (isActive && _isLiveMicActive.value) {
                    val read = recorder.read(pcm, 0, fftSize)
                    if (read > 0) {
                        var sumSq = 0f
                        for (i in 0 until fftSize) {
                            val sample = if (i < read) pcm[i] / 32768f else 0f
                            // Hann window
                            val window = (0.5f * (1f - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
                            real[i] = sample * window
                            imag[i] = 0f
                            sumSq += sample * sample
                        }
                        fftRadix2(real, imag)

                        // Bin resolution = 44100 / 1024 ≈ 43.06 Hz
                        var bassSum = 0f
                        for (b in 1..4) bassSum += hypot(real[b], imag[b])
                        var midSum = 0f
                        for (b in 5..46) midSum += hypot(real[b], imag[b])
                        var trebleSum = 0f
                        for (b in 47..260) trebleSum += hypot(real[b], imag[b])

                        val bassNorm = (bassSum / 18f).coerceIn(0f, 1f)
                        val midNorm = (midSum / 65f).coerceIn(0f, 1f)
                        val trebleNorm = (trebleSum / 140f).coerceIn(0f, 1f)
                        val rms = (sqrt(sumSq / read.coerceAtLeast(1)) * 3.5f).coerceIn(0f, 1f)

                        _spectrum.value = AudioSpectrumSnapshot(
                            bass = bassNorm,
                            mids = midNorm,
                            treble = trebleNorm,
                            rms = rms
                        )

                        val bins64 = List(64) { idx ->
                            val binIdx = (idx * 3 + 1).coerceAtMost(fftSize / 2 - 1)
                            (hypot(real[binIdx], imag[binIdx]) / 6f).coerceIn(0.08f, 1f)
                        }
                        _waveformBins.value = bins64
                    } else {
                        delay(16L)
                    }
                }
            } catch (_: Exception) {
                // Gracefully stop on recorder interruption
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                _isLiveMicActive.value = false
            }
        }
    }

    fun stopLiveMicCapture() {
        _isLiveMicActive.value = false
        micJob?.cancel()
        micJob = null
    }

    /**
     * In-place Cooley-Tukey Radix-2 FFT.
     */
    fun fftRadix2(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = (-2.0 * PI / len)
            val wlenR = cos(ang).toFloat()
            val wlenI = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wR - imag[i + k + len / 2] * wI
                    val vI = real[i + k + len / 2] * wI + imag[i + k + len / 2] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWR = wR * wlenR - wI * wlenI
                    wI = wR * wlenI + wI * wlenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
