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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zhiyi.AppVm
import app.zhiyi.data.LoreEntry
import app.zhiyi.data.World

/**
 * 世界观。
 * 一份设定集 = 一个"文件夹"，可以被人格共用、可以整体切换、可以整包分享。
 */
@Composable
fun WorldScreen(vm: AppVm, pid: String, onBack: () -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    var showNewWorld by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editingIdx by remember { mutableStateOf(-1) }
    var newName by remember { mutableStateOf("") }

    val world = vm.currentWorld
    val entries = world?.entries ?: emptyList()

    fun applyEntries(list: List<LoreEntry>) {
        vm.currentWorld?.let { vm.saveWorld(it.copy(entries = list)) }
    }

    Column(Modifier.fillMaxSize().background(T.bg).statusBarsPadding()) {
        GlassTopBar(
            title = "世界观",
            subtitle = if (world == null) "还没有绑定" else "${world.name} · ${entries.size} 条设定",
            onBack = onBack,
            trailing = {
                if (world != null) {
                    Text(
                        "分享", style = Ty.caption, color = T.accent,
                        modifier = Modifier.clickable { vm.shareWorld(world) }.padding(D.s2),
                    )
                }
            },
        )

        // 当前世界观
        Box(Modifier.padding(horizontal = D.s5, vertical = D.s2)) {
            GlassCard(Modifier.fillMaxWidth(), onClick = { showPicker = true }) {
                Row(Modifier.padding(D.s4), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(T.accentTint), Alignment.Center) {
                        Text("界", style = Ty.bodySm, color = T.accent)
                    }
                    Spacer(Modifier.width(D.s3))
                    Column(Modifier.weight(1f)) {
                        Text(world?.name ?: "未绑定世界观", style = Ty.bodySm, color = T.text)
                        Text(
                            world?.desc?.ifBlank { "${entries.size} 条设定" } ?: "点这里选一个，或者新建",
                            style = Ty.caption, color = T.textFaint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("切换", style = Ty.caption, color = T.accent)
                }
            }
        }

        if (world == null) {
            Box(Modifier.weight(1f).padding(D.s5), contentAlignment = Alignment.Center) {
                Text(
                    "世界观写的是「世界怎么运转」——环境、规则、称呼、共同经历。\n" +
                        "只有对话里出现关键词时才注入，不吃固定预算。\n" +
                        "同一份世界观可以给多个人格共用。",
                    style = Ty.caption, color = T.textFaint,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = D.s5, vertical = D.s2),
                verticalArrangement = Arrangement.spacedBy(D.s2),
            ) {
                if (entries.isEmpty()) {
                    item("empty") {
                        Box(Modifier.fillMaxWidth().padding(vertical = D.s8), contentAlignment = Alignment.Center) {
                            Text("还没有条目，点下面新增", style = Ty.caption, color = T.textFaint)
                        }
                    }
                }
                itemsIndexed(entries, key = { i, _ -> "e$i" }) { i, e ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(D.s4)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (e.constant) {
                                    Box(
                                        Modifier.clip(CircleShape).background(T.accentTint)
                                            .padding(horizontal = D.s2, vertical = 2.dp)
                                    ) { Label("常驻", color = T.accent) }
                                    Spacer(Modifier.width(D.s2))
                                }
                                Text(
                                    if (e.keys.isEmpty()) "无关键词" else e.keys.joinToString(" / "),
                                    style = Ty.bodySm, color = T.text,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("改", style = Ty.caption, color = T.textDim,
                                    modifier = Modifier.clickable { editingIdx = i; showEditor = true }.padding(D.s2))
                                Text("×", style = Ty.caption, color = T.danger,
                                    modifier = Modifier.clickable {
                                        applyEntries(entries.toMutableList().also { it.removeAt(i) })
                                    }.padding(D.s2))
                            }
                            Spacer(Modifier.height(D.s1))
                            Text(e.content, style = Ty.caption, color = T.textDim,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item("tail") { Spacer(Modifier.height(20.dp)) }
            }
        }

        Box(Modifier.padding(D.s5).navigationBarsPadding()) {
            PillButton(
                if (world == null) "新建世界观" else "新增设定",
                {
                    if (world == null) { newName = ""; showNewWorld = true }
                    else { editingIdx = -1; showEditor = true }
                },
                Modifier.fillMaxWidth(),
            )
        }
    }

    // ---------- 切换世界观 ----------
    if (showPicker) {
        Sheet({ showPicker = false }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择世界观", style = Ty.title2, color = T.text, modifier = Modifier.weight(1f))
                Text("新建", style = Ty.bodySm, color = T.accent,
                    modifier = Modifier.clickable { showPicker = false; newName = ""; showNewWorld = true }.padding(D.s2))
            }
            Spacer(Modifier.height(D.s3))
            if (vm.worlds.isEmpty()) {
                Text("还没有世界观", style = Ty.caption, color = T.textFaint)
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(vm.worlds, key = { it.id }) { w ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.bindWorld(w.id); showPicker = false }
                            .padding(vertical = D.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(CircleShape)
                                .background(if (w.id == world?.id) T.accent else T.track)
                        )
                        Spacer(Modifier.width(D.s3))
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = Ty.bodySm, color = T.text, maxLines = 1)
                            Text("${w.entries.size} 条", style = Ty.caption, color = T.textFaint)
                        }
                        Text("删", style = Ty.caption, color = T.danger,
                            modifier = Modifier.clickable { vm.deleteWorld(w.id) }.padding(D.s2))
                    }
                    InnerDivider(start = 0.dp)
                }
                item("none") {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.bindWorld(""); showPicker = false }
                            .padding(vertical = D.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text("不绑定（只用人格自带设定）", style = Ty.caption, color = T.textDim) }
                }
            }
        }
    }

    // ---------- 新建世界观 ----------
    if (showNewWorld) {
        Sheet({ showNewWorld = false }) {
            Text("新建世界观", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s3))
            Field("名字", newName, { newName = it }, "例如：临海旧城区")
            Spacer(Modifier.height(D.s3))
            Text("建好后会自动绑定到当前人格，也可以给别人格共用。", style = Ty.caption, color = T.textFaint)
            Spacer(Modifier.height(D.s4))
            PillButton("创建并绑定", {
                val w = World(id = vm.newWorldId(), name = newName.ifBlank { "新世界观" })
                vm.saveWorld(w)
                vm.bindWorld(w.id)
                showNewWorld = false
            }, Modifier.fillMaxWidth())
        }
    }

    // ---------- 条目编辑 ----------
    if (showEditor) {
        val init = if (editingIdx in entries.indices) entries[editingIdx] else LoreEntry()
        LoreEditor(init, onClose = { showEditor = false }) { entry ->
            val list = entries.toMutableList()
            if (editingIdx in list.indices) list[editingIdx] = entry else list.add(entry)
            applyEntries(list)
            showEditor = false
        }
    }
}

@Composable
private fun LoreEditor(init: LoreEntry, onClose: () -> Unit, onSave: (LoreEntry) -> Unit) {
    var keys by remember { mutableStateOf(init.keys.joinToString("、")) }
    var sec by remember { mutableStateOf(init.secondaryKeys.joinToString("、")) }
    var content by remember { mutableStateOf(init.content) }
    var constant by remember { mutableStateOf(init.constant) }

    Sheet(onClose) {
        Text("设定条目", style = Ty.title2, color = T.text)
        Spacer(Modifier.height(D.s3))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Field("触发关键词（顿号分隔）", keys, { keys = it }, "海边、旧城区")
            Spacer(Modifier.height(D.s3))
            Field("次要关键词（可选，需同时命中）", sec, { sec = it }, "下雨")
            Spacer(Modifier.height(D.s3))
            Field("内容", content, { content = it }, "这个世界里的规则、地点、关系", minLines = 6)
            Spacer(Modifier.height(D.s3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("常驻注入", style = Ty.bodySm, color = T.text)
                    Text("不看关键词，每轮都带上（占预算，少用）", style = Ty.caption, color = T.textFaint)
                }
                Toggle(constant, { constant = it })
            }
        }
        Spacer(Modifier.height(D.s4))
        PillButton("保存", {
            onSave(
                LoreEntry(
                    keys = keys.split("、", ",", "，").map { it.trim() }.filter { it.isNotBlank() },
                    content = content.trim(),
                    secondaryKeys = sec.split("、", ",", "，").map { it.trim() }.filter { it.isNotBlank() },
                    constant = constant,
                    caseSensitive = false,
                )
            )
        }, Modifier.fillMaxWidth(), enabled = content.isNotBlank())
    }
}
