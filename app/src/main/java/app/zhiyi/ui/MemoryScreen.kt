package app.zhiyi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zhiyi.AppVm
import app.zhiyi.data.Msg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryScreen(vm: AppVm) {
    var tab by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var viewingSrc by remember { mutableStateOf<String?>(null) }
    var editingFact by remember { mutableStateOf<app.zhiyi.data.Fact?>(null) }
    val diaries = vm.diaries
    val listState = rememberLazyListState()
    val viewing = vm.memPid.ifBlank { vm.persona?.id ?: "" }
    val viewingName = vm.personas.firstOrNull { it.id == viewing }?.name ?: "她"
    val facts = vm.facts
    val eps = vm.episodes
    val sums = vm.summaries
    val groupedFacts = remember(facts) { facts.groupBy { it.predicate } }
    // 已播过入场动画的条目：只在「换人格」时重置，切 tab 不重播。
    // 原来 key 里带了 tab，每切一次整页条目都重新错峰浮现，看着像一条条"数"出来（用户原话）。
    // 键带类型前缀——事实/回忆/摘要的 id 来自不同的表，会撞号。
    val shownIds = remember(viewing) { androidx.compose.runtime.mutableStateListOf<String>() }
    val shownTitles = remember(viewing) { androidx.compose.runtime.mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize()) {
        GlassTopBar(
            title = "$viewingName 的记忆",
            subtitle = "事实 / 回忆 / 摘要，都存在这台手机上",
            titleContent = {
                // 名字换人时上下滑走；「的记忆」自己不参与滑动，只跟着名字宽度左右让位
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = viewingName,
                        transitionSpec = {
                            (
                                androidx.compose.animation.slideInVertically(
                                    androidx.compose.animation.core.tween(300, easing = app.zhiyi.ui.Emphasized),
                                ) { it } + androidx.compose.animation.fadeIn(
                                    androidx.compose.animation.core.tween(220),
                                )
                                ).togetherWith(
                                androidx.compose.animation.slideOutVertically(
                                    androidx.compose.animation.core.tween(240, easing = app.zhiyi.ui.EmphasizedAccel),
                                ) { -it } + androidx.compose.animation.fadeOut(
                                    androidx.compose.animation.core.tween(160),
                                )
                                ).using(androidx.compose.animation.SizeTransform(clip = false))
                        },
                        label = "memTitleName",
                    ) { n -> Text(n, style = Ty.title2, color = T.text, maxLines = 1) }
                    Text("的记忆", style = Ty.title2, color = T.text, maxLines = 1)
                }
            },
            trailing = {
                if (vm.personas.isNotEmpty()) {
                    Box(
                        Modifier.size(D.touch).clip(CircleShape)
                            .clickable { confirmClear = true },
                        contentAlignment = Alignment.Center,
                    ) { GlyphTrash(T.danger) }
                }
            },
        )

        // 人格选择器：最上方选查看哪个人格
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = D.s5),
            horizontalArrangement = Arrangement.spacedBy(D.s2),
        ) {
            items(vm.personas, key = { it.id }) { ps ->
                val on = ps.id == viewing
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                // 切换人格时 chip 高亮平滑过渡（不用离屏 alpha，不卡）
                val chipBg by animateColorAsState(
                    if (on) T.accent else T.hi1,
                    androidx.compose.animation.core.tween(240, easing = app.zhiyi.ui.Emphasized), label = "chipBg",
                )
                val chipFg by animateColorAsState(
                    if (on) T.onAccent else T.textDim,
                    androidx.compose.animation.core.tween(240, easing = app.zhiyi.ui.Emphasized), label = "chipFg",
                )
                Box(
                    Modifier.clip(CircleShape)
                        .background(chipBg)
                        .clickable(src, indication = null) { vm.viewMemory(ps.id) }
                        .pressScale(src, 0.96f)
                        .padding(horizontal = D.s4, vertical = D.s2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ps.name, style = Ty.bodySm, color = chipFg)
                }
            }
        }
        Spacer(Modifier.height(D.s3))

        // 概览
        Row(
            Modifier.fillMaxWidth().padding(horizontal = D.s5),
            horizontalArrangement = Arrangement.spacedBy(D.s3),
        ) {
            StatCard("事实", facts.size, Modifier.weight(1f))
            StatCard("回忆", eps.size, Modifier.weight(1f))
            StatCard("摘要", sums.size, Modifier.weight(1f))
        }

        Spacer(Modifier.height(D.s4))
        Box(Modifier.padding(horizontal = D.s5)) {
            Segmented(listOf("事实", "回忆", "摘要", "日记"), tab, { tab = it })
        }
        Spacer(Modifier.height(D.s3))

        // 切换人格 / 切 tab 时自动回到最顶端（记忆是"翻阅档案"，不是"追最新"）
        LaunchedEffect(viewing, tab) { listState.scrollToItem(0) }
        // 注意：这里不能对 LazyColumn 做 graphicsLayer alpha 淡入（会强制整页离屏渲染，列表越大越卡）
        // 事实 tab 滚动到底时加载更多（分页，避免一次渲染全量）
        val shouldLoadMore by androidx.compose.runtime.remember {
            androidx.compose.runtime.derivedStateOf {
                tab == 0 && !vm.factsEnded &&
                    (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                    listState.layoutInfo.totalItemsCount - 6
            }
        }
        LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMoreFacts() }
        // 必须是 weight(1f)：Column 里的 fillMaxSize 会被上面的固定卡片挤成 0 高度，
        // 结果整页翻不动（踩过一次，顶部一加卡片列表就死）
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = D.s5, vertical = D.s1),
            verticalArrangement = Arrangement.spacedBy(D.s2),
        ) {
            when (tab) {
                0 -> {
                    if (facts.isEmpty()) item("e0") { Empty("还没有抽取到事实。聊几轮就会自己长出来。") }
                    // 按关系分组。标题和小项用同一个全局递增 index，保证整页逐条浮现
                    var acc = 0
                    groupedFacts.forEach { (pred, list) ->
                        val titleIdx = acc
                        val base = acc + 1
                        item("g_$pred") {
                            val titleAnim = pred !in shownTitles
                            if (titleAnim) LaunchedEffect(Unit) { shownTitles.add(pred) }
                            Text(
                                "$pred · ${list.size}",
                                style = Ty.caption, color = T.textDim,
                                modifier = Modifier.padding(start = D.s2, top = D.s2).staggerIn(titleAnim, titleIdx),
                            )
                        }
                        itemsIndexed(list, key = { _, f -> f.id }) { idx, f ->
                            val anim = "f${f.id}" !in shownIds
                            if (anim) LaunchedEffect(f.id) { shownIds.add("f${f.id}") }
                            GlassCard(Modifier.fillMaxWidth().staggerIn(anim, base + idx), onClick = { if (f.src.isNotBlank()) viewingSrc = f.src }) {
                                Row(Modifier.padding(D.s4), verticalAlignment = Alignment.CenterVertically) {
                                    Dot(f.importance)
                                    Spacer(Modifier.width(D.s3))
                                    Column(Modifier.weight(1f)) {
                                        Text(f.obj, style = Ty.bodySm, color = T.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        // 重要度 + 清晰度：前者是"当初多重要"，后者是"现在还记不记得清"。
                                        // 两个数分开显示，是因为它们会走散——很重的事隔久了也会变成"好像有这么回事"。
                                        val cl = f.clarity()
                                        Text(
                                            "重要度 ${"%.2f".format(f.importance)} · 清晰度 ${(cl * 100).toInt()}% · 命中 ${f.hits} 次 · ${ago(f.created)}",
                                            style = Ty.caption, color = if (cl < 0.35) T.textFaint else T.textDim,
                                        )
                                    }
                                    DelButton { vm.deleteFact(f.id) }
                                    EditButton { editingFact = f }
                                }
                            }
                        }
                        acc += 1 + list.size
                    }
                }
                1 -> {
                    if (eps.isEmpty()) item("e1") { Empty("还没有形成事件记忆。每聊够一轮就会压出一条。") }
                    itemsIndexed(eps, key = { _, e -> e.id }) { idx, e ->
                        val anim = "e${e.id}" !in shownIds
                        if (anim) LaunchedEffect(e.id) { shownIds.add("e${e.id}") }
                        GlassCard(Modifier.fillMaxWidth().staggerIn(anim, idx), onClick = { if (e.src.isNotBlank()) viewingSrc = e.src }) {
                            Column(Modifier.padding(D.s4)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Dot(e.importance)
                                    Spacer(Modifier.width(D.s3))
                                    Text(
                                        e.title.ifBlank { "一段往事" }, style = Ty.title3, color = T.text,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                    )
                                    DelButton { vm.deleteEpisode(e.id) }
                                }
                                Spacer(Modifier.height(D.s1))
                                Text(e.summary, style = Ty.bodySm, color = T.textDim, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(D.s2))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { ProgressBar(e.importance.toFloat(), height = 3.dp) }
                                    Spacer(Modifier.width(D.s2))
                                    Text(ago(e.ts), style = Ty.caption, color = T.textFaint)
                                }
                            }
                        }
                    }
                }
                2 -> {
                    if (sums.isEmpty()) item("e2") { Empty("还没压缩过。聊满 20 条会自动生成第一份长期摘要。") }
                    itemsIndexed(sums, key = { _, s -> s.id }) { idx, s ->
                        val anim = "s${s.id}" !in shownIds
                        if (anim) LaunchedEffect(s.id) { shownIds.add("s${s.id}") }
                        GlassCard(Modifier.fillMaxWidth().staggerIn(anim, idx)) {
                            Column(Modifier.padding(D.s4)) {
                                Label("L${s.level} · 截至第 ${s.upto} 条")
                                Spacer(Modifier.height(D.s2))
                                Text(s.content, style = Ty.bodySm, color = T.textDim)
                            }
                        }
                    }
                }
                else -> {
                    if (diaries.isEmpty()) item("e3") { Empty("她还没写过日记。每天第一次心跳时，她会以自己视角写一篇。") }
                    items(diaries, key = { it.first }) { (id, date, content) ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(D.s4)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("她的日记", style = Ty.title3, color = T.text, modifier = Modifier.weight(1f))
                                    Text(date, style = Ty.caption, color = T.textFaint)
                                }
                                Spacer(Modifier.height(D.s2))
                                Text(content, style = Ty.bodySm, color = T.textDim)
                            }
                        }
                    }
                }
            }
            item("tail") { Spacer(Modifier.height(110.dp)) }
        }
    }

    viewingSrc?.let { src ->
        // 异步取来源消息，不卡主线程
        val msgs by androidx.compose.runtime.produceState<List<Msg>>(emptyList(), src) {
            value = runCatching { vm.sourcesFor(src) }.getOrDefault(emptyList())
        }
        Sheet({ viewingSrc = null }) {
            Text("来自这些对话", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s2))
            Text("这条记忆是从下面这些话里长出来的", style = Ty.caption, color = T.textFaint)
            Spacer(Modifier.height(D.s3))
            if (msgs.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypingDots(T.accent); Spacer(Modifier.width(D.s2))
                    Text("读取来源…", style = Ty.caption, color = T.textFaint)
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(msgs, key = { it.id }) { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = D.s2)) {
                            Text(
                                if (m.role == "user") "你" else (vm.personas.firstOrNull { it.id == viewing }?.name ?: "她"),
                                style = Ty.caption, color = if (m.role == "user") T.accent else T.textDim,
                            )
                            Spacer(Modifier.width(D.s2))
                            Text(m.content, style = Ty.bodySm, color = T.textDim)
                        }
                    }
                }
            }
            Spacer(Modifier.height(D.s3))
            PillButton("关闭", { viewingSrc = null }, Modifier.fillMaxWidth(), primary = false)
        }
    }

    editingFact?.let { f ->
        var subj by remember(f.id) { mutableStateOf(f.subject) }
        var pred by remember(f.id) { mutableStateOf(f.predicate) }
        var obj by remember(f.id) { mutableStateOf(f.obj) }
        var imp by remember(f.id) { mutableStateOf(f.importance) }
        Sheet({ editingFact = null }) {
            Text("纠正这条记忆", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s2))
            Text("改了之后，下次她拼上下文就用新值", style = Ty.caption, color = T.textFaint)
            Spacer(Modifier.height(D.s3))
            Field("主语", subj, { subj = it })
            Spacer(Modifier.height(D.s2))
            Field("关系", pred, { pred = it }, "比如：职业 / 喜欢 / 养了一只…")
            Spacer(Modifier.height(D.s2))
            Field("内容", obj, { obj = it })
            Spacer(Modifier.height(D.s3))
            Text("重要度", style = Ty.caption, color = T.textFaint)
            Spacer(Modifier.height(D.s2))
            Segmented(
                listOf("低", "中", "高"),
                when { imp < 0.4 -> 0; imp < 0.7 -> 1; else -> 2 },
                { i -> imp = listOf(0.3, 0.6, 0.9)[i].toDouble() },
            )
            Spacer(Modifier.height(D.s4))
            PillButton("保存", {
                vm.editFact(f.id, subj, pred, obj, imp)
                editingFact = null
            }, Modifier.fillMaxWidth(), enabled = pred.isNotBlank() && obj.isNotBlank())
        }
    }

    if (confirmClear) {
        Sheet({ confirmClear = false }) {
            Text("清空记忆库", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s2))
            Text(
                "把「$viewingName」的事实、回忆、摘要全删掉，聊天记录保留。这个操作不可撤销。",
                style = Ty.bodySm, color = T.textDim,
            )
            Spacer(Modifier.height(D.s4))
            Row(horizontalArrangement = Arrangement.spacedBy(D.s2)) {
                PillButton("取消", { confirmClear = false }, Modifier.weight(1f), primary = false)
                PillButton("清空", {
                    vm.clearMemory(vm.memPid.ifBlank { vm.persona?.id ?: "" })
                    confirmClear = false
                }, Modifier.weight(1f), danger = true)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(D.s4)) {
            // 换人格时数字滚过去，不是硬切（三张卡同时变，硬切像刷新，滚动像「换了一个人」）
            AnimatedCount(value, style = Ty.title1, modifier = Modifier.pulseOn(value))
            Label(label)
        }
    }
}

@Composable
private fun Dot(importance: Double) {
    val c = when {
        importance >= 0.75 -> T.accent
        importance >= 0.5 -> T.ok
        else -> T.textFaint
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
}

@Composable
private fun EditButton(onClick: () -> Unit) {
    Box(
        Modifier.size(D.touch).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("✎", style = Ty.bodySm, color = T.textDim) }
}

@Composable
private fun DelButton(onClick: () -> Unit) {
    Box(
        Modifier.size(D.touch).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphTrash(T.textFaint, 16.dp) }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = D.s10), contentAlignment = Alignment.Center) {
        Text(text, style = Ty.bodySm, color = T.textFaint)
    }
}

fun ago(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    val m = d / 60000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "${m}分钟前"
        m < 1440 -> "${m / 60}小时前"
        m < 43200 -> "${m / 1440}天前"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ts))
    }
}
