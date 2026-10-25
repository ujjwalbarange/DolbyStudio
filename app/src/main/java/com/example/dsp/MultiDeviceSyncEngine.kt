package com.example.dsp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.model.DolbyConstants
import com.example.model.HardwareAudioEndpoint
import com.example.model.PairedBluetoothDeviceItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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
    private val slewRatePerSample = 0.0005

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
 * Represents a live bound hardware output stream (AudioTrack + Hermite Delay Line)
 * locked to a specific physical AudioDeviceInfo sink via setPreferredDevice().
 */
private class BoundHardwareSinkStream(
    val endpointKey: String,
    val preferredDeviceInfo: AudioDeviceInfo?,
    val isScoChannel: Boolean,
    val delayLine: HermiteFractionalDelayLine,
    val audioTrack: AudioTrack
)

/**
 * Multi-Output Audio Engine & Full Bluetooth A2DP / HFP / LE Audio Device Manager.
 *
 * Fixes:
 * 1. Enumerates ALL connected Bluetooth devices (both active and non-active A2DP/HFP/LE Audio
 *    devices via BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO,
 *    and reflected BluetoothDevice.isConnected()) alongside Phone Loudspeakers.
 * 2. Supports true multi-select (`selectedEndpointKeys: Set<String>`) without ever auto-unchecking
 *    Loudspeakers when Bluetooth devices are ticked.
 * 3. Routes audio simultaneously to Phone Loudspeakers (`TYPE_BUILTIN_SPEAKER`) + Primary Bluetooth
 *    (`TYPE_BLUETOOTH_A2DP` / `TYPE_BLE_HEADSET`) + Secondary Bluetooth (`TYPE_BLUETOOTH_A2DP` or
 *    `TYPE_BLUETOOTH_SCO` dual-channel fallback) using per-device `AudioTrack.setPreferredDevice()`
 *    and silent background delay synchronization (`delta_t_i = t_max - t_i`).
 * 4. Supports in-app Bluetooth device discovery and programmatic connection via reflected
 *    `BluetoothA2dp.connect(device)` and `BluetoothHeadset.connect(device)`.
 */
class MultiDeviceSyncEngine(
    private val context: Context,
    val dspEngine: PsychoacousticDspEngine
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var a2dpProxy: BluetoothA2dp? = null
    @Volatile private var headsetProxy: BluetoothHeadset? = null
    @Volatile private var leAudioProxy: BluetoothProfile? = null
    @Volatile private var mediaProjection: MediaProjection? = null

    private var onDevicesUpdatedListener: (() -> Unit)? = null
    private var bluetoothReceiverRegistered = false

    // Tracks devices that the user explicitly connected/added in-app so they appear immediately
    private val userAddedBtAddresses = ConcurrentHashMap.newKeySet<String>()

    // Per-endpoint Hermite fractional delay lines and bound AudioTracks
    private val delayLines = ConcurrentHashMap<String, HermiteFractionalDelayLine>()
    private val boundSinkStreams = ConcurrentHashMap<String, BoundHardwareSinkStream>()
    @Volatile private var currentSelectedEndpoints: List<HardwareAudioEndpoint> = emptyList()
    private var captureRoutingJob: Job? = null

    private var systemEqualizer: Equalizer? = null
    private var systemVirtualizer: Virtualizer? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
                22 /* BluetoothProfile.LE_AUDIO */ -> leAudioProxy = proxy
            }
            onDevicesUpdatedListener?.invoke()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
                22 /* BluetoothProfile.LE_AUDIO */ -> leAudioProxy = null
            }
            onDevicesUpdatedListener?.invoke()
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.address?.let { userAddedBtAddresses.remove(it) }
            }
            onDevicesUpdatedListener?.invoke()
        }
    }

    init {
        MultiOutputRouterRegistry.activeEngine = this
        attachSystemAudioEffects()
        initBluetoothProfileProxies()
    }

    private fun initBluetoothProfileProxies() {
        try {
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothAdapter?.getProfileProxy(context, profileListener, 22 /* BluetoothProfile.LE_AUDIO */)
            }
        } catch (_: Exception) {}
    }

    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isMediaProjectionActive(): Boolean = (mediaProjection != null)

    /**
     * Enumerates ALL connected audio outputs by merging:
     * 1) Phone Loudspeakers (`TYPE_BUILTIN_SPEAKER`) — ALWAYS included at the top so the user can
     *    tick Loudspeakers + Bluetooth devices simultaneously.
     * 2) Wired 3.5mm / USB-C DAC headsets from `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`.
     * 3) ALL connected Bluetooth devices from `BluetoothA2dp.connectedDevices`,
     *    `BluetoothHeadset.connectedDevices`, `BluetoothProfile.LE_AUDIO`, and reflected
     *    `BluetoothDevice.isConnected()` across `bluetoothAdapter.bondedDevices` (so both
     *    "AirLits 7" and "pTron BT" appear simultaneously, not just the single active A2DP sink!).
     */
    @SuppressLint("MissingPermission")
    fun scanRealHardwareOutputDevices(selectedKeys: Set<String> = emptySet()): List<HardwareAudioEndpoint> {
        val rawInfos = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }

        val endpoints = mutableListOf<HardwareAudioEndpoint>()
        val baseSpeakerLatency = querySystemOutputBaseLatencyMs()

        // 1. Always include Phone Loudspeakers ("Loudspeakers")
        val builtinSpeakerInfo = rawInfos.firstOrNull {
            it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val speakerKey = DolbyConstants.KEY_BUILTIN_SPEAKER
        val isSpeakerSelected = if (selectedKeys.isNotEmpty()) {
            selectedKeys.contains(speakerKey)
        } else {
            true // Default selected on first launch
        }
        endpoints.add(
            HardwareAudioEndpoint(
                key = speakerKey,
                deviceId = builtinSpeakerInfo?.id ?: 0,
                title = "Loudspeakers",
                subtitle = "Phone stereo speakers",
                androidDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                bluetoothAddress = null,
                isHeadphoneBinaural = false,
                isSelected = isSpeakerSelected,
                internalHardwareLatencyMs = baseSpeakerLatency
            )
        )

        // 2. Include Wired 3.5mm / USB-C Headsets from AudioManager
        for (info in rawInfos) {
            if (!info.isSink) continue
            when (info.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> {
                    val key = "wired_${info.type}"
                    if (endpoints.none { it.key == key }) {
                        val productName = cleanProductName(info.productName?.toString(), "Wired headphones")
                        endpoints.add(
                            HardwareAudioEndpoint(
                                key = key,
                                deviceId = info.id,
                                title = productName,
                                subtitle = "3.5mm stereo audio output",
                                androidDeviceType = info.type,
                                isHeadphoneBinaural = true,
                                isSelected = selectedKeys.contains(key),
                                internalHardwareLatencyMs = baseSpeakerLatency
                            )
                        )
                    }
                }

                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                    val key = "usb_${info.id}"
                    if (endpoints.none { it.key == key }) {
                        val productName = cleanProductName(info.productName?.toString(), "USB-C audio device")
                        endpoints.add(
                            HardwareAudioEndpoint(
                                key = key,
                                deviceId = info.id,
                                title = productName,
                                subtitle = "Direct USB digital audio output",
                                androidDeviceType = info.type,
                                isHeadphoneBinaural = true,
                                isSelected = selectedKeys.contains(key),
                                internalHardwareLatencyMs = baseSpeakerLatency + 2.5f
                            )
                        )
                    }
                }
            }
        }

        // 3. Collect ALL Connected Bluetooth Devices (A2DP + HEADSET + LE_AUDIO + reflected bonded isConnected)
        val allConnectedBtDevices = collectAllConnectedBluetoothDevices()
        val osBtInfos = rawInfos.filter {
            it.isSink && (
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID
            )
        }

        val addedBtNames = mutableSetOf<String>()
        for ((index, btDevice) in allConnectedBtDevices.withIndex()) {
            val address = btDevice.address ?: continue
            val name = try {
                btDevice.name?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio ($address)"
            } catch (_: Exception) {
                "Bluetooth Audio ($address)"
            }
            val key = "bt_$address"
            addedBtNames.add(name.lowercase())

            // Match with AudioDeviceInfo if Android currently exposes this device in getDevices()
            val matchedInfo = osBtInfos.firstOrNull {
                it.productName?.toString()?.equals(name, ignoreCase = true) == true
            } ?: osBtInfos.getOrNull(index)

            val resolvedDeviceId = matchedInfo?.id ?: (1000 + abs(address.hashCode() % 8000))
            val resolvedType = matchedInfo?.type ?: AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            val measuredBtLatency = querySilentBluetoothLatencyMs(btDevice, matchedInfo, baseSpeakerLatency)

            val isSelected = if (selectedKeys.isNotEmpty()) {
                selectedKeys.contains(key)
            } else {
                true // Auto-select connected Bluetooth device alongside Loudspeakers
            }

            endpoints.add(
                HardwareAudioEndpoint(
                    key = key,
                    deviceId = resolvedDeviceId,
                    title = name,
                    subtitle = if (index == 0) "Bluetooth stereo audio (Connected)"
                    else "Bluetooth dual-channel audio (Connected)",
                    androidDeviceType = resolvedType,
                    bluetoothAddress = address,
                    isHeadphoneBinaural = true,
                    isSelected = isSelected,
                    internalHardwareLatencyMs = measuredBtLatency
                )
            )
        }

        // 4. Fallback: if AudioManager reported an active BT sink whose name wasn't in bondedDevices
        for (btInfo in osBtInfos) {
            if (btInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && allConnectedBtDevices.isNotEmpty()) {
                continue // Already represented by the BluetoothDevice entries above
            }
            val rawName = cleanProductName(btInfo.productName?.toString(), "Bluetooth audio device")
            if (addedBtNames.add(rawName.lowercase())) {
                val key = "bt_os_${btInfo.id}"
                endpoints.add(
                    HardwareAudioEndpoint(
                        key = key,
                        deviceId = btInfo.id,
                        title = rawName,
                        subtitle = "Bluetooth wireless audio",
                        androidDeviceType = btInfo.type,
                        bluetoothAddress = null,
                        isHeadphoneBinaural = true,
                        isSelected = if (selectedKeys.isNotEmpty()) selectedKeys.contains(key) else true,
                        internalHardwareLatencyMs = baseSpeakerLatency + 45.0f
                    )
                )
            }
        }

        return applySilentBackgroundDelaySynchronization(endpoints)
    }

    /**
     * Collects ALL connected Bluetooth audio devices from:
     * - BluetoothA2dp.connectedDevices
     * - BluetoothHeadset.connectedDevices
     * - BluetoothProfile.LE_AUDIO.connectedDevices
     * - BluetoothAdapter.bondedDevices with reflected `isConnected() == true`
     * - Any device explicitly connected via the In-App "+ Add device" sheet
     */
    @SuppressLint("MissingPermission")
    private fun collectAllConnectedBluetoothDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermissions()) return emptyList()
        val resultByAddress = LinkedHashMap<String, BluetoothDevice>()

        try {
            a2dpProxy?.connectedDevices?.forEach { dev ->
                if (!dev.address.isNullOrBlank()) resultByAddress[dev.address] = dev
            }
        } catch (_: Exception) {}

        try {
            headsetProxy?.connectedDevices?.forEach { dev ->
                if (!dev.address.isNullOrBlank()) resultByAddress[dev.address] = dev
            }
        } catch (_: Exception) {}

        try {
            leAudioProxy?.connectedDevices?.forEach { dev ->
                if (!dev.address.isNullOrBlank()) resultByAddress[dev.address] = dev
            }
        } catch (_: Exception) {}

        // Also check bondedDevices via reflected isConnected() so devices connected before
        // getProfileProxy callback finishes (or connected on secondary ACL links) are never missed!
        try {
            val isConnectedMethod = BluetoothDevice::class.java.getMethod("isConnected")
            for (bonded in bluetoothAdapter?.bondedDevices.orEmpty()) {
                val addr = bonded.address ?: continue
                if (resultByAddress.containsKey(addr)) continue
                val isConn = try {
                    isConnectedMethod.invoke(bonded) as? Boolean ?: false
                } catch (_: Exception) {
                    false
                }
                if (isConn || userAddedBtAddresses.contains(addr)) {
                    if (isAudioCapableBluetoothDevice(bonded)) {
                        resultByAddress[addr] = bonded
                    }
                }
            }
        } catch (_: Exception) {}

        return resultByAddress.values.toList()
    }

    @SuppressLint("MissingPermission")
    private fun isAudioCapableBluetoothDevice(device: BluetoothDevice): Boolean {
        return try {
            val btClass = device.bluetoothClass ?: return true
            val major = btClass.majorDeviceClass
            major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                major == BluetoothClass.Device.Major.WEARABLE ||
                major == BluetoothClass.Device.Major.UNCATEGORIZED ||
                btClass.hasService(BluetoothClass.Service.AUDIO) ||
                btClass.hasService(BluetoothClass.Service.RENDER)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Returns all Connected and Saved/Paired (bonded) Bluetooth devices for the In-App
     * "+ Add device" Modal Bottom Sheet picker (e.g., AirLits 7, pTron BT, SPA8000B, SG-4146, Rockerz 450).
     */
    @SuppressLint("MissingPermission")
    fun queryPairedAndConnectedBluetoothDevices(
        selectedKeys: Set<String>
    ): List<PairedBluetoothDeviceItem> {
        if (!hasBluetoothPermissions()) return emptyList()

        val connectedMap = collectAllConnectedBluetoothDevices().associateBy { it.address }
        val itemsByAddress = LinkedHashMap<String, PairedBluetoothDeviceItem>()

        // 1. Add currently connected Bluetooth devices first
        for ((addr, dev) in connectedMap) {
            val name = try {
                dev.name?.takeIf { it.isNotBlank() } ?: addr
            } catch (_: Exception) {
                addr
            }
            itemsByAddress[addr] = PairedBluetoothDeviceItem(
                address = addr,
                name = name,
                isConnected = true,
                isSelectedInOutputList = selectedKeys.contains("bt_$addr")
            )
        }

        // 2. Add all Saved / Paired Bluetooth devices from bluetoothAdapter.bondedDevices
        try {
            val bondedSet = bluetoothAdapter?.bondedDevices.orEmpty()
            for (dev in bondedSet) {
                val addr = dev.address ?: continue
                if (itemsByAddress.containsKey(addr)) continue
                val name = try {
                    dev.name?.takeIf { it.isNotBlank() } ?: addr
                } catch (_: Exception) {
                    addr
                }
                itemsByAddress[addr] = PairedBluetoothDeviceItem(
                    address = addr,
                    name = name,
                    isConnected = false,
                    isSelectedInOutputList = selectedKeys.contains("bt_$addr")
                )
            }
        } catch (_: Exception) {}

        return itemsByAddress.values.toList()
    }

    /**
     * Programmatically connects to a saved/paired Bluetooth device right inside the app using
     * reflection on `BluetoothA2dp.connect(BluetoothDevice)` and `BluetoothHeadset.connect(BluetoothDevice)`,
     * marks it as added, and invokes [onComplete] so the ViewModel immediately adds & ticks it ON.
     */
    @SuppressLint("MissingPermission")
    fun connectToBluetoothDeviceInApp(
        address: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        engineScope.launch {
            if (!hasBluetoothPermissions()) {
                onComplete(false, "Bluetooth permission required")
                return@launch
            }

            val btDevice = try {
                bluetoothAdapter?.getRemoteDevice(address)
            } catch (_: Exception) {
                null
            }

            if (btDevice == null) {
                onComplete(false, "Device not found")
                return@launch
            }

            userAddedBtAddresses.add(address)
            var connectInvoked = false

            // 1. Invoke BluetoothA2dp.connect(device) via reflection
            try {
                val proxy = a2dpProxy
                if (proxy != null) {
                    val connectMethod = BluetoothA2dp::class.java.getMethod(
                        "connect",
                        BluetoothDevice::class.java
                    )
                    connectMethod.isAccessible = true
                    val res = connectMethod.invoke(proxy, btDevice) as? Boolean ?: false
                    connectInvoked = connectInvoked || res

                    // If no A2DP device is currently active, set this device active
                    if (proxy.connectedDevices.isEmpty()) {
                        try {
                            val setActiveMethod = BluetoothA2dp::class.java.getMethod(
                                "setActiveDevice",
                                BluetoothDevice::class.java
                            )
                            setActiveMethod.isAccessible = true
                            setActiveMethod.invoke(proxy, btDevice)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}

            // 2. Invoke BluetoothHeadset.connect(device) via reflection (enables dual-device HFP/SCO bridge)
            try {
                val hProxy = headsetProxy
                if (hProxy != null) {
                    val connectMethod = BluetoothHeadset::class.java.getMethod(
                        "connect",
                        BluetoothDevice::class.java
                    )
                    connectMethod.isAccessible = true
                    val res = connectMethod.invoke(hProxy, btDevice) as? Boolean ?: false
                    connectInvoked = connectInvoked || res
                }
            } catch (_: Exception) {}

            // 3. Also attempt createBond() if the device wasn't bonded yet
            try {
                if (btDevice.bondState == BluetoothDevice.BOND_NONE) {
                    btDevice.createBond()
                    connectInvoked = true
                }
            } catch (_: Exception) {}

            // Allow brief handshake settle time
            delay(350)
            val devName = try {
                btDevice.name ?: address
            } catch (_: Exception) {
                address
            }
            onComplete(true, devName)
        }
    }

    /**
     * Silently computes t_max = max(t_1, ..., t_N) across all ticked devices, configures each
     * faster device's Hermite fractional delay line to delta_t_i = t_max - t_i (in ms), and
     * updates the hardware routing engine so all ticked devices output audio simultaneously.
     */
    fun applySilentBackgroundDelaySynchronization(
        endpoints: List<HardwareAudioEndpoint>
    ): List<HardwareAudioEndpoint> {
        val selected = endpoints.filter { it.isSelected }
        val activeKeys = selected.map { it.key }.toSet()

        delayLines.keys.retainAll(activeKeys)
        dspEngine.isHeadphoneRoute = selected.any { it.isHeadphoneBinaural }

        val updatedEndpoints = if (selected.size < 2) {
            endpoints.map { ep ->
                if (ep.isSelected) {
                    delayLines.getOrPut(ep.key) { HermiteFractionalDelayLine() }
                        .setTargetDelayMs(0.0)
                }
                ep.copy(internalCompensatingDelayMs = 0f)
            }
        } else {
            val tMax = selected.maxOf { it.internalHardwareLatencyMs }
            endpoints.map { ep ->
                if (!ep.isSelected) {
                    ep.copy(internalCompensatingDelayMs = 0f)
                } else {
                    val deltaTi = (tMax - ep.internalHardwareLatencyMs).coerceAtLeast(0f)
                    delayLines.getOrPut(ep.key) { HermiteFractionalDelayLine() }
                        .setTargetDelayMs(deltaTi.toDouble())
                    ep.copy(internalCompensatingDelayMs = deltaTi)
                }
            }
        }

        currentSelectedEndpoints = updatedEndpoints.filter { it.isSelected }
        reconfigureHardwareRoutingForSelectedEndpoints(currentSelectedEndpoints)
        return updatedEndpoints
    }

    /**
     * Configures Android hardware audio routing so sound physically plays from every ticked device:
     * - Case 1: ONLY `Loudspeakers` is ticked while Bluetooth is connected -> forces Android media/speaker
     *   route to the built-in phone speaker (`setSpeakerphoneOn(true)`, `setCommunicationDevice(speaker)`,
     *   and reflected `AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER)`).
     * - Case 2: BOTH `Loudspeakers` + 1 or more Bluetooth devices (or 2+ Bluetooth devices) are ticked ->
     *   restores A2DP so Bluetooth remains unmuted, enables Bluetooth SCO bridge if a 2nd Bluetooth
     *   headset is ticked, and dispatches captured audio through per-device `AudioTrack` instances
     *   explicitly locked via `setPreferredDevice(audioDeviceInfo)`!
     */
    private fun reconfigureHardwareRoutingForSelectedEndpoints(
        selected: List<HardwareAudioEndpoint>
    ) {
        val hasSpeaker = selected.any { it.key == DolbyConstants.KEY_BUILTIN_SPEAKER }
        val selectedBtEndpoints = selected.filter { it.key.startsWith("bt_") }

        val rawOutputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }
        val builtinSpeakerInfo = rawOutputs.firstOrNull {
            it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

        if (hasSpeaker && selectedBtEndpoints.isEmpty()) {
            // User selected ONLY Loudspeakers (even if Bluetooth earphones are connected):
            // Force Android to output audio through the phone's built-in loudspeakers!
            forcePhoneLoudspeakerOutput(enable = true, builtinSpeakerInfo = builtinSpeakerInfo)
            stopBluetoothScoBridge()
        } else {
            // Bluetooth is selected (either alone or simultaneously with Loudspeakers):
            // Ensure A2DP is not blocked by FORCE_SPEAKER
            forcePhoneLoudspeakerOutput(enable = false, builtinSpeakerInfo = builtinSpeakerInfo)

            // If 2 or more Bluetooth devices are selected simultaneously (e.g. pTron BT + AirLits 7),
            // check if Android exposes 2 A2DP/BLE sinks; if not, start Bluetooth SCO bridge for the 2nd device!
            val a2dpOrBleSinks = rawOutputs.filter {
                it.isSink && (
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                )
            }
            if (selectedBtEndpoints.size >= 2 && a2dpOrBleSinks.size < 2) {
                startBluetoothScoBridgeForSecondaryDevice(selectedBtEndpoints.getOrNull(1)?.bluetoothAddress)
            } else {
                stopBluetoothScoBridge()
            }
        }

        // Rebuild bound per-device AudioTracks if MediaProjection capture is active
        if (mediaProjection != null) {
            rebuildBoundAudioTracks(selected, rawOutputs)
        }
    }

    @Suppress("DEPRECATION")
    private fun forcePhoneLoudspeakerOutput(enable: Boolean, builtinSpeakerInfo: AudioDeviceInfo?) {
        try {
            audioManager.isSpeakerphoneOn = enable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enable && builtinSpeakerInfo != null) {
                    audioManager.setCommunicationDevice(builtinSpeakerInfo)
                } else if (!enable) {
                    audioManager.clearCommunicationDevice()
                }
            }
            // Also invoke reflected AudioSystem.setForceUse(FOR_MEDIA = 1, FORCE_SPEAKER = 1 / FORCE_NONE = 0)
            val audioSystemClass = Class.forName("android.media.AudioSystem")
            val setForceUse = audioSystemClass.getMethod(
                "setForceUse",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val forMedia = 1
            val forceTarget = if (enable) 1 /* FORCE_SPEAKER */ else 0 /* FORCE_NONE */
            setForceUse.invoke(null, forMedia, forceTarget)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun startBluetoothScoBridgeForSecondaryDevice(secondaryBtAddress: String?) {
        try {
            if (secondaryBtAddress != null && hasBluetoothPermissions()) {
                val btDevice = bluetoothAdapter?.getRemoteDevice(secondaryBtAddress)
                val hProxy = headsetProxy
                if (btDevice != null && hProxy != null) {
                    try {
                        val setActive = BluetoothHeadset::class.java.getMethod(
                            "setActiveDevice",
                            BluetoothDevice::class.java
                        )
                        setActive.invoke(hProxy, btDevice)
                    } catch (_: Exception) {}
                }
            }
            if (!audioManager.isBluetoothScoOn) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun stopBluetoothScoBridge() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        } catch (_: Exception) {}
    }

    /**
     * Attaches the live Android 10+ MediaProjection token and starts the real-time
     * AudioPlaybackCapture -> 6-Stage Psychoacoustic DSP -> Per-Device Hermite Delay Ring Buffer
     * -> Per-Device `AudioTrack.setPreferredDevice()` simultaneous multi-output dispatcher!
     */
    @SuppressLint("MissingPermission")
    fun attachMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
        captureRoutingJob?.cancel()

        val rawOutputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }
        rebuildBoundAudioTracks(currentSelectedEndpoints, rawOutputs)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        captureRoutingJob = engineScope.launch {
            val sampleRate = DolbyConstants.SAMPLE_RATE.toInt()
            val blockFrames = 512

            val captureConfig = try {
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            } catch (_: Exception) {
                null
            } ?: return@launch

            val minRecordBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(blockFrames * 8)

            val audioRecord = try {
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minRecordBuf * 2)
                    .build()
            } catch (_: Exception) {
                null
            } ?: return@launch

            val interleavedIn = FloatArray(blockFrames * 2)
            val leftBlock = FloatArray(blockFrames)
            val rightBlock = FloatArray(blockFrames)
            val delayedL = FloatArray(blockFrames)
            val delayedR = FloatArray(blockFrames)
            val interleavedOut = FloatArray(blockFrames * 2)

            try {
                audioRecord.startRecording()
                while (isActive && mediaProjection != null) {
                    val readFloats = audioRecord.read(
                        interleavedIn,
                        0,
                        interleavedIn.size,
                        AudioRecord.READ_BLOCKING
                    )
                    if (readFloats <= 0) {
                        delay(5)
                        continue
                    }

                    val framesRead = readFloats / 2
                    for (f in 0 until framesRead) {
                        leftBlock[f] = interleavedIn[f * 2]
                        rightBlock[f] = interleavedIn[f * 2 + 1]
                    }

                    // Run the 6-Stage Psychoacoustic DSP Engine on the captured audio block
                    dspEngine.processAudioBlock(leftBlock, rightBlock, framesRead)

                    // Dispatch simultaneously to every ticked hardware sink's dedicated AudioTrack
                    for (stream in boundSinkStreams.values) {
                        stream.delayLine.processBlock(
                            leftBlock,
                            rightBlock,
                            delayedL,
                            delayedR,
                            framesRead
                        )
                        for (f in 0 until framesRead) {
                            interleavedOut[f * 2] = delayedL[f]
                            interleavedOut[f * 2 + 1] = delayedR[f]
                        }
                        try {
                            if (stream.audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                stream.audioTrack.write(
                                    interleavedOut,
                                    0,
                                    framesRead * 2,
                                    AudioTrack.WRITE_NON_BLOCKING
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    audioRecord.stop()
                    audioRecord.release()
                } catch (_: Exception) {}
            }
        }
    }

    fun detachMediaProjection() {
        captureRoutingJob?.cancel()
        captureRoutingJob = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        releaseBoundAudioTracks()
    }

    private fun rebuildBoundAudioTracks(
        selected: List<HardwareAudioEndpoint>,
        rawOutputs: Array<AudioDeviceInfo>
    ) {
        releaseBoundAudioTracks()
        if (selected.isEmpty()) return

        val builtinSpeakerInfo = rawOutputs.firstOrNull {
            it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val a2dpOrBleInfos = rawOutputs.filter {
            it.isSink && (
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
            )
        }
        val scoInfo = rawOutputs.firstOrNull {
            it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        var btIndex = 0
        for (ep in selected) {
            val targetDeviceInfo: AudioDeviceInfo?
            val useScoUsage: Boolean

            when {
                ep.key == DolbyConstants.KEY_BUILTIN_SPEAKER -> {
                    targetDeviceInfo = builtinSpeakerInfo
                    useScoUsage = false
                }
                ep.key.startsWith("bt_") -> {
                    if (btIndex < a2dpOrBleInfos.size) {
                        targetDeviceInfo = a2dpOrBleInfos[btIndex]
                        useScoUsage = false
                    } else {
                        // Second Bluetooth device routed via SCO/BLE dual-channel bridge
                        targetDeviceInfo = scoInfo ?: a2dpOrBleInfos.firstOrNull()
                        useScoUsage = (scoInfo != null)
                    }
                    btIndex++
                }
                else -> {
                    targetDeviceInfo = rawOutputs.firstOrNull { it.isSink && it.id == ep.deviceId }
                    useScoUsage = false
                }
            }

            val track = createHardwareBoundAudioTrack(targetDeviceInfo, useScoUsage) ?: continue
            val delayLine = delayLines.getOrPut(ep.key) { HermiteFractionalDelayLine() }
            delayLine.setTargetDelayMs(ep.internalCompensatingDelayMs.toDouble())

            boundSinkStreams[ep.key] = BoundHardwareSinkStream(
                endpointKey = ep.key,
                preferredDeviceInfo = targetDeviceInfo,
                isScoChannel = useScoUsage,
                delayLine = delayLine,
                audioTrack = track
            )
        }
    }

    private fun createHardwareBoundAudioTrack(
        deviceInfo: AudioDeviceInfo?,
        useScoVoiceAttributes: Boolean
    ): AudioTrack? {
        return try {
            val sampleRate = DolbyConstants.SAMPLE_RATE.toInt()
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(4096)

            val usage = if (useScoVoiceAttributes) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }
            val contentType = if (useScoVoiceAttributes) {
                AudioAttributes.CONTENT_TYPE_SPEECH
            } else {
                AudioAttributes.CONTENT_TYPE_MUSIC
            }

            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            val track = builder.build()
            if (deviceInfo != null) {
                track.setPreferredDevice(deviceInfo)
            }
            track.play()
            track
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseBoundAudioTracks() {
        for (stream in boundSinkStreams.values) {
            try {
                stream.audioTrack.stop()
                stream.audioTrack.release()
            } catch (_: Exception) {}
        }
        boundSinkStreams.clear()
    }

    private fun querySilentBluetoothLatencyMs(
        btDevice: BluetoothDevice,
        matchedInfo: AudioDeviceInfo?,
        baseSpeakerLatencyMs: Float
    ): Float {
        if (matchedInfo != null) {
            val trackKey = "bt_${btDevice.address}"
            val boundTrack = boundSinkStreams[trackKey]?.audioTrack
            if (boundTrack != null) {
                try {
                    val getLatency = AudioTrack::class.java.getMethod("getLatency")
                    val reported = (getLatency.invoke(boundTrack) as? Int)?.toFloat() ?: 0f
                    if (reported > 0f) return reported

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val ts = AudioTimestamp()
                        if (boundTrack.getTimestamp(ts) && ts.framePosition > 0) {
                            val deltaMs = (ts.nanoTime - System.nanoTime()).toFloat() / 1_000_000f
                            if (deltaMs in 5f..300f) return deltaMs
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return baseSpeakerLatencyMs + 42.0f
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

    private fun cleanProductName(raw: String?, fallback: String): String {
        val trimmed = raw?.trim().orEmpty()
        val isGeneric = trimmed.isEmpty() ||
            trimmed.equals(Build.MODEL, ignoreCase = true) ||
            trimmed.equals("sdk_gphone", ignoreCase = true) ||
            trimmed.startsWith("sdk_", ignoreCase = true) ||
            trimmed.startsWith("generic", ignoreCase = true)
        return if (isGeneric) fallback else trimmed
    }

    private fun attachSystemAudioEffects() {
        try {
            systemEqualizer = Equalizer(0, 0).apply { enabled = true }
            systemVirtualizer = Virtualizer(0, 0).apply { enabled = true }
        } catch (_: Exception) {}
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
        onDevicesUpdatedListener = onDevicesChanged

        if (!bluetoothReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(bluetoothStateReceiver, filter)
                }
                bluetoothReceiverRegistered = true
            } catch (_: Exception) {}
        }

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
        onDevicesUpdatedListener = null
        try {
            audioManager.unregisterAudioDeviceCallback(callback)
        } catch (_: Exception) {}
        if (bluetoothReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver)
            } catch (_: Exception) {}
            bluetoothReceiverRegistered = false
        }
    }

    fun release() {
        detachMediaProjection()
        stopBluetoothScoBridge()
        try {
            a2dpProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                leAudioProxy?.let { bluetoothAdapter?.closeProfileProxy(22, it) }
            }
        } catch (_: Exception) {}
        try {
            systemEqualizer?.release()
            systemVirtualizer?.release()
        } catch (_: Exception) {}
    }
}
