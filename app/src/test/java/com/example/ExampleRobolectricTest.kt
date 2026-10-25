package com.example

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import com.example.dsp.MultiDeviceSyncEngine
import com.example.dsp.PsychoacousticDspEngine
import com.example.model.DolbyConstants
import com.example.model.HardwareAudioEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        assertTrue(endpoints.any { it.key == DolbyConstants.KEY_BUILTIN_SPEAKER && it.title == "Loudspeakers" && it.isSelected })
    }

    @Test
    fun `verify silent background delay synchronization aligns selected multi-output endpoints to t_max`() {
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
}
