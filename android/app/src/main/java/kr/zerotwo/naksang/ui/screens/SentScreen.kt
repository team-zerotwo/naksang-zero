package kr.zerotwo.naksang.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.zerotwo.naksang.R
import kr.zerotwo.naksang.session.FallDelivery
import kr.zerotwo.naksang.ui.components.DisplayText
import kr.zerotwo.naksang.ui.components.NaksangButton
import kr.zerotwo.naksang.ui.components.StatusLine
import kr.zerotwo.naksang.ui.components.WarnBadge
import kr.zerotwo.naksang.ui.theme.Gmarket
import kr.zerotwo.naksang.ui.theme.NaksangColors
import kr.zerotwo.naksang.ui.theme.Pretendard
import kr.zerotwo.naksang.util.KoreanTime

@Composable
fun SentScreen(
    address: String,
    detail: String,
    detectedAtEpoch: Long?,
    delivery: FallDelivery,
    nowMillis: Long = System.currentTimeMillis(),
    onBack: () -> Unit,
) {
    val whenLabel = detectedAtEpoch?.let { KoreanTime.sentCaption(it, nowMillis) } ?: "방금"
    val pending = delivery != FallDelivery.SENT
    Box(Modifier.fillMaxSize().background(NaksangColors.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(400.dp)) {
                Image(
                    painter = painterResource(R.drawable.wave_ok),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "낙상제로",
                    modifier = Modifier.padding(start = 24.dp, top = 14.dp),
                    fontFamily = Gmarket,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Image(
                    painter = painterResource(R.drawable.illust_house_relief),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .width(230.dp),
                )
            }
            Column(Modifier.padding(horizontal = 24.dp)) {
                StatusLine(R.drawable.ic_send, if (pending) "전송 대기" else "전송 완료", NaksangColors.Ok)
                DisplayText("보호자에게\n알렸어요", 34.sp, Modifier.padding(top = 6.dp, bottom = 8.dp))
                Text("곧 연락이 올 거예요.", fontFamily = Pretendard, fontSize = 17.sp, color = NaksangColors.Text)
                Text("움직이기 힘드시면 그대로 계세요.", fontFamily = Pretendard, fontSize = 17.sp, color = NaksangColors.Sub)
            }
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                    Text("보낸 내용", color = NaksangColors.Sub, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "$whenLabel · 보호자 앱 푸시",
                    fontFamily = Pretendard,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = NaksangColors.Text,
                )
                Text(
                    listOf(address, detail).filter { it.isNotBlank() }.joinToString(", "),
                    fontFamily = Pretendard,
                    fontSize = 15.sp,
                    color = NaksangColors.Text,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WarnBadge(if (pending) "전송 대기" else "보호자 확인 대기")
                    Text(
                        if (pending) "  연결되면 이어서 보내요" else "  보호자가 웹에서 확인할 수 있어요",
                        color = NaksangColors.Sub,
                        fontFamily = Pretendard,
                        fontSize = 15.sp,
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            NaksangButton(
                text = "대기로 돌아가기",
                onClick = onBack,
                container = androidx.compose.ui.graphics.Color.Transparent,
                content = NaksangColors.Btn,
                border = NaksangColors.Btn,
            )
        }
    }
}
