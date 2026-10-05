package com.liveaireply.app.overlay

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liveaireply.app.engine.AssistantRuntime
import com.liveaireply.app.engine.AssistantStatus
import com.liveaireply.app.engine.OverlayAction
import com.liveaireply.app.engine.OverlayState
import com.liveaireply.app.ui.theme.LiveReplyTheme
import kotlin.math.roundToInt

/**
 * The floating control. Collapsed it is a status dot; expanded it is the suggestion card
 * from the specification, with a large STOP AI button always reachable.
 */
@Composable
fun OverlayContent() {
    val state by AssistantRuntime.overlayState.collectAsState()
    val settings = AssistantRuntime.container?.currentSettings
    val scale = (settings?.overlayScale ?: 1f).coerceIn(0.7f, 1.6f)
    val alpha = (settings?.overlayOpacity ?: 0.95f).coerceIn(0.4f, 1f)

    LiveReplyTheme {
        Box(modifier = Modifier.padding(4.dp)) {
            if (state.expanded) {
                ExpandedCard(state = state, scale = scale, alpha = alpha)
            } else {
                CollapsedDot(state = state, scale = scale, alpha = alpha)
            }
        }
    }
}

@Composable
private fun CollapsedDot(state: OverlayState, scale: Float, alpha: Float) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    Surface(
        shape = CircleShape,
        color = statusColor(state.status).copy(alpha = alpha),
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size((44 * scale).dp)
            // Keep tap and drag recognizers independent. A clickable modifier combined
            // with detectDragGestures on the same node can consume the down/up sequence,
            // leaving the collapsed bubble impossible to open on some Compose versions.
            .pointerInput("open-overlay") {
                detectTapGestures(onTap = { act(OverlayAction.TOGGLE_EXPAND) })
            }
            .pointerInput("drag-overlay") {
                fun finishDrag() {
                    val current = AssistantRuntime.container?.currentSettings?.overlayPosition
                    if (current != null && (offsetX != 0f || offsetY != 0f)) {
                        AssistantRuntime.overlayPositionUpdater?.invoke(
                            current.x + offsetX.roundToInt(),
                            current.y + offsetY.roundToInt()
                        )
                    }
                    offsetX = 0f
                    offsetY = 0f
                }
                detectDragGestures(
                    onDragEnd = ::finishDrag,
                    onDragCancel = ::finishDrag
                ) { change, drag ->
                    change.consume()
                    offsetX += drag.x
                    offsetY += drag.y
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = state.status.glyph,
                color = Color.White,
                fontSize = (16 * scale).sp
            )
        }
    }
}

@Composable
private fun ExpandedCard(state: OverlayState, scale: Float, alpha: Float) {
    var edited by remember(state.replyText) { mutableStateOf(state.replyText.orEmpty()) }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxScrollHeight = if (screenHeightDp > 0) {
        minOf(560, (screenHeightDp - 48).coerceAtLeast(1)).dp
    } else {
        560.dp
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = alpha),
        tonalElevation = 3.dp,
        modifier = Modifier.padding(4.dp)
    ) {
        Column(
            // Scrollable as a whole: with a long reply the Send/Copy/STOP controls must
            // stay reachable even on a small screen or in landscape.
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                // Bound the viewport to both a comfortable maximum and the current
                // screen height. Without this, a wrap-content overlay can push emergency
                // controls outside the reachable area, especially in landscape.
                .heightIn(max = maxScrollHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${state.status.glyph}  Live AI Reply",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = state.status.label,
                    color = statusColor(state.status),
                    fontSize = 12.sp
                )
            }

            Text(text = state.statusDetail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = "Mode: ${state.mode.label}", fontSize = 12.sp)
            state.currentAppLabel?.let { Text(text = "App: $it", fontSize = 12.sp) }

            state.latestIncomingText?.let {
                Text(text = "Latest message:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = "\u201C$it\u201D", fontSize = 13.sp)
            }

            state.errorMessage?.let {
                Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            if (state.hasSuggestion) {
                Text(text = "AI reply:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.replyEditable) {
                    OutlinedTextField(
                        value = edited,
                        onValueChange = { edited = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    // No inner scroll: the whole card scrolls, and a nested unbounded
                    // vertical scroll would throw at measure time.
                    Text(text = "\u201C${state.replyText}\u201D", fontSize = 13.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { act(OverlayAction.EDIT) }) { Text("Edit") }
                    OutlinedButton(onClick = { act(OverlayAction.REGENERATE) }) { Text("Regen") }
                    Button(
                        onClick = { act(OverlayAction.SEND, edited) },
                        enabled = state.canSend
                    ) { Text("Send") }
                }
                state.sendBlockedReason?.let {
                    Text(text = it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { act(OverlayAction.COPY, edited) }) { Text("Copy") }
                    TextButton(onClick = { act(OverlayAction.REJECT) }) { Text("Reject") }
                    TextButton(onClick = { act(OverlayAction.PAUSE_CHAT) }) { Text("Pause chat") }
                }
            }

            // The emergency control is always visible and always the loudest thing here.
            Button(
                onClick = { act(OverlayAction.STOP) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("STOP AI", fontWeight = FontWeight.Bold) }

            TextButton(onClick = { act(OverlayAction.TOGGLE_EXPAND) }) { Text("Collapse") }
        }
    }
}

private fun act(action: OverlayAction, edited: String? = null) {
    // Never run engine actions on the compose/main thread: SEND, REGENERATE and the
    // AUTO path make blocking AI calls and sleep for typing simulation.
    AssistantRuntime.postEngineAction(action, edited?.takeIf { it.isNotBlank() })
}

private fun statusColor(status: AssistantStatus): Color = when (status) {
    AssistantStatus.MONITORING -> Color(0xFF2E7D32)
    AssistantStatus.THINKING -> Color(0xFFF9A825)
    AssistantStatus.REPLY_READY -> Color(0xFF1565C0)
    AssistantStatus.ERROR -> Color(0xFFC62828)
    AssistantStatus.PAUSED -> Color(0xFF6D4C41)
    AssistantStatus.STOPPED -> Color(0xFF424242)
    AssistantStatus.IDLE -> Color(0xFF546E7A)
}
