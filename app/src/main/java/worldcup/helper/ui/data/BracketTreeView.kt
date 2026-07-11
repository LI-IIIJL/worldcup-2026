package worldcup.helper.ui.data

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import worldcup.helper.R
import worldcup.helper.data.CircleFlagLoader

/**
 * 淘汰赛对阵图 — 标准赛事树
 *
 * 布局：纵向时间线，每场比赛独立卡片，晋级关系用缩进和连线表示
 *
 * 上半区: R32 (8场) → R16 (4场) → QF (2场) → SF (1场)
 * 下半区: R32 (8场) → R16 (4场) → QF (2场) → SF (1场)
 * 决赛: SF1 vs SF2
 */
class BracketTreeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    data class Match(
        val id: String,
        val round: String, // "1/16", "1/8", "1/4", "SF", "Final", "Third"
        val homeTeam: String,
        val homeTeamCn: String,
        val awayTeam: String,
        val awayTeamCn: String,
        val homeFifaCode: String,
        val awayFifaCode: String,
        val homeScore: Int = 0,
        val awayScore: Int = 0,
        val status: String = "TIMED"
    )

    private var flagLoader: CircleFlagLoader? = null
    private val dp8 = (8 * resources.displayMetrics.density).toInt()
    private val dp12 = (12 * resources.displayMetrics.density).toInt()
    private val dp16 = (16 * resources.displayMetrics.density).toInt()
    private val dp4 = (4 * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp16, dp12, dp16, dp12)
    }

    fun setFlagLoader(loader: CircleFlagLoader) {
        flagLoader = loader
    }

    fun setMatches(matches: List<Match>) {
        removeAllViews()

        // 按轮次分组
        val r32 = matches.filter { it.round == "1/16决赛" }.sortedBy { it.id }
        val r16 = matches.filter { it.round == "1/8决赛" }.sortedBy { it.id }
        val qf = matches.filter { it.round == "1/4决赛" }.sortedBy { it.id }
        val sf = matches.filter { it.round == "半决赛" }.sortedBy { it.id }
        val final = matches.find { it.round == "决赛" }
        val third = matches.find { it.round == "三四名决赛" }

        // 上半区 (前一半)
        addSectionTitle("◀ 上半区", "#4488FF")
        addRound("1/16 决赛", r32.take(8), "1/8 决赛", r16.take(4))
        addRound("1/8 决赛", r16.take(4), "1/4 决赛", qf.take(2))
        addRound("1/4 决赛", qf.take(2), "半决赛", sf.take(1))
        addRound("半决赛", sf.take(1), "决赛", if (final != null) listOf(final) else emptyList())

        addDivider()

        // 下半区 (后一半)
        addSectionTitle("下半区 ▶", "#FF6B35")
        addRound("1/16 决赛", r32.drop(8), "1/8 决赛", r16.drop(4))
        addRound("1/8 决赛", r16.drop(4), "1/4 决赛", qf.drop(2))
        addRound("1/4 决赛", qf.drop(2), "半决赛", sf.drop(1))
        addRound("半决赛", sf.drop(1), "决赛", if (final != null) listOf(final) else emptyList())

        addDivider()

        // 决赛
        addFinalBlock(final, third)
    }

    private fun addSectionTitle(title: String, color: String) {
        addView(TextView(context).apply {
            text = title
            setTextColor(android.graphics.Color.parseColor(color))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp16, 0, dp12)
        })
    }

    private fun addRound(
        roundName: String,
        matches: List<Match>,
        nextRoundName: String,
        nextMatches: List<Match>
    ) {
        if (matches.isEmpty()) return

        // 轮次标题
        addView(TextView(context).apply {
            text = "— $roundName → $nextRoundName —"
            setTextColor(android.graphics.Color.parseColor("#666688"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp8, 0, dp8)
        })

        // 比赛卡片容器
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        for ((index, match) in matches.withIndex()) {
            val nextMatch = if (index / 2 < nextMatches.size) nextMatches[index / 2] else null
            container.addView(buildMatchCard(match, nextMatch))
        }

        addView(container)
    }

    private fun buildMatchCard(match: Match, nextMatch: Match?): LinearLayout {
        val isFinished = match.status == "FINISHED"
        val isTbd = match.homeTeam == "TBD" || match.awayTeam == "TBD"

        val bgColor = when {
            isFinished -> android.graphics.Color.parseColor("#1A3A2E")
            isTbd -> android.graphics.Color.parseColor("#15152A")
            else -> android.graphics.Color.parseColor("#1E1E32")
        }

        return LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(bgColor)
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp4, 0, dp4)
            }
            layoutParams = params
            setPadding(dp12, dp12, dp12, dp12)

            // 比赛信息行
            val infoRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // 主队
            infoRow.addView(buildTeamRow(
                match.homeTeamCn.ifEmpty { match.homeTeam },
                match.homeFifaCode,
                isFinished,
                isFinished && match.homeScore > match.awayScore,
                if (isFinished) "${match.homeScore}" else ""
            ))

            // VS
            infoRow.addView(TextView(context).apply {
                text = if (isFinished) "vs" else "vs"
                setTextColor(android.graphics.Color.parseColor("#444466"))
                textSize = 12f
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp12, 0, dp12, 0)
                }
            })

            // 客队
            infoRow.addView(buildTeamRow(
                match.awayTeamCn.ifEmpty { match.awayTeam },
                match.awayFifaCode,
                isFinished,
                isFinished && match.awayScore > match.homeScore,
                if (isFinished) "${match.awayScore}" else ""
            ))

            addView(infoRow)

            // 晋级信息
            if (nextMatch != null && isFinished) {
                val winner = if (match.homeScore > match.awayScore) match.homeTeamCn.ifEmpty { match.homeTeam }
                else match.awayTeamCn.ifEmpty { match.awayTeam }

                addView(TextView(context).apply {
                    text = "↓ 胜者 $winner 进入: ${nextMatch.homeTeamCn.ifEmpty { nextMatch.homeTeam }} vs ${nextMatch.awayTeamCn.ifEmpty { nextMatch.awayTeam }}"
                    setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    textSize = 10f
                    setPadding(0, dp8, 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            } else if (nextMatch != null) {
                addView(TextView(context).apply {
                    text = "↓ 胜者进入: ${nextMatch.homeTeamCn.ifEmpty { nextMatch.homeTeam }} vs ${nextMatch.awayTeamCn.ifEmpty { nextMatch.awayTeam }}"
                    setTextColor(android.graphics.Color.parseColor("#555577"))
                    textSize = 10f
                    setPadding(0, dp8, 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
        }
    }

    private fun buildTeamRow(
        name: String,
        fifaCode: String,
        isFinished: Boolean,
        isWinner: Boolean,
        score: String
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

            // 国旗
            val flag = ImageView(context).apply {
                layoutParams = LayoutParams(28, 28)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val d = if (fifaCode.isNotEmpty()) flagLoader?.loadFlag(fifaCode, 28) else null
            if (d != null) flag.setImageDrawable(d)
            addView(flag)

            // 队名
            addView(TextView(context).apply {
                text = if (name == "TBD") "待定" else name
                setTextColor(when {
                    name == "TBD" -> android.graphics.Color.parseColor("#555577")
                    isWinner -> android.graphics.Color.parseColor("#4CAF50")
                    isFinished -> android.graphics.Color.parseColor("#888888")
                    else -> android.graphics.Color.parseColor("#FFFFFF")
                })
                textSize = 13f
                setTypeface(null, if (isWinner) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(dp8, 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            // 比分
            if (score.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = score
                    setTextColor(if (isWinner) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FF6B35"))
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp8, 0, 0, 0)
                })
            }
        }
    }

    private fun addFinalBlock(final: Match?, third: Match?) {
        addSectionTitle("🏆 决赛", "#FFD700")

        if (final != null) {
            addView(buildMatchCard(final, null))
        }

        if (third != null) {
            addView(TextView(context).apply {
                text = "🥉 三四名决赛"
                setTextColor(android.graphics.Color.parseColor("#CCCC44"))
                textSize = 13f
                setPadding(0, dp16, 0, dp8)
            })
            addView(buildMatchCard(third, null))
        }
    }

    private fun addDivider() {
        addView(android.view.View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, dp16, 0, dp16)
            }
            setBackgroundColor(android.graphics.Color.parseColor("#2A2A44"))
        })
    }
}
