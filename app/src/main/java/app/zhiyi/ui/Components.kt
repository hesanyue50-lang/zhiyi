package app.zhiyi.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 卡片：半透明叠层 + 1px 描边 + 顶边高光（厚度感的全部来源） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = D.rCard,
    border: Color = T.stroke,
    fill: Brush? = null,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val src = remember { MutableInteractionSource() }
    // 默认渐变用 remember 缓存，避免每次重组重建 Brush（卡片多时能省不少）
    val defaultFill = remember(T.cardTop, T.cardBot) { Brush.verticalGradient(listOf(T.cardTop, T.cardBot)) }
    var m = modifier
        .clip(shape)
        .background(fill ?: defaultFill)
        .border(1.dp, border, shape)
    // pressScale 必须挂在链首：graphicsLayer 只作用于它右侧的内层，挂链尾只会缩内容不缩卡片
    when {
        onClick != null && onLongPress != null ->
            m = m.pressScale(src, 0.985f).combinedClickable(src, indication = null, onClick = onClick, onLongClick = onLongPress)
        onClick != null ->
            m = m.pressScale(src, 0.985f).clickable(src, indication = null, onClick = onClick)
    }
    Box(m) { content() }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = T.textFaint) {
    Text(text.uppercase(), style = Ty.label, color = color, modifier = modifier)
}

/** 主按钮：胶囊，一屏最多一个 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    labelStyle: androidx.compose.ui.text.TextStyle = Ty.title3,
) {
    val src = remember { MutableInteractionSource() }
    val bg = when {
        !enabled -> Brush.horizontalGradient(listOf(T.track, T.track))
        danger -> Brush.horizontalGradient(listOf(T.danger, Color(0xFFD93E3E)))
        primary -> Brush.horizontalGradient(listOf(T.accent, T.accentDeep))
        else -> Brush.horizontalGradient(listOf(T.hi2, T.hi2))
    }
    Row(
        modifier
            .pressScale(src)
            .height(height)
            .clip(CircleShape)
            .background(bg)
            .then(
                when {
                    danger -> Modifier
                    primary -> Modifier.border(1.dp, T.accentDeep.copy(alpha = 0.5f), CircleShape)
                    else -> Modifier.border(1.dp, T.handle, CircleShape)   // 更实的边框，能看出是按钮
                }
            )
            .clickable(src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = labelStyle,
            color = if (!enabled) T.textFaint else if (primary || danger) T.onAccent else T.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分段控件：凹槽 + 滑动指示块（位移走 spatial-fast 弹簧） */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val n = options.size.coerceAtLeast(1)
    BoxWithConstraints(modifier.fillMaxWidth().clip(CircleShape).background(T.hi1).padding(2.dp)) {
        val itemW = maxWidth / n
        // 滑动指示块：用「比例」驱动。切换时 fraction 平滑过渡（带过冲）；
        // 容器宽度变化时 itemW 瞬时变、offset 瞬时跟随，不会"没点任何东西滑块自己滑走"。
        val fraction by animateFloatAsState(selected.toFloat(), Mo.slider(), label = "seg")
        Box(
            Modifier
                .offset(x = itemW * fraction)
                .size(width = itemW, height = 34.dp)
                .clip(CircleShape)
                .background(T.hi2)   // 扁平：纯色，不渐变不描边
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                val active = i == selected
                val src = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).height(34.dp)
                        .pressScale(src, 0.97f)
                        .clickable(src, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(o, style = Ty.bodySm, color = if (active) T.text else T.textDim)
                }
            }
        }
    }
}

/** 开关：轨道 52×32，滑块 24，带过冲 */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val t by animateFloatAsState(if (checked) 1f else 0f, Mo.slider(), label = "tg")
    Box(
        modifier.size(52.dp, 32.dp)
            .pressScale(src, 0.94f)
            .clip(CircleShape)
            .background(if (checked) T.accent else T.track)
            .clickable(src, indication = null) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.padding(start = 4.dp).offset(x = (20f * t).dp).size(24.dp)
                .clip(CircleShape).background(Color.White)
        )
    }
}

/** 设置行：图标 30 圆角 8 → 标题/副标题 → 右侧值 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    valueAsButton: Boolean = false,
) {
    val src = remember { MutableInteractionSource() }
    var m = modifier.fillMaxWidth().height(if (subtitle == null) 54.dp else 64.dp)
    if (onClick != null) m = m.pressScale(src, 0.985f).clickable(src, indication = null, onClick = onClick)
    Row(m.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = Ty.bodySm,
                color = if (danger) T.danger else T.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(
                subtitle, style = Ty.caption, color = T.textFaint,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (value != null) {
            if (valueAsButton) {
                Text(
                    value, style = Ty.caption, color = T.accent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(T.accentTint)
                        .border(1.dp, T.accent.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = D.s3, vertical = D.s1),
                )
            } else {
                Text(value, style = Ty.bodySm, color = T.textDim)
            }
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

/** 分隔线内缩到文字起点，不做通栏实线 */
@Composable
fun InnerDivider(modifier: Modifier = Modifier, start: Dp = 16.dp) {
    Box(modifier.fillMaxWidth().padding(start = start).height(1.dp).background(T.strokeSoft))
}

@Composable
fun IconChip(color: Color = T.accentTint, content: @Composable () -> Unit) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(D.rSm)).background(color), contentAlignment = Alignment.Center) {
        content()
    }
}

/** 细进度条（自绘，避开 M3 版本差异） */
@Composable
fun ProgressBar(value: Float, modifier: Modifier = Modifier, color: Color = T.accent, height: Dp = 4.dp) {
    val v by animateFloatAsState(value.coerceIn(0f, 1f), Mo.effectsDefault(), label = "pb")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(T.track)) {
        Box(Modifier.fillMaxWidth(v).height(height).clip(CircleShape).background(color))
    }
}

/** 打字指示器 */
@Composable
fun TypingDots(color: Color = T.textDim) {
    val inf = rememberInfiniteTransition(label = "td")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by inf.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(700, delayMillis = i * 140, easing = LinearEasing),
                    RepeatMode.Reverse,
                ), label = "d$i",
            )
            Box(
                Modifier.padding(horizontal = 2.dp).size(5.dp).clip(CircleShape)
                    .background(color.copy(alpha = a))
            )
        }
    }
}

/** 自绘图标（不用 emoji，统一线宽） */
@Composable
fun GlyphSend(tint: Color, size: Dp = 18.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.08f, h * 0.5f)
            lineTo(w * 0.92f, h * 0.5f)
            moveTo(w * 0.56f, h * 0.16f)
            lineTo(w * 0.92f, h * 0.5f)
            lineTo(w * 0.56f, h * 0.84f)
        }
        drawPath(p, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = w * 0.11f, cap = StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ))
    }
}

@Composable
fun GlyphBack(tint: Color, size: Dp = 20.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.62f, h * 0.2f)
            lineTo(w * 0.3f, h * 0.5f)
            lineTo(w * 0.62f, h * 0.8f)
        }
        drawPath(p, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = w * 0.11f, cap = StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ))
    }
}

/**
 * 旁白：一对圆括号。
 *
 * 不做「引号」也不做「对话框+波浪线」——18dp 下细节全糊。
 * 括号是这套产品里旁白的既有符号（输入框里插的就是「（）」），
 * 用它当图标，按钮和插入结果直接对应上，不需要学。
 *
 * 弧线比直线更像「叙述」而不是「代码括号」：左右各一道向内收的曲线。
 */
@Composable
fun GlyphNarration(tint: Color, size: Dp = 18.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            // 左括号
            moveTo(w * 0.42f, h * 0.13f)
            cubicTo(w * 0.17f, h * 0.30f, w * 0.17f, h * 0.70f, w * 0.42f, h * 0.87f)
            // 右括号
            moveTo(w * 0.58f, h * 0.13f)
            cubicTo(w * 0.83f, h * 0.30f, w * 0.83f, h * 0.70f, w * 0.58f, h * 0.87f)
        }
        drawPath(
            p, tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.11f, cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

@Composable
fun GlyphPlus(tint: Color, size: Dp = 18.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawLine(tint, Offset(w / 2, h * 0.2f), Offset(w / 2, h * 0.8f), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.2f, h / 2), Offset(w * 0.8f, h / 2), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
    }
}

@Composable
fun GlyphDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 原生 ImageVector 兼容入口（保留给系统图标） */
@Composable
fun VectorGlyph(icon: ImageVector, tint: Color, size: Dp = 18.dp) {
    androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(size))
}

/**
 * 会滚动跳字的数字。
 *
 * 换人格时「事实 / 回忆 / 摘要」三个数会同时变。硬切给人的错觉是「页面刷新了」，
 * 滚动过去才像「换到了另一个人」。数值差越大滚得越久，但封顶 520ms，不吃时间。
 *
 * 首次组合不滚（animateIntAsState 的初值就是目标值），只在变化时滚。
 */
@Composable
fun AnimatedCount(
    value: Int,
    style: TextStyle = Ty.title1,
    color: Color = T.text,
    modifier: Modifier = Modifier,
) {
    val v by androidx.compose.animation.core.animateIntAsState(
        targetValue = value,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 520,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "animatedCount",
    )
    Text("$v", style = style, color = color, modifier = modifier)
}

/** 喇叭：实心喇叭身 + 两道弧线声波，和 GlyphSend 同一套圆头笔触 */
@Composable
fun GlyphSpeaker(tint: Color, size: Dp = 16.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // 喇叭身（实心）
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.08f, h * 0.37f)
            lineTo(w * 0.24f, h * 0.37f)
            lineTo(w * 0.46f, h * 0.15f)
            lineTo(w * 0.46f, h * 0.85f)
            lineTo(w * 0.24f, h * 0.63f)
            lineTo(w * 0.08f, h * 0.63f)
            close()
        }
        drawPath(body, tint)
        // 声波
        val wave = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.60f, h * 0.30f)
            cubicTo(w * 0.78f, h * 0.40f, w * 0.78f, h * 0.60f, w * 0.60f, h * 0.70f)
        }
        drawPath(
            wave, tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.10f, cap = StrokeCap.Round,
            ),
        )
    }
}
