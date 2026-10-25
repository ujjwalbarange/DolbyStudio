package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AudioMode
import com.example.model.EqualiserPreset
import com.example.model.ModeProfileSettings
import com.example.ui.components.DolbyProfileSwitch
import com.example.ui.components.DolbyTopBar
import com.example.ui.theme.DolbyAccentLightBlue
import com.example.ui.theme.DolbyAuraCyan
import com.example.ui.theme.DolbyGridLine
import com.example.ui.theme.DolbyObsidianBg
import com.example.ui.theme.DolbySectionHeaderBlue
import com.example.ui.theme.DolbyTextPrimary
import com.example.ui.theme.DolbyTextSecondary

@Composable
fun PresetSettingsScreen(
    mode: AudioMode,
    profile: ModeProfileSettings,
    onSelectPreset: (EqualiserPreset) -> Unit,
    onUpdateMacroTilt: (Float, Float) -> Unit,
    onToggleSurroundVirtualiser: (Boolean) -> Unit,
    onToggleVolumeLeveller: (Boolean) -> Unit,
    onResetProfile: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    val presetOptions = listOf(
        EqualiserPreset.BRILLIANT_TREBLE,
        EqualiserPreset.BASS_BOOST,
        EqualiserPreset.VOCAL_BOOST,
        EqualiserPreset.FLAT
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DolbyObsidianBg)
            .testTag("preset_settings_screen")
    ) {
        DolbyTopBar(
            onBackClick = onBackClick,
            onResetProfile = onResetProfile
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            // Header: "Music settings" / "Film settings" / "Game settings" / "Podcast settings"
            item {
                Text(
                    text = "${mode.title} settings",
                    color = DolbyTextPrimary,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 28.dp)
                        .testTag("preset_settings_header")
                )
            }

            // Top Animated Cubic Bézier Frequency Response Graph (Matches Screenshot 4)
            item {
                CubicBezierFrequencyGraph(
                    preset = profile.equaliserPreset,
                    macroBassTiltDb = profile.macroBassTiltDb,
                    macroTrebleTiltDb = profile.macroTrebleTiltDb,
                    onMacroTiltDrag = onUpdateMacroTilt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }

            // Section: "Choose equaliser" (Blue subheader)
            item {
                Text(
                    text = stringResource(id = R.string.choose_equaliser),
                    color = DolbySectionHeaderBlue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 10.dp)
                )
            }

            items(presetOptions.size) { index ->
                val preset = presetOptions[index]
                val isSelected = (profile.equaliserPreset == preset)
                val tagKey = preset.name.lowercase()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPreset(preset) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("preset_radio_row_$tagKey"),
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
                            .testTag("preset_radio_$tagKey")
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

            // Section: "Profile settings" (Blue subheader, Matches Screenshot 3)
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
                        .testTag("preset_surround_virtualiser_row"),
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
                        testTag = "preset_surround_virtualiser_switch"
                    )
                }

                // Volume leveller switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleVolumeLeveller(!profile.volumeLeveller) }
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                        .testTag("preset_volume_leveller_row"),
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
                        testTag = "preset_volume_leveller_switch"
                    )
                }
            }
        }
    }
}

@Composable
fun CubicBezierFrequencyGraph(
    preset: EqualiserPreset,
    macroBassTiltDb: Float,
    macroTrebleTiltDb: Float,
    onMacroTiltDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val rawNodes = when (preset) {
        EqualiserPreset.BRILLIANT_TREBLE -> floatArrayOf(0.0f, 0.35f, 3.2f, 8.2f)
        EqualiserPreset.BASS_BOOST -> floatArrayOf(8.2f, 4.6f, 0.6f, 0.0f)
        EqualiserPreset.VOCAL_BOOST -> floatArrayOf(-1.2f, 5.2f, 5.6f, -0.8f)
        EqualiserPreset.FLAT -> floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
        EqualiserPreset.MANUAL -> floatArrayOf(5.5f, 0.8f, 1.5f, 7.8f)
    }

    val p0 by animateFloatAsState(
        targetValue = (rawNodes[0] + macroBassTiltDb).coerceIn(-10f, 10f),
        animationSpec = tween(durationMillis = 280),
        label = "bezier_p0"
    )
    val p2 by animateFloatAsState(
        targetValue = (rawNodes[2] + macroTrebleTiltDb * 0.45f).coerceIn(-10f, 10f),
        animationSpec = tween(durationMillis = 280),
        label = "bezier_p2"
    )
    val p3 by animateFloatAsState(
        targetValue = (rawNodes[3] + macroTrebleTiltDb).coerceIn(-10f, 10f),
        animationSpec = tween(durationMillis = 280),
        label = "bezier_p3"
    )

    Column(modifier = modifier.testTag("frequency_response_graph")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(225.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .width(58.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "+10 db",
                    color = DolbyTextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "0 db",
                    color = DolbyTextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "-10 db",
                    color = DolbyTextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(macroBassTiltDb, macroTrebleTiltDb) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaDb = (-dragAmount.y / size.height) * 20f
                            if (change.position.x < size.width * 0.5f) {
                                onMacroTiltDrag(macroBassTiltDb + deltaDb, macroTrebleTiltDb)
                            } else {
                                onMacroTiltDrag(macroBassTiltDb, macroTrebleTiltDb + deltaDb)
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val midY = h * 0.5f

                    for (col in 0..3) {
                        val x = (w / 3f) * col
                        drawLine(
                            color = DolbyGridLine.copy(alpha = 0.65f),
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    drawLine(
                        color = DolbyGridLine.copy(alpha = 0.65f),
                        start = Offset(0f, midY),
                        end = Offset(w, midY),
                        strokeWidth = 1.dp.toPx()
                    )

                    fun dbToY(db: Float): Float {
                        val normalized = (db.coerceIn(-10f, 10f) + 10f) / 20f
                        return h * (1f - normalized)
                    }

                    val y0 = dbToY(p0)
                    val y2 = dbToY(p2)
                    val y3 = dbToY(p3)

                    val x0 = 0f
                    val x1 = w / 3f
                    val x2 = (2f * w) / 3f
                    val x3 = w

                    val mainSpline = Path().apply {
                        moveTo(x0, y0)
                        cubicTo(
                            x1 * 0.65f, y0,
                            x2 * 0.55f, y2,
                            x3, y3
                        )
                    }

                    drawPath(
                        path = mainSpline,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                DolbyAccentLightBlue.copy(alpha = 0.22f),
                                DolbyAuraCyan.copy(alpha = 0.25f)
                            )
                        ),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )

                    drawPath(
                        path = mainSpline,
                        color = DolbyAccentLightBlue,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 58.dp),
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
