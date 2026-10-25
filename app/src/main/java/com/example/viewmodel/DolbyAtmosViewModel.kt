package com.example.viewmodel

import android.app.Application
import android.media.AudioDeviceCallback
import androidx.lifecycle.AndroidViewModel
import com.example.dsp.MultiDeviceSyncEngine
import com.example.dsp.PsychoacousticDspEngine
import com.example.model.AppScreen
import com.example.model.AudioMode
import com.example.model.DolbyConstants
import com.example.model.EqualiserPreset
import com.example.model.HardwareAudioEndpoint
import com.example.model.ModeProfileSettings
import com.example.model.PairedBluetoothDeviceItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class DolbyUiState(
    val currentScreen: AppScreen = AppScreen.HERO_SPLASH,
    val useAudioEffects: Boolean = true,
    val selectedMode: AudioMode = AudioMode.MUSIC,
    val editingMode: AudioMode = AudioMode.MUSIC,
    val profiles: Map<AudioMode, ModeProfileSettings> = defaultProfiles(),
    val hardwareEndpoints: List<HardwareAudioEndpoint> = emptyList(),
    val activeDeviceId: String = DolbyConstants.KEY_BUILTIN_SPEAKER,
    val showAddDeviceSheet: Boolean = false,
    val pairedBluetoothDevices: List<PairedBluetoothDeviceItem> = emptyList(),
    val connectingBluetoothAddress: String? = null,
    val effectiveIeqBandsDb: List<Float> = List(10) { 0f }
) {
    val activeProfile: ModeProfileSettings
        get() = profiles[selectedMode] ?: ModeProfileSettings(selectedMode, EqualiserPreset.BRILLIANT_TREBLE)

    val editingProfile: ModeProfileSettings
        get() = profiles[editingMode] ?: ModeProfileSettings(editingMode, EqualiserPreset.BRILLIANT_TREBLE)

    companion object {
        fun defaultProfiles(): Map<AudioMode, ModeProfileSettings> = mapOf(
            AudioMode.SPATIAL_AUDIO to ModeProfileSettings(
                mode = AudioMode.SPATIAL_AUDIO,
                equaliserPreset = EqualiserPreset.BRILLIANT_TREBLE,
                surroundVirtualiser = true,
                volumeLeveller = true
            ),
            AudioMode.SMART_AUDIO to ModeProfileSettings(
                mode = AudioMode.SMART_AUDIO,
                equaliserPreset = EqualiserPreset.BRILLIANT_TREBLE,
                surroundVirtualiser = true,
                volumeLeveller = true
            ),
            AudioMode.MUSIC to ModeProfileSettings(
                mode = AudioMode.MUSIC,
                equaliserPreset = EqualiserPreset.BRILLIANT_TREBLE,
                surroundVirtualiser = true,
                volumeLeveller = true
            ),
            AudioMode.FILM to ModeProfileSettings(
                mode = AudioMode.FILM,
                equaliserPreset = EqualiserPreset.BASS_BOOST,
                surroundVirtualiser = true,
                volumeLeveller = true
            ),
            AudioMode.GAME to ModeProfileSettings(
                mode = AudioMode.GAME,
                equaliserPreset = EqualiserPreset.BASS_BOOST,
                surroundVirtualiser = true,
                volumeLeveller = true
            ),
            AudioMode.PODCAST to ModeProfileSettings(
                mode = AudioMode.PODCAST,
                equaliserPreset = EqualiserPreset.VOCAL_BOOST,
                surroundVirtualiser = false,
                volumeLeveller = true
            ),
            AudioMode.CUSTOM to ModeProfileSettings(
                mode = AudioMode.CUSTOM,
                equaliserPreset = EqualiserPreset.MANUAL,
                manualBandGainsDb = EqualiserPreset.MANUAL.targetBandGainsDb.toList(),
                surroundVirtualiser = true,
                volumeLeveller = true
            )
        )
    }
}

class DolbyAtmosViewModel(application: Application) : AndroidViewModel(application) {

    private val dspEngine = PsychoacousticDspEngine()
    private val syncEngine = MultiDeviceSyncEngine(application.applicationContext, dspEngine)
    private var deviceCallback: AudioDeviceCallback? = null

    private val seenEndpointKeys = mutableSetOf<String>()

    private val _uiState: MutableStateFlow<DolbyUiState>
    val uiState: StateFlow<DolbyUiState>

    init {
        val initialEndpoints = syncEngine.scanRealHardwareOutputDevices()
        val initialActiveId = initialEndpoints.firstOrNull { it.isSelected }?.key
            ?: DolbyConstants.KEY_BUILTIN_SPEAKER
        seenEndpointKeys.addAll(initialEndpoints.map { it.key })

        _uiState = MutableStateFlow(
            DolbyUiState(
                hardwareEndpoints = initialEndpoints,
                activeDeviceId = initialActiveId
            )
        )
        uiState = _uiState.asStateFlow()

        syncDspParametersWithState(_uiState.value)

        deviceCallback = syncEngine.registerDeviceCallback {
            refreshHardwareEndpoints()
        }
    }

    override fun onCleared() {
        super.onCleared()
        deviceCallback?.let { syncEngine.unregisterDeviceCallback(it) }
        syncEngine.release()
    }

    /**
     * Rescans AudioManager outputs and connected Bluetooth profiles (A2DP + HEADSET + LE_AUDIO).
     * Automatically selects newly connected hardware or falls back to "Loudspeakers"
     * (`DolbyConstants.KEY_BUILTIN_SPEAKER`) if the active Bluetooth or wired device disconnected.
     */
    fun refreshHardwareEndpoints() {
        _uiState.update { state ->
            val rawScanned = syncEngine.scanRealHardwareOutputDevices(state.activeDeviceId)

            var nextActiveId = state.activeDeviceId
            for (ep in rawScanned) {
                if (seenEndpointKeys.add(ep.key) && ep.key != DolbyConstants.KEY_BUILTIN_SPEAKER) {
                    nextActiveId = ep.key
                }
            }

            if (rawScanned.none { it.key == nextActiveId }) {
                nextActiveId = DolbyConstants.KEY_BUILTIN_SPEAKER
            }

            val updatedEndpoints = rawScanned.map { ep ->
                ep.copy(
                    isSelected = (ep.key == nextActiveId),
                    internalCompensatingDelayMs = 0f
                )
            }
            syncEngine.applyNativeSingleDeviceRouting(updatedEndpoints)

            val pairedList = if (state.showAddDeviceSheet) {
                syncEngine.queryPairedAndConnectedBluetoothDevices(nextActiveId)
            } else {
                state.pairedBluetoothDevices
            }

            val next = state.copy(
                hardwareEndpoints = updatedEndpoints,
                activeDeviceId = nextActiveId,
                pairedBluetoothDevices = pairedList
            )
            syncDspParametersWithState(next)
            next
        }
    }

    /**
     * Mutually Exclusive Single-Device Selection:
     * Tapping any device sets it as the active output (`activeDeviceId = endpointKey`),
     * marks its circle as ticked (#7BA7FF), unticks all other devices, and routes audio
     * natively via AudioManager without any screen-casting dialogs.
     */
    fun toggleEndpointSelection(endpointKey: String) {
        selectEndpoint(endpointKey)
    }

    fun selectEndpoint(endpointKey: String) {
        _uiState.update { state ->
            val targetId = if (state.hardwareEndpoints.any { it.key == endpointKey }) {
                endpointKey
            } else {
                DolbyConstants.KEY_BUILTIN_SPEAKER
            }

            val updatedEndpoints = state.hardwareEndpoints.map { ep ->
                ep.copy(
                    isSelected = (ep.key == targetId),
                    internalCompensatingDelayMs = 0f
                )
            }
            syncEngine.applyNativeSingleDeviceRouting(updatedEndpoints)

            val pairedList = if (state.showAddDeviceSheet) {
                syncEngine.queryPairedAndConnectedBluetoothDevices(targetId)
            } else {
                state.pairedBluetoothDevices
            }

            val next = state.copy(
                hardwareEndpoints = updatedEndpoints,
                activeDeviceId = targetId,
                pairedBluetoothDevices = pairedList
            )
            syncDspParametersWithState(next)
            next
        }
    }

    fun openAddDeviceSheet() {
        _uiState.update { state ->
            val paired = syncEngine.queryPairedAndConnectedBluetoothDevices(state.activeDeviceId)
            state.copy(
                showAddDeviceSheet = true,
                pairedBluetoothDevices = paired,
                connectingBluetoothAddress = null
            )
        }
    }

    fun dismissAddDeviceSheet() {
        _uiState.update { state ->
            state.copy(
                showAddDeviceSheet = false,
                connectingBluetoothAddress = null
            )
        }
    }

    fun connectAndSelectBluetoothDeviceFromSheet(address: String) {
        val targetKey = "bt_$address"
        _uiState.update { state ->
            state.copy(connectingBluetoothAddress = address)
        }

        syncEngine.connectToBluetoothDeviceInApp(address) { _, _ ->
            _uiState.update { state ->
                seenEndpointKeys.add(targetKey)
                val scanned = syncEngine.scanRealHardwareOutputDevices(targetKey)
                val resolvedActiveId = scanned.firstOrNull { it.isSelected }?.key
                    ?: DolbyConstants.KEY_BUILTIN_SPEAKER
                val updatedPaired = syncEngine.queryPairedAndConnectedBluetoothDevices(resolvedActiveId)

                val next = state.copy(
                    hardwareEndpoints = scanned,
                    activeDeviceId = resolvedActiveId,
                    pairedBluetoothDevices = updatedPaired,
                    connectingBluetoothAddress = null
                )
                syncDspParametersWithState(next)
                next
            }
        }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value.currentScreen
        return when (current) {
            AppScreen.PRESET_EDITOR, AppScreen.CUSTOM_EDITOR -> {
                _uiState.update { it.copy(currentScreen = AppScreen.MAIN_SETTINGS) }
                true
            }
            AppScreen.MAIN_SETTINGS -> {
                _uiState.update { it.copy(currentScreen = AppScreen.HERO_SPLASH) }
                true
            }
            AppScreen.HERO_SPLASH -> false
        }
    }

    fun setMasterAudioEffects(enabled: Boolean) {
        _uiState.update { state ->
            val next = state.copy(useAudioEffects = enabled)
            syncDspParametersWithState(next)
            next
        }
    }

    fun selectAudioMode(mode: AudioMode) {
        _uiState.update { state ->
            val next = state.copy(selectedMode = mode, editingMode = mode)
            syncDspParametersWithState(next)
            next
        }
    }

    fun openModeEditor(mode: AudioMode) {
        _uiState.update { state ->
            val targetScreen = if (mode == AudioMode.CUSTOM) {
                AppScreen.CUSTOM_EDITOR
            } else {
                AppScreen.PRESET_EDITOR
            }
            val next = state.copy(
                selectedMode = mode,
                editingMode = mode,
                currentScreen = targetScreen
            )
            syncDspParametersWithState(next)
            next
        }
    }

    fun selectEqualiserPresetForEditingMode(preset: EqualiserPreset) {
        _uiState.update { state ->
            val mode = state.editingMode
            val currentProf = state.editingProfile
            val updatedBands = if (preset == EqualiserPreset.MANUAL) {
                currentProf.manualBandGainsDb
            } else {
                preset.targetBandGainsDb.toList()
            }
            val updatedProf = currentProf.copy(
                equaliserPreset = preset,
                manualBandGainsDb = updatedBands,
                macroBassTiltDb = 0f,
                macroTrebleTiltDb = 0f
            )
            val updatedProfiles = state.profiles.toMutableMap().apply { put(mode, updatedProf) }
            val next = state.copy(profiles = updatedProfiles)
            syncDspParametersWithState(next)
            next
        }
    }

    fun updateManualBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..9) return
        val clamped = ((gainDb.coerceIn(-10f, 10f) * 10f).roundToInt()) / 10f
        _uiState.update { state ->
            val mode = state.editingMode
            val currentProf = state.editingProfile
            val mutableBands = currentProf.manualBandGainsDb.toMutableList()
            mutableBands[bandIndex] = clamped
            val updatedProf = currentProf.copy(
                equaliserPreset = EqualiserPreset.MANUAL,
                manualBandGainsDb = mutableBands
            )
            val updatedProfiles = state.profiles.toMutableMap().apply { put(mode, updatedProf) }
            val next = state.copy(profiles = updatedProfiles)
            syncDspParametersWithState(next)
            next
        }
    }

    fun updateMacroCurveTilt(bassTiltDb: Float, trebleTiltDb: Float) {
        _uiState.update { state ->
            val mode = state.editingMode
            val currentProf = state.editingProfile
            val updatedProf = currentProf.copy(
                macroBassTiltDb = bassTiltDb.coerceIn(-6f, 6f),
                macroTrebleTiltDb = trebleTiltDb.coerceIn(-6f, 6f)
            )
            val updatedProfiles = state.profiles.toMutableMap().apply { put(mode, updatedProf) }
            val next = state.copy(profiles = updatedProfiles)
            syncDspParametersWithState(next)
            next
        }
    }

    fun setSurroundVirtualiser(enabled: Boolean) {
        _uiState.update { state ->
            val mode = state.editingMode
            val updatedProf = state.editingProfile.copy(surroundVirtualiser = enabled)
            val updatedProfiles = state.profiles.toMutableMap().apply { put(mode, updatedProf) }
            val next = state.copy(profiles = updatedProfiles)
            syncDspParametersWithState(next)
            next
        }
    }

    fun setVolumeLeveller(enabled: Boolean) {
        _uiState.update { state ->
            val mode = state.editingMode
            val updatedProf = state.editingProfile.copy(volumeLeveller = enabled)
            val updatedProfiles = state.profiles.toMutableMap().apply { put(mode, updatedProf) }
            val next = state.copy(profiles = updatedProfiles)
            syncDspParametersWithState(next)
            next
        }
    }

    fun resetCurrentProfileToDefault() {
        val defaults = DolbyUiState.defaultProfiles()
        _uiState.update { state ->
            val mode = state.editingMode
            val defaultProf = defaults[mode] ?: return@update state
            val updatedProfiles = state.profiles.toMutableMap().apply { put(mode, defaultProf) }
            val next = state.copy(profiles = updatedProfiles)
            syncDspParametersWithState(next)
            next
        }
    }

    private fun syncDspParametersWithState(state: DolbyUiState) {
        val prof = state.activeProfile
        dspEngine.masterEnabled = state.useAudioEffects
        dspEngine.activeMode = state.selectedMode
        dspEngine.activePreset = prof.equaliserPreset
        dspEngine.surroundVirtualiserEnabled = prof.surroundVirtualiser
        dspEngine.volumeLevellerEnabled = prof.volumeLeveller
        dspEngine.macroBassTiltDb = prof.macroBassTiltDb
        dspEngine.macroTrebleTiltDb = prof.macroTrebleTiltDb
        dspEngine.isHeadphoneRoute = state.hardwareEndpoints.any { it.isSelected && it.isHeadphoneBinaural }

        val bands = if (prof.equaliserPreset == EqualiserPreset.MANUAL) {
            prof.manualBandGainsDb.toFloatArray()
        } else {
            prof.equaliserPreset.targetBandGainsDb
        }
        dspEngine.setBandGains(bands)
        syncEngine.syncSystemAudioEffects(
            masterEnabled = state.useAudioEffects,
            bandGainsDb = bands,
            surroundEnabled = prof.surroundVirtualiser || state.selectedMode == AudioMode.SPATIAL_AUDIO
        )
    }
}
