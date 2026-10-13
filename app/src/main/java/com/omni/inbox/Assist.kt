package com.omni.inbox

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Catch-up summaries and reply suggestions from Gemini Nano, which runs inside Android's AICore
 * on supported phones. Message text never leaves the device. Everything here degrades to "not
 * available" on phones without AICore, and callers simply hide the feature.
 */
object Assist {
    enum class State { CHECKING, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, READY }

    val state = MutableStateFlow(State.CHECKING)
    val progress = MutableStateFlow("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val model by lazy { Generation.getClient() }

    fun check() = scope.launch {
        state.value = runCatching {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> State.READY
                FeatureStatus.DOWNLOADABLE -> State.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> State.DOWNLOADING
                else -> State.UNAVAILABLE
            }
        }.getOrDefault(State.UNAVAILABLE)
    }

    /** Fetches the model through AICore (a few hundred MB, Wi-Fi recommended). */
    fun download() = scope.launch {
        state.value = State.DOWNLOADING
        runCatching {
            model.download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadProgress -> progress.value = "${s.totalBytesDownloaded / 1_000_000} MB"
                    is DownloadStatus.DownloadCompleted -> state.value = State.READY
                    is DownloadStatus.DownloadFailed -> { state.value = State.DOWNLOADABLE; progress.value = s(R.string.ai_download_failed) }
                    else -> {}
                }
            }
        }.onFailure { state.value = State.DOWNLOADABLE; progress.value = s(R.string.ai_download_failed) }
    }

    /** A few sentences on what happened and what's being asked of you. */
    suspend fun summarize(chat: String, msgs: List<Msg>): String? = ask(
        "Summarize this chat with $chat for me in 2-4 short sentences. Mention anything they asked me or plans " +
            "with times. Write in the same language as the messages.\n\n" + transcript(chat, msgs, 40),
    )

    /** One line per waiting person: "Anna: wants to know if you're coming Saturday". */
    suspend fun brief(people: List<Pair<String, List<Msg>>>): String? = ask(
        "These people are waiting for my reply. For each, write one short line \"Name: what they want\". " +
            "Same language as their messages, no intro.\n\n" +
            people.joinToString("\n\n") { (name, msgs) -> "## $name\n" + transcript(name, msgs, 6) },
    )

    /** Up to three short replies in my voice and the chat's language. */
    suspend fun replies(chat: String, msgs: List<Msg>): List<String> = ask(
        "Suggest 3 short, natural replies I could send next in this chat, each under 12 words, in the same " +
            "language and tone as the chat. One per line, no numbering, no quotes.\n\n" + transcript(chat, msgs, 15),
    )?.lines()?.map { it.trim().trimStart('-', '•', '*', ' ').trim('"') }?.filter { it.isNotBlank() && it.length < 120 }?.take(3).orEmpty()

    private fun transcript(chat: String, msgs: List<Msg>, last: Int): String =
        msgs.takeLast(last).joinToString("\n") { m -> (if (m.outgoing) "Me" else m.sender ?: chat) + ": " + m.text.take(300) }

    private suspend fun ask(prompt: String): String? {
        if (state.value != State.READY) return null
        return runCatching { model.generateContent(prompt).candidates.firstOrNull()?.text?.trim() }
            .onFailure { android.util.Log.w("OmniAssist", "generation failed", it) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
