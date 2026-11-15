package app.zhiyi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.zhiyi.AppVm
import app.zhiyi.core.ChatCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QQ 那边的会话。
 *
 * 为什么单独一页：QQ 的记忆按 `pid#openid` 存（每个人、每个群各一条线），
 * App 主界面读的是裸 pid，所以这些会话在主界面里根本不存在。
 * 这里把它们列出来，点进去能看聊天记录和记住的事。
 *
 * 只读——想继续聊就回 QQ。
 */
@Composable
fun QqSessionsScreen(vm: AppVm, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ChatCore.db(ctx) }

    var stats by remember { mutableStateOf<List<Pair<app.zhiyi.data.Db.ThreadStat, String>>>(emptyList()) }
    var opened by remember { mutableStateOf<String?>(null) }
    var msgs by remember { mutableStateOf<List<app.zhiyi.data.Msg>>(emptyList()) }
    var facts by remember { mutableStateOf<List<app.zhiyi.data.Fact>>(emptyList()) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) {
            db.scopedThreads().map { s ->
                val pid = s.key.substringBefore("#")
                val who = s.key.substringAfter("#")
                val label = if (who.startsWith("g:")) "群 …${who.removePrefix("g:").takeLast(8)}"
                else "私聊 …${who.takeLast(8)}"
                val name = runCatching { db.persona(pid)?.name }.getOrNull().orEmpty()
                s to (if (name.isBlank()) label else "$name · $label")
            }
        }
    }

    LaunchedEffect(opened) {
        val k = opened
        if (k == null) { msgs = emptyList(); facts = emptyList(); return@LaunchedEffect }
        val r = withContext(Dispatchers.IO) {
            db.recentMsgs(k, 80).reversed() to db.factsPage(k, 0, 40)
        }
        msgs = r.first
        facts = r.second
    }

    Column(Modifier.fillMaxSize().background(T.bg).statusBarsPadding()) {
        GlassTopBar(
            title = if (opened == null) "QQ 会话" else "会话详情",
            subtitle = if (opened == null) "${stats.size} 条记录线" else "",
            onBack = { if (opened != null) opened = null else onBack() },
        )

        if (opened == null) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = D.s5, end = D.s5, bottom = D.s6),
                verticalArrangement = Arrangement.spacedBy(D.s3),
            ) {
                item("intro") {
                    Text(
                        "QQ 里的对话和记忆按人/群分开存，主界面看不到，都在这里。只读。",
                        style = Ty.caption, color = T.textFaint,
                    )
                }
                if (stats.isEmpty()) {
                    item("empty") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(D.s4)) {
                                Text("还没有 QQ 会话", style = Ty.body, color = T.textDim)
                                Spacer(Modifier.height(D.s2))
                                Text("在 QQ 里跟她聊几句就会出现。", style = Ty.caption, color = T.textFaint)
                            }
                        }
                    }
                }
                items(stats) { (s, label) ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().clickable { opened = s.key }.padding(D.s4)
                        ) {
                            Text(label, style = Ty.bodySm, color = T.text)
                            Spacer(Modifier.height(D.s1))
                            Text(
                                "${s.msgs} 条 · 最后 ${agoShort(s.lastTs)}",
                                style = Ty.caption, color = T.textFaint,
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = D.s5, end = D.s5, bottom = D.s6),
                verticalArrangement = Arrangement.spacedBy(D.s3),
            ) {
                if (facts.isNotEmpty()) {
                    item("facts_h") {
                        Label("她记住的（${facts.size}）")
                    }
                    items(facts) { f ->
                        Text(
                            "· " + f.line(),
                            style = Ty.caption, color = T.textDim,
                            modifier = Modifier.padding(start = D.s2),
                        )
                    }
                }
                item("msgs_h") { Label("聊天记录") }
                items(msgs) { m ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            if (m.role == "user") "对方" else "她",
                            style = Ty.caption,
                            color = if (m.role == "user") T.textFaint else T.accent,
                        )
                        Text(m.content, style = Ty.bodySm, color = T.text)
                    }
                }
            }
        }
    }
}

private fun agoShort(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        d < 86_400_000L -> "${d / 3_600_000} 小时前"
        else -> "${d / 86_400_000L} 天前"
    }
}
