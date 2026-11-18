package com.puretube.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary

/** Small circular (?) badge that expands a plain-English explanation inline. */
@Composable
fun InlineTooltipBadge(tooltipExplanation: String) {
    var showTooltip by remember { mutableStateOf(false) }
    Column {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(1.dp, PureAccent, CircleShape)
                .clickable { showTooltip = !showTooltip },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "?",
                color = PureAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        AnimatedVisibility(visible = showTooltip) {
            Text(
                text = tooltipExplanation,
                color = PureTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF222222), RoundedCornerShape(6.dp))
                    .padding(10.dp)
            )
        }
    }
}

/**
 * A settings row: title, universal (?) tooltip badge, and a trailing control.
 * The tooltip expands inline below the row — no modal popups anywhere.
 */
@Composable
fun SettingRowWithTooltip(
    title: String,
    tooltipExplanation: String,
    modifier: Modifier = Modifier,
    trailingControl: @Composable () -> Unit
) {
    var showTooltip by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(1.dp, PureAccent, CircleShape)
                    .clickable { showTooltip = !showTooltip },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    color = PureAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            trailingControl()
        }
        AnimatedVisibility(visible = showTooltip) {
            Text(
                text = tooltipExplanation,
                color = PureTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF222222), RoundedCornerShape(6.dp))
                    .padding(10.dp)
            )
        }
    }
}
