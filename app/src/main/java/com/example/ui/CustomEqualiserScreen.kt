package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.DolbyConstants
import com.example.model.EqualiserPreset
import com.example.model.ModeProfileSettings
import com.example.ui.components.DolbyProfileSwitch
import com.example.ui.components.DolbyTopBar
import com.example.ui.theme.DolbyAccentLightBlue
import com.example.ui.theme.DolbyObsidianBg
import com.example.ui.theme.DolbySectionHeaderBlue
import com.example.ui.theme.DolbySliderDeepBlue
import com.example.ui.theme.DolbySliderTrackLightGrey
import com.example.ui.theme.DolbyTextPrimary
import com.example.ui.theme.DolbyTextSecondary

@Composable
fun CustomEqualiserScreen(
    profile: ModeProfileSettings,
    onUpdateBandGain: (Int, Float) -> Unit,
    onSelectPreset: (EqualiserPreset) -> Unit,
    onToggleSurroundVirtualiser: (Boolean) -> Unit,
    onToggleVolumeLeveller: (Boolean) -> Unit,
    onResetProfile: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    val presetOptions = listOf(
        EqualiserPreset.MANUAL,
        EqualiserPreset.BRILLIANT_TREBLE,
        EqualiserPreset.BASS_BOOST,
        EqualiserPreset.VOCAL_BOOST,
        EqualiserPreset.FLAT
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DolbyObsidianBg)
            .testTag("custom_settings_screen")
    ) {
        DolbyTopBar(
            onBackClick = onBackClick,
            onResetProfile = onResetProfile
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 110.dp)
        ) {
            // Header: "Custom settings" (Matches Screenshots 6 & 7)
            item {
                Text(
                    text = "Custom settings",
                    color = DolbyTextPrimary,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 28.dp)
                        .testTag("custom_settings_header")
                )
            }

            // Top 10-Band Vertical Pill Slider Bank (Matches Screenshot 6)
            item {
                TenBandVerticalEqualiserBank(
                    bandGainsDb = profile.manualBandGainsDb,
                    onBandGainChanged = onUpdateBandGain,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
            }

            // Section: "Choose equaliser" (Blue subheader)
            item {
                Text(
                    text = stringResource(id = R.string.choose_equaliser),
                    color = DolbySectionHeaderBlue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 10.dp)
                )
            }

            items(presetOptions.size) { idx ->
                val preset = presetOptions[idx]
                val isSelected = (profile.equaliserPreset == preset)
                val tagKey = preset.name.lowercase()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPreset(preset) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("custom_preset_row_$tagKey"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = DolbyAccentLightBlue,
                            unselectedColor = Color(0xFFDADCE0)
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("custom_preset_radio_$tagKey")
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = preset.displayName,
                        color = DolbyTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            // Section: "Profile settings" (Blue subheader, Matches Screenshot 7)
            item {
                Text(
                    text = stringResource(id = R.string.profile_settings),
                    color = DolbySectionHeaderBlue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp)
                )

                // Surround virtualiser switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSurroundVirtualiser(!profile.surroundVirtualiser) }
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                        .testTag("custom_surround_virtualiser_row"),
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
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(id = R.string.surround_virtualiser_desc),
                            color = DolbyTextSecondary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }

                    DolbyProfileSwitch(
                        checked = profile.surroundVirtualiser,
                        onCheckedChange = onToggleSurroundVirtualiser,
                        testTag = "custom_surround_virtualiser_switch"
                    )
                }

                // Volume leveller switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleVolumeLeveller(!profile.volumeLeveller) }
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                        .testTag("custom_volume_leveller_row"),
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
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(id = R.string.volume_leveller_desc),
                            color = DolbyTextSecondary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }

                    DolbyProfileSwitch(
                        checked = profile.volumeLeveller,
                        onCheckedChange = onToggleVolumeLeveller,
                        testTag = "custom_volume_leveller_switch"
                    )
                }
            }
        }
    }
}

/**
 * 10-Band Vertical Pill Slider Bank (31.5Hz to 16kHz) matching Screenshot 6:
 * - Upper track: light warm grey (#DCDAD5, 5.dp wide)
 * - Lower active track: deep cobalt blue (#004A99, 5.dp wide)
 * - Thumb cap: wide rounded horizontal light-blue pill (#8AB4F8, 26.dp x 14.dp)
 */
@Composable
fun TenBandVerticalEqualiserBank(
    bandGainsDb: List<Float>,
    onBandGainChanged: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.testTag("ten_band_equaliser_bank")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(235.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (bandIdx in 0 until 10) {
                val targetDb = bandGainsDb.getOrElse(bandIdx) { 0f }
                val animatedDb by animateFloatAsState(
                    targetValue = targetDb,
                    animationSpec = tween(durationMillis = 120),
                    label = "eq_band_$bandIdx"
                )
                val freqLabel = DolbyConstants.ISO_10_BAND_LABELS[bandIdx]

                VerticalEqPillSlider(
                    bandIndex = bandIdx,
                    freqLabel = freqLabel,
                    gainDb = animatedDb,
                    onGainChange = { newDb -> onBandGainChanged(bandIdx, newDb) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Bass (left) and Treble (right) labels matching Screenshot 6
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(id = R.string.bass_label),
                color = DolbyTextSecondary,
                fontSize = 14.sp
            )
            Text(
                text = stringResource(id = R.string.treble_label),
                color = DolbyTextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun VerticalEqPillSlider(
    bandIndex: Int,
    freqLabel: String,
    gainDb: Float,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .semantics {
                contentDescription = "Equaliser band $freqLabel Hz, ${"%.1f".format(gainDb)} dB"
            }
            .pointerInput(bandIndex) {
                detectTapGestures { offset ->
                    val frac = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                    val db = frac * 20f - 10f
                    onGainChange(db)
                }
            }
            .pointerInput(bandIndex) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val frac = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                    val db = frac * 20f - 10f
                    onGainChange(db)
                }
            }
            .testTag("eq_slider_band_$bandIndex")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerX = w * 0.5f
            val trackWidth = 5.5.dp.toPx()
            val thumbWidth = 25.dp.toPx().coerceAtMost(w * 0.90f)
            val thumbHeight = 14.dp.toPx()
            val thumbPadding = thumbHeight * 0.5f

            val usableHeight = h - thumbHeight
            val normalized = ((gainDb.coerceIn(-10f, 10f) + 10f) / 20f) // 0 at bottom (-10dB), 1 at top (+10dB)
            val thumbCenterY = thumbPadding + usableHeight * (1f - normalized)

            // 1. Upper Unselected Track (#DCDAD5 light warm grey, from top to thumbCenterY)
            drawRect(
                color = DolbySliderTrackLightGrey,
                topLeft = Offset(centerX - trackWidth * 0.5f, 0f),
                size = Size(trackWidth, thumbCenterY)
            )

            // 2. Lower Filled Active Track (#004A99 deep cobalt blue, from thumbCenterY to bottom)
            drawRect(
                color = DolbySliderDeepBlue,
                topLeft = Offset(centerX - trackWidth * 0.5f, thumbCenterY),
                size = Size(trackWidth, h - thumbCenterY)
            )

            // 3. Wide Rounded Horizontal Pill Thumb Cap (#8AB4F8)
            drawRoundRect(
                color = DolbyAccentLightBlue,
                topLeft = Offset(centerX - thumbWidth * 0.5f, thumbCenterY - thumbHeight * 0.5f),
                size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbHeight * 0.5f, thumbHeight * 0.5f)
            )
        }
    }
}
