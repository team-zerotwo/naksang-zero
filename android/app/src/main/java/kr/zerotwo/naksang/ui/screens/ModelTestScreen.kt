package kr.zerotwo.naksang.ui.screens

import android.app.Activity
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kr.zerotwo.naksang.R
import kr.zerotwo.naksang.detect.DetectSignal
import kr.zerotwo.naksang.detect.FallDebugSnapshot
import kr.zerotwo.naksang.detect.formatEventClock
import kr.zerotwo.naksang.detect.formatSeconds
import kr.zerotwo.naksang.detect.lyingPathStatus
import kr.zerotwo.naksang.detect.modelPathStatus
import kr.zerotwo.naksang.detect.rulePathStatus
import kr.zerotwo.naksang.session.ModelTestUi
import kr.zerotwo.naksang.ui.components.DisplayText
import kr.zerotwo.naksang.ui.components.NaksangButton
import kr.zerotwo.naksang.ui.components.NaksangIcon
import kr.zerotwo.naksang.ui.components.RowDivider
import kr.zerotwo.naksang.ui.components.StatusLine
import kr.zerotwo.naksang.ui.theme.NaksangColors
import kr.zerotwo.naksang.ui.theme.Pretendard
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ModelTestScreen(
    ui: ModelTestUi,
    mirrorX: Boolean,
    minVisibility: Float,
    demoOffline: Boolean,
    analysisFps: Int,
    cameraError: String?,
    onPreviewReady: (androidx.camera.core.Preview.SurfaceProvider, ViewPort?) -> Unit,
    onPreviewGone: () -> Unit,
    onBack: () -> Unit,
    onClearLog: () -> Unit,
    onExportLog: () -> Unit,
    onPickVideo: (android.net.Uri) -> Unit,
    onStopReplay: () -> Unit,
) {
    var showCountdown by remember { mutableStateOf(false) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickVideo(uri)
    }
    if (showCountdown) {
        val view = LocalView.current
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
            @Suppress("DEPRECATION")
            window.navigationBarColor = NaksangColors.WarnBg.toArgb()
        }
        BackHandler { showCountdown = false }
        CountdownScreen(
            endsAt = remember { System.currentTimeMillis() + 30_000L },
            totalMs = 30_000L,
            isTest = true,
            previewOnly = true,
            onOk = { showCountdown = false },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(NaksangColors.Bg),
    ) {
        TestPreview(
            ui = ui,
            mirrorX = mirrorX,
            minVisibility = minVisibility,
            onPreviewReady = onPreviewReady,
            onPreviewGone = onPreviewGone,
            onBack = onBack,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            DisplayText("모델 테스트", 28.sp)
            Text(
                if (demoOffline) {
                    "오프라인 데모가 켜져 있어요. 이 화면은 서버로 보내지 않고, 30초 알림도 시작하지 않아요."
                } else {
                    "이 화면은 서버로 보내지 않아요. 30초 알림도 시작하지 않아요. 감시 중이었다면 이 화면을 보는 동안만 잠시 쉬어요."
                },
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            val firedNow = ui.snapshot.wouldFire.isNotEmpty()
            StatusLine(
                icon = if (firedNow) R.drawable.ic_warn else R.drawable.ic_check,
                text = ui.liveLine,
                color = if (firedNow) NaksangColors.Warn else NaksangColors.Ok,
            )
            val lastFireLine = ui.lastFireLine
            if (lastFireLine != null) {
                Text(
                    lastFireLine,
                    color = NaksangColors.Warn,
                    fontFamily = Pretendard,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (ui.replayRunning) {
                Text(
                    "영상 ${ui.replayIndex + 1} / ${ui.replayTotal.coerceAtLeast(1)} · 약 5fps로 읽어요. 영상은 저장하지 않아요.",
                    color = NaksangColors.Sub,
                    fontFamily = Pretendard,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (cameraError != null) {
                Text(
                    cameraError,
                    color = NaksangColors.Warn,
                    fontFamily = Pretendard,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            val replayError = ui.replayError
            if (replayError != null) {
                Text(
                    replayError,
                    color = NaksangColors.Warn,
                    fontFamily = Pretendard,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            SectionLabel("지금 자세")
            MetricRow("자세", ui.snapshot.posture.korean())
            MetricRow("사람", if (ui.snapshot.personDetected) "보임" else "안 보임")
            MetricRow("몸통 각도", angleText(ui.snapshot.torsoAngleDeg))
            Text(
                "수평이 0°예요. 누워 있음은 설정한 눕는 각도 이하, 서 있음은 가파른 몸통, 앉아 있음은 허벅지가 접히거나 중간 각도예요.",
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            SectionLabel("바닥에 가만히 누움")
            val lie = ui.snapshot
            MetricRow(
                "경과",
                "${formatSeconds(lie.lyingElapsedMs)}초 / ${formatSeconds(lie.lyingThresholdMs)}초",
            )
            MetricRow("상태", lyingPathStatus(lie.lyingEnabled, DetectSignal.LYING_STILL in lie.wouldFire, lie.lyingCounting))
            Text(
                lyingHint(lie),
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SectionLabel("포즈 모델")
            MetricRow(
                "점수",
                String.format(Locale.US, "%.2f / %.2f", lie.modelScore, lie.modelThreshold),
            )
            ScoreBar(lie.modelScore, lie.modelThreshold)
            MetricRow(
                "나무 점수",
                String.format(Locale.US, "%.2f / %.2f", lie.modelGbdtScore, lie.modelGbdtThreshold),
            )
            MetricRow(
                "상태",
                modelPathStatus(lie.modelEnabled, DetectSignal.MODEL in lie.wouldFire, lie.modelStreak) +
                    " · 연속 ${lie.modelStreak}/${lie.modelHoldFrames}",
            )
            Text(
                "점수가 기준 이상인 프레임이 연속으로 차면 모델이 발화할 조건이에요. 막대 끝의 검은 선이 기준이에요.",
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            SectionLabel("어느 경로가 발화할까")
            val ruleElapsed = if (lie.ruleLatched || DetectSignal.RULE in lie.wouldFire) lie.ruleStillElapsedMs else 0L
            val viewElapsed = if (lie.viewLatched || DetectSignal.VIEW_DIRECTION in lie.wouldFire) lie.ruleStillElapsedMs else 0L
            MetricRow(
                "규칙",
                rulePathStatus(DetectSignal.RULE in lie.wouldFire, lie.ruleLatched) +
                    " · 멈춤 ${formatSeconds(ruleElapsed)}초 / ${formatSeconds(lie.ruleStillNeedMs)}초",
            )
            MetricRow(
                "시선 방향",
                rulePathStatus(DetectSignal.VIEW_DIRECTION in lie.wouldFire, lie.viewLatched) +
                    " · 멈춤 ${formatSeconds(viewElapsed)}초 / ${formatSeconds(lie.ruleStillNeedMs)}초",
            )
            MetricRow("포즈 모델", modelPathStatus(lie.modelEnabled, DetectSignal.MODEL in lie.wouldFire, lie.modelStreak))
            MetricRow("빠른 눕힘", if (DetectSignal.FAST_FLATTEN in lie.wouldFire) "지금 발화" else "대기")
            MetricRow("바닥에 누움", lyingPathStatus(lie.lyingEnabled, DetectSignal.LYING_STILL in lie.wouldFire, lie.lyingCounting))
            Text(
                "진행 중은 아직 시간이 덜 찬 상태예요. 지금 발화는 실제 감시였다면 30초 알림이 시작될 프레임이에요.",
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            SectionLabel("프레임")
            MetricRow("분석 속도", fpsText(ui.fps, analysisFps, ui.replayRunning))
            MetricRow("포즈 신뢰도", confidenceText(lie.poseConfidence))
            Text(
                "포즈 신뢰도는 이번 프레임에서 보인 관절 가시성의 평균이에요. 모델을 거르는 0.35 기준과는 다른 숫자예요.",
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            SectionLabel("발화 횟수")
            Text(
                "규칙 ${ui.counts.rule} · 시선 방향 ${ui.counts.viewDirection} · 포즈 모델 ${ui.counts.model} · 빠른 눕힘 ${ui.counts.fastFlatten} · 바닥에 누움 ${ui.counts.lyingStill}",
                color = NaksangColors.Text,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Text(
                "합계 ${ui.counts.total} · 최근 ${ui.events.size}건을 기억해요",
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            if (ui.events.isEmpty()) {
                Text(
                    "아직 발화할 조건이 없었어요.",
                    color = NaksangColors.Sub,
                    fontFamily = Pretendard,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            } else {
                ui.events.asReversed().forEach { event ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            "${formatEventClock(event)}  ${event.signal.korean()}",
                            color = NaksangColors.Text,
                            fontFamily = Pretendard,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                        Text(
                            eventDetail(event),
                            color = NaksangColors.Sub,
                            fontFamily = Pretendard,
                            fontSize = 15.sp,
                        )
                    }
                    RowDivider()
                }
            }

            Spacer(Modifier.height(12.dp))
            if (ui.replayRunning) {
                NaksangButton("영상 그만 보기", container = NaksangColors.Text, onClick = onStopReplay)
            } else {
                NaksangButton("영상 파일로 확인", onClick = { pickVideo.launch(arrayOf("video/*")) })
            }
            Spacer(Modifier.height(8.dp))
            NaksangButton("기록 보내기", icon = R.drawable.ic_share, onClick = onExportLog)
            Text(
                "기록 지우기",
                color = NaksangColors.Warn,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onClearLog)
                    .padding(vertical = 12.dp),
            )
            Text(
                "카운트다운 화면 미리보기",
                color = NaksangColors.Link,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { showCountdown = true }
                    .padding(vertical = 12.dp),
            )
            Text(
                "미리보기는 화면만 보여요. 보호자에게는 가지 않아요.",
                color = NaksangColors.Sub,
                fontFamily = Pretendard,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TestPreview(
    ui: ModelTestUi,
    mirrorX: Boolean,
    minVisibility: Float,
    onPreviewReady: (androidx.camera.core.Preview.SurfaceProvider, ViewPort?) -> Unit,
    onPreviewGone: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(NaksangColors.Text),
    ) {
        if (ui.replayRunning) {
            Box(Modifier.fillMaxSize().background(NaksangColors.Text))
        } else {
            val context = LocalContext.current
            val previewView = remember {
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            }
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(previewView) {
                val push = {
                    val port = runCatching { previewView.viewPort }.getOrNull()
                    onPreviewReady(previewView.surfaceProvider, port)
                }
                val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> push() }
                previewView.addOnLayoutChangeListener(listener)
                previewView.post { push() }
                onDispose {
                    previewView.removeOnLayoutChangeListener(listener)
                    onPreviewGone()
                }
            }
        }
        PosePersonOverlay(
            visible = ui.snapshot.personDetected || ui.landmarks.isNotEmpty(),
            personX = 0.5f,
            personY = 0.5f,
            landmarks = ui.landmarks,
            frameWidth = ui.frameWidth,
            frameHeight = ui.frameHeight,
            mirrorX = mirrorX && !ui.replayRunning,
            minVisibility = minVisibility,
            showBadge = false,
        )
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                NaksangIcon(R.drawable.ic_back, Color.White, size = 26.dp, description = "뒤로")
            }
            Text(
                if (ui.replayRunning) "영상 확인" else "실시간 카메라",
                color = Color.White,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (ui.snapshot.personDetected) "사람 있음" else "사람 없음",
                color = Color.White,
                fontFamily = Pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = NaksangColors.Sub,
        fontFamily = Pretendard,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = NaksangColors.Sub, fontFamily = Pretendard, fontSize = 15.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = NaksangColors.Text,
            fontFamily = Pretendard,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun ScoreBar(score: Double, threshold: Double) {
    val fraction = score.toFloat().coerceIn(0f, 1f)
    val mark = threshold.toFloat().coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(10.dp)
            .background(NaksangColors.Border),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .background(if (score >= threshold && threshold > 0.0) NaksangColors.Warn else NaksangColors.Btn),
        )
        Box(
            Modifier
                .fillMaxWidth(mark)
                .height(10.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.width(2.dp).height(10.dp).background(NaksangColors.Text))
        }
    }
}

private fun angleText(angle: Float?): String {
    if (angle == null) return "—"
    return String.format(Locale.US, "%.0f°", angle)
}

private fun confidenceText(value: Float?): String {
    if (value == null) return "—"
    return String.format(Locale.US, "%.2f", value)
}

private fun fpsText(measured: Float, configured: Int, replay: Boolean): String {
    if (replay) return "영상 5fps"
    val shown = if (measured <= 0f) "—" else String.format(Locale.US, "%.1f", measured)
    return "$shown · 설정 ${configured.coerceAtLeast(0)}"
}

private fun lyingHint(snapshot: FallDebugSnapshot): String {
    val slow = formatSeconds(snapshot.lyingUnwitnessedMs)
    val fast = formatSeconds(snapshot.lyingThresholdMs)
    return when {
        !snapshot.lyingEnabled -> "설정에서 꺼져 있어요. 이 경로는 발화하지 않아요."
        snapshot.lyingCounting && snapshot.lyingWitnessedFast -> "빠른 하강 기준 ${fast}초를 세고 있어요."
        snapshot.lyingCounting -> "천천히 눕거나 이미 누워 있어서 ${slow}초 기준이에요."
        else -> "빠른 하강은 ${formatSeconds(snapshot.lyingThresholdMs)}초, 그렇지 않으면 ${slow}초를 기다려요."
    }
}

private fun eventDetail(event: kr.zerotwo.naksang.detect.WouldTriggerEvent): String {
    val angle = event.torsoAngleDeg?.roundToInt()?.let { "${it}°" } ?: "—"
    val score = String.format(Locale.US, "%.2f", event.modelScore)
    val lying = formatSeconds(event.lyingElapsedMs)
    val need = formatSeconds(event.lyingThresholdMs)
    val confidence = event.poseConfidence?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    return "${event.posture.korean()} · 각도 $angle · 점수 $score · 누움 ${lying}초/${need}초 · 신뢰도 $confidence"
}
