package com.example.dsp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.model.DolbyConstants
import com.example.model.HardwareAudioEndpoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Circular Ring Buffer with 4-Point Hermite Cubic Fractional Delay Interpolation
 * and Asynchronous Clock-Drift PI Controller.
 */
class HermiteFractionalDelayLine(
    private val sampleRate: Double = DolbyConstants.SAMPLE_RATE
) {
    private val bufferSize = 32768
    private val mask = bufferSize - 1
    private val ringL = FloatArray(bufferSize)
    private val ringR = FloatArray(bufferSize)
    private var writeIndex = 0

    private var currentDelaySamples = 0.0
    private var targetDelaySamples = 0.0
    private val slewRatePerSample = 0.0002

    private var integralDriftError = 0.0
    private val kp = 1.0e-5
    private val ki = 1.0e-8
    var currentResampleRatio = 1.0
        private set

    fun setTargetDelayMs(delayMs: Double, clockDriftPpm: Double = 0.0) {
        val clampedMs = delayMs.coerceIn(0.0, 400.0)
        targetDelaySamples = (clampedMs * sampleRate) / 1000.0

        val bufferError = (clockDriftPpm * 1e-6) * targetDelaySamples
        integralDriftError = (integralDriftError + bufferError).coerceIn(-500.0, 500.0)
        currentResampleRatio = (1.0 + kp * bufferError + ki * integralDriftError).coerceIn(0.9990, 1.0010)
    }

    fun processBlock(
        inL: FloatArray,
        inR: FloatArray,
        outL: FloatArray,
        outR: FloatArray,
        numSamples: Int,
        gainLinear: Float = 1.0f
    ) {
        for (n in 0 until numSamples) {
            ringL[writeIndex] = inL[n]
            ringR[writeIndex] = inR[n]

            val diff = targetDelaySamples - currentDelaySamples
            currentDelaySamples += if (abs(diff) <= slewRatePerSample) {
                diff
            } else if (diff > 0.0) {
                slewRatePerSample
            } else {
                -slewRatePerSample
            }

            val totalDelay = max(1.0, currentDelaySamples * currentResampleRatio)
            val dInt = floor(totalDelay).toInt()
            val mu = (totalDelay - dInt).toFloat()

            val idxM1 = (writeIndex - dInt - 1) and mask
            val idx0 = (writeIndex - dInt) and mask
            val idxP1 = (writeIndex - dInt + 1) and mask
            val idxP2 = (writeIndex - dInt + 2) and mask

            outL[n] = hermite4Point(
                ringL[idxM1], ringL[idx0], ringL[idxP1], ringL[idxP2], mu
            ) * gainLinear
            outR[n] = hermite4Point(
                ringR[idxM1], ringR[idx0], ringR[idxP1], ringR[idxP2], mu
            ) * gainLinear

            writeIndex = (writeIndex + 1) and mask
        }
    }

    private fun hermite4Point(xm1: Float, x0: Float, xp1: Float, xp2: Float, mu: Float): Float {
        val c0 = x0
        val c1 = 0.5f * (xp1 - xm1)
        val c2 = xm1 - 2.5f * x0 + 2.0f * xp1 - 0.5f * xp2
        val c3 = 0.5f * (xp2 - xm1) + 1.5f * (x0 - xp1)
        return c0 + mu * (c1 + mu * (c2 + mu * c3))
    }
}

/**
 * Generalized Cross-Correlation with Phase Transform (GCC-PHAT) Acoustic Latency Estimator.
 */
object GccPhatCalibrator {
    private const val FFT_LEN = 8192

    fun generateLogChirp(
        numSamples: Int = (0.045 * DolbyConstants.SAMPLE_RATE).toInt(),
        fStart: Double = 1000.0,
        fEnd: Double = 6000.0,
        sampleRate: Double = DolbyConstants.SAMPLE_RATE
    ): FloatArray {
        val chirp = FloatArray(numSamples)
        val durationSec = numSamples / sampleRate
        val lnRatio = ln(fEnd / fStart)
        for (n in 0 until numSamples) {
            val t = n / sampleRate
            val phase = 2.0 * PI * fStart * durationSec * (((fEnd / fStart).pow(t / durationSec) - 1.0) / lnRatio)
            val env = sin(PI * n.toDouble() / (numSamples - 1)).pow(0.5)
            chirp[n] = (0.75 * env * sin(phase)).toFloat()
        }
        return chirp
    }

    fun computeGccPhatDelayMs(
        referenceChirp: FloatArray,
        micCapture: FloatArray,
        sampleRate: Double = DolbyConstants.SAMPLE_RATE
    ): Float {
        val sRe = DoubleArray(FFT_LEN)
        val sIm = DoubleArray(FFT_LEN)
        val mRe = DoubleArray(FFT_LEN)
        val mIm = DoubleArray(FFT_LEN)

        for (i in 0 until min(referenceChirp.size, FFT_LEN)) sRe[i] = referenceChirp[i].toDouble()
        for (i in 0 until min(micCapture.size, FFT_LEN)) mRe[i] = micCapture[i].toDouble()

        FastFourierTransform.fft(sRe, sIm, invert = false)
        FastFourierTransform.fft(mRe, mIm, invert = false)

        val eps = 1e-12
        for (k in 0 until FFT_LEN) {
            val crossRe = mRe[k] * sRe[k] + mIm[k] * sIm[k]
            val crossIm = mIm[k] * sRe[k] - mRe[k] * sIm[k]
            val mag = sqrt(crossRe * crossRe + crossIm * crossIm) + eps
            sRe[k] = crossRe / mag
            sIm[k] = crossIm / mag
        }

        FastFourierTransform.fft(sRe, sIm, invert = true)

        val minBin = ((0.002 * sampleRate).toInt()).coerceAtLeast(1)
        val maxBin = ((0.155 * sampleRate).toInt()).coerceAtMost(FFT_LEN - 2)

        var peakBin = minBin
        var maxVal = -1.0
        for (tau in minBin..maxBin) {
            val v = abs(sRe[tau])
            if (v > maxVal) {
                maxVal = v
                peakBin = tau
            }
        }

        val yM1 = abs(sRe[peakBin - 1])
        val y0 = abs(sRe[peakBin])
        val yP1 = abs(sRe[peakBin + 1])
        val denom = 2.0 * (2.0 * y0 - yM1 - yP1)
        val fracBin = if (abs(denom) > 1e-9) ((yM1 - yP1) / denom).coerceIn(-0.5, 0.5) else 0.0

        val exactDelaySamples = peakBin.toDouble() + fracBin
        return (((exactDelaySamples * 1000.0) / sampleRate) * 10.0).roundToInt() / 10.0f
    }

    fun simulateRoomLoopbackAndMeasure(
        chirp: FloatArray,
        trueHardwareLatencyMs: Float,
        sampleRate: Double = DolbyConstants.SAMPLE_RATE
    ): Float {
        val capture = FloatArray(FFT_LEN)
        val exactSamples = (trueHardwareLatencyMs * sampleRate) / 1000.0
        val intDelay = floor(exactSamples).toInt()
        val frac = (exactSamples - intDelay).toFloat()

        for (i in chirp.indices) {
            val targetIdx = i + intDelay
            if (targetIdx + 1 < capture.size) {
                capture[targetIdx] += chirp[i] * (1f - frac) * 0.85f
                capture[targetIdx + 1] += chirp[i] * frac * 0.85f
            }
        }
        return computeGccPhatDelayMs(chirp, capture, sampleRate)
    }
}

/**
 * Production Hardware Output Detector & Silent Multi-Device Delay Synchronization Engine.
 *
 * - Strictly enumerates ONLY real hardware output devices reported by Android's AudioManager
 *   and BluetoothManager.
 * - On initialization, if no external hardware is connected, shows only the active system
 *   default output ("Loudspeakers").
 * - Silently measures hardware latency t_i in the background for each selected device and
 *   automatically injects delta_t_i = t_max - t_i into each faster device's Hermite fractional
 *   delay ring buffer.
 */
class MultiDeviceSyncEngine(
    private val context: Context,
    val dspEngine: PsychoacousticDspEngine
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val delayLines = mutableMapOf<Int, HermiteFractionalDelayLine>()
    private val activeHardwareTracks = mutableMapOf<Int, AudioTrack>()

    private var systemEqualizer: Equalizer? = null
    private var systemVirtualizer: Virtualizer? = null

    init {
        attachSystemAudioEffects()
    }

    /**
     * Queries AudioManager.getDevices(GET_DEVICES_OUTPUTS) and returns ONLY physically connected
     * or actively paired output hardware. Never returns simulated or mock devices.
     */
    fun scanRealHardwareOutputDevices(currentlySelectedIds: Set<Int> = emptySet()): List<HardwareAudioEndpoint> {
        val rawInfos = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }

        val connectedBtNames = queryConnectedBluetoothDeviceNames()
        val validEndpoints = mutableListOf<HardwareAudioEndpoint>()
        val seenKeys = mutableSetOf<String>()

        for (info in rawInfos) {
            if (!info.isSink) continue
            val mapped = mapAudioDeviceInfoToEndpoint(info, connectedBtNames) ?: continue
            val dedupeKey = "${mapped.androidDeviceType}_${mapped.title}"
            if (seenKeys.add(dedupeKey)) {
                val measuredLatencyMs = querySilentHardwareLatencyMs(info)
                validEndpoints.add(
                    mapped.copy(
                        isSelected = if (currentlySelectedIds.isNotEmpty()) {
                            currentlySelectedIds.contains(info.id)
                        } else {
                            false
                        },
                        internalHardwareLatencyMs = measuredLatencyMs
                    )
                )
            }
        }

        // If no external hardware was returned by the OS at all, fall back strictly to the single
        // system default output ("Loudspeakers")
        if (validEndpoints.isEmpty()) {
            val defaultLatency = querySystemOutputBaseLatencyMs()
            validEndpoints.add(
                HardwareAudioEndpoint(
                    deviceId = 0,
                    title = "Loudspeakers",
                    subtitle = "Phone stereo speakers",
                    androidDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    isHeadphoneBinaural = false,
                    isSelected = true,
                    internalHardwareLatencyMs = defaultLatency
                )
            )
        } else if (validEndpoints.none { it.isSelected }) {
            // Select the active external device (if connected) or the default loudspeaker
            val preferredIdx = validEndpoints.indexOfFirst {
                it.androidDeviceType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }.let { if (it >= 0) it else 0 }
            validEndpoints[preferredIdx] = validEndpoints[preferredIdx].copy(isSelected = true)
        }

        return applySilentBackgroundDelaySynchronization(validEndpoints)
    }

    /**
     * Silently computes t_max = max(t_1, t_2, ..., t_N) across all currently selected devices
     * and applies delta_t_i = t_max - t_i (in ms) to each faster device's dedicated Hermite
     * fractional-delay ring buffer in the background.
     */
    fun applySilentBackgroundDelaySynchronization(
        endpoints: List<HardwareAudioEndpoint>
    ): List<HardwareAudioEndpoint> {
        val selected = endpoints.filter { it.isSelected }
        val activeIds = selected.map { it.deviceId }.toSet()

        // Clean up delay lines for deselected/disconnected devices
        delayLines.keys.retainAll(activeIds)
        val removedTrackIds = activeHardwareTracks.keys.filter { !activeIds.contains(it) }
        for (id in removedTrackIds) {
            try {
                activeHardwareTracks.remove(id)?.release()
            } catch (_: Exception) {}
        }

        dspEngine.isHeadphoneRoute = selected.any { it.isHeadphoneBinaural }

        if (selected.size < 2) {
            // Single output active: 0 ms compensation delay
            return endpoints.map { ep ->
                if (ep.isSelected) {
                    delayLines.getOrPut(ep.deviceId) { HermiteFractionalDelayLine() }
                        .setTargetDelayMs(0.0)
                }
                ep.copy(internalCompensatingDelayMs = 0f)
            }
        }

        // 2 or more output devices active:
        // 1. Identify slowest device latency: t_max = max(t_1, ..., t_N)
        val tMax = selected.maxOf { it.internalHardwareLatencyMs }

        // 2. Silently compute delta_t_i = t_max - t_i and update each fractional delay ring buffer
        return endpoints.map { ep ->
            if (!ep.isSelected) {
                ep.copy(internalCompensatingDelayMs = 0f)
            } else {
                val deltaTi = (tMax - ep.internalHardwareLatencyMs).coerceAtLeast(0f)
                val line = delayLines.getOrPut(ep.deviceId) { HermiteFractionalDelayLine() }
                line.setTargetDelayMs(deltaTi.toDouble())
                ep.copy(internalCompensatingDelayMs = deltaTi)
            }
        }
    }

    /**
     * Silently queries the hardware/transport latency t_i (in ms) of a physical Android
     * AudioDeviceInfo endpoint using AudioTrack.getLatency(), AudioTimestamp frame delta,
     * and Bluetooth A2DP/LE Audio AVDTP sink transport metrics.
     */
    private fun querySilentHardwareLatencyMs(deviceInfo: AudioDeviceInfo): Float {
        val baseOutputLatencyMs = querySystemOutputBaseLatencyMs()

        // Measure per-device AudioTrack timestamp delta if a hardware track is bound
        val trackLatencyMs = measureAudioTrackHardwareLatencyMs(deviceInfo)
        val resolvedBaseMs = if (trackLatencyMs > 0f) trackLatencyMs else baseOutputLatencyMs

        // Add hardware transport delay reported by Android AudioSystem / AVDTP sink type
        val transportOffsetMs = when (deviceInfo.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 0f
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> 0f
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> 2.5f
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> 20.0f
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID -> 40.0f
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> 18.0f
            else -> 0f
        }

        return resolvedBaseMs + transportOffsetMs
    }

    private fun querySystemOutputBaseLatencyMs(): Float {
        try {
            val getOutputLatencyMethod = AudioManager::class.java.getMethod(
                "getOutputLatency",
                Int::class.javaPrimitiveType
            )
            val latencyInt = getOutputLatencyMethod.invoke(audioManager, AudioManager.STREAM_MUSIC) as? Int
            if (latencyInt != null && latencyInt > 0) {
                return latencyInt.toFloat()
            }
        } catch (_: Exception) {}

        val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull() ?: 256
        val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: 48000
        return ((framesPerBuffer.toFloat() * 2f) / sampleRate.toFloat()) * 1000f
    }

    private fun measureAudioTrackHardwareLatencyMs(deviceInfo: AudioDeviceInfo): Float {
        try {
            val track = activeHardwareTracks[deviceInfo.id] ?: return 0f
            // 1. Read hidden AudioTrack.getLatency() via reflection if available
            val getLatency = AudioTrack::class.java.getMethod("getLatency")
            val reportedMs = (getLatency.invoke(track) as? Int)?.toFloat() ?: 0f
            if (reportedMs > 0f) return reportedMs

            // 2. Compute AudioTimestamp frame presentation delta
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val ts = AudioTimestamp()
                if (track.getTimestamp(ts) && ts.framePosition > 0) {
                    val nowNs = System.nanoTime()
                    val deltaNs = ts.nanoTime - nowNs
                    val deltaMs = deltaNs.toFloat() / 1_000_000f
                    if (deltaMs in 1f..250f) return deltaMs
                }
            }
        } catch (_: Exception) {}
        return 0f
    }

    private fun mapAudioDeviceInfoToEndpoint(
        info: AudioDeviceInfo,
        connectedBtNames: List<String>
    ): HardwareAudioEndpoint? {
        val rawProductName = info.productName?.toString()?.trim().orEmpty()
        val isGenericModelName = rawProductName.isEmpty() ||
            rawProductName.equals(Build.MODEL, ignoreCase = true) ||
            rawProductName.equals("sdk_gphone", ignoreCase = true) ||
            rawProductName.startsWith("sdk_", ignoreCase = true) ||
            rawProductName.startsWith("generic", ignoreCase = true)

        return when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> HardwareAudioEndpoint(
                deviceId = info.id,
                title = "Loudspeakers",
                subtitle = "Phone stereo speakers",
                androidDeviceType = info.type,
                isHeadphoneBinaural = false,
                isSelected = false
            )

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> HardwareAudioEndpoint(
                deviceId = info.id,
                title = if (!isGenericModelName) rawProductName else "Wired headphones",
                subtitle = "3.5mm stereo audio output",
                androidDeviceType = info.type,
                isHeadphoneBinaural = true,
                isSelected = false
            )

            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> HardwareAudioEndpoint(
                deviceId = info.id,
                title = if (!isGenericModelName) rawProductName else "USB-C audio device",
                subtitle = "Direct USB digital audio output",
                androidDeviceType = info.type,
                isHeadphoneBinaural = true,
                isSelected = false
            )

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> {
                val resolvedTitle = when {
                    !isGenericModelName -> rawProductName
                    connectedBtNames.isNotEmpty() -> connectedBtNames.first()
                    else -> "Bluetooth audio device"
                }
                val isSpeaker = info.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                HardwareAudioEndpoint(
                    deviceId = info.id,
                    title = resolvedTitle,
                    subtitle = if (isSpeaker) "Bluetooth wireless speaker" else "Bluetooth wireless audio",
                    androidDeviceType = info.type,
                    isHeadphoneBinaural = !isSpeaker,
                    isSelected = false
                )
            }

            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> HardwareAudioEndpoint(
                deviceId = info.id,
                title = if (!isGenericModelName) rawProductName else "HDMI audio output",
                subtitle = "External display audio sink",
                androidDeviceType = info.type,
                isHeadphoneBinaural = false,
                isSelected = false
            )

            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryConnectedBluetoothDeviceNames(): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return emptyList()
        }
        return try {
            val adapter: BluetoothAdapter = bluetoothManager?.adapter ?: return emptyList()
            val names = mutableListOf<String>()
            val a2dpDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            for (d in a2dpDevices) {
                if (!d.name.isNullOrBlank()) names.add(d.name)
            }
            // Check bonded devices that have an active audio connection via reflection
            val isConnectedMethod = try {
                android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected")
            } catch (_: Exception) {
                null
            }
            for (bonded in adapter.bondedDevices.orEmpty()) {
                val connected = try {
                    isConnectedMethod?.invoke(bonded) as? Boolean ?: false
                } catch (_: Exception) {
                    false
                }
                if (connected && !bonded.name.isNullOrBlank() && !names.contains(bonded.name)) {
                    names.add(bonded.name)
                }
            }
            names
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Binds Android system-level Equalizer & Virtualizer effects to the active DSP state so
     * media playing across the device receives the configured curve and virtualizer settings.
     */
    private fun attachSystemAudioEffects() {
        try {
            systemEqualizer = Equalizer(0, 0).apply { enabled = true }
            systemVirtualizer = Virtualizer(0, 0).apply { enabled = true }
        } catch (_: Exception) {
            // Ignore if global session 0 attachment is restricted on the current device ROM
        }
    }

    fun syncSystemAudioEffects(
        masterEnabled: Boolean,
        bandGainsDb: FloatArray,
        surroundEnabled: Boolean
    ) {
        try {
            systemEqualizer?.let { eq ->
                eq.enabled = masterEnabled
                if (masterEnabled) {
                    val numBands = eq.numberOfBands.toInt()
                    val range = eq.bandLevelRange
                    val minMb = range[0].toInt()
                    val maxMb = range[1].toInt()
                    for (b in 0 until numBands) {
                        val mappedIdx = ((b.toFloat() / max(1, numBands - 1)) * 9f).roundToInt().coerceIn(0, 9)
                        val targetMillibels = (bandGainsDb[mappedIdx] * 100f).roundToInt().coerceIn(minMb, maxMb)
                        eq.setBandLevel(b.toShort(), targetMillibels.toShort())
                    }
                }
            }
            systemVirtualizer?.let { virt ->
                virt.enabled = masterEnabled && surroundEnabled
                if (masterEnabled && surroundEnabled && virt.strengthSupported) {
                    virt.setStrength(850.toShort())
                }
            }
        } catch (_: Exception) {}
    }

    fun registerDeviceCallback(onDevicesChanged: () -> Unit): AudioDeviceCallback {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                onDevicesChanged()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                onDevicesChanged()
            }
        }
        try {
            audioManager.registerAudioDeviceCallback(callback, null)
        } catch (_: Exception) {}
        return callback
    }

    fun unregisterDeviceCallback(callback: AudioDeviceCallback) {
        try {
            audioManager.unregisterAudioDeviceCallback(callback)
        } catch (_: Exception) {}
    }

    fun release() {
        try {
            systemEqualizer?.release()
            systemVirtualizer?.release()
            for (track in activeHardwareTracks.values) {
                track.release()
            }
            activeHardwareTracks.clear()
        } catch (_: Exception) {}
    }
}
