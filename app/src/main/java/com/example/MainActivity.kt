package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppScreen
import com.example.ui.CustomEqualiserScreen
import com.example.ui.DesktopDashboardScreen
import com.example.ui.HeroSplashScreen
import com.example.ui.MainSettingsScreen
import com.example.ui.PresetSettingsScreen
import com.example.ui.theme.DolbyObsidianBg
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.DolbyAtmosViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                DolbyAtmosApp()
            }
        }
    }
}

@Composable
fun DolbyAtmosApp(
    viewModel: DolbyAtmosViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val startupBtPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshHardwareEndpoints()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missingPermissions = buildList {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
            if (missingPermissions.isNotEmpty()) {
                startupBtPermissionLauncher.launch(missingPermissions.toTypedArray())
            } else {
                viewModel.refreshHardwareEndpoints()
            }
        } else {
            viewModel.refreshHardwareEndpoints()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshHardwareEndpoints()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(DolbyObsidianBg),
        containerColor = DolbyObsidianBg,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(DolbyObsidianBg)
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            val isWideDesktopLayout = maxWidth >= 768.dp

            if (isWideDesktopLayout) {
                // Wide / Desktop Window Size (>= 768.dp): Two-Column Dashboard
                DesktopDashboardScreen(
                    useAudioEffects = uiState.useAudioEffects,
                    selectedMode = uiState.selectedMode,
                    editingMode = uiState.editingMode,
                    editingProfile = uiState.editingProfile,
                    hardwareEndpoints = uiState.hardwareEndpoints,
                    onToggleAudioEffects = { viewModel.setMasterAudioEffects(it) },
                    onToggleEndpoint = { viewModel.toggleEndpointSelection(it) },
                    onOpenAddDeviceSheet = { viewModel.openAddDeviceSheet() },
                    onSelectMode = { viewModel.selectAudioMode(it) },
                    onSelectPreset = { viewModel.selectEqualiserPresetForEditingMode(it) },
                    onUpdateBandGain = { idx, db -> viewModel.updateManualBandGain(idx, db) },
                    onUpdateMacroTilt = { bass, treble ->
                        viewModel.updateMacroCurveTilt(bass, treble)
                    },
                    onToggleSurroundVirtualiser = { viewModel.setSurroundVirtualiser(it) },
                    onToggleVolumeLeveller = { viewModel.setVolumeLeveller(it) },
                    onResetProfile = { viewModel.resetCurrentProfileToDefault() }
                )
            } else {
                // Compact / Mobile Window Size (< 768.dp): Single-Column Navigation Stack
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 600.dp)
                ) {
                    Crossfade(
                        targetState = uiState.currentScreen,
                        animationSpec = tween(durationMillis = 220),
                        label = "dolby_screen_crossfade"
                    ) { screen ->
                        when (screen) {
                            AppScreen.HERO_SPLASH -> {
                                HeroSplashScreen(
                                    useAudioEffects = uiState.useAudioEffects,
                                    onToggleAudioEffects = { viewModel.setMasterAudioEffects(it) },
                                    onOpenSettings = { viewModel.navigateTo(AppScreen.MAIN_SETTINGS) }
                                )
                            }

                            AppScreen.MAIN_SETTINGS -> {
                                MainSettingsScreen(
                                    selectedMode = uiState.selectedMode,
                                    hardwareEndpoints = uiState.hardwareEndpoints,
                                    showAddDeviceSheet = uiState.showAddDeviceSheet,
                                    pairedBluetoothDevices = uiState.pairedBluetoothDevices,
                                    connectingBluetoothAddress = uiState.connectingBluetoothAddress,
                                    onToggleEndpoint = { viewModel.toggleEndpointSelection(it) },
                                    onRescanHardwareEndpoints = { viewModel.refreshHardwareEndpoints() },
                                    onOpenAddDeviceSheet = { viewModel.openAddDeviceSheet() },
                                    onDismissAddDeviceSheet = { viewModel.dismissAddDeviceSheet() },
                                    onConnectBluetoothDevice = {
                                        viewModel.connectAndSelectBluetoothDeviceFromSheet(it)
                                    },
                                    onSelectMode = { viewModel.selectAudioMode(it) },
                                    onEditModeClick = { viewModel.openModeEditor(it) },
                                    onBackClick = { viewModel.navigateBack() }
                                )
                            }

                            AppScreen.PRESET_EDITOR -> {
                                PresetSettingsScreen(
                                    mode = uiState.editingMode,
                                    profile = uiState.editingProfile,
                                    onSelectPreset = {
                                        viewModel.selectEqualiserPresetForEditingMode(it)
                                    },
                                    onUpdateMacroTilt = { bass, treble ->
                                        viewModel.updateMacroCurveTilt(bass, treble)
                                    },
                                    onToggleSurroundVirtualiser = {
                                        viewModel.setSurroundVirtualiser(it)
                                    },
                                    onToggleVolumeLeveller = { viewModel.setVolumeLeveller(it) },
                                    onResetProfile = { viewModel.resetCurrentProfileToDefault() },
                                    onBackClick = { viewModel.navigateBack() }
                                )
                            }

                            AppScreen.CUSTOM_EDITOR -> {
                                CustomEqualiserScreen(
                                    profile = uiState.editingProfile,
                                    onUpdateBandGain = { idx, db ->
                                        viewModel.updateManualBandGain(idx, db)
                                    },
                                    onSelectPreset = {
                                        viewModel.selectEqualiserPresetForEditingMode(it)
                                    },
                                    onToggleSurroundVirtualiser = {
                                        viewModel.setSurroundVirtualiser(it)
                                    },
                                    onToggleVolumeLeveller = { viewModel.setVolumeLeveller(it) },
                                    onResetProfile = { viewModel.resetCurrentProfileToDefault() },
                                    onBackClick = { viewModel.navigateBack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
