package worldcup.helper.ui.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import worldcup.helper.data.model.RadarData

/**
 * 五维雷达图自定义 View
 *
 * 绘制一个五边形雷达图，展示球员的五项能力值
 */
class RadarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var radarData: RadarData? = null

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 79, 195, 247)  // #3C4FC3F7
        style = Paint.Style.FILL
    }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 79, 195, 247) // #B44FC3F7
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255) // #3CFFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val paintValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 215, 0) // #FFD700
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val levels = 5      // 5层同心网格
    private val sides = 5       // 五边形
    private val centerX: Float get() = width / 2f
    private val centerY: Float get() = height / 2f
    private val radius: Float get() = minOf(width, height) / 2f * 0.65f

    fun setData(data: RadarData) {
        this.radarData = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = radarData ?: return

        val cx = centerX
        val cy = centerY
        val r = radius
        if (r <= 0) return

        // 1. 绘制五层网格
        for (level in 1..levels) {
            val lr = r * level / levels
            val path = Path()
            for (i in 0 until sides) {
                val angle = Math.toRadians((-90 + 360.0 * i / sides).toDouble())
                val x = cx + lr * Math.cos(angle).toFloat()
                val y = cy + lr * Math.sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, paintGrid)
        }

        // 2. 绘制辐射线
        for (i in 0 until sides) {
            val angle = Math.toRadians((-90 + 360.0 * i / sides).toDouble())
            val x = cx + r * Math.cos(angle).toFloat()
            val y = cy + r * Math.sin(angle).toFloat()
            canvas.drawLine(cx, cy, x, y, paintGrid)
        }

        // 3. 绘制数据多边形
        val dataPath = Path()
        val dataCount = minOf(data.values.size, sides)

        for (i in 0 until dataCount) {
            val angle = Math.toRadians((-90 + 360.0 * i / sides).toDouble())
            val value = data.values[i].coerceIn(0f, 100f) / 100f
            val x = cx + r * value * Math.cos(angle).toFloat()
            val y = cy + r * value * Math.sin(angle).toFloat()
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        canvas.drawPath(dataPath, paintFill)
        canvas.drawPath(dataPath, paintStroke)

        // 4. 绘制数据点
        for (i in 0 until dataCount) {
            val angle = Math.toRadians((-90 + 360.0 * i / sides).toDouble())
            val value = data.values[i].coerceIn(0f, 100f) / 100f
            val x = cx + r * value * Math.cos(angle).toFloat()
            val y = cy + r * value * Math.sin(angle).toFloat()
            canvas.drawCircle(x, y, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4FC3F7")
                style = Paint.Style.FILL
            })
        }

        // 5. 绘制标签文字（在顶点外侧）
        for (i in 0 until dataCount) {
            val angle = Math.toRadians((-90 + 360.0 * i / sides).toDouble())
            val labelR = r + 50f
            val x = cx + labelR * Math.cos(angle).toFloat()
            val y = cy + labelR * Math.sin(angle).toFloat()

            val label = data.labels.getOrElse(i) { "" }
            val value = data.values.getOrElse(i) { 0f }.toInt()

            canvas.drawText("$label", x, y, paintLabel)
            val valueY = y + 30f
            canvas.drawText("$value", x, valueY, paintValue)
        }
    }
}
