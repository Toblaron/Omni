package com.omni.inbox

import android.content.Context
import io.github.up9cloud.td.JsonClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Telegram through TDLib's JSON interface: login, full history sync and live messages.
 * Messages land in the [Store] under conversation keys "tg:<chat_id>".
 */
object Telegram {
    sealed interface Auth {
        data object Unavailable : Auth          // libtdjson.so not bundled / failed to load
        data object NoCredentials : Auth
        data object Starting : Auth
        data object WaitPhone : Auth
        data class WaitCode(val phone: String?) : Auth
        data class WaitPassword(val hint: String?) : Auth
        data object Ready : Auth
        data class Unsupported(val step: String) : Auth
    }

    const val PKG = "org.telegram.messenger"
    private const val HISTORY_CHATS = 60
    private const val HISTORY_PER_CHAT = 300

    val auth = MutableStateFlow<Auth>(Auth.NoCredentials)
    val status = MutableStateFlow("")

    private lateinit var app: Context
    private var clientId = -1
    private var syncing = false
    private val nextExtra = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val users = ConcurrentHashMap<Long, String>()
    private val phones = ConcurrentHashMap<Long, String>() // user id → number, only for your contacts
    private val chats = ConcurrentHashMap<Long, String>()
    // a malformed update or a database error is logged, never allowed to crash the app
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e -> android.util.Log.e("OmniTelegram", "update failed", e) })

    private val loaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("tdjson")
            // synchronous offline call: proves the native library actually works
            android.util.Log.i("OmniTelegram", "TDLib self-test: " + JsonClient.td_execute(obj("getTextEntities", "text" to "@telegram").toString()).take(80))
        }.onFailure { android.util.Log.e("OmniTelegram", "TDLib unavailable", it) }.isSuccess
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("telegram", Context.MODE_PRIVATE)
    fun isLinked(ctx: Context) = prefs(ctx).getBoolean("linked", false)

    fun saveCredentials(ctx: Context, apiId: Int, apiHash: String) {
        prefs(ctx).edit().putInt("api_id", apiId).putString("api_hash", apiHash).apply()
        start(ctx)
    }

    @Synchronized
    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (!loaded) { auth.value = Auth.Unavailable; return }
        if (clientId >= 0) return
        if (prefs(app).getInt("api_id", 0) == 0) { auth.value = Auth.NoCredentials; return }
        JsonClient.td_execute(obj("setLogVerbosityLevel", "new_verbosity_level" to 1).toString())
        clientId = JsonClient.td_create_client_id()
        auth.value = Auth.Starting
        thread(name = "tdlib-receive", isDaemon = true) {
            while (true) {
                val raw = JsonClient.td_receive(10.0) ?: continue
                runCatching { onEvent(JSONObject(raw)) }
            }
        }
        // the client starts working on its first request and then reports its auth state
        send(obj("getOption", "name" to "version"))
    }

    // ---------- login ----------

    suspend fun submitPhone(phone: String) = errorOf(query(obj("setAuthenticationPhoneNumber", "phone_number" to phone)))
    suspend fun submitCode(code: String) = errorOf(query(obj("checkAuthenticationCode", "code" to code)))
    suspend fun submitPassword(pw: String) = errorOf(query(obj("checkAuthenticationPassword", "password" to pw)))

    suspend fun logOut() {
        query(obj("logOut"))
        prefs(app).edit().putBoolean("linked", false).apply()
    }

    /** Sends now, or at [at] (epoch ms) using Telegram's own server-side scheduling. */
    suspend fun sendText(chatId: Long, text: String, at: Long? = null): String? = errorOf(query(obj(
        "sendMessage",
        "chat_id" to chatId,
        "options" to at?.let { obj("messageSendOptions", "scheduling_state" to obj("messageSchedulingStateSendAtDate", "send_date" to (it / 1000).toInt())) },
        "input_message_content" to obj("inputMessageText", "text" to obj("formattedText", "text" to text)),
    )))

    // ---------- events ----------

    private fun onEvent(o: JSONObject) {
        if (o.has("@extra")) {
            pending.remove(o.optLong("@extra"))?.complete(o)
            return
        }
        when (o.optString("@type")) {
            "updateAuthorizationState" -> onAuthState(o.getJSONObject("authorization_state"))
            "updateUser" -> o.getJSONObject("user").let { remember(it) }
            "updateNewChat" -> o.getJSONObject("chat").let { chats[it.getLong("id")] = it.optString("title") }
            "updateChatTitle" -> chats[o.getLong("chat_id")] = o.optString("title")
            "updateNewMessage" -> o.getJSONObject("message").let { m ->
                // our own sends arrive first as pending; they're stored on updateMessageSendSucceeded.
                // scheduled ones wait on Telegram's servers and arrive again when actually sent
                if (!m.has("sending_state") && !m.has("scheduling_state")) scope.launch { save(listOf(m)) }
            }
            "updateMessageSendSucceeded" -> o.getJSONObject("message").let { m -> scope.launch { save(listOf(m)) } }
        }
    }

    private fun onAuthState(s: JSONObject) {
        when (val type = s.optString("@type")) {
            "authorizationStateWaitTdlibParameters" -> {
                val p = prefs(app)
                val dir = File(app.filesDir, "tdlib").absolutePath
                send(obj(
                    "setTdlibParameters",
                    "database_directory" to dir, "files_directory" to dir,
                    "use_file_database" to false, "use_chat_info_database" to true,
                    "use_message_database" to true, "use_secret_chats" to false,
                    "database_encryption_key" to if (p.getBoolean("db_keyed", false)) dbKey() else "",
                    "api_id" to p.getInt("api_id", 0), "api_hash" to p.getString("api_hash", ""),
                    "system_language_code" to java.util.Locale.getDefault().language, "device_model" to "Omni Inbox",
                    "application_version" to "0.3",
                ))
            }
            "authorizationStateWaitPhoneNumber" -> auth.value = Auth.WaitPhone
            "authorizationStateWaitCode" -> auth.value =
                Auth.WaitCode(s.optJSONObject("code_info")?.optString("phone_number"))
            "authorizationStateWaitPassword" -> auth.value = Auth.WaitPassword(s.optString("password_hint").ifBlank { null })
            "authorizationStateReady" -> {
                auth.value = Auth.Ready
                prefs(app).edit().putBoolean("linked", true).apply()
                scope.launch {
                    if (!prefs(app).getBoolean("db_keyed", false) &&
                        errorOf(query(obj("setDatabaseEncryptionKey", "new_encryption_key" to dbKey()))) == null
                    ) prefs(app).edit().putBoolean("db_keyed", true).apply()
                    syncHistory()
                }
            }
            "authorizationStateClosed" -> {
                // after logOut the client is gone; a fresh one starts the login over
                synchronized(this) { clientId = -1 }
                start(app)
            }
            "authorizationStateLoggingOut", "authorizationStateClosing" -> {}
            else -> auth.value = Auth.Unsupported(type.removePrefix("authorizationState"))
        }
    }

    // ---------- history ----------

    private suspend fun syncHistory() {
        if (syncing) return
        syncing = true
        try {
            status.value = s(R.string.tg_loading)
            val ids = query(obj("getChats", "chat_list" to obj("chatListMain"), "limit" to HISTORY_CHATS))
                .optJSONArray("chat_ids").longs()
            for ((i, chatId) in ids.withIndex()) {
                status.value = s(R.string.tg_syncing, i + 1, ids.size)
                var from = 0L
                var got = 0
                while (got < HISTORY_PER_CHAT) {
                    val msgs = query(obj(
                        "getChatHistory", "chat_id" to chatId, "from_message_id" to from,
                        "offset" to 0, "limit" to 100, "only_local" to false,
                    )).optJSONArray("messages").objects()
                    if (msgs.isEmpty()) break
                    save(msgs, notify = false)
                    got += msgs.size
                    from = msgs.last().getLong("id") // newest first, so this is the oldest so far
                }
                Store.get(app).changed()
            }
            status.value = s(R.string.tg_synced, ids.size)
        } catch (e: Exception) {
            status.value = s(R.string.tg_sync_stopped, e.message ?: "")
        } finally {
            syncing = false
        }
    }

    private suspend fun save(msgs: List<JSONObject>, notify: Boolean = true) {
        val store = Store.get(app)
        var added = false
        for (m in msgs) {
            val text = contentText(m.optJSONObject("content")) ?: continue
            val chatId = m.getLong("chat_id")
            val outgoing = m.optBoolean("is_outgoing")
            val title = chatTitle(chatId)
            added = store.add(
                Channel.TELEGRAM, PKG, "tg:$chatId", title, if (outgoing) null else senderName(m.optJSONObject("sender_id")),
                text, m.getLong("date") * 1000, outgoing, "tg:$chatId:${m.getLong("id")}", fuzzy = false,
                // private chats share the user's id; the number lets People merge them with SMS/WhatsApp
                address = if (chatId > 0) phoneOf(chatId) else null, notify = false,
            ) || added
        }
        if (added && notify) store.changed()
    }

    private suspend fun chatTitle(id: Long): String =
        chats[id] ?: query(obj("getChat", "chat_id" to id)).optString("title").ifBlank { "Telegram" }.also { chats[id] = it }

    private fun remember(u: JSONObject): String {
        val id = u.getLong("id")
        u.optString("phone_number").takeIf { it.isNotBlank() }?.let { phones[id] = it }
        return userName(u).also { users[id] = it }
    }

    private suspend fun phoneOf(userId: Long): String? {
        if (!users.containsKey(userId)) fetchUser(userId)
        return phones[userId]
    }

    private suspend fun fetchUser(id: Long): String? =
        query(obj("getUser", "user_id" to id)).takeIf { it.optString("@type") == "user" }?.let(::remember)

    private suspend fun senderName(s: JSONObject?): String? = when (s?.optString("@type")) {
        "messageSenderUser" -> s.getLong("user_id").let { id -> users[id] ?: fetchUser(id) }
        "messageSenderChat" -> chatTitle(s.getLong("chat_id"))
        else -> null
    }

    private fun userName(u: JSONObject) =
        listOf(u.optString("first_name"), u.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unknown" }

    /** Text for a message, a short label for media, null for service messages (joins, pins…). */
    private fun contentText(c: JSONObject?): String? {
        c ?: return null
        val caption = c.optJSONObject("caption")?.optString("text")?.takeIf { it.isNotBlank() }
        fun media(label: String) = if (caption != null) "$label · $caption" else label
        return when (val type = c.optString("@type")) {
            "messageText" -> c.optJSONObject("text")?.optString("text")
            "messagePhoto" -> media(s(R.string.media_photo))
            "messageVideo" -> media(s(R.string.media_video))
            "messageAnimation" -> media("GIF")
            "messageVoiceNote" -> media(s(R.string.media_voice))
            "messageVideoNote" -> s(R.string.media_video_note)
            "messageAudio" -> media("🎵 " + (c.optJSONObject("audio")?.optString("title")?.ifBlank { null } ?: s(R.string.media_audio)))
            "messageDocument" -> media("📎 " + (c.optJSONObject("document")?.optString("file_name")?.ifBlank { null } ?: s(R.string.media_file)))
            "messageSticker" -> (c.optJSONObject("sticker")?.optString("emoji") ?: "") + " " + s(R.string.media_sticker)
            "messageLocation", "messageVenue" -> s(R.string.media_location)
            "messageContact" -> s(R.string.media_contact)
            "messagePoll" -> "📊 " + (c.optJSONObject("poll")?.let { p -> p.optJSONObject("question")?.optString("text") ?: p.optString("question") } ?: s(R.string.media_poll))
            "messageCall" -> s(R.string.media_call)
            else -> if (type.startsWith("messageChat") || type.startsWith("messagePin") || type.startsWith("messageBasicGroup") ||
                type.startsWith("messageSupergroup") || type == "messageContactRegistered" || type == "messageScreenshotTaken"
            ) null else "[${type.removePrefix("message")}]"
        }
    }

    // ---------- plumbing ----------

    /** TDLib takes bytes as base64 in JSON. */
    private fun dbKey() = android.util.Base64.encodeToString(Vault.secret(app, "tdlib"), android.util.Base64.NO_WRAP)

    private fun send(o: JSONObject) = JsonClient.td_send(clientId, o.toString())

    private suspend fun query(o: JSONObject): JSONObject {
        val extra = nextExtra.getAndIncrement()
        val result = CompletableDeferred<JSONObject>()
        pending[extra] = result
        o.put("@extra", extra)
        send(o)
        return try { withTimeout(60_000) { result.await() } } finally { pending.remove(extra) }
    }

    private fun errorOf(r: JSONObject): String? =
        if (r.optString("@type") == "error") r.optString("message").ifBlank { "Telegram error" } else null

    private fun obj(type: String, vararg fields: Pair<String, Any?>) =
        JSONObject().put("@type", type).apply { fields.forEach { (k, v) -> put(k, v) } }

    private fun JSONArray?.longs(): List<Long> = this?.let { a -> List(a.length()) { a.getLong(it) } }.orEmpty()
    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
}
