package com.omni.inbox

/**
 * Reply-time statistics from the message timeline: how fast you answer each chat and how fast
 * they answer you. People you reliably answer quickly become VIPs.
 */
object Stats {
    data class Row(val convKey: String, val channel: Channel, val ts: Long, val outgoing: Boolean)

    /** Per-chat reply delays in ms: yours to them, and theirs to you. */
    data class Chat(val key: String, val mine: List<Long>, val theirs: List<Long>, val sent: Int, val received: Int)

    const val WINDOW = 60 * 86_400_000L
    /** Gaps longer than this are a new conversation, not a slow reply. */
    private const val MAX_GAP = 3 * 86_400_000L
    private const val VIP_MIN_REPLIES = 5
    private const val VIP_MEDIAN = 20 * 60_000L

    /** [rows] ordered by chat, then time. */
    fun compute(rows: List<Row>): Map<String, Chat> = rows.groupBy { it.convKey }.mapValues { (key, msgs) ->
        val mine = mutableListOf<Long>(); val theirs = mutableListOf<Long>()
        var waitingIn: Long? = null  // first unanswered message from them
        var waitingOut: Long? = null // first unanswered message from you
        for (m in msgs) {
            if (m.outgoing) {
                waitingIn?.let { if (m.ts - it <= MAX_GAP) mine += m.ts - it }
                waitingIn = null
                if (waitingOut == null) waitingOut = m.ts
            } else {
                waitingOut?.let { if (m.ts - it <= MAX_GAP) theirs += m.ts - it }
                waitingOut = null
                if (waitingIn == null) waitingIn = m.ts
            }
        }
        Chat(key, mine, theirs, msgs.count { it.outgoing }, msgs.count { !it.outgoing })
    }

    fun median(xs: List<Long>): Long? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    fun isVip(mine: List<Long>): Boolean = mine.size >= VIP_MIN_REPLIES && (median(mine) ?: Long.MAX_VALUE) <= VIP_MEDIAN

    @Volatile private var cache: Pair<Long, Map<String, Chat>>? = null

    /** Stats for the last [WINDOW], recomputed at most every 10 minutes. */
    fun current(store: Store): Map<String, Chat> {
        val now = System.currentTimeMillis()
        cache?.let { (at, v) -> if (now - at < 600_000) return v }
        return compute(store.timeline(now - WINDOW)).also { cache = now to it }
    }

    fun vipKeys(store: Store): Set<String> = current(store).values.filter { isVip(it.mine) }.mapTo(HashSet()) { it.key }

    /** "4 min", "2 h", "3 d". */
    fun duration(ms: Long): String = when {
        ms < 3_600_000 -> "${(ms / 60_000).coerceAtLeast(1)} min"
        ms < 86_400_000 -> "${ms / 3_600_000} h"
        else -> "${ms / 86_400_000} d"
    }
}
