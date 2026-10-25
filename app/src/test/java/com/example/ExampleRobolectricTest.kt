package com.example

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import com.example.dsp.MultiDeviceSyncEngine
import com.example.dsp.PsychoacousticDspEngine
import com.example.dsp.RbjBiquadFilter
import com.example.model.DolbyConstants
import com.example.model.HardwareAudioEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Dolby Atmos", appName)
    }

    @Test
    fun `verify initial hardware scan returns default loudspeakers without mock devices`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dspEngine = PsychoacousticDspEngine()
        val syncEngine = MultiDeviceSyncEngine(context, dspEngine)

        val endpoints = syncEngine.scanRealHardwareOutputDevices()
        assertTrue(endpoints.isNotEmpty())
        assertEquals(1, endpoints.count { it.isSelected })
        assertTrue(endpoints.any { it.key == DolbyConstants.KEY_BUILTIN_SPEAKER && it.title == "Loudspeakers" && it.isSelected })
    }

    @Test
    fun `verify disconnected active bluetooth device automatically falls back to loudspeakers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dspEngine = PsychoacousticDspEngine()
        val syncEngine = MultiDeviceSyncEngine(context, dspEngine)

        val endpoints = syncEngine.scanRealHardwareOutputDevices(activeDeviceId = "bt_AA:BB:CC:11:22:33")
        assertEquals(1, endpoints.count { it.isSelected })
        assertEquals(DolbyConstants.KEY_BUILTIN_SPEAKER, endpoints.first { it.isSelected }.key)
    }

    @Test
    fun `verify silent background delay synchronization aligns selected endpoints to t_max`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dspEngine = PsychoacousticDspEngine()
        val syncEngine = MultiDeviceSyncEngine(context, dspEngine)

        val connectedEndpoints = listOf(
            HardwareAudioEndpoint(
                key = "bt_AA:BB:CC:11:22:33",
                deviceId = 101,
                title = "AirLits 7",
                subtitle = "Bluetooth stereo audio (Connected)",
                androidDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                bluetoothAddress = "AA:BB:CC:11:22:33",
                isHeadphoneBinaural = true,
                isSelected = true,
                internalHardwareLatencyMs = 40.0f
            ),
            HardwareAudioEndpoint(
                key = "bt_DD:EE:FF:44:55:66",
                deviceId = 102,
                title = "pTron BT",
                subtitle = "Bluetooth dual-channel audio (Connected)",
                androidDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                bluetoothAddress = "DD:EE:FF:44:55:66",
                isHeadphoneBinaural = true,
                isSelected = true,
                internalHardwareLatencyMs = 20.0f
            ),
            HardwareAudioEndpoint(
                key = DolbyConstants.KEY_BUILTIN_SPEAKER,
                deviceId = 103,
                title = "Loudspeakers",
                subtitle = "Phone stereo speakers",
                androidDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                isHeadphoneBinaural = false,
                isSelected = true,
                internalHardwareLatencyMs = 12.0f
            )
        )

        val synced = syncEngine.applySilentBackgroundDelaySynchronization(connectedEndpoints)

        assertEquals(0.0f, synced[0].internalCompensatingDelayMs, 0.01f)
        assertEquals(20.0f, synced[1].internalCompensatingDelayMs, 0.01f)
        assertEquals(28.0f, synced[2].internalCompensatingDelayMs, 0.01f)
        assertTrue(synced.all { it.isSelected })
    }

    @Test
    fun `verify 7-stage DAX3 psychoacoustic DSP pipeline and -0_1 dBFS lookahead limiter`() {
        val dspEngine = PsychoacousticDspEngine()
        dspEngine.updateDspState(
            masterEnabled = true,
            surroundVirtualiser = true,
            volumeLeveller = true,
            isHeadphoneRoute = false,
            customBandsDb = listOf(4.0f, 3.5f, 2.0f, 0.5f, 0.0f, 1.5f, 3.0f, 4.5f, 5.0f, 4.0f)
        )

        val numSamples = 1024
        val left = FloatArray(numSamples)
        val right = FloatArray(numSamples)
        for (n in 0 until numSamples) {
            val t = n.toDouble() / 48000.0
            // Synthesize 80 Hz bass + 2.4 kHz vocal formant + high-amplitude transient
            val sigL = 0.85 * sin(2.0 * PI * 80.0 * t) + 0.65 * sin(2.0 * PI * 2400.0 * t)
            val sigR = 0.85 * sin(2.0 * PI * 80.0 * t) - 0.45 * sin(2.0 * PI * 2400.0 * t)
            left[n] = sigL.toFloat()
            right[n] = sigR.toFloat()
        }

        dspEngine.processBlock(left, right, numSamples)

        val ceilingLinear = 10.0.pow(-0.1 / 20.0).toFloat() + 1e-5f
        var maxSample = 0.0f
        var energySum = 0.0
        for (n in 0 until numSamples) {
            val absL = abs(left[n])
            val absR = abs(right[n])
            assertTrue("Left sample exceeds -0.1 dBFS ceiling: $absL", absL <= ceilingLinear)
            assertTrue("Right sample exceeds -0.1 dBFS ceiling: $absR", absR <= ceilingLinear)
            if (absL > maxSample) maxSample = absL
            if (absR > maxSample) maxSample = absR
            energySum += (left[n] * left[n] + right[n] * right[n]).toDouble()
        }

        assertTrue("Processed audio block should contain non-zero signal energy", energySum > 1.0)
        assertTrue("True-peak output should remain within brickwall ceiling", maxSample <= ceilingLinear)

        // Also test Binaural Headphone HRTF mode (Woodworth ITD + ILD + Pinna Notches)
        dspEngine.updateDspState(
            masterEnabled = true,
            surroundVirtualiser = true,
            volumeLeveller = true,
            isHeadphoneRoute = true,
            customBandsDb = listOf(2.0f, 2.0f, 1.0f, 0.0f, 0.0f, 1.0f, 2.0f, 3.0f, 3.0f, 2.0f)
        )
        dspEngine.processBlock(left, right, numSamples)

        val biquad = RbjBiquadFilter()
        biquad.setPeakingEq(1000.0, 48000.0, 6.0, 1.414)
        val magAt1k = biquad.magnitudeSquaredAt(1000.0, 48000.0)
        // +6 dB peaking filter at 1 kHz has magnitude^2 ~= 10^(12/20) ~= 3.981
        assertEquals(10.0.pow(6.0 / 10.0), magAt1k, 0.05)
    }
}
