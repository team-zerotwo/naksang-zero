package kr.zerotwo.naksang.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.zerotwo.naksang.R
import kr.zerotwo.naksang.detect.FallConfig
import kr.zerotwo.naksang.ui.components.DisplayText
import kr.zerotwo.naksang.ui.components.NaksangButton
import kr.zerotwo.naksang.ui.components.NaksangIcon
import kr.zerotwo.naksang.ui.theme.NaksangColors
import kr.zerotwo.naksang.ui.theme.Pretendard
import kotlin.math.max

@Composable
fun SettingsScreen(
    serverUrl: String,
    demoOffline: Boolean,
    frontCamera: Boolean,
    fps: Int,
    config: FallConfig,
    connected: Boolean,
    onSave: (server: String, demo: Boolean, front: Boolean, fps: Int, config: FallConfig) -> String?,
    onClear: () -> Unit,
    onStop: () -> Unit,
    watching: Boolean,
    onBack: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(serverUrl) }
    var demo by rememberSaveable { mutableStateOf(demoOffline) }
    var front by rememberSaveable { mutableStateOf(frontCamera) }
    var fpsText by rememberSaveable { mutableStateOf(fps.toString()) }
    var dropMs by rememberSaveable { mutableStateOf(config.dropWindowMs.toString()) }
    var drop by rememberSaveable { mutableStateOf(config.minDrop.toString()) }
    var angle by rememberSaveable { mutableStateOf(config.maxLieAngleDeg.toString()) }
    var still by rememberSaveable { mutableStateOf(config.stillDurationMs.toString()) }
    var move by rememberSaveable { mutableStateOf(config.stillMovement.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(NaksangColors.Bg)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                NaksangIcon(R.drawable.ic_back, NaksangColors.Text, size = 26.dp, description = "뒤로")
            }
            DisplayText("설정", 28.sp)
        }
        Text(
            "시연 전에 여기서 서버와 감지 민감도를 맞출 수 있어요.",
            color = NaksangColors.Sub,
            fontFamily = Pretendard,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )

        SettingField("서버 주소", url, { url = it }, KeyboardType.Uri)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("오프라인 데모", fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("서버 호출을 건너뛰고 화면만 확인해요", color = NaksangColors.Sub, fontSize = 15.sp, fontFamily = Pretendard)
            }
            Switch(
                checked = demo,
                onCheckedChange = { demo = it },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = NaksangColors.Btn,
                    checkedThumbColor = NaksangColors.Surface,
                    uncheckedTrackColor = NaksangColors.Border,
                    uncheckedThumbColor = NaksangColors.Surface,
                ),
            )
        }
        Text("카메라", color = NaksangColors.Sub, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            UnderlineChoice("전면", front) { front = true }
            UnderlineChoice("후면", !front) { front = false }
        }
        Text(
            "어르신이 화면의 괜찮아요를 누를 수 있게, 보통은 전면 카메라가 방을 보게 둡니다.",
            color = NaksangColors.Sub,
            fontSize = 15.sp,
            fontFamily = Pretendard,
        )

        Text("감지 기준", color = NaksangColors.Sub, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
        SettingField("떨어지는 시간 (ms)", dropMs, { dropMs = it }, KeyboardType.Number)
        SettingField("떨어지는 크기 (0~1)", drop, { drop = it }, KeyboardType.Decimal)
        SettingField("눕는 각도 (수평 0°)", angle, { angle = it }, KeyboardType.Decimal)
        SettingField("가만히 있는 시간 (ms)", still, { still = it }, KeyboardType.Number)
        SettingField("움직임 허용 (0~1)", move, { move = it }, KeyboardType.Decimal)
        SettingField("초당 분석 횟수", fpsText, { fpsText = it }, KeyboardType.Number)

        if (error != null) {
            Text(error!!, color = NaksangColors.Warn, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        NaksangButton("저장") {
            val parsed = parse(dropMs, drop, angle, still, move, fpsText)
            if (parsed == null) {
                error = "숫자를 다시 확인해 주세요."
            } else {
                val (cfg, frames) = parsed
                error = onSave(url, demo, front, frames, cfg)
            }
        }
        if (watching) {
            Text(
                "감지를 잠시 끄기",
                color = NaksangColors.Warn,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onStop)
                    .padding(vertical = 12.dp),
            )
        }
        if (connected) {
            Text(
                "이 폰의 연결 지우기",
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onClear)
                    .padding(vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun parse(
    dropMs: String,
    drop: String,
    angle: String,
    still: String,
    move: String,
    fps: String,
): Pair<FallConfig, Int>? {
    val dropWindow = dropMs.toLongOrNull() ?: return null
    val minDrop = drop.toFloatOrNull() ?: return null
    val lie = angle.toFloatOrNull() ?: return null
    val stillMs = still.toLongOrNull() ?: return null
    val stillMove = move.toFloatOrNull() ?: return null
    val frames = fps.toIntOrNull() ?: return null
    if (dropWindow !in 200..5_000) return null
    if (minDrop !in 0.05f..0.8f) return null
    if (lie !in 8f..70f) return null
    if (stillMs !in 400..10_000) return null
    if (stillMove !in 0.005f..0.3f) return null
    if (frames !in 2..10) return null
    val release = (lie + 17f).coerceAtMost(80f)
    val upright = max(58f, lie + 20f).coerceAtMost(89f)
    return FallConfig(
        dropWindowMs = dropWindow,
        minDrop = minDrop,
        maxLieAngleDeg = lie,
        lieAngleReleaseDeg = release,
        uprightAngleDeg = upright,
        stillDurationMs = stillMs,
        stillMovement = stillMove,
    ) to frames
}

@Composable
private fun SettingField(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = NaksangColors.Sub, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = NaksangColors.Text),
            cursorBrush = SolidColor(NaksangColors.Btn),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .heightIn(min = 48.dp)
                .border(width = 1.5.dp, color = NaksangColors.Border, shape = RoundedCornerShape(0.dp))
                .padding(horizontal = 0.dp, vertical = 12.dp),
            decorationBox = { inner -> Box { inner() } },
        )
    }
}

@Composable
private fun UnderlineChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) NaksangColors.Btn else NaksangColors.Text,
        fontFamily = Pretendard,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 16.sp,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .then(
                if (selected) {
                    Modifier.border(width = 0.dp, color = NaksangColors.Btn)
                } else {
                    Modifier
                },
            ),
    )
}
