package app.zhiyi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zhiyi.AppVm
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 会话列表 —— 像微信那样先选人，再进对话。
 * 每个人格有自己的对话和记忆，点进去就是"换了一个人"。
 */
@Composable
fun PersonaListScreen(vm: AppVm, onOpenChat: (String) -> Unit, onOpenSettings: () -> Unit, onOpenProfile: (String) -> Unit = {}) {
    val rows = vm.rows
    var pendingClear by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(Modifier.fillMaxSize()) {
        GlassTopBar(
            title = "对话",
            subtitle = if (rows.isEmpty()) "还没有人" else "${rows.size} 个人格 · 各有各的记忆",
            trailing = {
                Text(
                    "＋", style = Ty.title2, color = T.accent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onOpenSettings() }
                        .padding(horizontal = D.s3, vertical = D.s1),
                )
            },
        )

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有人格", style = Ty.title3, color = T.text)
                    Spacer(Modifier.height(D.s2))
                    Text("去设置里新建，或恢复内置人格", style = Ty.caption, color = T.textFaint)
                    Spacer(Modifier.height(D.s4))
                    PillButton("恢复内置人格", { vm.restoreBuiltins() })
                }
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = D.s5, vertical = D.s2),
            verticalArrangement = Arrangement.spacedBy(D.s2),
        ) {
            itemsIndexed(rows, key = { _, r -> r.persona.id }) { i, r ->
                GlassCard(
                    Modifier.fillMaxWidth().enterStagger(i),
                    onClick = { onOpenChat(r.persona.id) },
                    onLongPress = { pendingClear = r.persona.id to r.persona.name },
                ) {
                    Row(
                        Modifier.padding(horizontal = D.s4, vertical = D.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(T.accent, T.purple)))
                                .clickable { onOpenProfile(r.persona.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(r.persona.name.take(1), style = Ty.title3, color = T.onAccent)
                        }
                        Spacer(Modifier.width(D.s3))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.persona.name, style = Ty.bodySm, color = T.text,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                r.lastText.ifBlank { "还没有聊过" },
                                style = Ty.caption, color = T.textFaint,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(D.s2))
                        Text(shortTime(r.lastTs), style = Ty.caption, color = T.textFaint)
                    }
                }
            }
            item("tail") { Spacer(Modifier.height(110.dp)) }
        }
    }

    pendingClear?.let { (pid, name) ->
        Sheet({ pendingClear = null }) {
            Text("清空会话", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s2))
            Text("把「$name」的聊天记录清掉，记忆库不受影响。", style = Ty.bodySm, color = T.textDim)
            Spacer(Modifier.height(D.s4))
            Row(horizontalArrangement = Arrangement.spacedBy(D.s2)) {
                PillButton("取消", { pendingClear = null }, Modifier.weight(1f), primary = false)
                PillButton("清空", { vm.clearChat(pid); pendingClear = null }, Modifier.weight(1f), danger = true)
            }
        }
    }
}

private fun shortTime(ts: Long): String {
    if (ts <= 0L) return ""
    val now = Calendar.getInstance()
    val that = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == that.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))
        now.get(Calendar.YEAR) == that.get(Calendar.YEAR) -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ts))
        else -> SimpleDateFormat("yyyy/M/d", Locale.CHINA).format(Date(ts))
    }
}
