package worldcup.helper.ui.match

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import worldcup.helper.data.repos.ShotMapRepo
import kotlin.math.max
import kotlin.math.min

/**
 * 射门分布热力图 — 半场俯视图 Canvas View
 *
 * 在足球场半场俯视图上绘制射门点：
 * ⚽ 进球=绿色实心圆
 * 🎯 射正=蓝色实心圆
 * ❌ 射偏=灰色×
 * 🛑 被封堵=橙色菱形
 *
 * 点半径 = xG 值映射（0.05~0.8 → 6dp~22dp）
 */
class ShotMapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 射门数据 */
    var shots: List<ShotMapRepo.ShotEntry> = emptyList()
        set(value) { field = value; invalidate() }

    /** 图例文字（"共45射 · 6进球 · 18射正"） */
    var legendText: String = ""
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 28f   // 11sp ≈ 28px @2x
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
    }

    /** 球场区域 (padding 内) */
    private val fieldRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(12).toFloat()
        val topPad = dp(40).toFloat()  // 上方留图例空间

        fieldRect.set(pad, topPad, w - pad, h - pad)

        // 1. 绘制球场背景
        drawPitch(canvas, fieldRect)

        // 2. 绘制射门点
        for (shot in shots) {
            // 映射坐标: BDL 坐标系 (0-100, 0-100) → View 坐标
            // BDL x: 0=左边线, 100=右边线
            // BDL y: 0=底线, 100=中线
            val vx = fieldRect.left + (shot.x / 100f) * fieldRect.width()
            val vy = fieldRect.bottom - (shot.y / 100f) * fieldRect.height()

            val radius = mapXgToRadius(shot.xg)

            when (shot.result) {
                ShotMapRepo.ShotResult.GOAL -> {
                    paint.color = Color.parseColor("#2ECC71") // 绿
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(vx, vy, radius, paint)
                    // 进球加白色小光圈
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.WHITE
                    paint.strokeWidth = 2f
                    canvas.drawCircle(vx, vy, radius, paint)
                }
                ShotMapRepo.ShotResult.ON_TARGET -> {
                    paint.color = Color.parseColor("#3498DB") // 蓝
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(vx, vy, radius, paint)
                }
                ShotMapRepo.ShotResult.OFF_TARGET -> {
                    // 灰色 ×
                    paint.color = Color.parseColor("#888888")
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    val s = radius * 0.7f
                    canvas.drawLine(vx - s, vy - s, vx + s, vy + s, paint)
                    canvas.drawLine(vx + s, vy - s, vx - s, vy + s, paint)
                }
                ShotMapRepo.ShotResult.BLOCKED -> {
                    // 橙色菱形
                    paint.color = Color.parseColor("#E67E22")
                    paint.style = Paint.Style.FILL
                    val path = Path()
                    path.moveTo(vx, vy - radius)
                    path.lineTo(vx + radius, vy)
                    path.lineTo(vx, vy + radius)
                    path.lineTo(vx - radius, vy)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                ShotMapRepo.ShotResult.POST -> {
                    // 黄色圆环
                    paint.color = Color.parseColor("#F1C40F")
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    canvas.drawCircle(vx, vy, radius, paint)
                }
                else -> {
                    // 灰色小点（未知结果）
                    paint.color = Color.parseColor("#555555")
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(vx, vy, dp(3).toFloat(), paint)
                }
            }

            // 结果标签（每个射门点上方的文字标注）
            val label = when (shot.result) {
                ShotMapRepo.ShotResult.GOAL -> "⚽"
                ShotMapRepo.ShotResult.ON_TARGET -> "🎯"
                ShotMapRepo.ShotResult.OFF_TARGET -> "✕"
                ShotMapRepo.ShotResult.BLOCKED -> "🛑"
                ShotMapRepo.ShotResult.POST -> "🟡"
                else -> null
            }
            if (label != null && radius > dp(8).toFloat()) {
                labelPaint.color = Color.parseColor("#FFFFFF")
                labelPaint.textSize = dp(11).toFloat()
                canvas.drawText(label, vx, vy - radius - dp(4).toFloat(), labelPaint)
            }
        }

        // 3. 绘制图例
        if (legendText.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(legendText, w / 2f, dp(20).toFloat(), textPaint)
        }

        // 4. 绘制结果图例 (底部)
        drawLegend(canvas, w, h)
    }

    /** 绘制足球场半场 */
    private fun drawPitch(canvas: Canvas, rect: RectF) {
        // 草地背景
        paint.color = Color.parseColor("#1B5E20")
        paint.style = Paint.Style.FILL
        canvas.drawRect(rect, paint)

        // 草纹条纹
        paint.color = Color.parseColor("#1A6B1A")
        val stripeW = rect.width() / 8f
        for (i in 0..7) {
            if (i % 2 == 0) {
                canvas.drawRect(
                    rect.left + i * stripeW, rect.top,
                    rect.left + (i + 1) * stripeW, rect.bottom, paint
                )
            }
        }

        // 边线
        paint.color = Color.parseColor("#FFFFFF")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(rect, paint)

        // 罚球区 (大禁区)
        val boxW = rect.width() * 0.44f
        val boxH = rect.height() * 0.2f
        val boxLeft = rect.centerX() - boxW / 2f
        val boxTop = rect.bottom - boxH
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxW, rect.bottom, paint)

        // 球门线
        paint.strokeWidth = 4f
        paint.color = Color.parseColor("#E0E0E0")
        val goalW = rect.width() * 0.15f
        canvas.drawLine(
            rect.centerX() - goalW / 2f, rect.bottom,
            rect.centerX() + goalW / 2f, rect.bottom, paint
        )

        // 小禁区
        paint.strokeWidth = 2f
        val smallBoxH = rect.height() * 0.08f
        val smallBoxW = rect.width() * 0.18f
        canvas.drawRect(
            rect.centerX() - smallBoxW / 2f, rect.bottom - smallBoxH,
            rect.centerX() + smallBoxW / 2f, rect.bottom, paint
        )

        // 中圈（半圈，仅下半场）
        paint.strokeWidth = 1.5f
        val centerRadius = rect.width() * 0.1f
        canvas.drawArc(
            rect.centerX() - centerRadius, rect.top,
            rect.centerX() + centerRadius, rect.top + centerRadius * 2,
            0f, 180f, false, paint
        )

        // 中线
        canvas.drawLine(rect.left, rect.top, rect.right, rect.top, paint)
    }

    /** xG → 点半径 (dp) */
    private fun mapXgToRadius(xg: Double): Float {
        val minR = dp(4).toFloat()
        val maxR = dp(20).toFloat()
        val clamped = xg.coerceIn(0.0, 0.8)
        return (minR + (clamped / 0.8) * (maxR - minR)).toFloat()
    }

    /** 底部结果图例 */
    private fun drawLegend(canvas: Canvas, w: Float, h: Float) {
        val items = listOf(
            "⚽进球" to "#2ECC71",
            "🎯射正" to "#3498DB",
            "❌射偏" to "#888888",
            "🛑被封" to "#E67E22"
        )
        val itemW = w / items.size
        val y = h - dp(10).toFloat()
        textPaint.textSize = dp(10).toFloat()
        textPaint.textAlign = Paint.Align.CENTER

        for ((i, item) in items.withIndex()) {
            canvas.drawText(item.first, itemW * i + itemW / 2f, y, textPaint)
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
}
