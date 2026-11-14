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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.zhiyi.AppVm
import app.zhiyi.core.ChatCore
import app.zhiyi.data.CleanStore
import app.zhiyi.data.PersonaUsage
import app.zhiyi.data.StickerStore
import app.zhiyi.data.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 清理空间。
 *
 * 原来「清缓存」和「清表情」散在设置页，删记忆要去记忆页、删声音要去声音设置、
 * 删立绘要去桌宠设置——想腾点地方得跑四个地方。这里全部收拢成一页，
 * 并且**按人格分开算账**：大多数时候人只想清一个人的，不想全清。
 *
 * 三条线：
 * 1. 顶部看得见每一项占了多少（不看数字就不知道从哪下手）
 * 2. 安全项一键清（缓存 / 表情 / 孤儿图片）
 * 3. 每个人格一张卡：记忆、聊天、声音、立绘，各自单独删
 */
@Composable
fun CleanScreen(vm: AppVm, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ChatCore.db(ctx) }

    var usage by remember { mutableStateOf<List<Usage>>(emptyList()) }
    var people by remember { mutableStateOf<List<PersonaUsage>>(emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    // 确认框：文案 + 动作
    var confirmText by remember { mutableStateOf<String?>(null) }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun reload() { tick++ }

    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) {
            val us = CleanStore.overview(ctx, db)
            val ps = runCatching { db.personas() }.getOrDefault(emptyList())
                .map { CleanStore.personaUsage(ctx, db, it) }
            us to ps
        }
        usage = r.first
        people = r.second
    }

    fun ask(text: String, action: () -> Unit) {
        confirmText = text
        confirmAction = action
    }

    fun run(job: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { job() }.getOrElse { "操作失败：${it.message}" } }
            busy = false
            vm.toast = msg
            reload()
        }
    }

    val total = usage.sumOf { it.bytes }

    Column(Modifier.fillMaxSize().background(T.bg).statusBarsPadding()) {
        GlassTopBar(
            title = "清理空间",
            subtitle = "一共 ${CleanStore.human(total)}",
            onBack = onBack,
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.s5, end = D.s5, bottom = D.s6),
            verticalArrangement = Arrangement.spacedBy(D.s3),
        ) {
            // ---------- 占用总览 ----------
            item("usage") {
                Column {
                    Label("占用", Modifier.padding(bottom = D.s2))
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = D.s1)) {
                            usage.forEachIndexed { i, u ->
                                if (i > 0) InnerDivider()
                                UsageRow(u, max = usage.maxOfOrNull { it.bytes } ?: 1L)
                            }
                        }
                    }
                }
            }

            // ---------- 能安全清的 ----------
            item("quick") {
                Column {
                    Label("随手清", Modifier.padding(bottom = D.s2))
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = D.s1)) {
                            SettingRow(
                                "临时缓存",
                                "截图、合成音频、试听残留。清了不影响任何记忆和设置",
                                value = "清理", valueAsButton = true,
                                onClick = {
                                    run {
                                        var freed = 0L
                                        ctx.cacheDir.listFiles()?.forEach { f ->
                                            freed += CleanStore.dirBytes(f)
                                            runCatching { f.deleteRecursively() }
                                        }
                                        "已清理 ${CleanStore.human(freed)}"
                                    }
                                },
                            )
                            InnerDivider()
                            SettingRow(
                                "表情包",
                                "聊天里自动抓的那些图。清掉不影响文字",
                                danger = true,
                                onClick = {
                                    ask("清空全部表情包？聊天记录和记忆不受影响。") {
                                        run {
                                            val before = CleanStore.dirBytes(StickerStore.dir(ctx))
                                            StickerStore.clearAll(ctx)
                                            "已清空表情，释放 ${CleanStore.human(before)}"
                                        }
                                    }
                                },
                            )
                            InnerDivider()
                            SettingRow(
                                "没人用的聊天图片",
                                usage.firstOrNull { it.key == "image" }?.detail ?: "—",
                                danger = true,
                                onClick = {
                                    run {
                                        val freed = CleanStore.clearOrphanImages(ctx, db)
                                        if (freed == 0L) "没有可清理的图片" else "释放 ${CleanStore.human(freed)}"
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(D.s1))
                    Text(
                        "这里的三项都不碰人格、记忆和聊天文字——想删那些，往下走。",
                        style = Ty.caption, color = T.textFaint,
                        modifier = Modifier.padding(horizontal = D.s1),
                    )
                }
            }

            // ---------- 按人格 ----------
            item("peopleTitle") {
                Column {
                    Label("按人格清理", Modifier.padding(top = D.s2, bottom = D.s2))
                    Text(
                        "每个人格的记忆、聊天、声音、立绘是分开存的，可以只删其中一个。",
                        style = Ty.caption, color = T.textFaint,
                        modifier = Modifier.padding(horizontal = D.s1, vertical = 2.dp),
                    )
                }
            }

            if (people.isEmpty()) {
                item("nobody") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            "还没有人格。",
                            style = Ty.bodySm, color = T.textFaint,
                            modifier = Modifier.padding(D.s4),
                        )
                    }
                }
            }

            people.forEach { u ->
                item("p:${u.pid}") {
                    PersonaCleanCard(
                        u = u,
                        open = expanded == u.pid,
                        busy = busy,
                        onToggle = { expanded = if (expanded == u.pid) null else u.pid },
                        onClearMemory = {
                            ask("删除「${u.name}」的全部记忆？\\n\\n" +
                                "事实 ${u.facts} 条、往事 ${u.episodes} 条、摘要 ${u.summaries} 条。\\n" +
                                "聊天记录和人格设定都还在，只是她不再记得发生过什么。删了不能恢复。") {
                                run {
                                    CleanStore.clearMemory(db, u.pid)
                                    "已清空「${u.name}」的记忆"
                                }
                            }
                        },
                        onClearChat = {
                            ask("删除「${u.name}」的聊天记录？\\n\\n" +
                                "${u.msgs} 条消息会全部消失，人格和记忆不动。删了不能恢复。") {
                                run {
                                    CleanStore.clearChat(db, u.pid)
                                    "已清空「${u.name}」的聊天记录"
                                }
                            }
                        },
                        onClearVoice = {
                            ask("删除「${u.name}」的声音？\\n\\n" +
                                "上传的人声样本会删掉，之后她会用自己的默认发声。") {
                                run {
                                    CleanStore.clearVoice(ctx, u.pid)
                                    "已删除「${u.name}」的声音"
                                }
                            }
                        },
                        onClearSkin = {
                            ask("删除「${u.name}」名下的桌宠立绘？\\n\\n" +
                                "导入的形象图会删掉，桌宠回到内置那套。内置皮肤删不掉。") {
                                run {
                                    val n = CleanStore.clearSkins(ctx, u.pid)
                                    if (n == 0) "没有可删的立绘" else "已删除 $n 套立绘"
                                }
                            }
                        },
                        onDeleteAll = {
                            ask("删除「${u.name}」这个人格？\\n\\n" +
                                "人格设定、聊天记录、全部记忆、声音、立绘会一起删掉，和从设置里删人格一样。\\n" +
                                "这个操作不可恢复。") {
                                run {
                                    CleanStore.clearPersona(ctx, db, u.pid)
                                    expanded = null
                                    "已删除「${u.name}」"
                                }
                            }
                        },
                    )
                }
            }

            item("foot") {
                Text(
                    "删除都是立刻生效、不能撤销的。担心的话，先到设置里导出一份备份再清。",
                    style = Ty.caption, color = T.textFaint,
                    modifier = Modifier.padding(horizontal = D.s1, vertical = D.s2),
                )
            }
        }
    }

    // 确认框
    val cText = confirmText
    if (cText != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmText = null; confirmAction = null },
            title = { Text("确认", style = Ty.title3, color = T.text) },
            text = { Text(cText, style = Ty.bodySm, color = T.text) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val act = confirmAction
                    confirmText = null
                    confirmAction = null
                    act?.invoke()
                }) { Text("删除", color = T.danger) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmText = null; confirmAction = null
                }) { Text("算了", color = T.textFaint) }
            },
        )
    }
}

/** 一行占用：名字 + 说明 + 大小 + 细条 */
@Composable
private fun UsageRow(u: Usage, max: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = D.s4, vertical = D.s3), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(u.label, style = Ty.body, color = T.text)
            Text(u.detail, style = Ty.caption, color = T.textFaint)
            Spacer(Modifier.height(6.dp))
            val frac = if (max <= 0) 0f else (u.bytes.toFloat() / max).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth(0.72f).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(T.hi2)
            ) {
                Box(
                    Modifier.fillMaxWidth(frac).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(T.accent.copy(alpha = 0.55f))
                )
            }
        }
        Spacer(Modifier.width(D.s3))
        Text(CleanStore.human(u.bytes), style = Ty.caption, color = T.textFaint)
    }
}

@Composable
private fun PersonaCleanCard(
    u: PersonaUsage,
    open: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onClearMemory: () -> Unit,
    onClearChat: () -> Unit,
    onClearVoice: () -> Unit,
    onClearSkin: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }.padding(D.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(u.name, style = Ty.title3, color = T.text)
                    Text(
                        listOfNotNull(
                            "记忆 ${u.memoryCount}".takeIf { u.memoryCount > 0 },
                            "聊天 ${u.msgs} 条".takeIf { u.msgs > 0 },
                            u.voiceDetail.takeIf { u.hasVoice },
                            "立绘 ${u.skins.size} 套".takeIf { u.skins.isNotEmpty() },
                        ).joinToString(" · ").ifBlank { "空的，没有可清的" },
                        style = Ty.caption, color = T.textFaint,
                    )
                }
                Text(if (open) "收起" else "展开", style = Ty.caption, color = T.accent)
            }
            if (open) {
                InnerDivider()
                SettingRow(
                    "记忆",
                    "事实 ${u.facts} · 往事 ${u.episodes} · 摘要 ${u.summaries}",
                    value = "删除", valueAsButton = true, danger = true,
                    onClick = onClearMemory,
                )
                InnerDivider()
                SettingRow(
                    "聊天记录", "${u.msgs} 条消息",
                    value = "删除", valueAsButton = true, danger = true,
                    onClick = onClearChat,
                )
                InnerDivider()
                SettingRow(
                    "声音", u.voiceDetail,
                    value = "删除", valueAsButton = true, danger = true,
                    onClick = onClearVoice,
                )
                InnerDivider()
                SettingRow(
                    "桌宠立绘",
                    if (u.skins.isEmpty()) "没有导入的形象" else u.skins.joinToString("、"),
                    value = "删除", valueAsButton = true, danger = true,
                    onClick = onClearSkin,
                )
                InnerDivider()
                Box(Modifier.padding(D.s3)) {
                    PillButton(
                        "删除这个人格", onDeleteAll, Modifier.fillMaxWidth(),
                        primary = false, danger = true,
                    )
                }
            }
        }
    }
}
