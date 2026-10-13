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

internal val Void = Color(0xFF020406)
internal val Panel = Color(0xFF070D12)
internal val Line = Color(0xFF0C3440)
internal val Cyan = Color(0xFF00E5FF)
internal val CyanDim = Color(0xFF4FA9B8)
internal val Alert = Color(0xFFFF3D7F)

internal val Head = FontFamily(Font(R.font.orbitron, FontWeight.Normal), Font(R.font.orbitron, FontWeight.Bold))
internal val Body = FontFamily(Font(R.font.rajdhani_medium, FontWeight.Normal), Font(R.font.rajdhani_semibold, FontWeight.SemiBold))

internal val Angled = CutCornerShape(topStart = 0.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 10.dp)
internal val Tag = CutCornerShape(4.dp)

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
internal fun Hud(text: String, size: TextUnit = 11.sp, color: Color = Cyan, modifier: Modifier = Modifier, bold: Boolean = false) =
    Text(text.uppercase(), modifier, color = color, fontFamily = Head, fontSize = size, letterSpacing = 2.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
internal fun NeonButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = Angled, border = BorderStroke(1.dp, if (enabled) Cyan else Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Cyan.copy(alpha = 0.08f), contentColor = Cyan),
    ) { Text(text.uppercase(), fontFamily = Head, fontSize = 12.sp, letterSpacing = 1.5.sp) }

@Composable
internal fun NeonField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
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
internal fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

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
    // message to scroll to and highlight when a chat opens from search
    var focus by rememberSaveable { mutableLongStateOf(0L) }
    fun go(to: String, focusId: Long = 0L) { back = if (route in setOf("search", "settings", "stats")) route else "inbox"; route = to; focus = focusId }
    fun up() { route = back; back = "inbox"; focus = 0L }
    val locked by AppLock.locked.collectAsState()
    val openRequest by MainActivity.openRequest.collectAsState()
    LaunchedEffect(openRequest) { openRequest?.let { go(it); MainActivity.openRequest.value = null } }
    BackHandler(route != "inbox") { up() }
    Surface(Modifier.fillMaxSize(), color = Void) {
        Box(Modifier.safeDrawingPadding()) {
            val importing by ImportState.state.collectAsState()
            if (locked) LockScreen()
            else if (importing != null) ImportScreen(importing!!) { key -> ImportState.clear(); if (key != null) go(key) }
            else when (route) {
                "inbox" -> InboxScreen(store, onOpen = { go(it) }, onTelegram = { go("telegram") }, onSearch = { go("search") }, onSettings = { go("settings") })
                "telegram" -> TelegramScreen(::up)
                "settings" -> SettingsScreen(onTelegram = { go("telegram") }, onStats = { go("stats") }, onBack = ::up)
                "stats" -> StatsScreen(store, onOpen = { go(it) }, onBack = ::up)
                "search" -> SearchScreen(store, onOpen = ::go, onBack = ::up)
                in Regex("person:.*") -> PersonScreen(store, route.removePrefix("person:"), onOpenChat = { go(it) }, onBack = ::up)
                else -> ConversationScreen(store, route, focus, ::up)
            }
        }
    }
}

// ---------- shared widgets ----------

@Composable
internal fun TelegramBadge(onClick: () -> Unit) {
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
internal fun Chip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = Tag,
        color = if (selected) color.copy(alpha = 0.14f) else Panel,
        border = BorderStroke(1.dp, if (selected) color else Line),
    ) {
        Hud(label, size = 10.sp, color = if (selected) color else CyanDim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
internal fun Avatar(title: String, channel: Channel) {
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
internal fun SetupCard() {
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
                NeonField(input, { input = it }, "+47 412 34 567", Modifier.fillMaxWidth(), KeyboardType.Phone)
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

// ---------- time ----------

internal fun shortTime(ts: Long): String =
    if (DateUtils.isToday(ts)) DateFormat.getTimeInstance(DateFormat.SHORT).format(ts)
    else SimpleDateFormat("d MMM", Locale.getDefault()).format(ts)

// ---------- small header button ----------

@Composable
internal fun HudButton(label: String, onClick: () -> Unit) =
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
private fun SettingsScreen(onTelegram: () -> Unit, onStats: () -> Unit, onBack: () -> Unit) {
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
        NeonButton("Stats", onClick = onStats)
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
private fun SearchScreen(store: Store, onOpen: (String, Long) -> Unit, onBack: () -> Unit) {
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
            items(hits, key = { it.id }) { h ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(h.convKey, h.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
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
internal fun AppTags(chats: List<Conversation>) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    chats.map { it.channel }.distinct().forEach { Hud(it.label, size = 8.sp, color = Color(it.color)) }
}

@Composable
internal fun LinkDialog(store: Store, c: Conversation, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    val all by produceState(emptyList<Conversation>()) { value = withContext(Dispatchers.IO) { store.conversations(null) } }
    val mine = People.group(all).firstOrNull { p -> p.chats.any { it.key == c.key } }?.chats.orEmpty().map { it.key }.toSet()
    val merged = all.filter { it.key in mine && it.key != c.key }
    val candidates = all.filter { it.key !in mine && !it.isGroup && it.title.contains(q, true) }.take(40)
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
