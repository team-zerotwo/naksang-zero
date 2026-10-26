package kr.zerotwo.naksang.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.zerotwo.naksang.R
import kr.zerotwo.naksang.ui.components.DisplayText
import kr.zerotwo.naksang.ui.components.FieldRow
import kr.zerotwo.naksang.ui.components.NaksangButton
import kr.zerotwo.naksang.ui.components.NaksangIcon
import kr.zerotwo.naksang.ui.components.RowDivider
import kr.zerotwo.naksang.ui.components.StatusLine
import kr.zerotwo.naksang.ui.components.WarnBadge
import kr.zerotwo.naksang.ui.theme.NaksangColors
import kr.zerotwo.naksang.ui.theme.Pretendard

enum class ConnectMode { FORM, LINKED, EDIT }

private val rooms = listOf("안방", "거실", "작은방", "주방")

@Composable
fun ConnectScreen(
    mode: ConnectMode,
    inviteCode: String,
    address: String,
    detail: String,
    entryMethod: String,
    roomNote: String,
    busy: Boolean,
    error: String?,
    copied: Boolean,
    onSubmit: (String, String, String, String) -> Unit,
    onNext: () -> Unit,
    onCopied: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var addressText by rememberSaveable(mode, address) { mutableStateOf(address) }
    var detailText by rememberSaveable(mode, detail) { mutableStateOf(detail) }
    var entryText by rememberSaveable(mode, entryMethod) { mutableStateOf(entryMethod) }
    var roomText by rememberSaveable(mode, roomNote) { mutableStateOf(roomNote) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val shownError = localError ?: error
    val editing = mode != ConnectMode.LINKED

    Box(
        Modifier
            .fillMaxSize()
            .background(NaksangColors.Bg)
            .imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = 110.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.illust_town),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 110.dp, y = (-36).dp)
                        .width(360.dp)
                        .rotate(-4f)
                        .clip(RoundedCornerShape(bottomStart = 60.dp)),
                )
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 8.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clickable(onClick = onBack),
                                contentAlignment = Alignment.Center,
                            ) {
                                NaksangIcon(R.drawable.ic_back, NaksangColors.Sub, size = 26.dp, description = "뒤로")
                            }
                        }
                        Text(
                            text = if (mode == ConnectMode.EDIT) "주소 수정" else "연결 설정 · 1 / 2",
                            color = NaksangColors.Sub,
                            fontFamily = Pretendard,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (mode == ConnectMode.FORM) {
                            Text(
                                text = "설정",
                                color = NaksangColors.Btn,
                                fontFamily = Pretendard,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(onClick = onOpenSettings)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                    if (mode == ConnectMode.LINKED) {
                        CodeSlab(
                            code = inviteCode,
                            copied = copied,
                            onCopy = {
                                copyCode(context, inviteCode)
                                onCopied()
                            },
                            onShare = { shareCode(context, inviteCode) },
                        )
                    } else if (mode == ConnectMode.FORM) {
                        Box(
                            Modifier
                                .padding(top = 28.dp)
                                .width(300.dp)
                                .background(NaksangColors.Text, RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                                .padding(horizontal = 24.dp, vertical = 22.dp),
                        ) {
                            Column {
                                Text("연결하면 여기에", color = ColorWhite.copy(alpha = 0.85f), fontSize = 15.sp, fontFamily = Pretendard)
                                DisplayText("코드가\n나와요", 36.sp, color = ColorWhite)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "보호자에게 6자리를 알려 주세요",
                                    color = ColorWhite.copy(alpha = 0.85f),
                                    fontSize = 15.sp,
                                    fontFamily = Pretendard,
                                )
                            }
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DisplayText(if (mode == ConnectMode.EDIT) "주소" else "어르신 댁 주소", 24.sp)
                    WarnBadge("필수")
                }
                Spacer(Modifier.height(10.dp))
                RowDivider()
                if (editing) {
                    FieldRow("주소", icon = R.drawable.ic_pin) {
                        InlineField(addressText, "도로명 주소", { addressText = it }, ImeAction.Next)
                    }
                    RowDivider()
                    FieldRow("동·호수") {
                        InlineField(detailText, "101동 502호", { detailText = it }, ImeAction.Next)
                    }
                    RowDivider()
                    FieldRow("공동현관", optional = true) {
                        InlineField(entryText, "예: #1234", { entryText = it }, ImeAction.Done)
                    }
                    RowDivider()
                    RoomRow(roomText) { roomText = it }
                    RowDivider()
                } else {
                    FieldRow("주소", icon = R.drawable.ic_pin) { ValueText(address) }
                    RowDivider()
                    FieldRow("동·호수") { ValueText(detail) }
                    RowDivider()
                    FieldRow("공동현관", optional = true) { ValueText(entryMethod.ifBlank { "—" }) }
                    RowDivider()
                    RoomRow(roomNote, enabled = false) {}
                    RowDivider()
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NaksangIcon(R.drawable.ic_lock, NaksangColors.Sub, size = 18.dp)
                    Text("보호자에게만 보여요", color = NaksangColors.Sub, fontSize = 15.sp, fontFamily = Pretendard)
                }
                Text(
                    "이 주소는 넘어졌을 때 보호자에게 함께 보내져요.",
                    color = NaksangColors.Sub,
                    fontSize = 15.sp,
                    fontFamily = Pretendard,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (shownError != null) {
                    Text(
                        shownError,
                        color = NaksangColors.Warn,
                        fontFamily = Pretendard,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (mode == ConnectMode.FORM) {
                        Text(
                            "설정",
                            color = NaksangColors.Link,
                            fontFamily = Pretendard,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(onClick = onOpenSettings)
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(NaksangColors.Bg)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp, top = 8.dp),
        ) {
            val label = when {
                busy && mode == ConnectMode.EDIT -> "저장하는 중"
                busy -> "연결하는 중"
                mode == ConnectMode.LINKED -> "다음"
                mode == ConnectMode.EDIT -> "저장"
                else -> "연결하기"
            }
            NaksangButton(
                text = label,
                enabled = !busy,
                onClick = {
                    if (mode == ConnectMode.LINKED) {
                        onNext()
                    } else {
                        if (addressText.isBlank() || detailText.isBlank()) {
                            localError = "주소와 동·호수를 입력해 주세요."
                        } else {
                            localError = null
                            onSubmit(addressText, detailText, entryText, roomText)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CodeSlab(code: String, copied: Boolean, onCopy: () -> Unit, onShare: () -> Unit) {
    Box(
        Modifier
            .padding(top = 18.dp)
            .width(312.dp)
            .background(NaksangColors.Text, RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
            .padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("이 폰의 연결 코드", color = ColorWhite.copy(alpha = 0.85f), fontSize = 15.sp, fontFamily = Pretendard)
                StatusLine(R.drawable.ic_check, "연결됐어요", NaksangColors.OkBg, iconSize = 18.dp)
            }
            Text(
                text = formatInvite(code),
                style = kr.zerotwo.naksang.ui.theme.displayStyle(52.sp, ColorWhite).copy(
                    letterSpacing = 0.02.sp,
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            Text(
                "보호자에게 이 코드를 알려주세요",
                color = ColorWhite.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontFamily = Pretendard,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                SlabAction(R.drawable.ic_copy, if (copied) "복사했어요" else "복사", onCopy)
                SlabAction(R.drawable.ic_share, "보내기", onShare)
            }
        }
    }
}

@Composable
private fun SlabAction(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NaksangIcon(icon, ColorWhite, size = 22.dp)
        Text(label, color = ColorWhite, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun RoomRow(selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "폰 둔 방\n(선택)",
            modifier = Modifier.width(84.dp),
            color = NaksangColors.Sub,
            fontSize = 15.sp,
            fontFamily = Pretendard,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.width(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
            rooms.forEach { room ->
                val on = room == selected
                Text(
                    text = room,
                    color = if (on) NaksangColors.Btn else NaksangColors.Text,
                    fontFamily = Pretendard,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .then(if (enabled) Modifier.clickable { onSelect(if (on) "" else room) } else Modifier)
                        .padding(top = 10.dp, bottom = 8.dp)
                        .drawBehind {
                            if (on) {
                                drawLine(
                                    color = NaksangColors.Btn,
                                    start = androidx.compose.ui.geometry.Offset(0f, size.height + 4.dp.toPx()),
                                    end = androidx.compose.ui.geometry.Offset(size.width, size.height + 4.dp.toPx()),
                                    strokeWidth = 3.dp.toPx(),
                                )
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun InlineField(value: String, placeholder: String, onChange: (String) -> Unit, ime: ImeAction) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = Pretendard,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = NaksangColors.Text,
        ),
        cursorBrush = SolidColor(NaksangColors.Btn),
        keyboardOptions = KeyboardOptions(imeAction = ime),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, color = NaksangColors.Sub, fontSize = 16.sp, fontFamily = Pretendard)
                }
                inner()
            }
        },
    )
}

@Composable
private fun ValueText(value: String) {
    Text(value, color = NaksangColors.Text, fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

private val ColorWhite = androidx.compose.ui.graphics.Color.White

fun formatInvite(code: String): String {
    val digits = code.filter { it.isDigit() }
    return if (digits.length >= 6) "${digits.take(3)} ${digits.drop(3).take(3)}" else code
}

private fun copyCode(context: Context, code: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("낙상제로 연결 코드", formatInvite(code).replace(" ", "")))
}

private fun shareCode(context: Context, code: String) {
    val pretty = formatInvite(code)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "낙상제로 연결 코드 $pretty\n보호자 웹에서 이 코드를 입력해 주세요.")
    }
    context.startActivity(Intent.createChooser(send, "보호자에게 코드 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
