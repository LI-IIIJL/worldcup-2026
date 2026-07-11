package worldcup.helper.ui.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 足球场阵型站位图（Canvas 绘制）
 *
 * 根据 BDL match_lineups 的 formation 字符串（如 "4-3-3"），
 * 在球场俯视图上放置球员圆圈+号码。
 */
class LineupCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 颜色
    private val pitchGreen = Color.parseColor("#1B5E20")
    private val pitchLight = Color.parseColor("#2E7D32")
    private val lineWhite = Color.parseColor("#80FFFFFF")
    private val playerHomeFill = Color.parseColor("#40FF6B35")
    private val playerHomeStroke = Color.parseColor("#FFFF6B35")
    private val playerAwayFill = Color.parseColor("#404488FF")
    private val playerAwayStroke = Color.parseColor("#FF4488FF")
    private val textWhite = Color.parseColor("#FFFFFF")
    private val textDim = Color.parseColor("#CCCCCC")

    // 数据
    private var homePlayers: List<LineupPlayer> = emptyList()
    private var awayPlayers: List<LineupPlayer> = emptyList()
    private var homeFormation: String = "4-3-3"
    private var awayFormation: String = "4-3-3"

    private val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineWhite; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textDim; textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textWhite; textSize = 26f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineWhite; textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setLineups(
        home: List<LineupPlayer>, away: List<LineupPlayer>,
        homeF: String?, awayF: String?
    ) {
        homePlayers = home; awayPlayers = away
        homeFormation = homeF ?: "4-3-3"; awayFormation = awayF ?: "4-3-3"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val pad = 30f
        val fieldW = width - pad * 2
        val fieldH = height - pad * 4 - 60f // 留顶底空间给队名
        val topY = pad + 40f
        val field = RectF(pad, topY, pad + fieldW, topY + fieldH)

        // 草地
        fieldPaint.color = pitchGreen; fieldPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(field, 8f, 8f, fieldPaint)
        // 条纹
        for (i in 0 until 6) {
            val stripeLeft = field.left + fieldW * i / 6
            fieldPaint.color = if (i % 2 == 0) pitchGreen else pitchLight
            canvas.drawRect(stripeLeft, field.top, stripeLeft + fieldW / 6, field.bottom, fieldPaint)
        }
        // 边线
        canvas.drawRoundRect(field, 8f, 8f, linePaint)
        // 中线
        canvas.drawLine(field.centerX(), field.top, field.centerX(), field.bottom, linePaint)
        // 中圈
        canvas.drawCircle(field.centerX(), field.centerY(), fieldW * 0.12f, linePaint)

        // 队名
        canvas.drawText(homeFormation, field.centerX(), pad + 30f, labelPaint)

        // 画主队球员（下半场方向）
        drawPlayers(canvas, homePlayers, field, isHome = true)
        // 画客队球员（上半场方向）
        drawPlayers(canvas, awayPlayers, field, isHome = false)
    }

    private fun drawPlayers(canvas: Canvas, players: List<LineupPlayer>, field: RectF, isHome: Boolean) {
        val rows = parseFormation(if (isHome) homeFormation else awayFormation)
        if (rows.isEmpty() || players.isEmpty()) return

        val r = field.width() / (rows.size + 2).coerceAtLeast(6) * 0.45f  // 球员圆半径
        val cellH = field.height() / (rows.size + 1).toFloat()
        val startX = field.left + field.width() / (players.size / rows.size + 2).coerceAtLeast(4).toFloat()

        var idx = 0
        for ((rowIdx, count) in rows.withIndex()) {
            val cy = if (isHome) field.bottom - cellH * (rowIdx + 1) else field.top + cellH * (rowIdx + 1)
            val spacing = field.width() / (count + 1).toFloat()
            for (col in 0 until count) {
                if (idx >= players.size) break
                val cx = field.left + spacing * (col + 1)
                val p = players[idx]

                // 圆背景
                playerPaint.style = Paint.Style.FILL
                playerPaint.color = if (isHome) playerHomeFill else playerAwayFill
                canvas.drawCircle(cx, cy, r, playerPaint)
                playerPaint.style = Paint.Style.STROKE
                playerPaint.strokeWidth = 2f
                playerPaint.color = if (isHome) playerHomeStroke else playerAwayStroke
                canvas.drawCircle(cx, cy, r, playerPaint)

                // 号码
                canvas.drawText("${p.number}", cx, cy + 8f, numPaint)

                idx++
            }
        }

        // 名字放圆下方
        playerPaint.style = Paint.Style.FILL
        playerPaint.textSize = 20f
        playerPaint.textAlign = Paint.Align.CENTER
        playerPaint.color = textWhite
        var ni = 0
        for ((rowIdx, count) in rows.withIndex()) {
            val cy = if (isHome) field.bottom - cellH * (rowIdx + 1) + r + 28f else field.top + cellH * (rowIdx + 1) + r + 28f
            val spacing = field.width() / (count + 1).toFloat()
            for (col in 0 until count) {
                if (ni >= players.size) break
                val cx = field.left + spacing * (col + 1)
                val p = players[ni]
                val displayName = if (p.name.length > 5) p.name.take(4) + "." else p.name
                canvas.drawText(displayName, cx, cy, namePaint)
                ni++
            }
        }
    }

    /** 解析阵型 "4-3-3" → [4, 3, 3]，不包含门将 */
    private fun parseFormation(f: String): List<Int> {
        return f.split("-").mapNotNull { it.toIntOrNull() }
    }
}

/** 阵型中的单个球员数据 */
data class LineupPlayer(val name: String, val number: Int, val position: String = "")
