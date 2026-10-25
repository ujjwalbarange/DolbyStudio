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
    val selectedEndpointKeys: Set<String> = setOf(DolbyConstants.KEY_BUILTIN_SPEAKER),
    val showAddDeviceSheet: Boolean = false,
    val pairedBluetoothDevices: List<PairedBluetoothDeviceItem> = emptyList(),
    val connectingBluetoothAddress: String? = null,
    val isMultiOutputCaptureActive: Boolean = false,
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

    // Tracks keys we have already seen so newly connected BT devices default to ticked ON
    // without ever auto-unchecking Loudspeakers or overriding user manual deselections.
    private val seenEndpointKeys = mutableSetOf<String>()

    private val _uiState: MutableStateFlow<DolbyUiState>
    val uiState: StateFlow<DolbyUiState>

    init {
        val initialEndpoints = syncEngine.scanRealHardwareOutputDevices()
        val initialSelectedKeys = initialEndpoints.filter { it.isSelected }.map { it.key }.toSet()
            .ifEmpty { setOf(DolbyConstants.KEY_BUILTIN_SPEAKER) }
        seenEndpointKeys.addAll(initialEndpoints.map { it.key })

        _uiState = MutableStateFlow(
            DolbyUiState(
                hardwareEndpoints = initialEndpoints,
                selectedEndpointKeys = initialSelectedKeys,
                isMultiOutputCaptureActive = syncEngine.isMediaProjectionActive()
            )
        )
        uiState = _uiState.asStateFlow()

        syncDspParametersWithState(_uiState.value)

        // Register real-time hardware connection / disconnection + Bluetooth profile listener
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
     * Rescans both AudioManager outputs and BluetoothAdapter (A2DP + HEADSET + LE_AUDIO + bondedDevices)
     * so all connected Bluetooth devices (e.g., "AirLits 7" and "pTron BT") and "Loudspeakers"
     * appear simultaneously.
     */
    fun refreshHardwareEndpoints() {
        _uiState.update { state ->
            val rawScanned = syncEngine.scanRealHardwareOutputDevices(state.selectedEndpointKeys)
            val updatedSelectedKeys = state.selectedEndpointKeys.toMutableSet()

            // Any newly discovered connected Bluetooth or wired device defaults to selected=true
            // while preserving existing selections (never auto-unchecking Loudspeakers).
            for (ep in rawScanned) {
                if (seenEndpointKeys.add(ep.key)) {
                    updatedSelectedKeys.add(ep.key)
                }
            }

            if (updatedSelectedKeys.isEmpty()) {
                updatedSelectedKeys.add(DolbyConstants.KEY_BUILTIN_SPEAKER)
            }

            val mergedEndpoints = rawScanned.map { ep ->
                ep.copy(isSelected = updatedSelectedKeys.contains(ep.key))
            }
            val synced = syncEngine.applySilentBackgroundDelaySynchronization(mergedEndpoints)
            val pairedList = if (state.showAddDeviceSheet) {
                syncEngine.queryPairedAndConnectedBluetoothDevices(updatedSelectedKeys)
            } else {
                state.pairedBluetoothDevices
            }

            val next = state.copy(
                hardwareEndpoints = synced,
                selectedEndpointKeys = updatedSelectedKeys,
                pairedBluetoothDevices = pairedList,
                isMultiOutputCaptureActive = syncEngine.isMediaProjectionActive()
            )
            syncDspParametersWithState(next)
            next
        }
    }

    /**
     * True Multi-Select toggle: allows ticking 1, 2, 3, or more output devices simultaneously
     * (e.g., Loudspeakers + pTron BT + AirLits 7). Ticking a Bluetooth device never unchecks Loudspeakers.
     */
    fun toggleEndpointSelection(endpointKey: String) {
        _uiState.update { state ->
            val updatedKeys = state.selectedEndpointKeys.toMutableSet()
            if (updatedKeys.contains(endpointKey)) {
                updatedKeys.remove(endpointKey)
            } else {
                updatedKeys.add(endpointKey)
            }

            // Keep at least one output active so audio is never orphaned
            if (updatedKeys.isEmpty()) {
                val fallbackKey = state.hardwareEndpoints.firstOrNull()?.key
                    ?: DolbyConstants.KEY_BUILTIN_SPEAKER
                updatedKeys.add(fallbackKey)
            }

            val updatedEndpoints = state.hardwareEndpoints.map { ep ->
                ep.copy(isSelected = updatedKeys.contains(ep.key))
            }
            val synced = syncEngine.applySilentBackgroundDelaySynchronization(updatedEndpoints)
            val pairedList = if (state.showAddDeviceSheet) {
                syncEngine.queryPairedAndConnectedBluetoothDevices(updatedKeys)
            } else {
                state.pairedBluetoothDevices
            }

            val next = state.copy(
                hardwareEndpoints = synced,
                selectedEndpointKeys = updatedKeys,
                pairedBluetoothDevices = pairedList,
                isMultiOutputCaptureActive = syncEngine.isMediaProjectionActive()
            )
            syncDspParametersWithState(next)
            next
        }
    }

    /**
     * Opens the In-App "+ Add device" Modal Bottom Sheet listing all Connected and Saved/Paired
     * Bluetooth devices directly inside the app without redirecting to Android Settings.
     */
    fun openAddDeviceSheet() {
        _uiState.update { state ->
            val currentKeys = state.selectedEndpointKeys
            val paired = syncEngine.queryPairedAndConnectedBluetoothDevices(currentKeys)
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

    /**
     * Connects to a Paired/Saved or Connected Bluetooth device tapped inside the In-App
     * "+ Add device" bottom sheet, adds it to the active output list under Loudspeakers,
     * and ticks it ON (`isSelected = true`) automatically.
     */
    fun connectAndSelectBluetoothDeviceFromSheet(address: String) {
        val targetKey = "bt_$address"
        _uiState.update { state ->
            state.copy(connectingBluetoothAddress = address)
        }

        syncEngine.connectToBluetoothDeviceInApp(address) { _, _ ->
            _uiState.update { state ->
                val updatedKeys = state.selectedEndpointKeys.toMutableSet().apply {
                    add(targetKey)
                }
                seenEndpointKeys.add(targetKey)

                val scanned = syncEngine.scanRealHardwareOutputDevices(updatedKeys).map { ep ->
                    ep.copy(isSelected = updatedKeys.contains(ep.key))
                }
                val synced = syncEngine.applySilentBackgroundDelaySynchronization(scanned)
                val updatedPaired = syncEngine.queryPairedAndConnectedBluetoothDevices(updatedKeys)

                val next = state.copy(
                    hardwareEndpoints = synced,
                    selectedEndpointKeys = updatedKeys,
                    pairedBluetoothDevices = updatedPaired,
                    connectingBluetoothAddress = null,
                    isMultiOutputCaptureActive = syncEngine.isMediaProjectionActive()
                )
                syncDspParametersWithState(next)
                next
            }
        }
    }

    fun onMediaProjectionStateChanged() {
        _uiState.update { state ->
            state.copy(isMultiOutputCaptureActive = syncEngine.isMediaProjectionActive())
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
