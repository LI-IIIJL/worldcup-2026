package worldcup.helper.ui.match

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import worldcup.helper.R
import worldcup.helper.data.PlayerRepository
import worldcup.helper.data.model.*
import worldcup.helper.data.repos.SharedRepository
import worldcup.helper.ui.widget.RadarChartView
import worldcup.helper.ui.widget.ShotMapView
import java.text.SimpleDateFormat
import java.util.*

class PlayerDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYER_NAME = "player_name"
        const val EXTRA_TEAM_NAME = "team_name"

        private const val BG_DARK = "#0F0F23"
        private const val BG_CARD = "#1A1A3E"
        private const val WHITE = "#FFFFFF"
        private const val GOLD = "#FFD700"
        private const val ORANGE = "#FF6B35"
        private const val GREEN = "#00CC66"
        private const val GRAY = "#888888"
        private const val LIGHT_BLUE = "#4FC3F7"
        private const val RED = "#E94560"
        private const val PURPLE = "#BB86FC"
    }

    private lateinit var playerRepo: PlayerRepository
    private lateinit var repo: SharedRepository
    private var currentProfile: PlayerProfile? = null
    private var currentMatchIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_detail)

        playerRepo = PlayerRepository(applicationContext)
        repo = SharedRepository.getInstance(applicationContext)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.player_root)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, v.paddingTop + top, v.paddingRight, v.paddingBottom)
            insets
        }

        val playerName = intent.getStringExtra(EXTRA_PLAYER_NAME) ?: ""
        val teamName = intent.getStringExtra(EXTRA_TEAM_NAME) ?: ""

        findViewById<TextView>(R.id.tv_back).setOnClickListener { finish() }

        loadPlayerProfile(playerName, teamName)
    }

    private fun loadPlayerProfile(playerName: String, teamName: String) {
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    playerRepo.getPlayerProfile(playerName, teamName)
                }
                currentProfile = profile
                renderAll(profile)
            } catch (e: Exception) {
                Toast.makeText(this@PlayerDetailActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderAll(profile: PlayerProfile) {
        renderHeader(profile)
        renderSummary(profile)         // 🆕 文本摘要
        renderTeamContext(profile)     // 🆕 球队上下文
        renderQuickStats(profile)
        renderCareerStats(profile)
        renderSeasonStats(profile)
        renderMatchProgression(profile) // 🆕 逐场表现
        renderHonors(profile)
        renderRadarChart(profile)
        renderShotMap(profile)       // 🆕 射门分布图
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第1层：头部
    // ══════════════════════════════════════════════════════════════════════

    private fun renderHeader(profile: PlayerProfile) {
        val root = findViewById<View>(R.id.player_root)
        root.setBackgroundColor(Color.parseColor(BG_DARK))

        val avatar = findViewById<ImageView>(R.id.iv_player_avatar)
        if (!profile.photoUrl.isNullOrEmpty()) {
            avatar.load(profile.photoUrl) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.ic_player_placeholder)
                error(R.drawable.ic_player_placeholder)
                size(160, 160)
            }
        } else {
            avatar.setImageResource(R.drawable.ic_player_placeholder)
            avatar.setBackgroundColor(Color.parseColor("#1A1A3E"))
        }

        val displayName = profile.nameCn.ifEmpty { profile.name }
        findViewById<TextView>(R.id.tv_player_name).text = displayName

        if (profile.nameCn.isNotEmpty()) {
            findViewById<TextView>(R.id.tv_player_name_en).text = profile.name
            findViewById<TextView>(R.id.tv_player_name_en).visibility = View.VISIBLE
        }

        findViewById<TextView>(R.id.tv_player_number).text = "#${profile.jerseyNumber}"
        findViewById<TextView>(R.id.tv_player_position).text = profile.positionDisplay
        findViewById<TextView>(R.id.tv_player_team).text = profile.teamNameCn.ifEmpty { profile.teamName }

        if (profile.isInjured) {
            findViewById<TextView>(R.id.tv_injury_status).apply {
                text = "🩹 伤病"
                visibility = View.VISIBLE
                setTextColor(Color.parseColor(RED))
            }
        }

        findViewById<TextView>(R.id.tv_player_club).text = "🏛 ${profile.clubDisplay}"

        val mv = profile.marketValueMil
        if (mv != null && mv > 0) {
            findViewById<TextView>(R.id.tv_market_value).text = "💰 €${mv}M"
        }

        findViewById<TextView>(R.id.tv_player_ids).text = buildString {
            profile.personId?.let { append("FD: $it") }
            if (profile.personId != null && profile.apiSportsId != null) append(" | ")
            profile.apiSportsId?.let { append("AS: $it") }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 🆕 文本摘要
    // ══════════════════════════════════════════════════════════════════════

    private fun renderSummary(profile: PlayerProfile) {
        val parts = mutableListOf<String>()
        if (profile.wcMatchesOnPitch > 0) parts.add("📋 ${profile.wcMatchesOnPitch}场")
        if (profile.wcGoals > 0) parts.add("⚽ ${profile.wcGoals}球")
        if (profile.wcAssists > 0) parts.add("🅰 ${profile.wcAssists}助")
        val rating = profile.seasonStats?.rating
        if (rating != null && rating > 0) parts.add("⭐ ${String.format("%.2f", rating)}分")
        if (profile.isInjured) parts.add("🩹 伤病中")
        if (profile.wcStartingXI > 0 && profile.wcMatchesOnPitch > 0) {
            val startPct = (profile.wcStartingXI.toFloat() / profile.wcMatchesOnPitch * 100).toInt()
            if (startPct >= 80) parts.add("🔒 绝对主力")
            else if (startPct >= 50) parts.add("🔄 轮换主力")
        }
        val lastMatch = profile.matchHistories.maxByOrNull { it.matchDate }
        if (lastMatch != null && lastMatch.minutes > 0) {
            val lastRating = lastMatch.rating
            if (lastRating != null && lastRating >= 7.5) parts.add("🔥 近况火热")
            else if (lastRating != null && lastRating < 6.0) parts.add("📉 近期低迷")
        }
        val summary = parts.joinToString(" · ")
        if (summary.isNotEmpty()) {
            findViewById<TextView>(R.id.tv_player_ids).apply {
                text = summary
                setTextColor(Color.parseColor(GOLD))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 🆕 球队上下文卡片
    // ══════════════════════════════════════════════════════════════════════

    private fun renderTeamContext(profile: PlayerProfile) {
        val fifaCode = profile.teamFifaCode
        if (fifaCode.isBlank()) return

        lifecycleScope.launch {
            val teamMatches = withContext(Dispatchers.IO) {
                try { repo.matches.getTeamMatches(fifaCode) } catch (_: Exception) { emptyList() }
            }
            if (teamMatches.isEmpty()) return@launch

            val finished = teamMatches.filter { md ->
                try {
                    val s = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                    val matchTime = s.parse(md.datetime)?.time ?: 0L
                    System.currentTimeMillis() - matchTime > 7200_000L
                } catch (_: Exception) { true }
            }
            val upcoming = teamMatches.filter { md ->
                try {
                    val s = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                    val matchTime = s.parse(md.datetime)?.time ?: 0L
                    System.currentTimeMillis() < matchTime + 1800_000L
                } catch (_: Exception) { false }
            }

            val parent = findViewById<LinearLayout>(R.id.quick_stats_container)
            val ctx = this@PlayerDetailActivity

            val title = TextView(ctx).apply {
                text = "─── 🏟 球队动态 ───"
                setTextColor(Color.parseColor("#555577")); textSize = 11f
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1A3E"))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, dp(12))
                }
            }

            card.addView(TextView(ctx).apply {
                text = "${profile.teamNameCn}  ·  近${finished.size}场"
                setTextColor(Color.parseColor("#FFFFFF")); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            })

            val recent5 = finished.takeLast(5)
            if (recent5.isNotEmpty()) {
                val barRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, dp(2))
                }
                for (m in recent5) {
                    val result = when {
                        m.homeScore > m.awayScore && (m.homeFifaCode == fifaCode || m.homeTeamEn == profile.teamName) -> true
                        m.awayScore > m.homeScore && (m.awayFifaCode == fifaCode || m.awayTeamEn == profile.teamName) -> true
                        else -> false
                    }
                    val isDraw = m.homeScore == m.awayScore
                    val color = if (result) "#00CC66" else if (isDraw) "#555577" else "#E94560"
                    barRow.addView(TextView(ctx).apply {
                        text = " ${if (result) "W" else if (isDraw) "D" else "L"} "
                        setTextColor(Color.parseColor("#FFFFFF")); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                        setBackgroundColor(Color.parseColor(color))
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(dp(2), 0, dp(2), 0)
                        }
                    })
                }
                card.addView(barRow)
            }

            val wins = finished.count { m ->
                (m.homeScore > m.awayScore && (m.homeFifaCode == fifaCode || m.homeTeamEn == profile.teamName)) ||
                (m.awayScore > m.homeScore && (m.awayFifaCode == fifaCode || m.awayTeamEn == profile.teamName))
            }
            val draws = finished.count { m -> m.homeScore == m.awayScore }
            val losses = finished.size - wins - draws

            card.addView(TextView(ctx).apply {
                text = "${wins}胜 ${draws}平 ${losses}负  |  进${finished.sumOf { if (it.homeFifaCode == fifaCode) it.homeScore else it.awayScore }}球 失${finished.sumOf { if (it.homeFifaCode == fifaCode) it.awayScore else it.homeScore }}球"
                setTextColor(Color.parseColor("#8888AA")); textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })

            val next = upcoming.firstOrNull()
            if (next != null) {
                val dt = next.datetime
                val d = if (dt.length >= 10) dt.substring(0, 10) else ""
                val t = if (dt.length >= 16) dt.substring(11, 16) else ""
                val nextLabel = "${next.homeTeamCn} vs ${next.awayTeamCn}"
                card.addView(TextView(ctx).apply {
                    text = "⏭ 下一场: $d $t · $nextLabel"
                    setTextColor(Color.parseColor("#4488FF")); textSize = 11f
                    setPadding(0, dp(6), 0, 0)
                })
            }

            val parentLayout = parent.parent as? LinearLayout
            if (parentLayout != null) {
                val idx = parentLayout.indexOfChild(parent)
                parentLayout.addView(title, idx)
                parentLayout.addView(card, idx + 1)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第2层：快速统计条
    // ══════════════════════════════════════════════════════════════════════

    private fun renderQuickStats(profile: PlayerProfile) {
        val stats = profile.seasonStats
        val rating = stats?.rating

        val items = listOf(
            QuickStat("出场", "${profile.wcMatchesOnPitch}"),
            QuickStat("进球", "${profile.wcGoals}"),
            QuickStat("助攻", "${profile.wcAssists}"),
            QuickStat("评分", rating?.let { String.format("%.2f", it) } ?: "—"),
        )

        val container = findViewById<LinearLayout>(R.id.quick_stats_container)
        container.removeAllViews()

        for (item in items) {
            val card = createQuickStatCard(item.label, item.value)
            container.addView(card)
            if (item != items.last()) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(1), dp(40)).apply {
                        setMargins(dp(8), 0, dp(8), 0)
                    }
                    setBackgroundColor(Color.parseColor("#33FFFFFF"))
                })
            }
        }
    }

    private data class QuickStat(val label: String, val value: String)

    private fun createQuickStatCard(label: String, value: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(TextView(this).apply {
            text = value; setTextColor(Color.parseColor(GOLD)); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = label; setTextColor(Color.parseColor(GRAY)); textSize = 11f
            setPadding(0, 2, 0, 0)
        })
        return card
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第3层：世界杯累计统计（12项）
    // ══════════════════════════════════════════════════════════════════════

    private fun renderCareerStats(profile: PlayerProfile) {
        val items = listOf(
            StatItem("出场", "${profile.wcMatchesOnPitch}", "首发", "${profile.wcStartingXI}"),
            StatItem("进球", "${profile.wcGoals}", "助攻", "${profile.wcAssists}"),
            StatItem("出场分钟", "${profile.wcMinutesPlayed}'", "场均", String.format("%.0f'", profile.minutesPerMatch)),
            StatItem("黄牌", "${profile.wcYellowCards}", "红牌", "${profile.wcRedCards}"),
            StatItem("替补登场", "${profile.wcSubbedIn}", "被换下", "${profile.wcSubbedOut}"),
            StatItem("点球", "${profile.wcPenalties}", "乌龙", "${profile.wcOwnGoals}"),
        )

        val grid = findViewById<LinearLayout>(R.id.career_stats_grid)
        grid.removeAllViews()

        for (item in items) {
            grid.addView(createStatRow(item.label1, item.value1, item.label2, item.value2))
        }
    }

    private data class StatItem(val label1: String, val value1: String, val label2: String, val value2: String)

    private fun createStatRow(l1: String, v1: String, l2: String, v2: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
        }
        row.addView(createStatCell(l1, v1))
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(8), dp(4), dp(8), dp(4))
            }
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
        })
        row.addView(createStatCell(l2, v2))
        return row
    }

    private fun createStatCell(label: String, value: String): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(6), 0, dp(6))
        }
        cell.addView(TextView(this).apply {
            text = value; setTextColor(Color.parseColor(WHITE)); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        })
        cell.addView(TextView(this).apply {
            text = label; setTextColor(Color.parseColor(GRAY)); textSize = 11f
            setPadding(0, 2, 0, 0)
        })
        return cell
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第4层：赛季详细18项统计
    // ══════════════════════════════════════════════════════════════════════

    private fun renderSeasonStats(profile: PlayerProfile) {
        val stats = profile.seasonStats
        val section = findViewById<ViewStub>(R.id.season_stats_stub) ?: return
        if (stats == null) { section.visibility = View.GONE; return }
        section.layoutResource = R.layout.section_season_stats
        val container = section.inflate() as LinearLayout

        container.addView(createStatSection("⚔️ 进攻", listOf(
            Pair("进球", "${stats.goals}"), Pair("助攻", "${stats.assists}"),
            Pair("射门", "${stats.shotsTotal}"), Pair("射正", "${stats.shotsOnTarget}"),
            Pair("射门转化率", String.format("%.1f%%", stats.shotAccuracy)),
        )))
        container.addView(createStatSection("🧠 组织", listOf(
            Pair("传球", "${stats.passesTotal}"), Pair("关键传球", "${stats.passesKey}"),
            Pair("传球成功率", String.format("%.1f%%", stats.passAccuracy)),
        )))
        container.addView(createStatSection("🛡️ 防守", listOf(
            Pair("抢断", "${stats.tacklesTotal}"), Pair("拦截", "${stats.interceptions}"),
            Pair("对抗成功", "${stats.duelsWon}/${stats.duelsTotal}"),
            Pair("对抗成功率", String.format("%.1f%%", stats.duelWinRate)),
        )))
        container.addView(createStatSection("⚠️ 纪律", listOf(
            Pair("犯规", "${stats.foulsCommitted}"), Pair("被犯规", "${stats.foulsDrawn}"),
            Pair("过人成功", "${stats.dribblesSuccess}"),
            Pair("黄牌", "${stats.yellowCards}"), Pair("红牌", "${stats.redCards}"),
        )))

        val adv = profile.advancedStats
        if (adv != null) {
            container.addView(createStatSection("🔬 高级", listOfNotNull(
                adv.expectedGoals?.let { Pair("xG", String.format("%.2f", it)) },
                adv.expectedAssists?.let { Pair("xA", String.format("%.2f", it)) },
                adv.crossesAccurate?.let { Pair("精准传中", "$it") },
                adv.aerialDuelsWon?.let { Pair("头球争顶赢", "$it") },
                adv.possessionLost?.let { Pair("丢球权", "$it") },
                adv.ballRecoveries?.let { Pair("球权恢复", "$it") },
            )))
        }
    }

    private fun createStatSection(title: String, items: List<Pair<String, String>>): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        section.addView(TextView(this).apply {
            text = title; setTextColor(Color.parseColor(ORANGE)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        for ((label, value) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            row.addView(TextView(this).apply {
                text = label; setTextColor(Color.parseColor(GRAY)); textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = value; setTextColor(Color.parseColor(WHITE)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            })
            section.addView(row)
        }
        return section
    }

    // ══════════════════════════════════════════════════════════════════════
    // 🆕 逐场表现趋势
    // ══════════════════════════════════════════════════════════════════════

    private fun renderMatchProgression(profile: PlayerProfile) {
        val histories = profile.matchHistories.filter { it.minutes > 0 }
        if (histories.isEmpty()) return
        val ctx = this@PlayerDetailActivity

        val container = findViewById<LinearLayout>(R.id.radar_chart_container)

        val title = TextView(ctx).apply {
            text = "─── 📈 逐场表现 ───"
            setTextColor(Color.parseColor("#555577")); textSize = 11f
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(12), 0, 0)
            }
        }
        container.addView(title)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A3E"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val sorted = histories.sortedBy { it.matchDate }
        for ((i, m) in sorted.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setBackgroundColor(if (i % 2 == 0) Color.parseColor("#08FFFFFF") else Color.TRANSPARENT)
            }

            val dateShort = try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val d = sdf.parse(m.matchDate) ?: Date()
                SimpleDateFormat("MM/dd", Locale.CHINA).format(d)
            } catch (_: Exception) { m.matchDate.takeLast(5) }

            row.addView(TextView(ctx).apply {
                text = dateShort
                setTextColor(Color.parseColor("#8888AA")); textSize = 10f
                layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT)
            })

            val oppShort = if (m.opponent.length > 8) m.opponent.take(7) + "…" else m.opponent
            row.addView(TextView(ctx).apply {
                text = oppShort
                setTextColor(Color.parseColor("#BBBBBB")); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            val rt = m.rating
            if (rt != null && rt > 0) {
                val rtColor = when {
                    rt >= 8.0 -> "#00CC66"
                    rt >= 7.0 -> "#FFD700"
                    rt >= 6.0 -> "#FF6B35"
                    else -> "#E94560"
                }
                row.addView(TextView(ctx).apply {
                    text = String.format("%.1f", rt)
                    setTextColor(Color.parseColor(rtColor)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.END; setPadding(dp(6), 0, 0, 0)
                })
            }
            if (m.goals > 0) row.addView(TextView(ctx).apply {
                text = "⚽${m.goals}"; setTextColor(Color.parseColor(GREEN)); textSize = 11f
                gravity = Gravity.END; setPadding(dp(4), 0, 0, 0)
            })
            if (m.assists > 0) row.addView(TextView(ctx).apply {
                text = "🅰${m.assists}"; setTextColor(Color.parseColor(LIGHT_BLUE)); textSize = 11f
                gravity = Gravity.END; setPadding(dp(4), 0, 0, 0)
            })
            row.addView(TextView(ctx).apply {
                text = "${m.minutes}'"; setTextColor(Color.parseColor(GRAY)); textSize = 10f
                gravity = Gravity.END; setPadding(dp(6), 0, 0, 0)
            })
            card.addView(row)
        }
        container.addView(card)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第5层：生涯荣誉
    // ══════════════════════════════════════════════════════════════════════

    private fun renderHonors(profile: PlayerProfile) {
        val honors = profile.honors
        val hContainer = findViewById<LinearLayout>(R.id.honors_container)
        if (honors.isEmpty()) { hContainer.visibility = View.GONE; return }

        val byCategory = honors.groupBy { it.category }
        for ((category, items) in byCategory) {
            val badge = when {
                category.contains("Winner", true) || category.contains("Champion", true) -> "🏆"
                category.contains("Runner", true) || category.contains("Second", true) -> "🥈"
                else -> "🥉"
            }
            hContainer.addView(TextView(this).apply {
                text = "$badge $category"
                setTextColor(Color.parseColor(GOLD)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, dp(3))
            })
            for (h in items) {
                hContainer.addView(TextView(this).apply {
                    text = "  ${h.title} (${h.year})"
                    setTextColor(Color.parseColor(WHITE)); textSize = 12f
                    setPadding(dp(8), dp(1), 0, dp(1))
                })
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第6层：五维雷达图
    // ══════════════════════════════════════════════════════════════════════

    private fun renderRadarChart(profile: PlayerProfile) {
        val radarData = profile.toRadarData()
        val rContainer = findViewById<LinearLayout>(R.id.radar_chart_container)
        rContainer.removeAllViews()

        val radarView = RadarChartView(this).apply {
            setData(radarData)
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER }
        }
        rContainer.addView(radarView)

        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        for ((i, label) in radarData.labels.withIndex()) {
            val value = radarData.values[i]
            legendRow.addView(TextView(this).apply {
                text = "$label ${value.toInt()}"
                setTextColor(Color.parseColor(LIGHT_BLUE)); textSize = 11f
                setPadding(dp(8), 0, dp(8), 0)
            })
        }
        rContainer.addView(legendRow)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第7层：射门分布图 (BDL match_shots)
    // ══════════════════════════════════════════════════════════════════════

    private fun renderShotMap(profile: PlayerProfile) {
        val shotMap = profile.shotMap
        val container = findViewById<LinearLayout>(R.id.shot_map_container)
        container.removeAllViews()

        if (shotMap == null || shotMap.shots.isEmpty()) {
            // 显示"暂无数据"而非完全空白
            val tv = TextView(this).apply {
                text = "暂无射门数据\n比赛结束后自动更新"
                setTextColor(Color.parseColor("#666688"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            }
            container.addView(tv)
            return
        }

        val shotMapView = ShotMapView(this).apply {
            setData(shotMap)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
            ).apply { setMargins(0, dp(8), 0, dp(8)) }
        }
        container.addView(shotMapView)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════════════════

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
