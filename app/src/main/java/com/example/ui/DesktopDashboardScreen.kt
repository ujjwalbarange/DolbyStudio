package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AudioMode
import com.example.model.EqualiserPreset
import com.example.model.HardwareAudioEndpoint
import com.example.model.ModeProfileSettings
import com.example.ui.components.DolbyMasterCardSwitch
import com.example.ui.components.DolbyProfileSwitch
import com.example.ui.components.DolbySelectionIndicator
import com.example.ui.theme.DolbyAccentLightBlue
import com.example.ui.theme.DolbyDivider
import com.example.ui.theme.DolbyMasterPillWhite
import com.example.ui.theme.DolbyMasterTextDark
import com.example.ui.theme.DolbyObsidianBg
import com.example.ui.theme.DolbySectionHeaderBlue
import com.example.ui.theme.DolbySurfaceCard
import com.example.ui.theme.DolbySurfaceElevated
import com.example.ui.theme.DolbyTextPrimary
import com.example.ui.theme.DolbyTextSecondary

private val DesktopBorderColor = Color(0xFF1E2638)
private val DesktopSubtitleColor = Color(0xFF94A3B8)

/**
 * PC-Adapted Responsive Two-Column Dashboard (>= 768.dp width):
 * - Left Column (380.dp): Hero status card ("Dolby Atmos - Use Audio effects" switch),
 *   Multi-Device Output Selector, and Audio Profile selection ("Spatial audio", "Smart audio",
 *   "Music", "Film", "Game", "Podcast", "Custom").
 * - Right Column (flex-grow): Dynamic editor pane displaying the live Cubic Bézier frequency
 *   response curve (-10 dB to +10 dB), the 10-band manual equalizer sliders, and the
 *   "Surround virtualiser" / "Volume leveller" toggles.
 */
@Composable
fun DesktopDashboardScreen(
    useAudioEffects: Boolean,
    selectedMode: AudioMode,
    editingMode: AudioMode,
    editingProfile: ModeProfileSettings,
    hardwareEndpoints: List<HardwareAudioEndpoint>,
    onToggleAudioEffects: (Boolean) -> Unit,
    onToggleEndpoint: (String) -> Unit,
    onOpenAddDeviceSheet: () -> Unit,
    onSelectMode: (AudioMode) -> Unit,
    onSelectPreset: (EqualiserPreset) -> Unit,
    onUpdateBandGain: (Int, Float) -> Unit,
    onUpdateMacroTilt: (Float, Float) -> Unit,
    onToggleSurroundVirtualiser: (Boolean) -> Unit,
    onToggleVolumeLeveller: (Boolean) -> Unit,
    onResetProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(DolbyObsidianBg)
            .testTag("desktop_dashboard_screen")
    ) {
        // ====================================================================
        // LEFT COLUMN: Hero Status Card + Output Device Selector + Mode List
        // ====================================================================
        LazyColumn(
            modifier = Modifier
                .width(390.dp)
                .fillMaxHeight()
                .background(DolbyObsidianBg),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. Hero Status Card ("Dolby Atmos" + "Use Audio effects" pill switch)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF1B253B),
                                    DolbySurfaceElevated
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.app_name),
                        color = DolbyTextPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(id = R.string.hero_subtitle),
                        color = DesktopSubtitleColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(DolbyMasterPillWhite)
                            .clickable { onToggleAudioEffects(!useAudioEffects) }
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                            .testTag("desktop_master_toggle_pill"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.use_audio_effects),
                            color = DolbyMasterTextDark,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        DolbyMasterCardSwitch(
                            checked = useAudioEffects,
                            onCheckedChange = onToggleAudioEffects,
                            modifier = Modifier.testTag("desktop_master_switch")
                        )
                    }
                }
            }

            // 2. Output Device Selector (Loudspeakers + Connected Hardware Multi-Select)
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = R.string.loudspeakers),
                        color = DolbySectionHeaderBlue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )

                    hardwareEndpoints.forEach { endpoint ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onToggleEndpoint(endpoint.key) }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DolbySelectionIndicator(
                                selected = endpoint.isSelected,
                                onClick = { onToggleEndpoint(endpoint.key) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = endpoint.title,
                                    color = DolbyTextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = endpoint.subtitle,
                                    color = DesktopSubtitleColor,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onOpenAddDeviceSheet() }
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .testTag("desktop_add_device_row"),
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
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "+ Add device",
                            color = DolbySectionHeaderBlue,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 3. Spatial audio & Stereo audio Profile Selection
            item {
                Text(
                    text = stringResource(id = R.string.spatial_audio_section),
                    color = DolbyTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                DesktopModeRow(
                    mode = AudioMode.SPATIAL_AUDIO,
                    isSelected = selectedMode == AudioMode.SPATIAL_AUDIO,
                    onSelect = { onSelectMode(AudioMode.SPATIAL_AUDIO) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(id = R.string.stereo_audio_section),
                    color = DolbyTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                val stereoModes = listOf(
                    AudioMode.SMART_AUDIO,
                    AudioMode.MUSIC,
                    AudioMode.FILM,
                    AudioMode.GAME,
                    AudioMode.PODCAST,
                    AudioMode.CUSTOM
                )

                stereoModes.forEach { mode ->
                    DesktopModeRow(
                        mode = mode,
                        isSelected = selectedMode == mode,
                        onSelect = { onSelectMode(mode) }
                    )
                }
            }
        }

        // Vertical Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(DesktopBorderColor)
        )

        // ====================================================================
        // RIGHT COLUMN: Live Frequency Curve / 10-Band Sliders + Profile Toggles
        // ====================================================================
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(DolbyObsidianBg),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${editingMode.title} settings",
                            color = DolbyTextPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = editingMode.subtitle,
                            color = DesktopSubtitleColor,
                            fontSize = 14.sp
                        )
                    }

                    IconButton(
                        onClick = onResetProfile,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset profile to default",
                            tint = DolbySectionHeaderBlue
                        )
                    }
                }
            }

            // Live Cubic Bézier Frequency Graph OR 10-Band Vertical Sliders
            item {
                val showManualSliders =
                    editingMode == AudioMode.CUSTOM && editingProfile.equaliserPreset == EqualiserPreset.MANUAL

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(DolbySurfaceElevated)
                        .padding(20.dp)
                ) {
                    if (showManualSliders) {
                        TenBandVerticalEqualiserBank(
                            bandGainsDb = editingProfile.manualBandGainsDb,
                            onBandGainChanged = onUpdateBandGain,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        CubicBezierFrequencyGraph(
                            preset = editingProfile.equaliserPreset,
                            macroBassTiltDb = editingProfile.macroBassTiltDb,
                            macroTrebleTiltDb = editingProfile.macroTrebleTiltDb,
                            onMacroTiltDrag = onUpdateMacroTilt,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Choose equaliser Radio List
            item {
                val presetOptions = if (editingMode == AudioMode.CUSTOM) {
                    listOf(
                        EqualiserPreset.MANUAL,
                        EqualiserPreset.BRILLIANT_TREBLE,
                        EqualiserPreset.BASS_BOOST,
                        EqualiserPreset.VOCAL_BOOST,
                        EqualiserPreset.FLAT
                    )
                } else {
                    listOf(
                        EqualiserPreset.BRILLIANT_TREBLE,
                        EqualiserPreset.BASS_BOOST,
                        EqualiserPreset.VOCAL_BOOST,
                        EqualiserPreset.FLAT
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = R.string.choose_equaliser),
                        color = DolbySectionHeaderBlue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    presetOptions.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelectPreset(preset) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = editingProfile.equaliserPreset == preset,
                                onClick = { onSelectPreset(preset) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = DolbySectionHeaderBlue,
                                    unselectedColor = Color(0xFFDADCE0)
                                ),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = preset.displayName,
                                color = DolbyTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Profile settings Switches ("Surround virtualiser" & "Volume leveller")
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.profile_settings),
                        color = DolbySectionHeaderBlue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(DolbySurfaceElevated)
                            .clickable {
                                onToggleSurroundVirtualiser(!editingProfile.surroundVirtualiser)
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.surround_virtualiser),
                                color = DolbyTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(id = R.string.surround_virtualiser_desc),
                                color = DolbyTextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                        DolbyProfileSwitch(
                            checked = editingProfile.surroundVirtualiser,
                            onCheckedChange = onToggleSurroundVirtualiser,
                            testTag = "desktop_surround_switch"
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(DolbySurfaceElevated)
                            .clickable {
                                onToggleVolumeLeveller(!editingProfile.volumeLeveller)
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.volume_leveller),
                                color = DolbyTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(id = R.string.volume_leveller_desc),
                                color = DolbyTextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                        DolbyProfileSwitch(
                            checked = editingProfile.volumeLeveller,
                            onCheckedChange = onToggleVolumeLeveller,
                            testTag = "desktop_volume_leveller_switch"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopModeRow(
    mode: AudioMode,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) DolbySurfaceCard else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = DolbySectionHeaderBlue,
                unselectedColor = Color(0xFFDADCE0)
            ),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = mode.title,
                color = DolbyTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = mode.subtitle,
                color = DesktopSubtitleColor,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
        if (mode.hasEditor) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(DolbyDivider)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Edit ${mode.title}",
                tint = DolbyAccentLightBlue,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(18.dp)
            )
        }
    }
}
