package worldcup.helper.ui.data

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * 淘汰赛横向树状图
 *
 * 决赛(Level 0) → SF(1) → QF(2) → R16(3) → R32(4)
 * 每层 2^L 个节点，均匀分布
 */
class BracketView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BracketMatch(
        val id: String, val homeTeam: String, val homeTeamCn: String,
        val awayTeam: String, val awayTeamCn: String,
        val homeFifaCode: String = "", val awayFifaCode: String = "",
        val homeScore: Int = 0, val awayScore: Int = 0,
        val status: String = "TIMED", val roundLabel: String = ""
    )

    data class LayoutNode(
        val match: BracketMatch,
        var x: Float = 0f, var y: Float = 0f,
        var level: Int = 0, var index: Int = 0
    )

    // 颜色
    private val LINE = Color.parseColor("#444466")
    private val CARD = Color.parseColor("#1E1E32")
    private val CARD_DONE = Color.parseColor("#1A3A2E")
    private val CARD_TBD = Color.parseColor("#151525")
    private val WIN = Color.parseColor("#4CAF50")
    private val ORA = Color.parseColor("#FF6B35")
    private val WHITE = Color.parseColor("#FFFFFF")
    private val DIM = Color.parseColor("#7777AA")
    private val GOLD = Color.parseColor("#FFD700")
    private val BLUE = Color.parseColor("#4488FF")
    private val R_ORA = Color.parseColor("#FF6B35")

    private val dm = resources.displayMetrics
    private val cardW = dp(115f); private val cardH = dp(42f)
    private val gapX = dp(50f)
    private val flagS = dp(16f); private val textS = sp(10f); private val scoreS = sp(12f)
    private val roundS = sp(8f); private val titleS = sp(14f); private val corner = dp(5f); private val pad = dp(4f)

    // 决赛和节点列表
    private var finalNode: LayoutNode? = null
    private var thirdNode: LayoutNode? = null
    private val allNodes = mutableListOf<List<LayoutNode>>() // [level][node]

    private val flagCache = mutableMapOf<String, Bitmap>()
    private var flagLoader: worldcup.helper.data.CircleFlagLoader? = null
    fun setFlagLoader(l: worldcup.helper.data.CircleFlagLoader) { flagLoader = l }

    fun setMatches(matches: List<BracketMatch>) {
        val r32 = matches.filter { it.roundLabel == "1/16决赛" }.sortedBy { it.id }
        val r16 = matches.filter { it.roundLabel == "1/8决赛" }.sortedBy { it.id }
        val qf = matches.filter { it.roundLabel == "1/4决赛" }.sortedBy { it.id }
        val sf = matches.filter { it.roundLabel == "半决赛" }.sortedBy { it.id }

        allNodes.clear()
        // Level 1-4: SF(2), QF(4), R16(8), R32(16)
        allNodes.add(sf.map { LayoutNode(it, level = 1, index = 0) })
        allNodes.add(qf.map { LayoutNode(it, level = 2, index = 0) })
        allNodes.add(r16.map { LayoutNode(it, level = 3, index = 0) })
        allNodes.add(r32.map { LayoutNode(it, level = 4, index = 0) })
        // 更新index
        for ((li, level) in allNodes.withIndex()) {
            for ((ni, node) in level.withIndex()) {
                node.level = li + 1
                node.index = ni
            }
        }

        finalNode = matches.find { it.roundLabel == "决赛" }?.let { LayoutNode(it, level = 0) }
        thirdNode = matches.find { it.roundLabel == "三四名决赛" }?.let { LayoutNode(it) }

        requestLayout(); invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = (dp(60f) + (cardW + gapX) * 5).toInt()
        val neededH = (cardH + dp(8f)) * 16 + dp(100f)
        val h = neededH.coerceAtLeast(1500f)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (allNodes.isEmpty()) return
        val h = height.toFloat(); val w = width.toFloat()

        // 计算每个节点的位置
        val levels = allNodes.size
        val startX = dp(40f)
        val yMargin = dp(30f)
        val useH = h - yMargin * 2

        // Level 0（决赛）位置
        val fY = h / 2f
        val fX = dp(8f)

        // Level 1-4 位置
        for ((li, level) in allNodes.withIndex()) {
            val lx = startX + (cardW + gapX) * li
            val n = level.size
            for ((ni, node) in level.withIndex()) {
                node.x = lx
                // 上半区节点在上半部，下半区节点在下半部
                if (ni < n / 2) {
                    // 上半区（均匀分布在顶部1/4空间）
                    val topH = useH * 0.35f
                    val topStart = yMargin
                    node.y = topStart + (ni.toFloat() + 0.5f) / (n / 2f) * topH
                } else {
                    // 下半区（均匀分布在底部1/4空间）
                    val botH = useH * 0.35f
                    val botStart = h - yMargin - botH
                    node.y = botStart + ((ni - n / 2).toFloat() + 0.5f) / (n / 2f) * botH
                }
            }
        }

        // 画连线
        val lineP = mkPaint(false, 2f, LINE)
        // 决赛 → Level 1（两个SF）
        val sfNodes = allNodes.getOrNull(0) ?: return
        for (sf in sfNodes) {
            c.drawLine(fX + cardW, fY, fX + cardW + gapX * 0.3f, fY, lineP)
            c.drawLine(fX + cardW + gapX * 0.3f, fY, fX + cardW + gapX * 0.3f, sf.y, lineP)
            c.drawLine(fX + cardW + gapX * 0.3f, sf.y, sf.x, sf.y, lineP)
        }

        // Level L → Level L+1
        for (li in 0 until allNodes.size - 1) {
            val cur = allNodes[li]
            val next = allNodes[li + 1]
            for ((ni, node) in cur.withIndex()) {
                // 两个子节点
                val c1 = next.getOrNull(ni * 2) ?: continue
                val c2 = next.getOrNull(ni * 2 + 1) ?: continue
                val midX = node.x + cardW + gapX * 0.3f

                c.drawLine(node.x + cardW, node.y, midX, node.y, lineP)
                c.drawLine(midX, node.y, midX, (c1.y + c2.y) / 2f, lineP)
                c.drawLine(midX, c1.y, c1.x, c1.y, lineP)
                c.drawLine(midX, c2.y, c2.x, c2.y, lineP)
            }
        }

        // 标题
        c.drawText("🏆 决赛", fX, fY - cardH - dp(10f), mkPaint(true, titleS, GOLD))
        c.drawText("▲ 上半区", startX, dp(16f), mkPaint(true, roundS, BLUE))
        c.drawText("▼ 下半区", startX, h - dp(8f), mkPaint(true, roundS, R_ORA))

        // 轮次标签
        val labels = listOf("半决赛", "1/4决赛", "1/8决赛", "1/16决赛")
        for ((li, label) in labels.withIndex()) {
            val lx = startX + (cardW + gapX) * li + cardW / 2f
            c.drawText(label, lx - dp(12f), dp(32f), mkPaint(false, roundS, DIM))
        }

        // 画决赛
        finalNode?.let { drawCard(c, it.match, fX, fY) }

        // 画所有节点
        for (level in allNodes) {
            for (node in level) {
                drawCard(c, node.match, node.x, node.y)
            }
        }

        // 三四名
        thirdNode?.let {
            val tx = dp(8f); val ty = h - dp(60f)
            c.drawText("🥉 三四名", tx, ty - cardH - dp(8f), mkPaint(true, roundS, Color.parseColor("#CCCC44")))
            drawCard(c, it.match, tx, ty)
        }
    }

    private fun drawCard(c: Canvas, m: BracketMatch, x: Float, y: Float) {
        val isDone = m.status == "FINISHED"
        val isTbd = m.homeTeam == "TBD" || m.awayTeam == "TBD"
        val home = m.homeTeamCn.ifEmpty { m.homeTeam }
        val away = m.awayTeamCn.ifEmpty { m.awayTeam }
        val bg = when { isTbd -> CARD_TBD; isDone -> CARD_DONE; else -> CARD }

        c.drawRoundRect(x, y - cardH / 2f, x + cardW, y + cardH / 2f, corner, corner, mkPaint(true, 0f, bg))

        val barColor = if (isDone) WIN else if (isTbd) LINE else ORA
        c.drawRoundRect(x, y - cardH / 2f, x + dp(3f), y + cardH / 2f, 0f, 0f, mkPaint(true, 0f, barColor))

        val tx = x + dp(5f) + flagS + dp(3f)
        val mid = y

        // 主队
        drawFlag(c, m.homeFifaCode, x + dp(5f), y - cardH / 2f + dp(4f))
        c.drawText(limitText(home, 8), tx, y - dp(3f), mkPaint(false, textS, if (isTbd) DIM else WHITE))
        if (isDone) {
            val s = "${m.homeScore}"
            val sp = mkPaint(true, scoreS, if (m.homeScore > m.awayScore) WIN else ORA)
            c.drawText(s, x + cardW - pad - sp.measureText(s), y - dp(3f), sp)
        }

        c.drawLine(x + dp(3f), mid, x + cardW - pad, mid, mkPaint(false, 1f, LINE))

        // 客队
        drawFlag(c, m.awayFifaCode, x + dp(5f), mid + dp(4f))
        c.drawText(limitText(away, 8), tx, mid + dp(10f), mkPaint(false, textS, if (isTbd) DIM else WHITE))
        if (isDone) {
            val s = "${m.awayScore}"
            val sp = mkPaint(true, scoreS, if (m.awayScore > m.homeScore) WIN else ORA)
            c.drawText(s, x + cardW - pad - sp.measureText(s), mid + dp(10f), sp)
        }
    }

    private fun drawFlag(c: Canvas, code: String, x: Float, y: Float) {
        if (code.isEmpty() || flagLoader == null) return
        if (!flagCache.containsKey(code)) {
            try {
                val d = flagLoader?.loadFlag(code, flagS.toInt()) ?: return
                val b = Bitmap.createBitmap(flagS.toInt(), flagS.toInt(), Bitmap.Config.ARGB_8888)
                val tmp = Canvas(b)
                d.setBounds(0, 0, flagS.toInt(), flagS.toInt())
                d.draw(tmp)
                flagCache[code] = b
            } catch (_: Exception) { return }
        }
        flagCache[code]?.let { c.drawBitmap(it, x, y, null) }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)
    private fun mkPaint(b: Boolean, s: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = s; isFakeBoldText = b }
    private fun limitText(t: String, n: Int) = if (t.length > n) t.substring(0, n) + "…" else t
}
