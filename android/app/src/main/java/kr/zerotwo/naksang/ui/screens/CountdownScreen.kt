package kr.zerotwo.naksang.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.zerotwo.naksang.R
import kr.zerotwo.naksang.ui.components.DisplayText
import kr.zerotwo.naksang.ui.components.NaksangButton
import kr.zerotwo.naksang.ui.components.StatusLine
import kr.zerotwo.naksang.ui.theme.NaksangColors
import kr.zerotwo.naksang.ui.theme.Pretendard
import kr.zerotwo.naksang.ui.theme.displayStyle
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CountdownScreen(
    endsAt: Long,
    totalMs: Long,
    isTest: Boolean,
    secondsOverride: Int? = null,
    previewOnly: Boolean = false,
    onOk: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt, secondsOverride) {
        if (secondsOverride != null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val remainMs = (endsAt - now).coerceAtLeast(0L)
    val seconds = secondsOverride ?: ((remainMs + 999) / 1000).toInt()
    val progress = if (secondsOverride != null) {
        secondsOverride / 30f
    } else {
        (remainMs.toFloat() / totalMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    }

    val scrollState = rememberScrollState()
    Column(Modifier.fillMaxSize().background(NaksangColors.WarnBg)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val ring = countdownRingSize(maxHeight)
            // Title, ring gap, info lines, and the padding above the button.
            // A little slack so a tight estimate scrolls instead of clipping.
            val fits = maxHeight >= ring + CountdownChrome + 16.dp
            if (fits) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        CountdownTitle(previewOnly, isTest)
                        Spacer(Modifier.height(12.dp))
                        CountdownRingBlock(
                            ring,
                            seconds,
                            progress,
                            previewOnly,
                            Modifier.align(Alignment.CenterHorizontally),
                        )
                        Spacer(Modifier.weight(1f))
                        CountdownInfo()
                        Spacer(Modifier.height(28.dp))
                    }
                    WorryHouse(Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    CountdownTitle(previewOnly, isTest)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        CountdownRingBlock(ring, seconds, progress, previewOnly)
                        WorryHouse(Modifier.align(Alignment.BottomEnd))
                    }
                    Spacer(Modifier.height(12.dp))
                    CountdownInfo()
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            NaksangButton(
                text = "괜찮아요",
                onClick = onOk,
                icon = R.drawable.ic_check,
                container = NaksangColors.Text,
                content = androidx.compose.ui.graphics.Color.White,
                height = 72.dp,
                fontSize = 22.sp,
            )
        }
    }
}

/** Space the title, info lines, and gaps take besides the ring. */
private val CountdownChrome = 280.dp

/**
 * Original ring is 320dp. Short screens shrink it, but not below 220dp;
 * anything that still does not fit scrolls above the pinned button.
 */
internal fun countdownRingSize(viewportHeight: Dp): Dp {
    return (viewportHeight - CountdownChrome).coerceIn(220.dp, 320.dp)
}

@Composable
private fun CountdownTitle(previewOnly: Boolean, isTest: Boolean) {
    Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
        StatusLine(
            R.drawable.ic_warn,
            when {
                previewOnly -> "미리보기 · 알림은 가지 않아요"
                isTest -> "테스트 · 넘어지신 것 같아요"
                else -> "넘어지신 것 같아요"
            },
            NaksangColors.Warn,
        )
        DisplayText("괜찮으세요?", 44.sp, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CountdownRingBlock(
    ring: Dp,
    seconds: Int,
    progress: Float,
    previewOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val digit = (128f * ring.value / 320f).coerceAtLeast(72f).sp
    Box(modifier.size(ring), contentAlignment = Alignment.Center) {
        CountdownRing(progress, Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = seconds.toString(),
                style = displayStyle(digit),
                textAlign = TextAlign.Center,
            )
            Text(
                if (previewOnly) "초 · 화면만 재생해요" else "초 뒤 보호자에게 알려요",
                color = NaksangColors.Warn,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        }
    }
}

@Composable
private fun CountdownInfo() {
    Column(Modifier.padding(start = 24.dp, end = 24.dp).width(240.dp)) {
        InfoLine("받는 사람", "보호자")
        InfoLine("함께 보내는 내용", "주소 · 감지 시각")
    }
}

@Composable
private fun WorryHouse(modifier: Modifier) {
    Image(
        painter = painterResource(R.drawable.illust_house_worry),
        contentDescription = null,
        modifier = modifier
            .offset(x = 48.dp)
            .width(180.dp)
            .rotate(-8f),
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = NaksangColors.Sub, fontFamily = Pretendard, fontSize = 15.sp)
        Text("  ", fontSize = 15.sp)
        Text(value, color = NaksangColors.Text, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun CountdownRing(progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 22.dp.toPx()
        val radius = size.minDimension / 2f - stroke - 18.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        drawArc(
            color = NaksangColors.Clay250,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = NaksangColors.Warn,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        repeat(30) { index ->
            val rad = Math.toRadians(index * 12.0 - 90.0)
            val outer = radius + stroke / 2f + 10.dp.toPx()
            val inner = outer - if (index % 5 == 0) 14.dp.toPx() else 8.dp.toPx()
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            drawLine(
                color = NaksangColors.Warn,
                start = Offset(center.x + c * inner, center.y + s * inner),
                end = Offset(center.x + c * outer, center.y + s * outer),
                strokeWidth = if (index % 5 == 0) 3.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
