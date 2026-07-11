package worldcup.helper.ui.teams

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import worldcup.helper.R
import worldcup.helper.data.CircleFlagLoader
import worldcup.helper.data.MatchData
import worldcup.helper.data.repos.SharedRepository
import worldcup.helper.data.repos.TeamRepo
import worldcup.helper.data.model.UnifiedMatch
import worldcup.helper.network.FootballMatchesResponse
import worldcup.helper.ui.match.ShotMapView
import worldcup.helper.network.LiveApiClient
import worldcup.helper.ui.match.PlayerDetailActivity
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class TeamDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEAM_NAME = "team_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team_detail)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.tv_back)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.updatePadding(top = v.paddingTop + top)
            insets
        }

        val teamName = intent.getStringExtra(EXTRA_TEAM_NAME) ?: run { finish(); return }
        val flagLoader = CircleFlagLoader(this)
        val repo = SharedRepository.getInstance(this)

        findViewById<TextView>(R.id.tv_back).setOnClickListener { finish() }

        // 🔴 缓存 API 比分（供赛程列表覆盖使用）
        var apiScoreMap = emptyMap<String, worldcup.helper.data.repos.MatchRepo.ScoreInfo>()
        lifecycleScope.launch {
            apiScoreMap = withContext(Dispatchers.IO) { repo.matches.fetchApiScoreMap() }
        }

        // 通过 TeamRepo 加载球队数据
        val teamDetail = repo.teams.getTeamDetail(teamName)
        if (teamDetail == null) {
            android.util.Log.e("TeamDetail", "未找到球队: $teamName")
            finish(); return
        }

        val basic = teamDetail.basic
        findViewById<TextView>(R.id.tv_team_name).text = basic.nameCn
        findViewById<TextView>(R.id.tv_team_group).text =
            if (basic.group.isNotEmpty()) "${basic.group}组" else ""

        // ⭐ 小组排名 + 统计展示（API优先）
        // 异步获取 standings 后更新 header + 统计卡片
        lifecycleScope.launch {
            try {
                val standings = withContext(Dispatchers.IO) { repo.standings.getStandings() }
                val groupRows = standings[basic.group]
                val rankInfo = groupRows?.find { it.fifaCode == basic.fifaCode }

                if (rankInfo != null) {
                    // 更新 header（排名 + 积分）
                    findViewById<TextView>(R.id.tv_team_group).text = "${basic.group}组 · 第${rankInfo.rank}名"
                    findViewById<TextView>(R.id.tv_team_en_name).text =
                        "${basic.nameEn}  ·  ${rankInfo.points}分"

                    // ✅ 用 API 权威数据覆盖统计卡片
                    val statsContainer = findViewById<LinearLayout>(R.id.ll_team_stats)
                    statsContainer.visibility = View.VISIBLE

                    findViewById<TextView>(R.id.tv_matches_played).text = "${rankInfo.played}"
                    findViewById<TextView>(R.id.tv_wins).text = "${rankInfo.wins}"
                    findViewById<TextView>(R.id.tv_draws).text = "${rankInfo.draws}"
                    findViewById<TextView>(R.id.tv_losses).text = "${rankInfo.losses}"
                    findViewById<TextView>(R.id.tv_goals_for).text = "${rankInfo.goalsFor}"
                    findViewById<TextView>(R.id.tv_goals_against).text = "${rankInfo.goalsAgainst}"

                    val gd = rankInfo.goalDiff
                    val gdStr = if (gd > 0) "+$gd" else "$gd"
                    findViewById<TextView>(R.id.tv_record_summary).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.tv_record_summary).text =
                        "${rankInfo.wins}胜 ${rankInfo.draws}平 ${rankInfo.losses}负 · 进${rankInfo.goalsFor}失${rankInfo.goalsAgainst} · 净胜${gdStr} · ${rankInfo.points}分"
                } else {
                    android.util.Log.w("TeamDetail", "Standings API succeeded but no rankInfo for ${basic.nameEn} (group=${basic.group}, fifa=${basic.fifaCode}, ${standings.size} groups available)")
                    findViewById<TextView>(R.id.tv_team_en_name).text = basic.nameEn
                    // fallback: 尝试直接调 football-data matches API 计算
                    try {
                        val apiMatches = withContext(Dispatchers.IO) {
                            LiveApiClient.footballData.getMatches()
                        }
                        fillStatsFromApiMatches(apiMatches, basic)
                    } catch (_: Exception) {
                        fillStatsFromLocalMatches(teamDetail.schedule, basic)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TeamDetail", "Standings coroutine failed", e)
                findViewById<TextView>(R.id.tv_team_en_name).text = basic.nameEn
                // fallback: 尝试直接调 football-data matches API
                try {
                    val apiMatches = withContext(Dispatchers.IO) {
                        LiveApiClient.footballData.getMatches()
                    }
                    fillStatsFromApiMatches(apiMatches, basic)
                } catch (_: Exception) {
                    fillStatsFromLocalMatches(teamDetail.schedule, basic)
                }
            }
        }

        // 国旗
        val flagIv = findViewById<ImageView>(R.id.iv_team_flag)
        val drawable = flagLoader.loadFlag(basic.fifaCode)
        if (drawable != null) {
            flagIv.setImageDrawable(drawable)
            flagIv.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // 队徽 crest（异步加载）
        lifecycleScope.launch {
            val crestUrl = withContext(Dispatchers.IO) { repo.teams.getCrestUrl(basic.fifaCode) }
            if (!crestUrl.isNullOrEmpty()) {
                flagIv.load(crestUrl) {
                    crossfade(true)
                    error(flagLoader.loadFlag(basic.fifaCode))
                }
            }
        }

        // 阵容 RecyclerView
        val playersList = teamDetail.players.map { p ->
            mapOf<String, Any>(
                "name" to p.name,
                "nameCn" to (p.nameCn.ifEmpty { p.name }),
                "number" to p.jerseyNumber,
                "position" to p.position,
                "positionCn" to p.positionCn,
                "club" to p.club,
                "marketValueMil" to (p.marketValueMil ?: 0.0),
                "photo" to (p.photoUrl ?: "")
            )
        }

        val squadRv = findViewById<RecyclerView>(R.id.rv_squad)
        squadRv.layoutManager = LinearLayoutManager(this)
        squadRv.isNestedScrollingEnabled = false
        squadRv.adapter = SquadAdapter(playersList, basic.nameEn)

        // 🌐 球队附加信息：Elo / 主场 / 阵容规模
        showTeamExtraInfo(teamDetail)

        // 📊 BDL 球队场均数据（异步）
        lifecycleScope.launch {
            try {
                val bdlTeams = withContext(Dispatchers.IO) { LiveApiClient.bdlApi.getTeams() }
                val bdlTeam = bdlTeams.data.find { t ->
                    t.abbreviation?.equals(basic.fifaCode, ignoreCase = true) == true
                } ?: return@launch

                val bdlMatches = withContext(Dispatchers.IO) {
                    LiveApiClient.bdlApi.getMatches(teamIds = listOf(bdlTeam.id))
                }
                val completed = bdlMatches.data.filter { it.status == "completed" }
                if (completed.isEmpty()) return@launch
                val recentIds = completed.takeLast(5).mapNotNull { it.id }

                val rawStats = withContext(Dispatchers.IO) {
                    LiveApiClient.bdlApi.getTeamMatchStats(recentIds)
                }
                val ourStats = rawStats.data.filter { it.team_id == bdlTeam.id }
                if (ourStats.isNotEmpty()) renderBdlTeamStats(ourStats)
            } catch (_: Exception) { /* BDL 增强数据，静默失败 */ }
        }

        // 本地赛程数据（用于近况/赛程展示）
        val stats = teamDetail.schedule
        val finished = stats.filter { isMatchFinishedByTime(it) }

        if (finished.isNotEmpty()) {
            // 🔵 近5场状态条 — 用圆角方块
            val formContainer = findViewById<LinearLayout>(R.id.form_row_container)
            val formSection = findViewById<View>(R.id.form_section)
            val dividerForm = findViewById<View>(R.id.divider_form)
            formSection.visibility = View.VISIBLE
            dividerForm.visibility = View.VISIBLE

            formContainer.addView(TextView(this).apply {
                text = "近5场 "
                setTextColor(Color.parseColor("#8888AA")); textSize = 11f
                setPadding(0, 0, 6, 0)
            })
            val recent5 = finished.takeLast(5)
            for (m in recent5) {
                val isHome = m.homeFifaCode == basic.fifaCode
                val won = if (isHome) m.homeScore > m.awayScore else m.awayScore > m.homeScore
                val drew = m.homeScore == m.awayScore
                val (letter, bgColor) = when {
                    won -> "W" to 0xFF00CC66.toInt()
                    drew -> "D" to 0xFFFFD700.toInt()
                    else -> "L" to 0xFFFF4444.toInt()
                }
                val square = TextView(this).apply {
                    text = letter
                    setTextColor(Color.WHITE); textSize = 10f; typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    val d = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 4f
                        setColor(bgColor)
                    }
                    background = d
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(2, 0, 2, 0) }
                }
                formContainer.addView(square)
            }
        }

        // 🎯 射门分布热力图（赛后聚合，异步加载）
        loadTeamShotMap(basic.nameEn)

        // 赛程列表（标注 API 比分当 API 返回时更新）
        val scheduleSection = findViewById<LinearLayout>(R.id.schedule_section)
        val scheduleContainer = findViewById<LinearLayout>(R.id.schedule_container)
        val dividerSchedule = findViewById<View>(R.id.divider_schedule)
        scheduleSection.visibility = View.VISIBLE
        dividerSchedule.visibility = View.VISIBLE

        // 存储分数 TextViews 用于后续 API 覆盖
        val scoreViews = mutableListOf<Pair<TextView, String>>()

        for (m in stats) {
            // 优先从本地取比分，API 覆盖稍后异步执行
            val localScore = "${m.homeScore}-${m.awayScore}"
            val isFin = isMatchFinishedByTime(m)
            val scoreStr = if (isFin) localScore else "vs"
            val dt = m.datetime
            val datePart = if (dt.length >= 16) dt.substring(5, 16) else dt

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 6, 4, 6)
            }
            // 比分/状态标签
            val tvScore = TextView(this).apply {
                text = scoreStr
                setTextColor(if (isFin) Color.WHITE else Color.parseColor("#FFD700"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = dp(40)
                setPadding(8, 4, 8, 4)
                val pillBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 8f
                    setColor(if (isFin) 0xFF2A2A4A.toInt() else 0x332A2A4A.toInt())
                }
                background = pillBg
            }
            row.addView(tvScore)
            scoreViews.add(tvScore to m.id)
            // 对阵
            row.addView(TextView(this).apply {
                text = "${m.homeTeamCn}  vs  ${m.awayTeamCn}"
                setTextColor(Color.WHITE)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(10, 0, 8, 0)
                }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // 日期
            row.addView(TextView(this).apply {
                text = datePart
                setTextColor(Color.parseColor("#8888AA"))
                textSize = 10f
            })
            scheduleContainer.addView(row)
        }

        // 异步等待 apiScoreMap 加载完成后更新赛程比分
        lifecycleScope.launch {
            // 等待 apiScoreMap 非空（最多等 5 秒）
            var waited = 0
            while (apiScoreMap.isEmpty() && waited < 50) {
                kotlinx.coroutines.delay(100)
                waited++
            }
            if (apiScoreMap.isEmpty()) return@launch
            for ((tvScore, matchId) in scoreViews) {
                val apiScore = apiScoreMap[matchId]
                if (apiScore != null) {
                    val newScore = "${apiScore.homeScore}-${apiScore.awayScore}"
                    tvScore.text = newScore
                    // 本地显示 vs 的已完赛比赛也更新颜色
                    tvScore.setTextColor(Color.WHITE)
                }
            }
        }
    }

    /** 从本地赛程数据填充统计卡片（API fallback） */
    private fun fillStatsFromLocalMatches(schedule: List<UnifiedMatch>, basic: TeamRepo.TeamBasicInfo) {
        val finished = schedule.filter { isMatchFinishedByTime(it) }
        if (finished.isEmpty()) return

        val statsContainer = findViewById<LinearLayout>(R.id.ll_team_stats)
        statsContainer.visibility = View.VISIBLE

        var w = 0; var d = 0; var l = 0; var gf = 0; var ga = 0
        for (m in finished) {
            val isHome = m.homeFifaCode == basic.fifaCode
            val hs = m.homeScore; val aws = m.awayScore
            if (isHome) { gf += hs; ga += aws } else { gf += aws; ga += hs }
            val teamWon = if (isHome) hs > aws else aws > hs
            val teamDrew = hs == aws
            when { teamWon -> w++; teamDrew -> d++; else -> l++ }
        }

        findViewById<TextView>(R.id.tv_matches_played).text = "${w + d + l}"
        findViewById<TextView>(R.id.tv_wins).text = "$w"
        findViewById<TextView>(R.id.tv_draws).text = "$d"
        findViewById<TextView>(R.id.tv_losses).text = "$l"
        findViewById<TextView>(R.id.tv_goals_for).text = "$gf"
        findViewById<TextView>(R.id.tv_goals_against).text = "$ga"

        val gd = gf - ga
        val gdStr = if (gd > 0) "+$gd" else "$gd"
        val pts = w * 3 + d
        findViewById<TextView>(R.id.tv_record_summary).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_record_summary).text =
            "${w}胜 ${d}平 ${l}负 · 进${gf}失${ga} · 净胜${gdStr} · ${pts}分"
    }

    /** 从 football-data matches API 计算球队统计（更可靠的 fallback） */
    private fun fillStatsFromApiMatches(resp: FootballMatchesResponse, basic: TeamRepo.TeamBasicInfo) {
        val teamFifa = basic.fifaCode
        val finished = resp.matches.filter { m ->
            val isFinished = m.status == "FINISHED" || m.status == "COMPLETED" || m.status == "IN_PLAY"
            (m.homeTeam?.tla == teamFifa || m.awayTeam?.tla == teamFifa) && isFinished
        }
        if (finished.isEmpty()) {
            android.util.Log.w("TeamDetail", "fillStatsFromApiMatches: no finished matches for $teamFifa")
            return
        }

        val statsContainer = findViewById<LinearLayout>(R.id.ll_team_stats)
        statsContainer.visibility = View.VISIBLE

        var w = 0; var d = 0; var l = 0; var gf = 0; var ga = 0
        for (m in finished) {
            val isHome = m.homeTeam?.tla == teamFifa
            val hs = m.score?.fullTime?.home ?: 0
            val aws = m.score?.fullTime?.away ?: 0
            if (isHome) { gf += hs; ga += aws } else { gf += aws; ga += hs }
            when {
                hs > aws -> if (isHome) w++ else l++
                hs < aws -> if (isHome) l++ else w++
                else -> d++
            }
        }

        findViewById<TextView>(R.id.tv_matches_played).text = "${w + d + l}"
        findViewById<TextView>(R.id.tv_wins).text = "$w"
        findViewById<TextView>(R.id.tv_draws).text = "$d"
        findViewById<TextView>(R.id.tv_losses).text = "$l"
        findViewById<TextView>(R.id.tv_goals_for).text = "$gf"
        findViewById<TextView>(R.id.tv_goals_against).text = "$ga"

        val gd = gf - ga
        val gdStr = if (gd > 0) "+$gd" else "$gd"
        val pts = w * 3 + d
        findViewById<TextView>(R.id.tv_record_summary).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_record_summary).text =
            "${w}胜 ${d}平 ${l}负 · 进${gf}失${ga} · 净胜${gdStr} · ${pts}分"
    }

    /** 📊 BDL 球队场均数据渲染 */
    private fun renderBdlTeamStats(stats: List<worldcup.helper.network.BdlTeamMatchStats>) {
        val container = findViewById<LinearLayout>(R.id.ll_bdl_stats) ?: return
        val row = findViewById<LinearLayout>(R.id.bdl_stats_row)
        if (stats.isEmpty()) return
        container.visibility = View.VISIBLE

        val n = stats.size.toDouble()
        // 计算平均值（过滤 null）
        val avgPossession = stats.mapNotNull { it.possession }.average().let { "%.0f".format(it) }
        val avgShots = stats.mapNotNull { it.total_shots }.average().let { "%.1f".format(it) }
        val avgSot = stats.mapNotNull { it.shots_on_target }.average().let { "%.1f".format(it) }
        val avgCorners = stats.mapNotNull { it.corners }.average().let { "%.1f".format(it) }
        val avgFouls = stats.mapNotNull { it.fouls }.average().let { "%.0f".format(it) }

        data class StatItem(val label: String, val value: String, val suffix: String, val color: Int)
        val items = listOf(
            StatItem("控球率", "$avgPossession", "%", 0xFF4ECDC4.toInt()),
            StatItem("射门", avgShots, "", 0xFFFF6B35.toInt()),
            StatItem("射正", avgSot, "", 0xFF00CC66.toInt()),
            StatItem("角球", avgCorners, "", 0xFFFFD700.toInt()),
            StatItem("犯规", avgFouls, "", 0xFFFF4444.toInt())
        )

        for (item in items) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = item.value
                setTextColor(item.color)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            col.addView(TextView(this).apply {
                text = item.label
                setTextColor(Color.parseColor("#888888"))
                textSize = 9f
                gravity = Gravity.CENTER
                setPadding(0, 1, 0, 0)
            })
            row.addView(col)
        }
    }

    /** 显示球队附加信息（Elo / 主场 / 阵容规模） */
    private fun showTeamExtraInfo(teamDetail: TeamRepo.TeamDetail) {
        val elo = teamDetail.elo
        val stadium = teamDetail.homeStadium
        val squadSize = teamDetail.players.size

        val tvElo = findViewById<TextView>(R.id.tv_team_elo)
        val tvStadium = findViewById<TextView>(R.id.tv_team_stadium)
        val tvSquadCount = findViewById<TextView>(R.id.tv_team_squad_count)
        val divider1 = findViewById<View>(R.id.divider_extra_1)
        val divider2 = findViewById<View>(R.id.divider_extra_2)

        var anyVisible = false
        val items = mutableListOf<Pair<TextView, View?>>()

        if (elo != null && elo > 0) {
            tvElo.visibility = View.VISIBLE
            tvElo.text = "🌐 Elo $elo"
            anyVisible = true
            items.add(tvElo to divider1)
        }
        if (!stadium.isNullOrBlank()) {
            tvStadium.visibility = View.VISIBLE
            tvStadium.text = "🏟️ $stadium"
            anyVisible = true
            items.add(tvStadium to divider2)
        }
        if (squadSize > 0) {
            tvSquadCount.visibility = View.VISIBLE
            tvSquadCount.text = "👥 ${squadSize}人"
            anyVisible = true
        }

        if (anyVisible) {
            findViewById<View>(R.id.cv_team_extra).visibility = View.VISIBLE
            // 在可见项之间显示分隔线
            var shown = 0
            for ((tv, div) in items) {
                if (tv.visibility == View.VISIBLE && div != null) {
                    shown++
                    if (shown < items.size) div.visibility = View.VISIBLE
                }
            }
        }
    }

    /** 🎯 异步加载射门分布热力图 */
    private fun loadTeamShotMap(teamName: String) {
        lifecycleScope.launch {
            try {
                val shotMap = withContext(Dispatchers.IO) {
                    SharedRepository.getInstance(this@TeamDetailActivity).shotMap.getTeamShotMap(teamName)
                } ?: return@launch

                val container = findViewById<LinearLayout>(R.id.team_content)
                val scheduleSection = findViewById<View>(R.id.schedule_section)
                val idx = container.indexOfChild(scheduleSection)
                if (idx < 0) return@launch

                // 射门图卡片
                val card = LinearLayout(this@TeamDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1A1A2E"))
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) }
                }

                // 标题行
                val header = LinearLayout(this@TeamDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(this@TeamDetailActivity).apply {
                    text = "🎯 射门分布"
                    setTextColor(Color.parseColor("#FF6B35")); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                header.addView(TextView(this@TeamDetailActivity).apply {
                    text = "${shotMap.matchCount}场 · ${shotMap.totalShots}射 ${shotMap.goals}球"
                    setTextColor(Color.parseColor("#8888AA")); textSize = 11f
                })
                card.addView(header)

                // ShotMapView
                val shotMapView = ShotMapView(this@TeamDetailActivity).apply {
                    this.shots = shotMap.shots
                    this.legendText = "共${shotMap.totalShots}射 · ${shotMap.goals}进球 · ${shotMap.shotsOnTarget}射正"
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(280)
                    ).apply { setMargins(0, dp(8), 0, 0) }
                }
                card.addView(shotMapView)

                container.addView(card, idx)
            } catch (_: Exception) { }
        }
    }

    private fun dp(n: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics
    ).toInt()
}

class SquadAdapter(private val players: List<Map<String, Any>>, private val teamName: String) :
    RecyclerView.Adapter<SquadAdapter.ViewHolder>() {

    private val posOrder = mapOf(
        "GK" to 0, "Goalkeeper" to 0, "门将" to 0,
        "DF" to 1, "CB" to 1, "FB" to 1, "Defender" to 1, "Defence" to 1, "后卫" to 1,
        "中后卫" to 1, "边后卫" to 1,
        "MF" to 2, "DM" to 2, "CM" to 2, "AM" to 2, "Midfielder" to 2, "Midfield" to 2, "中场" to 2,
        "防守中场" to 2, "中前卫" to 2, "攻击中场" to 2,
        "FW" to 3, "ST" to 3, "CF" to 3, "Forward" to 3, "Offence" to 3, "前锋" to 3,
        "LW" to 3, "RW" to 3, "左边锋" to 3, "右边锋" to 3, "中锋" to 3
    )

    private val sortedPlayers by lazy {
        players.sortedBy {
            val pos = it["position"] as? String ?: ""
            posOrder[pos] ?: 99
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPhoto: ImageView = view.findViewById(R.id.iv_player_avatar)
        val tvInitials: TextView = view.findViewById(R.id.tv_avatar_initials)
        val tvJerseyBadge: TextView = view.findViewById(R.id.tv_jersey_badge)
        val tvPlayerName: TextView = view.findViewById(R.id.tv_player_name)
        val tvPosition: TextView = view.findViewById(R.id.tv_position)
        val tvClub: TextView = view.findViewById(R.id.tv_club)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player_row, parent, false))
    }

    override fun getItemCount() = sortedPlayers.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = sortedPlayers[position]

        val number = p["number"] as? Double
        val numInt = number?.toInt() ?: 0

        // 号码角标（头像右下角）
        holder.tvJerseyBadge.apply {
            text = if (numInt > 0) "#$numInt" else ""
            visibility = if (numInt > 0) View.VISIBLE else View.INVISIBLE
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val nameCn = p["nameCn"] as? String
        holder.tvPlayerName.text = if (!nameCn.isNullOrEmpty() && nameCn != p["name"]) nameCn else p["name"] as? String ?: "未知"

        val posCn = p["positionCn"] as? String ?: p["position"] as? String ?: ""
        holder.tvPosition.text = posCn

        val marketValue = p["marketValueMil"] as? Double
        val club = p["club"] as? String ?: ""
        val clubText = buildString {
            if (club.isNotEmpty()) append(club)
            if (marketValue != null) {
                if (isNotEmpty()) append(" · ")
                append("€${"%.0f".format(marketValue)}M")
            }
        }
        holder.tvClub.text = clubText.ifEmpty { "" }

        val displayName = if (!nameCn.isNullOrEmpty() && nameCn != p["name"]) nameCn else (p["name"] as? String ?: "")
        val initial = if (displayName.length >= 2) displayName.takeLast(2) else displayName.take(1)
        holder.tvInitials.text = initial

        val photoUrl = p["photo"] as? String
        if (!photoUrl.isNullOrEmpty()) {
            holder.ivPhoto.visibility = View.VISIBLE
            holder.tvInitials.visibility = View.GONE
            holder.ivPhoto.load(photoUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
                placeholder(generateAvatar(holder.itemView.context, displayName, numInt))
                error(generateAvatar(holder.itemView.context, displayName, numInt))
            }
        } else {
            holder.ivPhoto.visibility = View.GONE
            holder.tvInitials.visibility = View.VISIBLE
            val avatarDrawable = generateAvatar(holder.itemView.context, displayName, numInt)
            holder.tvInitials.background = avatarDrawable
        }

        holder.tvPlayerName.setTextColor(Color.WHITE)

        // 点击跳转到 PlayerDetailActivity（统一入口）
        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            val intent = android.content.Intent(context, PlayerDetailActivity::class.java)
            intent.putExtra(PlayerDetailActivity.EXTRA_PLAYER_NAME, p["name"] as? String ?: "")
            intent.putExtra(PlayerDetailActivity.EXTRA_TEAM_NAME, teamName)
            context.startActivity(intent)
        }
    }
}

/** dp转px */
private fun dp(dp: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
    android.content.res.Resources.getSystem().displayMetrics
).toInt()

/** 时间感知判断比赛是否已结束（修复JSON状态滞后问题） */
private fun isMatchFinishedByTime(match: UnifiedMatch): Boolean {
    if (match.status == "FINISHED" || match.status == "COMPLETED") return true
    return try {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val dt = match.datetime
        cal.set(Calendar.YEAR, dt.substring(0, 4).toInt())
        cal.set(Calendar.MONTH, dt.substring(5, 7).toInt() - 1)
        cal.set(Calendar.DAY_OF_MONTH, dt.substring(8, 10).toInt())
        cal.set(Calendar.HOUR_OF_DAY, dt.substring(11, 13).toInt())
        cal.set(Calendar.MINUTE, dt.substring(14, 16).toInt())
        cal.set(Calendar.SECOND, 0)
        val diffMs = System.currentTimeMillis() - cal.timeInMillis
        TimeUnit.MILLISECONDS.toMinutes(diffMs) > 125
    } catch (_: Exception) { false }
}

/** 生成圆形头像（带姓为首字母的彩色圆） */
private fun generateAvatar(context: android.content.Context, name: String, number: Int): Drawable {
    val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, context.resources.displayMetrics).toInt()
    val colors = intArrayOf(
        0xFFE94560.toInt(), 0xFF2ECC71.toInt(), 0xFF3498DB.toInt(),
        0xFF9B59B6.toInt(), 0xFFF39C12.toInt(), 0xFF1ABC9C.toInt(),
        0xFFE67E22.toInt(), 0xFF2980B9.toInt(), 0xFF8E44AD.toInt()
    )
    val colorIndex = (kotlin.math.abs(name.hashCode()) + number) % colors.size
    val bgColor = colors[colorIndex]

    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = bgColor
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    paint.color = Color.argb(40, 255, 255, 255)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, paint)

    paint.color = Color.WHITE
    paint.textSize = size * 0.45f
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textAlign = Paint.Align.CENTER
    paint.isFakeBoldText = true
    val initials = if (name.length >= 2) name.takeLast(2) else name
    val x = size / 2f
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(initials, x, y, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}
