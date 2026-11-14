package app.zhiyi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zhiyi.AppVm

/** 她的主页：聚合展示心情、天气、生活、日记，让用户看到"一个完整的人" */
@Composable
fun ProfileScreen(vm: AppVm, onBack: () -> Unit) {
    val p = vm.persona
    if (p == null || p.id.isBlank()) return

    // 主页讲的是「当前人格」。但 memPid 可能是上次在记忆页翻到的另一个人，
    // 那样这里会显示别人的事实/回忆 —— 进页面先对齐到当前人格。
    androidx.compose.runtime.LaunchedEffect(p.id) { vm.viewMemory(p.id) }

    LazyColumn(
        Modifier.fillMaxSize().background(T.bg).statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = D.s5, vertical = D.s2),
        verticalArrangement = Arrangement.spacedBy(D.s3),
    ) {
        // 顶栏
        item("top") {
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val src = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(D.touch).clip(CircleShape)
                        .clickable(src, indication = null, onClick = onBack)
                        .pressScale(src),
                    contentAlignment = Alignment.Center,
                ) { GlyphBack(T.textDim) }
                Spacer(Modifier.width(D.s1))
                Text("她的主页", style = Ty.title2, color = T.text)
            }
        }

        // 头部：头像 + 名字 + 状态
        item("head") {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(D.s4), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(T.accent, T.purple))),
                        contentAlignment = Alignment.Center,
                    ) { Text(p.name.take(1), style = Ty.title1, color = T.onAccent) }
                    Spacer(Modifier.width(D.s3))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = Ty.title2, color = T.text, maxLines = 1)
                        if (p.role.isNotBlank())
                            Text("是对方的${p.role}", style = Ty.caption, color = T.textDim, maxLines = 1)
                        if (vm.statusLine.isNotBlank())
                            Text(vm.statusLine, style = Ty.caption, color = T.textFaint, maxLines = 1)
                        if (vm.daysTogether > 0)
                            Text("相识第 ${vm.daysTogether} 天", style = Ty.caption, color = T.textFaint)
                        if (p.birthday.isNotBlank())
                            Text("生日 ${p.birthday}", style = Ty.caption, color = T.textFaint)
                    }
                }
            }
        }

        // 她的心：九维雷达图（从记忆页搬过来的，那里是翻阅档案的地方，放图表碍事）
        item("heart") {
            val hAxes = remember(p.id, vm.facts.size, vm.episodes.size) {
                app.zhiyi.memory.Heart.axes(vm.db, p.id)
            }
            val hSt = remember(p.id, vm.facts.size, vm.episodes.size) {
                app.zhiyi.memory.Heart.load(vm.db, p.id)
            }
            HeartCard(
                axes = hAxes,
                mood = app.zhiyi.memory.Heart.moodLabel(hSt),
                tendency = app.zhiyi.memory.Heart.tendency(hSt),
                thought = hSt.thought,
            )
        }

        // 心情走势（和记忆页同一张卡，Catmull-Rom 平滑 + 切人格时逐点 morph）
        if (vm.moodHistory.isNotEmpty()) {
            item("mood") { MoodCard(vm.moodHistory) }
        }

        // 记忆统计
        item("stats") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.s3)) {
                ProfileStat("事实", vm.facts.size, Modifier.weight(1f))
                ProfileStat("回忆", vm.episodes.size, Modifier.weight(1f))
                ProfileStat("日记", vm.diaries.size, Modifier.weight(1f))
            }
        }

        // 记忆关系图谱
        if (vm.facts.size >= 2) {
            item("graph") { WordCloud(vm.facts) }
        }

        // 聊天统计
        vm.chatStats?.takeIf { it.total > 0 }?.let { st ->
            item("chat-stats") {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(D.s4)) {
                        Text("聊天统计", style = Ty.title3, color = T.text)
                        Spacer(Modifier.height(D.s3))
                        Row(Modifier.fillMaxWidth()) {
                            StatItem("总消息", st.total.toString(), Modifier.weight(1f))
                            StatItem("聊了", "${st.activeDays} 天", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(D.s2))
                        Row(Modifier.fillMaxWidth()) {
                            StatItem("最热一天", fmtDate(st.hottestDate), Modifier.weight(1f))
                            StatItem("她说了", "${st.assistantCount} 条", Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 她的动态（朋友圈式时间轴）
        if (vm.life.isNotEmpty() || vm.diaries.isNotEmpty()) {
            item("timeline-label") { Label("她的动态", Modifier.padding(top = D.s1)) }
            itemsIndexed(vm.life, key = { i, _ -> "life$i" }) { _, l ->
                TimelineItem("", l)
            }
            itemsIndexed(vm.diaries, key = { i, _ -> "d$i" }) { _, (_, date, content) ->
                TimelineItem(fmtDate(date), content)
            }
        }

        item("spacer") { Spacer(Modifier.height(D.s4)) }
    }
}

private fun fmtDate(d: String): String {
    // 兼容 "20261101" 和 "2026-11-01" 两种格式
    val cleaned = d.replace("-", "")
    if (cleaned.length < 8) return d
    val m = cleaned.substring(4, 6).toIntOrNull() ?: return d
    val day = cleaned.substring(6, 8).toIntOrNull() ?: return d
    return "${m}月${day}日"
}

@Composable
private fun TimelineItem(time: String, content: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = D.s2)) {
        Column(Modifier.width(22.dp).padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(T.accent))
        }
        Spacer(Modifier.width(D.s2))
        GlassCard(Modifier.weight(1f)) {
            Column(Modifier.padding(horizontal = D.s3, vertical = D.s2)) {
                if (time.isNotBlank()) {
                    Text(time, style = Ty.caption, color = T.textFaint)
                    Spacer(Modifier.height(2.dp))
                }
                Text(content, style = Ty.bodySm, color = T.text)
            }
        }
    }
}

@Composable
private fun WordCloud(facts: List<app.zhiyi.data.Fact>) {
    val colors = listOf(
        Color(0xFFF06292), Color(0xFF4FC3F7), Color(0xFFFFB74D),
        Color(0xFF81C784), Color(0xFFBA68C8), Color(0xFF4DB6AC), Color(0xFFFF8A65),
    )
    val textMeasurer = rememberTextMeasurer()
    // 测量所有词，按面积降序（大的先放中心，参考 d3-cloud 螺旋放置）
    val measured = remember(facts) {
        facts.take(50).map { f ->
            val size = (9 + f.importance.coerceIn(0.1, 1.0) * 10).sp
            val color = colors[kotlin.math.abs(f.predicate.hashCode()) % colors.size]
            Triple(
                textMeasurer.measure(f.obj.take(8), style = TextStyle(fontSize = size, color = color)),
                f.obj.take(8), color,
            )
        }.sortedByDescending { it.first.size.width * it.first.size.height }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(D.s4)) {
            Text("记忆词云", style = Ty.title3, color = T.text)
            Spacer(Modifier.height(D.s2))
            Canvas(Modifier.fillMaxWidth().height(280.dp)) {
                val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = minOf(size.width, size.height) / 2f
                for ((layout, _, _) in measured) {
                    val ww = layout.size.width + 6.dp.toPx()
                    val wh = layout.size.height + 4.dp.toPx()
                    var pos: Offset? = null
                    var theta = 0.0
                    var r = 0.0
                    while (r <= maxR) {
                        theta += 0.15
                        r = 4f * theta
                        val x = cx + (r * kotlin.math.cos(theta)).toFloat() - ww / 2f
                        val y = cy + (r * kotlin.math.sin(theta)).toFloat() - wh / 2f
                        if (x < 0 || y < 0 || x + ww > size.width || y + wh > size.height) {
                            if (r > maxR) break
                            continue
                        }
                        val rect = androidx.compose.ui.geometry.Rect(x, y, x + ww, y + wh)
                        if (placed.none { it.overlaps(rect) }) {
                            placed.add(rect)
                            pos = Offset(x, y)
                            break
                        }
                    }
                    if (pos != null) drawText(layout, topLeft = pos)
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = D.s2), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Ty.bodySm, color = T.text)
        Text(label, style = Ty.caption, color = T.textFaint)
    }
}

@Composable
private fun ProfileStat(label: String, value: Int, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = D.s3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedCount(value, style = Ty.title2, modifier = Modifier.pulseOn(value))
            Text(label, style = Ty.caption, color = T.textFaint)
        }
    }
}
