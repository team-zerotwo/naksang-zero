package kr.zerotwo.naksang.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kr.zerotwo.naksang.R
import kr.zerotwo.naksang.ui.components.DisplayText
import kr.zerotwo.naksang.ui.components.NaksangButton
import kr.zerotwo.naksang.ui.components.NaksangIcon
import kr.zerotwo.naksang.ui.components.StatusLine
import kr.zerotwo.naksang.ui.theme.NaksangColors
import kr.zerotwo.naksang.ui.theme.Pretendard

@Composable
fun CameraPlaceScreen(
    personVisible: Boolean,
    personX: Float,
    personY: Float,
    mirrorX: Boolean,
    cameraError: String?,
    showLiveCamera: Boolean,
    onPreviewReady: (androidx.camera.core.Preview.SurfaceProvider) -> Unit,
    onPreviewGone: () -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(NaksangColors.Text)) {
        Box(Modifier.fillMaxSize().padding(bottom = 300.dp)) {
            if (showLiveCamera) {
                val context = LocalContext.current
                val previewView = remember {
                    PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                }
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                DisposableEffect(previewView) {
                    onPreviewReady(previewView.surfaceProvider)
                    onDispose { onPreviewGone() }
                }
            } else {
                Image(
                    painter = painterResource(R.drawable.illust_living_room),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(NaksangColors.Text, NaksangColors.Text.copy(alpha = 0f)),
                        ),
                    ),
            )
            PersonOverlay(personVisible, personX, personY, mirrorX)
            Row(
                Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 24.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    NaksangIcon(R.drawable.ic_back, Color.White, size = 26.dp, description = "뒤로")
                }
                Text(
                    "카메라 위치",
                    color = Color.White,
                    fontFamily = Pretendard,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Text("2 / 2", color = Color.White, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(
                Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .height(22.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(NaksangColors.Sage300),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset(y = (-12).dp)
                    .shadow(8.dp, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(NaksangColors.Bg)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 16.dp),
            ) {
                if (personVisible) {
                    StatusLine(R.drawable.ic_check, "사람이 잘 보여요", NaksangColors.Ok)
                } else {
                    StatusLine(R.drawable.ic_warn, "사람이 화면에 들어오면 알려 드려요", NaksangColors.Warn)
                }
                DisplayText("방 전체가 보이게\n폰을 세워 주세요", 26.sp, Modifier.padding(top = 6.dp, bottom = 12.dp))
                Text("바닥이 화면 아래쪽에 보여요", fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = NaksangColors.Text)
                Text("— 침대 옆 바닥까지 들어와요", fontFamily = Pretendard, fontSize = 16.sp, color = NaksangColors.Sub)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NaksangIcon(R.drawable.ic_lock, NaksangColors.Text, size = 18.dp)
                    Spacer(Modifier.size(6.dp))
                    Text("영상은 저장하지 않아요", fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text("— 폰 안에서만 판단해요", fontFamily = Pretendard, fontSize = 16.sp, color = NaksangColors.Sub)
                if (cameraError != null) {
                    Text(cameraError, color = NaksangColors.Warn, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(16.dp))
                NaksangButton("이 위치로 시작", onClick = onStart)
            }
        }
    }
}

@Composable
private fun PersonOverlay(visible: Boolean, personX: Float, personY: Float, mirrorX: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        if (!visible) return@Canvas
        val xNorm = if (mirrorX) 1f - personX else personX
        val boxW = 120.dp.toPx()
        val boxH = 210.dp.toPx()
        val left = (size.width * xNorm - boxW / 2f).coerceIn(12.dp.toPx(), (size.width - boxW - 12.dp.toPx()).coerceAtLeast(0f))
        val top = (size.height * personY - boxH / 2f).coerceIn(72.dp.toPx(), (size.height - boxH - 12.dp.toPx()).coerceAtLeast(0f))
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(boxW, boxH),
            cornerRadius = CornerRadius(18.dp.toPx()),
            style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))),
        )
    }
    if (visible) {
        Text(
            "사람 인식",
            color = Color.White,
            fontFamily = Pretendard,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 118.dp, top = 150.dp)
                .background(NaksangColors.Text, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
