package worldcup.helper.ui.widget

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import worldcup.helper.data.model.Shot
import worldcup.helper.data.model.ShotMap
import worldcup.helper.data.model.ShotResult

/**
 * 球员射门分布图 Canvas View
 *
 * 绘制半场球门的俯视图，标记每次射门位置
 * 颜色: 进球=绿, 点球=紫, 射偏=灰
 * 大小: 圆半径 = sqrt(xg) * 8dp, 最小值 5dp
 * 点击: 显示 tooltip
 *
 * 坐标映射:
 *   BDL player_x (0-100) → canvasX (0-width)
 *   BDL player_y (0-100) → canvasY (height→0)  // Y 翻转: 底线在底部
 */
class ShotMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 图例色
    companion object {
        private val COLOR_GOAL = 0xFF00CC66.toInt()
        private val COLOR_PENALTY = 0xFFBB86FC.toInt()
        private val COLOR_MISS = 0xFF888888.toInt()
        private val COLOR_PITCH = 0xFF1A2A1A.toInt()
        private val COLOR_LINE = 0xFF336633.toInt()
        private val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private val COLOR_XG = 0x99FFFFFF.toInt()
        private const val DOT_MIN_R = 5f
        private const val DOT_SCALE = 8f
    }

    private var shotMap: ShotMap = ShotMap.EMPTY
    private val dotRects = mutableListOf<RectF>() // 每个射门点的触摸区域
    private var tooltipIndex = -1 // -1 = 无 tooltip
    private val paintPitch = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paintXg = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paintTooltipBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintTooltipText = TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        paintLine.style = Paint.Style.STROKE
        paintLine.strokeWidth = 2f
        paintPitch.style = Paint.Style.FILL
        paintText.textSize = 28f
        paintText.textAlign = Paint.Align.CENTER
        paintText.isFakeBoldText = true
        paintXg.textSize = 18f
        paintXg.textAlign = Paint.Align.CENTER
        paintTooltipBg.style = Paint.Style.FILL
        paintTooltipText.textSize = 22f
        paintTooltipText.textAlign = Paint.Align.LEFT
    }

    fun setData(data: ShotMap) {
        shotMap = data
        tooltipIndex = -1
        dotRects.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. 背景
        paintPitch.color = COLOR_PITCH
        canvas.drawRoundRect(0f, 0f, w, h, 16f, 16f, paintPitch)

        // 2. 球场线条
        paintLine.color = COLOR_LINE
        // 底线
        canvas.drawLine(0f, h, w, h, paintLine)
        // 球门区
        val gzW = w * 0.3f
        val gzH = h * 0.15f
        canvas.drawRect((w - gzW) / 2, h - gzH, (w + gzW) / 2, h, paintLine)
        // 球门
        val goalW = w * 0.18f
        canvas.drawRect((w - goalW) / 2, h - h * 0.04f, (w + goalW) / 2, h, paintLine)

        // 3. 射门点
        if (shotMap.shots.isEmpty()) {
            paintText.textSize = 24f
            paintText.color = 0x66888888.toInt()
            canvas.drawText("暂无射门数据", w / 2, h / 2, paintText)
            return
        }

        dotRects.clear()
        for ((i, shot) in shotMap.shots.withIndex()) {
            // BDL 坐标 → Canvas: X 翻转 (0=左/100=右)
            val cx = (shot.x / 100f) * w
            val cy = h - (shot.y / 100f) * h // Y 翻转: 底线在底部

            val r = (Math.sqrt(shot.xg.toDouble()).toFloat() * DOT_SCALE).coerceAtLeast(DOT_MIN_R)

            // 颜色
            val color = when (shot.result) {
                ShotResult.GOAL -> COLOR_GOAL
                ShotResult.PENALTY -> COLOR_PENALTY
                ShotResult.MISS -> COLOR_MISS
            }

            // 外圈
            paintLine.color = color
            paintLine.strokeWidth = 2f
            paintLine.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, r + 2f, paintLine)

            // 填充
            paintPitch.color = color
            paintPitch.alpha = if (tooltipIndex == i) 255 else 180
            paintPitch.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r, paintPitch)

            // xG 标签（只对 xG > 0.05 的显示）
            if (shot.xg > 0.05f) {
                paintXg.color = COLOR_XG
                paintXg.textSize = 16f
                canvas.drawText(String.format("%.2f", shot.xg), cx, cy - r - 6f, paintXg)
            }

            dotRects.add(RectF(cx - r - 10f, cy - r - 10f, cx + r + 10f, cy + r + 10f))
        }

        // 4. Tooltip
        if (tooltipIndex in shotMap.shots.indices) {
            drawTooltip(canvas, tooltipIndex)
        }

        // 5. 汇总信息
        paintText.textSize = 24f
        paintText.color = COLOR_TEXT
        canvas.drawText(
            "${shotMap.totalShots}射 · ${shotMap.totalGoals}球 · xG ${String.format("%.2f", shotMap.totalXg)}",
            w / 2, 30f, paintText
        )
    }

    private fun drawTooltip(canvas: Canvas, idx: Int) {
        val shot = shotMap.shots[idx]
        val shotRect = dotRects.getOrNull(idx) ?: return

        val bodyDesc = when {
            shot.bodyPart.contains("head") -> "头球"
            shot.bodyPart.contains("left") -> "左脚"
            else -> "右脚"
        }
        val typeDesc = when {
            shot.shotType.contains("penalty", ignoreCase = true) -> "点球"
            shot.shotType.contains("free_kick", ignoreCase = true) -> "任意球"
            else -> ""
        }
        val resultDesc = if (shot.isGoal) "⚽ 进球！" else ""

        val lines = listOfNotNull(
            "${shot.minute}' $bodyDesc${if (typeDesc.isNotEmpty()) " $typeDesc" else ""}",
            "xG: ${String.format("%.2f", shot.xg)}",
            if (resultDesc.isNotEmpty()) resultDesc else null
        )

        val pad = 16f
        val lineH = 28f
        val textW = lines.maxOf { paintTooltipText.measureText(it) }
        val boxW = textW + pad * 2
        val boxH = lineH * lines.size + pad * 2

        // Position tooltip above the dot if possible
        val tx = (shotRect.centerX() - boxW / 2).coerceIn(4f, width - boxW - 4f)
        val ty = (shotRect.top - boxH - 4f).coerceAtLeast(4f)

        paintTooltipBg.color = 0xDD222244.toInt()
        canvas.drawRoundRect(tx, ty, tx + boxW, ty + boxH, 8f, 8f, paintTooltipBg)

        paintTooltipText.color = 0xFFFFFFFF.toInt()
        paintTooltipText.textSize = 22f
        paintTooltipText.isFakeBoldText = false
        for ((i, line) in lines.withIndex()) {
            canvas.drawText(line, tx + pad, ty + pad + lineH * (i + 1f), paintTooltipText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            for ((i, rect) in dotRects.withIndex()) {
                if (rect.contains(x, y)) {
                    tooltipIndex = if (tooltipIndex == i) -1 else i
                    invalidate()
                    return true
                }
            }
            tooltipIndex = -1
            invalidate()
        }
        return true
    }
}
