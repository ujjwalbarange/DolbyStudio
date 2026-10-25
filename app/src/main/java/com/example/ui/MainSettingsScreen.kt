package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.model.AudioMode
import com.example.model.HardwareAudioEndpoint
import com.example.model.PairedBluetoothDeviceItem
import com.example.ui.components.DolbySelectionIndicator
import com.example.ui.components.DolbyTopBar
import com.example.ui.theme.DolbyAccentLightBlue
import com.example.ui.theme.DolbyDivider
import com.example.ui.theme.DolbyObsidianBg
import com.example.ui.theme.DolbySectionHeaderBlue
import com.example.ui.theme.DolbySurfaceElevated
import com.example.ui.theme.DolbyTextPrimary

private val SubtitleSlateColor = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(
    selectedMode: AudioMode,
    hardwareEndpoints: List<HardwareAudioEndpoint>,
    showAddDeviceSheet: Boolean = false,
    pairedBluetoothDevices: List<PairedBluetoothDeviceItem> = emptyList(),
    connectingBluetoothAddress: String? = null,
    onToggleEndpoint: (String) -> Unit,
    onRescanHardwareEndpoints: () -> Unit,
    onOpenAddDeviceSheet: () -> Unit = {},
    onDismissAddDeviceSheet: () -> Unit = {},
    onConnectBluetoothDevice: (String) -> Unit = {},
    onSelectMode: (AudioMode) -> Unit,
    onEditModeClick: (AudioMode) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    val context = LocalContext.current

    // Bluetooth permission launcher for Android 12+ (BLUETOOTH_CONNECT + BLUETOOTH_SCAN)
    val btPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRescanHardwareEndpoints()
        onOpenAddDeviceSheet()
    }

    // Clicking "+ Add device" opens the In-App Bluetooth Device Picker Bottom Sheet
    val handleAddDeviceClick = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBtConnect = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val hasBtScan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBtConnect || !hasBtScan) {
                btPermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
            } else {
                onRescanHardwareEndpoints()
                onOpenAddDeviceSheet()
            }
        } else {
            onRescanHardwareEndpoints()
            onOpenAddDeviceSheet()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DolbyObsidianBg)
            .testTag("main_settings_screen")
    ) {
        DolbyTopBar(
            onBackClick = onBackClick
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            // Large "Settings" Header (Matches Screenshots 2 & 5)
            item {
                Text(
                    text = stringResource(id = R.string.settings_title),
                    color = DolbyTextPrimary,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 28.dp)
                        .testTag("settings_header_title")
                )
            }

            // Output Section Header in #7BA7FF (Matches "Loudspeakers" header in Screenshots 2 & 5)
            item {
                Text(
                    text = stringResource(id = R.string.loudspeakers),
                    color = DolbySectionHeaderBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 6.dp)
                        .testTag("output_devices_section_header")
                )
            }

            // Detected Real Hardware Output Devices (Clean multi-select via MediaRouter2 & AudioDeviceInfo)
            items(hardwareEndpoints.size) { idx ->
                val endpoint = hardwareEndpoints[idx]
                HardwareOutputRow(
                    endpoint = endpoint,
                    onToggle = { onToggleEndpoint(endpoint.key) }
                )
            }

            // Minimal "+ Add device" row in the exact same clean list style
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { handleAddDeviceClick() }
                        .padding(start = 16.dp, end = 24.dp, top = 10.dp, bottom = 14.dp)
                        .testTag("add_device_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add device",
                            tint = DolbySectionHeaderBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "+ Add device",
                        color = DolbySectionHeaderBlue,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Section 1: Spatial audio (Matches Screenshot 2)
            item {
                Text(
                    text = stringResource(id = R.string.spatial_audio_section),
                    color = DolbyTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                )

                SettingsModeRow(
                    mode = AudioMode.SPATIAL_AUDIO,
                    isSelected = (selectedMode == AudioMode.SPATIAL_AUDIO),
                    onSelect = { onSelectMode(AudioMode.SPATIAL_AUDIO) },
                    onEditClick = null
                )
            }

            // Section 2: Stereo audio (Matches Screenshots 2 & 5)
            item {
                Text(
                    text = stringResource(id = R.string.stereo_audio_section),
                    color = DolbyTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp)
                )
            }

            val stereoModes = listOf(
                AudioMode.SMART_AUDIO,
                AudioMode.MUSIC,
                AudioMode.FILM,
                AudioMode.GAME,
                AudioMode.PODCAST,
                AudioMode.CUSTOM
            )

            items(stereoModes.size) { idx ->
                val mode = stereoModes[idx]
                SettingsModeRow(
                    mode = mode,
                    isSelected = (selectedMode == mode),
                    onSelect = { onSelectMode(mode) },
                    onEditClick = if (mode.hasEditor) {
                        { onEditModeClick(mode) }
                    } else null
                )
            }
        }
    }

    // In-App Device Picker Modal Bottom Sheet
    if (showAddDeviceSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissAddDeviceSheet,
            sheetState = sheetState,
            containerColor = DolbySurfaceElevated,
            contentColor = DolbyTextPrimary,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.testTag("add_device_bottom_sheet")
        ) {
            AddBluetoothDeviceBottomSheetContent(
                pairedDevices = pairedBluetoothDevices,
                connectingAddress = connectingBluetoothAddress,
                onSelectDevice = { item ->
                    onConnectBluetoothDevice(item.address)
                },
                onPairBrandNewDevice = {
                    onDismissAddDeviceSheet()
                    try {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                }
            )
        }
    }
}

@Composable
private fun AddBluetoothDeviceBottomSheetContent(
    pairedDevices: List<PairedBluetoothDeviceItem>,
    connectingAddress: String?,
    onSelectDevice: (PairedBluetoothDeviceItem) -> Unit,
    onPairBrandNewDevice: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 28.dp)
    ) {
        Text(
            text = "Connect audio device",
            color = DolbyTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )

        Text(
            text = "Tap any connected or saved Bluetooth device to add and enable audio routing",
            color = SubtitleSlateColor,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        )

        HorizontalDivider(color = DolbyDivider, thickness = 1.dp)

        if (pairedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "No paired Bluetooth audio devices found on this phone yet. Tap below to pair a new Bluetooth speaker or earphones.",
                    color = SubtitleSlateColor,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
            ) {
                items(pairedDevices.size) { idx ->
                    val item = pairedDevices[idx]
                    val isConnecting = (connectingAddress == item.address)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDevice(item) }
                            .padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
                            .testTag("sheet_bt_device_${item.address}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DolbySelectionIndicator(
                            selected = item.isSelectedInOutputList,
                            onClick = { onSelectDevice(item) }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                color = DolbyTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    isConnecting -> "Connecting Bluetooth audio..."
                                    item.isConnected && item.isSelectedInOutputList -> "Connected • Active in output route"
                                    item.isConnected -> "Connected • Tap to tick in output list"
                                    else -> "Saved device • Tap to connect & tick"
                                },
                                color = if (item.isConnected) DolbySectionHeaderBlue else SubtitleSlateColor,
                                fontSize = 13.sp
                            )
                        }

                        if (isConnecting) {
                            CircularProgressIndicator(
                                color = DolbySectionHeaderBlue,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            color = DolbyDivider,
            thickness = 1.dp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onPairBrandNewDevice,
                modifier = Modifier.testTag("pair_brand_new_device_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = DolbySectionHeaderBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Pair a brand new device",
                    color = DolbySectionHeaderBlue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun HardwareOutputRow(
    endpoint: HardwareAudioEndpoint,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
            .testTag("output_device_row_${endpoint.deviceId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DolbySelectionIndicator(
            selected = endpoint.isSelected,
            onClick = onToggle,
            modifier = Modifier.testTag("output_device_select_${endpoint.deviceId}")
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = endpoint.title,
                color = DolbyTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = endpoint.subtitle,
                color = SubtitleSlateColor,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun SettingsModeRow(
    mode: AudioMode,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEditClick: (() -> Unit)?
) {
    val modeKey = mode.name.lowercase()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .testTag("mode_row_$modeKey"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = DolbySectionHeaderBlue,
                unselectedColor = Color(0xFFDADCE0)
            ),
            modifier = Modifier
                .size(48.dp)
                .testTag("mode_radio_$modeKey")
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = mode.title,
                color = DolbyTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = mode.subtitle,
                color = SubtitleSlateColor,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        if (onEditClick != null) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(36.dp)
                    .background(DolbyDivider)
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onEditClick,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_mode_$modeKey")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Configure ${mode.title} settings",
                    tint = DolbyAccentLightBlue,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
