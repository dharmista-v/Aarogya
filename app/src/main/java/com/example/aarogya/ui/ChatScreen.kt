package com.example.aarogya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.aarogya.ChatMessage
import com.example.aarogya.R

private val SUGGESTIONS = listOf(
    "How am I doing today?",
    "Am I in a deficit this week?",
    "How many calories do I have left?",
    "What should I eat for dinner?",
    "What are my biggest calorie sources?",
    "How much weight have I lost?",
)

/** On-device health chat: exact answers from your data, SmolLM2 for everything else. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    busy: Boolean,
    contentPadding: PaddingValues,
    onAsk: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpen: () -> Unit,
) {
    LaunchedEffect(Unit) { onOpen() } // warm up the on-device model while the user reads/types
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex + 1)
    }
    fun send(text: String) {
        if (text.isBlank() || busy) return
        onAsk(text)
        input = ""
    }

    // The bottom padding (navigation bar) is marked as consumed, so imePadding only adds the part of the
    // keyboard that's taller than it — otherwise both stack up and leave a gap above the keyboard.
    val bottomBar = contentPadding.calculateBottomPadding()
    Column(
        Modifier.fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding(), bottom = bottomBar)
            .consumeWindowInsets(PaddingValues(bottom = bottomBar))
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "On-device · your data stays on this phone", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (messages.isNotEmpty()) TextButton(onNewChat) { Text("New chat") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text("Ask about your health data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (messages.isEmpty()) {
                        Text(
                            "Questions about your calories, foods and weight are answered exactly from your data. Anything else goes to SmolLM2 on your phone.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { send(s) }, label = { Text(s) }) }
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { Bubble(it) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                // Multi-line fields swallow the Send action, so treat Enter as send.
                input, { if ('\n' in it) send(it.replace("\n", " ")) else input = it }, Modifier.weight(1f),
                placeholder = { Text("Ask about your calories, foods, weight…") },
                shape = RoundedCornerShape(24.dp), maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
            )
            IconButton({ send(input) }, enabled = input.isNotBlank() && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start) {
            Box(
                Modifier.widthIn(max = 300.dp)
                    .background(
                        when {
                            m.fromUser -> MaterialTheme.colorScheme.primaryContainer
                            m.kind == ChatMessage.Kind.INFO -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (m.streaming && m.text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(m.status ?: "Thinking on-device…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(m.text + if (m.streaming) " ▍" else "", style = MaterialTheme.typography.bodyMedium)
                }
            }
            val tag = when (m.kind) {
                ChatMessage.Kind.FROM_DATA -> "From your data"
                ChatMessage.Kind.LLM -> "On-device AI · may be inaccurate"
                else -> null
            }
            if (tag != null) Text(tag, Modifier.padding(start = 8.dp, top = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
