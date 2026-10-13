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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
            CatchUpButton(c.title) { messages }
            if (c.needsReply) TextButton(onClick = { scope.launch(Dispatchers.IO) { store.markDone(key) } }) { Hud(s(R.string.done), size = 9.sp, color = Alert) }
            if (!c.isGroup) TextButton(onClick = { linking = true }) { Hud(s(R.string.link), size = 9.sp) }
            if (c.pkg != null) TextButton(onClick = { Actions.openChat(ctx, c) }) { Hud(s(R.string.open_app), size = 9.sp) }
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
            CatchUpButton(p.name) { messages }
        }
        Rule()
        MessageList(Modifier.weight(1f), messages, chatOf, showApp = true, selection, focusId = 0L)
        Rule()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Hud(s(R.string.via), size = 8.sp, color = CyanDim)
            p.chats.forEach { chat ->
                Chip(chat.channel.label, Color(chat.channel.color), chat.key == target.key) { replyKey = chat.key }
            }
            Hud(s(R.string.open_chat), size = 8.sp, modifier = Modifier.clickable { onOpenChat(target.key) }.padding(6.dp))
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
            ) { Hud(s(R.string.newest), size = 9.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
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
        days <= 0 -> s(R.string.today)
        days == 1 -> s(R.string.yesterday)
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
            if (m.hasImage) Thumb(m.id)
            var showOriginal by remember(m.id) { mutableStateOf(false) }
            Text(if (showOriginal && m.original != null) m.original else m.text, color = if (m.revoked) CyanDim else Cyan)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (m.revoked) Hud(s(R.string.deleted_by_sender), size = 7.sp, color = Alert, modifier = Modifier.padding(end = 8.dp))
                if (m.original != null) Hud(s(if (showOriginal) R.string.show_edited else R.string.show_original), size = 7.sp, color = Alert,
                    modifier = Modifier.clickable { showOriginal = !showOriginal }.padding(end = 8.dp))
                Hud(shortTime(m.ts), size = 7.sp, color = CyanDim)
            }
        }
        SignalChips(m, c)
    }
}

/** A captured picture; tap for full size. */
@Composable
private fun Thumb(id: Long) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            Store.get(ctx).image(id)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    var full by remember { mutableStateOf(false) }
    val b = bmp ?: return
    androidx.compose.foundation.Image(
        b, null, Modifier.padding(bottom = 6.dp).widthIn(max = 276.dp).heightIn(max = 300.dp).clickable { full = true },
    )
    if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false }) {
        androidx.compose.foundation.Image(b, null, Modifier.fillMaxWidth().clickable { full = false })
    }
}

/** Upcoming times ("i morgen kl 18") and street addresses found in a message, as tappable chips. */
@Composable
private fun SignalChips(m: Msg, c: Conversation) {
    val ctx = LocalContext.current
    val whens = remember(m.id) { Signals.whens(m.text, m.ts).filter { it.start > System.currentTimeMillis() - 2 * 3_600_000 } }
    val places = remember(m.id) { Signals.places(m.text) }
    if (whens.isEmpty() && places.isEmpty()) return
    val askNotify = rememberNotifyPermission()
    val scope = rememberCoroutineScope()
    Row(Modifier.padding(top = 4.dp).widthIn(max = 300.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        whens.forEach { w ->
            var open by remember { mutableStateOf(false) }
            Box {
                SignalChip("📅 " + whenLabel(w)) { open = true }
                DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Panel) {
                    MenuItem(s(R.string.add_to_calendar)) { open = false; Actions.addToCalendar(ctx, w, "${c.title}: ${m.text}") }
                    MenuItem(s(R.string.remind_me)) {
                        open = false
                        askNotify()
                        // an hour ahead for timed plans; at 9 for all-day ones
                        val at = (if (w.hasTime) w.start - 3_600_000 else w.start).coerceAtLeast(System.currentTimeMillis() + 60_000)
                        scope.launch(Dispatchers.IO) { Reminders.add(ctx, c.key, at, m.text) }
                        Toast.makeText(ctx, s(R.string.reminder_set, whenLabel(Signals.When(at, true, ""))), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        places.forEach { p -> SignalChip("📍 $p") { Actions.openMap(ctx, p) } }
    }
}

@Composable
private fun SignalChip(label: String, onClick: () -> Unit) =
    Surface(onClick = onClick, shape = Tag, color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Cyan, fontSize = 13.sp, maxLines = 1)
    }

private fun whenLabel(w: Signals.When): String {
    val day = when (val d = ((dayStart(w.start) - dayStart(System.currentTimeMillis())) / DateUtils.DAY_IN_MILLIS).toInt()) {
        0 -> s(R.string.today)
        1 -> s(R.string.tomorrow)
        in 2..6 -> SimpleDateFormat("EEE", Locale.getDefault()).format(w.start)
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(w.start)
    }
    return if (w.hasTime) "$day ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(w.start)}" else day
}

// ---------- selection ----------

/** Long-press-to-select state for a list; [delete] removes the picked items. */
internal class Selection<T>(val ids: MutableSet<T>, val deleteTitle: (Int) -> String, val delete: () -> Unit) {
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
        Selection(ids, { plural(R.plurals.delete_messages, it) }) {
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
        title = { Hud(selection.deleteTitle(n), size = 12.sp, bold = true) },
        text = { Text(s(R.string.removed_here_only), color = CyanDim, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = { confirm = false; selection.delete() }) { Hud(s(R.string.delete), size = 9.sp, color = Alert) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Hud(s(R.string.cancel), size = 9.sp) } },
    )
    Row(Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { selection.ids.clear() }) { Text("✕", fontFamily = Head, fontSize = 18.sp, color = Cyan) }
        Hud(s(R.string.n_selected, n), size = 14.sp, bold = true, modifier = Modifier.weight(1f))
        if (menu != null) Box {
            TextButton(onClick = { open = true }) { Text("⋮", fontSize = 22.sp, color = Cyan) }
            DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Panel) { menu { open = false } }
        }
        TextButton(onClick = { confirm = true }) { Hud(s(R.string.delete), size = 10.sp, color = Alert) }
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
        MenuItem(s(R.string.copy)) {
            close(); Actions.copy(ctx, text()); selection.ids.clear()
            Toast.makeText(ctx, s(R.string.copied), Toast.LENGTH_SHORT).show()
        }
        MenuItem(s(R.string.share)) { close(); Actions.share(ctx, text()); selection.ids.clear() }
        MenuItem(s(R.string.forward)) { close(); forwarding = text(); selection.ids.clear() }
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
        title = { Hud(s(R.string.forward_to), size = 12.sp, bold = true) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, color = CyanDim, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                NeonField(q, { q = it }, s(R.string.filter), Modifier.fillMaxWidth())
                if (targets.isEmpty()) Text(s(R.string.no_targets), color = CyanDim, fontSize = 14.sp)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(targets.filter { it.title.contains(q, true) }, key = { it.key }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onDismiss()
                                scope.launch {
                                    val ok = Actions.send(ctx, store, t, text)
                                    Toast.makeText(ctx, s(if (ok) R.string.sent_to else R.string.send_failed_to, t.title), Toast.LENGTH_SHORT).show()
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
        confirmButton = { TextButton(onClick = onDismiss) { Hud(s(R.string.close), size = 9.sp) } },
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

    val canSchedule = remember(version, c.key, tgAuth) { Actions.canSchedule(ctx, c) }
    val queued by produceState(emptyList<Reminder>(), version, c.key) {
        value = withContext(Dispatchers.IO) { store.pendingReminders().filter { it.convKey == c.key && it.kind == Reminder.KIND_SEND } }
    }
    var scheduling by remember { mutableStateOf(false) }
    if (scheduling) ScheduleDialog(onDismiss = { scheduling = false }) { at ->
        scheduling = false
        val body = text.trim()
        text = ""
        scope.launch {
            val ok = Actions.schedule(ctx, c, body, at)
            if (!ok) text = body
            Toast.makeText(ctx, if (ok) s(R.string.scheduled_at, shortTime(at) + " " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(at)) else s(R.string.schedule_failed), Toast.LENGTH_SHORT).show()
        }
    }

    queued.forEach { r ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Hud("⏱ " + shortTime(r.at) + " " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(r.at), size = 8.sp, color = CyanDim)
            Spacer(Modifier.width(8.dp))
            Text(r.text, Modifier.weight(1f), color = CyanDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { scope.launch(Dispatchers.IO) { Reminders.cancel(ctx, r.id) } }) { Hud("✕", size = 10.sp, color = Alert) }
        }
    }
    if (!canReply) {
        Text(s(R.string.reply_closed),
            Modifier.padding(16.dp), fontSize = 14.sp, color = CyanDim)
        return
    }
    SuggestionChips(store, c) { text = it }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NeonField(text, { text = it }, s(R.string.transmit_via, c.channel.label), Modifier.weight(1f), singleLine = false)
        Spacer(Modifier.width(8.dp))
        if (canSchedule && text.isNotBlank()) {
            TextButton(onClick = { scheduling = true }) { Text("⏱", fontSize = 20.sp, color = Cyan) }
        }
        NeonButton(s(R.string.send), text.isNotBlank()) {
            val body = text.trim()
            text = ""
            scope.launch {
                if (!Actions.send(ctx, store, c, body)) {
                    text = body
                    Toast.makeText(ctx, s(R.string.send_failed, c.channel.label), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** Snooze presets plus "pick date & time…", for sending later. */
@Composable
private fun ScheduleDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val ctx = LocalContext.current
    val options = remember { snoozeOptions(System.currentTimeMillis()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Panel, shape = Angled,
        title = { Hud(s(R.string.send_later), size = 12.sp, bold = true) },
        text = {
            Column {
                options.forEach { (label, at) ->
                    Text(s(label), Modifier.fillMaxWidth().clickable { onPick(at) }.padding(vertical = 12.dp), color = Cyan, fontSize = 17.sp)
                }
                Text(s(R.string.pick_time), Modifier.fillMaxWidth().clickable {
                    val cal = Calendar.getInstance()
                    android.app.DatePickerDialog(ctx, { _, y, mo, d ->
                        android.app.TimePickerDialog(ctx, { _, h, mi ->
                            cal.set(y, mo, d, h, mi, 0)
                            if (cal.timeInMillis > System.currentTimeMillis()) onPick(cal.timeInMillis)
                            else Toast.makeText(ctx, s(R.string.time_passed), Toast.LENGTH_SHORT).show()
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }.padding(vertical = 12.dp), color = Cyan, fontSize = 17.sp)
                Text(s(R.string.schedule_note), color = CyanDim, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Hud(s(R.string.cancel), size = 9.sp) } },
    )
}

// ---------- on-device AI ----------

/** ✦: a short summary of the chat from Gemini Nano; hidden on phones without it. */
@Composable
private fun CatchUpButton(title: String, messages: () -> List<Msg>) {
    val ai by Assist.state.collectAsState()
    if (ai != Assist.State.READY) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Hud("✦", size = 12.sp) }
    if (open) AiDialog(s(R.string.catch_up, title), onDismiss = { open = false }) { Assist.summarize(title, messages()) }
}

/** Shows the result of [load] (or a failure note) in the app's dialog style. */
@Composable
internal fun AiDialog(title: String, onDismiss: () -> Unit, load: suspend () -> String?) {
    val text by produceState<String?>(null) { value = load() ?: s(R.string.ai_failed) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Panel, shape = Angled,
        title = { Hud(title, size = 12.sp, bold = true) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(text ?: s(R.string.thinking), color = if (text == null) CyanDim else Cyan, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Hud(s(R.string.ai_badge), size = 7.sp, color = CyanDim)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Hud(s(R.string.close), size = 9.sp) } },
    )
}

/** Up to three suggested replies when they're waiting on you; tapping one fills the box. */
@Composable
private fun SuggestionChips(store: Store, c: Conversation, onPick: (String) -> Unit) {
    val ai by Assist.state.collectAsState()
    if (ai != Assist.State.READY || !c.needsReply) return
    val ideas by produceState(emptyList<String>(), c.key, c.lastTs) {
        value = withContext(Dispatchers.IO) { Assist.replies(c.title, store.messages(c.key)) }
    }
    if (ideas.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Hud("✦", size = 10.sp, modifier = Modifier.align(Alignment.CenterVertically))
        ideas.forEach { SignalChip(it) { onPick(it) } }
    }
}
