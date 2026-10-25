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
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.model.DolbyConstants
import com.example.model.HardwareAudioEndpoint
import com.example.model.PairedBluetoothDeviceItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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

private class BoundHardwareSinkStream(
    val endpointKey: String,
    val preferredDeviceInfo: AudioDeviceInfo?,
    val delayLine: HermiteFractionalDelayLine,
    val audioTrack: AudioTrack?
)

/**
 * Native Android System Audio Routing Engine (Mutually Exclusive Single-Device Switching).
 *
 * Architecture:
 * 1. Zero Screen-Casting / MediaProjection: Never prompts for screen recording or display capture.
 * 2. Mutually Exclusive Single-Device Switching:
 *    - Displays all currently available audio output sinks under "Loudspeakers":
 *      * Phone Speakers ("Loudspeakers")
 *      * Wired Headphones / USB DAC (when plugged in)
 *      * Actively Connected Bluetooth Headphones / Speakers (e.g., "pTron BT", "AirLits 7")
 *    - Routes audio natively via `AudioManager`:
 *      * When "Loudspeakers" is selected: calls `audioManager.clearCommunicationDevice()` and sets
 *        `AudioDeviceInfo.TYPE_BUILTIN_SPEAKER` via `setCommunicationDevice` / `setPreferredDevice`.
 *      * When a connected Bluetooth device is selected: sets that device active via
 *        `BluetoothA2dp.setActiveDevice(btDevice)`, `audioManager.setCommunicationDevice(btDeviceInfo)`,
 *        `MediaRouter2.transferTo(route)`, and `AudioTrack.setPreferredDevice(btDeviceInfo)`.
 *      * When Wired Headphones / USB DAC is selected: routes to `TYPE_WIRED_HEADSET`,
 *        `TYPE_WIRED_HEADPHONES`, or `TYPE_USB_HEADSET`.
 *    - Automatic Fallback on Disconnect: if a Bluetooth device disconnects or wired headphones are
 *      unplugged while active, automatically falls back to "Loudspeakers".
 */
class MultiDeviceSyncEngine(
    private val context: Context,
    val dspEngine: PsychoacousticDspEngine
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val routerExecutor = Executors.newSingleThreadExecutor()

    private var mediaRouter2: MediaRouter2? = null
    private var routeCallback: MediaRouter2.RouteCallback? = null
    private var transferCallback: MediaRouter2.TransferCallback? = null

    @Volatile private var a2dpProxy: BluetoothA2dp? = null
    @Volatile private var headsetProxy: BluetoothHeadset? = null
    @Volatile private var leAudioProxy: BluetoothProfile? = null

    private var onDevicesUpdatedListener: (() -> Unit)? = null
    private var bluetoothReceiverRegistered = false

    private val userAddedBtAddresses = ConcurrentHashMap.newKeySet<String>()

    private val delayLines = ConcurrentHashMap<String, HermiteFractionalDelayLine>()
    private val boundSinkStreams = ConcurrentHashMap<String, BoundHardwareSinkStream>()

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
            } else if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.address?.let { userAddedBtAddresses.remove(it) }
                }
            }
            onDevicesUpdatedListener?.invoke()
        }
    }

    init {
        attachSystemAudioEffects()
        initBluetoothProfileProxies()
        initMediaRouter2()
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

    private fun initMediaRouter2() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val router = MediaRouter2.getInstance(context)
                mediaRouter2 = router

                val preference = RouteDiscoveryPreference.Builder(
                    listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO),
                    true
                ).build()

                val rCallback = object : MediaRouter2.RouteCallback() {
                    override fun onRoutesAdded(routes: List<MediaRoute2Info>) {
                        onDevicesUpdatedListener?.invoke()
                    }
                    override fun onRoutesRemoved(routes: List<MediaRoute2Info>) {
                        onDevicesUpdatedListener?.invoke()
                    }
                    override fun onRoutesChanged(routes: List<MediaRoute2Info>) {
                        onDevicesUpdatedListener?.invoke()
                    }
                }
                val tCallback = object : MediaRouter2.TransferCallback() {
                    override fun onTransfer(
                        oldController: MediaRouter2.RoutingController,
                        newController: MediaRouter2.RoutingController
                    ) {
                        onDevicesUpdatedListener?.invoke()
                    }
                    override fun onTransferFailure(requestedRoute: MediaRoute2Info) {}
                }

                routeCallback = rCallback
                transferCallback = tCallback
                router.registerRouteCallback(routerExecutor, rCallback, preference)
                router.registerTransferCallback(routerExecutor, tCallback)
            } catch (_: Exception) {}
        }
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

    /**
     * Enumerates all currently available audio output sinks under "Loudspeakers":
     * 1) Phone Speakers ("Loudspeakers")
     * 2) Wired Headphones / USB DAC (when plugged in)
     * 3) Actively Connected Bluetooth Headphones / Speakers (e.g., "pTron BT", "AirLits 7")
     *
     * Enforces mutually exclusive single-device selection (`activeDeviceId`). If `activeDeviceId`
     * is null or its hardware device disconnected, automatically falls back to "Loudspeakers"
     * (`DolbyConstants.KEY_BUILTIN_SPEAKER`).
     */
    @SuppressLint("MissingPermission")
    fun scanRealHardwareOutputDevices(
        activeDeviceId: String? = null
    ): List<HardwareAudioEndpoint> {
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
        endpoints.add(
            HardwareAudioEndpoint(
                key = speakerKey,
                deviceId = builtinSpeakerInfo?.id ?: 0,
                title = "Loudspeakers",
                subtitle = "Phone stereo speakers",
                androidDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                bluetoothAddress = null,
                isHeadphoneBinaural = false,
                isSelected = false,
                internalHardwareLatencyMs = baseSpeakerLatency
            )
        )

        // 2. Include Wired 3.5mm / USB-C Headsets from AudioManager (when plugged in)
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
                                isSelected = false,
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
                                isSelected = false,
                                internalHardwareLatencyMs = baseSpeakerLatency + 2.5f
                            )
                        )
                    }
                }
            }
        }

        // 3. Collect ALL Actively Connected Bluetooth Devices
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

            val matchedInfo = osBtInfos.firstOrNull {
                it.productName?.toString()?.equals(name, ignoreCase = true) == true
            } ?: osBtInfos.getOrNull(index)

            val resolvedDeviceId = matchedInfo?.id ?: (1000 + abs(address.hashCode() % 8000))
            val resolvedType = matchedInfo?.type ?: AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            val measuredBtLatency = querySilentBluetoothLatencyMs(btDevice, matchedInfo, baseSpeakerLatency)

            endpoints.add(
                HardwareAudioEndpoint(
                    key = key,
                    deviceId = resolvedDeviceId,
                    title = name,
                    subtitle = "Bluetooth stereo audio (Connected)",
                    androidDeviceType = resolvedType,
                    bluetoothAddress = address,
                    isHeadphoneBinaural = true,
                    isSelected = false,
                    internalHardwareLatencyMs = measuredBtLatency
                )
            )
        }

        // 4. Fallback: if AudioManager reported an active BT sink whose name wasn't in bondedDevices
        for (btInfo in osBtInfos) {
            if (btInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && allConnectedBtDevices.isNotEmpty()) {
                continue
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
                        isSelected = false,
                        internalHardwareLatencyMs = baseSpeakerLatency + 45.0f
                    )
                )
            }
        }

        // Resolve single active device ID (automatic fallback to "Loudspeakers" if disconnected)
        val resolvedActiveKey = if (activeDeviceId != null && endpoints.any { it.key == activeDeviceId }) {
            activeDeviceId
        } else {
            DolbyConstants.KEY_BUILTIN_SPEAKER
        }

        val singleSelectedEndpoints = endpoints.map { ep ->
            ep.copy(
                isSelected = (ep.key == resolvedActiveKey),
                internalCompensatingDelayMs = 0f
            )
        }

        applyNativeSingleDeviceRouting(singleSelectedEndpoints)
        return singleSelectedEndpoints
    }

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

    @SuppressLint("MissingPermission")
    fun queryPairedAndConnectedBluetoothDevices(
        activeDeviceId: String
    ): List<PairedBluetoothDeviceItem> {
        if (!hasBluetoothPermissions()) return emptyList()

        val connectedMap = collectAllConnectedBluetoothDevices().associateBy { it.address }
        val itemsByAddress = LinkedHashMap<String, PairedBluetoothDeviceItem>()

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
                isSelectedInOutputList = (activeDeviceId == "bt_$addr")
            )
        }

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
                    isSelectedInOutputList = (activeDeviceId == "bt_$addr")
                )
            }
        } catch (_: Exception) {}

        return itemsByAddress.values.toList()
    }

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

            try {
                val proxy = a2dpProxy
                if (proxy != null) {
                    val connectMethod = BluetoothA2dp::class.java.getMethod(
                        "connect",
                        BluetoothDevice::class.java
                    )
                    connectMethod.isAccessible = true
                    connectMethod.invoke(proxy, btDevice)

                    try {
                        val setActiveMethod = BluetoothA2dp::class.java.getMethod(
                            "setActiveDevice",
                            BluetoothDevice::class.java
                        )
                        setActiveMethod.isAccessible = true
                        setActiveMethod.invoke(proxy, btDevice)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            try {
                val hProxy = headsetProxy
                if (hProxy != null) {
                    val connectMethod = BluetoothHeadset::class.java.getMethod(
                        "connect",
                        BluetoothDevice::class.java
                    )
                    connectMethod.isAccessible = true
                    connectMethod.invoke(hProxy, btDevice)
                }
            } catch (_: Exception) {}

            try {
                if (btDevice.bondState == BluetoothDevice.BOND_NONE) {
                    btDevice.createBond()
                }
            } catch (_: Exception) {}

            delay(300)
            val devName = try {
                btDevice.name ?: address
            } catch (_: Exception) {
                address
            }
            onComplete(true, devName)
        }
    }

    /**
     * Synchronizes delay lines and applies native Android routing.
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

        applyNativeSingleDeviceRouting(updatedEndpoints)
        return updatedEndpoints
    }

    /**
     * Native Android System Audio Routing for the single selected output device:
     * - "Loudspeakers": calls `audioManager.clearCommunicationDevice()` and sets communication
     *   device to `AudioDeviceInfo.TYPE_BUILTIN_SPEAKER` on Android 12+.
     * - Connected Bluetooth device: routes playback to `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET`,
     *   or `TYPE_BLE_SPEAKER` via `setCommunicationDevice`, `BluetoothA2dp.setActiveDevice`, and
     *   `MediaRouter2.transferTo`.
     * - Wired Headphones / USB Audio: routes playback to `TYPE_WIRED_HEADSET`,
     *   `TYPE_WIRED_HEADPHONES`, or `TYPE_USB_HEADSET`.
     */
    @SuppressLint("MissingPermission")
    fun applyNativeSingleDeviceRouting(
        endpoints: List<HardwareAudioEndpoint>
    ) {
        val activeEndpoint = endpoints.firstOrNull { it.isSelected }
            ?: endpoints.firstOrNull { it.key == DolbyConstants.KEY_BUILTIN_SPEAKER }
            ?: return

        dspEngine.isHeadphoneRoute = activeEndpoint.isHeadphoneBinaural

        val rawOutputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }

        val builtinSpeakerInfo = rawOutputs.firstOrNull {
            it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

        when {
            activeEndpoint.key == DolbyConstants.KEY_BUILTIN_SPEAKER -> {
                // Route to Phone Stereo Speakers ("Loudspeakers")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice()
                        if (builtinSpeakerInfo != null) {
                            audioManager.setCommunicationDevice(builtinSpeakerInfo)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                    }
                } catch (_: Exception) {}

                transferMediaRouter2ToBuiltInSpeaker()
                bindSingleAudioTrackToDevice(activeEndpoint.key, builtinSpeakerInfo)
            }

            activeEndpoint.key.startsWith("bt_") -> {
                // Route to the selected Connected Bluetooth Headphone / Speaker
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice()
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                    }
                } catch (_: Exception) {}

                val btAddress = activeEndpoint.bluetoothAddress
                if (btAddress != null && hasBluetoothPermissions()) {
                    try {
                        val btDevice = bluetoothAdapter?.getRemoteDevice(btAddress)
                        val proxy = a2dpProxy
                        if (btDevice != null && proxy != null) {
                            val setActiveMethod = BluetoothA2dp::class.java.getMethod(
                                "setActiveDevice",
                                BluetoothDevice::class.java
                            )
                            setActiveMethod.isAccessible = true
                            setActiveMethod.invoke(proxy, btDevice)
                        }
                    } catch (_: Exception) {}
                }

                val targetBtInfo = rawOutputs.firstOrNull {
                    it.isSink && (
                        it.id == activeEndpoint.deviceId ||
                        it.productName?.toString()?.equals(activeEndpoint.title, ignoreCase = true) == true
                    )
                } ?: rawOutputs.firstOrNull {
                    it.isSink && (
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && targetBtInfo != null) {
                    try {
                        audioManager.setCommunicationDevice(targetBtInfo)
                    } catch (_: Exception) {}
                }

                transferMediaRouter2ToBluetooth(activeEndpoint.title)
                bindSingleAudioTrackToDevice(activeEndpoint.key, targetBtInfo)
            }

            else -> {
                // Route to Wired Headphones (3.5mm) or USB-C DAC
                val wiredOrUsbInfo = rawOutputs.firstOrNull {
                    it.isSink && (it.id == activeEndpoint.deviceId || it.type == activeEndpoint.androidDeviceType)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice()
                        if (wiredOrUsbInfo != null) {
                            audioManager.setCommunicationDevice(wiredOrUsbInfo)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                    }
                } catch (_: Exception) {}

                bindSingleAudioTrackToDevice(activeEndpoint.key, wiredOrUsbInfo)
            }
        }
    }

    private fun transferMediaRouter2ToBuiltInSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val router = mediaRouter2 ?: return
                val speakerRoute = router.routes.firstOrNull { route ->
                    route.isSystemRoute && (
                        route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER ||
                        route.name.toString().contains("speaker", ignoreCase = true) ||
                        route.name.toString().contains("phone", ignoreCase = true)
                    )
                }
                if (speakerRoute != null && !router.systemController.selectedRoutes.contains(speakerRoute)) {
                    router.transferTo(speakerRoute)
                }
            } catch (_: Exception) {}
        }
    }

    private fun transferMediaRouter2ToBluetooth(btTitle: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val router = mediaRouter2 ?: return
                val btRoute = router.routes.firstOrNull { route ->
                    route.name.toString().equals(btTitle, ignoreCase = true)
                } ?: router.routes.firstOrNull { route ->
                    route.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP ||
                        route.type == MediaRoute2Info.TYPE_BLE_HEADSET
                }
                if (btRoute != null && !router.systemController.selectedRoutes.contains(btRoute)) {
                    router.transferTo(btRoute)
                }
            } catch (_: Exception) {}
        }
    }

    private fun bindSingleAudioTrackToDevice(
        endpointKey: String,
        deviceInfo: AudioDeviceInfo?
    ) {
        releaseBoundAudioTracks()
        val delayLine = delayLines.getOrPut(endpointKey) { HermiteFractionalDelayLine() }
        delayLine.setTargetDelayMs(0.0)
        val track = createPreferredDeviceAudioTrack(deviceInfo)
        boundSinkStreams[endpointKey] = BoundHardwareSinkStream(
            endpointKey = endpointKey,
            preferredDeviceInfo = deviceInfo,
            delayLine = delayLine,
            audioTrack = track
        )
    }

    private fun createPreferredDeviceAudioTrack(
        deviceInfo: AudioDeviceInfo?
    ): AudioTrack? {
        return try {
            val sampleRate = DolbyConstants.SAMPLE_RATE.toInt()
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(4096)

            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            track
        } catch (_: Exception) {
            null
        }
    }

    fun processSoftwareAudioStream(
        leftBlock: FloatArray,
        rightBlock: FloatArray,
        numFrames: Int
    ) {
        if (numFrames <= 0) return

        dspEngine.processAudioBlock(leftBlock, rightBlock, numFrames)

        val delayedL = FloatArray(numFrames)
        val delayedR = FloatArray(numFrames)
        val interleavedOut = FloatArray(numFrames * 2)

        for (stream in boundSinkStreams.values) {
            stream.delayLine.processBlock(
                leftBlock,
                rightBlock,
                delayedL,
                delayedR,
                numFrames
            )
            val track = stream.audioTrack ?: continue
            for (f in 0 until numFrames) {
                interleavedOut[f * 2] = delayedL[f]
                interleavedOut[f * 2 + 1] = delayedR[f]
            }
            try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                track.write(
                    interleavedOut,
                    0,
                    numFrames * 2,
                    AudioTrack.WRITE_NON_BLOCKING
                )
            } catch (_: Exception) {}
        }
    }

    private fun releaseBoundAudioTracks() {
        for (stream in boundSinkStreams.values) {
            try {
                stream.audioTrack?.stop()
                stream.audioTrack?.release()
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
                onDevicesUpdatedListener?.invoke()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                onDevicesUpdatedListener?.invoke()
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
        releaseBoundAudioTracks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val router = mediaRouter2
                val rCallback = routeCallback
                val tCallback = transferCallback
                if (router != null && rCallback != null) {
                    router.unregisterRouteCallback(rCallback)
                }
                if (router != null && tCallback != null) {
                    router.unregisterTransferCallback(tCallback)
                }
            } catch (_: Exception) {}
        }
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
        try {
            routerExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}
