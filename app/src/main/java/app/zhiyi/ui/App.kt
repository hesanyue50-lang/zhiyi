package app.zhiyi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import app.zhiyi.AppVm

/** 子页面：进入右滑入、返回反向右滑出 */
private fun isSubKey(k: String?) =
    (k ?: "").startsWith("chat:") || (k ?: "").startsWith("world:") || (k ?: "").startsWith("voice:")

@Composable
fun ZhiYiApp(vm: AppVm) {
    var sub by remember { mutableStateOf<String?>(null) }   // "world:pid" / "chat:pid"
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tab = pagerState.currentPage

    // ---- 主题 ----
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (vm.cfg.theme) {
        "dark" -> true
        "light" -> false
        else -> sysDark
    }
    androidx.compose.runtime.LaunchedEffect(dark) { T.apply(dark) }

    val view = androidx.compose.ui.platform.LocalView.current

    // 弹层已经画在根 Box，遮罩天然铺满全屏，不需要再隐藏系统栏了。
    // 这里只维护状态栏图标的明暗（浅色主题下要黑图标）。dark 不变就不跑。
    androidx.compose.runtime.LaunchedEffect(dark) {
        val act = view.context as? android.app.Activity
        val w = act?.window
        if (w != null) {
            val c = androidx.core.view.WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    // 从别的 App 分享过来的文字：切到对话页直接发给她
    androidx.compose.runtime.LaunchedEffect(vm.pendingShare) {
        val t = vm.pendingShare
        if (t != null) {
            vm.pendingShare = null
            val pid = app.zhiyi.core.ChatCore.activePersona(view.context).id
            if (pid.isNotBlank()) {
                vm.switchPersona(pid)
                sub = "chat:$pid"
                vm.send(t)
            }
        }
    }

    // 返回键：先弹子页面，再回对话页（第 0 页），最后才让系统退出
    BackHandler(enabled = sub != null || tab != 0) {
        if (sub != null) sub = null else scope.launch { pagerState.animateScrollToPage(0) }
    }

    Box(Modifier.fillMaxSize().background(T.bg)) {
        AuroraBackground()

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                // 主界面三页：左右滑切换（跟手拖动，参考 lanmesh PanelPager）。一直在，不随 sub 重建。
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 0,   // 不预加载相邻页：设置页/记忆页都很重，常驻三页会拖慢滑动
                ) { page ->
                    when (page) {
                        0 -> PersonaListScreen(
                            vm,
                            onOpenChat = { pid -> vm.switchPersona(pid); sub = "chat:$pid" },
                            onOpenSettings = { scope.launch { pagerState.animateScrollToPage(3) } },
                            onOpenProfile = { pid -> vm.switchPersona(pid); sub = "profile:$pid" },
                        )
                        1 -> MemoryScreen(vm)
                        2 -> CompanionScreen(
                            vm,
                            onOpenPetSettings = { scope.launch { pagerState.animateScrollToPage(3) } },
                            onOpenQq = { sub = "qq" },
                            onOpenProactive = { scope.launch { pagerState.animateScrollToPage(3) } },
                        )
                        else -> SettingsScreen(vm, onOpenWorld = { pid -> sub = "world:$pid" }, onOpenClean = { sub = "clean" }, onOpenQq = { sub = "qq" })
                    }
                }

            }
        }

        // 子页面：铺满全屏（含状态栏），内部自己加 statusBarsPadding（否则状态栏露主界面背景，不沉浸）
        androidx.compose.animation.AnimatedVisibility(
            visible = sub != null,
            modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 650f)) { it / 8 } +
                fadeIn(tween(220, easing = Emphasized)) +
                scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.85f, stiffness = 650f)),
            exit = slideOutHorizontally(tween(220, easing = EmphasizedAccel)) { it / 8 } +
                fadeOut(tween(180, easing = EmphasizedAccel)) +
                scaleOut(targetScale = 0.95f, animationSpec = tween(220, easing = EmphasizedAccel)),
        ) {
            // 保存退出前的 sub：否则 sub 变 null 时 content 立即变空，退出动画看不到
            val saved = remember { mutableStateOf(sub) }
            if (sub != null) saved.value = sub
            val k = saved.value
            when {
                k?.startsWith("chat:") == true -> ChatScreen(
                    vm,
                    onBack = { sub = null },
                    onOpenSettings = { sub = null; scope.launch { pagerState.animateScrollToPage(2) } },
                )
                k?.startsWith("world:") == true -> WorldScreen(vm, k.removePrefix("world:"), onBack = { sub = null })
                k?.startsWith("profile:") == true -> ProfileScreen(vm, onBack = { sub = null })
                k == "clean" -> CleanScreen(vm, onBack = { sub = null })
                k == "qq" -> QqScreen(vm, onBack = { sub = null })
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = sub == null && UiLayer.modals == 0,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(220, easing = Emphasized)
            ) + androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(260, easing = Emphasized)
            ) { it / 2 },
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(120, easing = EmphasizedAccel)
            ),
        ) {
            TabBar(
                current = pagerState.targetPage,
                onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )
        }

        // 弹层画在根 Box：这里没有 statusBarsPadding，遮罩能压住整屏
        UiLayer.sheetContent?.invoke()

        vm.toast?.let { t -> Toast(t) { vm.toast = null } }
    }
}

/** 弹层计数：任何一个底部抽屉打开时，悬浮标签栏让位 */
object UiLayer {
    var modals by mutableStateOf(0)

    /** 当前弹层：在根 Box 里渲染，遮罩才能铺满全屏（含状态栏区域） */
    var sheetContent: (@Composable () -> Unit)? by mutableStateOf(null)
}

/** 背景：两层极光径向渐变，避免纯色死板 */
@Composable
fun AuroraBackground() {
    // 两个径向渐变 Brush 用 remember 缓存，避免每次重组重建（radialGradient 绘制贵）
    val glowABrush = remember(T.glowA) {
        Brush.radialGradient(
            colors = listOf(T.glowA, Color(0x00000000)),
            center = Offset(120f, -80f), radius = 900f,
        )
    }
    val glowBBrush = remember(T.glowB) {
        Brush.radialGradient(
            colors = listOf(T.glowB, Color(0x00000000)),
            center = Offset(820f, 1600f), radius = 1000f,
        )
    }
    Box(Modifier.fillMaxSize().background(glowABrush))
    Box(Modifier.fillMaxSize().background(glowBBrush))
}

@Composable
fun TabBar(current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(60.dp)
            .clip(CircleShape)
            .background(T.glass)
            .border(1.dp, T.stroke, CircleShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem("对话", current == 0, { onSelect(0) }) { c, s -> GlyphChat(c, s) }
        TabItem("记忆", current == 1, { onSelect(1) }) { c, s -> GlyphMemory(c, s) }
        TabItem("陪伴", current == 2, { onSelect(2) }) { c, s -> GlyphHeart(c, s) }
        TabItem("设置", current == 3, { onSelect(3) }) { c, s -> GlyphGear(c, s) }
    }
}

@Composable
private fun TabItem(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    glyph: @Composable (Color, androidx.compose.ui.unit.Dp) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val a by animateFloatAsState(if (active) 1f else 0f, Mo.effectsDefault(), label = "tb")
    Row(
        Modifier
            .clip(CircleShape)
            .background(T.accent.copy(alpha = 0.16f * a))
            .clickable(src, indication = null, onClick = onClick)
            .pressScale(src, 0.94f)
            .height(44.dp)
            .animateContentSize(Mo.effectsFast())
            .padding(horizontal = if (active) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph(if (active) T.accent else T.textDim, 18.dp)
        if (active) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = Ty.bodySm, color = T.accent)
        }
    }
}

@Composable
fun GlyphChat(c: Color, s: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(s)) {
        val w = size.width; val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = w * 0.06f, top = h * 0.12f, right = w * 0.94f, bottom = h * 0.74f,
                    radiusX = w * 0.22f, radiusY = w * 0.22f,
                )
            )
        }
        drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f))
        drawLine(c, Offset(w * 0.28f, h * 0.76f), Offset(w * 0.24f, h * 0.95f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
    }
}

@Composable
fun GlyphMemory(c: Color, s: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(s)) {
        val w = size.width; val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(w * 0.06f, h * 0.3f, w * 0.66f, h * 0.9f))
        }
        drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f))
        drawLine(c, Offset(w * 0.62f, h * 0.28f), Offset(w * 0.94f, h * 0.28f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
        drawLine(c, Offset(w * 0.72f, h * 0.12f), Offset(w * 0.94f, h * 0.12f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
    }
}

@Composable
fun GlyphHeart(c: Color, s: androidx.compose.ui.unit.Dp) {
    // 和 Chat / Memory / Gear 统一：全部走描边，线宽 0.1w，圆头圆角。
    // 实心会在四个空心图标里跳出来，看着像另一套图标集。
    androidx.compose.foundation.Canvas(Modifier.size(s)) {
        val w = size.width; val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.50f, h * 0.88f)
            cubicTo(w * 0.10f, h * 0.60f, w * 0.06f, h * 0.30f, w * 0.29f, h * 0.24f)
            cubicTo(w * 0.42f, h * 0.21f, w * 0.50f, h * 0.33f, w * 0.50f, h * 0.39f)
            cubicTo(w * 0.50f, h * 0.33f, w * 0.58f, h * 0.21f, w * 0.71f, h * 0.24f)
            cubicTo(w * 0.94f, h * 0.30f, w * 0.90f, h * 0.60f, w * 0.50f, h * 0.88f)
            close()
        }
        drawPath(
            path, c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.10f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
        )
    }
}

@Composable
fun GlyphGear(c: Color, s: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(s)) {
        val w = size.width; val h = size.height
        val cx = w / 2; val cy = h / 2
        drawCircle(c, radius = w * 0.3f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f))
        repeat(8) { i ->
            val ang = (i * 45) * Math.PI / 180
            val r1 = w * 0.36f; val r2 = w * 0.48f
            drawLine(
                c,
                Offset(cx + (r1 * Math.cos(ang)).toFloat(), cy + (r1 * Math.sin(ang)).toFloat()),
                Offset(cx + (r2 * Math.cos(ang)).toFloat(), cy + (r2 * Math.sin(ang)).toFloat()),
                strokeWidth = w * 0.1f, cap = StrokeCap.Round,
            )
        }
    }
}

/** 底部抽屉：玻璃浮层，下滑关闭 */
@Composable
fun Sheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val close = androidx.compose.runtime.rememberUpdatedState(onClose)
    val body = androidx.compose.runtime.rememberUpdatedState(content)
    // 每次组合都重新注册槽位（SideEffect），而不是只在首次注册：
    // 否则在 Pager 里划去别的页再开别的 Sheet、关掉后，这个 Sheet 的槽位被清空、
    // 划回来却不再注册 → 抽屉永久消失（真踩过）。
    androidx.compose.runtime.SideEffect {
        UiLayer.sheetContent = { SheetSurface({ close.value() }) { body.value(this) } }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { UiLayer.sheetContent = null }
    }
}

/** 真正的抽屉：全屏遮罩 + 底部圆角面板 */
@Composable
private fun SheetSurface(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    androidx.compose.runtime.DisposableEffect(Unit) {
        UiLayer.modals += 1
        onDispose { UiLayer.modals -= 1 }
    }
    val scrim by animateFloatAsState(if (shown) 1f else 0f, tween(180), label = "sc")
    val off by animateDpAsState(if (shown) 0.dp else 900.dp, spring(dampingRatio = 0.85f, stiffness = 650f), label = "sh")
    val sheetScope = rememberCoroutineScope()
    // 关闭时先播退出动画（遮罩淡出 + 面板下滑），再真正移除。否则直接从组合销毁，动画来不及播
    fun requestClose() {
        if (!shown) return
        shown = false
        sheetScope.launch {
            kotlinx.coroutines.delay(190)
            onClose()
        }
    }
    BackHandler { requestClose() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(T.scrim.copy(alpha = T.scrim.alpha * scrim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { requestClose() })
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().offset(y = off)
                .clip(RoundedCornerShape(topStart = D.rSheet, topEnd = D.rSheet))
                .background(T.bgElev)
                .border(1.dp, T.stroke, RoundedCornerShape(topStart = D.rSheet, topEnd = D.rSheet))
                .imePadding()
                .navigationBarsPadding()
                .padding(D.s5)
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(CircleShape).background(T.handle)
            )
            Spacer(Modifier.height(D.s4))
            Box(Modifier.popIn(delayMs = 60, from = 0.985f, riseDp = 6f)) {
                Column(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

@Composable
fun Toast(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(2200)
        onDone()
    }
    Box(Modifier.fillMaxSize().padding(bottom = 100.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.clip(CircleShape).background(T.glass)
                .border(1.dp, T.stroke, CircleShape)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(text, style = Ty.bodySm, color = T.text)
        }
    }
}

/** 顶部玻璃栏 */
@Composable
fun GlassTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    // 需要拆开做动画时（比如「名字」滑、「后缀」不滑）就自己传一份，title 只当无障碍文本用
    titleContent: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp)
            .padding(horizontal = D.s5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.size(D.touch).clip(CircleShape)
                    .clickable(src, indication = null, onClick = onBack).pressScale(src),
                contentAlignment = Alignment.Center,
            ) { GlyphBack(T.textDim) }
            Spacer(Modifier.width(D.s1))
        }
        Column(Modifier.weight(1f)) {
            if (titleContent != null) {
                titleContent()
            } else androidx.compose.animation.AnimatedContent(
                targetState = title,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, easing = Emphasized)) +
                        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220, easing = Emphasized)) { it / 3 })
                        .togetherWith(
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160, easing = EmphasizedAccel)) +
                                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(160, easing = EmphasizedAccel)) { -it / 3 })
                },
                label = "topbarTitle",
            ) { t -> Text(t, style = Ty.title2, color = T.text, maxLines = 1) }
            if (subtitle != null) Text(subtitle, style = Ty.caption, color = T.textFaint, maxLines = 1)
        }
        trailing?.invoke()
    }
}

@Composable
fun GlyphTrash(c: Color, s: androidx.compose.ui.unit.Dp = 18.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(s)) {
        val w = size.width; val h = size.height
        val sw = w * 0.085f
        // 盖子 + 提手
        drawLine(c, Offset(w * 0.36f, h * 0.08f), Offset(w * 0.64f, h * 0.08f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(c, Offset(w * 0.38f, h * 0.2f), Offset(w * 0.38f, h * 0.08f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(c, Offset(w * 0.62f, h * 0.2f), Offset(w * 0.62f, h * 0.08f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(c, Offset(w * 0.18f, h * 0.2f), Offset(w * 0.82f, h * 0.2f), strokeWidth = sw, cap = StrokeCap.Round)
        // 桶身（上窄下宽圆角）
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.28f, h * 0.2f)
            lineTo(w * 0.32f, h * 0.92f)
            lineTo(w * 0.68f, h * 0.92f)
            lineTo(w * 0.72f, h * 0.2f)
        }
        drawPath(body, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // 两条竖纹
        drawLine(c, Offset(w * 0.44f, h * 0.34f), Offset(w * 0.44f, h * 0.78f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(c, Offset(w * 0.56f, h * 0.34f), Offset(w * 0.56f, h * 0.78f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
