package com.omni.inbox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar

@Composable
internal fun InboxScreen(store: Store, onOpen: (String) -> Unit, onTelegram: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by store.changes.collectAsState()
    var filter by rememberSaveable { mutableStateOf<Channel?>(null) }
    var view by rememberSaveable { mutableStateOf(View.ALL) }
    val archiveView = view == View.ARCHIVE
    var peopleMode by rememberSaveable { mutableStateOf(true) }
    // re-checks once a minute so chats whose snooze ran out come back without a new message
    val minute by produceState(0L) { while (true) { value = System.currentTimeMillis() / 60_000; delay(60_000) } }
    val all by produceState(emptyList<Conversation>(), version, minute) { value = withContext(Dispatchers.IO) { store.inbox() } }
    val vips by produceState(emptySet<String>(), version) { value = withContext(Dispatchers.IO) { Stats.vipKeys(store) } }

    val live = all.filter { !it.hidden }
    val waiting = live.count { it.needsReply && !it.muted }
    val following = live.count { it.followUp && !it.muted }
    val hiddenCount = all.count { it.hidden }
    fun shows(c: Conversation) = when (view) {
        View.ALL -> !c.hidden
        View.REPLY -> !c.hidden && c.needsReply && !c.muted
        View.FOLLOW_UP -> !c.hidden && c.followUp && !c.muted
        View.VIP -> !c.hidden && c.key in vips
        View.ARCHIVE -> c.hidden
    }
    val chats = all.filter { (filter == null || it.channel == filter) && shows(it) }
    // people group across all apps first, so a person shows up under each app filter they're on
    val people = remember(all, filter, view, vips) {
        People.group(all)
            .filter { p -> (filter == null || p.chats.any { it.channel == filter }) && (if (archiveView) p.hidden else !p.hidden && p.chats.any(::shows)) }
            .sortedWith(compareByDescending<Person> { it.pinned }.thenByDescending { it.sortTs })
    }

    // long-press selection: person keys in people mode, chat keys otherwise
    val picked = remember { mutableStateSetOf<String>() }
    LaunchedEffect(peopleMode, people, chats) {
        picked.retainAll(if (peopleMode) people.mapTo(HashSet()) { it.key } else chats.mapTo(HashSet()) { it.key })
    }
    BackHandler(picked.isNotEmpty()) { picked.clear() }
    fun pickedChats(): List<Conversation> =
        if (peopleMode) people.filter { it.key in picked }.flatMap { it.chats } else chats.filter { it.key in picked }
    fun act(block: suspend (List<Conversation>) -> Unit) {
        val target = pickedChats()
        picked.clear()
        scope.launch(Dispatchers.IO) { failSoftly(ctx) { block(target) } }
    }
    val selection = Selection(picked, { plural(R.plurals.delete_chats, it) }) { act { cs -> store.deleteConversations(cs.map { it.key }) } }

    val askNotify = rememberNotifyPermission()
    var snoozing by remember { mutableStateOf<List<Conversation>?>(null) }
    snoozing?.let { target ->
        SnoozeDialog(onDismiss = { snoozing = null }) { at ->
            snoozing = null
            askNotify()
            scope.launch(Dispatchers.IO) { snooze(ctx, store, target, at) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection) { close ->
            val sel = pickedChats()
            val allPinned = sel.isNotEmpty() && sel.all { it.pinned }
            val allMuted = sel.isNotEmpty() && sel.all { it.muted }
            MenuItem(s(if (allPinned) R.string.unpin else R.string.pin)) { close(); act { store.setPinned(it.map(Conversation::key), !allPinned) } }
            MenuItem(s(if (allMuted) R.string.unmute else R.string.mute)) { close(); act { store.setMuted(it.map(Conversation::key), !allMuted) } }
            if (archiveView) MenuItem(s(R.string.restore)) { close(); act { restore(ctx, store, it) } }
            else MenuItem(s(R.string.archive)) { close(); act { store.setArchived(it.map(Conversation::key), true) } }
            MenuItem(s(R.string.snooze_menu)) { close(); snoozing = sel; picked.clear() }
            MenuItem(s(R.string.mark_done)) { close(); act { cs -> cs.forEach { store.markDone(it.key) } } }
        }
        else Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Hud("Omni//Inbox", size = 22.sp, bold = true)
                Hud(s(R.string.inbox_subtitle, live.size, waiting), size = 9.sp, color = CyanDim)
            }
            val ai by Assist.state.collectAsState()
            var briefing by remember { mutableStateOf(false) }
            if (ai == Assist.State.READY && waiting > 0) {
                HudButton(s(R.string.brief_button)) { briefing = true }
                Spacer(Modifier.width(6.dp))
            }
            if (briefing) AiDialog(s(R.string.waiting_on_you), onDismiss = { briefing = false }) {
                withContext(Dispatchers.IO) {
                    val who = People.group(live).filter { it.needsReply }.take(8)
                    Assist.brief(who.map { p -> p.name to store.messages(p.chats.filter { it.needsReply }.map { it.key }) })
                }
            }
            HudButton(s(R.string.find), onSearch)
            Spacer(Modifier.width(6.dp))
            HudButton(s(R.string.sys), onSettings)
            Spacer(Modifier.width(6.dp))
            TelegramBadge(onTelegram)
        }
        SetupCard()
        ScrollHintRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            fun toggle(v: View) { view = if (view == v) View.ALL else v; picked.clear() }
            Chip(s(R.string.chip_all), Cyan, filter == null && view == View.ALL) { filter = null; view = View.ALL; picked.clear() }
            Chip(s(R.string.chip_reply, waiting), Alert, view == View.REPLY) { toggle(View.REPLY) }
            if (following > 0 || view == View.FOLLOW_UP) Chip(s(R.string.chip_follow_up, following), Alert, view == View.FOLLOW_UP) { toggle(View.FOLLOW_UP) }
            if (vips.isNotEmpty() || view == View.VIP) Chip(s(R.string.chip_vip), Cyan, view == View.VIP) { toggle(View.VIP) }
            Chip(s(if (peopleMode) R.string.chip_people else R.string.chip_chats), CyanDim, peopleMode) { peopleMode = !peopleMode; picked.clear() }
            Channel.entries.forEach { ch ->
                val unread = live.count { it.channel == ch && it.unread && !it.muted }
                Chip(if (unread > 0) "${ch.label} · $unread" else ch.label, Color(ch.color), filter == ch) { filter = if (filter == ch) null else ch }
            }
            Chip(s(R.string.chip_archive, hiddenCount), CyanDim, archiveView) { toggle(View.ARCHIVE) }
        }
        Rule()
        val empty = if (peopleMode) people.isEmpty() else chats.isEmpty()
        if (empty) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    s(if (archiveView) R.string.archive_empty else R.string.no_signal),
                    color = CyanDim,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                fun swipe(cs: List<Conversation>) = SwipeActions(
                    onDone = { scope.launch(Dispatchers.IO) { cs.forEach { store.markDone(it.key) } } },
                    onArchive = {
                        scope.launch(Dispatchers.IO) { if (archiveView) restore(ctx, store, cs) else store.setArchived(cs.map { it.key }, true) }
                    },
                    archiveLabel = s(if (archiveView) R.string.restore else R.string.archive),
                )
                if (peopleMode) items(people, key = { it.key }) { p ->
                    SwipeRow(swipe(p.chats), enabled = !selection.active) {
                        InboxRow(
                            title = p.name, c = p.latest, tags = { AppTags(p.chats) }, unread = p.unread, needsReply = p.needsReply,
                            followUp = p.followUp, vip = p.chats.any { it.key in vips },
                            pinned = p.pinned, muted = p.muted, snoozeUntil = p.chats.maxOf { it.snoozeUntil }, draft = p.draft,
                            selected = p.key in picked, onLongClick = { selection.toggle(p.key) },
                        ) {
                            if (selection.active) selection.toggle(p.key)
                            else if (p.chats.size == 1) onOpen(p.latest.key) else onOpen("person:${p.key}")
                        }
                    }
                    Rule()
                } else items(chats, key = { it.key }) { c ->
                    SwipeRow(swipe(listOf(c)), enabled = !selection.active) {
                        InboxRow(
                            title = c.title, c = c, tags = { Hud(c.channel.label, size = 8.sp, color = Color(c.channel.color)) },
                            unread = c.unread && !c.muted, needsReply = c.needsReply && !c.muted, pinned = c.pinned, muted = c.muted,
                            followUp = c.followUp && !c.muted, vip = c.key in vips,
                            snoozeUntil = c.snoozeUntil, draft = c.draft?.takeIf(String::isNotBlank),
                            selected = c.key in picked, onLongClick = { selection.toggle(c.key) },
                        ) { if (selection.active) selection.toggle(c.key) else onOpen(c.key) }
                    }
                    Rule()
                }
            }
        }
    }
}

/** Runs a user action; a failure shows a short note instead of closing the app. */
internal suspend fun failSoftly(ctx: android.content.Context, block: suspend () -> Unit) {
    runCatching { block() }.onFailure {
        android.util.Log.e("OmniInbox", "action failed", it)
        withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, s(R.string.action_failed, it.message ?: it.javaClass.simpleName), android.widget.Toast.LENGTH_LONG).show() }
    }
}

private enum class View { ALL, REPLY, FOLLOW_UP, VIP, ARCHIVE }

private fun snooze(ctx: android.content.Context, store: Store, chats: List<Conversation>, at: Long) {
    store.snooze(chats.map { it.key }, at)
    // one nudge per person/chat is enough
    chats.distinctBy { People.normalizeName(it.title) }.forEach { Reminders.add(ctx, it.key, at, s(R.string.snoozed_back, it.lastText)) }
}

private fun restore(ctx: android.content.Context, store: Store, chats: List<Conversation>) {
    val keys = chats.map { it.key }
    store.setArchived(keys, false)
    store.snooze(keys, 0)
    keys.forEach { Reminders.cancelFor(ctx, it) }
}

// ---------- rows ----------

@Composable
private fun InboxRow(
    title: String, c: Conversation, tags: @Composable () -> Unit, unread: Boolean, needsReply: Boolean,
    followUp: Boolean, vip: Boolean, pinned: Boolean, muted: Boolean, snoozeUntil: Long, draft: String?,
    selected: Boolean, onLongClick: () -> Unit, onClick: () -> Unit,
) {
    val nameColor = when { muted -> CyanDim; unread -> Cyan; else -> Cyan.copy(alpha = 0.85f) }
    Row(
        Modifier.fillMaxWidth().background(if (selected) Cyan.copy(alpha = 0.12f) else Void)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(title, c.channel)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread) Box(Modifier.padding(end = 6.dp).size(7.dp).background(Cyan, Tag))
                if (pinned) Hud("◆", size = 9.sp, modifier = Modifier.padding(end = 5.dp))
                if (vip) Hud("★", size = 10.sp, modifier = Modifier.padding(end = 5.dp))
                Text(title, Modifier.weight(1f), fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = nameColor, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (muted) { Hud(s(R.string.tag_muted), size = 7.sp, color = CyanDim); Spacer(Modifier.width(6.dp)) }
                if (needsReply) {
                    Hud(s(R.string.tag_reply), size = 7.sp, color = Alert, modifier = Modifier.border(1.dp, Alert.copy(alpha = 0.6f), Tag).padding(horizontal = 5.dp, vertical = 2.dp))
                    Spacer(Modifier.width(6.dp))
                } else if (followUp) {
                    Hud(s(R.string.tag_follow_up), size = 7.sp, color = Alert, modifier = Modifier.border(1.dp, Alert.copy(alpha = 0.4f), Tag).padding(horizontal = 5.dp, vertical = 2.dp))
                    Spacer(Modifier.width(6.dp))
                }
                if (snoozeUntil > System.currentTimeMillis()) Hud("💤 " + snoozeLabel(snoozeUntil), size = 8.sp, color = CyanDim)
                else Hud(shortTime(c.lastTs), size = 9.sp, color = CyanDim)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                tags()
                Spacer(Modifier.width(8.dp))
                if (draft != null) Text(s(R.string.draft, draft), fontSize = 15.sp, color = Alert, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else {
                    val preview = if (c.lastSender != null && c.lastSender != c.title) "${c.lastSender}: ${c.lastText}" else c.lastText
                    Text(preview, fontSize = 15.sp, color = CyanDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private class SwipeActions(val onDone: () -> Unit, val onArchive: () -> Unit, val archiveLabel: String)

/** Swipe right: done (clears "reply"). Swipe left: archive, or restore in the archive. */
@Composable
private fun SwipeRow(actions: SwipeActions, enabled: Boolean, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) { if (state.currentValue != SwipeToDismissBoxValue.Settled) state.reset() }
    SwipeToDismissBox(
        state = state,
        gesturesEnabled = enabled,
        onDismiss = { if (it == SwipeToDismissBoxValue.StartToEnd) actions.onDone() else actions.onArchive() },
        backgroundContent = {
            val right = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxSize().background(if (right) Cyan.copy(alpha = 0.18f) else Alert.copy(alpha = 0.18f)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (right) Arrangement.Start else Arrangement.End,
            ) { Hud(if (right) s(R.string.done) else actions.archiveLabel, size = 11.sp, color = if (right) Cyan else Alert, bold = true) }
        },
    ) { content() }
}

// ---------- snooze ----------

/** Preset wake-up times, from [now]: in an hour, this/tomorrow evening, tomorrow morning, next Monday. */
internal fun snoozeOptions(now: Long): List<Pair<Int, Long>> {
    fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply {
        timeInMillis = now; add(Calendar.DAY_OF_YEAR, daysAhead)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val toMonday = ((Calendar.MONDAY - today.get(Calendar.DAY_OF_WEEK) + 7) % 7).let { if (it == 0) 7 else it }
    return buildList {
        add(R.string.snooze_hour to now + 3_600_000)
        if (today.get(Calendar.HOUR_OF_DAY) < 17) add(R.string.snooze_evening to at(0, 18))
        else add(R.string.snooze_tomorrow_evening to at(1, 18))
        add(R.string.snooze_tomorrow_morning to at(1, 8))
        add(R.string.snooze_next_week to at(toMonday, 8))
    }
}

private fun snoozeLabel(at: Long): String =
    if (android.text.format.DateUtils.isToday(at)) DateFormat.getTimeInstance(DateFormat.SHORT).format(at) else shortTime(at)

@Composable
private fun SnoozeDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val options = remember { snoozeOptions(System.currentTimeMillis()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Panel, shape = Angled,
        title = { Hud(s(R.string.snooze_until), size = 12.sp, bold = true) },
        text = {
            Column {
                options.forEach { (label, at) ->
                    Text(s(label), Modifier.fillMaxWidth().combinedClickable(onClick = { onPick(at) }).padding(vertical = 12.dp), color = Cyan, fontSize = 17.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Hud(s(R.string.cancel), size = 9.sp) } },
    )
}

/** Returns a function that asks for notification permission (Android 13+) if it's missing. */
@Composable
internal fun rememberNotifyPermission(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
