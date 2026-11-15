package app.zhiyi.ui

import app.zhiyi.voice.MimoTts
import app.zhiyi.voice.VoiceStore
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.rotate
import app.zhiyi.AppVm
import app.zhiyi.core.ChatCore
import kotlinx.coroutines.launch
import app.zhiyi.data.Persona
import app.zhiyi.data.appendModel
import app.zhiyi.data.modelList
import app.zhiyi.data.removeModel
import app.zhiyi.proactive.PermissionHelper
import app.zhiyi.data.Provider


private fun androidx.compose.foundation.lazy.LazyListScope.collapsible(
    key: String,
    title: String,
    hint: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    item("h_$key", contentType = "header") {
        CollapsibleHeader(title, hint, open, onToggle)
    }
    if (open) content()
}

@Composable
private fun CollapsibleHeader(title: String, hint: String, open: Boolean, onToggle: () -> Unit) {
    // 箭头走 spring：松手就立刻开始转，tween 会有一小段"延迟感"
    val rot by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.72f,
            stiffness = 900f,
        ),
        label = "arrow",
    )
    // 展开的组标题稍亮一点，一眼能看出哪几组是开着的
    val titleTint by androidx.compose.animation.animateColorAsState(
        targetValue = if (open) T.text else T.textDim,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "tint",
    )
    Row(
        Modifier.fillMaxWidth()
            .clickable { onToggle() }
            .padding(top = D.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Ty.title3, color = titleTint)
            Text(hint, style = Ty.caption, color = T.textFaint)
        }
        androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height
            rotate(rot) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.36f, h * 0.18f)
                    lineTo(w * 0.68f, h * 0.50f)
                    lineTo(w * 0.36f, h * 0.82f)
                }
                drawPath(p, T.textDim, style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w * 0.12f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ))
            }
        }
    }
}

@Composable
private fun GroupTitle(title: String, hint: String) {
    Column(Modifier.padding(start = D.s4, top = D.s1)) {
        Text(title, style = Ty.title3, color = T.text)
        Text(hint, style = Ty.caption, color = T.textFaint)
    }
}

@Composable
private fun GroupRow(title: String, subtitle: String, selected: Boolean, onOpen: () -> Unit) {
    SettingRow(
        title, subtitle,
        value = if (selected) "在看" else "",
        onClick = onOpen,
    )
}

@Composable
fun SettingsScreen(vm: AppVm, onOpenWorld: (String) -> Unit = {}, onOpenClean: () -> Unit = {}, onOpenQq: () -> Unit = {}) {
    val ctx = LocalContext.current
    // 设置用折叠分组：每组一条可点的标题，展开才显示里面的项。
    // 不另做"目录"——折叠条本身就能点到，目录和内容两套东西只会让人多点一次。
    val sdb = remember { ChatCore.db(ctx) }
    var openGroups by remember {
        mutableStateOf(sdb.get("settings_open", "role").split(",").filter { it.isNotBlank() }.toSet())
    }
    fun toggleGroup(k: String) {
        openGroups = if (k in openGroups) openGroups - k else openGroups + k
        sdb.put("settings_open", openGroups.joinToString(","))
    }
    var showProvider by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Provider?>(null) }
    var showPersonaEdit by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }
    var showPersonaList by remember { mutableStateOf(false) }
    var showPasteJson by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    // 声音：Key 输入框 / 音色选择弹窗 / 改完样本后用来强制刷新那一行
    var showTtsKey by remember { mutableStateOf(false) }
    var ttsKeyDraft by remember { mutableStateOf("") }
    var showVoicePick by remember { mutableStateOf(false) }
    var voicePickPid by remember { mutableStateOf("") }
    var voiceTick by remember { mutableStateOf(0) }

    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val pid = voicePickPid
            if (pid.isBlank()) {
                vm.toast = "先选一个人格"
            } else {
                // 最大 7MB 的拷贝不能放主线程，选到大文件就是 ANR
                kotlinx.coroutines.MainScope().launch {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        VoiceStore.save(ctx, pid, uri)
                    }
                    vm.toast = if (ok) "声音样本已保存" else "存不下：超过 7MB 或这个文件读不出来"
                    voiceTick++
                }
            }
        }
    }

    val pickPng = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()?.let { vm.importCard(it) }
        }
    }
    // 一个入口吃所有格式：PNG 走读卡，其余按 JSON 自分流
    val pickAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) { vm.toast = "导入失败：读不了这个文件"; return@rememberLauncherForActivityResult }
        val isPng = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
        if (isPng) vm.importCard(bytes)
        else vm.importAny(String(bytes, Charsets.UTF_8))
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 从系统文件里挑一个 JSON（人格卡 / 世界观都认）
    val pickJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            }.getOrNull()
            if (text.isNullOrBlank()) vm.error = "这个文件读不出内容" else vm.importAny(text)
        }
    }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }

    val cfg = vm.cfg
    var showProGuide by remember { mutableStateOf(false) }
    var city by remember { mutableStateOf(vm.cfg.city) }
    var weatherNow by remember { mutableStateOf("") }
    fun fetchWeather() {
        val c = city.trim()
        if (c.isBlank()) { weatherNow = ""; return }
        weatherNow = "抓取中…"
        kotlinx.coroutines.MainScope().launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val db = app.zhiyi.data.Db.get(ctx)
                app.zhiyi.data.WeatherHelper.refresh(db, c)
                app.zhiyi.data.WeatherHelper.cached(db, c)
            }
            weatherNow = r ?: "没抓到（检查城市名或网络）"
        }
    }
    // 填完城市停 600ms 自动抓一次天气
    androidx.compose.runtime.LaunchedEffect(city) {
        if (city.isBlank()) { weatherNow = ""; return@LaunchedEffect }
        kotlinx.coroutines.delay(600)
        fetchWeather()
    }
    var stickerSrc by remember { mutableStateOf(vm.cfg.stickerSource) }
    var crawling by remember { mutableStateOf(false) }
    var crawlMsg by remember { mutableStateOf("") }
    fun crawlNow() {
        if (crawling) return
        crawling = true
        crawlMsg = ""
        kotlinx.coroutines.MainScope().launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.zhiyi.data.StickerStore.crawl(ctx, vm.cfg.stickerSource) { msg -> crawlMsg = msg }
            }
            crawling = false
            crawlMsg = when {
                n > 0 -> "抓到 $n 张"
                n == -1 -> "先填接口地址"
                n == -2 -> "接口连不上"
                n == -3 -> "接口里没找到图片链接"
                else -> "失败"
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = D.s5, vertical = D.s2),
        verticalArrangement = Arrangement.spacedBy(D.s5),
    ) {
        item("title", contentType = "title") {
            Column(Modifier.animateItem()) {
                Spacer(Modifier.height(D.s2))
                Text("设置", style = Ty.display, color = T.text)
                Text("一切都在本地，只有对话内容会发给你选的模型", style = Ty.caption, color = T.textFaint)
            }
        }

        // ---------------- API ----------------

        collapsible("role", "角色", "人格设定 · 记忆 · 主动发话", "role" in openGroups, { toggleGroup("role") }) {
        item("persona", contentType = "persona") {
            Column(Modifier.animateItem()) {
                Label("人格", Modifier.padding(start = D.s4, bottom = D.s2))

                // 每个人格一张卡：头像 + 名字 + 它绑定的世界观
                vm.personas.forEachIndexed { idx, ps ->
                    val world = remember(ps.id, vm.worlds) { vm.worlds.firstOrNull { it.id == ps.worldId } }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(D.s4), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(ps.name)
                            Spacer(Modifier.width(D.s3))
                            Column(Modifier.weight(1f)) {
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        editingPersona = ps; showPersonaEdit = true
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(ps.name, style = Ty.title3, color = T.text, modifier = Modifier.weight(1f))
                                    Text("编辑", style = Ty.caption, color = T.textDim)
                                }
                                Spacer(Modifier.height(2.dp))
                                Row(
                                    Modifier.fillMaxWidth().clickable { onOpenWorld(ps.id) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        world?.let { "${it.name} · ${it.entries.size} 条" } ?: "未绑定世界观 · 点这里设置",
                                        style = Ty.caption,
                                        color = if (world == null) T.textFaint else T.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("›", style = Ty.caption, color = T.textFaint)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(D.s2))
                }

                if (vm.personas.isEmpty()) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SettingRow("恢复内置人格", "两个人格示例，带开场白", value = "恢复", valueAsButton = true,
                            onClick = { vm.restoreBuiltins() })
                    }
                    Spacer(Modifier.height(D.s2))
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.animateItem()) {
                        SettingRow("新建人格", "不用导入，从空白开始写", value = "新建", valueAsButton = true,
                            onClick = { editingPersona = null; showPersonaEdit = true })
                        InnerDivider()
                        SettingRow(
                            "导入", "PNG 角色卡 / JSON 人格 / 世界观，自动识别", value = "选择", valueAsButton = true,
                            onClick = {
                                pickAny.launch(
                                    arrayOf("image/*", "application/json", "text/plain", "application/octet-stream", "*/*")
                                )
                            },
                        )
                        if (vm.toast != null) {
                            InnerDivider()
                            Box(Modifier.padding(horizontal = D.s4, vertical = D.s2)) {
                                Text(vm.toast ?: "", style = Ty.caption, color = T.accent)
                            }
                        }
                    }
                }
            }
        }

        item("mem", contentType = "mem") {
            Column(Modifier.animateItem()) {
                Label("记忆与上下文", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        SegmentRow("上下文预算", listOf("4K", "6K", "8K", "16K"),
                            listOf(4000, 6000, 8000, 16000).indexOf(cfg.ctxTokens).takeIf { it >= 0 } ?: 1
                        ) { i -> vm.saveCfg(cfg.copy(ctxTokens = listOf(4000, 6000, 8000, 16000)[i])) }
                        InnerDivider()
                        SegmentRow("压缩间隔", listOf("10条", "20条", "40条"),
                            listOf(10, 20, 40).indexOf(cfg.summarizeEvery).takeIf { it >= 0 } ?: 1
                        ) { i -> vm.saveCfg(cfg.copy(summarizeEvery = listOf(10, 20, 40)[i])) }
                        InnerDivider()
                        SegmentRow("抽取频率", listOf("每2轮", "每3轮", "每6轮"),
                            listOf(2, 3, 6).indexOf(cfg.factsEvery).takeIf { it >= 0 } ?: 1
                        ) { i -> vm.saveCfg(cfg.copy(factsEvery = listOf(2, 3, 6)[i])) }
                        InnerDivider()
                        SettingRow(
                            "向量召回", "填了 embedding 模型才生效",
                            trailing = {
                                Toggle(cfg.embedEnabled, { v -> vm.saveCfg(cfg.copy(embedEnabled = v)) })
                            },
                        )
                    }
                }
            }
        }

        // ---------------- 主动发话 ----------------

        item("pro", contentType = "pro") {
            Column(Modifier.animateItem()) {
                Label("主动发话", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        SettingRow(
                            "让她主动找你", if (cfg.proactiveEnabled) "每 15 分钟检查一次" else "当前关闭",
                            trailing = {
                                Toggle(cfg.proactiveEnabled, { v ->
                                    if (v && Build.VERSION.SDK_INT >= 33)
                                        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    vm.saveCfg(cfg.copy(proactiveEnabled = v))
                                    if (v) showProGuide = true
                                })
                            },
                        )
                        InnerDivider()
                        SettingRow(
                            "立即测试",
                            "跳过评分，马上让她发一条（用来确认链路通不通）",
                            trailing = {
                                androidx.compose.material3.TextButton(onClick = {
                                    vm.testProactive()
                                }) { Text("测试") }
                            },
                        )
                        if (cfg.proactiveEnabled) {
                            InnerDivider()
                            Text(
                                "上次结果：" + vm.proactiveLastHint,
                                style = Ty.caption, color = T.textFaint,
                                modifier = Modifier.padding(horizontal = D.s4, vertical = D.s2),
                            )
                        }
                        InnerDivider()
                        val qIdx = when {
                            cfg.quietStart == cfg.quietEnd -> 3
                            cfg.quietStart == 22 -> 0
                            cfg.quietStart == 23 -> 1
                            cfg.quietStart == 0 -> 2
                            else -> 1
                        }
                        SegmentRow("免打扰", listOf("22-8", "23-7", "0-9", "关"), qIdx) { i ->
                            val pairs = listOf(22 to 8, 23 to 7, 0 to 9, 0 to 0)
                            vm.saveCfg(cfg.copy(quietStart = pairs[i].first, quietEnd = pairs[i].second))
                        }
                        InnerDivider()
                        SegmentRow("每日上限", listOf("1", "2", "3", "5"),
                            listOf(1, 2, 3, 5).indexOf(cfg.dailyCap).takeIf { it >= 0 } ?: 2
                        ) { i -> vm.saveCfg(cfg.copy(dailyCap = listOf(1, 2, 3, 5)[i])) }
                        InnerDivider()
                        SegmentRow("总量上限（所有人物加起来）", listOf("3", "5", "8", "12"),
                            listOf(3, 5, 8, 12).indexOf(cfg.totalDailyCap).takeIf { it >= 0 } ?: 1
                        ) { i -> vm.saveCfg(cfg.copy(totalDailyCap = listOf(3, 5, 8, 12)[i])) }
                        InnerDivider()
                        SegmentRow("最小间隔", listOf("30分", "60分", "90分", "3时"),
                            listOf(30, 60, 90, 180).indexOf(cfg.minGapMin).takeIf { it >= 0 } ?: 2
                        ) { i -> vm.saveCfg(cfg.copy(minGapMin = listOf(30, 60, 90, 180)[i])) }
                        InnerDivider()
                    }
                }
            }
        }

                }

        collapsible("sound", "声音与表情", "TTS · 音色克隆 · 表情包", "sound" in openGroups, { toggleGroup("sound") }) {
                    item("voice", contentType = "voice") {
            Column(Modifier.animateItem()) {
                Label("她的声音", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        // 预先算好每个人格绑了什么音色，避免在 forEach 里 remember（人数一变就崩）
                        val binds = remember(vm.personas, voiceTick) {
                            vm.personas.map { it.id to VoiceStore.describe(ctx, it.id) }
                        }
                        SettingRow(
                            "MiMo API Key",
                            if (vm.cfg.ttsKey.isBlank()) "声音克隆要用，去 platform.xiaomimimo.com 拿"
                            else "已填写",
                            value = if (vm.cfg.ttsKey.isBlank()) "未设置" else "修改",
                            valueAsButton = true,
                            onClick = {
                                ttsKeyDraft = vm.cfg.ttsKey
                                showTtsKey = true
                            },
                        )
                        InnerDivider()
                        if (vm.personas.isEmpty()) {
                            Column(Modifier.padding(horizontal = D.s4, vertical = D.s3)) {
                                Text("还没有人格，先去建一个再来绑音色。", style = Ty.caption, color = T.textFaint)
                            }
                        } else {
                            vm.personas.forEachIndexed { i, ps ->
                                val d = binds.firstOrNull { it.first == ps.id }?.second ?: ""
                                SettingRow(
                                    ps.name,
                                    if (d.isBlank()) "未绑定音色" else d,
                                    value = "选择",
                                    valueAsButton = true,
                                    onClick = {
                                        voicePickPid = ps.id
                                        showVoicePick = true
                                    },
                                )
                                if (i != vm.personas.size - 1) InnerDivider()
                            }
                        }
                        InnerDivider()
                        Column(Modifier.padding(horizontal = D.s4, vertical = D.s3)) {
                            Text(
                                "绑定之后，她的消息点喇叭、或桌宠开着声音时，都会用她自己这个音色。" +
                                    "样本 5~10 秒干净人声效果最好，带背景音乐会串味，上限 7MB。",
                                style = Ty.caption, color = T.textFaint,
                            )
                        }
                    }
                }
            }
        }

        item("sticker", contentType = "sticker") {
            Column(Modifier.animateItem()) {
                Label("表情包", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        SettingRow(
                            "允许联网抓表情", "关掉后完全不联网下载表情",
                            trailing = { Toggle(cfg.stickerCrawl, { v -> vm.saveCfg(cfg.copy(stickerCrawl = v)) }) },
                        )
                        InnerDivider()
                        Column(Modifier.padding(horizontal = D.s4, vertical = D.s3)) {
                            Field(
                                "表情接口地址（返回图片 URL 的 JSON）",
                                stickerSrc,
                                { stickerSrc = it; vm.saveCfg(cfg.copy(stickerSource = it)) },
                                "https://example.com/stickers.json",
                            )
                            Spacer(Modifier.height(D.s2))
                            Row(horizontalArrangement = Arrangement.spacedBy(D.s2)) {
                                PillButton(
                                    if (crawling) "抓取中…" else "立即抓取",
                                    { crawlNow() },
                                    Modifier.weight(1f), enabled = cfg.stickerCrawl && !crawling,
                                    height = 40.dp, labelStyle = Ty.bodySm,
                                )
                                PillButton(
                                    "清空表情",
                                    { app.zhiyi.data.StickerStore.clearAll(ctx); vm.toast = "已清空表情" },
                                    Modifier.weight(1f), primary = false,
                                    height = 40.dp, labelStyle = Ty.bodySm,
                                )
                            }
                            Spacer(Modifier.height(D.s2))
                            Text(
                                "已存 ${app.zhiyi.data.StickerStore.sizeMb(ctx)}KB · ${app.zhiyi.data.StickerStore.list(ctx).size} 张" +
                                    (if (crawlMsg.isNotBlank()) " · $crawlMsg" else ""),
                                style = Ty.caption, color = T.textFaint,
                            )
                        }
                    }
                }
            }
        }

                }

        collapsible("chat", "聊天与外观", "主题 · 聊天行为", "chat" in openGroups, { toggleGroup("chat") }) {
            item("theme", contentType = "theme") {
            Column(Modifier.animateItem()) {
                Label("外观", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    val idx = listOf("system", "dark", "light").indexOf(cfg.theme).coerceAtLeast(0)
                    SegmentRow("主题", listOf("跟随系统", "夜间", "日间"), idx) { i ->
                        vm.saveCfg(cfg.copy(theme = listOf("system", "dark", "light")[i]))
                    }
                }
            }
        }

                item("chat", contentType = "chat") {
            Column(Modifier.animateItem()) {
                Label("对话", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        SegmentRow(
                            "思考强度（越低回得越快）",
                            listOf("低", "中", "高"),
                            when (cfg.reasoningEffort) {
                                "medium" -> 1
                                "high" -> 2
                                else -> 0
                            },
                        ) { i ->
                            vm.saveCfg(cfg.copy(reasoningEffort = listOf("low", "medium", "high")[i]))
                        }
                        InnerDivider()
                        Column(Modifier.padding(horizontal = D.s4, vertical = D.s3)) {
                            Text(
                                "低＝保留推理想一遍就答，不磨叽；中＝多想一步；高＝想清楚再开口（更慢）。" +
                                    "如果网关不支持这个参数，就按模型默认走。",
                                style = Ty.caption, color = T.textFaint,
                            )
                        }
                    }
                }
            }
        }

        // ---------------- 记忆 ----------------

                    }

        collapsible("pet", "桌宠", "立绘 · 看屏幕 · 气泡行为 · 权限", "pet" in openGroups, { toggleGroup("pet") }) {
                    item("pet", contentType = "pet") {
            Column(Modifier.animateItem()) {
                Label("桌宠", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        SettingRow(
                            "显示桌宠",
                            if (cfg.petEnabled) "在桌面上陪着你" else "当前关闭",
                            trailing = {
                                Toggle(cfg.petEnabled, { v ->
                                    if (v && !android.provider.Settings.canDrawOverlays(ctx)) {
                                        runCatching {
                                            ctx.startActivity(
                                                android.content.Intent(
                                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    android.net.Uri.parse("package:${ctx.packageName}")
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                        vm.toast = "请先允许「显示在其他应用上层」，再回来打开"
                                    } else {
                                        vm.saveCfg(cfg.copy(petEnabled = v))
                                    }
                                })
                            },
                        )
                        InnerDivider()
                        Text(
                            "她会以像素形象坐在桌面上，可自由拖动。需要「显示在其他应用上层」权限。",
                            style = Ty.caption, color = T.textFaint,
                            modifier = Modifier.padding(horizontal = D.s4, vertical = D.s2),
                        )
                        InnerDivider()
                        val prefsCfg = ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                        var shotMode by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(prefsCfg.getString("shot_mode", "projection") ?: "projection")
                        }
                        var shizukuOk by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(app.zhiyi.pet.ScreenCapture.shizukuReady())
                        }
                        var pickShot by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

                        SettingRow(
                            "识屏方式",
                            when {
                                shotMode == "auto" -> "自动（优先 ADB）"
                                shotMode == "adb" -> if (shizukuOk) "ADB（已授权）" else "ADB（未授权）"
                                else -> "共享屏幕（录屏授权）"
                            },
                            trailing = {
                                androidx.compose.material3.TextButton(onClick = { pickShot = true }) { Text("切换") }
                            },
                        )
                        if (shotMode == "adb" && !shizukuOk) {
                            SettingRow(
                                "授权 ADB",
                                if (app.zhiyi.pet.ScreenCapture.shizukuAvailable()) "点右侧申请 Shizuku 权限"
                                else "还没装 Shizuku（或没启动）",
                                trailing = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        runCatching {
                                            if (app.zhiyi.pet.ScreenCapture.shizukuAvailable()) {
                                                rikka.shizuku.Shizuku.requestPermission(0x5A11)
                                                vm.toast = "已发起授权，请在弹出的窗口里允许"
                                            } else vm.toast = "先安装并启动 Shizuku"
                                        }
                                    }) { Text("授权") }
                                },
                            )
                        }
                        if (pickShot) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { pickShot = false },
                                title = { Text("识屏方式") },
                                text = {
                                    Column(Modifier.animateItem()) {
                                        Text("共享屏幕：用「开始录制」授权，稳定但要每次授权",
                                            style = Ty.caption, color = T.textFaint)
                                        Text("ADB：走 Shizuku 权限截图，不弹录屏框，但要装 Shizuku",
                                            style = Ty.caption, color = T.textFaint)
                                        androidx.compose.foundation.layout.Spacer(Modifier.height(D.s2))
                                        listOf("auto" to "自动（优先 ADB）", "adb" to "ADB（Shizuku）", "projection" to "共享屏幕").forEach { (k, label) ->
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .clickable {
                                                        prefsCfg.edit().putString("shot_mode", k).commit()
                                                        shotMode = k
                                                        pickShot = false
                                                        if (k == "adb" && !app.zhiyi.pet.ScreenCapture.shizukuReady() &&
                                                            app.zhiyi.pet.ScreenCapture.shizukuAvailable()) {
                                                            runCatching { rikka.shizuku.Shizuku.requestPermission(0x5A11) }
                                                        }
                                                    }
                                                    .padding(vertical = D.s2),
                                            ) {
                                                Text(if (k == shotMode) "✓ $label" else "   $label")
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = { pickShot = false }) { Text("关闭") }
                                },
                            )
                        }
                        var skins by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(app.zhiyi.pet.PetSkin.list(ctx)) }
                        var curSkin by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(
                                prefsCfg.getString("pet_skin", app.zhiyi.pet.PetSkin.BUILTIN) ?: app.zhiyi.pet.PetSkin.BUILTIN
                            )
                        }
                        var pending by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf<List<Pair<android.net.Uri, String>>>(emptyList())
                        }
                        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
                        ) { uris ->
                            if (uris.isNotEmpty()) {
                                pending = uris.map { u ->
                                    val nm = runCatching {
                                        ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                            if (c.moveToFirst()) c.getString(0) else null
                                        }
                                    }.getOrNull() ?: (u.lastPathSegment ?: "")
                                    u to app.zhiyi.pet.PetSkin.guessFace(nm)
                                }
                            }
                        }
                        var bindSkin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                        skins.forEach { sk ->
                            val boundName = vm.personas.firstOrNull { it.id == sk.personaId }?.name
                            SettingRow(
                                sk.name,
                                when {
                                    sk.keywords.isNotEmpty() -> sk.keywords.take(3).joinToString("·")
                                    sk.id == curSkin -> "使用中"
                                    boundName != null -> "绑定：$boundName"
                                    else -> "点击切换"
                                },
                                trailing = {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        if (sk.id == curSkin) Text("✓", color = T.accent)
                                        androidx.compose.material3.TextButton(
                                            onClick = { bindSkin = sk.id },
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = D.s2),
                                        ) { Text("绑定", style = Ty.caption) }
                                    }
                                },
                                modifier = Modifier.clickable {
                                    prefsCfg.edit().putString("pet_skin", sk.id).apply()
                                    curSkin = sk.id
                                    if (prefsCfg.getBoolean("pet_on", false)) {
                                        runCatching { app.zhiyi.pet.PetService.reload(ctx) }
                                    }
                                },
                            )
                            InnerDivider()
                        }
                        if (pending.isNotEmpty()) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { pending = emptyList() },
                                title = { Text("给每张图指定表情") },
                                text = {
                                    Column(Modifier.heightIn(max = 320.dp)) {
                                        Text("点右侧表情可循环切换", style = Ty.caption, color = T.textFaint)
                                        pending.forEachIndexed { i, (u, face) ->
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = D.s1),
                                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    (u.lastPathSegment ?: "").takeLast(14),
                                                    style = Ty.caption, color = T.textFaint,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                androidx.compose.material3.TextButton(onClick = {
                                                    val idx = app.zhiyi.pet.PetSkin.FACES.indexOf(face)
                                                    val next = app.zhiyi.pet.PetSkin.FACES[(idx + 1) % app.zhiyi.pet.PetSkin.FACES.size]
                                                    pending = pending.toMutableList().also { it[i] = u to next }
                                                }) { Text(face) }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        val list = pending
                                        pending = emptyList()
                                        app.zhiyi.pet.PetSkin.importMapped(
                                            ctx,
                                            "皮肤 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                            "", list,
                                        )
                                        skins = app.zhiyi.pet.PetSkin.list(ctx)
                                        vm.toast = "皮肤已导入（${list.size} 张）"
                                    }) { Text("导入") }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { pending = emptyList() }) { Text("取消") }
                                },
                            )
                        }

                        bindSkin?.let { sid ->
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { bindSkin = null },
                                title = { Text("绑定到哪个人格") },
                                text = {
                                    Column(Modifier.animateItem()) {
                                        Text("不绑定 = 跟随当前人格", style = Ty.caption, color = T.textFaint)
                                        vm.personas.forEach { p ->
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .clickable {
                                                        app.zhiyi.pet.PetSkin.setPersona(ctx, sid, p.id)
                                                        skins = app.zhiyi.pet.PetSkin.list(ctx)
                                                        bindSkin = null
                                                        if (prefsCfg.getBoolean("pet_on", false))
                                                            runCatching { app.zhiyi.pet.PetService.reload(ctx) }
                                                    }
                                                    .padding(vertical = D.s2),
                                            ) { Text(p.name) }
                                        }
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable {
                                                    app.zhiyi.pet.PetSkin.setPersona(ctx, sid, "")
                                                    skins = app.zhiyi.pet.PetSkin.list(ctx)
                                                    bindSkin = null
                                                }
                                                .padding(vertical = D.s2),
                                        ) { Text("不绑定", color = T.textFaint) }
                                    }
                                },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = { bindSkin = null }) { Text("关闭") }
                                },
                            )
                        }
                        SettingRow(
                            "导入皮肤",
                            "选一组 PNG，文件名含 calm/happy/sad…",
                            trailing = {
                                androidx.compose.material3.TextButton(onClick = { importLauncher.launch(arrayOf("image/*")) }) { Text("选择") }
                            },
                        )
                    }
                }
            }
        }


                }

        collapsible("conn", "连接", "模型 API · 环境变量 · 本地存储", "conn" in openGroups, { toggleGroup("conn") }) {
        item("qq", contentType = "qq") {
            Column(Modifier.animateItem()) {
                Label("QQ", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        SettingRow(
                            "QQ 机器人",
                            if (app.zhiyi.qq.QqBot.anyRunning()) app.zhiyi.qq.QqBot.summary(ctx)
                            else "在 QQ 里和她聊天（官方接口，不需要公网）",
                            onClick = onOpenQq,
                        )
                    }
                }
            }
        }

        item("api", contentType = "api") {
            Column(Modifier.animateItem()) {
                Label("接入模型", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.animateItem()) {
                        vm.providers.forEachIndexed { i, p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.activateProvider(p.id) }
                                    .padding(horizontal = D.s4, vertical = D.s3),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(18.dp).clip(CircleShape)
                                        .background(if (p.id == vm.activeProvider?.id) T.accent else T.track),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (p.id == vm.activeProvider?.id)
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                                }
                                Spacer(Modifier.width(D.s3))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = Ty.bodySm, color = T.text)
                                    Text(
                                        "${p.chatModel}  ·  ${p.base.replace("https://", "")}",
                                        style = Ty.caption, color = T.textFaint,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text("编辑", style = Ty.caption, color = T.textDim,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(T.hi2)
                                        .border(1.dp, T.handle, CircleShape)
                                        .clickable { editing = p; showProvider = true }
                                        .padding(horizontal = D.s3, vertical = D.s1))
                                Spacer(Modifier.width(D.s2))
                                Text("删除", style = Ty.caption, color = T.danger,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(T.dangerTint)
                                        .border(1.dp, T.danger.copy(alpha = 0.4f), CircleShape)
                                        .clickable { vm.deleteProvider(p.id) }
                                        .padding(horizontal = D.s3, vertical = D.s1))
                            }
                            if (i != vm.providers.lastIndex) InnerDivider()
                        }
                        InnerDivider(start = 0.dp)
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = null; showProvider = true }
                                .padding(horizontal = D.s4, vertical = D.s4),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconChip { GlyphPlus(T.accent, 14.dp) }
                            Spacer(Modifier.width(D.s3))
                            Text("接入新的 API", style = Ty.bodySm, color = T.accent)
                        }
                    }
                }
            }
        }

        // ---------------- 人格 ----------------

        item("env", contentType = "env") {
            Column(Modifier.animateItem()) {
                Label("环境感知", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        SettingRow(
                            "所在城市",
                            if (weatherNow.isBlank()) "填了就按天气调整语气" else weatherNow,
                            trailing = {
                                androidx.compose.foundation.text.BasicTextField(
                                    value = city, onValueChange = { city = it },
                                    singleLine = true,
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(T.hi2)
                                        .padding(horizontal = D.s2, vertical = D.s1),
                                    textStyle = Ty.bodySm.copy(color = T.text),
                                )
                            },
                        )
                        InnerDivider()
                        Text(
                            "立即刷新天气",
                            style = Ty.bodySm, color = T.accent,
                            modifier = Modifier
                                .clickable { fetchWeather() }
                                .padding(horizontal = D.s4, vertical = D.s2),
                        )
                    }
                }
            }
        }

        item("storage", contentType = "storage") {
            Column(Modifier.animateItem()) {
                Label("存储", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        // 原来的「清理缓存 / 清缓存+清空表情」全部并入「清理空间」，
                        // 免得同一件事有两个入口、以后改了这里忘那里
                        SettingRow(
                            "清理空间",
                            "占用 ${app.zhiyi.data.CleanStore.human(app.zhiyi.data.CleanStore.dirBytes(ctx.cacheDir))} 缓存 · " +
                                "记忆、声音、立绘按人格分开删",
                            onClick = onOpenClean,
                        )

                    }
                }
            }
        }

                }

        collapsible("data", "数据与关于", "备份恢复 · 关于", "data" in openGroups, { toggleGroup("data") }) {
        item("backup", contentType = "backup") {
            Column(Modifier.animateItem()) {
                Label("备份与恢复", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = D.s1)) {
                        SettingRow("导出备份", "所有数据打成一个 JSON 文件", value = "导出",
                            onClick = { exportBackup.launch("zhiyi-backup.json") })
                        InnerDivider()
                        SettingRow("导入备份", "从 JSON 恢复（会覆盖现有数据）", value = "导入",
                            onClick = { importBackup.launch(arrayOf("application/json")) })
                        InnerDivider()
                        Text(
                            "备份含人格、世界观、聊天记录、记忆、日记、API 配置。导出文件里带 API Key，别随便分享。",
                            style = Ty.caption, color = T.textFaint,
                            modifier = Modifier.padding(horizontal = D.s4, vertical = D.s2),
                        )
                    }
                }
            }
        }

        item("about", contentType = "about") {
            Column(Modifier.animateItem()) {
                Label("关于", Modifier.padding(start = D.s4, bottom = D.s2))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(D.s4)) {
                        Text("知忆 7.9", style = Ty.title3, color = T.text)
                        if (vm.daysTogether > 0) {
                            Spacer(Modifier.height(D.s1))
                            Text(
                                "和她相识第 ${vm.daysTogether} 天",
                                style = Ty.caption, color = T.textDim,
                            )
                        }
                        Spacer(Modifier.height(D.s1))
                        Text(
                            "分层记忆：最近对话 → 滚动摘要 → 结构化事实 → 事件回忆（向量召回）→ 人格锚点。" +
                                "每轮生成前按 token 预算重新拼一次上下文，所以能聊很久还不崩人设。\n\n" +
                                "数据只存在本机 zhiyi.db。换手机前记得备份。",
                            style = Ty.caption, color = T.textDim,
                        )
                    }
                }
            }
        }

                }


        item("tail", contentType = "tail") { Spacer(Modifier.height(110.dp)) }
    }

    // ---------------- 抽屉 ----------------
    if (showProGuide) {
        Sheet({ showProGuide = false }) {
            Text("让后台能叫醒她", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s2))
            Text(
                "主动发话靠系统定时唤醒。国产系统默认会杀掉后台，得手动开两处：",
                style = Ty.bodySm, color = T.textDim,
            )
            Spacer(Modifier.height(D.s3))
            val okBatt = PermissionHelper.ignoringBattery(ctx)
            SettingRow(
                "允许后台运行",
                if (okBatt) "已允许" else "电池优化里放行",
                value = if (okBatt) "已开" else "去开",
                valueAsButton = !okBatt,
                onClick = { if (!okBatt) PermissionHelper.requestBattery(ctx) },
            )
            InnerDivider()
            SettingRow(
                "自启动", "被系统杀了能自己爬起来",
                value = "去开", valueAsButton = true,
                onClick = { PermissionHelper.openAutoStart(ctx) },
            )
            Spacer(Modifier.height(D.s3))
            Text(
                "没有这两处，切到后台一段时间后她就再也醒不过来了。",
                style = Ty.caption, color = T.textFaint,
            )
            Spacer(Modifier.height(D.s4))
            PillButton("知道了", { showProGuide = false }, Modifier.fillMaxWidth())
        }
    }
    if (showProvider) {
        ProviderSheet(
            initial = editing,
            onClose = { showProvider = false },
            onSave = { p -> vm.saveProvider(p); showProvider = false },
            newId = { vm.newId() },
        )
    }
    if (showPersonaEdit) {
        PersonaSheet(
            initial = editingPersona ?: Persona(),
            onClose = { showPersonaEdit = false },
            onSave = { p -> vm.savePersona(p); showPersonaEdit = false },
            onDelete = {
                editingPersona?.id?.let { vm.deletePersona(it) }
                showPersonaEdit = false
            },
            onShare = { editingPersona?.let { vm.sharePersona(it) } },
            providers = vm.providers,
            currentProviderId = editingPersona?.let { vm.providerFor(it.id)?.id }
                ?: vm.providers.firstOrNull()?.id ?: "",
            onPickProvider = { pid -> editingPersona?.let { vm.setProviderFor(it.id, pid) } },
            revs = editingPersona?.id?.takeIf { it.isNotBlank() }
                ?.let { pid -> remember(pid, showPersonaEdit) { vm.db.personaRevs(pid) } }
                ?: emptyList(),
            onRestore = { rev -> editingPersona?.id?.let { vm.restorePersona(it, rev) } },
            onResetEvolve = { editingPersona?.id?.let { vm.resetEvolve(it) } },
        )
    }
    if (showPersonaList) {
        Sheet({ showPersonaList = false }) {
            Text("切换人格", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s4))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                vm.personas.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            vm.switchPersona(p.id); showPersonaList = false
                        }.padding(vertical = D.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(p.name)
                        Spacer(Modifier.width(D.s3))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = Ty.body, color = T.text)
                            Text(p.role.ifBlank { p.summary.take(24) }, style = Ty.caption, color = T.textFaint)
                        }
                        if (p.id == vm.persona?.id) {
                            Text("当前", style = Ty.caption, color = T.accent)
                            Spacer(Modifier.width(D.s3))
                        }
                        Text(
                            "删除", style = Ty.caption, color = T.danger,
                            modifier = Modifier.clickable {
                                vm.deletePersona(p.id)
                                showPersonaList = false
                            }.padding(horizontal = D.s2, vertical = D.s1),
                        )
                    }
                    InnerDivider(start = 0.dp)
                }
            }
            Spacer(Modifier.height(D.s4))
            PillButton("关闭", { showPersonaList = false }, Modifier.fillMaxWidth(), primary = false)
        }
    }
    if (showPasteJson) {
        Sheet({ showPasteJson = false }) {
            Text("粘贴角色 JSON", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s3))
            Field("角色定义", pasteText, { pasteText = it }, "把 chara_card_v2 的 JSON 整段贴进来", minLines = 8)
            Spacer(Modifier.height(D.s3))
            PillButton(
                "从文件选择（.json）",
                {
                    pickJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                },
                Modifier.fillMaxWidth(), primary = false,
            )
            Spacer(Modifier.height(D.s4))
            PillButton("导入", {
                if (pasteText.isBlank()) { vm.error = "先粘贴内容，或改用上面的选文件" }
                else { vm.importAny(pasteText); showPasteJson = false }
            }, Modifier.fillMaxWidth(), enabled = pasteText.isNotBlank())
        }
    }

    // 填 MiMo Key
    if (showTtsKey) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTtsKey = false },
            title = { Text("MiMo API Key", style = Ty.title3, color = T.text) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = ttsKeyDraft,
                        onValueChange = { ttsKeyDraft = it.trim() },
                        singleLine = true,
                        placeholder = { Text("sk-...", style = Ty.bodySm) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(D.s2))
                    Text(
                        "声音克隆走小米 MiMo 的 voiceclone 模型，目前限时免费。" +
                            "Key 只存在这台手机上。",
                        style = Ty.caption, color = T.textFaint,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.saveCfg(vm.cfg.copy(ttsKey = ttsKeyDraft))
                    showTtsKey = false
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTtsKey = false }) { Text("取消") }
            },
        )
    }

    // 选音色：内置两条 + 自己的文件
    if (showVoicePick) {
        val pidV = voicePickPid
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showVoicePick = false },
            title = { Text("她的声音", style = Ty.title3, color = T.text) },
            text = {
                Column {
                    Text("内置音色", style = Ty.caption, color = T.textFaint)
                    Spacer(Modifier.height(D.s1))
                    VoiceStore.PRESETS.forEach { (key, name) ->
                        // 用户传了自己的文件时预设不生效（dataUrl 里文件优先），这里得说真话
                        val on = VoiceStore.userFile(ctx, pidV) == null &&
                            VoiceStore.currentPreset(ctx, pidV) == key
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    VoiceStore.choosePreset(ctx, pidV, key)
                                    voiceTick++
                                    showVoicePick = false
                                }
                                .padding(vertical = D.s2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, style = Ty.bodySm, color = if (on) T.accent else T.text)
                            Spacer(Modifier.weight(1f))
                            if (on) Text("使用中", style = Ty.caption, color = T.accent)
                        }
                    }
                    Spacer(Modifier.height(D.s3))
                    InnerDivider()
                    Spacer(Modifier.height(D.s3))
                    if (VoiceStore.userFile(ctx, pidV) != null) {
                        Text(
                            "你已经传了自己的录音，内置音色暂时不生效——先删掉上传的样本。",
                            style = Ty.caption, color = T.accent.copy(alpha = 0.75f),
                        )
                        Spacer(Modifier.height(D.s1))
                    }
                    Text("自己的录音", style = Ty.caption, color = T.textFaint)
                    Spacer(Modifier.height(D.s1))
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                showVoicePick = false
                                pickVoice.launch(arrayOf("audio/*"))
                            }
                            .padding(vertical = D.s2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("上传一段音频", style = Ty.bodySm, color = T.text)
                    }
                    if (VoiceStore.userFile(ctx, pidV) != null) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    // 文案写的是「删掉上传的样本」，就只删文件，别把内置选择一起清了
                                    VoiceStore.removeUser(ctx, pidV)
                                    voiceTick++
                                    showVoicePick = false
                                }
                                .padding(vertical = D.s2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("删掉上传的样本", style = Ty.bodySm, color = T.danger)
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showVoicePick = false }) { Text("关闭") }
            },
        )
    }

}

@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(T.accent, T.purple))),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), style = Ty.title3, color = T.onAccent)
    }
}

@Composable
private fun SegmentRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = D.s4, vertical = D.s3)) {
        Text(title, style = Ty.bodySm, color = T.text)
        Spacer(Modifier.height(D.s2))
        Segmented(options, selected, onSelect)
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String = "",
    minLines: Int = 1,
    secret: Boolean = false,
) {
    Column {
        Label(label)
        Spacer(Modifier.height(D.s2))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(D.rMd))
                .background(T.fieldBg)
                .padding(horizontal = D.s3, vertical = D.s3)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = Ty.bodySm.copy(color = T.text),
                cursorBrush = SolidColor(T.accent),
                maxLines = if (minLines > 1) 10 else 1,
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().heightIn(min = (minLines * 20).dp),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(
                        placeholder, style = Ty.bodySm, color = T.textFaint,
                        maxLines = if (minLines > 1) 10 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    inner()
                },
            )
        }
    }
}

@Composable
private fun ProviderSheet(
    initial: Provider?,
    onClose: () -> Unit,
    onSave: (Provider) -> Unit,
    newId: () -> String,
) {
    val id = initial?.id ?: remember { newId() }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var base by remember { mutableStateOf(initial?.base ?: "") }
    var key by remember { mutableStateOf(initial?.key ?: "") }
    var chat by remember { mutableStateOf(initial?.chatModel ?: "") }
    var cheap by remember { mutableStateOf(initial?.cheapModel ?: "") }
    var embed by remember { mutableStateOf(initial?.embedModel ?: "") }
    var vision by remember { mutableStateOf(initial?.visionModel ?: "") }

    // 0=主对话 1=记忆 2=向量
    var pickFor by remember { mutableStateOf(-1) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var pickErr by remember { mutableStateOf("") }
    var pickMsg by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun fetchModels(target: Int) {
        pickFor = target
        if (base.isBlank()) { pickErr = "先填 Base URL"; return }
        // 表单里没填 Key 时，用已保存的兜底（编辑场景：key 输入框可能是掩码印象，实际值在库里）
        val useKey = key.trim().ifBlank { initial?.key?.trim() ?: "" }
        if (useKey.isBlank()) { pickErr = "先填 API Key（或先保存后再拉取）"; return }
        loading = true
        pickErr = ""
        pickMsg = ""
        models = emptyList()
        scope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    app.zhiyi.llm.Llm(
                        Provider(id = "probe", name = "probe", base = base.trim(), key = useKey,
                            chatModel = "", cheapModel = "", embedModel = "")
                    ).models()
                }
            }
            loading = false
            res.onSuccess {
                models = it.sorted()
                if (it.isEmpty()) pickErr = "接口没返回模型列表"
            }.onFailure {
                pickErr = when {
                    it.message?.contains("401") == true -> "HTTP 401：Key 无效。检查 Key 是否填对（注意别混入空格/换行）"
                    it.message?.contains("404") == true -> "HTTP 404：这个地址没有 /models 接口，可以在下面手填模型名"
                    else -> it.message ?: "拉取失败"
                }
            }
        }
    }

    Sheet(onClose) {
        if (pickFor >= 0) {
            // ---------- 模型选择 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择模型", style = Ty.title2, color = T.text, modifier = Modifier.weight(1f))
                Text("返回", style = Ty.bodySm, color = T.accent,
                    modifier = Modifier.clickable { pickFor = -1 }.padding(D.s2))
            }
            Spacer(Modifier.height(D.s2))
            Text(
                when (pickFor) {
                    0 -> "主对话模型：点一个作为主模型，再点就作为回退（失败自动切下一个）"
                    1 -> "记忆小模型：抽事实/压摘要，越便宜越好，可加回退"
                    2 -> "向量模型（可选）：做语义回忆，可加回退"
                    else -> "识屏视觉模型：截图后拿它看图，留空就跟着主对话模型走"
                },
                style = Ty.caption, color = T.textFaint,
            )
            Spacer(Modifier.height(D.s3))
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) { TypingDots(T.accent); Spacer(Modifier.width(D.s2)); Text("正在拉取…", style = Ty.caption, color = T.textFaint) }
            }
            if (pickErr.isNotBlank()) {
                Text(pickErr, style = Ty.caption, color = T.danger)
                Spacer(Modifier.height(D.s2))
            }
            if (pickMsg.isNotBlank()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(D.rSm))
                        .background(T.ok.copy(alpha = 0.14f))
                        .padding(horizontal = D.s3, vertical = D.s2)
                ) {
                    Text("✓ $pickMsg", style = Ty.bodySm, color = T.ok)
                }
                Spacer(Modifier.height(D.s2))
            }
            var manualModel by remember { mutableStateOf("") }
            Field("或者直接手填", manualModel, { manualModel = it }, "例如 deepseek-chat / glm-4.6")
            Spacer(Modifier.height(D.s2))
            PillButton(
                "使用这个名字",
                {
                    val mm = manualModel.trim()
                    val before = when (pickFor) {
                        0 -> chat.modelList(); 1 -> cheap.modelList()
                        2 -> embed.modelList(); else -> vision.modelList()
                    }
                    when (pickFor) {
                        0 -> chat = chat.appendModel(mm)
                        1 -> cheap = cheap.appendModel(mm)
                        2 -> embed = embed.appendModel(mm)
                        else -> vision = vision.appendModel(mm)
                    }
                    pickMsg = if (mm in before) "「$mm」已经在列表里"
                        else if (before.isEmpty()) "已设为主模型：$mm"
                        else "已添加为回退 ${before.size + 1}：$mm"
                    manualModel = ""
                },
                Modifier.fillMaxWidth(), primary = false,
                enabled = manualModel.isNotBlank(),
                height = 40.dp,
            )
            Spacer(Modifier.height(D.s3))
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(models) { m ->
                    val cur = when (pickFor) {
                        0 -> chat; 1 -> cheap; 2 -> embed; else -> vision
                    }
                    val inList = m in cur.modelList()
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                val before = cur.modelList()
                                when (pickFor) {
                                    0 -> chat = chat.appendModel(m)
                                    1 -> cheap = cheap.appendModel(m)
                                    2 -> embed = embed.appendModel(m)
                                    else -> vision = vision.appendModel(m)   // 3 = 识屏视觉模型
                                }
                                pickMsg = if (m in before) "「$m」已经在列表里"
                                    else if (before.isEmpty()) "已设为主模型：$m"
                                    else "已添加为回退 ${before.size + 1}：$m"
                                // 不关选择页，可以连续加多个回退模型
                            }.padding(vertical = D.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            m, style = Ty.bodySm,
                            color = if (inList) T.accent else T.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (inList) "✓ 已选" else "＋ 添加",
                            style = Ty.caption, color = if (inList) T.ok else T.accent,
                        )
                    }
                    InnerDivider(start = 0.dp)
                }
            }
            Spacer(Modifier.height(D.s4))
            PillButton("重新拉取", { fetchModels(pickFor) }, Modifier.fillMaxWidth(), primary = false)
            return@Sheet
        }

        // ---------- 表单 ----------
        Text(if (initial == null) "接入新的 API" else "编辑 API", style = Ty.title2, color = T.text)
        Spacer(Modifier.height(D.s2))
        Text("OpenAI 兼容接口，地址一般以 /v1 结尾", style = Ty.caption, color = T.textFaint)
        Spacer(Modifier.height(D.s4))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Field("备注名", name, { name = it }, "我的 DeepSeek")
            Spacer(Modifier.height(D.s3))
            Field("Base URL", base, { base = it }, "https://api.deepseek.com/v1")
            Spacer(Modifier.height(D.s3))
            Field("API Key", key, { key = it }, "sk-...", secret = true)
            Spacer(Modifier.height(D.s4))
            Label("模型")
            Spacer(Modifier.height(D.s2))
            ModelPickRow("主对话模型", chat,
                onAdd = { fetchModels(0) },
                onRemove = { chat = chat.removeModel(it) },
                hint = "选有多模态的主模型，可以更好地实现看图（发照片她能看懂）")
            Spacer(Modifier.height(D.s2))
            ModelPickRow("记忆用小模型", cheap,
                onAdd = { fetchModels(1) },
                onRemove = { cheap = cheap.removeModel(it) })
            Spacer(Modifier.height(D.s2))
            ModelPickRow("向量模型（可选）", embed,
                onAdd = { fetchModels(2) },
                onRemove = { embed = embed.removeModel(it) })
            Spacer(Modifier.height(D.s2))
            ModelPickRow("识屏视觉模型", vision,
                onAdd = { fetchModels(3) },
                onRemove = { vision = vision.removeModel(it) },
                hint = "留空 = 用主对话模型。识屏 / 识视频都走这里，专挑看得懂图的模型会更准")
            Spacer(Modifier.height(D.s2))
            Text("「＋」拉这个接口的 /models 列表，第一个是主模型，后面是回退", style = Ty.caption, color = T.textFaint)
        }
        Spacer(Modifier.height(D.s4))
        PillButton("保存", {
            onSave(
                Provider(
                    id = id, name = name.ifBlank { "未命名" }, base = base.trim(), key = key.trim(),
                    chatModel = chat.trim(), cheapModel = cheap.trim(), embedModel = embed.trim(),
                    visionModel = vision.trim(),
                )
            )
        }, Modifier.fillMaxWidth(), enabled = base.isNotBlank())
    }
}

@Composable
private fun ModelPickRow(
    label: String,
    value: String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    hint: String? = null,
) {
    val models = value.modelList()
    Column(Modifier.fillMaxWidth().padding(vertical = D.s1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Ty.caption, color = T.textFaint)
                if (hint != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(hint, style = Ty.caption, color = T.textFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (models.isEmpty()) "未选择"
                    else if (models.size == 1) models[0]
                    else "${models[0]}  +${models.size - 1} 个回退",
                    style = Ty.bodySm, color = if (models.isEmpty()) T.textFaint else T.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.clip(CircleShape).background(T.accentTint)
                    .border(1.dp, T.accent.copy(alpha = 0.5f), CircleShape)
                    .clickable { onAdd() }
                    .padding(horizontal = D.s3, vertical = D.s2)
            ) { Text("＋", style = Ty.bodySm, color = T.accent) }
        }
        // 主模型单独一行：带「×」可删，删了回退顶上第一个自动升为主
        if (models.isNotEmpty()) {
            Spacer(Modifier.height(D.s1))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("主", style = Ty.caption, color = T.accent)
                Spacer(Modifier.width(D.s2))
                Text(models[0], style = Ty.caption, color = T.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(22.dp).clip(CircleShape).clickable { onRemove(models[0]) },
                    contentAlignment = Alignment.Center,
                ) { Text("×", style = Ty.caption, color = T.textFaint) }
            }
        }
        // 回退列表（可逐条删）
        if (models.size > 1) {
            models.drop(1).forEach { m ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("↳ 回退", style = Ty.caption, color = T.textFaint)
                    Spacer(Modifier.width(D.s2))
                    Text(m, style = Ty.caption, color = T.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).clickable { onRemove(m) },
                        contentAlignment = Alignment.Center,
                    ) { Text("×", style = Ty.caption, color = T.textFaint) }
                }
            }
        }
    }
}

private fun fmtRevTime(ts: Long): String {
    if (ts <= 0) return ""
    val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
    return f.format(java.util.Date(ts))
}

@Composable
private fun PersonaSheet(
    initial: Persona,
    onClose: () -> Unit,
    onSave: (Persona) -> Unit,
    onDelete: () -> Unit = {},
    onShare: () -> Unit = {},
    providers: List<Provider> = emptyList(),
    currentProviderId: String = "",
    onPickProvider: (String) -> Unit = {},
    revs: List<app.zhiyi.data.PersonaRev> = emptyList(),
    onRestore: (Int) -> Unit = {},
    onResetEvolve: () -> Unit = {},
) {
    var name by remember { mutableStateOf(initial.name) }
    var role by remember { mutableStateOf(initial.role) }
    var callYou by remember { mutableStateOf(initial.callYou) }
    var summary by remember { mutableStateOf(initial.summary) }
    var personality by remember { mutableStateOf(initial.personality) }
    var speech by remember { mutableStateOf(initial.speech) }
    var voiceText by remember {
        mutableStateOf(app.zhiyi.memory.VoiceTone.voiceToText(initial?.voiceSamples ?: ""))
    }
    var catchphrase by remember { mutableStateOf(initial.catchphrase.joinToString("、")) }
    var taboo by remember { mutableStateOf(initial.taboo.joinToString("、")) }
    var firstMes by remember { mutableStateOf(initial.firstMes) }
    var proactive by remember { mutableStateOf(initial.proactiveStyle) }
    var pickedProviderId by remember { mutableStateOf(currentProviderId) }
    var sayPre by remember { mutableStateOf(initial.sayPrefix) }
    var saySuf by remember { mutableStateOf(initial.saySuffix) }
    var birthday by remember { mutableStateOf(initial.birthday) }
    var shadow by remember { mutableStateOf(initial.shadow) }
    var aspire by remember { mutableStateOf(initial.aspire) }
    var showHist by remember { mutableStateOf(false) }

    Sheet(onClose) {
        Text("人格设定", style = Ty.title2, color = T.text)
        Spacer(Modifier.height(D.s3))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Field("名字", name, { name = it }, "温言")
            Spacer(Modifier.height(D.s3))
            Field("和你的关系", role, { role = it }, "恋人 / 老朋友 / 助理")
            Spacer(Modifier.height(D.s3))
            Field("她怎么称呼你", callYou, { callYou = it }, "你 / 阿念")
            Spacer(Modifier.height(D.s3))
            Field("一句话设定", summary, { summary = it }, "住你楼下的书店店员…", minLines = 2)
            Spacer(Modifier.height(D.s3))
            Field("性格", personality, { personality = it }, "安静、克制、观察力极强…", minLines = 4)
            Spacer(Modifier.height(D.s3))
            Field("说话方式", speech, { speech = it }, "短句为主，很少用感叹号…", minLines = 3)
            Spacer(Modifier.height(D.s3))
            Field(
                "向往（她心里真正想要的）", aspire, { aspire = it },
                "想被人需要，但不想承认；希望有一天能说出那句一直没说的话", minLines = 3,
            )
            Spacer(Modifier.height(D.s3))
            Field(
                "阴影（她自己也不愿承认的那部分）", shadow, { shadow = it },
                "怕被丢下，所以先装作不在意；被否定的时候会先笑着接住，回家才难过", minLines = 3,
            )
            Spacer(Modifier.height(D.s2))
            Text(
                "上面两条不会直接说出口，它渗在反应里：被忽略时先缩一下再装作没事、听到某句话会突然认真、" +
                    "给建议时带着自己的私心。没有内在张力的角色，只是形容词堆出来的。",
                style = Ty.caption, color = T.textFaint,
            )
            Spacer(Modifier.height(D.s3))
            Field(
                "场景语气例句（可选）",
                voiceText,
                { voiceText = it },
                "[倒苦水]\n谁啊这么离谱，说吧我陪你骂\n\n[闲聊搭话]\n在的。刚发了会儿呆\n\n（用 [场景] 分段，每行一句。可用的场景：" +
                    app.zhiyi.memory.VoiceScene.entries.joinToString("、") { it.label } + "）",
                minLines = 6,
            )
            Spacer(Modifier.height(D.s3))
            Field("口头禅（顿号分隔）", catchphrase, { catchphrase = it }, "嗯。、这样啊。")
            Spacer(Modifier.height(D.s3))
            Field("绝对不能说（顿号分隔）", taboo, { taboo = it }, "作为AI、我是语言模型")
            Spacer(Modifier.height(D.s3))
            Field("开场白", firstMes, { firstMes = it }, "她发的第一条消息", minLines = 3)
            Spacer(Modifier.height(D.s3))
            Field("生日（月日）", birthday, { birthday = it }, "3月17日（空则不提醒）")
            Spacer(Modifier.height(D.s3))
            Field("主动发话的风格", proactive, { proactive = it }, "像忽然想起一件事那样开口", minLines = 2)
            Spacer(Modifier.height(D.s3))
            Label("这个身份用的模型")
            Spacer(Modifier.height(D.s2))
            if (providers.isEmpty()) {
                Text("还没有接入任何 API（去上面「接入模型」加一个）", style = Ty.caption, color = T.textFaint)
            } else {
                providers.forEach { pv ->
                    val on = pv.id == pickedProviderId
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { pickedProviderId = pv.id }
                            .padding(vertical = D.s2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(CircleShape)
                                .background(if (on) T.accent else T.track),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Box(Modifier.size(6.dp).clip(CircleShape).background(T.onAccent)) }
                        Spacer(Modifier.width(D.s3))
                        Column(Modifier.weight(1f)) {
                            Text(pv.name, style = Ty.bodySm, color = T.text)
                            Text(pv.chatModel, style = Ty.caption, color = T.textFaint, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(D.s3))
            Label("台词标记（必填，始终发给模型）")
            Spacer(Modifier.height(D.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Field("说出口的话 · 前缀", sayPre, { sayPre = it }, "「")
                }
                Spacer(Modifier.width(D.s2))
                Box(Modifier.weight(1f)) {
                    Field("后缀", saySuf, { saySuf = it }, "」")
                }
            }
            Spacer(Modifier.height(D.s2))
            Text(
                "标记之间的文字会被当成她真正说的话（气泡 + 朗读）；标记外面的是旁白，渲染成居中提示。",
                style = Ty.caption, color = T.textFaint,
            )
        }
        Spacer(Modifier.height(D.s4))
        PillButton("保存", {
            onSave(
                initial.copy(
                    id = initial.id.ifBlank { "p_" + System.currentTimeMillis().toString(36) },
                    name = name.ifBlank { "无名" }, role = role, callYou = callYou, summary = summary,
                    personality = personality, speech = speech,
                    voiceSamples = app.zhiyi.memory.VoiceTone.parseVoiceText(voiceText),
                    catchphrase = catchphrase.split("、", ",").map { it.trim() }.filter { it.isNotBlank() },
                    taboo = taboo.split("、", ",").map { it.trim() }.filter { it.isNotBlank() },
                    firstMes = firstMes, proactiveStyle = proactive,
                    sayPrefix = sayPre.ifBlank { "「" }, saySuffix = saySuf.ifBlank { "」" },
                    birthday = birthday.trim(),
                    // evolveNote 不在这里写：它是她自己长出来的，编辑设定不该顺手把它清掉
                    shadow = shadow, aspire = aspire,
                )
            )
        }, Modifier.fillMaxWidth())
        if (pickedProviderId.isNotBlank() && pickedProviderId != currentProviderId) {
            onPickProvider(pickedProviderId)
        }
        if (initial.evolveNote.isNotBlank()) {
            Spacer(Modifier.height(D.s4))
            Label("她最近学到的（从你们的聊天里长出来的）")
            Spacer(Modifier.height(D.s2))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(D.rMd)).background(T.fieldBg)
                    .padding(D.s3),
            ) {
                initial.evolveNote.split("\n").filter { it.isNotBlank() }.forEach {
                    Text("· $it", style = Ty.bodySm, color = T.textDim)
                    Spacer(Modifier.height(D.s1))
                }
            }
            Spacer(Modifier.height(D.s2))
            PillButton("让她回到你写的样子", onResetEvolve, Modifier.fillMaxWidth(), primary = false)
        }
        if (initial.id.isNotBlank() && revs.isNotEmpty()) {
            Spacer(Modifier.height(D.s4))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Label("版本历史 · ${revs.size} 版")
                Spacer(Modifier.weight(1f))
                Text(
                    if (showHist) "收起" else "展开",
                    style = Ty.bodySm, color = T.accent,
                    modifier = Modifier.clickable { showHist = !showHist },
                )
            }
            if (showHist) {
                Spacer(Modifier.height(D.s2))
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    revs.take(30).forEachIndexed { idx, r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = D.s2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "r${"%03d".format(r.rev)}",
                                style = Ty.caption, color = if (idx == 0) T.accent else T.textFaint,
                            )
                            Spacer(Modifier.width(D.s2))
                            Column(Modifier.weight(1f)) {
                                Text(r.note, style = Ty.caption, color = T.textDim, maxLines = 1)
                                Text(fmtRevTime(r.ts), style = Ty.caption, color = T.textFaint)
                            }
                            if (idx != 0) {
                                Text(
                                    "回到这版", style = Ty.caption, color = T.accent,
                                    modifier = Modifier.clickable { onRestore(r.rev) },
                                )
                            } else {
                                Text("当前", style = Ty.caption, color = T.textFaint)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(D.s2))
                Text(
                    "回滚不是退回旧状态，是把旧版本重新发布一次——历史一条都不会少，随时能再回来。",
                    style = Ty.caption, color = T.textFaint,
                )
            }
        }
        if (initial.id.isNotBlank()) {
            Spacer(Modifier.height(D.s3))
            PillButton("分享这个人格", onShare, Modifier.fillMaxWidth(), primary = false)
            Spacer(Modifier.height(D.s2))
            PillButton("删除这个人格", onDelete, Modifier.fillMaxWidth(), danger = true)
        }
    }
}
