package app.zhiyi.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 动效中枢 —— 照 balance-widget 的 Anim.java 那套方法做的。
 *
 * 三条原则（都是被"动效很差"逼出来的）：
 * 1. 时长和曲线只在这里定义，组件里不准写 300ms / ease
 * 2. **能过冲**的只有位移和缩放；高度、透明度、遮罩必须单调曲线，过冲会露馅
 * 3. 按下和松开用不同曲线：按下快进不过冲（110ms），松开弹簧回弹（270ms）——
 *    对称的按压反馈看起来就是"假"
 */
object Du {
    /** 展开/收起（高度） */
    const val EXPAND = 240
    /** 淡入 */
    const val FADE = 260
    /** 整页进场 */
    const val ENTER = 280
    /** 列表错开间隔（M3/头部 App 共识：30~50ms） */
    const val STAGGER = 40
    /** 最多错开 5 项（再多就同批出现，别让人等） */
    const val MAX_STAGGER = 8
    /** 按下反馈：按下要快 */
    const val PRESS_IN = 110
    /** 松开回弹 */
    const val PRESS_OUT = 270
}

/** M3 emphasized decelerate：先快后缓、收尾极稳。用于进场位移与透明度 */
val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 出场：加速离开，比进场快 */
val EmphasizedAccel: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * 按下反馈（替换对称的 pressScale 旧实现）
 * 按下：110ms 到 0.96，不过冲；松开：270ms 弹簧回 1（+9% 过冲）。
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.97f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val s = remember { Animatable(1f) }
    LaunchedEffect(isPressed) {
        if (isPressed) {
            s.animateTo(pressed, tween(Du.PRESS_IN, easing = Emphasized))
        } else {
            s.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 900f))
        }
    }
    return this.graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/**
 * 「落位」出现：透明度 + 轻微缩放 + 上移，走 spatial-default 弹簧（+1.5% 过冲）。
 * 比纯淡入有重量感——视觉上像"落"到位置上，而不是"显"出来。
 */
@Composable
fun Modifier.popIn(delayMs: Int = 0, from: Float = 0.92f, riseDp: Float = 8f): Modifier {
    val k = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        k.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 650f))
    }
    return this.graphicsLayer {
        val v = k.value
        alpha = v.coerceIn(0f, 1f)
        val sc = from + (1f - from) * v
        scaleX = sc; scaleY = sc
        translationY = (1f - v) * riseDp * density
    }
}

/**
 * 列表逐条错开淡入（右侧内容体）。index 越大出现得越晚，超过 12 条同批。
 */
@Composable
fun Modifier.enterStagger(index: Int, riseDp: Float = 8f): Modifier {
    // 记住"已播过"：LazyColumn 回收 item 再重建时不重播（否则滚动会闪）
    var played by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val k = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played) {
            played = true
            delay((min(index, Du.MAX_STAGGER) * Du.STAGGER).toLong())
            k.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 600f))
        }
    }
    return this.graphicsLayer {
        alpha = k.value
        translationY = (1f - k.value) * riseDp * density
    }
}

/** 列表项入场：play=true 时错开淡入。play 由调用方的"已播过"集合控制，滚动回收/切 tab 不重播 */
@Composable
fun Modifier.staggerIn(play: Boolean, index: Int, riseDp: Float = 8f): Modifier {
    val k = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (play) {
            k.snapTo(0f)
            delay((min(index, Du.MAX_STAGGER) * Du.STAGGER).toLong())
            k.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 600f))
        }
    }
    return this.graphicsLayer {
        alpha = k.value
        translationY = (1f - k.value) * riseDp * density
    }
}

/** 数值更新时的"活一下"：放大 2% 再回位。只用于确认"数据变了"，别用在静态元素上 */
@Composable
fun Modifier.pulseOn(value: Any): Modifier {
    val s = remember { Animatable(1f) }
    var last by remember { androidx.compose.runtime.mutableStateOf(value) }
    var first by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(value) {
        if (first) { first = false; last = value; return@LaunchedEffect }
        if (value == last) return@LaunchedEffect
        last = value
        s.snapTo(1.02f)
        s.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f))
    }
    return this.graphicsLayer { scaleX = s.value; scaleY = s.value }
}
