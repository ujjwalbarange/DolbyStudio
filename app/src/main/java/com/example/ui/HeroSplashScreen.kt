package com.example.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.DolbyMasterCardSwitch
import com.example.ui.components.DolbyTopBar
import com.example.ui.theme.DolbyAuraCyan
import com.example.ui.theme.DolbyAuraRoyal
import com.example.ui.theme.DolbyMasterPillWhite
import com.example.ui.theme.DolbyMasterTextDark
import com.example.ui.theme.DolbyObsidianBg
import com.example.ui.theme.DolbyTextPrimary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HeroSplashScreen(
    useAudioEffects: Boolean,
    onToggleAudioEffects: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "aura_wave_transition")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aura_phase"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DolbyObsidianBg)
            .testTag("hero_splash_screen")
    ) {
        // 1. Lifestyle Hero Background Visual (Screenshot 1)
        Image(
            painter = painterResource(id = R.drawable.hero_listener_bg),
            contentDescription = "Dolby Atmos listener hero visual",
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Concentric Blue Soundwave Ring (#68D8FF to #3B82F6)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("hero_soundwave_aura_canvas")
        ) {
            val centerX = size.width * 0.50f
            val centerY = size.height * 0.255f
            val baseRadius = size.width * 0.39f
            val activeScale = if (useAudioEffects) 0.30f else 0.08f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        DolbyAuraCyan.copy(alpha = 0.20f * (0.5f + activeScale)),
                        DolbyAuraRoyal.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(centerX, centerY),
                    radius = baseRadius * 1.24f
                ),
                center = Offset(centerX, centerY),
                radius = baseRadius * 1.24f
            )

            val numPoints = 96
            for (ring in 0..2) {
                val path = Path()
                val ringScale = 1.0f + (ring - 1) * 0.025f
                val freqHarmonic = 5 + ring * 2
                val amp = size.width * 0.011f * (if (useAudioEffects) 1f else 0.2f)

                for (i in 0..numPoints) {
                    val angle = (i.toFloat() / numPoints) * (2f * PI.toFloat())
                    val r = baseRadius * ringScale +
                        amp * sin(angle * freqHarmonic + wavePhase * (ring + 1)) +
                        (amp * 0.5f) * cos(angle * 3f - wavePhase)
                    val px = centerX + r * cos(angle)
                    val py = centerY + r * sin(angle)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()

                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            DolbyAuraCyan.copy(alpha = if (ring == 1) 0.90f else 0.42f),
                            DolbyAuraRoyal.copy(alpha = if (ring == 1) 0.85f else 0.35f),
                            DolbyAuraCyan.copy(alpha = if (ring == 1) 0.90f else 0.42f)
                        )
                    ),
                    style = Stroke(width = if (ring == 1) 6.5f else 3f)
                )
            }
        }

        // 3. Smooth vertical gradient into #0B0E14
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0x330B0E14),
                        0.45f to Color.Transparent,
                        0.64f to Color(0xB80B0E14),
                        0.82f to Color(0xEB0B0E14),
                        1.0f to DolbyObsidianBg
                    )
                )
        )

        // 4. Foreground Content Layer (Matches Screenshot 1 with zero clutter)
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            DolbyTopBar(
                onBackClick = onOpenSettings
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.hero_title),
                    color = DolbyTextPrimary,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.testTag("hero_title_text")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(id = R.string.hero_subtitle),
                    color = Color(0xFFE8EAED),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Rounded White Pill Card ("Use Audio effects" + Blue Switch with Checkmark)
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = DolbyMasterPillWhite,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(78.dp)
                        .clickable { onToggleAudioEffects(!useAudioEffects) }
                        .testTag("use_audio_effects_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
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
                            modifier = Modifier.testTag("use_audio_effects_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Bottom Row: Left Settings Gear Icon + Right "-> Learn more" Button (Screenshot 1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC262C36))
                            .border(1.dp, Color(0x22FFFFFF), CircleShape)
                            .clickable { onOpenSettings() }
                            .testTag("hero_settings_gear_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Open Settings",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xCC262C36))
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(24.dp))
                            .clickable { onOpenSettings() }
                            .padding(horizontal = 20.dp)
                            .testTag("hero_learn_more_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.learn_more),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
