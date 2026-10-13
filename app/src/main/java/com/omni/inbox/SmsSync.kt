package com.omni.inbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import java.util.concurrent.Executors

/** Mirrors the phone's SMS database into the [Store] and sends SMS replies. */
object SmsSync {
    private val io = Executors.newSingleThreadExecutor()
    private val names = HashMap<String, String>()
    private var observing = false
    @Volatile private var fullImportDone = false

    fun canRead(ctx: Context) = granted(ctx, Manifest.permission.READ_SMS)
    fun canSend(ctx: Context) = granted(ctx, Manifest.permission.SEND_SMS)

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Imports recent SMS and keeps following new ones while the process lives. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!canRead(app)) return
        // the deep import runs once per process; afterwards the observer picks up new messages
        if (!fullImportDone) io.execute { if (!fullImportDone) { import(app, 3000); fullImportDone = true } }
        if (observing) return
        observing = true
        app.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { io.execute { import(app, 50) } }
            },
        )
    }

    private fun import(ctx: Context, limit: Int) = runCatching { importNow(ctx, limit) }
        .onFailure { android.util.Log.e("OmniSms", "SMS import failed", it) }

    private fun importNow(ctx: Context, limit: Int) {
        val store = Store.get(ctx)
        val pkg = Telephony.Sms.getDefaultSmsPackage(ctx)
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            null, null, "${Telephony.Sms.DATE} DESC LIMIT $limit",
        ) ?: return
        var added = false
        store.inTransaction {
            cursor.use { c ->
                while (c.moveToNext()) {
                    val body = c.getString(3) ?: continue
                    val address = c.getString(2) ?: continue
                    val type = c.getInt(5)
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                    val outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                    val name = contactName(ctx, address)
                    added = store.add(
                        Channel.SMS, pkg, "sms:${c.getLong(1)}", name, if (outgoing) null else name,
                        body, c.getLong(4), outgoing, "sms:${c.getLong(0)}", fuzzy = true,
                        address = address, notify = false,
                    ) || added
                }
            }
        }
        if (added) store.changed()
    }

    private fun contactName(ctx: Context, address: String): String {
        names[address]?.let { return it }
        var name = address
        if (granted(ctx, Manifest.permission.READ_CONTACTS)) {
            ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) name = it.getString(0) }
        }
        names[address] = name
        return name
    }

    /** Sends via the system; the sent row lands in the SMS database and comes back through the observer. */
    fun send(ctx: Context, address: String, text: String) {
        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        if (parts.size > 1) sms.sendMultipartTextMessage(address, null, parts, null, null)
        else sms.sendTextMessage(address, null, text, null, null)
    }
}
