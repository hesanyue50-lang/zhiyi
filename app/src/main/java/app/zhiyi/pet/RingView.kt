package app.zhiyi.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 环形菜单（弧形扇区版）。背景全透明，N 段圆弧拼成近似圆环。
 * 文字保持正立（不做 rotate），避免"旋转 90 度"。触摸由 PetService 处理。
 */
class RingView(
    ctx: Context,
    private val items: List<PetService.RingSpec>,
    private val clockwise: Boolean = true,
) : View(ctx) {

    private var selected: String = ""
    private var appear = 0f
    private val dp = ctx.resources.displayMetrics.density
    private fun d(v: Float) = v * dp

    // 绝对尺寸：外半径 124dp / 内半径 74dp（比之前小一圈，好点）
    private val outerR get() = d(124f)
    private val innerR get() = d(74f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#E8B4CA"); strokeWidth = d(1.4f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A3C46"); textAlign = Paint.Align.CENTER
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A3C46"); textAlign = Paint.Align.CENTER
    }

    private val outerRect = RectF()
    private val innerRect = RectF()
    private val path = Path()

    private fun startAppear() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            addUpdateListener { appear = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setSelected(key: String) { if (selected != key) { selected = key; invalidate() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startAppear() }
    override fun onTouchEvent(e: MotionEvent): Boolean = false

    /** 每段弧的绘制起点角度（0°=右，顺时针；第一段在正上方） */
    private fun segStart(index: Int): Float = -90f - (360f / items.size) / 2f + index * (360f / items.size)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = items.size
        if (n == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val sweep = 360f / n
        val gap = 3.5f                        // 段间留缝
        outerRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        innerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)

        for (k in 0 until n) {
            val item = items[k]
            val order = k.toFloat() / n
            val p = ((appear - order * 0.5f) / 0.5f).coerceIn(0f, 1f)
            if (p <= 0f) continue
            val idx = if (clockwise) k else (n - 1 - k)
            val start = segStart(idx) + gap / 2f
            val sw = sweep - gap

            path.reset()
            path.arcTo(outerRect, start, sw, true)
            path.arcTo(innerRect, start + sw, -sw, false)
            path.close()

            val on = item.key == selected
            fillPaint.alpha = ((if (on) 235 else 175) * p).toInt()
            fillPaint.color = if (on) Color.parseColor("#F9C6DB") else Color.parseColor("#FDF2F7")
            canvas.drawPath(path, fillPaint)
            strokePaint.alpha = (190 * p).toInt()
            canvas.drawPath(path, strokePaint)

            // 图标 + 文字：环中线，文字正立（不 rotate）
            val mid = Math.toRadians((segStart(idx) + sweep / 2f).toDouble())
            val midR = (outerR + innerR) / 2f
            val tx = cx + (midR * kotlin.math.cos(mid)).toFloat()
            val ty = cy + (midR * kotlin.math.sin(mid)).toFloat()

            iconPaint.alpha = (255 * p).toInt()
            textPaint.alpha = (255 * p).toInt()
            iconPaint.textSize = d(if (on) 19f else 17f)
            textPaint.textSize = d(if (on) 10.5f else 9.5f)
            canvas.drawText(item.icon, tx, ty - d(1f), iconPaint)
            canvas.drawText(item.label, tx, ty + d(13f), textPaint)
        }
    }

    fun currentSelected(): String = selected

    /** 用绝对坐标偏移找扇区 */
    fun keyForOffset(dx: Float, dy: Float): String {
        val n = items.size
        if (n == 0) return ""
        if (hypot(dx, dy) < innerR * 0.85f) return ""
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val norm = ((deg % 360) + 360) % 360
        var best = ""
        var bestDiff = 360.0
        for (k in 0 until n) {
            val idx = if (clockwise) k else (n - 1 - k)
            val centerRaw = -90.0 + idx * (360.0 / n)
            val center = ((centerRaw % 360) + 360) % 360
            var diff = kotlin.math.abs(norm - center)
            if (diff > 180) diff = 360 - diff
            if (diff < bestDiff) { bestDiff = diff; best = items[k].key }
        }
        return if (bestDiff <= (360.0 / n) / 2 + 4) best else ""
    }
}
