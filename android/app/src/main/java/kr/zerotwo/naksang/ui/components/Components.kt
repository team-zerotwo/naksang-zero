package kr.zerotwo.naksang.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.zerotwo.naksang.ui.theme.NaksangColors
import kr.zerotwo.naksang.ui.theme.Pretendard
import kr.zerotwo.naksang.ui.theme.displayStyle

@Composable
fun NaksangIcon(
    res: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    description: String? = null,
) {
    Icon(
        painter = painterResource(res),
        contentDescription = description,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@Composable
fun NaksangButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    container: Color = NaksangColors.Btn,
    content: Color = NaksangColors.BtnText,
    border: Color? = null,
    height: Dp = 58.dp,
    fontSize: TextUnit = 18.sp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (height >= 70.dp) 20.dp else 16.dp)
    val bg = if (enabled) container else container.copy(alpha = 0.45f)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .height(height)
            .clip(shape)
            .background(bg)
            .then(if (border != null) Modifier.border(2.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                NaksangIcon(icon, content, size = if (height >= 70.dp) 26.dp else 22.dp)
            }
            Text(
                text = text,
                color = content,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
            )
        }
    }
}

@Composable
fun DisplayText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = NaksangColors.Text,
) {
    Text(text = text, modifier = modifier, style = displayStyle(size, color))
}

@Composable
fun FieldRow(
    label: String,
    optional: Boolean = false,
    icon: Int? = null,
    iconTint: Color = NaksangColors.Text,
    last: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = if (optional) "$label\n(선택)" else label,
            modifier = Modifier.width(84.dp),
            color = NaksangColors.Sub,
            fontFamily = Pretendard,
            fontSize = 15.sp,
            lineHeight = 18.sp,
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (icon != null) {
            NaksangIcon(icon, iconTint, size = 22.dp)
        }
    }
}

@Composable
fun RowDivider(top: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.5.dp)
            .background(NaksangColors.Border),
    )
}

@Composable
fun WarnBadge(text: String) {
    Text(
        text = text,
        color = NaksangColors.Warn,
        fontFamily = Pretendard,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier
            .background(NaksangColors.WarnBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusLine(icon: Int, text: String, color: Color, iconSize: Dp = 20.dp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NaksangIcon(icon, color, size = iconSize, description = null)
        Text(
            text = text,
            color = color,
            fontFamily = Pretendard,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}
