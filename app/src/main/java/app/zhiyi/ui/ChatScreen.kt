package app.zhiyi.ui

import app.zhiyi.voice.MimoTts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zhiyi.AppVm
import app.zhiyi.data.IMG_PREFIX
import app.zhiyi.data.ImageStore
import app.zhiyi.data.Msg
import app.zhiyi.data.StickerStore
import app.zhiyi.data.isStickerMessage
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun ChatScreen(vm: AppVm, onBack: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val p = vm.persona
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val messages = vm.messages
    val streaming = vm.streaming
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    // 输入框要能控制光标位置（点「旁白」要把光标塞进括号里），所以内部用 TextFieldValue；
    // vm.draft 仍存纯文本，两边靠 LaunchedEffect 单向同步。
    var draftVal by remember { mutableStateOf(TextFieldValue(vm.draft)) }
    LaunchedEffect(vm.draft) {
        if (vm.draft != draftVal.text) draftVal = TextFieldValue(vm.draft, TextRange(vm.draft.length))
    }
    // 她刚说完这条，趁你还在看就先悄悄合成好——点喇叭时就是读本地了。
    // 合成慢是 MiMo 那边的事，我们能做的是别让「合成」和「你点击」串行。
    LaunchedEffect(messages.size, p?.id, vm.cfg.ttsKey) {
        if (vm.cfg.ttsKey.isBlank()) return@LaunchedEffect
        val last = messages.lastOrNull { it.role != "user" && it.content.isNotBlank() }
            ?: return@LaunchedEffect
        // 提前触发：反正只是后台合成，早点开始就早点能读本地
        kotlinx.coroutines.delay(400)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MimoTts.prefetch(ctx.applicationContext, last.pid, last.content)
        }
    }
    // 自动朗读：她说完就念。只在"最后一条确实是她的新消息"时触发，
    // 否则切人格/重进页面也会把历史最后一条念一遍。
    val autoRead = vm.cfg.ttsAuto
    LaunchedEffect(messages.size, autoRead, p?.id) {
        if (!autoRead) return@LaunchedEffect
        if (vm.cfg.ttsKey.isBlank()) return@LaunchedEffect
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        if (last.role == "user" || last.content.isBlank()) return@LaunchedEffect
        if (isStickerMessage(last.content) || isEmojiOnly(last.content)) return@LaunchedEffect
        MimoTts.speak(ctx, last.pid, last.content)
    }

    var showSwitch by remember { mutableStateOf(false) }
    var showHeart by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Msg?>(null) }
    var showEmoji by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // 待发送的图片（相对文件名）
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { uris.mapNotNull { ImageStore.save(ctx, it) } }
                pendingImages = pendingImages + saved
            }
        }
    }
    // 已播过入场动画的消息 id：滚动回收重建时不重播；切人格重置、超 500 条裁剪
    val playedIds = remember(p?.id) { androidx.compose.runtime.mutableStateListOf<Long>() }
    if (playedIds.size > 500) playedIds.removeRange(0, 250)
    // 注意：对话页内切人格不能对 LazyColumn 做 graphicsLayer alpha 淡入（离屏渲染，消息多时卡）
    var emojiTab by remember { mutableStateOf(0) }

    if (p == null || p.id.isBlank()) {
        NoPersonaScreen(vm, onOpenSettings)
        return
    }

    // 跟随新消息：只在用户本来就在底部时
    val atBottom by androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        last == null || last.index >= info.totalItemsCount - 2
    }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty() || !atBottom) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }

    // 进入 / 切换人格：等列表有 item 再落底
    LaunchedEffect(p.id) {
        var tries = 0
        while (listState.layoutInfo.totalItemsCount == 0 && tries < 30) {
            androidx.compose.runtime.withFrameNanos { }
            tries++
        }
        listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }


    Column(Modifier.fillMaxSize().background(T.bg).statusBarsPadding()) {
        // 顶栏：返回 · 头像+名字（点开切人格）
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = D.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val backSrc = remember { MutableInteractionSource() }
            Box(
                Modifier.size(D.touch).clip(CircleShape)
                    .clickable(backSrc, indication = null, onClick = onBack)
                    .pressScale(backSrc),
                contentAlignment = Alignment.Center,
            ) { GlyphBack(T.textDim) }

            val headSrc = remember { MutableInteractionSource() }
            Row(
                Modifier.clip(RoundedCornerShape(D.rCard))
                    .clickable(headSrc, indication = null) { showSwitch = true }
                    .pressScale(headSrc, 0.97f)
                    .padding(horizontal = D.s2, vertical = D.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(T.accent, T.purple))),
                    contentAlignment = Alignment.Center,
                ) { Text(p.name.take(1), style = Ty.caption, color = T.onAccent) }
                Spacer(Modifier.width(D.s2))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = Ty.title3, color = T.text, maxLines = 1)
                        Spacer(Modifier.width(D.s2))
                        // 此刻的心情：一眼能看到，点开是九维明细
                        val hState = remember(p.id, messages.size) {
                            app.zhiyi.memory.Heart.load(vm.db, p.id)
                        }
                        Box(
                            Modifier.clip(CircleShape).background(T.accentTint)
                                .clickable { showHeart = true }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                app.zhiyi.memory.Heart.moodLabel(hState),
                                style = Ty.caption, color = T.accent, maxLines = 1,
                            )
                        }
                    }
                    Text(
                        when {
                            vm.activeProvider == null -> "还没接上模型"
                            vm.sending -> "正在说…"
                            vm.statusLine.isNotBlank() -> vm.statusLine
                            else -> vm.activeProvider?.chatModel ?: ""
                        },
                        style = Ty.caption, color = T.textFaint, maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            // 自动朗读开关。开着 = 她一说就念；关掉 = 立刻截断，不等这句念完
            val autoOn = vm.cfg.ttsAuto
            val autoSrc = remember { MutableInteractionSource() }
            Box(
                Modifier.size(D.touch).clip(CircleShape)
                    .clickable(autoSrc, indication = null) {
                        val on = !autoOn
                        vm.saveCfg(vm.cfg.copy(ttsAuto = on))
                        if (!on) MimoTts.stop()
                    }
                    .pressScale(autoSrc),
                contentAlignment = Alignment.Center,
            ) {
                GlyphSpeaker(if (autoOn) T.accent else T.textFaint, 17.dp)
            }
        }

        if (vm.activeProvider == null) {
            Box(Modifier.padding(horizontal = D.s5, vertical = D.s2)) {
                GlassCard(Modifier.fillMaxWidth(), onClick = onOpenSettings) {
                    Column(Modifier.padding(D.s4)) {
                        Text("先接一个 API", style = Ty.title3, color = T.text)
                        Spacer(Modifier.height(D.s1))
                        Text("点这里填 DeepSeek / 硅基流动 / Kimi 的 Key。", style = Ty.bodySm, color = T.textDim)
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = D.s5, vertical = D.s3),
                verticalArrangement = Arrangement.spacedBy(D.s2),
            ) {
                items(messages, key = { it.id }, contentType = { m ->
                    when {
                        isStickerMessage(m.content) -> "sticker"
                        isEmojiOnly(m.content) -> "emoji"
                        m.role == "user" -> "user"
                        else -> "assistant"
                    }
                }) { m ->
                    // 同一条消息的多段气泡共享同一个动画开关：一起滑入，
                    // 否则第一段播完把 id 记入 played，后面的段瞬间出现 = 错位（真踩过）
                    val msgNew = isNewMsg(m.id, messages, playedIds)
                    val markPlayed: () -> Unit = { playedIds.add(m.id) }
                    when {
                        isStickerMessage(m.content) -> StickerBubble(
                            m.content.removePrefix(IMG_PREFIX), mine = m.role == "user", animateIn = msgNew,
                        )
                        isEmojiOnly(m.content) -> EmojiBubble(
                            m.content, mine = m.role == "user", onLongPress = { picked = m }, animateIn = msgNew,
                        )
                        m.role == "user" -> {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (m.imageList().isNotEmpty()) ImageMsg(m.imageList(), mine = true)
                                // 他自己写的旁白（动作/补充说明）抽出来单独成条，不混进气泡
                                splitUserNarration(m.content).forEach { seg ->
                                    if (seg.narration) {
                                        Narration(seg.text, animateIn = msgNew)
                                    } else {
                                        Bubble(seg.text, mine = true, onLongPress = { picked = m },
                                            animateIn = msgNew, onAnimated = markPlayed)
                                    }
                                }
                            }
                        }
                        else -> {
                            // 台词进气泡，动作/环境单独成条（旁白）
                            val segs = splitNarration(m.content, p.sayPrefix, p.saySuffix)
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                segs.forEach { seg ->
                                    if (seg.narration) Narration(seg.text, animateIn = msgNew)
                                    else Bubble(seg.text, mine = false, onLongPress = { picked = m },
                                        animateIn = msgNew, onAnimated = markPlayed)
                                }
                                if (m.content.isNotBlank()) SpeakerButton(m.content, m.pid)
                            }
                        }
                    }
                }
                if (vm.sending) {
                    item("typing") {
                        if (streaming.isEmpty()) TypingBubble() else Bubble(streaming, mine = false, streaming = true)
                    }
                }
            }

            // 底部渐隐：还能往下翻时才有
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val remaining = (((last?.let { it.offset + it.size } ?: 0) - info.viewportEndOffset).coerceAtLeast(0)).toFloat()
            val fadePx = with(LocalDensity.current) { 88.dp.toPx() }
            val target = (remaining / fadePx).coerceIn(0f, 1f)
            val fadeAlpha by androidx.compose.animation.core.animateFloatAsState(
                target, Mo.effectsDefault(), label = "fade",
            )
            if (fadeAlpha > 0.01f) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(88.dp)
                        .background(Brush.verticalGradient(listOf(T.bg.copy(alpha = 0f), T.bg.copy(alpha = fadeAlpha))))
                )
            }

            if (messages.isEmpty() && !vm.sending) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("先开口吧", style = Ty.bodySm, color = T.textFaint)
                }
            }
        }

        vm.error?.let { e ->
            Box(Modifier.padding(horizontal = D.s5, vertical = D.s1)) {
                GlassCard(
                    Modifier.fillMaxWidth(), border = T.dangerTint,
                    fill = Brush.verticalGradient(listOf(T.dangerTint, Color(0x14FF5C5C))),
                ) {
                    Row(Modifier.padding(D.s3), verticalAlignment = Alignment.CenterVertically) {
                        Text(e, style = Ty.caption, color = T.danger, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(D.s2))
                        Text("知道了", style = Ty.caption, color = T.textDim,
                            modifier = Modifier.clickable { vm.clearError() })
                    }
                }
            }
        }

        // 待发送图片预览
        if (pendingImages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = D.s3, vertical = D.s1),
                horizontalArrangement = Arrangement.Start,
            ) {
                pendingImages.forEach { name ->
                    PendingImageThumb(name) { pendingImages = pendingImages - name }
                }
            }
        }

        Composer(
            value = draftVal,
            onValue = { draftVal = it; vm.draft = it.text },
            canSend = (stripEmptyNarration(vm.draft).isNotBlank() || pendingImages.isNotEmpty()) && !vm.sending,
            onSend = {
                val t = stripEmptyNarration(vm.draft)
                val imgs = pendingImages
                vm.draft = ""
                pendingImages = emptyList()
                if (t.isNotBlank() || imgs.isNotEmpty()) vm.send(t, imgs)
            },
            onMore = { showMore = true },
        )
        Spacer(Modifier.height(D.s2))
    }

    if (showSwitch) PersonaSwitchSheet(vm) { showSwitch = false }

    // 她的心：从顶部心情胶囊点开。画成雷达图，不再把九个数字摊开列
    if (showHeart) {
        val st = remember(p.id, messages.size) { app.zhiyi.memory.Heart.load(vm.db, p.id) }
        val hAxes = remember(p.id, messages.size) { app.zhiyi.memory.Heart.axes(vm.db, p.id) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHeart = false },
            text = {
                HeartCard(
                    axes = hAxes,
                    mood = app.zhiyi.memory.Heart.moodLabel(st),
                    tendency = app.zhiyi.memory.Heart.tendency(st),
                    thought = st.thought,
                    compact = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showHeart = false }) { Text("知道了") }
            },
        )
    }

    picked?.let { m ->
        Sheet({ picked = null }) {
            Label("消息")
            Spacer(Modifier.height(D.s2))
            Text(m.content.take(120), style = Ty.bodySm, color = T.textDim, maxLines = 3)
            Spacer(Modifier.height(D.s3))
            InnerDivider(start = 0.dp)
            SettingRow("复制", "复制这条文字", onClick = { vm.copyText(m.content); picked = null })
            if (m.role == "assistant") {
                InnerDivider()
                SettingRow("重新生成", "删掉这条，让她重说", onClick = { vm.regenerate(m); picked = null })
            }
            InnerDivider()
            SettingRow(
                if (m.role == "user") "撤回" else "删除这条",
                if (m.role == "user") "连同它的回复一起删掉" else "只删这一条助手消息",
                danger = true,
                onClick = { vm.recall(m); picked = null },
            )
        }
    }

    if (showMore) {
        Sheet({ showMore = false }) {
            Text("发送", style = Ty.title2, color = T.text)
            Spacer(Modifier.height(D.s3))
            SettingRow("表情", "发一个表情", onClick = { showMore = false; showEmoji = true })
            InnerDivider(start = 0.dp)
            SettingRow("图片", "发一张照片给她看", onClick = { showMore = false; pickImages.launch("image/*") })
        }
    }

    if (showEmoji) {
        Sheet({ showEmoji = false }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("表情", style = Ty.title2, color = T.text, modifier = Modifier.weight(1f))
                Text(
                    "设置里管理", style = Ty.caption, color = T.textDim,
                    modifier = Modifier.clickable { showEmoji = false; onOpenSettings() }.padding(D.s2),
                )
            }
            Spacer(Modifier.height(D.s3))
            Segmented(listOf("内置", "我的表情"), emojiTab, { emojiTab = it })
            Spacer(Modifier.height(D.s3))
            if (emojiTab == 0) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(D.s2),
                ) {
                    items(EMOJIS.size) { i ->
                        val e = EMOJIS[i]
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(D.rSm))
                                .clickable { showEmoji = false; vm.send(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, style = TextStyle(fontSize = 26.sp)) }
                    }
                }
            } else {
                val stickers = StickerStore.list(ctx)
                if (stickers.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(D.s6), contentAlignment = Alignment.Center) {
                        Text("还没有网络表情\n去 设置 → 表情包 → 立即抓取", style = Ty.caption, color = T.textFaint)
                    }
                } else {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(D.s2),
                    ) {
                        items(stickers.size) { i ->
                            val name = stickers[i]
                            val bmp = remember(name) {
                                runCatching {
                                    android.graphics.BitmapFactory.decodeFile(
                                        java.io.File(StickerStore.dir(ctx), name).absolutePath
                                    )
                                }.getOrNull()
                            }
                            Box(
                                Modifier.size(72.dp).clip(RoundedCornerShape(D.rMd)).background(T.hi2)
                                    .pointerInput(name) {
                                        detectTapGestures(
                                            onTap = { showEmoji = false; vm.send(IMG_PREFIX + name) },
                                            onLongPress = {
                                                StickerStore.delete(ctx, name)
                                                vm.toast = "已删除这张表情"
                                                showEmoji = false
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (bmp != null) {
                                    Image(bmp.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Text("坏", style = Ty.caption, color = T.textFaint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 气泡 ----------------

@Composable
private fun ImageMsg(names: List<String>, mine: Boolean) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        names.take(4).forEach { name ->
            val bmp = remember(name) { BitmapFactory.decodeFile(ImageStore.abs(ctx, name).absolutePath) }
            if (bmp != null) {
                Box(
                    Modifier.padding(2.dp).clip(RoundedCornerShape(D.rCard))
                        .border(1.dp, T.stroke, RoundedCornerShape(D.rCard))
                ) {
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier.size(width = 150.dp, height = 200.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingImageThumb(name: String, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(name) { BitmapFactory.decodeFile(ImageStore.abs(ctx, name).absolutePath) }
    if (bmp != null) {
        Box(Modifier.padding(3.dp)) {
            Image(
                bmp.asImageBitmap(), null,
                Modifier.size(72.dp).clip(RoundedCornerShape(D.rMd)),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape)
                    .background(T.danger).clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) { Text("×", style = Ty.caption, color = Color.White) }
        }
    }
}

@Composable
fun Bubble(
    text: String,
    mine: Boolean,
    streaming: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    animateIn: Boolean = false,
    onAnimated: () -> Unit = {},
) {
    val shape = if (mine) RoundedCornerShape(D.rCard, D.rCard, 6.dp, D.rCard)
    else RoundedCornerShape(D.rCard, D.rCard, D.rCard, 6.dp)

    // 新消息从自己那侧轻微滑入（纯位移，不透明渐变——不是"浮现式"）。
    // 关键：LaunchedEffect(Unit) 而不是 (animateIn)——animateIn 在动画中途翻转时
    // 旧协程会被取消，Animatable 停在半路 = 气泡永远贴边（真踩过）。
    val started = remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val ty = remember { androidx.compose.animation.core.Animatable(if (animateIn && !started.value) 8f else 0f) }
    val tx = remember { androidx.compose.animation.core.Animatable(if (animateIn && !started.value) (if (mine) 12f else -12f) else 0f) }
    val ta = remember { androidx.compose.animation.core.Animatable(if (animateIn && !started.value) 0f else 1f) }
    val ts = remember { androidx.compose.animation.core.Animatable(if (animateIn && !started.value) 0.92f else 1f) }
    LaunchedEffect(Unit) {
        if (animateIn && !started.value) {
            started.value = true
            val springSpec = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.85f, stiffness = 700f)
            val j1 = launch { tx.animateTo(0f, springSpec) }
            val j2 = launch { ty.animateTo(0f, springSpec) }
            val j3 = launch { ts.animateTo(1f, springSpec) }
            val j4 = launch { ta.animateTo(1f, tween(150, easing = Emphasized)) }
            j1.join(); j2.join(); j3.join(); j4.join()
            onAnimated()
        }
    }

    Row(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = ta.value
            scaleX = ts.value
            scaleY = ts.value
            translationY = ty.value * density.density
            translationX = tx.value * density.density
        },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .then(
                    if (onLongPress != null && !streaming)
                        Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
                    else Modifier
                )
                .clip(shape)
                .background(
                    if (mine) Brush.horizontalGradient(listOf(T.accent, T.accentDeep))
                    else Brush.verticalGradient(listOf(T.hi1, T.hi2))
                )
                .then(if (mine) Modifier else Modifier.border(1.dp, T.stroke, shape))
                .padding(horizontal = D.s4, vertical = D.s3)
        ) {
            Column {
                Text(text, style = Ty.body, color = if (mine) T.onAccent else T.text)
                if (streaming) {
                    Spacer(Modifier.height(D.s1))
                    TypingDots(T.accent)
                }
            }
        }
    }
}

/**
 * 只出现在她的消息下面：点一下用克隆的声音念出来，再点一下停。
 *
 * 单独一个小喇叭而不是长按菜单，是因为「听一遍」是高频动作，
 * 埋在长按里每次都要两秒。
 */
@Composable
private fun SpeakerButton(raw: String, pid: String) {
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    // 按「这一条」判断，不能用全局 playing —— 否则点一条所有喇叭都亮，
    // 在亮着的那条上点一下还会变成「停止别人」，表现成点了没反应
    // 显式读一次 playingKey 来触发重组——只靠 isPlaying() 内部读 State 在个别情况下
    // 不会让这个 Composable 重新执行，表现就是「播完了按钮还亮着」「点它没反应」
    val pk = MimoTts.playingKey
    val playing = pk != null && MimoTts.isPlaying(pid, raw)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape)
                .background(if (playing) T.accent.copy(alpha = 0.14f) else Color.Transparent)
                .clickable {
                    if (playing) {
                        MimoTts.stop()
                    } else {
                        MimoTts.speak(ctx, pid, raw) { e ->
                            if (e != null) err = e
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            GlyphSpeaker(if (playing) T.accent else T.textFaint, 15.dp)
        }
    }
    if (err != null) {
        LaunchedEffect(err) {
            android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_SHORT).show()
            err = null
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.clip(RoundedCornerShape(D.rCard, D.rCard, D.rCard, 6.dp))
                .background(Brush.verticalGradient(listOf(T.hi1, T.hi2)))
                .border(1.dp, T.stroke, RoundedCornerShape(D.rCard, D.rCard, D.rCard, 6.dp))
                .padding(horizontal = D.s4, vertical = D.s4)
        ) { TypingDots() }
    }
}

@Composable
private fun Narration(text: String, animateIn: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().then(if (animateIn) Modifier.popIn() else Modifier),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = Ty.caption.copy(fontStyle = FontStyle.Italic, lineHeight = 20.sp),
            color = T.textFaint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = D.s4),
        )
    }
}

@Composable
private fun EmojiBubble(text: String, mine: Boolean, onLongPress: (() -> Unit)? = null, animateIn: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = D.s1).then(if (animateIn) Modifier.popIn() else Modifier),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 40.sp, lineHeight = 46.sp),
            modifier = Modifier
                .then(
                    if (onLongPress != null)
                        Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
                    else Modifier
                )
                .padding(horizontal = D.s2),
        )
    }
}

@Composable
private fun StickerBubble(name: String, mine: Boolean, animateIn: Boolean = false) {
    val ctx = LocalContext.current
    val bmp = remember(name) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(
                java.io.File(StickerStore.dir(ctx), name).absolutePath
            )
        }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = D.s1).then(if (animateIn) Modifier.popIn() else Modifier),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), null,
                modifier = Modifier.size(110.dp).clip(RoundedCornerShape(D.rMd)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("[表情不存在]", style = Ty.caption, color = T.textFaint)
        }
    }
}

@Composable
private fun Composer(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var tlr by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 旁白区间由文本本身推导（成对的（）），不需要额外状态，用户直接删括号也能改回正文
    val narrs = remember(value.text) { narrationRanges(value.text) }

    // 在光标处插一对括号，光标落在中间 —— 之后打的字天然落在括号内 = 旁白
    val insertNarration: () -> Unit = {
        val t = value.text
        val a0 = value.selection.min.coerceIn(0, t.length)
        val b0 = value.selection.max.coerceIn(0, t.length)
        onValue(TextFieldValue(t.replaceRange(a0, b0, "（）"), TextRange(a0 + 1)))
        focusRequester.requestFocus()
    }

    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = D.s3, vertical = D.s2),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (onMore != null) {
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(src, indication = null, onClick = onMore).pressScale(src),
                contentAlignment = Alignment.Center,
            ) { Text("＋", style = Ty.title2, color = T.textDim) }
            Spacer(Modifier.width(D.s1))
        }

        // 旁白按钮：插一对圆角括号，光标自动进去
        val narSrc = remember { MutableInteractionSource() }
        Box(
            Modifier.size(D.touch).clip(CircleShape)
                .background(if (focused) T.hi2 else Color.Transparent)
                .clickable(narSrc, indication = null) { insertNarration() }
                .pressScale(narSrc, 0.94f),
            contentAlignment = Alignment.Center,
        ) {
            GlyphNarration(T.textDim, 18.dp)
        }
        Spacer(Modifier.width(D.s1))

        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(D.rSheet))
                .background(Brush.verticalGradient(listOf(T.hi1, T.hi2)))
                .border(1.dp, if (focused) T.accent.copy(alpha = 0.5f) else T.stroke, RoundedCornerShape(D.rSheet))
                .padding(horizontal = D.s5, vertical = D.s3)
                // 在文字底下描出旁白块：括号范围内的字被一个圆角矩形包住
                .drawBehind {
                    val lr = tlr ?: return@drawBehind
                    if (narrs.isEmpty()) return@drawBehind
                    val total = lr.layoutInput.text.length
                    val padX = 2.dp.toPx()
                    val radius = CornerRadius(6.dp.toPx())
                    for (r in narrs) {
                        val st = r.first.coerceIn(0, total)
                        val en = (r.last + 1).coerceIn(0, total)
                        if (en <= st) continue
                        val l0 = lr.getLineForOffset(st)
                        val l1 = lr.getLineForOffset((en - 1).coerceAtLeast(0))
                        for (li in l0..l1) {
                            val ls = maxOf(st, lr.getLineStart(li))
                            val le = minOf(en, lr.getLineEnd(li, visibleEnd = false))
                            if (le <= ls) continue
                            val left = lr.getBoundingBox(ls).left
                            val right = lr.getBoundingBox(le - 1).right
                            val top = lr.getLineTop(li) + 1.dp.toPx()
                            val bot = lr.getLineBottom(li) - 1.dp.toPx()
                            val tl2 = Offset(left - padX, top)
                            val sz = Size(right - left + padX * 2f, (bot - top).coerceAtLeast(1f))
                            drawRoundRect(T.accent.copy(alpha = 0.13f), tl2, sz, radius)
                            drawRoundRect(
                                T.accent.copy(alpha = 0.28f), tl2, sz, radius,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                            )
                        }
                    }
                }
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = Ty.body.copy(color = T.text),
                cursorBrush = SolidColor(T.accent),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                onTextLayout = { if (it != tlr) tlr = it },
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    if (value.text.isEmpty()) Text("说点什么…", style = Ty.body, color = T.textFaint)
                    inner()
                },
            )
        }
        Spacer(Modifier.width(D.s2))
        val sendSrc = remember { MutableInteractionSource() }
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(
                    if (canSend) Brush.horizontalGradient(listOf(T.accent, T.accentDeep))
                    else Brush.horizontalGradient(listOf(T.hi1, T.hi2))
                )
                .then(if (canSend) Modifier else Modifier.border(1.dp, T.stroke, CircleShape))
                .clickable(sendSrc, indication = null, enabled = canSend) { onSend() }
                .pressScale(sendSrc, 0.92f),
            contentAlignment = Alignment.Center,
        ) { GlyphSend(if (canSend) T.onAccent else T.textFaint) }
    }
}

// ---------------- 辅助 ----------------

private data class Seg(val narration: Boolean, val text: String)

/** 输入框里所有「（…）」区间（含括号本身）。未闭合的括号一路算到末尾——用户可能还在打 */
private fun narrationRanges(t: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var i = 0
    while (i < t.length) {
        val ch = t[i]
        if (ch == '（' || ch == '(') {
            val close = if (ch == '（') '）' else ')'
            val j = t.indexOf(close, i + 1)
            if (j < 0) {
                out.add(i until t.length)
                break
            }
            out.add(i until (j + 1))
            i = j + 1
        } else {
            i++
        }
    }
    return out
}

/** 发送前清掉只插了括号没填内容的空旁白 */
private fun stripEmptyNarration(t: String): String =
    t.replace(Regex("[（(]\\s*[）)]"), "").trim()

/** 把用户写的一条消息切成「正文 / 旁白」若干段 */
private fun splitUserNarration(raw: String): List<Seg> {
    val out = ArrayList<Seg>()
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '（' || ch == '(') {
            val close = if (ch == '（') '）' else ')'
            val j = raw.indexOf(close, i + 1)
            val end = if (j < 0) raw.length else j
            val body = raw.substring(i + 1, end).trim()
            if (body.isNotEmpty()) out.add(Seg(true, body))
            i = if (j < 0) raw.length else j + 1
        } else {
            var k = i
            while (k < raw.length && raw[k] != '（' && raw[k] != '(') k++
            val body = raw.substring(i, k).trim()
            if (body.isNotEmpty()) out.add(Seg(false, body))
            i = k
        }
    }
    if (out.isEmpty()) out.add(Seg(false, raw))
    return out
}

/**
 * 把一条回复切成「旁白 / 台词」。
 *
 * 这里出过一次大错：原来引号外那段交给 parsePlainNarration，而那个函数把
 * **没有括号的文字一律当台词**——于是「她抬眼「嗯。」」里的"她抬眼"被渲染成
 * 一个气泡，用户看到的全是对话、几乎没旁白。
 *
 * 现在的规则：
 *   引号内的 = 台词；引号外的 = 旁白。
 *   一句引号都没有时，才退化成"括号/星号里是旁白，其余是台词"。
 *
 * 引号认五种，因为模型不保证守哪套：自定义标记、「」、『』、“”、""、‘’
 */
private val OPEN_TO_CLOSE = mapOf(
    '「' to '」', '『' to '』',
    '“' to '”', '"' to '"', '‘' to '’',
)

private fun splitNarration(raw: String, prefix: String, suffix: String): List<Seg> {
    val p0 = prefix.trim().ifBlank { "「" }
    val s0 = suffix.trim().ifBlank { "」" }
    val out = ArrayList<Seg>()
    fun say(t: String) {
        val v = t.trim()
        if (v.isNotEmpty()) out.add(Seg(false, v))
    }
    fun outside(t: String) {
        val v = stripInlineMarks(t)
        if (v.isNotEmpty()) out.add(Seg(true, v))
    }

    var i = 0
    var segStart = 0          // 上一段旁白的起点
    var sawQuote = false
    while (i < raw.length) {
        val ch = raw[i]
        val close: Char? = when {
            p0.length == 1 && s0.length == 1 && ch == p0[0] -> s0[0]
            else -> OPEN_TO_CLOSE[ch]
        }
        if (close == null) { i++; continue }
        val j = raw.indexOf(close, i + 1)
        if (j <= i + 1) { i++; continue }        // 空引号 / 没闭合：当普通字符跳过
        sawQuote = true
        outside(raw.substring(segStart, i))      // 引号前 → 旁白
        say(raw.substring(i + 1, j))             // 引号内 → 台词
        i = j + 1
        segStart = i
    }
    if (!sawQuote) {
        // 没引号但写了冒号：「她抬眼看你：嗯，知道了。」
        // 模型经常这么写——冒号前是旁白，后面才是她说的话
        val ci = maxOf(raw.lastIndexOf('：'), raw.lastIndexOf(':'))
        if (ci in 1 until raw.length - 1) {
            val left = raw.substring(0, ci)
            val right = raw.substring(ci + 1)
            if (left.count { it.isLetter() } >= 2 && right.any { it.isLetter() }) {
                val l = stripInlineMarks(left)
                if (l.isNotEmpty()) out.add(Seg(true, l))
                parsePlainNarration(right).forEach { out.add(it) }
                if (out.isNotEmpty()) return out
            }
        }
        val plain = parsePlainNarration(raw)
        return plain.ifEmpty { listOf(Seg(false, raw)) }
    }
    outside(raw.substring(segStart))             // 最后一段尾巴 → 旁白
    return out.ifEmpty { listOf(Seg(false, raw)) }
}

/**
 * 剥掉旁白外层包裹的符号，以及残留的单边符号。
 *
 * 模型写旁白的写法实测有五六种，编进 prompt 也堵不住，只能在渲染前统一收一次：
 *   （她抬眼）  (她抬眼)  【她抬眼】  *她抬眼*  **她抬眼**  （（她抬眼））
 * 都收成「她抬眼」——界面上一个括号都不许露出来。
 */
private fun cleanNarration(raw: String): String {
    var t = raw.trim()
    val pairs = listOf(
        "（" to "）", "(" to ")",
        "【" to "】", "[" to "]",
        "*" to "*", "_" to "_",
    )
    var guard = 0
    while (guard++ < 4) {
        val hit = pairs.firstOrNull { (l, r) ->
            t.length > l.length + r.length && t.startsWith(l) && t.endsWith(r)
        } ?: break
        t = t.substring(hit.first.length, t.length - hit.second.length).trim()
    }
    t = t.replace(Regex("^[*_]+"), "").replace(Regex("[*_]+$"), "")
    t = t.replace("*", "")          // 夹在中间的星号同样是标记（（她笑*了*））
    t = t.trim { c ->
        c == '（' || c == '）' || c == '(' || c == ')' || c == '*' || c == '_' ||
            c == '【' || c == '】' || c == '[' || c == ']' || c == '~'
    }
    return t.trim()
}

/**
 * 把一段「没有引号」的文本按旁白符号切开。
 * 识别 （…） (…) 【…】 *…* ；切出的旁白一律剥掉包裹符。
 * 星号只认 120 字以内的成对，避免把正文里的乘法号/列表符吃掉。
 *
 * 只在**整条一个引号都没有**时当兜底用：那种情况下，括号里是旁白，括号外只好算台词。
 */
private fun parsePlainNarration(s: String): List<Seg> {
    if (s.isBlank()) return emptyList()
    val out = ArrayList<Seg>()
    var i = 0
    val n = s.length
    while (i < n) {
        val ch = s[i]
        val close = when (ch) {
            '（' -> '）'
            '(' -> ')'
            '【' -> '】'
            '*' -> '*'
            else -> null
        }
        if (close != null) {
            val j = s.indexOf(close, i + 1)
            val ok = j > i + 1 && (close != '*' || j - i <= 120)
            if (ok) {
                val body = cleanNarration(s.substring(i, j + 1))
                if (body.isNotEmpty()) out.add(Seg(true, body))
                i = j + 1
                continue
            }
            if (close != '*') {
                // 括号没闭合：一路吃到末尾当旁白，同样剥掉开头那个
                val body = cleanNarration(s.substring(i))
                if (body.isNotEmpty()) out.add(Seg(true, body))
                break
            }
        }
        var k = i
        while (k < n && s[k] != '（' && s[k] != '(' && s[k] != '【' && s[k] != '*') k++
        val body = s.substring(i, k).trim()
        // 只剩括号/符号的残片（「（（A））」剥完留下的那个「）」）直接丢，别当台词发出去
        if (body.any { it.isLetterOrDigit() || it == '…' || it == '—' }) out.add(Seg(false, body))
        i = if (k == i) i + 1 else k
    }
    return out
}

/**
 * 引号外的整段都是旁白——**整段一个 Seg**，不再按括号切。
 * 按括号切会把「她抬眼，又低下去」拆成好几行居中提示，反而更碎。
 * 这里只把公式化的标记剥掉：既有括号的边界情况在 cleanNarration 里处理。
 *
 * `（她抬眼）又低下去（笑了笑）` → `她抬眼 又低下去 笑了笑`
 */
private fun stripInlineMarks(s: String): String {
    var t = s
    t = t.replace(Regex("[（(]([^（()）]*)[）)]"), " $1 ")
    t = t.replace(Regex("【([^】]*)】"), " $1 ")
    // 先去粗体符号再处理单星号，否则 **粗体** 会被拆成 "* 粗体 *"
    t = t.replace("**", "")
    t = t.replace(Regex("\\*([^*\\n]+)\\*"), " $1 ")   // *动作*
    t = t.replace(Regex("[ \t]+"), " ")
    // 多行保留换行，但把行首行尾的空格收掉
    t = t.split("\n").joinToString("\n") { it.trim() }
    return cleanNarration(t)
}

private fun isEmojiOnly(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty() || t.length > 16) return false
    var i = 0
    var count = 0
    while (i < t.length) {
        val cp = t.codePointAt(i)
        if (Character.isWhitespace(cp)) { i += Character.charCount(cp); continue }
        val isEmoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
            cp in 0x2190..0x21FF || cp in 0x2B00..0x2BFF ||
            cp in 0xFE00..0xFE0F || cp == 0x200D
        if (!isEmoji) return false
        count++
        i += Character.charCount(cp)
    }
    return count in 1..8
}

private val EMOJIS = listOf(
    "😀", "😄", "😅", "😂", "🙂", "😊", "😍", "🥰",
    "😘", "😏", "😴", "😭", "😤", "😡", "🤔", "😳",
    "😱", "🙄", "🤗", "😮", "💤", "👍", "👎", "👏",
    "🙏", "👋", "❤️", "💔", "✨", "🎉", "🔥", "🌸",
)

@Composable
private fun PersonaSwitchSheet(vm: AppVm, onClose: () -> Unit) {
    Sheet(onClose) {
        Text("切换人格", style = Ty.title2, color = T.text)
        Spacer(Modifier.height(D.s2))
        Text("每个人格有自己的对话和记忆，切过去就像换了个人。", style = Ty.caption, color = T.textFaint)
        Spacer(Modifier.height(D.s3))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(vm.personas, key = { it.id }) { ps ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.switchPersona(ps.id); onClose() }
                        .padding(vertical = D.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(T.accent, T.purple))),
                        contentAlignment = Alignment.Center,
                    ) { Text(ps.name.take(1), style = Ty.caption, color = T.onAccent) }
                    Spacer(Modifier.width(D.s3))
                    Column(Modifier.weight(1f)) {
                        Text(ps.name, style = Ty.bodySm, color = T.text, maxLines = 1)
                        Text(
                            ps.role.ifBlank { ps.summary.take(20) },
                            style = Ty.caption, color = T.textFaint, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (ps.id == vm.persona?.id) Text("当前", style = Ty.caption, color = T.accent)
                }
                InnerDivider(start = 0.dp)
            }
        }
        Spacer(Modifier.height(D.s4))
        PillButton("关闭", onClose, Modifier.fillMaxWidth(), primary = false)
    }
}

@Composable
private fun NoPersonaScreen(vm: AppVm, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(T.bg)) {
        GlassTopBar(title = "知忆", subtitle = "还没有人格")
        Box(Modifier.weight(1f).padding(horizontal = D.s5, vertical = D.s4)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(D.s5)) {
                    Text("先给她一个身份", style = Ty.title3, color = T.text)
                    Spacer(Modifier.height(D.s2))
                    Text("可以手工写一个人格，也可以恢复两个示例。", style = Ty.bodySm, color = T.textDim)
                    Spacer(Modifier.height(D.s4))
                    PillButton("恢复内置人格", { vm.restoreBuiltins() }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(D.s2))
                    PillButton("去设置里新建", onOpenSettings, Modifier.fillMaxWidth(), primary = false)
                }
            }
        }
        Spacer(Modifier.height(100.dp))
    }
}

/** 判断这条消息是否"刚出现"：是最后一条、且没播过（纯查询，副作用在动画完成后） */
private fun isNewMsg(id: Long, messages: List<Msg>, played: List<Long>): Boolean =
    id == messages.lastOrNull()?.id && id !in played
