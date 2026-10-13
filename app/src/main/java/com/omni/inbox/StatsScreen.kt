package com.omni.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PersonStats(val person: Person, val mine: List<Long>, val theirs: List<Long>, val sent: Int, val received: Int)

private data class Report(
    val people: List<PersonStats>,
    val perApp: List<Pair<Channel, Int>>,
    val waiting: List<Person>,
)

/** Reply speed both ways, who's been waiting longest, and where your messages happen. */
@Composable
internal fun StatsScreen(store: Store, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val report by produceState<Report?>(null) {
        value = withContext(Dispatchers.IO) {
            val chats = Stats.current(store)
            val people = People.group(store.inbox()).map { p ->
                val cs = p.chats.mapNotNull { chats[it.key] }
                PersonStats(p, cs.flatMap { it.mine }, cs.flatMap { it.theirs }, cs.sumOf { it.sent }, cs.sumOf { it.received })
            }
            val perApp = store.timeline(System.currentTimeMillis() - Stats.WINDOW).groupingBy { it.channel }.eachCount()
                .toList().sortedByDescending { it.second }
            val waiting = people.map { it.person }.filter { it.needsReply }.sortedBy { p -> p.chats.filter { it.needsReply }.minOf { it.lastTs } }
            Report(people, perApp, waiting)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("<", fontFamily = Head, fontSize = 20.sp, color = Cyan) }
            Hud(s(R.string.stats_title), size = 16.sp, bold = true)
        }
        Rule()
        val r = report ?: run { Hud(s(R.string.crunching), color = CyanDim, modifier = Modifier.padding(20.dp)); return }
        val open = { p: Person -> onOpen(if (p.chats.size == 1) p.latest.key else "person:${p.key}") }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Section(s(R.string.per_app)) }
            val top = r.perApp.maxOfOrNull { it.second } ?: 1
            items(r.perApp, key = { "app" + it.first }) { (ch, n) -> Bar(ch.label, Color(ch.color), n, top) }

            item { Section(s(R.string.waiting_longest)) }
            if (r.waiting.isEmpty()) item { Text(s(R.string.inbox_zero), color = CyanDim) }
            items(r.waiting.take(8), key = { "w" + it.key }) { p ->
                val since = p.chats.filter { it.needsReply }.minOf { it.lastTs }
                Line(p.name, Stats.duration(System.currentTimeMillis() - since)) { open(p) }
            }

            val mine = r.people.filter { it.mine.size >= 3 }.sortedBy { Stats.median(it.mine) }
            item { Section(s(R.string.you_fastest)) }
            items(mine.take(8), key = { "m" + it.person.key }) { s ->
                Line((if (Stats.isVip(s.mine)) "★ " else "") + s.person.name, Stats.duration(Stats.median(s.mine)!!)) { open(s.person) }
            }
            item { Section(s(R.string.you_slowest)) }
            items(mine.asReversed().take(5), key = { "s" + it.person.key }) { s ->
                Line(s.person.name, Stats.duration(Stats.median(s.mine)!!)) { open(s.person) }
            }

            val theirs = r.people.filter { it.theirs.size >= 3 }.sortedBy { Stats.median(it.theirs) }
            item { Section(s(R.string.they_fastest)) }
            items(theirs.take(8), key = { "t" + it.person.key }) { s ->
                Line(s.person.name, Stats.duration(Stats.median(s.theirs)!!)) { open(s.person) }
            }
            if (mine.isEmpty() && theirs.isEmpty()) item { Text(s(R.string.stats_empty), color = CyanDim) }
        }
    }
}

@Composable
private fun Section(title: String) = Hud(title, size = 11.sp, bold = true, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))

@Composable
private fun Line(name: String, value: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), color = Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Hud(value, size = 10.sp, color = CyanDim)
    }

@Composable
private fun Bar(label: String, color: Color, n: Int, top: Int) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hud(label, size = 9.sp, color = color, modifier = Modifier.width(90.dp))
        Box(Modifier.weight(1f).height(10.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(n.toFloat() / top).background(color.copy(alpha = 0.7f), Tag))
        }
        Hud("$n", size = 9.sp, color = CyanDim, modifier = Modifier.padding(start = 8.dp).width(48.dp))
    }
