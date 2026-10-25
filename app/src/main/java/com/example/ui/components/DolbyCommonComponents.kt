package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.ui.theme.DolbyAccentLightBlue
import com.example.ui.theme.DolbyObsidianBg
import com.example.ui.theme.DolbySectionHeaderBlue
import com.example.ui.theme.DolbySurfaceCard
import com.example.ui.theme.DolbyTextPrimary
import com.example.ui.theme.DolbyToggleBrightBlue

@Composable
fun DolbyTopBar(
    onBackClick: () -> Unit,
    onResetProfile: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .size(48.dp)
                .testTag("top_bar_back_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Navigate back",
                tint = DolbyTextPrimary
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("top_bar_overflow_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = DolbyTextPrimary
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(DolbySurfaceCard)
            ) {
                DropdownMenuItem(
                    text = { Text("Reset to default", color = DolbyTextPrimary) },
                    onClick = {
                        menuExpanded = false
                        onResetProfile?.invoke()
                    },
                    modifier = Modifier.testTag("menu_reset_default")
                )
            }
        }
    }
}

/**
 * Circular selection indicator matching the exact #7BA7FF / #8AB4F8 outer ring + filled inner circle
 * style used across the Dolby Atmos Settings screens.
 */
@Composable
fun DolbySelectionIndicator(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val strokeWidth = 2.dp.toPx()
            val ringColor = if (selected) DolbySectionHeaderBlue else Color(0xFFDADCE0)
            drawCircle(
                color = ringColor,
                radius = (size.minDimension - strokeWidth) * 0.5f,
                style = Stroke(width = strokeWidth)
            )
            if (selected) {
                drawCircle(
                    color = DolbySectionHeaderBlue,
                    radius = size.minDimension * 0.26f
                )
            }
        }
    }
}

/**
 * Master toggle switch inside the white floating card on Screen 1 (Screenshot 1):
 * Blue pill track (#1B72E8) with a white circle thumb and blue checkmark icon inside.
 */
@Composable
fun DolbyMasterCardSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = tween(durationMillis = 180),
        label = "master_switch_offset"
    )
    val trackColor = if (checked) DolbyToggleBrightBlue else Color(0xFF94A3B8)

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .width(56.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Enabled",
                    tint = DolbyToggleBrightBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Profile settings pill switch on Screens 3, 4, 7 (Surround virtualiser & Volume leveller):
 * Light-blue pill track (#8AB4F8) with a dark obsidian thumb (#0B0E14) when ON.
 */
@Composable
fun DolbyProfileSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = tween(durationMillis = 180),
        label = "profile_switch_offset"
    )
    val trackColor = if (checked) DolbyAccentLightBlue else Color(0xFF282D37)
    val thumbColor = if (checked) DolbyObsidianBg else Color(0xFF9AA0A6)

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .width(56.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)
            .border(
                width = if (checked) 0.dp else 1.5.dp,
                color = if (checked) Color.Transparent else Color(0xFF8E918F),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .testTag(testTag),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(24.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}
