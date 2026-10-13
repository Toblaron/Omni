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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat

private val Accent = Color(0xFFCBA6F7)

@Composable
fun OmniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color(0xFF1E1E2E),
            background = Color(0xFF11111B), surface = Color(0xFF11111B),
            surfaceVariant = Color(0xFF1E1E2E), onSurfaceVariant = Color(0xFFA6ADC8),
            onBackground = Color(0xFFCDD6F4), onSurface = Color(0xFFCDD6F4),
        ),
        content = content,
    )
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(open != null) { open = null }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) {
            val key = open
            if (key == null) InboxScreen(store) { open = it }
            else ConversationScreen(store, key) { open = null }
        }
    }
}

// ---------- inbox ----------

@Composable
private fun InboxScreen(store: Store, onOpen: (String) -> Unit) {
    val version by store.changes.collectAsState()
    var filter by rememberSaveable { mutableStateOf<Channel?>(null) }
    val conversations by produceState(emptyList<Conversation>(), version, filter) {
        value = withContext(Dispatchers.IO) { store.conversations(filter) }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Omni Inbox", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Accent,
        )
        SetupCard()
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("All", Accent, filter == null) { filter = null }
            Channel.entries.forEach { ch -> Chip(ch.label, Color(ch.color), filter == ch) { filter = ch } }
        }
        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No messages yet.\nNew messages from your apps will appear here as they arrive.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(conversations, key = { it.key }) { c -> ConversationRow(c) { onOpen(c.key) } }
            }
        }
    }
}

@Composable
private fun Chip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(50),
        color = if (selected) color.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, color) else null,
    ) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 14.sp,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.title, c.channel)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortTime(c.lastTs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.channel.label, fontSize = 12.sp, color = Color(c.channel.color), fontWeight = FontWeight.Medium)
                Text(" · ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val preview = if (c.lastSender != null && c.lastSender != c.title) "${c.lastSender}: ${c.lastText}" else c.lastText
                Text(preview, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Avatar(title: String, channel: Channel) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(Color(channel.color).copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#", color = Color(channel.color),
            fontWeight = FontWeight.Bold, fontSize = 18.sp)
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

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setup", fontWeight = FontWeight.Bold)
            if (!listenerOn) {
                Text("Allow notification access so WhatsApp, Telegram, Signal, Messenger and Snapchat messages show up here.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { openListenerSettings(ctx) }) { Text("Allow notification access") }
            }
            if (!smsOn) {
                Text("Allow SMS and contacts to show your text messages with names.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    askPerms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
                }) { Text("Allow SMS & contacts") }
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

// ---------- conversation ----------

@Composable
private fun ConversationScreen(store: Store, key: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by store.changes.collectAsState()
    val conv by produceState<Conversation?>(null, version) { value = withContext(Dispatchers.IO) { store.conversation(key) } }
    val messages by produceState(emptyList<Msg>(), version) { value = withContext(Dispatchers.IO) { store.messages(key) } }
    val c = conv ?: return

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Avatar(c.title, c.channel)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.channel.label, fontSize = 12.sp, color = Color(c.channel.color))
            }
            c.pkg?.let { pkg -> TextButton(onClick = { openApp(ctx, pkg) }) { Text("Open app") } }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(12.dp)) {
            items(messages.asReversed(), key = { it.id }) { m -> Bubble(m, c) }
        }

        ReplyBar(ctx, store, c)
    }
}

@Composable
private fun Bubble(m: Msg, c: Conversation) {
    val mine = m.outgoing
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp),
            color = if (mine) Accent.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!mine && m.sender != null && m.sender != c.title) {
                    Text(m.sender, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(c.channel.color))
                }
                Text(m.text, color = if (mine) Color(0xFF1E1E2E) else MaterialTheme.colorScheme.onSurface)
                Text(shortTime(m.ts), fontSize = 10.sp, modifier = Modifier.align(Alignment.End),
                    color = if (mine) Color(0xFF1E1E2E).copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReplyBar(ctx: Context, store: Store, c: Conversation) {
    val version by store.changes.collectAsState()
    val isSms = c.channel == Channel.SMS && c.address != null && c.key.startsWith("sms:")
    val canReply = remember(version, c.key) { (isSms && SmsSync.canSend(ctx)) || Replies.canReply(c.key) }
    var text by rememberSaveable(c.key) { mutableStateOf("") }

    if (!canReply) {
        Text(
            "Replying works after a new message arrives from this chat (it uses the notification's Reply button). Use \"Open app\" meanwhile.",
            Modifier.padding(16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
            placeholder = { Text("Message ${c.channel.label}") }, shape = RoundedCornerShape(24.dp), maxLines = 5,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            enabled = text.isNotBlank(), shape = CircleShape, contentPadding = PaddingValues(14.dp),
            onClick = {
                val body = text.trim()
                val ok = if (isSms) runCatching { SmsSync.send(ctx, c.address!!, body) }.isSuccess
                else Replies.send(ctx, c.key, body)
                if (ok) {
                    if (!isSms) {
                        val now = System.currentTimeMillis()
                        store.add(c.channel, c.pkg, c.key, c.title, null, body, now, true, "${c.key}|me|$now", fuzzy = true)
                    }
                    text = ""
                } else {
                    Toast.makeText(ctx, "Couldn't send - open ${c.channel.label} to reply", Toast.LENGTH_LONG).show()
                }
            },
        ) { Text("➤", fontSize = 18.sp) }
    }
}

private fun openApp(ctx: Context, pkg: String) {
    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (intent != null) ctx.startActivity(intent)
    else Toast.makeText(ctx, "App not installed", Toast.LENGTH_SHORT).show()
}

private fun shortTime(ts: Long): String =
    if (DateUtils.isToday(ts)) DateFormat.getTimeInstance(DateFormat.SHORT).format(ts)
    else java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(ts)
