package dev.baseline.ui.ai

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.ai.ChatMessage
import dev.baseline.ai.context.Suggestion
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.ChipActive
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.SleepPurple
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import dev.baseline.ai.engine.Role as ChatRole

private val DisabledIcon = Color(0xFF5B636D)

/**
 * The Ask sheet (spec §3.6): a modal bottom sheet that opens partly expanded and can be dragged to full height. The content is laid out for the part of the sheet on screen ([Modifier.onScreen]), so the input row is visible at the half-height stop. [onDismiss] is called on
 * the close button, a drag down and Back; the caller then drops the ViewModel, which discards the chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(vm: AskViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Card, contentColor = TextPrimary) {
        Box(Modifier.fillMaxHeight().onScreen()) {
            AskContent(
                state = state,
                onSend = vm::send,
                onPick = vm::pick,
                onMoreDetail = vm::moreDetail,
                onRetry = vm::retry,
                onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                modifier = Modifier.fillMaxHeight().imePadding(),
                onInputFocused = { scope.launch { sheetState.expand() } },
                maxSuggestions = if (sheetState.targetValue == SheetValue.PartiallyExpanded) 2 else Int.MAX_VALUE,
            )
        }
    }
}

/**
 * Sizes the content to the part of the sheet that is on screen. A modal sheet lays its content out at full height and slides it up from
 * below, so at the half-height stop the bottom (the suggestions and the input row) would sit past the screen edge. The sheet itself keeps
 * its full-height size, so its stops don't change; only the content shrinks, and grows again as it is dragged up.
 */
@Composable
private fun Modifier.onScreen(): Modifier {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val navBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    var top by remember { mutableIntStateOf(0) }
    return this
        .onGloballyPositioned { top = it.positionInWindow().y.roundToInt() }
        .layout { measurable, constraints ->
            val height = (windowHeight - navBottom - top).coerceIn(0, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
        }
}

/** The sheet's content, stateless apart from the text being typed, so tests can drive it without a [ModalBottomSheet]. */
@Composable
fun AskContent(
    state: AskUiState,
    onSend: (String) -> Unit,
    onPick: (Suggestion) -> Unit,
    onMoreDetail: (Int) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onInputFocused: () -> Unit = {},
    maxSuggestions: Int = Int.MAX_VALUE,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val count = state.messages.size
    LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
    val empty = count == 0

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.title,
                Modifier.weight(1f).semantics { heading() },
                color = TextPrimary,
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            IconButton(onClose, Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", Modifier.size(20.dp), tint = TextMuted)
            }
        }
        if (empty) Text(state.hint, color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp)

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(state.messages) { index, message ->
                MessageItem(message, onRetry = onRetry, onMoreDetail = { onMoreDetail(index) })
            }
        }

        if (empty && state.suggestions.isNotEmpty()) {
            Column(Modifier.padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.suggestions.take(maxSuggestions).forEach { SuggestionChip(it, enabled = !state.busy, onClick = { onPick(it) }) }
            }
        }

        InputRow(
            value = input,
            onValueChange = { input = it },
            placeholder = state.placeholder,
            busy = state.busy,
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    input = ""
                    onSend(text)
                }
            },
            onFocused = onInputFocused,
        )
    }
}

@Composable
private fun SuggestionChip(suggestion: Suggestion, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag("ask-suggestion").heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        color = ChipActive,
        contentColor = if (enabled) TextBody else TextMuted,
        border = BorderStroke(1.dp, Outline),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            Text(suggestion.text, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun InputRow(value: String, onValueChange: (String) -> Unit, placeholder: String, busy: Boolean, onSend: () -> Unit, onFocused: () -> Unit) {
    val canSend = !busy && value.isNotBlank()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).testTag("ask-input").onFocusChanged { if (it.isFocused) onFocused() },
            readOnly = busy,
            placeholder = { Text(placeholder, color = TextMuted) },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = SleepPurple,
                unfocusedBorderColor = Outline,
                cursorColor = SleepPurple,
            ),
        )
        IconButton(
            onSend,
            Modifier.size(48.dp),
            enabled = canSend,
            colors = IconButtonDefaults.iconButtonColors(contentColor = SleepPurple, disabledContentColor = DisabledIcon),
        ) {
            Icon(painterResource(R.drawable.ic_send), contentDescription = "Send", Modifier.size(22.dp))
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessage, onRetry: () -> Unit, onMoreDetail: () -> Unit) {
    when {
        message.role == ChatRole.USER ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    message.text,
                    Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(ChipActive).padding(horizontal = 14.dp, vertical = 10.dp),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
            }

        message.error != null ->
            Column(Modifier.fillMaxWidth()) {
                Text(message.error, color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp)
                Box(
                    Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onRetry),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("Try again", color = SleepPurple, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

        message.note -> Text(message.text, Modifier.fillMaxWidth(), color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp)

        else -> Answer(message, onMoreDetail)
    }
}

@Composable
private fun Answer(message: ChatMessage, onMoreDetail: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (message.lookedUp.isNotEmpty()) {
            Text(
                "Looked up: " + message.lookedUp.joinToString("; ") {
                    it.removePrefix("Looking up ")
                },
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        message.status?.let { Text(it, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp) }
        if (message.text.isNotEmpty()) {
            Text(message.text, Modifier.padding(top = 4.dp), color = TextBody, fontSize = 15.sp, lineHeight = 22.sp)
        } else if (message.streaming && message.status == null) {
            CircularProgressIndicator(Modifier.size(16.dp), color = SleepPurple, strokeWidth = 2.dp)
        }
        if (!message.streaming && message.text.isNotBlank()) TrailingRow(message, onMoreDetail)
    }
}

/** Copy icon, the engine label and, when offered, "· More detail ☁" (spec §3.6). Shifted left so the icon lines up with the answer text. */
@Composable
private fun TrailingRow(message: ChatMessage, onMoreDetail: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(Modifier.offset(x = (-16).dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Answer", message.text))) } },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy answer", Modifier.size(16.dp), tint = TextMuted)
        }
        message.label?.let { Text(it.text, color = TextMuted, fontSize = 11.sp) }
        if (message.canMoreDetail) {
            Box(
                Modifier.testTag("ask-more-detail").heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onMoreDetail).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("· More detail ☁", color = StrainBlue, fontSize = 11.sp)
            }
        }
    }
}
