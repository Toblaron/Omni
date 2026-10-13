package com.omni.inbox

import java.util.Calendar

/**
 * Reads meaning out of message text, Norwegian and English, entirely on the phone:
 * whether a message expects an answer, and the times and places it mentions.
 */
object Signals {
    private const val PRONOUNS = "du|dere|vi|jeg|han|hun|den|det|noen|man|you|we|i|he|she|they|it|u|anyone|someone"
    private const val VERBS = "kan|kunne|skal|skulle|vil|ville|har|hadde|er|var|gidder|blir|må|bør|trenger|liker|vet|kommer|går|tar|fikk|" +
        "can|could|will|would|are|is|do|does|did|should|shall|have|has|was|were"

    private val QUESTION_WORD = Regex("""^(hva|hvor|når|hvem|hvorfor|hvordan|hvilken|hvilke|hvilket|hvor mye|hvor mange|what|when|where|who|why|how|which)\b""")
    private val VERB_FIRST = Regex("""^($VERBS)\s+($PRONOUNS)\b""")
    private val REQUEST = Regex(
        """\b(ring meg|ring når|ring tilbake|gi beskjed|gi lyd|si ifra|si fra|send meg|svar|husk|ikke glem|vær så snill|vennligst|""" +
            """call me|call back|let me know|text me|send me|tell me|remind|don't forget|dont forget|please|pls|plz)\b"""
    )
    private val SNAP = Regex("""(sent you a snap|sendte deg en snap)""")
    private val ACK_WORDS = 5

    /** True when [text] reads like it expects an answer: a question, a request, a plan, or anything substantial. */
    fun asks(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        if ('?' in t || QUESTION_WORD.containsMatchIn(t) || VERB_FIRST.containsMatchIn(t)) return true
        if (REQUEST.containsMatchIn(t) || SNAP.containsMatchIn(t) || whens(text, System.currentTimeMillis()).isNotEmpty()) return true
        // "ok", "haha", "takk!", "👍", "kommer om 5" don't need an answer; longer messages usually do
        return t.split(Regex("\\s+")).count { w -> w.any(Char::isLetterOrDigit) } > ACK_WORDS
    }

    // ---------- times ----------

    data class When(val start: Long, val hasTime: Boolean, val match: String)

    private val DAYS_NO = listOf("søndag", "mandag", "tirsdag", "onsdag", "torsdag", "fredag", "lørdag")
    private val DAYS_EN = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val MONTHS = listOf(
        "jan|januar|january", "feb|februar|february", "mar|mars|march", "apr|april", "mai|may", "jun|juni|june",
        "jul|juli|july", "aug|august", "sep|sept|september", "okt|oct|oktober|october", "nov|november", "des|dec|desember|december",
    ).map { it.split('|') }
    private val DAY_WORDS = Regex(
        """\b(i dag|idag|today|i morgen|imorgen|imorra|i morra|tomorrow|i overmorgen|i kveld|ikveld|tonight|""" +
            """(?:på |on |neste |next )?(?:${(DAYS_NO + DAYS_EN).joinToString("|")}))\b"""
    )
    // "kl 18", "kl. 18.30", "klokka 7", "at 6pm" / "18:30" / "6 pm"
    private val CLOCK_PREFIXED = Regex("""\b(?:kl\.?|klokka|at)\s*(\d{1,2})(?:[:.](\d{2}))?(?:\s*(am|pm))?\b""")
    private val CLOCK_COLON = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val CLOCK_AMPM = Regex("""\b(\d{1,2})\s*(am|pm)\b""")
    // "14.10", "14/10/26"; times are matched (and blanked out) first, so "kl 14.10" stays a time
    private val DATE_NUM = Regex("""\b(\d{1,2})[./](\d{1,2})(?:[./](\d{2}|\d{4}))?\b""")
    private val DATE_WORD = Regex("""\b(\d{1,2})\.?\s+(${MONTHS.flatten().sortedByDescending { it.length }.joinToString("|")})\b""")

    /** Times mentioned in [text], resolved against [ref] (when the message was sent). */
    fun whens(text: String, ref: Long): List<When> {
        var t = text.lowercase()
        val base = Calendar.getInstance().apply { timeInMillis = ref }

        // clock time
        var hour = -1; var minute = 0; var clockMatch = ""; var bare = false
        val clock = CLOCK_PREFIXED.find(t)?.let { m -> Triple(m, m.groupValues[1], m.groupValues[2] to m.groupValues[3]) }
            ?: CLOCK_COLON.find(t)?.let { m -> Triple(m, m.groupValues[1], m.groupValues[2] to "") }
            ?: CLOCK_AMPM.find(t)?.let { m -> Triple(m, m.groupValues[1], "" to m.groupValues[2]) }
        clock?.let { (m, h, rest) ->
            val (min, ampm) = rest
            val hh = h.toInt(); val mm = min.toIntOrNull() ?: 0
            if (hh in 0..23 && mm in 0..59 && !(ampm.isNotEmpty() && hh > 12)) {
                hour = when { ampm == "pm" && hh < 12 -> hh + 12; ampm == "am" && hh == 12 -> 0; else -> hh }
                minute = mm
                bare = min.isEmpty() && ampm.isEmpty()
                clockMatch = m.value.trim()
                t = t.replaceRange(m.range, " ".repeat(m.value.length))
            }
        }

        // day
        var day: Calendar? = null
        var dayMatch = ""
        var evening = false
        DAY_WORDS.find(t)?.let { m ->
            dayMatch = m.value
            val w = m.value.removePrefix("på ").removePrefix("on ")
            val next = w.startsWith("neste ") || w.startsWith("next ")
            val name = w.removePrefix("neste ").removePrefix("next ")
            val cal = base.clone() as Calendar
            when (name) {
                "i dag", "idag", "today" -> {}
                "i kveld", "ikveld", "tonight" -> evening = true
                "i morgen", "imorgen", "imorra", "i morra", "tomorrow" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                "i overmorgen" -> cal.add(Calendar.DAY_OF_YEAR, 2)
                else -> {
                    val target = (DAYS_NO.indexOf(name).takeIf { it >= 0 } ?: DAYS_EN.indexOf(name)) + 1 // Calendar.SUNDAY = 1
                    var ahead = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                    if (ahead == 0) ahead = 7 // "på fredag" said on a Friday means next week
                    if (next && ahead < 7) ahead += 7
                    cal.add(Calendar.DAY_OF_YEAR, ahead)
                }
            }
            day = cal
        }
        val word = DATE_WORD.find(t)
        if (word != null) {
            val month = MONTHS.indexOfFirst { word.groupValues[2] in it }
            dateIn(base, word.groupValues[1].toInt(), month, null)?.let { day = it; dayMatch = word.value }
        } else DATE_NUM.find(t)?.let { m ->
            dateIn(base, m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toIntOrNull())?.let { day = it; dayMatch = m.value }
        }

        if (day == null && hour < 0 && !evening) return emptyList()
        // "kl 7" for plans means the evening; "kl 7 i kveld" certainly does
        if (bare && hour in 1..7) hour += 12
        if (evening && hour in 1..11) hour += 12

        val cal = (day ?: base.clone() as Calendar).apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            when {
                hour >= 0 -> { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
                evening -> { set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0) }
                else -> { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }
            }
        }
        // a bare time already past today means tomorrow
        if (day == null && hour >= 0 && cal.timeInMillis <= ref) cal.add(Calendar.DAY_OF_YEAR, 1)
        val match = listOf(dayMatch, clockMatch).filter { it.isNotBlank() }.joinToString(" ")
        return listOf(When(cal.timeInMillis, hour >= 0 || evening, match))
    }

    /** Day [d] of [month] in [year] (the next such date from [base] without a year); null if not a date. */
    private fun dateIn(base: Calendar, d: Int, month: Int, year: Int?): Calendar? {
        if (month !in 0..11 || d < 1) return null
        return (base.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            if (year != null) set(Calendar.YEAR, if (year < 100) 2000 + year else year)
            set(Calendar.MONTH, month)
            if (d > getActualMaximum(Calendar.DAY_OF_MONTH)) return null
            set(Calendar.DAY_OF_MONTH, d)
            if (year == null && timeInMillis < base.timeInMillis - 86_400_000) add(Calendar.YEAR, 1)
        }
    }

    // ---------- places ----------

    private val STREET = Regex(
        """\b[A-ZÆØÅ][\wæøåÆØÅ.-]*(?:gata|gaten|gate|veien|vei|vegen|veg|plass|plassen|allé|alle|torget|bakken|stien|brygge|brygga|kaia)""" +
            """\s+\d{1,4}\s?[a-zA-Z]?\b(?:,\s*\d{4}\s+[A-ZÆØÅ][\wæøå]+)?""" +
            """|\b\d{1,5}[A-Za-z]?\s+[A-Z][\w.]*(?:\s+[A-Z][\w.]*)?\s+(?:Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Boulevard|Blvd|Drive|Dr)\b\.?"""
    )

    /** Street addresses in [text]: "Storgata 5", "Bogstadveien 12B, 0355 Oslo", "221B Baker Street". */
    fun places(text: String): List<String> = STREET.findAll(text).map { it.value.trim() }.distinct().toList()
}
