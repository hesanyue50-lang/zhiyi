package app.zhiyi.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zhiyi.memory.Heart
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 九维雷达图。
 *
 * 为什么不列九个数字：九个 0~100 的整数并排摆着，读者得自己在脑子里画形状。
 * 而情绪的形状本身就是信息——「依恋亲密高、压力低」是一个往左上鼓的团，
 * 「压力疲惫高、喜悦低」是一根往右下戳的刺。画出来一眼就看懂，念数字要三秒。
 *
 * 基线也画进去（虚线）：这样「涨了还是跌了」不靠记忆对比，几何上直接看得出来。
 *
 * 纯 Canvas，不引第三方图表库——只需要一个九边形，没必要为此背依赖。
 */
@Composable
fun HeartRadar(
    axes: List<Heart.Axis>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val n = axes.size
    if (n < 3) return

    // 每个轴一个动画值：换人格或数值更新时多边形平滑变形，不跳
    val anim = ArrayList<androidx.compose.runtime.State<Float>>(n)
    for (i in 0 until n) {
        anim.add(
            animateFloatAsState(
                targetValue = axes[i].value / 100f,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 150f),
                label = "radar$i",
            )
        )
    }

    val tm = rememberTextMeasurer()
    val labelSize = if (compact) 10.sp else 11.sp

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // 半径留出标签位置：0.65 是反复试出来「标签不糊边也不挤中间」的比例
        val rOuter = min(size.width, size.height) / 2f * 0.65f
        val step = (2.0 * PI / n).toFloat()
        val start = (-PI / 2).toFloat()      // 第一根轴朝正上，顺时针

        fun pt(i: Int, r: Float): Offset {
            val a = start + step * i
            return Offset(cx + r * cos(a), cy + r * sin(a))
        }

        // —— 网格：四层同心九边形 ——
        for (ring in 1..4) {
            val p = Path()
            for (i in 0 until n) {
                val o = pt(i, rOuter * ring / 4f)
                if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
            }
            p.close()
            drawPath(p, color = if (ring == 4) T.stroke else T.strokeSoft, style = Stroke(1f))
        }
        // —— 辐条 ——
        for (i in 0 until n) {
            drawLine(T.strokeSoft, Offset(cx, cy), pt(i, rOuter), strokeWidth = 1f)
        }

        // —— 当前值：平滑闭合曲线（不画端点，只留线） ——
        var maxI = 0
        val cur = ArrayList<Offset>(n)
        for (i in 0 until n) {
            val v = anim[i].value.coerceIn(0f, 1f)
            if (v > anim[maxI].value) maxI = i
            cur.add(pt(i, rOuter * v))
        }
        val curP = smoothClosed(cur)

        // 值越高越亮：一片死灰的雷达图看不出情绪起伏
        drawPath(
            curP,
            brush = Brush.radialGradient(
                colors = listOf(T.accent.copy(alpha = 0.34f), T.accent.copy(alpha = 0.10f)),
                center = Offset(cx, cy),
                radius = rOuter,
            ),
            style = Fill,
        )
        drawPath(curP, color = T.accent, style = Stroke(width = 2.4f))

        // —— 基线（静息态）：必须画在填充之上，否则被半透明填充吃掉 ——
        val baseP = smoothClosed(
            List(n) { i -> pt(i, rOuter * (axes[i].base / 100f).coerceIn(0f, 1f)) }
        )
        drawPath(
            baseP,
            color = T.text.copy(alpha = 0.34f),
            style = Stroke(
                width = 1.4f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7f, 7f)),
            ),
        )

        // —— 标签：只写维度名，数值藏在图形里 ——
        for (i in 0 until n) {
            val a = start + step * i
            val p = pt(i, rOuter + 13.dp.toPx())
            val low = axes[i].value < axes[i].base - 3
            val high = axes[i].value > axes[i].base + 3
            val col = when {
                i == maxI -> T.accent
                low -> T.textFaint
                else -> T.textDim
            }
            val layout = tm.measure(
                AnnotatedString(axes[i].label),
                style = TextStyle(
                    fontSize = labelSize,
                    fontWeight = if (i == maxI || high) FontWeight.SemiBold else FontWeight.Medium,
                    color = col,
                ),
            )
            // 按角度把标签往外推，避免压到多边形：上下居中，左右各自让开
            val w = layout.size.width.toFloat()
            val h = layout.size.height.toFloat()
            val dx = p.x - w / 2f
            val dy = p.y - h / 2f
            drawText(layout, topLeft = Offset(dx, dy))
        }
    }
}

/**
 * 九宫格上的角是数据，线不该也是角。
 *
 * 用 Catmull-Rom 转三次贝塞尔做闭合平滑：过点、不闭合处不打断，
 * 每个顶点处切线由前后两点共同决定，所以形状是「圆」的而数值仍然准确落在轴上。
 *
 * 张力取 1/8 而不是标准的 1/6：相邻维度差很大时（依恋 72 → 压力 12），
 * 标准张力会让曲线在两点之间鼓出去，把低值画得比真实值高。
 */
private fun smoothClosed(pts: List<Offset>): Path {
    val p = Path()
    val n = pts.size
    if (n < 3) return p
    p.moveTo(pts[0].x, pts[0].y)
    val k = 8f
    for (i in 0 until n) {
        val p0 = pts[(i - 1 + n) % n]
        val p1 = pts[i]
        val p2 = pts[(i + 1) % n]
        val p3 = pts[(i + 2) % n]
        val c1x = p1.x + (p2.x - p0.x) / k
        val c1y = p1.y + (p2.y - p0.y) / k
        val c2x = p2.x - (p3.x - p1.x) / k
        val c2y = p2.y - (p3.y - p1.y) / k
        p.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
    p.close()
    return p
}

/**
 * 「她的心」卡片：雷达图 + 一句话心境 + 倾向 + 惦记着的事。
 *
 * 放在记忆页（和人格相关）而不是设置页——设置是「调参数」的地方，
 * 而情绪是「她现在怎么样」，属于内容不属于配置。
 */
@Composable
fun HeartCard(
    axes: List<Heart.Axis>,
    mood: String,
    tendency: String,
    thought: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    GlassCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(D.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("她的心", style = Ty.title3, color = T.text)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(CircleShape)
                        .background(T.accent.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(mood, style = Ty.caption, color = T.accent)
                }
            }
            Spacer(Modifier.height(D.s2))
            HeartRadar(
                axes = axes,
                modifier = Modifier.fillMaxWidth().height(if (compact) 190.dp else 220.dp),
                compact = compact,
            )
            Spacer(Modifier.height(D.s1))
            // 图例：只有两条，不解释就看不懂虚线是什么
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp, 2.dp).background(T.accent))
                Spacer(Modifier.size(D.s1))
                Text("此刻", style = Ty.caption, color = T.textFaint)
                Spacer(Modifier.size(D.s3))
                Box(Modifier.size(10.dp, 2.dp).background(T.textFaint.copy(alpha = 0.45f)))
                Spacer(Modifier.size(D.s1))
                Text("平时", style = Ty.caption, color = T.textFaint)
            }
            if (tendency.isNotBlank()) {
                Spacer(Modifier.height(D.s3))
                Text(tendency, style = Ty.bodySm, color = T.textDim)
            }
            if (thought.isNotBlank()) {
                Spacer(Modifier.height(D.s2))
                Text("心里惦记着：$thought", style = Ty.caption, color = T.textFaint)
            }
            if (!compact) {
                Spacer(Modifier.height(D.s2))
                Text(
                    "实线是她此刻的位置，虚线是她平日的基线——往外鼓＝比平时高，往里缩＝比平时低。",
                    style = Ty.caption, color = T.textFaint,
                )
            }
        }
    }
}

@Composable
fun MoodCard(history: List<Float>) {
    // 固定 20 个点，切换人格时每个点平滑移动到新位置（morph）；曲线本身用 Catmull-Rom 平滑，不画点
    val fixed = 20
    val target = remember(history) { resampleMood(history, fixed) }
    val pts = ArrayList<androidx.compose.runtime.State<Float>>(fixed)
    for (i in 0 until fixed) {
        pts.add(
            androidx.compose.animation.core.animateFloatAsState(
                targetValue = target[i],
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 160f),
                label = "moodPt$i",
            )
        )
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(D.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("心情走势", style = Ty.title3, color = T.text)
                Spacer(Modifier.weight(1f))
                val cur = history.lastOrNull() ?: 0.5f
                Text(
                    when { cur < 0.3f -> "😢"; cur < 0.5f -> "😔"; cur < 0.7f -> "🙂"; else -> "😊" },
                    style = Ty.title2, color = T.text,
                )
            }
            Spacer(Modifier.height(D.s2))
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val stepX = size.width / (fixed - 1)
                val points = ArrayList<androidx.compose.ui.geometry.Offset>(fixed)
                for (i in 0 until fixed) {
                    points.add(
                        androidx.compose.ui.geometry.Offset(
                            i * stepX,
                            size.height * (1f - pts[i].value.coerceIn(0f, 1f)),
                        )
                    )
                }
                // Catmull-Rom → 三次贝塞尔，画平滑曲线
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 0 until points.size - 1) {
                        val p0 = points[maxOf(0, i - 1)]
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val p3 = points[minOf(points.size - 1, i + 2)]
                        cubicTo(
                            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                            p2.x, p2.y,
                        )
                    }
                }
                val fill = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fill, brush = Brush.verticalGradient(listOf(T.accent.copy(alpha = 0.28f), Color.Transparent)))
                drawPath(path, color = T.accent, style = Stroke(width = 2.5f))
            }
        }
    }
}

fun resampleMood(history: List<Float>, n: Int): List<Float> {
    if (history.isEmpty()) return List(n) { 0.5f }
    if (history.size == 1) return List(n) { history[0] }
    // 线性插值重采样到 n 个点（平滑采样）
    return List(n) { i ->
        val t = i.toFloat() / (n - 1) * (history.size - 1)
        val lo = t.toInt().coerceIn(0, history.size - 1)
        val hi = (lo + 1).coerceAtMost(history.size - 1)
        val frac = t - lo
        history[lo] * (1 - frac) + history[hi] * frac
    }
}
