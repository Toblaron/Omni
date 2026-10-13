package com.omni.inbox

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

// ---------- theme: solid black, cyan, angular ----------

private val Void = Color(0xFF020406)
private val Panel = Color(0xFF070D12)
private val Line = Color(0xFF0C3440)
private val Cyan = Color(0xFF00E5FF)
private val CyanDim = Color(0xFF4FA9B8)
private val Alert = Color(0xFFFF3D7F)

private val Head = FontFamily(Font(R.font.orbitron, FontWeight.Normal), Font(R.font.orbitron, FontWeight.Bold))
private val Body = FontFamily(Font(R.font.rajdhani_medium, FontWeight.Normal), Font(R.font.rajdhani_semibold, FontWeight.SemiBold))

private val Angled = CutCornerShape(topStart = 0.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 10.dp)
private val Tag = CutCornerShape(4.dp)

@Composable
fun OmniTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = Body)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan, onPrimary = Void, secondary = CyanDim,
            background = Void, onBackground = Cyan, surface = Void, onSurface = Cyan,
            surfaceVariant = Panel, onSurfaceVariant = CyanDim, outline = Line, error = Alert,
        ),
        typography = base.copy(
            bodyLarge = base.bodyLarge.body().copy(fontSize = 17.sp), bodyMedium = base.bodyMedium.body(),
            bodySmall = base.bodySmall.body(), titleMedium = base.titleMedium.body(),
            labelLarge = base.labelLarge.copy(fontFamily = Head, letterSpacing = 1.5.sp),
        ),
        content = content,
    )
}

/** Orbitron, uppercase, tracked out: headings and labels. */
@Composable
private fun Hud(text: String, size: TextUnit = 11.sp, color: Color = Cyan, modifier: Modifier = Modifier, bold: Boolean = false) =
    Text(text.uppercase(), modifier, color = color, fontFamily = Head, fontSize = size, letterSpacing = 2.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun NeonButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = Angled, border = BorderStroke(1.dp, if (enabled) Cyan else Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Cyan.copy(alpha = 0.08f), contentColor = Cyan),
    ) { Text(text.uppercase(), fontFamily = Head, fontSize = 12.sp, letterSpacing = 1.5.sp) }

@Composable
private fun NeonField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
                      keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, singleLine: Boolean = true) =
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier, singleLine = singleLine, maxLines = 5, shape = Angled,
        placeholder = { Text(label, color = CyanDim.copy(alpha = 0.6f)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cyan, unfocusedBorderColor = Line, cursorColor = Cyan,
            focusedTextColor = Cyan, unfocusedTextColor = Cyan, focusedContainerColor = Panel, unfocusedContainerColor = Panel,
        ),
    )

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

// ---------- navigation ----------

@Composable
private operator fun Regex.contains(text: String) = matches(text)

@Composable
fun App() {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    // "inbox", "telegram", "settings", "search", or a conversation key
    var route by rememberSaveable { mutableStateOf("inbox") }
    var back by rememberSaveable { mutableStateOf("inbox") }
    fun go(to: String) { back = if (route == "search" || route == "settings") route else "inbox"; route = to }
    fun up() { route = back; back = "inbox" }
    val locked by AppLock.locked.collectAsState()
    BackHandler(route != "inbox") { up() }
    Surface(Modifier.fillMaxSize(), color = Void) {
        Box(Modifier.safeDrawingPadding()) {
            val importing by ImportState.state.collectAsState()
            if (locked) LockScreen()
            else if (importing != null) ImportScreen(importing!!) { key -> ImportState.clear(); if (key != null) go(key) }
            else when (route) {
                "inbox" -> InboxScreen(store, onOpen = ::go, onTelegram = { go("telegram") }, onSearch = { go("search") }, onSettings = { go("settings") })
                "telegram" -> TelegramScreen(::up)
                "settings" -> SettingsScreen(onTelegram = { go("telegram") }, onBack = ::up)
                "search" -> SearchScreen(store, onOpen = ::go, onBack = ::up)
                in Regex("person:.*") -> PersonScreen(store, route.removePrefix("person:"), onOpenChat = ::go, onBack = ::up)
                else -> ConversationScreen(store, route, ::up)
            }
        }
    }
}

// ---------- inbox ----------

@Composable
private fun InboxScreen(store: Store, onOpen: (String) -> Unit, onTelegram: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val version by store.changes.collectAsState()
    var filter by rememberSaveable { mutableStateOf<Channel?>(null) }
    var replyOnly by rememberSaveable { mutableStateOf(false) }
    var peopleMode by rememberSaveable { mutableStateOf(true) }
    val all by produceState(emptyList<Conversation>(), version, filter) {
        value = withContext(Dispatchers.IO) { store.conversations(filter) }
    }
    val waiting = all.count { it.needsReply }
    val conversations = if (replyOnly) all.filter { it.needsReply } else all
    val people = remember(conversations) { conversations.groupBy { it.personKey }.map { (k, chats) -> Person(k, chats.first().title, chats) } }

    // long-press selection: person keys in people mode, chat keys otherwise
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateSetOf<String>() }
    LaunchedEffect(peopleMode, people) {
        val live = if (peopleMode) people.mapTo(HashSet()) { it.key } else conversations.mapTo(HashSet()) { it.key }
        picked.retainAll(live)
    }
    BackHandler(picked.isNotEmpty()) { picked.clear() }
    val selection = Selection(picked, "chat") {
        val keys = if (peopleMode) people.filter { it.key in picked }.flatMap { p -> p.chats.map { it.key } } else picked.toList()
        picked.clear()
        scope.launch(Dispatchers.IO) { store.deleteConversations(keys) }
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection)
        else Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Hud("Omni//Inbox", size = 22.sp, bold = true)
                Hud("${all.size} channels · $waiting awaiting reply", size = 9.sp, color = CyanDim)
            }
            HudButton("Find", onSearch)
            Spacer(Modifier.width(6.dp))
            HudButton("Sys", onSettings)
            Spacer(Modifier.width(6.dp))
            TelegramBadge(onTelegram)
        }
        SetupCard()
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("All", Cyan, filter == null && !replyOnly) { filter = null; replyOnly = false }
            Chip("Reply · $waiting", Alert, replyOnly) { replyOnly = !replyOnly }
            Chip(if (peopleMode) "People" else "Chats", CyanDim, peopleMode) { peopleMode = !peopleMode; picked.clear() }
            Channel.entries.forEach { ch -> Chip(ch.label, Color(ch.color), filter == ch) { filter = if (filter == ch) null else ch } }
        }
        Rule()
        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("// NO SIGNAL\nNew messages from your apps appear here as they arrive.", color = CyanDim)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (peopleMode) {
                    items(people, key = { it.key }) { p ->
                        PersonRow(p, p.key in picked, onLongClick = { selection.toggle(p.key) }) {
                            if (selection.active) selection.toggle(p.key)
                            else if (p.chats.size == 1) onOpen(p.latest.key) else onOpen("person:${p.key}")
                        }
                        Rule()
                    }
                } else items(conversations, key = { it.key }) { c ->
                    ConversationRow(c, c.key in picked, onLongClick = { selection.toggle(c.key) }) {
                        if (selection.active) selection.toggle(c.key) else onOpen(c.key)
                    }
                    Rule()
                }
            }
        }
    }
}

@Composable
private fun TelegramBadge(onClick: () -> Unit) {
    val auth by Telegram.auth.collectAsState()
    val (label, color) = when (auth) {
        Telegram.Auth.Ready -> "TG LINKED" to Cyan
        Telegram.Auth.Unavailable -> "TG OFFLINE" to CyanDim
        else -> "LINK TG" to Color(Channel.TELEGRAM.color)
    }
    Surface(onClick = onClick, shape = Tag, color = Panel, border = BorderStroke(1.dp, color.copy(alpha = 0.7f))) {
        Hud(label, size = 9.sp, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun Chip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = Tag,
        color = if (selected) color.copy(alpha = 0.14f) else Panel,
        border = BorderStroke(1.dp, if (selected) color else Line),
    ) {
        Hud(label, size = 10.sp, color = if (selected) color else CyanDim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun ConversationRow(c: Conversation, selected: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) Cyan.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.title, c.channel)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.unread) Box(Modifier.padding(end = 6.dp).size(7.dp).background(Cyan, Tag))
                Text(c.title, Modifier.weight(1f), fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal,
                    color = if (c.unread) Cyan else Cyan.copy(alpha = 0.85f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.needsReply) {
                    Hud("↩ reply", size = 7.sp, color = Alert, modifier = Modifier.border(1.dp, Alert.copy(alpha = 0.6f), Tag).padding(horizontal = 5.dp, vertical = 2.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Hud(shortTime(c.lastTs), size = 9.sp, color = CyanDim)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hud(c.channel.label, size = 8.sp, color = Color(c.channel.color))
                Spacer(Modifier.width(8.dp))
                val preview = if (c.lastSender != null && c.lastSender != c.title) "${c.lastSender}: ${c.lastText}" else c.lastText
                Text(preview, fontSize = 15.sp, color = CyanDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Avatar(title: String, channel: Channel) {
    Box(
        Modifier.size(44.dp).border(1.dp, Color(channel.color).copy(alpha = 0.8f), Angled).background(Panel, Angled),
        contentAlignment = Alignment.Center,
    ) {
        Text(title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#", color = Cyan, fontFamily = Head,
            fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

// ---------- setup ----------

@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    val resumed by MainActivity.resumed.collectAsState()
    var permTick by remember { mutableIntStateOf(0) }
    val listenerOn = remember(resumed) { ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx) }
    val smsOn = remember(resumed, permTick) { SmsSync.canRead(ctx) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        SmsSync.start(ctx)
    }
    if (listenerOn && smsOn) return

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).border(1.dp, Alert.copy(alpha = 0.6f), Angled)
            .background(Panel, Angled).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Hud("// Setup incomplete", color = Alert)
        if (!listenerOn) {
            Text("Allow notification access so WhatsApp, Signal, Messenger and Snapchat messages show up here.", color = CyanDim)
            NeonButton("Notification access") { openListenerSettings(ctx) }
        }
        if (!smsOn) {
            Text("Allow SMS and contacts to show your text messages with names.", color = CyanDim)
            NeonButton("SMS & contacts") {
                askPerms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
            }
        }
    }
}

private fun openListenerSettings(ctx: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(ctx, OmniListener::class.java).flattenToString(),
        )
    } else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    runCatching { ctx.startActivity(intent) }
        .onFailure { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}

// ---------- telegram link ----------

@Composable
private fun TelegramScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by Telegram.auth.collectAsState()
    val status by Telegram.status.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var input2 by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(auth) { input = ""; input2 = ""; error = null }

    fun submit(action: suspend () -> String?) {
        busy = true
        scope.launch { error = action(); busy = false }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("<", fontFamily = Head, fontSize = 20.sp, color = Cyan) }
            Hud("Telegram uplink", size = 16.sp, bold = true)
        }
        Rule()
        when (val a = auth) {
            Telegram.Auth.Unavailable -> Text(
                "The TDLib library isn't bundled in this build, so Telegram runs on notifications only.", color = CyanDim)
            Telegram.Auth.NoCredentials -> {
                Text("Get your own API ID and hash at my.telegram.org → API development tools (any app name works). They stay on this phone.", color = CyanDim)
                NeonField(input, { input = it.filter(Char::isDigit) }, "api_id", Modifier.fillMaxWidth(), KeyboardType.Number)
                NeonField(input2, { input2 = it.trim() }, "api_hash", Modifier.fillMaxWidth())
                NeonButton("Connect", input.isNotBlank() && input2.length >= 16) {
                    Telegram.saveCredentials(ctx, input.toInt(), input2)
                }
            }
            Telegram.Auth.Starting -> Hud("Connecting…", color = CyanDim)
            Telegram.Auth.WaitPhone -> {
                Text("Phone number in international format.", color = CyanDim)
                NeonField(input, { input = it }, "+46 70 123 45 67", Modifier.fillMaxWidth(), KeyboardType.Phone)
                NeonButton("Send code", !busy && input.isNotBlank()) { submit { Telegram.submitPhone(input.trim()) } }
            }
            is Telegram.Auth.WaitCode -> {
                Text("Enter the login code Telegram sent${a.phone?.let { " to +$it" } ?: ""} (check the Telegram app).", color = CyanDim)
                NeonField(input, { input = it.filter(Char::isDigit) }, "code", Modifier.fillMaxWidth(), KeyboardType.Number)
                NeonButton("Verify", !busy && input.isNotBlank()) { submit { Telegram.submitCode(input) } }
            }
            is Telegram.Auth.WaitPassword -> {
                Text("Two-step verification password${a.hint?.let { " (hint: $it)" } ?: ""}.", color = CyanDim)
                NeonField(input, { input = it }, "password", Modifier.fillMaxWidth(), secret = true)
                NeonButton("Unlock", !busy && input.isNotBlank()) { submit { Telegram.submitPassword(input) } }
            }
            Telegram.Auth.Ready -> {
                Hud("● Link active", color = Cyan)
                Text("Full history and live messages come straight from Telegram. ${status}", color = CyanDim)
                NeonButton("Log out", !busy) { submit { Telegram.logOut(); null } }
            }
            is Telegram.Auth.Unsupported -> Text("Telegram asked for a login step this app doesn't support yet: ${a.step}.", color = Alert)
        }
        error?.let { Text(it, color = Alert) }
    }
}

// ---------- conversation ----------

@Composable
private fun ConversationScreen(store: Store, key: String, onBack: () -> Unit) {
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
        if (selection.active) SelectionBar(selection)
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
            c.pkg?.let { pkg -> TextButton(onClick = { openApp(ctx, pkg) }) { Hud("Open ↗", size = 9.sp) } }
        }
        Rule()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(12.dp)) {
            items(messages.asReversed(), key = { it.id }) { m -> Bubble(m, c, selection = selection) }
        }
        Rule()
        ReplyBar(ctx, store, c)
    }
}

@Composable
private fun Bubble(m: Msg, c: Conversation, showApp: Boolean = false, selection: Selection<Long>) {
    val mine = m.outgoing
    val picked = m.id in selection.ids
    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(
                interactionSource = null, indication = null,
                onClick = { if (selection.active) selection.toggle(m.id) },
                onLongClick = { selection.toggle(m.id) },
            )
            .background(if (picked) Cyan.copy(alpha = 0.12f) else Color.Transparent)
            .padding(vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .border(1.dp, if (picked) Alert else if (mine) Cyan.copy(alpha = 0.8f) else Line, Angled)
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

/** Long-press-to-select state for a list; [delete] removes the picked items. */
private class Selection<T>(val ids: MutableSet<T>, val noun: String, val delete: () -> Unit) {
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

@Composable
private fun SelectionBar(selection: Selection<*>) {
    var confirm by remember { mutableStateOf(false) }
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
        TextButton(onClick = { confirm = true }) { Hud("Delete", size = 10.sp, color = Alert) }
    }
}

@Composable
private fun ReplyBar(ctx: Context, store: Store, c: Conversation) {
    val version by store.changes.collectAsState()
    val tgAuth by Telegram.auth.collectAsState()
    val scope = rememberCoroutineScope()
    val tgChat = c.key.removePrefix("tg:").toLongOrNull()?.takeIf { c.key.startsWith("tg:") }
    val isSms = c.channel == Channel.SMS && c.address != null && c.key.startsWith("sms:")
    val canReply = remember(version, c.key, tgAuth) {
        (tgChat != null && tgAuth == Telegram.Auth.Ready) || (isSms && SmsSync.canSend(ctx)) || Replies.canReply(c.key)
    }
    var text by rememberSaveable(c.key) { mutableStateOf("") }

    if (!canReply) {
        Text("// Reply channel opens when a new message arrives from this chat. Use OPEN ↗ meanwhile.",
            Modifier.padding(16.dp), fontSize = 14.sp, color = CyanDim)
        return
    }
    fun fail() = Toast.makeText(ctx, "Couldn't send - open ${c.channel.label} to reply", Toast.LENGTH_LONG).show()

    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NeonField(text, { text = it }, "Transmit via ${c.channel.label}", Modifier.weight(1f), singleLine = false)
        Spacer(Modifier.width(8.dp))
        NeonButton("Send", text.isNotBlank()) {
            val body = text.trim()
            text = ""
            when {
                tgChat != null -> scope.launch { Telegram.sendText(tgChat, body)?.let { fail() } }
                isSms -> runCatching { SmsSync.send(ctx, c.address!!, body) }.onFailure { fail() }
                Replies.send(ctx, c.key, body) -> {
                    val now = System.currentTimeMillis()
                    store.add(c.channel, c.pkg, c.key, c.title, null, body, now, true, "${c.key}|me|$now", fuzzy = true)
                }
                else -> { text = body; fail() }
            }
        }
    }
}

private fun openApp(ctx: Context, pkg: String) {
    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (intent != null) ctx.startActivity(intent)
    else Toast.makeText(ctx, "App not installed", Toast.LENGTH_SHORT).show()
}

private fun shortTime(ts: Long): String =
    if (DateUtils.isToday(ts)) DateFormat.getTimeInstance(DateFormat.SHORT).format(ts)
    else SimpleDateFormat("d MMM", Locale.getDefault()).format(ts)

// ---------- small header button ----------

@Composable
private fun HudButton(label: String, onClick: () -> Unit) =
    Surface(onClick = onClick, shape = Tag, color = Panel, border = BorderStroke(1.dp, Line)) {
        Hud(label, size = 9.sp, color = Cyan, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }

// ---------- lock ----------

@Composable
private fun LockScreen() {
    val activity = LocalContext.current as? MainActivity
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Hud("Omni//Inbox", size = 24.sp, bold = true)
        Spacer(Modifier.height(8.dp))
        Hud("// Vault sealed", size = 10.sp, color = CyanDim)
        Spacer(Modifier.height(32.dp))
        NeonButton("Unlock") { activity?.unlock() }
    }
}

// ---------- settings ----------

@Composable
private fun SettingsScreen(onTelegram: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var lockOn by remember { mutableStateOf(AppLock.isEnabled(ctx)) }
    val lockAvailable = remember { AppLock.available(ctx) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("<", fontFamily = Head, fontSize = 20.sp, color = Cyan) }
            Hud("System", size = 16.sp, bold = true)
        }
        Rule()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Hud("App lock", size = 11.sp)
                Text(
                    if (lockAvailable) "Fingerprint, face or screen lock after 1 minute away. Also hides the app in recents and blocks screenshots."
                    else "Set up a screen lock on the phone to enable this.",
                    color = CyanDim, fontSize = 14.sp,
                )
            }
            Switch(
                checked = lockOn, enabled = lockAvailable,
                onCheckedChange = { lockOn = it; AppLock.setEnabled(ctx, it); (ctx as? MainActivity)?.applySecureFlag() },
                colors = SwitchDefaults.colors(checkedThumbColor = Void, checkedTrackColor = Cyan, uncheckedTrackColor = Panel, uncheckedBorderColor = Line),
            )
        }
        Rule()
        Column {
            Hud("Encryption", size = 11.sp)
            Text("Messages are stored in an encrypted database (SQLCipher, 256-bit AES) whose key is sealed by the phone's secure hardware. Telegram's own data is encrypted the same way.",
                color = CyanDim, fontSize = 14.sp)
        }
        Rule()
        NeonButton("Telegram uplink", onClick = onTelegram)
        Rule()
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { ImportState.load(ctx, it) } }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hud("WhatsApp history", size = 11.sp)
            Text("In WhatsApp open a chat → ⋮ → More → Export chat, and share it to Omni Inbox. Or pick an exported file here.",
                color = CyanDim, fontSize = 14.sp)
            NeonButton("Import chat file") { pick.launch(arrayOf("text/plain", "application/zip")) }
        }
    }
}

// ---------- search ----------

@Composable
private fun SearchScreen(store: Store, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val hits by produceState(emptyList<SearchHit>(), query) {
        kotlinx.coroutines.delay(150) // debounce typing
        value = withContext(Dispatchers.IO) { runCatching { store.search(query) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("<", fontFamily = Head, fontSize = 20.sp, color = Cyan) }
            NeonField(query, { query = it }, "Search · from:anna · app:telegram", Modifier.weight(1f))
        }
        Hud(if (query.isBlank()) "// Scan all channels" else "${hits.size} hits", size = 9.sp, color = CyanDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Rule()
        LazyColumn(Modifier.fillMaxSize()) {
            items(hits, key = { "${it.convKey}|${it.ts}|${it.text.hashCode()}" }) { h ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(h.convKey) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Hud(h.channel.label, size = 8.sp, color = Color(h.channel.color))
                        Spacer(Modifier.width(8.dp))
                        Text(h.title + (h.sender?.takeIf { it != h.title }?.let { " · $it" } ?: ""), Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hud(shortTime(h.ts), size = 8.sp, color = CyanDim)
                    }
                    Text(h.text, color = CyanDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Rule()
            }
        }
    }
}

// ---------- people ----------

@Composable
private fun AppTags(chats: List<Conversation>) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    chats.map { it.channel }.distinct().forEach { Hud(it.label, size = 8.sp, color = Color(it.color)) }
}

@Composable
private fun PersonRow(p: Person, selected: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val c = p.latest
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) Cyan.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(p.name, c.channel)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.unread) Box(Modifier.padding(end = 6.dp).size(7.dp).background(Cyan, Tag))
                Text(p.name, Modifier.weight(1f), fontWeight = if (p.unread) FontWeight.Bold else FontWeight.Normal,
                    color = if (p.unread) Cyan else Cyan.copy(alpha = 0.85f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.needsReply) {
                    Hud("↩ reply", size = 7.sp, color = Alert, modifier = Modifier.border(1.dp, Alert.copy(alpha = 0.6f), Tag).padding(horizontal = 5.dp, vertical = 2.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Hud(shortTime(c.lastTs), size = 9.sp, color = CyanDim)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppTags(p.chats)
                Spacer(Modifier.width(8.dp))
                val preview = if (c.lastSender != null && c.lastSender != c.title) "${c.lastSender}: ${c.lastText}" else c.lastText
                Text(preview, fontSize = 15.sp, color = CyanDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One human's messages from every app, interleaved; replies go out on the chosen app. */
@Composable
private fun PersonScreen(store: Store, personKey: String, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
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

    Column(Modifier.fillMaxSize().imePadding()) {
        if (selection.active) SelectionBar(selection)
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(12.dp)) {
            items(messages.asReversed(), key = { it.id }) { m ->
                val chat = p.chats.firstOrNull { it.key == m.convKey } ?: p.latest
                Bubble(m, chat, showApp = true, selection = selection)
            }
        }
        Rule()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Hud("Via", size = 8.sp, color = CyanDim)
            p.chats.forEach { chat ->
                Chip(chat.channel.label, Color(chat.channel.color), chat.key == target.key) { replyKey = chat.key }
            }
            Hud("Chat ↗", size = 8.sp, modifier = Modifier.clickable { onOpenChat(target.key) }.padding(6.dp))
        }
        ReplyBar(ctx, store, target)
    }
}

@Composable
private fun LinkDialog(store: Store, c: Conversation, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    val all by produceState(emptyList<Conversation>()) { value = withContext(Dispatchers.IO) { store.conversations(null) } }
    val merged = all.filter { it.personKey == c.personKey && it.key != c.key }
    val candidates = all.filter { it.key != c.key && !it.isGroup && it.personKey != c.personKey && it.title.contains(q, true) }.take(40)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Panel, shape = Angled,
        title = { Hud("Link ${c.title}", size = 12.sp, bold = true) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (merged.isNotEmpty()) {
                    Text("Merged with: " + merged.joinToString { "${it.title} (${it.channel.label})" }, color = CyanDim, fontSize = 14.sp)
                    NeonButton("Unlink this chat") { scope.launch(Dispatchers.IO) { store.unlink(c.key) }; onDismiss() }
                }
                Text("Same person on another app:", color = CyanDim, fontSize = 14.sp)
                NeonField(q, { q = it }, "Filter", Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(candidates, key = { it.key }) { o ->
                        Row(Modifier.fillMaxWidth().clickable { scope.launch(Dispatchers.IO) { store.link(c.key, o.key) }; onDismiss() }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Hud(o.channel.label, size = 8.sp, color = Color(o.channel.color), modifier = Modifier.width(80.dp))
                            Text(o.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Hud("Close", size = 9.sp) } },
    )
}

// ---------- import ----------

@Composable
private fun ImportScreen(state: ImportState.State, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onDone(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDone(null) }) { Text("<", fontFamily = Head, fontSize = 20.sp, color = Cyan) }
            Hud("WhatsApp import", size = 16.sp, bold = true)
        }
        Rule()
        when (state) {
            ImportState.State.Reading -> Hud("Decoding archive…", color = CyanDim)
            is ImportState.State.Failed -> Text(state.reason, color = Alert)
            is ImportState.State.Ready -> {
                val parsed = state.parsed
                var title by rememberSaveable { mutableStateOf(parsed.title) }
                var me by rememberSaveable { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                Text("${parsed.lines.size} messages · ${shortTime(parsed.lines.first().ts)} → ${shortTime(parsed.lines.last().ts)}", color = CyanDim)
                NeonField(title, { title = it }, "Chat name", Modifier.fillMaxWidth())
                Hud("Which one is you?", size = 10.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    parsed.senders.take(12).forEach { name -> Chip(name, Cyan, me == name) { me = name } }
                }
                NeonButton("Import", !busy && me != null && title.isNotBlank()) {
                    busy = true
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { WhatsAppImport.save(ctx, parsed, title.trim(), me) }
                        Toast.makeText(ctx, "Imported $n messages", Toast.LENGTH_SHORT).show()
                        val key = withContext(Dispatchers.IO) { Store.get(ctx).conversations(Channel.WHATSAPP).firstOrNull { it.title.equals(title.trim(), true) }?.key }
                        onDone(key)
                    }
                }
            }
        }
    }
}
