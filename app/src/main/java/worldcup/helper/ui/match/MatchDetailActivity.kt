package worldcup.helper.ui.match

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import worldcup.helper.R
import worldcup.helper.data.CircleFlagLoader
import worldcup.helper.data.MatchData
import worldcup.helper.data.PredictionData
import worldcup.helper.data.TrophyData
import worldcup.helper.data.repos.ShotMapRepo
import worldcup.helper.data.repos.SharedRepository
import worldcup.helper.network.ApiSportsLineupDetail
import worldcup.helper.network.ApiSportsLineupPlayer
import worldcup.helper.network.LiveApiClient
import worldcup.helper.ui.teams.TeamDetailActivity

class MatchDetailActivity : AppCompatActivity() {

    companion object {
        const val GREEN = "#2ECC71"
        const val GREEN_DARK = "#1B8A3C"
        const val GOLD = "#FFD700"
        const val RED = "#E94560"
        const val WHITE = "#FFFFFF"
        const val GRAY = "#888888"
        const val DARK_SURFACE = "#0F2818"
    }

    private var currentTab = 0 // 0=赛况, 1=阵容, 2=数据
    private lateinit var match: MatchData.Match
    private lateinit var matchData: MatchData
    private lateinit var flagLoader: CircleFlagLoader
    private val stadiumRepo by lazy { SharedRepository.getInstance(this).stadiums }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_match_detail)

        // 刘海屏适配
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.tv_back)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.updatePadding(top = v.paddingTop + top)
            insets
        }

        val matchIdStr = intent.getStringExtra("match_id") ?: run { finish(); return }
        matchData = MatchData(this)
        flagLoader = CircleFlagLoader(this)
        match = matchData.matches.find { it.id == matchIdStr } ?: run { finish(); return }
        val matchId = matchIdStr.toIntOrNull() ?: 0
        val pred = PredictionData(this).getPrediction(matchId)
        val status = matchData.getStatus(match)

        // 🔴 API 优先：覆盖实时比分
        lifecycleScope.launch {
            val apiScores = SharedRepository.getInstance(this@MatchDetailActivity).matches.fetchApiScoreMap()
            val score = apiScores[matchIdStr]
            if (score != null && score.status != "SCHEDULED") {
                match = match.copy(
                    homeScore = score.homeScore,
                    awayScore = score.awayScore,
                    status = score.status
                )
                refreshScoreDisplay()
            }
        }

        findViewById<TextView>(R.id.tv_back).setOnClickListener { finish() }

        // === 头部分数 ===
        val homeFlag = findViewById<ImageView>(R.id.iv_home_flag)
        val awayFlag = findViewById<ImageView>(R.id.iv_away_flag)
        findViewById<TextView>(R.id.tv_home_name).text = match.homeTeamCn
        findViewById<TextView>(R.id.tv_away_name).text = match.awayTeamCn

        // ✅ 新架构: 点击球队名 → TeamDetailActivity / 球队资料页
        val homeTeamEn = match.homeTeam
        val awayTeamEn = match.awayTeam
        findViewById<TextView>(R.id.tv_home_name).setOnClickListener {
            startActivity(Intent(this, TeamDetailActivity::class.java).apply {
                putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, homeTeamEn)
            })
        }
        findViewById<TextView>(R.id.tv_away_name).setOnClickListener {
            startActivity(Intent(this, TeamDetailActivity::class.java).apply {
                putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, awayTeamEn)
            })
        }

        val hf = flagLoader.loadFlag(match.homeFifa)
        val af = flagLoader.loadFlag(match.awayFifa)
        if (hf != null) { homeFlag.setImageDrawable(hf) }
        if (af != null) { awayFlag.setImageDrawable(af) }

        // 点击国旗也跳转到球队卡
        homeFlag.setOnClickListener {
            startActivity(Intent(this, TeamDetailActivity::class.java).apply {
                putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, homeTeamEn)
            })
        }
        awayFlag.setOnClickListener {
            startActivity(Intent(this, TeamDetailActivity::class.java).apply {
                putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, awayTeamEn)
            })
        }

        refreshScoreDisplay()

        // === 比赛信息 ===
        val dateLabel = matchData.getDateLabel(match)
        val infoText = "${match.round} · $dateLabel ${match.time} · ${match.venue}"
        findViewById<TextView>(R.id.tv_match_info).text = infoText

        // === 场馆信息卡片 ===
        addStadiumCard(infoText)

        // === AI预测折叠卡片 ===
        if (pred != null) {
            setupPredictionCard(pred)
        } else {
            findViewById<LinearLayout>(R.id.prediction_card).visibility = View.GONE
        }

        // === Tab栏（所有状态都显示） ===
        setupTabs()

        // === 加载数据（含阵容，即使没有事件） ===
        if (status == MatchData.Status.FINISHED || status == MatchData.Status.LIVE) {
            loadFinishedData(matchIdStr)
        }
    }

    private fun setupPredictionCard(pred: PredictionData.Prediction) {
        findViewById<LinearLayout>(R.id.pred_header).setOnClickListener {
            val body = findViewById<LinearLayout>(R.id.pred_body)
            val arrow = findViewById<TextView>(R.id.pred_arrow)
            body.visibility = if (body.visibility == View.GONE) View.VISIBLE else View.GONE
            arrow.text = if (body.visibility == View.VISIBLE) "▲" else "▼"
        }

        val bars = findViewById<LinearLayout>(R.id.ll_win_bars)
        bars.removeAllViews()

        // 单条比例进度条：主队% | 平局% | 客队%
        val total = (pred.teamA.winProb + pred.draw + pred.teamB.winProb).coerceAtLeast(1)
        val hW = (pred.teamA.winProb.toFloat() / total * 100).coerceAtLeast(3f)
        val dW = (pred.draw.toFloat() / total * 100).coerceAtLeast(3f)
        val aW = (pred.teamB.winProb.toFloat() / total * 100).coerceAtLeast(3f)

        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24)
            setPadding(0, 8, 0, 8)
        }
        // 主队段
        barRow.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(RED))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, hW)
        })
        // 平局段
        barRow.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, dW)
        })
        // 客队段
        barRow.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(GREEN))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, aW)
        })
        bars.addView(barRow)

        // 百分比文字行
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        labelRow.addView(TextView(this).apply {
            text = "${pred.teamA.cnName} ${pred.teamA.winProb}%"
            setTextColor(Color.parseColor(RED)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 平局显示在中间
        labelRow.addView(TextView(this).apply {
            text = "平 ${pred.draw}%"
            setTextColor(Color.parseColor("#AAAAAA")); textSize = 12f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        labelRow.addView(TextView(this).apply {
            text = "${pred.teamB.winProb}% ${pred.teamB.cnName}"
            setTextColor(Color.parseColor(GREEN)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bars.addView(labelRow)

        if (!pred.mostLikelyScore.isNullOrEmpty() && pred.mostLikelyScore != "?") {
            findViewById<TextView>(R.id.tv_pred_score).text = "🔮 预计比分: ${pred.mostLikelyScore}"
        }
        if (pred.homeElo > 0) {
            findViewById<TextView>(R.id.tv_elo_stats).text = "ELO: ${pred.homeElo} vs ${pred.awayElo}"
        }
        findViewById<TextView>(R.id.tv_pred_factors).text = pred.keyFactors.joinToString(" · ")
        findViewById<TextView>(R.id.tv_pred_analysis).text = pred.analysis
    }

    private fun setupTabs() {
        val tabs = listOf(R.id.tab_events, R.id.tab_lineup, R.id.tab_stats)
        val contents = listOf(R.id.tab_events_content, R.id.tab_lineup_content, R.id.tab_stats_content)

        // 显示球员 tab（已废弃旧阵容，改为球员名单）
        findViewById<View>(R.id.tab_lineup).visibility = View.VISIBLE
        findViewById<View>(R.id.tab_lineup_content).visibility = View.VISIBLE

        // 隐藏交锋 tab（不需要）
        findViewById<View>(R.id.tab_h2h).visibility = View.GONE
        findViewById<View>(R.id.tab_h2h_content).visibility = View.GONE

        // 设置Tab指示条宽度
        val indicator = findViewById<View>(R.id.tab_indicator)
        val screenW = resources.displayMetrics.widthPixels
        val tabW = screenW / 3f
        indicator.layoutParams = LinearLayout.LayoutParams(tabW.toInt(), 2)

        tabs.forEachIndexed { i, id ->
            findViewById<TextView>(id).setOnClickListener { switchTab(i) }
        }
        switchTab(0)
    }

    private fun switchTab(index: Int) {
        currentTab = index
        val tabs = listOf(R.id.tab_events, R.id.tab_lineup, R.id.tab_stats)
        val contents = listOf(R.id.tab_events_content, R.id.tab_lineup_content, R.id.tab_stats_content)

        tabs.forEachIndexed { i, id ->
            val tv = findViewById<TextView>(id)
            tv.setTextColor(if (i == index) Color.parseColor(GREEN) else Color.parseColor("#666666"))
            tv.textSize = if (i == index) 14f else 13f
            tv.typeface = if (i == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        contents.forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
        }
        // Animate indicator
        val indicator = findViewById<View>(R.id.tab_indicator)
        val screenW = resources.displayMetrics.widthPixels
        val tabW = screenW / 3f
        val anim = ValueAnimator.ofFloat(indicator.translationX, index * tabW).apply {
            duration = 200
            addUpdateListener { indicator.translationX = it.animatedValue as Float }
            start()
        }
    }

    /**
     * 刷新比分显示（由 API 覆盖后调用）
     */
    private fun refreshScoreDisplay() {
        val tvScore = findViewById<TextView>(R.id.tv_score)
        val tvHt = findViewById<TextView>(R.id.tv_ht_score)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val status = matchData.getStatus(match)
        when (status) {
            MatchData.Status.FINISHED -> {
                tvScore.text = "${match.homeScore} : ${match.awayScore}"
                tvStatus.text = "已结束"
                if (match.htHome != null && match.htAway != null) {
                    tvHt.text = "半场 ${match.htHome}:${match.htAway}"
                    tvHt.visibility = View.VISIBLE
                }
            }
            MatchData.Status.LIVE -> {
                tvStatus.text = "🔴 直播中"
                tvStatus.setTextColor(Color.parseColor(RED))
                tvScore.text = "${match.homeScore} : ${match.awayScore}"
                tvScore.setTextColor(Color.parseColor(GOLD))
            }
            else -> {
                tvStatus.text = "未开始"
                tvScore.text = "—:—"
                tvScore.setTextColor(Color.parseColor("#555577"))
                tvStatus.setTextColor(Color.parseColor("#555577"))
            }
        }
    }

    private fun showEmptyState() {
        findViewById<View>(R.id.tab_events_content).visibility = View.GONE
        findViewById<View>(R.id.tab_lineup_content).visibility = View.GONE
        findViewById<View>(R.id.tab_stats_content).visibility = View.GONE
    }

    private fun addStadiumCard(infoText: String) {
        // 查找场馆信息
        val stadiumName = match.stadium.ifEmpty {
            val found = stadiumRepo.findStadium(match.homeTeam)
            found?.name ?: ""
        }
        val capacity = if (stadiumName.isNotEmpty()) {
            val found = stadiumRepo.findStadium(stadiumName)
            if (found != null) "（${found.capacity}席）" else ""
        } else ""

        if (stadiumName.isEmpty() && match.stadiumCity.isEmpty()) return

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(16, 0, 16, 8)
            }
        }
        card.addView(TextView(this).apply {
            text = "📍 "
            textSize = 16f
        })
        card.addView(TextView(this).apply {
            text = buildString {
                if (stadiumName.isNotEmpty()) append(stadiumName)
                if (capacity.isNotEmpty()) append(" $capacity")
                if (match.stadiumCity.isNotEmpty()) append(" · ${match.stadiumCity}")
            }
            setTextColor(Color.parseColor(GRAY))
            textSize = 12f
        })
        val parent = findViewById<LinearLayout>(R.id.score_header).parent as LinearLayout
        val infoTv = findViewById<TextView>(R.id.tv_match_info)
        val infoIdx = parent.indexOfChild(infoTv)
        parent.addView(card, infoIdx + 1)
    }

    // ========================================================================
    // 加载完赛数据
    // ========================================================================
    private data class EventInfo(
        val type: String, val minute: Int, val team: String, val teamCn: String,
        val player: String, val playerCn: String, val assist: String, val score: String
    )
    private data class PlayerMatchStat(
        val name: String, val nameCn: String, val number: Int, val position: String,
        val isStarter: Boolean, val rating: Double, val minutes: Int,
        val goals: Int, val assists: Int, val yellowCards: Int, val redCards: Int,
        val shots: Int, val passes: Int, val tackles: Int,
        val photoUrl: String = "",
        val grid: String = "",       // api-sports 网格坐标，如 "2:3"
        val apiSportsId: Int = 0     // 用于 TrophyData 查询荣誉
    )

    private var homeStats: Map<String, Any> = emptyMap()
    private var awayStats: Map<String, Any> = emptyMap()
    private var events: List<EventInfo> = emptyList()
    private var homeLineup: List<PlayerMatchStat> = emptyList()
    private var awayLineup: List<PlayerMatchStat> = emptyList()
    private var homeSubs: List<PlayerMatchStat> = emptyList()
    private var awaySubs: List<PlayerMatchStat> = emptyList()
    private var homeFormation = "4-3-3"
    private var awayFormation = "4-3-3"
    private var homeCoach: String = ""
    private var awayCoach: String = ""
    private var bestPlayer: String = ""  // 全场最佳球员名
    private var bestPlayerRating: String = ""

    /** 球员详情数据（用于球员Tab展示） */
    private data class PlayerOnField(
        val name: String, val nameCn: String, val number: Int,
        val position: String, val rating: Double, val isHome: Boolean,
        val goals: Int, val assists: Int,
        val shots: Int, val passes: Int, val tackles: Int,
        val interceptions: Int, val fouls: Int, val yellowCards: Int,
        val minutes: Int, val photoUrl: String = ""
    )
    private var homeOnField: List<PlayerOnField> = emptyList()
    private var awayOnField: List<PlayerOnField> = emptyList()

    /** 球员英文名 → 中文名缓存 (从 players_2026.json 构建，覆盖 1246 人) */
    private val chineseNameMap: Map<String, String> by lazy {
        try {
            val json = assets.open("players_2026.json").bufferedReader().use { it.readText() }
            val data = Gson().fromJson(json, Map::class.java)
            val teams = data["teams"] as? List<Map<String, Any>> ?: return@lazy emptyMap()
            val map = mutableMapOf<String, String>()
            for (team in teams) {
                val players = team["players"] as? List<Map<String, Any>> ?: continue
                for (p in players) {
                    val name = (p["name"] as? String) ?: continue
                    val nameCn = (p["nameCn"] as? String)?.takeIf { it.isNotEmpty() } ?: continue
                    map[name] = nameCn
                    // 也存小写
                    map[name.lowercase()] = nameCn
                    // 存缩写 key
                    val parts = name.split(" ")
                    if (parts.size >= 2) {
                        val initial = "${parts[0].take(1)}. ${parts.drop(1).joinToString(" ")}"
                        map[initial] = nameCn
                        map[initial.lowercase()] = nameCn
                    }
                }
            }
            Log.d("MatchDetail", "中文名映射加载完成: ${map.size} 条")
            map
        } catch (e: Exception) {
            Log.e("MatchDetail", "中文名映射加载失败", e)
            emptyMap()
        }
    }

    private fun loadFinishedData(matchId: String) {
        try {
            val json = assets.open("match_events.json").bufferedReader().use { it.readText() }
            val allEvents: Map<String, Any> = Gson().fromJson(json, object : TypeToken<Map<String, Any>>() {}.type)

            // Parse match events stats
            val ed = allEvents[matchId] as? Map<String, Any>
            val rawEvents = ed?.get("events") as? List<Map<String, Any>> ?: emptyList()
            events = rawEvents.map { e ->
                val eventType = e["type"] as? String ?: "goal"
                val rawPlayer = e["player"] as? String ?: ""
                val rawPlayerCn = e["playerCn"] as? String ?: ""
                val rawAssist = e["assist"] as? String ?: ""

                // 换人事件特殊处理：本地JSON的playerCn格式为"被换下↓ 换上↑"
                val parsedSubOn: String
                val parsedSubOff: String
                if (eventType == "sub") {
                    // 尝试解析 "名字↓ 名字↑" 格式
                    val downIdx = rawPlayerCn.indexOf("↓")
                    val upIdx = rawPlayerCn.indexOf("↑")
                    if (downIdx >= 0 && upIdx > downIdx) {
                        parsedSubOff = rawPlayerCn.substring(0, downIdx).trim()
                        parsedSubOn = rawPlayerCn.substring(downIdx + 1, upIdx).trim()
                    } else {
                        // fallback: player是下场，assist留空
                        parsedSubOn = rawPlayerCn.ifEmpty { rawPlayer }
                        parsedSubOff = rawPlayer
                    }
                } else {
                    parsedSubOn = rawPlayerCn.ifEmpty { rawPlayer }
                    parsedSubOff = rawAssist
                }

                // 始终优先用 findChineseName 查询完整中文名
                val subOnCn = findChineseName(parsedSubOn).ifEmpty { parsedSubOn }
                val subOffCn = if (parsedSubOff.isNotEmpty()) findChineseName(parsedSubOff).ifEmpty { parsedSubOff } else ""

                EventInfo(
                    type = eventType,
                    minute = (e["minute"] as? Double)?.toInt() ?: 0,
                    team = e["team"] as? String ?: "",
                    teamCn = e["teamCn"] as? String ?: "",
                    player = parsedSubOn,
                    playerCn = subOnCn,
                    assist = subOffCn,
                    score = e["score"] as? String ?: ""
                )
            }

            val sd = ed?.get("stats") as? Map<String, Any>
            if (sd != null) {
                homeStats = sd["home"] as? Map<String, Any> ?: emptyMap()
                awayStats = sd["away"] as? Map<String, Any> ?: emptyMap()
            }

            // 第一步：从本地生成阵容（兜底方案，显示所有球员）
            generateLineups()

            // 第二步：异步尝试 API 获取真实数据（在线优先）
            loadApiLineups(matchId)
            loadApiEvents(matchId)
            loadApiStats(matchId)

            // 第三步：聚合球员个人统计（补充 tackles/interceptions/clearances 等字段）
            loadApiPlayerStats(matchId)

            // 第四步：API 升级最佳球员
            loadApiBestPlayer(matchId)

            // Render
            renderTimeline()
            renderLineup()
            renderStats()

            // 加载单场射门图
            loadApiShotMap(matchId)
        } catch (e: Exception) {
            Log.e("MatchDetail", "Load error", e)
        }
    }

    /** 从 api-sports 异步拉取真实阵容，成功后替换本地数据并重新渲染 */
    private fun loadApiLineups(matchId: String) {
        lifecycleScope.launch {
            try {
                // 1. 从映射表获取 api-sports fixture ID
                val fixtureId = getFixtureId(matchId)

                if (fixtureId == null) {
                    Log.d("MatchDetail", "match $matchId: 未找到 api-sports fixture ID，跳过 lineup API")
                    return@launch
                }

                Log.d("MatchDetail", "正在从 api-sports 拉取阵容 (fixture=$fixtureId)")

                // 2. 调用 API
                val resp = withContext(Dispatchers.IO) {
                    LiveApiClient.apiSports.getFixtureLineups(fixtureId)
                }
                val lineups = resp.response
                if (lineups.isEmpty()) {
                    Log.d("MatchDetail", "API 返回空阵容，保留本地数据")
                    return@launch
                }

                // 3. 将 API 阵容数据转换为 PlayerMatchStat
                val homeTeamNameEn = match.homeTeam
                val awayTeamNameEn = match.awayTeam

                for (apiLineup in lineups) {
                    val apiTeam = apiLineup.team
                    val isHome = apiTeam.name.equals(homeTeamNameEn, ignoreCase = true) ||
                            apiTeam.name.contains(homeTeamNameEn, ignoreCase = true) ||
                            homeTeamNameEn.contains(apiTeam.name, ignoreCase = true)
                    val isAway = !isHome && (apiTeam.name.equals(awayTeamNameEn, ignoreCase = true) ||
                            apiTeam.name.contains(awayTeamNameEn, ignoreCase = true) ||
                            awayTeamNameEn.contains(apiTeam.name, ignoreCase = true))
                    if (!isHome && !isAway) continue

                    // 从本地 players_2026.json 获取中文名和照片
                    val localPlayers = if (isHome) homeLineup + homeSubs else awayLineup + awaySubs

                    // 构建多key映射：全名 + 小写 + 缩写（"L. Messi"→"Lionel Messi"）
                    val localMap = mutableMapOf<String, PlayerMatchStat>()
                    for (lp in localPlayers) {
                        val lower = lp.name.lowercase()
                        localMap[lower] = lp
                        localMap[lp.name] = lp
                        val parts = lp.name.split(" ")
                        if (parts.size >= 2) {
                            val abbr = "${parts[0].take(1)}. ${parts.drop(1).joinToString(" ")}"
                            localMap[abbr] = lp
                            localMap[abbr.lowercase()] = lp
                        }
                    }

                    fun convertApiPlayer(apiPlayer: ApiSportsLineupPlayer, isStarter: Boolean): PlayerMatchStat {
                        val p = apiPlayer.player
                        val apiName = p.name ?: ""
                        // 多策略匹配本地球员
                        var local = localMap[apiName]
                        if (local == null) local = localMap[apiName.lowercase()]
                        if (local == null) {
                            local = localMap.entries.firstOrNull {
                                it.key.contains(apiName, ignoreCase = true) ||
                                apiName.lowercase().contains(it.key.lowercase())
                            }?.value
                        }
                        val cnName = if (local != null) local.nameCn else findChineseName(apiName)

                        return PlayerMatchStat(
                            name = apiName,
                            nameCn = cnName,
                            number = local?.number ?: 0,
                            position = local?.position ?: "",
                            isStarter = isStarter,
                            rating = local?.rating ?: 6.0,
                            minutes = if (isStarter) 90 else 0,
                            goals = local?.goals ?: 0,
                            assists = local?.assists ?: 0,
                            yellowCards = local?.yellowCards ?: 0,
                            redCards = local?.redCards ?: 0,
                            shots = local?.shots ?: 0,
                            passes = local?.passes ?: 0,
                            tackles = local?.tackles ?: 0,
                            photoUrl = local?.photoUrl ?: "",
                            grid = apiPlayer.grid ?: "",
                            apiSportsId = local?.apiSportsId ?: 0
                        )
                    }

                    val starters = apiLineup.startXI.map { convertApiPlayer(it, true) }
                    val subs = apiLineup.substitutes.map { convertApiPlayer(it, false) }

                    if (isHome) {
                        homeLineup = starters
                        homeSubs = subs
                        homeFormation = apiLineup.formation ?: homeFormation
                        homeCoach = apiLineup.coach?.name ?: ""
                    } else {
                        awayLineup = starters
                        awaySubs = subs
                        awayFormation = apiLineup.formation ?: awayFormation
                        awayCoach = apiLineup.coach?.name ?: ""
                    }
                }

                Log.d("MatchDetail", "API 阵容更新成功: ${homeLineup.size}+${homeSubs.size} / ${awayLineup.size}+${awaySubs.size}")

                // 4. 重新渲染阵容
                withContext(Dispatchers.Main) {
                    renderLineup()
                }
            } catch (e: Exception) {
                Log.e("MatchDetail", "API 阵容拉取失败，保留本地数据", e)
            }
        }
    }

    /** 从 api-sports 异步拉取实时事件，成功后替换本地数据并重新渲染时间线 */
    private fun loadApiEvents(matchId: String) {
        lifecycleScope.launch {
            try {
                val fixtureId = getFixtureId(matchId) ?: run {
                    Log.d("MatchDetail", "events: 未找到 fixture ID，跳过 API"); return@launch
                }

                Log.d("MatchDetail", "正在从 api-sports 拉取事件 (fixture=$fixtureId)")
                val resp = withContext(Dispatchers.IO) {
                    LiveApiClient.apiSports.getFixtureEvents(fixtureId)
                }
                val rawEvents = resp.response
                if (rawEvents.isEmpty()) {
                    Log.d("MatchDetail", "API 返回空事件，保留本地数据"); return@launch
                }

                // 转换为 EventInfo
                val homeCn = match.homeTeamCn
                val awayCn = match.awayTeamCn
                val mappedEvents = rawEvents.mapNotNull { e ->
                    val time = e.time?.elapsed ?: return@mapNotNull null
                    val team = e.team?.name ?: return@mapNotNull null
                    val player = e.player?.name ?: ""
                    val type = when (e.type) {
                        "Goal" -> "goal"; "Card" -> {
                            when (e.detail) {
                                "Yellow Card" -> "yellow"; "Red Card" -> "red"
                                else -> "card"
                            }
                        }
                        "subst" -> "sub"; "Var" -> "var"
                        else -> "other"
                    }

                    // 判断所属队伍的中文名
                    val isHome = team.contains(match.homeTeam, ignoreCase = true) ||
                        match.homeTeam.contains(team, ignoreCase = true)
                    val teamCn = if (isHome) homeCn else awayCn

                    EventInfo(
                        type = type, minute = time,
                        team = team, teamCn = teamCn,
                        player = player,
                        playerCn = findChineseName(player),
                        assist = findChineseName(e.assist?.name ?: ""),
                        score = ""
                    )
                }

                if (mappedEvents.isNotEmpty()) {
                    events = mappedEvents
                    Log.d("MatchDetail", "API 事件更新成功: ${events.size} 条")
                    withContext(Dispatchers.Main) { renderTimeline() }
                }
            } catch (e: Exception) {
                Log.e("MatchDetail", "API 事件拉取失败，保留本地数据", e)
            }
        }
    }

    /** 从 api-sports 异步拉取实时统计，成功后替换本地数据并重新渲染 */
    private fun loadApiStats(matchId: String) {
        lifecycleScope.launch {
            try {
                val fixtureId = getFixtureId(matchId) ?: run {
                    Log.d("MatchDetail", "stats: 未找到 fixture ID，跳过 API"); return@launch
                }

                Log.d("MatchDetail", "正在从 api-sports 拉取统计 (fixture=$fixtureId)")
                val resp = withContext(Dispatchers.IO) {
                    LiveApiClient.apiSports.getFixtureStatistics(fixtureId)
                }
                val teamsStats = resp.response
                if (teamsStats.size < 2) {
                    Log.d("MatchDetail", "API 返回统计不足，保留本地数据"); return@launch
                }

                // 将 api-sports statistics 转为 Map<String, Any>
                val homeTeamName = match.homeTeam
                val awayTeamName = match.awayTeam

                for (ts in teamsStats) {
                    val teamName = ts.team.name ?: ""
                    val isHome = teamName.contains(homeTeamName, ignoreCase = true) ||
                        homeTeamName.contains(teamName, ignoreCase = true)
                    val isAway = !isHome && (teamName.contains(awayTeamName, ignoreCase = true) ||
                        awayTeamName.contains(teamName, ignoreCase = true))
                    if (!isHome && !isAway) continue

                    val statMap = mutableMapOf<String, Any>()
                    for (item in ts.statistics) {
                        val key = item.type ?: continue
                        val value = item.value ?: 0
                        // 统一 key 命名
                        statMap[normalizeStatKey(key)] = when (value) {
                            is String -> value; is Number -> value; is Boolean -> value.toString()
                            else -> value.toString()
                        }
                    }
                    if (isHome) {
                        // "合并"而非"替换"：API 只返回部分字段（控球/射门/角球/犯规等）
                        // 本地数据有抢断/拦截/解围/长传/传中/过人 等 API 不提供的字段
                        homeStats = homeStats + statMap
                    } else {
                        awayStats = awayStats + statMap
                    }
                }

                Log.d("MatchDetail", "API 统计更新成功")
                withContext(Dispatchers.Main) { renderStats() }
            } catch (e: Exception) {
                Log.e("MatchDetail", "API 统计拉取失败，保留本地数据", e)
            }
        }
    }

    /** 通过 fixture_id_map.json 从 matchId 反查 api-sports fixture ID */
    private fun getFixtureId(matchId: String): Int? {
        return try {
            val mapJson = assets.open("fixture_id_map.json").bufferedReader().use { it.readText() }
            val mapData = Gson().fromJson(mapJson, Map::class.java)
            val mapping = mapData["mapping"] as? Map<String, Any> ?: emptyMap()
            val raw = mapping[matchId]
            when (raw) {
                is Double -> raw.toInt()
                is Int -> raw
                is Long -> raw.toInt()
                is Map<*, *> -> {
                    val id = raw["api_sports_fixture_id"]
                    when (id) {
                        is Double -> id.toInt()
                        is Int -> id
                        is Long -> id.toInt()
                        else -> null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("MatchDetail", "fixture_id_map 查找失败", e)
            null
        }
    }

    /**
     * 从 api-sports fixtures/players 聚合球员个人数据到团队统计
     * 覆盖: tackles/interceptions/clearances/passes/dribbles/saves
     * 这些字段 fixtures/statistics API 不提供，但 fixtures/players API 每个球员都有
     */
    private fun loadApiPlayerStats(matchId: String) {
        lifecycleScope.launch {
            try {
                val fixtureId = getFixtureId(matchId) ?: return@launch
                Log.d("MatchDetail", "正在从 api-sports 拉取球员统计聚合 (fixture=$fixtureId)")

                // ── Step 1: api-sports 基础数据 ──
                val resp = withContext(Dispatchers.IO) {
                    LiveApiClient.apiSports.getFixturePlayers(fixtureId)
                }
                val teamStats = resp.response
                if (teamStats.size < 2) {
                    Log.d("MatchDetail", "球员统计不足"); return@launch
                }

                val homeTeamName = match.homeTeam
                val awayTeamName = match.awayTeam

                // ── Step 2: 合并数据并写入 homeStats/awayStats ──
                for (ts in teamStats) {
                    val teamName = ts.team.name ?: ""
                    val isHome = teamName.contains(homeTeamName, ignoreCase = true) ||
                        homeTeamName.contains(teamName, ignoreCase = true)
                    val isAway = !isHome && (teamName.contains(awayTeamName, ignoreCase = true) ||
                        awayTeamName.contains(teamName, ignoreCase = true))
                    if (!isHome && !isAway) continue

                    var tackles = 0; var interceptions = 0; var clearances = 0
                    var passes = 0; var dribbles = 0; var saves = 0
                    var shots = 0; var shotsOn = 0; var fouls = 0

                    // ── 构建球员列表（用于球员Tab） ──
                    val onFieldPlayers = mutableListOf<PlayerOnField>()

                    for (p in ts.players) {
                        val player = p.player; val statistics = p.statistics
                        var pRating = 0.0; var pGoals = 0; var pAssists = 0
                        var pShots = 0; var pPasses = 0; var pTackles = 0
                        var pInt = 0; var pFouls = 0; var pYellow = 0; var pMinutes = 0; var pNumber = 0
                        for (stat in statistics) {
                            val t = stat.tackles
                            tackles += t?.total ?: 0
                            interceptions += t?.interceptions ?: 0
                            clearances += t?.blocks ?: 0

                            val pass = stat.passes
                            passes += pass?.total ?: 0

                            val drib = stat.dribbles
                            dribbles += drib?.success ?: 0

                            saves += 0
                            shots += stat.shots?.total ?: 0
                            shotsOn += stat.shots?.on ?: 0
                            fouls += stat.fouls?.committed ?: 0

                            pRating = stat.games?.rating?.toDoubleOrNull() ?: 0.0
                            pGoals = stat.goals?.total ?: 0
                            pAssists = stat.goals?.assists ?: 0
                            pShots = stat.shots?.total ?: 0
                            pPasses = pass?.total ?: 0
                            pTackles = t?.total ?: 0
                            pInt = t?.interceptions ?: 0
                            pFouls = stat.fouls?.committed ?: 0
                            pYellow = stat.cards?.yellow ?: 0
                            pMinutes = stat.games?.minutes ?: 0
                            pNumber = stat.games?.number ?: 0
                        }
                        val apiName = player.name ?: ""
                        val cnName = findChineseName(apiName)
                        val photo = player.photo ?: ""
                        if (pMinutes > 0 || pRating > 0) {
                            onFieldPlayers.add(PlayerOnField(
                                name = apiName, nameCn = cnName, number = pNumber,
                                position = "", rating = pRating, isHome = isHome,
                                goals = pGoals, assists = pAssists,
                                shots = pShots, passes = pPasses, tackles = pTackles,
                                interceptions = pInt, fouls = pFouls,
                                yellowCards = pYellow, minutes = pMinutes,
                                photoUrl = photo
                            ))
                        }
                    }

                    val aggMap = mutableMapOf<String, Int>()
                    aggMap["tackles"] = tackles
                    aggMap["interceptions"] = interceptions
                    aggMap["clearances"] = clearances
                    aggMap["passes"] = passes
                    aggMap["dribbles"] = dribbles
                    aggMap["saves"] = saves
                    aggMap["shots"] = shots
                    aggMap["shotsOnTarget"] = shotsOn
                    aggMap["fouls"] = fouls

                    val filtered = aggMap.filter { it.value > 0 }

                    if (isHome) {
                        homeStats = homeStats + filtered
                        homeOnField = onFieldPlayers
                    } else {
                        awayStats = awayStats + filtered
                        awayOnField = onFieldPlayers
                    }
                    Log.d("MatchDetail", "球员聚合: ${if(isHome)"主"else"客"}队 tackles=$tackles int=$interceptions")
                }

                withContext(Dispatchers.Main) {
                    renderStats()
                    renderPlayers()
                }
            } catch (e: Exception) {
                Log.e("MatchDetail", "球员统计聚合失败", e)
            }
        }
    }

    /** api-sports 统计字段名 → 统一为 renderStats() 期望的 camelCase key */
    private fun normalizeStatKey(raw: String): String = when (raw.lowercase()) {
        "ball possession" -> "possession"
        "total shots" -> "shots"
        "shots on goal" -> "shotsOnTarget"
        "shots off goal" -> "shotsOffTarget"
        "blocked shots" -> "blockedShots"
        "shots insidebox" -> "shotsInsideBox"
        "shots outsidebox" -> "shotsOutsideBox"
        "corner kicks" -> "corners"
        "fouls" -> "fouls"
        "yellow cards" -> "yellowCards"
        "red cards" -> "redCards"
        "offsides" -> "offsides"
        "total passes" -> "passes"
        "passes accurate" -> "passAccuracy"
        "expected_goals" -> "expectedGoals"
        "goalkeeper saves" -> "saves"
        "free kicks" -> "freeKicks"
        "tackles" -> "tackles"
        "interceptions" -> "interceptions"
        "clearances" -> "clearances"
        "crosses" -> "crosses"
        "dribbles" -> "dribbles"
        "long balls" -> "longBalls"
        else -> raw.lowercase().replace(" ", "_")
    }

    /** 从 Any (String/Number/Double) 提取 Int 值，用于统计对比 */
    private fun statToInt(value: Any?): Int {
        return when (value) {
            is Double -> value.toInt()
            is Long -> value.toInt()
            is Int -> value
            is String -> {
                // 去除 "%" " " 等后缀，再转数字
                val cleaned = value.replace(Regex("[^0-9.]"), "")
                cleaned.toIntOrNull() ?: 0
            }
            is Boolean -> if (value) 1 else 0
            else -> 0
        }
    }

    /** 从 BDL 异步拉取全场最佳球员（仅在已完赛时调用） */
    private fun loadApiBestPlayer(matchId: String) {
        val matchIdInt = matchId.toIntOrNull() ?: return
        lifecycleScope.launch {
            try {
                Log.d("MatchDetail", "正在从 BDL 拉取全场最佳")
                val resp = withContext(Dispatchers.IO) {
                    LiveApiClient.bdlApi.getMatchBestPlayers(matchIds = listOf(matchIdInt))
                }
                val data = resp.data
                val motm = data.firstOrNull()
                if (motm == null) {
                    Log.d("MatchDetail", "BDL 未返回最佳球员数据")
                    return@launch
                }

                bestPlayer = motm.player_name ?: ""
                bestPlayerRating = motm.rating ?: ""

                if (bestPlayer.isNotEmpty()) {
                    Log.d("MatchDetail", "全场最佳: $bestPlayer")
                    // 尝试从本地球员数据找中文名
                    val cnName = findChineseName(bestPlayer)
                    if (cnName != bestPlayer) bestPlayer = cnName
                    withContext(Dispatchers.Main) { renderBestPlayer() }
                }
            } catch (e: Exception) {
                Log.e("MatchDetail", "BDL 最佳球员拉取失败", e)
            }
        }
    }

    /** 从 BDL 异步拉取单场射门图（赛后），成功则在统计下方渲染 */
    private fun loadApiShotMap(matchId: String) {
        val matchIdInt = matchId.toIntOrNull() ?: return
        lifecycleScope.launch {
            try {
                Log.d("MatchDetail", "正在从 BDL 拉取射门图")
                val resp = withContext(Dispatchers.IO) {
                    LiveApiClient.bdlApi.getMatchShots(listOf(matchIdInt))
                }
                val shots = resp.data
                if (shots.isEmpty()) {
                    Log.d("MatchDetail", "BDL 射门数据为空"); return@launch
                }

                val homeShots = shots.count { it.team_id == null || it.team_id == 0 }
                val total = shots.size
                val goals = shots.count { it.is_goal }
                val onTarget = shots.count { s ->
                    s.is_goal || "savedshot".equals(s.result, ignoreCase = true) ||
                    "saved".equals(s.result, ignoreCase = true)
                }

                Log.d("MatchDetail", "Shot map: ${shots.size} shots, $goals goals, $onTarget on target")

                val shotMapView = ShotMapView(this@MatchDetailActivity).apply {
                    this.shots = shots.map { s ->
                        ShotMapRepo.ShotEntry(
                            playerName = findChineseName(s.player_name ?: ""),
                            minute = s.minute ?: 0,
                            x = (s.player_x ?: 50.0).toFloat(),
                            y = (s.player_y ?: 50.0).toFloat(),
                            xg = s.xg ?: 0.0,
                            result = when {
                                s.is_goal -> ShotMapRepo.ShotResult.GOAL
                                "savedshot".equals(s.result, ignoreCase = true) ||
                                "saved".equals(s.result, ignoreCase = true) -> ShotMapRepo.ShotResult.ON_TARGET
                                "blockedshot".equals(s.result, ignoreCase = true) ||
                                "blocked".equals(s.result, ignoreCase = true) -> ShotMapRepo.ShotResult.BLOCKED
                                "missedshots".equals(s.result, ignoreCase = true) ||
                                "off_target".equals(s.result, ignoreCase = true) ||
                                "missed".equals(s.result, ignoreCase = true) -> ShotMapRepo.ShotResult.OFF_TARGET
                                "shotonpost".equals(s.result, ignoreCase = true) -> ShotMapRepo.ShotResult.POST
                                else -> ShotMapRepo.ShotResult.UNKNOWN
                            },
                            bodyPart = s.body_part ?: "",
                            isGoal = s.is_goal
                        )
                    }
                    this.legendText = "共${total}射 · ${goals}进球 · ${onTarget}射正"
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
                    ).apply { setMargins(0, dp(8), 0, 0) }
                }

                // 在统计 Tab 底部追加射门图
                val statsContainer = findViewById<LinearLayout>(R.id.stats_container)
                val shotCard = LinearLayout(this@MatchDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1A1A2E"))
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(12), 0, 0) }
                }
                shotCard.addView(TextView(this@MatchDetailActivity).apply {
                    text = "🎯 射门分布"
                    setTextColor(Color.parseColor("#FF6B35")); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                })
                shotCard.addView(shotMapView)
                statsContainer.addView(shotCard)
            } catch (e: Exception) {
                Log.e("MatchDetail", "BDL 射门图拉取失败", e)
            }
        }
    }

    /** 通过球员英文名查找中文名（映射表已含全名+缩写+小写） */
    private fun findChineseName(engName: String): String {
        if (engName.isEmpty()) return engName
        // 0. 直接返回缓存中的中文名（如果有的话）
        chineseNameMap[engName]?.let { return it }
        val lower = engName.lowercase()
        chineseNameMap[lower]?.let { return it }

        // 0b. Unicode 归一化（处理带重音符号的匹配: "Eustáquio" vs "Eustaquio"）
        val normalized = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")  // 去掉所有组合用重音标记
        chineseNameMap.entries.firstOrNull { (key) ->
            val keyNorm = java.text.Normalizer.normalize(key.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
            keyNorm == normalized
        }?.let { return it.value }

        // 1. 缩写 key 匹配: 构造 "K. Mbappé" → "k. mbappé" 查表
        // 支持 "M. Neuer" 格式
        val parts = engName.split(" ")
        if (parts.size >= 2 && parts[0].length == 1 && parts[0].endsWith(".")) {
            val abbrKey = "${parts[0]} ${parts.drop(1).joinToString(" ")}"
            chineseNameMap[abbrKey]?.let { return it }
            chineseNameMap[abbrKey.lowercase()]?.let { return it }
        }

        // 2. 尝试只用姓氏匹配（处理 "Nathan-Dylan Saliba" → 匹配 "Nathan Saliba"）
        val lastName = parts.lastOrNull()?.lowercase() ?: ""
        if (lastName.length > 2) {
            chineseNameMap.entries.firstOrNull { (key) ->
                val keyLast = key.split(" ").lastOrNull()?.lowercase() ?: ""
                keyLast == lastName || key.lowercase().endsWith(lastName)
            }?.let { return it.value }
        }

        // 3. 部分匹配（最终兜底）
        chineseNameMap.entries.firstOrNull { (key) ->
            key.lowercase().contains(lower) || lower.contains(key.lowercase())
        }?.let { return it.value }

        return engName
    }

    private fun generateLineups() {
        try {
            val json = assets.open("players_2026.json").bufferedReader().use { it.readText() }
            val data = Gson().fromJson(json, Map::class.java)
            val teams = data["teams"] as? List<Map<String, Any>> ?: return

            val homeTeamPlayers = mutableListOf<Map<String, Any>>()
            val awayTeamPlayers = mutableListOf<Map<String, Any>>()

            val homeCn = match.homeTeamCn
            val awayCn = match.awayTeamCn
            val homeEn = match.homeTeam

            for (t in teams) {
                val tName = t["name"] as? String ?: ""
                val tCn = MatchData.getChineseName(tName)
                val tCode = t["countryCode"] as? String ?: ""
                // 多维度别名匹配（players_2026 和 matches 队名可能不同）
                val isHome = tName.equals(homeEn, ignoreCase = true) ||
                    tCn == homeCn || tName == homeCn ||
                    (match.homeFifa.isNotEmpty() && tCode == match.homeFifa)
                val isAway = tName.equals(match.awayTeam, ignoreCase = true) ||
                    tCn == awayCn || tName == awayCn ||
                    (match.awayFifa.isNotEmpty() && tCode == match.awayFifa)
                when {
                    isHome -> homeTeamPlayers.addAll(t["players"] as? List<Map<String, Any>> ?: emptyList())
                    isAway -> awayTeamPlayers.addAll(t["players"] as? List<Map<String, Any>> ?: emptyList())
                }
            }

            // 从真实事件数据提取球员统计（进球/助攻/红黄牌）
            val homeEventMap = mutableMapOf<String, Triple<Int, Int, String>>() // name -> (goals, assists, cards)
            val awayEventMap = mutableMapOf<String, Triple<Int, Int, String>>()
            for (evt in events) {
                val targetMap = if (evt.teamCn == homeCn || evt.team == match.homeTeam) homeEventMap else awayEventMap
                val key = evt.playerCn.ifEmpty { evt.player }
                val existing = targetMap.getOrDefault(key, Triple(0, 0, ""))
                val newCards = when (evt.type) {
                    "yellow" -> existing.third + "Y"
                    "red" -> existing.third + "R"
                    else -> existing.third
                }
                when (evt.type) {
                    "goal" -> targetMap[key] = Triple(existing.first + 1, existing.second, newCards)
                    "assist" -> targetMap[key] = Triple(existing.first, existing.second + 1, newCards)
                    "yellow", "red" -> targetMap[key] = Triple(existing.first, existing.second, newCards)
                }
            }

            fun playersToStats(players: List<Map<String, Any>>, isHome: Boolean): Pair<List<PlayerMatchStat>, List<PlayerMatchStat>> {
                val starters = mutableListOf<PlayerMatchStat>()
                val subs = mutableListOf<PlayerMatchStat>()
                val eventMap = if (isHome) homeEventMap else awayEventMap

                for ((idx, p) in players.withIndex()) {
                    val name = p["name"] as? String ?: ""
                    val nameCn = p["nameCn"] as? String ?: name
                    val num = when (val n = p["jerseyNumber"]) {
                        is Double -> n.toInt(); is Long -> n.toInt(); is Int -> n; else -> idx + 1
                    }
                    val pos = p["position"] as? String ?: ""
                    val photoUrl = p["photo_url"] as? String ?: ""
                    val isStarter = idx < 11

                    // 从真实事件数据读取进球/助攻/红黄牌
                    val lookupKeys = listOf(nameCn, name, nameCn.replace("·", " "), nameCn.replace("·", ""))
                    var eventGoals = 0; var eventAssists = 0; var eventCards = ""
                    for (lk in lookupKeys) {
                        val match = eventMap[lk]
                        if (match != null) { eventGoals = match.first; eventAssists = match.second; eventCards = match.third; break }
                    }
                    // 也尝试模糊匹配
                    if (eventGoals == 0 && eventAssists == 0) {
                        for ((k, v) in eventMap) {
                            val lastName = name.split(" ").lastOrNull() ?: ""
                            val cnLastName = nameCn.takeLast(2)
                            if (k.contains(lastName, ignoreCase = true) || k.contains(cnLastName)) {
                                eventGoals = v.first; eventAssists = v.second; eventCards = v.third; break
                            }
                        }
                    }

                    val rating = 6.0 + eventGoals * 0.5 + eventAssists * 0.3 - (if (eventCards.contains("R")) 1.5 else 0.0) - (if (eventCards.contains("Y")) 0.5 else 0.0)
                    val mins = if (isStarter) 90 else 15
                    val yc = if (eventCards.contains("Y")) 1 else 0
                    val rc = if (eventCards.contains("R")) 1 else 0

                    val apiId = when (val a = p["api_sports_id"]) {
                        is Double -> a.toInt(); is Long -> a.toInt(); is Int -> a; else -> 0
                    }
                    val stat = PlayerMatchStat(
                        name = name, nameCn = nameCn, number = num, position = pos, isStarter = isStarter,
                        rating = rating.coerceIn(5.0, 10.0), minutes = mins,
                        goals = eventGoals, assists = eventAssists, yellowCards = yc, redCards = rc,
                        shots = 0, passes = 0, tackles = 0,
                        photoUrl = photoUrl,
                        apiSportsId = apiId
                    )
                    if (isStarter) starters.add(stat) else subs.add(stat)
                }
                return Pair(starters.take(11), subs)
            }

            val (hs, hsub) = playersToStats(homeTeamPlayers, true)
            val (as_, asub) = playersToStats(awayTeamPlayers, false)
            homeLineup = hs; awayLineup = as_; homeSubs = hsub; awaySubs = asub
            homeFormation = detectFormation(homeLineup); awayFormation = detectFormation(awayLineup)
        } catch (e: Exception) {
            android.util.Log.e("MatchDetail", "Lineup gen error", e)
        }
    }

    private fun detectFormation(players: List<PlayerMatchStat>): String {
        val fieldPos = players.take(11)
        val defence = fieldPos.count { it.position.contains("后卫") || it.position.contains("边后卫") || it.position == "DF" }
        val midfield = fieldPos.count { it.position.contains("中场") || it.position.contains("防守中场") || it.position == "MF" }
        val forward = fieldPos.count { it.position.contains("前锋") || it.position.contains("边锋") || it.position == "FW" }
        val gk = 1
        val def = defence.coerceIn(3, 5)
        val mid = (11 - gk - def - forward).coerceIn(2, 5)
        val fwd = (11 - gk - def - mid).coerceIn(1, 4)
        return "$def-$mid-$fwd"
    }

    // ========================================================================
    // Tab 2: 球员 — 双方上场球员卡片
    // ========================================================================
    private fun renderPlayers() {
        val container = findViewById<LinearLayout>(R.id.lineup_container)
        container.removeAllViews()

        val homePlayers = homeOnField.ifEmpty { homeLineup.take(11).map { p ->
            PlayerOnField(p.name, p.nameCn, p.number, p.position, p.rating, true,
                p.goals, p.assists, p.shots, p.passes, p.tackles, 0, 0, p.yellowCards, p.minutes, p.photoUrl)
        } }
        val awayPlayers = awayOnField.ifEmpty { awayLineup.take(11).map { p ->
            PlayerOnField(p.name, p.nameCn, p.number, p.position, p.rating, false,
                p.goals, p.assists, p.shots, p.passes, p.tackles, 0, 0, p.yellowCards, p.minutes, p.photoUrl)
        } }

        if (homePlayers.isEmpty() && awayPlayers.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (match.status == "TIMED" || matchData.getStatus(match) == MatchData.Status.UPCOMING)
                    "⏳ 比赛尚未开始，暂无球员数据" else "⏳ 加载中..."
                setTextColor(Color.parseColor(GRAY)); textSize = 13f; gravity = Gravity.CENTER
                setPadding(0, 40, 0, 40)
            })
            return
        }

        // ── 主队 ──
        addTeamPlayersSection(container, match.homeTeamCn, homePlayers, true)
        // ── 分割 ──
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1A3A2A"))
        })
        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8.dpToPx())
        })
        // ── 客队 ──
        addTeamPlayersSection(container, match.awayTeamCn, awayPlayers, false)
    }

    /** 绘制一队的上场球员卡片网格 */
    private fun addTeamPlayersSection(parent: LinearLayout, teamCn: String, players: List<PlayerOnField>, isHome: Boolean) {
        val color = if (isHome) 0xFFFF7043.toInt() else 0xFF42A5F5.toInt()

        // 队名头部
        parent.addView(TextView(this).apply {
            text = "$teamCn 上场球员 (${players.size})"
            setTextColor(color); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 8)
        })

        // 球员卡片网格（每行3个）
        var row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        for ((idx, p) in players.withIndex()) {
            val card = createPlayerCard(p, isHome)
            row.addView(card)
            if (idx % 3 == 2 || idx == players.size - 1) {
                parent.addView(row)
                if (idx < players.size - 1) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                }
            }
        }
    }

    /** 创建单个球员卡片（头像+名字+号码+评分） */
    private fun createPlayerCard(p: PlayerOnField, isHome: Boolean): LinearLayout {
        val ratingColor = when {
            p.rating >= 8.0 -> 0xFFFFD700.toInt()  // 金色
            p.rating >= 6.5 -> 0xFF00FF88.toInt()  // 绿色
            p.rating >= 5.0 -> 0xFFFFA500.toInt()  // 橙色
            p.rating > 0 -> 0xFFE94560.toInt()     // 红色
            else -> 0xFF666666.toInt()
        }
        val ctx = this

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(3, 3, 3, 3)
            }
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { showPlayerDetailDialog(p, isHome) }

            // 头像
            val avatarSize = 48.dpToPx()
            val avatarIv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (p.photoUrl.isNotEmpty()) {
                    load(p.photoUrl) { crossfade(true); placeholder(R.mipmap.ic_launcher) }
                } else {
                    setImageResource(R.mipmap.ic_launcher)
                }
                // 圆形裁剪
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                setBackgroundResource(R.drawable.bg_flag_circle)
            }
            addView(avatarIv)

            // 号码
            addView(TextView(ctx).apply {
                text = "#${p.number}"
                setTextColor(if (isHome) 0xFFFF7043.toInt() else 0xFF42A5F5.toInt())
                textSize = 10f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })

            // 名字
            val displayName = p.nameCn.ifEmpty { p.name }
            addView(TextView(ctx).apply {
                text = displayName
                setTextColor(0xFFFFFFFF.toInt()); textSize = 11f
                gravity = Gravity.CENTER; maxLines = 1
            })

            // 评分
            if (p.rating > 0) {
                addView(TextView(ctx).apply {
                    text = "%.1f".format(p.rating)
                    setTextColor(ratingColor); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
            } else {
                addView(TextView(ctx).apply {
                    text = "—"
                    setTextColor(0xFF666666.toInt()); textSize = 11f
                    gravity = Gravity.CENTER
                })
            }
        }
    }

    /** 球员详情弹窗 — 实时数据 + 查看球员卡按钮 */
    private fun showPlayerDetailDialog(p: PlayerOnField, isHome: Boolean) {
        val ctx = this@MatchDetailActivity
        val teamCn = if (isHome) match.homeTeamCn else match.awayTeamCn
        val ratingStr = if (p.rating > 0) "%.1f".format(p.rating) else "—"

        val statLines = buildString {
            append("🏅 评分: $ratingStr\n")
            append("⏱ 出场: ${p.minutes}'\n")
            if (p.goals > 0) append("⚽ 进球: ${p.goals}\n")
            if (p.assists > 0) append("🅰️ 助攻: ${p.assists}\n")
            if (p.shots > 0) append("🎯 射门: ${p.shots}\n")
            if (p.passes > 0) append("🎯 传球: ${p.passes}\n")
            if (p.tackles > 0) append("💪 抢断: ${p.tackles}\n")
            if (p.interceptions > 0) append("🔄 拦截: ${p.interceptions}\n")
            if (p.fouls > 0) append("⚠️ 犯规: ${p.fouls}\n")
            if (p.yellowCards > 0) append("🟨 黄牌: ${p.yellowCards}\n")
        }

        val displayName = p.nameCn.ifEmpty { p.name }
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
            .setTitle("$displayName (#${p.number}) · $teamCn")
            .setMessage(statLines.ifEmpty { "暂无详细数据" })
            .setPositiveButton("📋 查看球员卡") { _, _ ->
                val intent = Intent(ctx, PlayerDetailActivity::class.java).apply {
                    putExtra(PlayerDetailActivity.EXTRA_PLAYER_NAME, p.name)
                    putExtra(PlayerDetailActivity.EXTRA_TEAM_NAME,
                        if (isHome) match.homeTeam else match.awayTeam)
                }
                startActivity(intent)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // ========================================================================
    // Tab 1: 赛况 — 时间线
    // ========================================================================
    private fun renderTimeline() {
        val container = findViewById<LinearLayout>(R.id.timeline_container)
        container.removeAllViews()

        // 比赛信息摘要卡片（始终显示）
        addMatchSummaryCard(container)

        if (events.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (matchData.getStatus(match) == MatchData.Status.LIVE)
                    "🔴 比赛进行中，暂无实时事件\n请关注后续更新"
                else
                    "暂无详细事件数据"
                setTextColor(Color.parseColor(GRAY)); gravity = Gravity.CENTER
                setPadding(0, 40, 0, 40); textSize = 14f
            })
            return
        }

        val sorted = events.sortedBy { it.minute }
        for (evt in sorted) {
            addEventRowCompact(container, evt)
        }

        // 比赛结束标记
        val end = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 10, 0, 4)
        }
        end.addView(TextView(this).apply { text = "┃"; setTextColor(Color.parseColor(GOLD)); textSize = 16f })
        end.addView(TextView(this).apply {
            text = "比赛结束 · ${match.homeTeamCn} ${match.homeScore}:${match.awayScore} ${match.awayTeamCn}"
            setTextColor(Color.parseColor(GOLD)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setPadding(10, 4, 10, 4)
        })
        end.addView(TextView(this).apply { text = "┃"; setTextColor(Color.parseColor(GOLD)); textSize = 16f })
        container.addView(end)
    }

    /** 赛况标签页顶部 — 比赛摘要卡片 */
    private fun addMatchSummaryCard(container: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 12)
            }
        }

        // 标题行
        card.addView(TextView(this).apply {
            text = "📋 比赛摘要"
            setTextColor(Color.parseColor("#FF6B35")); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        })

        // 比分 + 半场 — 用 matchData.getStatus 实时判断，不依赖 match.status（API可能未覆盖）
        val localStatus = matchData.getStatus(match)
        val prefix = if (localStatus == MatchData.Status.LIVE) "🔴 实时" else "✅"
        val scoreText = if (match.htHome != null && match.htAway != null)
            "$prefix ${match.homeTeamCn} ${match.homeScore} - ${match.awayScore} ${match.awayTeamCn}  (半场 ${match.htHome}:${match.htAway})"
        else
            "$prefix ${match.homeTeamCn} ${match.homeScore} - ${match.awayScore} ${match.awayTeamCn}"

        card.addView(TextView(this).apply {
            text = scoreText; setTextColor(Color.parseColor(WHITE)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 6)
        })

        // 比赛信息行
        val dateLabel = matchData.getDateLabel(match)
        val venueStr = buildString {
            append(match.round); append(" · "); append(dateLabel); append(" "); append(match.time)
            if (match.stadium.isNotEmpty()) { append(" · "); append(match.stadium) }
        }
        card.addView(TextView(this).apply {
            text = venueStr; setTextColor(Color.parseColor("#8888AA")); textSize = 11f
            setPadding(0, 0, 0, 4)
        })

        // 比赛事件统计
        val goalEvents = events.filter { it.type == "goal" }
        val homeGoals = goalEvents.count { it.teamCn == match.homeTeamCn || it.team == match.homeTeam }
        val awayGoals = goalEvents.count { it.teamCn == match.awayTeamCn || it.team == match.awayTeam }
        val yellows = events.count { it.type == "yellow" }
        val reds = events.count { it.type == "red" }
        val subs = events.count { it.type == "sub" }

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0)
        }
        val summaryItems = listOf(
            "⚽ ${match.homeScore}:${match.awayScore}" to RED,
            "🟨 $yellows" to GOLD,
            "🟥 $reds" to RED,
            "🔄 $subs" to GREEN
        )
        for ((text, color) in summaryItems) {
            statsRow.addView(TextView(this).apply {
                this.text = text; setTextColor(Color.parseColor(color)); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        card.addView(statsRow)

        // 数据来源标签
        card.addView(TextView(this).apply {
            text = if (events.any { it.player.isNotEmpty() }) "📡 数据来源: API 实时" else "💾 数据来源: 本地预设"
            setTextColor(Color.parseColor(GRAY)); textSize = 9f
            setPadding(0, 6, 0, 0)
        })

        container.addView(card)
    }

    private fun addEventRowCompact(container: LinearLayout, evt: EventInfo) {
        val isHome = evt.team == match.homeTeam
        val eventColor = if (isHome) RED else GREEN
        val align = if (isHome) Gravity.START else Gravity.END

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 3, 0, 3)
        }

        // Left content
        val leftWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
        }
        // Right content
        val rightWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 6
            }
        }

        // Time in center with circle
        val timeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val icon = when (evt.type) {
            "goal" -> "⚽"; "yellow" -> "🟨"; "red" -> "🟥"
            "sub" -> "🔄"; "var" -> "📺"; else -> "▸"
        }

        // Player name label
        val detailText = when (evt.type) {
            "goal" -> {
                if (evt.assist.isNotEmpty()) "${evt.playerCn} \n(${evt.assist}助攻)"
                else evt.playerCn
            }
            "sub" -> if (evt.assist.isNotEmpty()) "⬆️${evt.playerCn} ⬇️${evt.assist}" else "⬆️${evt.playerCn}"
            "var" -> "VAR介入"
            "yellow" -> "${evt.playerCn} 🟨"
            "red" -> "${evt.playerCn} 🟥"
            else -> evt.playerCn
        }

        if (isHome) {
            // Left: home team event
            val eventIcon = TextView(this).apply {
                text = "$icon ${evt.minute}'"; setTextColor(Color.parseColor(eventColor)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            }
            leftWrap.addView(eventIcon)

            val playerLabel = if (evt.type == "goal" && evt.assist.isNotEmpty()) {
                val label = TextView(this).apply {
                    text = evt.playerCn; setTextColor(Color.parseColor(WHITE)); textSize = 12f
                    maxLines = 2; gravity = Gravity.END
                }
                val assistLabel = TextView(this).apply {
                    text = "助: ${evt.assist}"; setTextColor(Color.parseColor(GRAY)); textSize = 10f
                    gravity = Gravity.END
                }
                leftWrap.addView(label)
                leftWrap.addView(assistLabel)
            } else {
                val label = TextView(this).apply {
                    text = when (evt.type) {
                        "sub" -> if (evt.assist.isNotEmpty()) "⬆️${evt.playerCn}\n⬇️${evt.assist}" else "⬆️${evt.playerCn}"
                        else -> evt.playerCn
                    }
                    setTextColor(Color.parseColor(WHITE)); textSize = 12f
                    maxLines = 2; gravity = Gravity.END
                }
                leftWrap.addView(label)
            }

            timeBlock.addView(TextView(this).apply {
                text = "${evt.minute}'"; setTextColor(Color.parseColor(GOLD)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            })
            timeBlock.addView(View(this).apply {
                setBackgroundColor(Color.parseColor(GOLD))
                layoutParams = LinearLayout.LayoutParams(2, dp(24))
            })

            row.addView(leftWrap)
            row.addView(timeBlock)
            row.addView(rightWrap) // empty
        } else {
            // Right: away team event
            timeBlock.addView(TextView(this).apply {
                text = "${evt.minute}'"; setTextColor(Color.parseColor(GOLD)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            })
            timeBlock.addView(View(this).apply {
                setBackgroundColor(Color.parseColor(GOLD))
                layoutParams = LinearLayout.LayoutParams(2, dp(24))
            })

            val eventIcon = TextView(this).apply {
                text = "${evt.minute}' $icon"; setTextColor(Color.parseColor(eventColor)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            }
            rightWrap.addView(eventIcon)

            if (evt.type == "goal" && evt.assist.isNotEmpty()) {
                rightWrap.addView(TextView(this).apply {
                    text = evt.playerCn; setTextColor(Color.parseColor(WHITE)); textSize = 12f; maxLines = 2
                })
                rightWrap.addView(TextView(this).apply {
                    text = "助: ${evt.assist}"; setTextColor(Color.parseColor(GRAY)); textSize = 10f
                })
            } else {
                rightWrap.addView(TextView(this).apply {
                    text = if (evt.type == "sub" && evt.assist.isNotEmpty()) "⬆️${evt.playerCn}\n⬇️${evt.assist}" else evt.playerCn
                    setTextColor(Color.parseColor(WHITE)); textSize = 12f; maxLines = 2
                })
            }

            row.addView(leftWrap) // empty
            row.addView(timeBlock)
            row.addView(rightWrap)
        }

        container.addView(row)
    }

    // ========================================================================
    // Tab 2: 阵容 — 绿茵场+球员位置
    // ========================================================================
    private fun renderLineup() {
        val container = findViewById<LinearLayout>(R.id.lineup_container)
        container.removeAllViews()

        // 主队
        addTeamLineupSection(container, match.homeTeamCn, homeFormation, homeLineup, homeSubs, true, homeCoach)
        // 分割
        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, 16, 0, 16) }
        })
        // 客队
        addTeamLineupSection(container, match.awayTeamCn, awayFormation, awayLineup, awaySubs, false, awayCoach)
    }

    private fun addTeamLineupSection(container: LinearLayout, teamName: String, formation: String,
                                      starters: List<PlayerMatchStat>, subs: List<PlayerMatchStat>,
                                      isHome: Boolean, coach: String = "") {
        // 队名 + 阵型 + 教练
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        header.addView(TextView(this).apply {
            text = teamName; setTextColor(Color.parseColor(WHITE)); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = formation; setTextColor(Color.parseColor(GREEN)); textSize = 13f
            background = resources.getDrawable(R.drawable.bg_round_tag, theme)
            setPadding(8, 3, 8, 3)
        })
        container.addView(header)

        // 教练信息（仅API有数据时显示）
        if (coach.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "教练: $coach"
                setTextColor(Color.parseColor("#8888AA")); textSize = 11f
                setPadding(0, 0, 0, 6)
            })
        }

        // 绿茵场 -- 按 grid 坐标布局的阵型图
        val fieldMargin = 8
        val fieldHeight = dp(240)
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.parseColor("#0D3B1A"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fieldHeight).apply {
                setMargins(0, 4, 0, 4)
            }
        }

        val starters11 = starters.take(11)

        // 判断是否有 grid 坐标（API 返回的数据）
        val hasGrid = starters11.any { it.grid.isNotEmpty() }

        if (hasGrid) {
            // 方案A：有网格坐标 → 按 formation 行布局
            renderFormationGrid(field, starters11, isHome)
        } else {
            // 方案B：无网格坐标 → 按位置分组（旧方案，本地数据兜底）
            val byPos = starters11.groupBy { posGroup(it.position) }
            val order = listOf("FW", "MID", "DEF", "GK")
            for (group in order) {
                val players = byPos[group] ?: continue
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                for (p in players) {
                    row.addView(createPlayerChip(p, isHome))
                }
                field.addView(row)
            }
        }

        container.addView(field)

        // 替补
        container.addView(TextView(this).apply {
            text = "替补"; setTextColor(Color.parseColor("#999999")); textSize = 12f
            setPadding(0, 10, 0, 6)
        })
        val subGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (p in subs.take(12)) {
            subGrid.addView(createSubRow(p, isHome))
        }
        container.addView(subGrid)
    }

    /**
     * 按阵型行布局：根据 formation 字符串（如 "4-3-3"）分配每行人数
     * 🔴 修复：按球员实际位置分组，不再机械切分列表
     */
    private fun renderFormationGrid(field: LinearLayout, starters: List<PlayerMatchStat>, isHome: Boolean) {
        val formationStr = if (isHome) homeFormation else awayFormation
        val formationParts = formationStr.split("-").mapNotNull { it.toIntOrNull() }
        val actualFormation = formationParts.ifEmpty { listOf(4, 3, 3) }

        // 按实际位置分组
        val byPos = starters.take(11).groupBy { posGroup(it.position) }
        val gkList = byPos["GK"] ?: emptyList()
        val defList = byPos["DEF"] ?: emptyList()
        val midList = byPos["MID"] ?: emptyList()
        val fwList = byPos["FW"] ?: emptyList()

        // 按阵型人数分配（取足阵型要求的数量，多余的归入该行末尾）
        val defCount = actualFormation.getOrElse(0) { 4 }.coerceIn(1, 6)
        val midCount = actualFormation.getOrElse(1) { 3 }.coerceIn(1, 6)
        val fwCount = actualFormation.getOrElse(2) { 3 }.coerceIn(1, 4)

        // 从上到下渲染: FW, MID, DEF, GK
        val fwRow = fwList.take(fwCount.coerceAtLeast(1))
        val midRow = midList.take(midCount.coerceAtLeast(1))
        val defRow = defList.take(defCount.coerceAtLeast(1))
        val gkRow = gkList.take(1)

        // 如果某行没球员，从其他行补足
        val allAssigned = fwRow + midRow + defRow + gkRow
        val remaining = starters.take(11).filter { it !in allAssigned }
        val finalRows = listOf(fwRow, midRow, defRow, gkRow)

        for ((rowIdx, rowPlayers) in finalRows.withIndex()) {
            val players = if (rowPlayers.isEmpty() && remaining.isNotEmpty()) {
                // 该行空 → 从剩余补一位
                val p = remaining.first()
                remaining.toMutableList().remove(p)
                listOf(p)
            } else rowPlayers

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (p in players) {
                row.addView(createPlayerChip(p, isHome))
            }
            field.addView(row)
        }
    }

    private fun posGroup(pos: String): String {
        return when {
            pos.contains("门将") || pos == "GK" -> "GK"
            pos.contains("后卫") || pos == "DF" || pos.contains("边后卫") || pos.contains("中后卫") -> "DEF"
            pos.contains("中场") || pos == "MF" || pos.contains("防守中场") -> "MID"
            else -> "FW"
        }
    }

    private fun createPlayerChip(p: PlayerMatchStat, isHome: Boolean): View {
        val accent = if (isHome) RED else GREEN
        val w = (resources.displayMetrics.widthPixels - dp(40)) / 5
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(3, 4, 3, 4); setOnClickListener { showPlayerDetail(p, isHome) }
        }
        val size = dp(50)
        if (p.photoUrl.isNotEmpty()) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size); scaleType = ImageView.ScaleType.CENTER_CROP
                background = resources.getDrawable(R.drawable.bg_flag_circle, theme)
            }
            iv.load(p.photoUrl) { crossfade(true) }
            card.addView(iv)
        } else {
            val bg = LinearLayout(this).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(size, size); setBackgroundColor(Color.parseColor(if (isHome) "#4A0A0A" else "#0A2A0A")) }
            bg.addView(TextView(this).apply { text = "#${p.number}"; setTextColor(Color.parseColor(WHITE)); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
            card.addView(bg)
        }
        val dn = when {
            p.nameCn.isNotEmpty() && p.nameCn.length <= 4 -> p.nameCn
            p.nameCn.isNotEmpty() -> p.nameCn.take(4)  // 长中文名取前4字
            p.name.length > 12 -> p.name.take(10)
            else -> p.name
        }
        card.addView(TextView(this).apply { text = dn; setTextColor(Color.parseColor(WHITE)); textSize = 9f; gravity = Gravity.CENTER; maxLines = 1; setPadding(0, 3, 0, 0) })
        card.addView(TextView(this).apply { text = "#${p.number}"; setTextColor(Color.parseColor(accent)); textSize = 10f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        return card
    }

    private fun createSubRow(p: PlayerMatchStat, isHome: Boolean): View {
        val color = if (isHome) RED else GREEN
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            background = resources.getDrawable(R.drawable.bg_card, theme)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 3, 0, 3)
            }
            setOnClickListener { showPlayerDetail(p, isHome) }
        }

        val numTv = TextView(this).apply {
            text = "${p.number}"; setTextColor(Color.parseColor(WHITE)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(36, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(numTv)

        // 小头像
        if (p.photoUrl.isNotEmpty()) {
            val iv = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(28, 28).apply { marginStart = 4 }; scaleType = ImageView.ScaleType.CENTER_CROP }
            iv.load(p.photoUrl) { crossfade(true) }
            row.addView(iv)
        }

        val nameTv = TextView(this).apply {
            text = if (p.nameCn.isNotEmpty()) p.nameCn else p.name
            setTextColor(Color.parseColor(WHITE)); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4 }
        }

        val statText = buildString {
            if (p.goals > 0) append("⚽${p.goals} ")
            if (p.assists > 0) append("🎯${p.assists} ")
            if (p.yellowCards > 0) append("🟨")
            if (p.redCards > 0) append("🟥")
        }
        val statTv = TextView(this).apply {
            text = statText.ifEmpty { "${p.minutes}'" }
            setTextColor(Color.parseColor(color)); textSize = 11f
        }

        val ratingTv = TextView(this).apply {
            text = String.format("%.1f", p.rating)
            setTextColor(Color.parseColor(color)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(40, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 4 }
        }

        row.addView(numTv); row.addView(nameTv); row.addView(statTv); row.addView(ratingTv)
        return row
    }

    private fun showPlayerDetail(p: PlayerMatchStat, isHome: Boolean) {
        val color = if (isHome) RED else GREEN
        val builder = AlertDialog.Builder(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "#${p.number} ${p.nameCn}"; setTextColor(Color.parseColor(WHITE)); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = String.format("%.1f", p.rating); setTextColor(Color.parseColor(color)); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(headerRow)

        content.addView(TextView(this).apply {
            text = p.position; setTextColor(Color.parseColor(GRAY)); textSize = 12f
            setPadding(0, 4, 0, 12)
        })

        // Stats grid
        val gridAdapter = listOf(
            "出场时间" to "${p.minutes}'", "进球" to "${p.goals}", "助攻" to "${p.assists}",
            "射门" to "${p.shots}", "传球" to "${p.passes}", "抢断" to "${p.tackles}",
            "黄牌" to "${p.yellowCards}", "红牌" to "${p.redCards}"
        )

        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        for (rowIdx in 0..1) {
            val rowGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            for (col in 0..3) {
                val idx = rowIdx * 4 + col
                if (idx >= gridAdapter.size) break
                val (label, value) = gridAdapter[idx]
                val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }
                item.addView(TextView(this).apply { text = value; setTextColor(Color.parseColor(WHITE)); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
                item.addView(TextView(this).apply { text = label; setTextColor(Color.parseColor(GRAY)); textSize = 11f })
                rowGrid.addView(item)
            }
            grid.addView(rowGrid)
        }
        content.addView(grid)

        // 荣誉墙（从 TrophyData 加载）
        if (p.apiSportsId > 0) {
            try {
                val trophyData = TrophyData(this)
                val summary = trophyData.getTrophiesSummary(p.apiSportsId)
                if (summary.isNotEmpty()) {
                    content.addView(TextView(this).apply {
                        text = summary
                        setTextColor(Color.parseColor("#AAAAAA"))
                        textSize = 11f
                        setLineSpacing(0f, 1.3f)
                        setPadding(0, 16, 0, 0)
                    })
                }
            } catch (_: Exception) { }
        }

        builder.setView(content)
        builder.setPositiveButton("查看完整资料") { _, _ ->
            startActivity(Intent(this, PlayerDetailActivity::class.java).apply {
                putExtra(PlayerDetailActivity.EXTRA_PLAYER_NAME, p.nameCn.ifEmpty { p.name })
                putExtra(PlayerDetailActivity.EXTRA_TEAM_NAME,
                    if (isHome) match.homeTeam else match.awayTeam)
            })
        }
        builder.setNegativeButton("关闭", null)
        builder.show()
    }

    // ========================================================================
    // Tab 3: 数据 — 统计对比
    // ========================================================================
    private fun renderStats() {
        val container = findViewById<LinearLayout>(R.id.stats_container)
        container.removeAllViews()

        val statDefs = listOf(
            Triple("控球率", "possession", "%"),
            Triple("射门", "shots", ""),
            Triple("射正", "shotsOnTarget", ""),
            Triple("射偏", "shotsOffTarget", ""),
            Triple("被封堵", "blockedShots", ""),
            Triple("角球", "corners", ""),
            Triple("任意球", "freeKicks", ""),
            Triple("越位", "offsides", ""),
            Triple("犯规", "fouls", ""),
            Triple("黄牌", "yellowCards", ""),
            Triple("红牌", "redCards", ""),
            Triple("传球", "passes", ""),
            Triple("传球成功率", "passAccuracy", "%"),
            Triple("抢断", "tackles", ""),
            Triple("拦截", "interceptions", ""),
            Triple("解围", "clearances", ""),
            Triple("门将扑救", "saves", ""),
            Triple("过人", "dribbles", "")
        )

        // Team labels
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        labelRow.addView(TextView(this).apply {
            text = match.homeTeamCn; setTextColor(Color.parseColor(RED)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        })
        labelRow.addView(TextView(this).apply {
            text = "统计项"; setTextColor(Color.parseColor("#999999")); textSize = 12f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f)
        })
        labelRow.addView(TextView(this).apply {
            text = match.awayTeamCn; setTextColor(Color.parseColor(GREEN)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        })
        container.addView(labelRow)

        for ((label, key, suffix) in statDefs) {
            val hv = statToInt(homeStats[key])
            val av = statToInt(awayStats[key])
            addStatRow(container, label, hv, av, suffix)
        }

        // 无数据时
        if (homeStats.isEmpty() && awayStats.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无统计数据"; setTextColor(Color.parseColor(GRAY)); gravity = Gravity.CENTER
                setPadding(0, 48, 0, 48); textSize = 14f
            })
        }
    }

    /** 渲染全场最佳 MVP 卡片 */
    private fun renderBestPlayer() {
        if (bestPlayer.isEmpty()) return
        val parent = findViewById<LinearLayout>(R.id.score_header).parent as LinearLayout
        val infoTv = findViewById<TextView>(R.id.tv_match_info)
        val infoIdx = parent.indexOfChild(infoTv)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A3A1A"))
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 4, 16, 8) }
        }

        card.addView(TextView(this).apply {
            text = "🏆"
            textSize = 20f
            setPadding(0, 0, 10, 0)
        })

        card.addView(TextView(this).apply {
            text = "全场最佳: $bestPlayer"
            setTextColor(Color.parseColor(GOLD))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (bestPlayerRating.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "评分 $bestPlayerRating"
                setTextColor(Color.parseColor(GREEN))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
        }

        parent.addView(card, infoIdx + 1)
    }

    private fun addStatRow(container: LinearLayout, label: String, hv: Int, av: Int, suffix: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 7, 0, 7)
            background = resources.getDrawable(R.drawable.bg_card, theme)
            setPadding(12, 8, 12, 8)
        }

        val valRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val total = (hv + av).coerceAtLeast(1).toFloat()

        val ht = TextView(this).apply {
            text = "$hv$suffix"; setTextColor(Color.parseColor(RED)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        val lt = TextView(this).apply {
            text = label; setTextColor(Color.parseColor("#999999")); textSize = 11f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f)
        }
        val at = TextView(this).apply {
            text = "$av$suffix"; setTextColor(Color.parseColor(GREEN)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        valRow.addView(ht); valRow.addView(lt); valRow.addView(at)

        val barOuter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        }

        val barH = View(this).apply {
            setBackgroundResource(R.drawable.bg_bar_home_gradient)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, hv.toFloat() / total)
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val barA = View(this).apply {
            setBackgroundResource(R.drawable.bg_bar_away_gradient)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, av.toFloat() / total)
        }

        barOuter.addView(barH)
        barOuter.addView(spacer)
        barOuter.addView(barA)

        row.addView(valRow); row.addView(barOuter)
        container.addView(row)

        animBar(barH, hv.toFloat() / total, 600)
        animBar(barA, av.toFloat() / total, 600)
    }

    private fun animBar(view: View, targetWeight: Float, duration: Long) {
        val anim = ValueAnimator.ofFloat(0.02f, targetWeight.coerceAtLeast(0.02f))
        anim.duration = duration
        anim.addUpdateListener {
            val lp = view.layoutParams as LinearLayout.LayoutParams
            lp.weight = (it.animatedValue as Float).coerceAtLeast(0.02f)
            view.layoutParams = lp
        }
        anim.start()
    }


    /** dp转px */
    private fun dp(n: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics
    ).toInt()
}
