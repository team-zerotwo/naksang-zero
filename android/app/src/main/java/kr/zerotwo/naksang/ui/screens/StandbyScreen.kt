package kr.zerotwo.naksang.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.zerotwo.naksang.R
import kr.zerotwo.naksang.ui.components.DisplayText
import kr.zerotwo.naksang.ui.components.NaksangIcon
import kr.zerotwo.naksang.ui.components.StatusLine
import kr.zerotwo.naksang.ui.theme.Gmarket
import kr.zerotwo.naksang.ui.theme.NaksangColors
import kr.zerotwo.naksang.ui.theme.Pretendard
import kr.zerotwo.naksang.util.KoreanTime

@Composable
fun StandbyScreen(
    batteryPercent: Int,
    charging: Boolean,
    heartbeatOk: Boolean?,
    lastHeartbeatAt: Long?,
    demoOffline: Boolean,
    watchingSince: Long?,
    cameraError: String?,
    nowMillis: Long = System.currentTimeMillis(),
    ticking: Boolean = true,
    onEditAddress: () -> Unit,
    onOpenSettings: () -> Unit,
    onSecretTest: () -> Unit,
) {
    var now by remember { mutableLongStateOf(nowMillis) }
    LaunchedEffect(ticking) {
        if (!ticking) return@LaunchedEffect
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val since = watchingSince ?: now
    val sinceCaption = KoreanTime.watchingCaption(since, now)
    val duration = KoreanTime.durationLabel(since, now)
    val heart = KoreanTime.heartbeatCaption(lastHeartbeatAt, heartbeatOk, now, demoOffline)
    val linkWord = when {
        demoOffline -> "데모"
        heartbeatOk == true -> "정상"
        heartbeatOk == false -> "끊김"
        else -> "확인 중"
    }
    val linkColor = if (heartbeatOk == false) NaksangColors.Warn else NaksangColors.Text
    val lowBattery = batteryPercent in 0..40 && !charging
    val detectSub = if (sinceCaption.startsWith("오늘")) "오늘 켜짐" else "켜진 시간"

    Column(
        Modifier
            .fillMaxSize()
            .background(NaksangColors.Bg)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(500.dp)) {
            Image(
                painter = painterResource(R.drawable.wave_sage),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopStart,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "낙상제로",
                modifier = Modifier.statusBarsPadding().padding(start = 24.dp, top = 14.dp),
                fontFamily = Gmarket,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = NaksangColors.Text,
            )
            Image(
                painter = painterResource(R.drawable.illust_house_calm),
                contentDescription = "지켜보는 집",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 36.dp)
                    .offset(x = 64.dp)
                    .width(300.dp)
                    .secretHold(onSecretTest),
            )
            Column(Modifier.statusBarsPadding().padding(start = 24.dp, top = 78.dp).width(190.dp)) {
                StatusLine(R.drawable.ic_check, "켜져 있어요", NaksangColors.Ok)
                DisplayText("지켜\n보는\n중", 52.sp, Modifier.padding(top = 6.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 28.dp, end = 24.dp)) {
                Text("평소처럼 지내세요", fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = NaksangColors.Text)
                Text(sinceCaption, fontFamily = Pretendard, fontSize = 15.sp, color = NaksangColors.Sub)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1.2f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NaksangIcon(R.drawable.ic_bolt, if (lowBattery) NaksangColors.Warn else NaksangColors.Sub, size = 16.dp)
                    Text("배터리", color = if (lowBattery) NaksangColors.Warn else NaksangColors.Sub, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    DisplayText(
                        if (batteryPercent >= 0) batteryPercent.toString() else "—",
                        40.sp,
                        color = if (lowBattery) NaksangColors.Warn else NaksangColors.Text,
                    )
                    if (batteryPercent >= 0) {
                        Text("%", color = if (lowBattery) NaksangColors.Warn else NaksangColors.Text, fontFamily = Gmarket, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            }
            Metric(label = "연결", value = linkWord, sub = heart, valueColor = linkColor)
            Metric(label = "감지", value = duration, sub = detectSub, valueColor = NaksangColors.Text)
        }

        if (lowBattery) {
            Row(
                Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth()
                    .background(NaksangColors.WarnBg)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NaksangIcon(R.drawable.ic_bolt, NaksangColors.Warn, size = 22.dp)
                Column(Modifier.weight(1f)) {
                    Text("충전기를 꽂아 두세요", color = NaksangColors.Warn, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("배터리 $batteryPercent%", color = NaksangColors.Warn, fontFamily = Pretendard, fontSize = 15.sp)
                }
            }
        }

        if (cameraError != null) {
            Text(
                cameraError,
                color = NaksangColors.Warn,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Action(R.drawable.ic_edit, "주소 수정", Modifier.weight(1f), onEditAddress)
            Box(Modifier.width(1.5.dp).height(28.dp).background(NaksangColors.Border))
            Action(R.drawable.ic_sun, "설정", Modifier.weight(1f).padding(start = 16.dp), onOpenSettings)
        }

        Row(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NaksangIcon(R.drawable.ic_lock, NaksangColors.Sub, size = 18.dp)
            Text("영상은 저장하지 않아요 · 폰 안에서만 판단해요", color = NaksangColors.Sub, fontSize = 15.sp, fontFamily = Pretendard)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, sub: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(Modifier.height(88.dp)) {
        Box(Modifier.width(1.5.dp).fillMaxHeight().padding(vertical = 8.dp).background(NaksangColors.Border))
        Column(Modifier.padding(start = 16.dp)) {
            Text(label, color = NaksangColors.Sub, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            DisplayText(value, 26.sp, Modifier.padding(top = 6.dp), color = valueColor)
            Text(sub, color = NaksangColors.Sub, fontFamily = Pretendard, fontSize = 15.sp)
        }
    }
}

@Composable
private fun Action(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NaksangIcon(icon, NaksangColors.Text, size = 20.dp)
        Text(label, fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = NaksangColors.Text)
    }
}

private fun Modifier.secretHold(onFire: () -> Unit): Modifier = pointerInput(onFire) {
    awaitEachGesture {
        awaitFirstDown()
        try {
            withTimeout(5_000) { waitForUpOrCancellation() }
        } catch (_: PointerEventTimeoutCancellationException) {
            onFire()
            waitForUpOrCancellation()
        }
    }
}
