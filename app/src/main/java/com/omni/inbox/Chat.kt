package com.omni.inbox

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ---------- single chat ----------

@Composable
internal fun ConversationScreen(store: Store, key: String, focusId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by store.changes.collectAsState()
    val conv by produceState<Conversation?>(null, version) { value = withContext(Dispatchers.IO) { store.conversation(key) } }
    val messages by produceState(emptyList<Msg>(), version) { value = withContext(Dispatchers.IO) { store.messages(key) } }
    val c = conv ?: return
    LaunchedEffect(key, c.lastTs) { withContext(Dispatchers.IO) { store.markSeen(key) } }
    val scope = rememberCoroutineScope()
    var linking by remember { mutableStateOf(false) }
    if (linking) LinkDialog(store, c, onDismiss = { linking = false })
    val selection = rememberSelection(store, messages, onBack)

    Column(Modifier.fillMaxSize().imePadding()) {
        if (selection.active) MessageSelectionBar(store, selection, messages) { c }
        else Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("<", fontFamily = Head, fontSize = 20.sp, color = Cyan) }
            Avatar(c.title, c.channel)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hud(c.channel.label, size = 8.sp, color = Color(c.channel.color))
            }
            if (c.needsReply) TextButton(onClick = { scope.launch(Dispatchers.IO) { store.markDone(key) } }) { Hud("Done ✓", size = 9.sp, color = Alert) }
            if (!c.isGroup) TextButton(onClick = { linking = true }) { Hud("Link", size = 9.sp) }
            if (c.pkg != null) TextButton(onClick = { Actions.openChat(ctx, c) }) { Hud("Open ↗", size = 9.sp) }
        }
        Rule()
        MessageList(Modifier.weight(1f), messages, { c }, showApp = false, selection, focusId)
        Rule()
        ReplyBar(store, c)
    }
}

// ---------- one person across apps ----------

/** One human's messages from every app, interleaved; replies go out on the chosen app. */
@Composable
internal fun PersonScreen(store: Store, personKey: String, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    val version by store.changes.collectAsState()
    val person by produceState<Person?>(null, version) { value = withContext(Dispatchers.IO) { store.person(personKey) } }
    val p = person ?: return
    val messages by produceState(emptyList<Msg>(), version, p.chats.size) {
        value = withContext(Dispatchers.IO) { store.messages(p.chats.map { it.key }) }
    }
    // reply where they last wrote from
    var replyKey by rememberSaveable(personKey) { mutableStateOf<String?>(null) }
    val target = p.chats.firstOrNull { it.key == replyKey } ?: p.chats.first()
    LaunchedEffect(personKey, p.latest.lastTs) { withContext(Dispatchers.IO) { p.chats.forEach { store.markSeen(it.key) } } }
    val selection = rememberSelection(store, messages, onBack)
    val chatOf = { m: Msg -> p.chats.firstOrNull { it.key == m.convKey } ?: p.latest }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (selection.active) MessageSelectionBar(store, selection, messages, chatOf)
        else Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("<", fontFamily = Head, fontSize = 20.sp, color = Cyan) }
            Avatar(p.name, p.latest.channel)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AppTags(p.chats)
            }
        }
        Rule()
        MessageList(Modifier.weight(1f), messages, chatOf, showApp = true, selection, focusId = 0L)
        Rule()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Hud("Via", size = 8.sp, color = CyanDim)
            p.chats.forEach { chat ->
                Chip(chat.channel.label, Color(chat.channel.color), chat.key == target.key) { replyKey = chat.key }
            }
            Hud("Chat ↗", size = 8.sp, modifier = Modifier.clickable { onOpenChat(target.key) }.padding(6.dp))
        }
        ReplyBar(store, target)
    }
}

// ---------- message list ----------

private sealed interface Entry {
    data class Message(val m: Msg) : Entry
    data class Day(val start: Long) : Entry
}

/** Newest at the bottom, a divider above each day, and a jump-down button once scrolled up. */
@Composable
private fun MessageList(
    modifier: Modifier, messages: List<Msg>, chatOf: (Msg) -> Conversation, showApp: Boolean,
    selection: Selection<Long>, focusId: Long,
) {
    // reverse layout: index 0 is the newest message; a day's divider follows its oldest message
    val entries = remember(messages) {
        buildList<Entry> {
            val newestFirst = messages.asReversed()
            newestFirst.forEachIndexed { i, m ->
                add(Entry.Message(m))
                val day = dayStart(m.ts)
                if (i == newestFirst.lastIndex || dayStart(newestFirst[i + 1].ts) != day) add(Entry.Day(day))
            }
        }
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var flash by remember { mutableLongStateOf(0L) }
    LaunchedEffect(focusId, entries.isNotEmpty()) {
        if (focusId == 0L) return@LaunchedEffect
        val i = entries.indexOfFirst { it is Entry.Message && it.m.id == focusId }
        if (i < 0) return@LaunchedEffect
        state.scrollToItem(i, -300)
        flash = focusId
        delay(2_000)
        flash = 0L
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, reverseLayout = true, contentPadding = PaddingValues(12.dp)) {
            items(entries, key = { e -> when (e) { is Entry.Message -> e.m.id; is Entry.Day -> "d${e.start}" } }) { e ->
                when (e) {
                    is Entry.Day -> DayDivider(e.start)
                    is Entry.Message -> Bubble(e.m, chatOf(e.m), showApp, selection, highlight = e.m.id == flash)
                }
            }
        }
        if (state.firstVisibleItemIndex > 3) {
            Surface(
                onClick = { scope.launch { state.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
                shape = Tag, color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Cyan),
            ) { Hud("▼ newest", size = 9.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        }
    }
}

private fun dayStart(ts: Long) = Calendar.getInstance().apply {
    timeInMillis = ts; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(start: Long): String {
    val today = dayStart(System.currentTimeMillis())
    val days = ((today - start) / DateUtils.DAY_IN_MILLIS).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(start)
        Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) ->
            SimpleDateFormat("d MMMM", Locale.getDefault()).format(start)
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(start)
    }
}

@Composable
private fun DayDivider(start: Long) = Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f).height(1.dp).background(Line))
    Hud(dayLabel(start), size = 8.sp, color = CyanDim, modifier = Modifier.padding(horizontal = 10.dp))
    Box(Modifier.weight(1f).height(1.dp).background(Line))
}

@Composable
private fun Bubble(m: Msg, c: Conversation, showApp: Boolean, selection: Selection<Long>, highlight: Boolean) {
    val mine = m.outgoing
    val picked = m.id in selection.ids
    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(
                interactionSource = null, indication = null,
                onClick = { if (selection.active) selection.toggle(m.id) },
                onLongClick = { selection.toggle(m.id) },
            )
            .background(if (picked || highlight) Cyan.copy(alpha = 0.12f) else Color.Transparent)
            .padding(vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .border(1.dp, if (picked || highlight) Alert else if (mine) Cyan.copy(alpha = 0.8f) else Line, Angled)
                .background(if (mine) Cyan.copy(alpha = 0.09f) else Panel, Angled)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showApp) Hud(c.channel.label + if (!mine && m.sender != null && m.sender != c.title) " · ${m.sender}" else "", size = 7.sp, color = Color(c.channel.color))
            else if (!mine && m.sender != null && m.sender != c.title) Hud(m.sender, size = 8.sp, color = Color(c.channel.color))
            Text(m.text, color = Cyan)
            Hud(shortTime(m.ts), size = 7.sp, color = CyanDim, modifier = Modifier.align(Alignment.End))
        }
    }
}

// ---------- selection ----------

/** Long-press-to-select state for a list; [delete] removes the picked items. */
internal class Selection<T>(val ids: MutableSet<T>, val noun: String, val delete: () -> Unit) {
    val active get() = ids.isNotEmpty()
    fun toggle(id: T) { if (!ids.remove(id)) ids.add(id) }
}

/** [onEmpty] runs when a delete leaves no messages, since the chat itself is then gone. */
@Composable
private fun rememberSelection(store: Store, messages: List<Msg>, onEmpty: () -> Unit): Selection<Long> {
    val scope = rememberCoroutineScope()
    val ids = remember { mutableStateSetOf<Long>() }
    // drop ids that vanished (deleted elsewhere, or after our own delete)
    LaunchedEffect(messages) { val live = messages.mapTo(HashSet()) { it.id }; ids.retainAll(live) }
    BackHandler(ids.isNotEmpty()) { ids.clear() }
    return remember(messages) {
        Selection(ids, "message") {
            val doomed = ids.toList()
            val emptied = messages.all { it.id in ids }
            ids.clear()
            scope.launch {
                withContext(Dispatchers.IO) { store.delete(doomed) }
                if (emptied) onEmpty()
            }
        }
    }
}

/** ✕ · N selected · ⋮ · DELETE. [menu] fills the ⋮ dropdown; call close() before acting. */
@Composable
internal fun SelectionBar(selection: Selection<*>, menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null) {
    var confirm by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val n = selection.ids.size
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Panel, shape = Angled,
        title = { Hud("Delete $n ${selection.noun}${if (n == 1) "" else "s"}?", size = 12.sp, bold = true) },
        text = { Text("Removed from Omni Inbox only. They stay in the original app.", color = CyanDim, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = { confirm = false; selection.delete() }) { Hud("Delete", size = 9.sp, color = Alert) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Hud("Cancel", size = 9.sp) } },
    )
    Row(Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { selection.ids.clear() }) { Text("✕", fontFamily = Head, fontSize = 18.sp, color = Cyan) }
        Hud("$n selected", size = 14.sp, bold = true, modifier = Modifier.weight(1f))
        if (menu != null) Box {
            TextButton(onClick = { open = true }) { Text("⋮", fontSize = 22.sp, color = Cyan) }
            DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Panel) { menu { open = false } }
        }
        TextButton(onClick = { confirm = true }) { Hud("Delete", size = 10.sp, color = Alert) }
    }
}

@Composable
internal fun MenuItem(label: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Hud(label, size = 10.sp) }, onClick = onClick)

@Composable
private fun MessageSelectionBar(store: Store, selection: Selection<Long>, messages: List<Msg>, chatOf: (Msg) -> Conversation) {
    val ctx = LocalContext.current
    var forwarding by remember { mutableStateOf<String?>(null) }
    forwarding?.let { text -> ForwardDialog(store, text) { forwarding = null } }
    fun text() = Actions.asText(messages.filter { it.id in selection.ids }) { chatOf(it).title }
    SelectionBar(selection) { close ->
        MenuItem("Copy") {
            close(); Actions.copy(ctx, text()); selection.ids.clear()
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }
        MenuItem("Share…") { close(); Actions.share(ctx, text()); selection.ids.clear() }
        MenuItem("Forward…") { close(); forwarding = text(); selection.ids.clear() }
    }
}

/** Pick any chat we can send into right now and send [text] there. */
@Composable
private fun ForwardDialog(store: Store, text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    val targets by produceState(emptyList<Conversation>()) {
        value = withContext(Dispatchers.IO) { store.inbox() }.filter { Actions.canSend(ctx, it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Panel, shape = Angled,
        title = { Hud("Forward to", size = 12.sp, bold = true) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, color = CyanDim, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                NeonField(q, { q = it }, "Filter", Modifier.fillMaxWidth())
                if (targets.isEmpty()) Text("No chat can send right now. SMS, linked Telegram, and chats with a fresh notification can.", color = CyanDim, fontSize = 14.sp)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(targets.filter { it.title.contains(q, true) }, key = { it.key }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onDismiss()
                                scope.launch {
                                    val ok = Actions.send(ctx, store, t, text)
                                    Toast.makeText(ctx, if (ok) "Sent to ${t.title}" else "Couldn't send to ${t.title}", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Hud(t.channel.label, size = 8.sp, color = Color(t.channel.color), modifier = Modifier.width(80.dp))
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Hud("Close", size = 9.sp) } },
    )
}

// ---------- reply ----------

@Composable
private fun ReplyBar(store: Store, c: Conversation) {
    val ctx = LocalContext.current
    val version by store.changes.collectAsState()
    val tgAuth by Telegram.auth.collectAsState()
    val scope = rememberCoroutineScope()
    val canReply = remember(version, c.key, tgAuth) { Actions.canSend(ctx, c) }
    var text by rememberSaveable(c.key) { mutableStateOf("") }

    // drafts survive leaving the chat and closing the app
    var loaded by remember(c.key) { mutableStateOf(false) }
    LaunchedEffect(c.key) {
        val saved = withContext(Dispatchers.IO) { store.draft(c.key) }
        if (text.isEmpty()) text = saved
        loaded = true
    }
    LaunchedEffect(c.key, text, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(400)
        withContext(Dispatchers.IO) { store.saveDraft(c.key, text) }
    }

    if (!canReply) {
        Text("// Reply channel opens when a new message arrives from this chat. Use OPEN ↗ meanwhile.",
            Modifier.padding(16.dp), fontSize = 14.sp, color = CyanDim)
        return
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NeonField(text, { text = it }, "Transmit via ${c.channel.label}", Modifier.weight(1f), singleLine = false)
        Spacer(Modifier.width(8.dp))
        NeonButton("Send", text.isNotBlank()) {
            val body = text.trim()
            text = ""
            scope.launch {
                if (!Actions.send(ctx, store, c, body)) {
                    text = body
                    Toast.makeText(ctx, "Couldn't send - open ${c.channel.label} to reply", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
