package worldcup.helper.ui.data

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import worldcup.helper.R
import worldcup.helper.data.CircleFlagLoader
import worldcup.helper.data.MatchData
import worldcup.helper.data.model.ScorerRow
import worldcup.helper.data.model.StandingRow
import worldcup.helper.data.repos.SharedRepository
import worldcup.helper.data.repos.StandingRepo
import worldcup.helper.network.LiveApiClient

/**
 * Tab D: 比赛数据 — 积分榜 / 球员榜 / 球队
 * API-first: 优先调在线 API，失败后降级到本地 JSON
 *
 * 架构:
 *   loadStandings() → DataRepository.getStandingsWithApi() → fallback local
 *   loadRankings()  → football-data /scorers for 射手榜 → fallback match_events.json
 *                   → match_events.json for 助攻/牌 (真实数据)
 *                   → 评分榜去除假评分，显示"数据积累中"
 */
class DataFragment : Fragment() {

    // 排行榜当前选中子Tab: "scorers" / "assists" / "ratings"
    private var rankingTab = "scorers"

    private val repo by lazy { SharedRepository.getInstance(requireContext()) }
    private val fragmentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_data, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSubTabs(view)
        loadBracket(view)
    }

    private fun setupSubTabs(view: View) {
        val tab1 = view.findViewById<TextView>(R.id.tab_bracket)
        val tab2 = view.findViewById<TextView>(R.id.tab_standings)
        val tab3 = view.findViewById<TextView>(R.id.tab_rankings)
        val tab4 = view.findViewById<TextView>(R.id.tab_teams)
        val content1 = view.findViewById<View>(R.id.content_bracket)
        val content2 = view.findViewById<View>(R.id.content_standings)
        val content3 = view.findViewById<View>(R.id.content_rankings)
        val content4 = view.findViewById<View>(R.id.content_teams)

        tab1.setOnClickListener {
            switchSubTab(tab1, listOf(tab2, tab3, tab4), content1, listOf(content2, content3, content4))
            loadBracket(view)
        }
        tab2.setOnClickListener {
            switchSubTab(tab2, listOf(tab1, tab3, tab4), content2, listOf(content1, content3, content4))
            loadStandings(view)
        }
        tab3.setOnClickListener {
            switchSubTab(tab3, listOf(tab1, tab2, tab4), content3, listOf(content1, content2, content4))
            loadRankings(view)
        }
        tab4.setOnClickListener {
            switchSubTab(tab4, listOf(tab1, tab2, tab3), content4, listOf(content1, content2, content3))
            if (childFragmentManager.findFragmentByTag("teams") == null) {
                childFragmentManager.beginTransaction()
                    .add(R.id.content_teams, worldcup.helper.ui.teams.TeamsFragment(), "teams")
                    .commit()
            }
        }
        switchSubTab(tab1, listOf(tab2, tab3, tab4), content1, listOf(content2, content3, content4))
    }

    private fun switchSubTab(active: TextView, inactives: List<TextView>,
                              activeContent: View, hiddens: List<View>) {
        val orange = ContextCompat.getColor(requireContext(), R.color.accent_orange)
        val muted = ContextCompat.getColor(requireContext(), R.color.text_muted)
        active.setTextColor(orange); active.textSize = 15f
        for (v in inactives) { v.setTextColor(muted); v.textSize = 13f }
        activeContent.visibility = View.VISIBLE
        for (v in hiddens) { v.visibility = View.GONE }
    }

    // ========================================================================
    // 积分榜 — API优先
    // ========================================================================
    private fun loadStandings(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.standings_container)
        container.removeAllViews()
        val flagLoader = CircleFlagLoader(requireContext())

        // 显示加载中
        container.addView(TextView(requireContext()).apply {
            text = "🔄 正在加载积分榜..."
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            textSize = 14f; setPadding(0, 40, 0, 40); gravity = android.view.Gravity.CENTER
        })

        fragmentScope.launch {
            val standings: Map<String, List<StandingRow>> = repo.standings.getStandings()
            renderStandings(container, standings, flagLoader)
        }
    }

    /** 渲染积分榜表格（从StandingRow数据构建UI） */
    private fun renderStandings(
        container: LinearLayout,
        standings: Map<String, List<StandingRow>>,
        flagLoader: CircleFlagLoader
    ) {
        container.removeAllViews()

        try {
            for ((groupRaw, rows) in standings.entries.sortedBy { it.key }) {
                val group = groupRaw.removePrefix("GROUP_")

                // === 组标题 ===
                val groupColors = mapOf(
                    "A" to "#FF6B35", "B" to "#4488FF", "C" to "#00CC66", "D" to "#FFD700",
                    "E" to "#E94560", "F" to "#9B59B6", "G" to "#1ABC9C", "H" to "#FF8C00",
                    "I" to "#3498DB", "J" to "#2ECC71", "K" to "#E74C3C", "L" to "#F39C12"
                )
                val groupColor = groupColors[group] ?: "#FF6B35"

                val groupHeader = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 24, 0, 10)
                }
                groupHeader.addView(TextView(requireContext()).apply {
                    text = group
                    setTextColor(android.graphics.Color.parseColor(groupColor))
                    textSize = 22f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 8, 0)
                })
                groupHeader.addView(TextView(requireContext()).apply {
                    text = "组"
                    setTextColor(android.graphics.Color.parseColor("#8888AA"))
                    textSize = 14f
                })
                groupHeader.addView(TextView(requireContext()).apply {
                    text = "  前2名直接晋级"
                    setTextColor(android.graphics.Color.parseColor("#555577"))
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                container.addView(groupHeader)

                // === 表头 ===
                container.addView(makeStandingsHeader())

                // === 数据行 ===
                for ((i, row) in rows.withIndex()) {
                    val displayName = row.teamNameCn
                    val isPromoted = row.isPromoted
                    val isThird = i == 2

                    val rowView = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(8, 10, 8, 10)
                        val bgColor = when {
                            isPromoted -> android.graphics.Color.parseColor("#0A2A1A")
                            isThird -> android.graphics.Color.parseColor("#0A1A2A")
                            i % 2 == 0 -> android.graphics.Color.parseColor("#0F0F23")
                            else -> android.graphics.Color.parseColor("#1A1A2E")
                        }
                        setBackgroundColor(bgColor)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 1, 0, 1) }
                    }

                    // 点击跳转球队详情
                    val teamEn = row.teamName
                    rowView.setOnClickListener {
                        val intent = Intent(requireContext(), worldcup.helper.ui.teams.TeamDetailActivity::class.java)
                        intent.putExtra("team_name", teamEn)
                        startActivity(intent)
                    }

                    // 晋级指示条
                    val barColor = when {
                        isPromoted -> "#00CC66"
                        isThird -> "#4488FF"
                        else -> "#00FFFFFF"
                    }
                    val bar = View(requireContext()).apply {
                        setBackgroundColor(android.graphics.Color.parseColor(barColor))
                        layoutParams = LinearLayout.LayoutParams(3, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 6 }
                    }
                    rowView.addView(bar)

                    // 排名
                    val rankStr = when (i) {
                        0 -> "🥇"
                        1 -> "🥈"
                        2 -> "🥉"
                        else -> "  ${i + 1}"
                    }
                    rowView.addView(TextView(requireContext()).apply {
                        text = rankStr
                        setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                        textSize = if (i < 3) 14f else 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        gravity = android.view.Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })

                    // 球队名 + 国旗
                    val teamCell = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.2f)
                    }
                    val flagDrawable = flagLoader.loadFlag(row.fifaCode)
                    if (flagDrawable != null) {
                        teamCell.addView(ImageView(requireContext()).apply {
                            setImageDrawable(flagDrawable)
                            layoutParams = LinearLayout.LayoutParams(22, 22).apply { marginEnd = 6 }
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        })
                    }
                    teamCell.addView(TextView(requireContext()).apply {
                        text = displayName
                        setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                        textSize = 12f
                        typeface = if (isPromoted) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    })
                    rowView.addView(teamCell)

                    // 数据列
                    rowView.addView(statCell("${row.played}", 0.6f, "#8888AA"))
                    rowView.addView(statCell("${row.wins}", 0.6f, "#00CC66"))
                    rowView.addView(statCell("${row.draws}", 0.6f, "#8888AA"))
                    rowView.addView(statCell("${row.losses}", 0.6f, "#FF4444"))
                    rowView.addView(statCell("${row.goalsFor}/${row.goalsAgainst}", 1.2f, "#CCCCCC"))

                    // 净胜球
                    val gd = row.goalDiff
                    val gdStr = if (gd > 0) "+$gd" else "$gd"
                    val gdColor = if (gd > 0) "#00CC66" else if (gd < 0) "#FF4444" else "#8888AA"
                    rowView.addView(statCell(gdStr, 0.7f, gdColor))

                    // 积分
                    val ptsColor = when {
                        isPromoted -> "#00CC66"
                        isThird -> "#4488FF"
                        else -> "#FFFFFF"
                    }
                    rowView.addView(TextView(requireContext()).apply {
                        text = "${row.points}"
                        setTextColor(android.graphics.Color.parseColor(ptsColor))
                        textSize = if (isPromoted) 17f else 14f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        gravity = android.view.Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })

                    container.addView(rowView)
                }

                // 组间分隔
                container.addView(View(requireContext()).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { setMargins(0, 4, 0, 0) }
                })
            }

            if (standings.isEmpty()) {
                container.addView(TextView(requireContext()).apply {
                    text = "暂无比赛数据，比赛开始后将自动更新"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
                    textSize = 14f; setPadding(0, 40, 0, 40); gravity = android.view.Gravity.CENTER
                })
            }

            addTournamentInfoFooter(container)

        } catch (e: Exception) {
            android.util.Log.e("DataFragment", "渲染积分榜失败", e)
            container.addView(TextView(requireContext()).apply {
                text = "加载失败，请重试"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.live_red))
                textSize = 14f; setPadding(0, 40, 0, 40); gravity = android.view.Gravity.CENTER
            })
        }
    }

    /** 积分榜表头 */
    private fun makeStandingsHeader(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(android.graphics.Color.parseColor("#141428"))
            // 左边占位
            addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(31), ViewGroup.LayoutParams.MATCH_PARENT)
            })
            addView(statCell("球队", 2.2f, "#666688"))
            addView(statCell("近5场", 1.2f, "#666688"))
            addView(statCell("赛", 0.6f, "#666688"))
            addView(statCell("胜", 0.6f, "#666688"))
            addView(statCell("平", 0.6f, "#666688"))
            addView(statCell("负", 0.6f, "#666688"))
            addView(statCell("进/失", 1.2f, "#666688"))
            addView(statCell("净", 0.7f, "#666688"))
            addView(statCell("积分", 0.9f, "#666688"))
        }
    }

    /** dp→px 工具 */
    private fun dp(n: Int): Int {
        return (n * resources.displayMetrics.density).toInt()
    }

    /** 积分榜底部赛事规则信息栏 */
    private fun addTournamentInfoFooter(container: LinearLayout) {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 0) }
        }

        card.addView(TextView(requireContext()).apply {
            text = "🏆 2026 国际足联第23届世界杯"
            setTextColor(android.graphics.Color.parseColor("#FF6B35"))
            textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(requireContext()).apply {
            text = "首次扩军至 48 支球队 · 104场比赛"
            setTextColor(android.graphics.Color.parseColor("#8888AA"))
            textSize = 11f; setPadding(0, 4, 0, 4)
        })
        card.addView(TextView(requireContext()).apply {
            text = "小组赛 → 1/16决赛 → 1/8决赛 → 1/4决赛 → 半决赛 → 决赛\n前2名+8个最佳第3名→32队晋级 · 淘汰赛单场淘汰"
            setTextColor(android.graphics.Color.parseColor("#555577"))
            textSize = 10f; setPadding(0, 2, 0, 2)
        })
        card.addView(TextView(requireContext()).apply {
            text = "📊 来源: football-data.org + worldcup26.ir"
            setTextColor(android.graphics.Color.parseColor("#555577"))
            textSize = 9f; setPadding(0, 6, 0, 0)
        })

        container.addView(card)
    }

    // ========================================================================
    // 淘汰赛对阵图 — 自动赛果同步 + 自动晋级
    // 每点开淘汰赛 Tab 时会从 API 拉取实时数据，自动推导下一轮对阵
    // ========================================================================
    // 淘汰赛对阵图 — 每次打开都从 API 刷新
    // ========================================================================

    /** football-data stage → 轮次中文名（兼容多种命名） */
    private val stageToRound = mapOf(
        "LAST_32" to "1/16决赛", "ROUND_32" to "1/16决赛",
        "LAST_16" to "1/8决赛", "ROUND_16" to "1/8决赛",
        "QUARTER_FINALS" to "1/4决赛",
        "SEMI_FINALS" to "半决赛",
        "THIRD_PLACE" to "三四名决赛",
        "FINAL" to "决赛"
    )

    /** 淘汰赛二叉树：父节点索引 → [左子, 右子]（按排序后的列表索引） */
    private val bracketTree = mapOf(
        // R32 → R16
        0 to intArrayOf(0, 1), 1 to intArrayOf(2, 3), 2 to intArrayOf(4, 5), 3 to intArrayOf(6, 7),
        4 to intArrayOf(8, 9), 5 to intArrayOf(10, 11), 6 to intArrayOf(12, 13), 7 to intArrayOf(14, 15),
        // R16 → QF
        8 to intArrayOf(0, 1), 9 to intArrayOf(2, 3), 10 to intArrayOf(4, 5), 11 to intArrayOf(6, 7),
        // QF → SF
        12 to intArrayOf(0, 1), 13 to intArrayOf(2, 3),
        // SF → Final
        14 to intArrayOf(0, 1),
        // Third place
        15 to intArrayOf(0, 1)
    )

    private fun loadBracket(view: View) {
        // 每次打开都刷新
        val container = view.findViewById<LinearLayout>(R.id.bracket_container)
        container.removeAllViews()

        // 加载中
        container.addView(TextView(requireContext()).apply {
            text = "🔄 正在从 football-data API 获取实时赛果..."
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            textSize = 12f; setPadding(0, 40, 0, 40); gravity = android.view.Gravity.CENTER
        })

        fragmentScope.launch {
            try {
                // 1) 直接调 API
                val apiScores = fetchKnockoutScoresFromApi()
                val liveCount = apiScores.count { it.status == "FINISHED" }
                android.util.Log.i("Bracket", "API 返回 ${apiScores.size} 场比赛，其中 $liveCount 场已完赛")

                // 2) 读本地模板
                val matches = readKnockoutTemplate()

                // 3) 合并
                val mergedCount = mergeApiScores(matches, apiScores)
                android.util.Log.i("Bracket", "合并后已完赛: $mergedCount/32")

                // 4) 自动晋级
                val advancedCount = autoAdvanceBracket(matches)
                android.util.Log.i("Bracket", "自动晋级: $advancedCount 场")

                // 5) 渲染
                renderBracket(container, matches)

                // 6) 状态摘要
                val finishedTotal = matches.count { it["status"] == "FINISHED" }
                val apiFinished = apiScores.count { it.status == "FINISHED" }
                val koFinished = apiScores.count { it.status == "FINISHED" && it.roundLabel != "GROUP_STAGE" }
                container.addView(TextView(requireContext()).apply {
                    text = "📡 football-data API: 同步 $mergedCount/32 场 | 已完赛 $finishedTotal 场"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
                    textSize = 10f; setPadding(dp(8), dp(4), dp(8), dp(8))
                })
            } catch (e: Exception) {
                android.util.Log.e("Bracket", "API 失败: ${e.message}", e)
                container.removeAllViews()
                // 用本地数据但显示 API 失败提示
                try {
                    val matches = readKnockoutTemplate()
                    renderBracket(container, matches)
                    container.addView(TextView(requireContext()).apply {
                        text = "⚠️ API 同步失败 (${e.message ?: "未知错误"})，显示本地数据"
                        setTextColor(Color.parseColor("#FF6B35"))
                        textSize = 10f; setPadding(0, 4, 0, 8); gravity = android.view.Gravity.CENTER
                    })
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 从 football-data API 获取实时比分
     * 不限 stage，所有比赛全拿，后续按队伍名匹配到本地模板
     */
    private suspend fun fetchKnockoutScoresFromApi(): List<ApiMatchScore> {
        return withContext(Dispatchers.IO) {
            val resp = LiveApiClient.footballData.getMatches()
            // 日志：打印所有比赛概况（方便排查）
            val all = resp.matches.size
            val finished = resp.matches.count { it.status == "FINISHED" }
            val knockoutStages = resp.matches.filter { m ->
                m.stage != null && m.stage != "GROUP_STAGE"
            }
            android.util.Log.i("Bracket", "API 总计 $all 场, FINISHED $finished 场, 非小组赛 ${knockoutStages.size} 场")
            for (m in knockoutStages.take(40)) {
                val homeTla = m.homeTeam.tla ?: "null"
                val awayTla = m.awayTeam.tla ?: "null"
                android.util.Log.i("Bracket", "  [${m.stage}] ${m.homeTeam.name}($homeTla) ${m.score?.fullTime?.home?:'-'}:${m.score?.fullTime?.away?:'-'} ${m.awayTeam.name}($awayTla) status=${m.status}")
            }
            resp.matches.map { ApiMatchScore(
                roundLabel = it.stage ?: "",
                homeTeam = it.homeTeam.name ?: "",
                awayTeam = it.awayTeam.name ?: "",
                homeTla = (it.homeTeam.tla ?: "").uppercase(),
                awayTla = (it.awayTeam.tla ?: "").uppercase(),
                homeScore = it.score?.fullTime?.home ?: 0,
                awayScore = it.score?.fullTime?.away ?: 0,
                status = it.status ?: "TIMED",
                winner = it.score?.winner,
                duration = it.score?.duration
            )}
        }
    }

    /** API 返回的淘汰赛数据（含 TLA 3字母代码用于精准匹配） */
    data class ApiMatchScore(
        val roundLabel: String,
        val homeTeam: String,
        val awayTeam: String,
        val homeTla: String,   // 3-letter FIFA code from football-data API
        val awayTla: String,   // e.g. "BRA", "KOR", "COD", "IRN"
        val homeScore: Int,
        val awayScore: Int,
        val status: String,
        val winner: String? = null,     // "HOME_TEAM" / "AWAY_TEAM" / null
        val duration: String? = null     // "PENALTY_SHOOTOUT" / "EXTRA_TIME" / "REGULAR" / null
    )

    /** 读取本地淘汰赛模板 */
    private fun readKnockoutTemplate(): MutableList<MutableMap<String, Any>> {
        val gson = Gson()
        val json = requireContext().assets.open("matches.json").bufferedReader().use { it.readText() }
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        val all: List<Map<String, Any>> = gson.fromJson(json, type)
        val ko = all.filter { (it["type"] as? String) == "knockout" }
        // 构建 ID→排序索引 映射
        val r32Order = BRACKET_ORDER_R32.withIndex().associate { (i, id) -> id to i }
        val r16Order = BRACKET_ORDER_R16.withIndex().associate { (i, id) -> id to i }
        val qfOrder  = BRACKET_ORDER_QF.withIndex().associate { (i, id) -> id to i }
        val sfOrder  = BRACKET_ORDER_SF.withIndex().associate { (i, id) -> id to i }
        return ko
            .sortedBy { m ->
                val id = m["id"] as? String ?: ""
                val round = m["round"] as? String ?: ""
                val baseOrder = when (round) {
                    "1/16决赛" -> 0; "1/8决赛" -> 100; "1/4决赛" -> 200
                    "半决赛" -> 300; "决赛" -> 400; "三四名决赛" -> 350
                    else -> 500
                }
                val idx = when (round) {
                    "1/16决赛" -> r32Order[id] ?: 99
                    "1/8决赛" -> r16Order[id] ?: 99
                    "1/4决赛" -> qfOrder[id] ?: 99
                    "半决赛" -> sfOrder[id] ?: 99
                    else -> 0
                }
                baseOrder + idx
            }
            .map { it.toMutableMap() }
            .toMutableList()
    }

    /**
     * 从 API 合并比分到本地模板 → 返回匹配到的场次数
     *
     * 匹配策略（按优先级）：
     *   1. TLA (3-letter) → 本地 FIFA 2-letter 代码匹配（最可靠）
     *   2. 标准化队名精确匹配（含别名查找表）
     *   3. contains 模糊匹配（原方案，作为最后保障）
     */
    private fun mergeApiScores(
        matches: MutableList<MutableMap<String, Any>>,
        apiScores: List<ApiMatchScore>
    ): Int {
        val allLocal = matches.filter { it["type"] as? String != "group" }
        var matched = 0
        var unmatched = 0

        for (api in apiScores) {
            if (api.status != "FINISHED") continue

            val apiHomeTla = api.homeTla.uppercase()
            val apiAwayTla = api.awayTla.uppercase()
            val apiHomeName = api.homeTeam.lowercase().trim()
            val apiAwayName = api.awayTeam.lowercase().trim()

            val local = allLocal.find { m ->
                val homeFifa = (m["homeFifaCode"] as? String ?: "").uppercase()
                val awayFifa = (m["awayFifaCode"] as? String ?: "").uppercase()
                val homeName = (m["homeTeam"] as? String ?: "").lowercase().trim()
                val awayName = (m["awayTeam"] as? String ?: "").lowercase().trim()

                // 策略1: TLA → 本地 FIFA 2-letter 代码匹配
                val tlaMatch = tryTlaMatch(apiHomeTla, apiAwayTla, homeFifa, awayFifa)
                if (tlaMatch) return@find true

                // 策略2: 标准化队名匹配（含别名查找）
                val nameMatch = tryNameMatch(apiHomeName, apiAwayName, homeName, awayName)
                if (nameMatch) return@find true

                // 策略3: contains 模糊匹配（原方案）
                tryContainsMatch(apiHomeName, apiAwayName, homeName, awayName)
            }

            if (local != null) {
                local["homeScore"] = api.homeScore
                local["awayScore"] = api.awayScore
                local["status"] = api.status
                // 处理点球决胜：API winner 字段 → 本地 penaltyWinner
                if (api.duration == "PENALTY_SHOOTOUT" && api.winner != null) {
                    if (api.winner == "HOME_TEAM") local["penaltyWinner"] = "home"
                    else if (api.winner == "AWAY_TEAM") local["penaltyWinner"] = "away"
                    android.util.Log.i("Bracket", "点球决胜: ${api.homeTeam} vs ${api.awayTeam}, 胜者=${api.winner}")
                }
                matched++
            } else {
                unmatched++
                val reason = buildString {
                    append("队名=[${api.homeTeam} vs ${api.awayTeam}]")
                    append(" TLA=[${api.homeTla}/${api.awayTla}]")
                    append(" 状态=${api.status}")
                    // 尝试在所有本地比赛查找相近队名
                    val allNames = allLocal.map { m ->
                        val h = m["homeTeam"] ?: ""
                        val a = m["awayTeam"] ?: ""
                        val hf = m["homeFifaCode"] ?: ""
                        val af = m["awayFifaCode"] ?: ""
                        "$h($hf)vs$a($af)"
                    }
                    append(" 本地模板: $allNames")
                }
                android.util.Log.w("Bracket", "❌ API 未匹配: $reason")
            }
        }

        android.util.Log.i("Bracket", "mergeApiScores: 匹配=$matched 未匹配=$unmatched")
        return matched
    }

    /** TLA (3-letter) → 本地 FIFA 2-letter 代码匹配 */
    private fun tryTlaMatch(
        apiHomeTla: String, apiAwayTla: String,
        localHomeFifa: String, localAwayFifa: String
    ): Boolean {
        if (apiHomeTla.isBlank() || apiAwayTla.isBlank() ||
            localHomeFifa.isBlank() || localAwayFifa.isBlank()) return false

        // 将 API 3-letter TLA 映射到本地 2-letter FIFA 代码
        val apiHomeFifa2 = tlaToFifa2[apiHomeTla] ?: return false
        val apiAwayFifa2 = tlaToFifa2[apiAwayTla] ?: return false

        return (apiHomeFifa2 == localHomeFifa && apiAwayFifa2 == localAwayFifa) ||
               (apiHomeFifa2 == localAwayFifa && apiAwayFifa2 == localHomeFifa) // 主客队可能反了
    }

    /** 标准化队名匹配（含别名查找表） */
    private fun tryNameMatch(
        apiHome: String, apiAway: String,
        localHome: String, localAway: String
    ): Boolean {
        // 标准化：移除特殊字符、多余空格
        fun normalize(s: String): String = s
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 别名解析：将队名映射到"标准名"
        fun canonical(name: String): String = when {
            name.contains("korea") || name.contains("south korea") -> "korea republic"
            name.contains("drc") || name.contains("dr congo") || name.contains("democratic republic") -> "congo dr"
            name.contains("usa") || name.contains("united states") -> "united states"
            name.contains("iran") || name.contains("ir iran") -> "iran"
            name.contains("ivory") || name.contains("cote") || name.contains("côte") -> "ivory coast"
            name.contains("bosnia") -> "bosnia and herzegovina"
            name.contains("czech") -> "czech republic"
            name.contains("cape verde") || name.contains("cape verde islands") -> "cape verde"
            name.contains("curacao") || name.contains("curaçao") -> "curaçao"
            name.contains("portugal") -> "portugal"
            name.contains("netherlands") || name.contains("holland") -> "netherlands"
            else -> name
        }

        val aHome = normalize(canonical(apiHome))
        val aAway = normalize(canonical(apiAway))
        val lHome = normalize(canonical(localHome))
        val lAway = normalize(canonical(localAway))

        // 标准化后精确匹配（主客或客主）
        return (aHome == lHome && aAway == lAway) ||
               (aHome == lAway && aAway == lHome)
    }

    /** contains 模糊匹配（原方案的改良版） */
    private fun tryContainsMatch(
        apiHome: String, apiAway: String,
        localHome: String, localAway: String
    ): Boolean {
        // 用单词列表匹配替代子串 contains，避免 "a" in "paraguay" 这种误匹配
        val apiWords = (apiHome.split(" ") + apiAway.split(" ")).filter { it.length > 2 }.toSet()
        val localWords = (localHome.split(" ") + localAway.split(" ")).filter { it.length > 2 }.toSet()

        // 如果一方完全包含另一方的所有显著单词
        val overlap = apiWords.intersect(localWords)
        val minWords = apiWords.size.coerceAtMost(localWords.size)
        if (minWords > 0 && overlap.size >= minWords) return true

        return false
    }

    /**
     * 自动晋级推演 → 返回已晋级的场次数
     */
    private fun autoAdvanceBracket(
        matches: MutableList<MutableMap<String, Any>>
    ): Int {
        val r32 = matches.filter { it["round"] == "1/16决赛" }
        val r16 = matches.filter { it["round"] == "1/8决赛" }
        val qf = matches.filter { it["round"] == "1/4决赛" }
        val sf = matches.filter { it["round"] == "半决赛" }

        var advanced = 0

        // R32 → R16
        for (i in 0..7) {
            val m1 = r32.getOrNull(i * 2) ?: continue
            val m2 = r32.getOrNull(i * 2 + 1) ?: continue
            val target = r16.getOrNull(i) ?: continue
            if (fillNextRound(target, m1, m2)) advanced++
        }

        // R16 → QF
        for (i in 0..3) {
            val m1 = r16.getOrNull(i * 2) ?: continue
            val m2 = r16.getOrNull(i * 2 + 1) ?: continue
            val target = qf.getOrNull(i) ?: continue
            if (fillNextRound(target, m1, m2)) advanced++
        }

        // QF → SF
        for (i in 0..1) {
            val m1 = qf.getOrNull(i * 2) ?: continue
            val m2 = qf.getOrNull(i * 2 + 1) ?: continue
            val target = sf.getOrNull(i) ?: continue
            if (fillNextRound(target, m1, m2)) advanced++
        }

        // SF → Final + 三四名
        val sf1 = sf.getOrNull(0)
        val sf2 = sf.getOrNull(1)
        val final_ = matches.find { it["round"] == "决赛" }
        val third = matches.find { it["round"] == "三四名决赛" }
        if (sf1 != null && sf2 != null) {
            if (fillNextRound(final_, sf1, sf2)) advanced++
            if (third != null) fillThirdPlace(third, sf1, sf2)
        }

        return advanced
    }

    /** 将两场比赛的胜者填入下一轮 → 返回是否晋级成功 */
    private fun fillNextRound(
        target: MutableMap<String, Any>?,
        match1: MutableMap<String, Any>,
        match2: MutableMap<String, Any>
    ): Boolean {
        if (target == null) return false
        val winner1 = getWinner(match1)
        val winner2 = getWinner(match2)
        var filled = false
        if (winner1 != null) {
            target["homeTeam"] = winner1.first
            target["homeTeamCn"] = MatchData.getChineseName(winner1.first)
            target["homeFifaCode"] = winner1.second
            filled = true
        }
        if (winner2 != null) {
            target["awayTeam"] = winner2.first
            target["awayTeamCn"] = MatchData.getChineseName(winner2.first)
            target["awayFifaCode"] = winner2.second
            filled = true
        }
        return filled
    }

    /** 三四名决赛 = 半决赛负者 */
    private fun fillThirdPlace(
        target: MutableMap<String, Any>,
        sf1: MutableMap<String, Any>,
        sf2: MutableMap<String, Any>
    ) {
        val loser1 = getLoser(sf1)
        val loser2 = getLoser(sf2)
        if (loser1 != null) {
            target["homeTeam"] = loser1.first
            target["homeTeamCn"] = MatchData.getChineseName(loser1.first)
            target["homeFifaCode"] = loser1.second
        }
        if (loser2 != null) {
            target["awayTeam"] = loser2.first
            target["awayTeamCn"] = MatchData.getChineseName(loser2.first)
            target["awayFifaCode"] = loser2.second
        }
    }

    /** 获取胜者 (teamName, fifaCode) — 支持点球决胜 */
    private fun getWinner(match: MutableMap<String, Any>): Pair<String, String>? {
        if (match["status"] != "FINISHED") return null
        // 点球决胜优先
        val pw = match["penaltyWinner"] as? String
        if (pw == "home") return ((match["homeTeam"] as? String) ?: "") to ((match["homeFifaCode"] as? String) ?: "")
        if (pw == "away") return ((match["awayTeam"] as? String) ?: "") to ((match["awayFifaCode"] as? String) ?: "")
        val hs = (match["homeScore"] as? Number)?.toInt() ?: 0
        val as_ = (match["awayScore"] as? Number)?.toInt() ?: 0
        return when {
            hs > as_ -> ((match["homeTeam"] as? String) ?: "") to ((match["homeFifaCode"] as? String) ?: "")
            as_ > hs -> ((match["awayTeam"] as? String) ?: "") to ((match["awayFifaCode"] as? String) ?: "")
            else -> null  // 平局（淘汰赛不会出现，点球已处理）
        }
    }

    /** 获取负者 — 支持点球决胜 */
    private fun getLoser(match: MutableMap<String, Any>): Pair<String, String>? {
        if (match["status"] != "FINISHED") return null
        val pw = match["penaltyWinner"] as? String
        if (pw == "home") return ((match["awayTeam"] as? String) ?: "") to ((match["awayFifaCode"] as? String) ?: "")
        if (pw == "away") return ((match["homeTeam"] as? String) ?: "") to ((match["homeFifaCode"] as? String) ?: "")
        val hs = (match["homeScore"] as? Number)?.toInt() ?: 0
        val as_ = (match["awayScore"] as? Number)?.toInt() ?: 0
        return when {
            hs > as_ -> ((match["awayTeam"] as? String) ?: "") to ((match["awayFifaCode"] as? String) ?: "")
            as_ > hs -> ((match["homeTeam"] as? String) ?: "") to ((match["homeFifaCode"] as? String) ?: "")
            else -> null
        }
    }

    /** 淘汰赛对阵图 — 分组列表 + API数据驱动 + 晋级线 */
    private fun renderBracket(container: LinearLayout, matches: List<MutableMap<String, Any>>) {
        container.removeAllViews()
        val flagLoader = CircleFlagLoader(requireContext())

        if (matches.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "淘汰赛数据尚未生成"; setTextColor(Color.parseColor("#888888"))
                textSize = 14f; setPadding(0, 60, 0, 40); gravity = android.view.Gravity.CENTER
            }); return
        }

        // 分组: R32 → R16, R16 → QF, QF → SF, SF → Final
        val r32 = matches.filter { it["round"] == "1/16决赛" }
        val r16 = matches.filter { it["round"] == "1/8决赛" }
        val qf  = matches.filter { it["round"] == "1/4决赛" }
        val sf  = matches.filter { it["round"] == "半决赛" }
        val final_ = matches.find { it["round"] == "决赛" }
        val third = matches.find { it["round"] == "三四名决赛" }

        // 决赛
        if (final_ != null) {
            container.addView(sectionLabel("🏆 决赛", Color.parseColor("#FFD700"), 17f))
            container.addView(matchCard(final_, null, flagLoader))
        }
        if (third != null) {
            container.addView(sectionLabel("🥉 三四名决赛", Color.parseColor("#CCCC44"), 14f))
            container.addView(matchCard(third, null, flagLoader))
        }

        // 半决赛
        if (sf.isNotEmpty()) {
            container.addView(sectionLabel("半决赛 → 决赛", Color.parseColor("#666688"), 13f))
            for (m in sf) container.addView(matchCard(m, final_, flagLoader))
        }

        // 1/4决赛
        if (qf.isNotEmpty()) {
            container.addView(sectionLabel("1/4决赛 → 半决赛", Color.parseColor("#666688"), 13f))
            for ((i, m) in qf.withIndex()) {
                container.addView(matchCard(m, sf.getOrNull(i / 2), flagLoader))
            }
        }

        // 1/8决赛
        if (r16.isNotEmpty()) {
            container.addView(sectionLabel("1/8决赛 → 1/4决赛", Color.parseColor("#666688"), 13f))
            for ((i, m) in r16.withIndex()) {
                container.addView(matchCard(m, qf.getOrNull(i / 2), flagLoader))
            }
        }

        // 1/16决赛 — 两场一组，左右配对
        if (r32.isNotEmpty()) {
            container.addView(sectionLabel("1/16决赛  ▲ 上半区", Color.parseColor("#4488FF"), 13f))
            for (i in 0..3) addPair(container, r32.getOrNull(i*2), r32.getOrNull(i*2+1), r16.getOrNull(i), flagLoader)

            container.addView(sectionLabel("1/16决赛  ▼ 下半区", Color.parseColor("#FF6B35"), 13f))
            for (i in 0..3) addPair(container, r32.getOrNull(8+i*2), r32.getOrNull(8+i*2+1), r16.getOrNull(4+i), flagLoader)
        }

        val finishedCount = matches.count { (it["status"] as? String) == "FINISHED" }
        container.addView(TextView(requireContext()).apply {
            text = "🟠 已完赛 $finishedCount 场  ·  ⚪ 待定 ${matches.size - finishedCount} 场"
            setTextColor(Color.parseColor("#555577"))
            textSize = 11f; setPadding(0, 12, 0, 12); gravity = android.view.Gravity.CENTER
        })
    }

    /** 两场 R32 + 自动晋级到 R16 */
    private fun addPair(p: LinearLayout, m1: MutableMap<String, Any>?, m2: MutableMap<String, Any>?, next: MutableMap<String, Any>?, fl: CircleFlagLoader) {
        if (m1 == null || m2 == null) return
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 0)
        }
        val left = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(matchCard(m1, next, fl))
        left.addView(matchCard(m2, next, fl))
        row.addView(left)

        row.addView(TextView(requireContext()).apply {
            text = "  →  "; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#555577")); textSize = 18f
        })

        val right = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
        }
        if (next != null) right.addView(matchCard(next, null, fl))
        else right.addView(TextView(requireContext()).apply {
            text = "待定"; setTextColor(Color.parseColor("#555577")); textSize = 12f
            gravity = Gravity.CENTER; setPadding(0, 20, 0, 20)
        })
        row.addView(right)
        p.addView(row)
        p.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(8), 0, dp(8)) }
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        })
    }

    /** 单场比赛卡片 */
    private fun matchCard(m: MutableMap<String, Any>, next: MutableMap<String, Any>?, fl: CircleFlagLoader): LinearLayout {
        val done = m["status"] == "FINISHED"
        val he = m["homeTeam"] as? String ?: ""; val hc = m["homeTeamCn"] as? String ?: ""
        val ae = m["awayTeam"] as? String ?: ""; val ac = m["awayTeamCn"] as? String ?: ""
        val home = hc.ifEmpty { he }; val away = ac.ifEmpty { ae }
        val tbd = home == "TBD" || away == "TBD" || home == "待定"
        val hs = (m["homeScore"] as? Number)?.toInt() ?: 0; val as_ = (m["awayScore"] as? Number)?.toInt() ?: 0
        val hf = m["homeFifaCode"] as? String ?: ""; val af = m["awayFifaCode"] as? String ?: ""
        val pw = m["penaltyWinner"] as? String  // "home", "away", or null
        val hasPen = pw != null
        val phs = if (hasPen) (m["penaltyHomeScore"] as? Number)?.toInt() ?: 0 else 0
        val pas = if (hasPen) (m["penaltyAwayScore"] as? Number)?.toInt() ?: 0 else 0
        val homeWins = if (hasPen) pw == "home" else hs > as_
        val awayWins = if (hasPen) pw == "away" else as_ > hs

        val bg = when { tbd -> 0xFF15152A.toInt(); done -> 0xFF1A3A2E.toInt(); else -> 0xFF1E1E32.toInt() }
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        card.addView(teamLine(fl, home, hf, if (done) "$hs" else "", done, homeWins, he))
        card.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(4), 0, dp(4)) }
            setBackgroundColor(0xFF2A2A44.toInt())
        })
        card.addView(teamLine(fl, away, af, if (done) "$as_" else "", done, awayWins, ae))

        // 点球决胜提示
        if (hasPen && done) {
            card.addView(TextView(context).apply {
                text = "⚫ 点球 ${phs}:${pas}"; setTextColor(0xFFAA8833.toInt()); textSize = 10f
                gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
            })
        }

        // 晋级提示
        if (done && !tbd && next != null) {
            val winner = getWinner(m)
            val w = winner?.first ?: home
            val nh = (next["homeTeamCn"] as? String)?.ifEmpty { next["homeTeam"] as? String } ?: "?"
            val na = (next["awayTeamCn"] as? String)?.ifEmpty { next["awayTeam"] as? String } ?: "?"
            card.addView(TextView(context).apply {
                text = "→ 晋级 $w → ${nh}/${na}"; setTextColor(0xFF4CAF50.toInt()); textSize = 10f
                setPadding(0, dp(2), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        } else if (!tbd && next != null) {
            val nh = (next["homeTeamCn"] as? String)?.ifEmpty { next["homeTeam"] as? String } ?: "?"
            val na = (next["awayTeamCn"] as? String)?.ifEmpty { next["awayTeam"] as? String } ?: "?"
            card.addView(TextView(context).apply {
                text = "→ 胜者 → ${nh}/${na}"; setTextColor(0xFF555577.toInt()); textSize = 10f
                setPadding(0, dp(2), 0, 0)
            })
        }
        return card
    }

    /** 一行球队：国旗 + 队名 + 比分 */
    /** 一行球队：国旗 + 队名 + 比分（点击跳转球队卡） */
    private fun teamLine(fl: CircleFlagLoader, name: String, fifa: String, score: String, done: Boolean, win: Boolean, enName: String = ""): LinearLayout {
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iv = ImageView(requireContext()).apply { layoutParams = ViewGroup.LayoutParams(dp(24), dp(24)); scaleType = ImageView.ScaleType.FIT_CENTER }
        val d = if (fifa.isNotEmpty()) fl.loadFlag(fifa, dp(24)) else null; if (d != null) iv.setImageDrawable(d)
        row.addView(iv)
        val t = name == "TBD" || name == "待定"
        row.addView(TextView(requireContext()).apply {
            text = if (t) "待定" else name
            setTextColor(when { t -> 0xFF555577.toInt(); win -> 0xFF4CAF50.toInt(); done && !win -> 0xFF888888.toInt(); else -> 0xFFEEEEFF.toInt() })
            textSize = 13f; setTypeface(null, if (win) Typeface.BOLD else Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, 0, 0) }
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (score.isNotEmpty()) {
            row.addView(TextView(requireContext()).apply {
                text = score; setTextColor(if (win) 0xFF4CAF50.toInt() else 0xFFFF6B35.toInt())
                textSize = 15f; setTypeface(null, Typeface.BOLD)
            })
        } else if (!t) {
            row.addView(TextView(requireContext()).apply { text = "vs"; setTextColor(0xFF444466.toInt()); textSize = 11f })
        }
        // 点击整行跳转球队卡
        if (!t && enName.isNotEmpty()) {
            row.setOnClickListener {
                val intent = Intent(requireContext(), worldcup.helper.ui.teams.TeamDetailActivity::class.java)
                intent.putExtra("team_name", enName)
                startActivity(intent)
            }
        }
        return row
    }

    private fun sectionLabel(text: String, color: Int, size: Float) = TextView(requireContext()).apply {
        this.text = "— $text —"; gravity = Gravity.CENTER; setTextColor(color); textSize = size
        setTypeface(null, Typeface.BOLD); setPadding(0, dp(12), 0, dp(6))
    }

    /** 降级方案：纯读 JSON 然后复用 View 卡片渲染 */
    private fun loadBracketFromJson(container: LinearLayout) {
        try {
            val matches = readKnockoutTemplate()
            if (matches.isEmpty()) {
                container.removeAllViews()
                container.addView(TextView(requireContext()).apply {
                    text = "淘汰赛数据尚未生成"
                    setTextColor(Color.parseColor("#888888"))
                    textSize = 14f; setPadding(0, 60, 0, 40); gravity = android.view.Gravity.CENTER
                }); return
            }
            renderBracket(container, matches)
        } catch (e: Exception) {
            android.util.Log.e("DataFragment", "淘汰赛降级加载失败", e)
            container.removeAllViews()
            container.addView(TextView(requireContext()).apply {
                text = "加载失败，请重试"
                setTextColor(Color.parseColor("#FF4444"))
                textSize = 14f; setPadding(0, 60, 0, 40); gravity = android.view.Gravity.CENTER
            })
        }
    }

    // ========================================================================
    // 球员榜 — 中文名查找 + 跳转修复
    // ========================================================================
    private data class PlayerStat(
        val name: String, val nameCn: String, val teamCn: String,
        val value: Double, val matches: Int, val teamFifa: String = "",
        val teamEn: String = ""    // 英文队名，用于跳转TeamDetailActivity
    )

    /** players_2026.json 中文名映射 (英文名.lowercase() → 中文名) */
    private var playerCnMap: Map<String, String>? = null

    private fun getPlayerCnMap(): Map<String, String> {
        if (playerCnMap != null) return playerCnMap!!
        try {
            val gson = Gson()
            val json = requireContext().assets.open("players_2026.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            val teams = root["teams"] as? List<Map<String, Any>> ?: emptyList()
            val map = mutableMapOf<String, String>()
            for (team in teams) {
                val players = team["players"] as? List<Map<String, Any>> ?: continue
                for (p in players) {
                    val eng = (p["name"] as? String)?.lowercase()?.trim() ?: continue
                    val cn = p["nameCn"] as? String ?: continue
                    if (cn.isNotEmpty()) map[eng] = cn
                }
            }
            playerCnMap = map
            return map
        } catch (e: Exception) {
            playerCnMap = emptyMap()
            return emptyMap()
        }
    }

    /** 获取球员中文名，找不到则返回原英文名 */
    private fun getPlayerCn(engName: String): String {
        val cnMap = getPlayerCnMap()
        val key = engName.lowercase().trim()
        // 精确匹配
        cnMap[key]?.let { return it }
        // 缩写展开匹配: "K. Mbappe" → "kylian mbappe"
        val parts = key.replace(".", "").split(" ").filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val fi = parts[0].firstOrNull()
            val ln = parts.last()
            for ((mapKey, mapVal) in cnMap) {
                val mapParts = mapKey.split(" ").filter { it.isNotEmpty() }
                if (mapParts.size >= 2) {
                    val mapFi = mapParts[0].firstOrNull()
                    val mapLn = mapParts.last()
                    if (fi == mapFi && ln == mapLn) return mapVal
                }
            }
        }
        return engName
    }

    private var apiScorersLoaded = false
    private var apiScorersData: List<ScorerRow> = emptyList()
    private var apiRankingsLoaded = false
    private var apiRankingsData: List<StandingRepo.SeasonRanking> = emptyList()

    private fun loadRankings(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.rankings_container)
        container.removeAllViews()

        // 排行榜筛选 Tab
        val filterRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 12)
        }
        val scorersTab = rankingChip("⚽ 射手榜", "scorers", container)
        val assistsTab = rankingChip("🅰 助攻榜", "assists", container)
        val ratingsTab = rankingChip("⭐ 评分榜", "ratings", container)
        val shotsTab = rankingChip("🎯 射正榜", "shots", container)
        val keypassTab = rankingChip("🔑 关键传", "keypass", container)
        val tacklesTab = rankingChip("💪 抢断榜", "tackles", container)
        val dribblesTab = rankingChip("⚡ 过人榜", "dribbles", container)
        val cardsTab = rankingChip("🟨 牌榜", "cards", container)
        filterRow.addView(scorersTab)
        filterRow.addView(assistsTab)
        filterRow.addView(ratingsTab)
        filterRow.addView(shotsTab)
        filterRow.addView(keypassTab)
        filterRow.addView(tacklesTab)
        filterRow.addView(dribblesTab)
        filterRow.addView(cardsTab)
        container.addView(filterRow)

        // API优先加载射手榜数据（仅首次加载）
        if (!apiScorersLoaded) {
            fragmentScope.launch {
                apiScorersData = repo.standings.getScorers()
                apiScorersLoaded = true
                if (rankingTab == "scorers") {
                    renderRankingList(container)
                }
            }
        }

        // API优先加载赛季排名数据（助攻/评分/牌 — 仅首次加载）
        if (!apiRankingsLoaded) {
            fragmentScope.launch {
                apiRankingsData = repo.standings.getSeasonRankings(requireContext())
                apiRankingsLoaded = true
                if (rankingTab == "assists" || rankingTab == "ratings" || rankingTab == "cards" ||
                    rankingTab == "shots" || rankingTab == "keypass" || rankingTab == "tackles" || rankingTab == "dribbles") {
                    renderRankingList(container)
                }
            }
        }

        // 加载本地 match_events 数据（备选方案）
        renderRankingList(container)
    }

    private fun renderRankingList(container: LinearLayout) {
        // 移除之前渲染的榜单数据（保留filterRow）
        while (container.childCount > 1) container.removeViewAt(1)

        try {
            val json = requireContext().assets.open("match_events.json").bufferedReader().use { it.readText() }
            val gson = Gson()
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val allEvents: Map<String, Any> = gson.fromJson(json, type)

            val matchesJson = requireContext().assets.open("matches.json").bufferedReader().use { it.readText() }
            val matchesType = object : TypeToken<List<Map<String, Any>>>() {}.type
            val matches: List<Map<String, Any>> = gson.fromJson(matchesJson, matchesType)
            val matchMap = matches.associateBy { it["id"] as? String ?: "" }

            // 计算球员数据（助攻/牌用本地真实事件；评分用真实rating估算；射手榜用API）
            val playerStats = mutableMapOf<String, PlayerStat>()
            val playerGoals = mutableMapOf<String, Int>()
            val playerAssists = mutableMapOf<String, Int>()
            val playerCards = mutableMapOf<String, Int>()     // yellow+red
            val playerMatches = mutableMapOf<String, MutableSet<String>>()
            val playerTeam = mutableMapOf<String, String>()
            val playerNameCn = mutableMapOf<String, String>()

            for ((matchId, ed) in allEvents) {
                val edMap = ed as? Map<String, Any> ?: continue
                val rawEvents = edMap["events"] as? List<Map<String, Any>> ?: continue

                for (evt in rawEvents) {
                    val type2 = evt["type"] as? String ?: continue
                    val player = evt["player"] as? String ?: ""
                    val playerCn = evt["playerCn"] as? String ?: player
                    val team = evt["team"] as? String ?: ""
                    val teamCn = evt["teamCn"] as? String ?: ""
                    val key = "$team:$player"

                    playerMatches.putIfAbsent(key, mutableSetOf())
                    playerMatches[key]!!.add(matchId)
                    if (playerTeam[key] == null) playerTeam[key] = teamCn
                    playerNameCn[key] = playerCn

                    when (type2) {
                        "goal" -> {
                            playerGoals[key] = (playerGoals[key] ?: 0) + 1
                            // 不再添加假评分！评分数据仅从API获取
                        }
                        "assist" -> {
                            playerAssists[key] = (playerAssists[key] ?: 0) + 1
                        }
                        "yellow", "red" -> {
                            playerCards[key] = (playerCards[key] ?: 0) + 1
                        }
                    }
                }
            }

            // 构建本地数据列表
            val scorers = playerGoals.entries
                .sortedByDescending { it.value }
                .take(30)
                .mapNotNull { entry ->
                    val key = entry.key
                    val teamN = key.substringBefore(":")
                    val playerN = key.substringAfter(":")
                    val m = playerMatches[key]?.size ?: 0
                    if (m == 0) return@mapNotNull null
                    val tc = playerNameCn[key] ?: playerN
                    val cn = getPlayerCn(tc)
                    PlayerStat(playerN, cn, playerTeam[key] ?: teamN, entry.value.toDouble(), m, teamEn = teamN)
                }

            val assistList = playerAssists.entries
                .sortedByDescending { it.value }
                .take(30)
                .mapNotNull { entry ->
                    val key = entry.key
                    val teamN = key.substringBefore(":")
                    val playerN = key.substringAfter(":")
                    val m = playerMatches[key]?.size ?: 0
                    if (m == 0) return@mapNotNull null
                    val tc = playerNameCn[key] ?: playerN
                    val cn = getPlayerCn(tc)
                    PlayerStat(playerN, cn, playerTeam[key] ?: teamN, entry.value.toDouble(), m, teamEn = teamN)
                }

            val cardList = playerCards.entries
                .sortedByDescending { it.value }
                .take(30)
                .mapNotNull { entry ->
                    val key = entry.key
                    val teamN = key.substringBefore(":")
                    val playerN = key.substringAfter(":")
                    val m = playerMatches[key]?.size ?: 0
                    if (m == 0) return@mapNotNull null
                    val tc = playerNameCn[key] ?: playerN
                    val cn = getPlayerCn(tc)
                    PlayerStat(playerN, cn, playerTeam[key] ?: teamN, entry.value.toDouble(), m, teamEn = teamN)
                }

            // 选择当前数据源
            val title: String
            val valueLabel: String
            val valueSuffix: String
            val currentData: List<PlayerStat>

            when (rankingTab) {
                "scorers" -> {
                    title = "⚽ 射手榜"
                    valueLabel = "进球"
                    valueSuffix = "次"
                    if (apiScorersLoaded && apiScorersData.isNotEmpty()) {
                        currentData = apiScorersData.map { sr ->
                            val cn = getPlayerCn(sr.nameCn)
                            // 从 SeasonRanking 数据中匹配出场次数
                            val appearances = if (apiRankingsLoaded) {
                                apiRankingsData.firstOrNull { r ->
                                    r.playerNameCn == cn || 
                                    r.playerName.equals(sr.nameCn, ignoreCase = true)
                                }?.appearances ?: 0
                            } else 0
                            PlayerStat(cn, cn, MatchData.getChineseName(sr.teamName), sr.goals.toDouble(), appearances, teamEn = sr.teamName)
                        }
                    } else {
                        currentData = scorers
                    }
                }
                "assists" -> {
                    title = "🅰 助攻榜"
                    valueLabel = "助攻"
                    valueSuffix = "次"
                    currentData = if (apiRankingsLoaded && apiRankingsData.isNotEmpty()) {
                        apiRankingsData
                            .filter { it.assists > 0 }
                            .sortedByDescending { it.assists }
                            .take(30)
                            .map { val cn = getPlayerCn(it.playerNameCn.ifEmpty { it.playerName }); PlayerStat(cn, cn, MatchData.getChineseName(it.teamName), it.assists.toDouble(), it.appearances, teamEn = it.teamName) }
                    } else {
                        assistList
                    }
                }
                "ratings" -> {
                    title = "⭐ 评分榜"
                    valueLabel = "评分"
                    valueSuffix = ""
                    currentData = if (apiRankingsLoaded && apiRankingsData.isNotEmpty()) {
                        apiRankingsData
                            .filter { it.rating > 0 }
                            .sortedByDescending { it.rating }
                            .take(30)
                            .map { val cn = getPlayerCn(it.playerNameCn.ifEmpty { it.playerName }); PlayerStat(cn, cn, MatchData.getChineseName(it.teamName), it.rating, it.appearances, teamEn = it.teamName) }
                    } else {
                        emptyList()
                    }
                }
                "shots" -> {
                    title = "🎯 射正榜"
                    valueLabel = "射正"
                    valueSuffix = "次"
                    currentData = if (apiRankingsLoaded && apiRankingsData.isNotEmpty()) {
                        apiRankingsData
                            .filter { it.shotsOnTarget > 0 }
                            .sortedByDescending { it.shotsOnTarget }
                            .take(30)
                            .map { val cn = getPlayerCn(it.playerNameCn.ifEmpty { it.playerName }); PlayerStat(cn, cn, MatchData.getChineseName(it.teamName), it.shotsOnTarget.toDouble(), it.appearances, teamEn = it.teamName) }
                    } else { emptyList() }
                }
                "keypass" -> {
                    title = "🔑 关键传球"
                    valueLabel = "传球"
                    valueSuffix = "次"
                    currentData = if (apiRankingsLoaded && apiRankingsData.isNotEmpty()) {
                        apiRankingsData
                            .filter { it.keyPasses > 0 }
                            .sortedByDescending { it.keyPasses }
                            .take(30)
                            .map { val cn = getPlayerCn(it.playerNameCn.ifEmpty { it.playerName }); PlayerStat(cn, cn, MatchData.getChineseName(it.teamName), it.keyPasses.toDouble(), it.appearances, teamEn = it.teamName) }
                    } else { emptyList() }
                }
                "tackles" -> {
                    title = "💪 抢断榜"
                    valueLabel = "抢断"
                    valueSuffix = "次"
                    currentData = if (apiRankingsLoaded && apiRankingsData.isNotEmpty()) {
                        apiRankingsData
                            .filter { it.tackles > 0 }
                            .sortedByDescending { it.tackles }
                            .take(30)
                            .map { val cn = getPlayerCn(it.playerNameCn.ifEmpty { it.playerName }); PlayerStat(cn, cn, MatchData.getChineseName(it.teamName), it.tackles.toDouble(), it.appearances, teamEn = it.teamName) }
                    } else { emptyList() }
                }
                "dribbles" -> {
                    title = "⚡ 过人榜"
                    valueLabel = "过人"
                    valueSuffix = "次"
                    currentData = if (apiRankingsLoaded && apiRankingsData.isNotEmpty()) {
                        apiRankingsData
                            .filter { it.dribbles > 0 }
                            .sortedByDescending { it.dribbles }
                            .take(30)
                            .map { val cn = getPlayerCn(it.playerNameCn.ifEmpty { it.playerName }); PlayerStat(cn, cn, MatchData.getChineseName(it.teamName), it.dribbles.toDouble(), it.appearances, teamEn = it.teamName) }
                    } else { emptyList() }
                }
                "cards" -> {
                    title = "🟨 纪律榜"
                    valueLabel = "牌"
                    valueSuffix = "次"
                    currentData = if (apiRankingsLoaded && apiRankingsData.isNotEmpty()) {
                        apiRankingsData
                            .filter { it.cards > 0 }
                            .sortedByDescending { it.cards }
                            .take(30)
                            .map { val cn = getPlayerCn(it.playerNameCn.ifEmpty { it.playerName }); PlayerStat(cn, cn, MatchData.getChineseName(it.teamName), it.cards.toDouble(), it.appearances, teamEn = it.teamName) }
                    } else {
                        cardList
                    }
                }
                else -> {
                    title = "球员榜"; valueLabel = ""; valueSuffix = ""; currentData = emptyList()
                }
            }

            // 标题
            container.addView(TextView(requireContext()).apply {
                text = title
                setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 4, 0, 8)
            })

            if (currentData.isEmpty() && rankingTab != "ratings") {
                container.addView(TextView(requireContext()).apply {
                    text = "数据积累中，比赛开始后将自动更新"
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                    textSize = 14f; setPadding(0, 30, 0, 30); gravity = android.view.Gravity.CENTER
                })
            } else if (rankingTab == "ratings" && currentData.isEmpty()) {
                // 评分榜：API无数据时才显示说明
                container.addView(TextView(requireContext()).apply {
                    text = "⭐ 评分数据将在比赛结束后更新\n数据来源: api-sports.io Pro"
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                    textSize = 13f; setPadding(16, 30, 16, 30); gravity = android.view.Gravity.CENTER
                })
                container.addView(TextView(requireContext()).apply {
                    text = "评分来自 api-sports 赛后数据，半场后逐步更新\n请等待比赛结束"
                    setTextColor(android.graphics.Color.parseColor("#555577"))
                    textSize = 11f; setPadding(16, 0, 16, 30); gravity = android.view.Gravity.CENTER
                })
            } else {
                // Header
                val headerRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(4, 6, 4, 6)
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg_secondary))
                }
                headerRow.addView(statCell("#", 0.5f, "#666666"))
                headerRow.addView(statCell("球员", 3f, "#666666"))
                headerRow.addView(statCell("球队", 2f, "#666666"))
                headerRow.addView(statCell(valueLabel, 1f, "#666666"))
                headerRow.addView(statCell("场次", 1f, "#666666"))
                container.addView(headerRow)

                val top3Colors = listOf("#FFD700", "#C0C0C0", "#CD7F32")
                for ((i, ps) in currentData.withIndex()) {
                    val rank = i + 1
                    val vStr = if (rankingTab == "ratings") {
                        "${"%.1f".format(ps.value)}"
                    } else {
                        "${ps.value.toInt()}${valueSuffix}"
                    }

                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(4, 8, 4, 8)
                        val bg = if (i % 2 == 0) ContextCompat.getColor(requireContext(), R.color.bg_primary)
                            else ContextCompat.getColor(requireContext(), R.color.bg_secondary)
                        setBackgroundColor(bg)
                    }

                    val rankColor = if (i < 3) top3Colors[i] else "#8888AA"
                    row.addView(statCell("$rank", 0.5f, rankColor))

                    // 球员名 → 点击跳转球员详情
                    val playerNameKey = ps.name.takeIf { it.isNotEmpty() } ?: ps.nameCn
                    row.addView(TextView(requireContext()).apply {
                        text = ps.nameCn
                        setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
                        setOnClickListener {
                            val intent = Intent(requireContext(), worldcup.helper.ui.match.PlayerDetailActivity::class.java)
                            intent.putExtra("player_name", playerNameKey)
                            intent.putExtra("team_name", ps.teamEn)
                            startActivity(intent)
                        }
                    })

                    // 球队名 → 点击跳转球队详情
                    val teamKey = ps.teamEn.takeIf { it.isNotEmpty() } ?: ps.teamCn
                    row.addView(TextView(requireContext()).apply {
                        text = ps.teamCn
                        setTextColor(android.graphics.Color.parseColor("#8888AA"))
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
                        setOnClickListener {
                            val intent = Intent(requireContext(), worldcup.helper.ui.teams.TeamDetailActivity::class.java)
                            intent.putExtra("team_name", teamKey)
                            startActivity(intent)
                        }
                    })
                    row.addView(statCell(vStr, 1f, "#FF6B35"))
                    row.addView(statCell(if (ps.matches > 0) "${ps.matches}" else "—", 1f, "#8888AA"))
                    container.addView(row)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("DataFragment", "加载球员榜失败", e)
            container.addView(TextView(requireContext()).apply {
                text = "加载失败，请重试"
                setTextColor(android.graphics.Color.parseColor("#FF4444"))
                textSize = 14f; setPadding(0, 40, 0, 40); gravity = android.view.Gravity.CENTER
            })
        }
    }

    private fun rankingChip(text: String, tabKey: String, container: ViewGroup): TextView {
        val chip = TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(10, 6, 10, 6)
            val active = tabKey == rankingTab
            setBackgroundColor(android.graphics.Color.parseColor(if (active) "#FF6B35" else "#1A1A2E"))
            setTextColor(android.graphics.Color.parseColor(if (active) "#FFFFFF" else "#8888AA"))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(2, 0, 2, 0) }
            setOnClickListener {
                rankingTab = tabKey
                loadRankings(requireView())
            }
        }
        return chip
    }

    private fun updateRankingChips(vararg chips: TextView) {
        for (chip in chips) {
            val tabKey = when (chip.text.toString()) {
                "⚽ 射手榜" -> "scorers"
                "🅰 助攻榜" -> "assists"
                "⭐ 评分榜" -> "ratings"
                "🎯 射正榜" -> "shots"
                "🔑 关键传" -> "keypass"
                "💪 抢断榜" -> "tackles"
                "⚡ 过人榜" -> "dribbles"
                "🟨 牌榜" -> "cards"
                else -> ""
            }
            val active = tabKey == rankingTab
            chip.setBackgroundColor(android.graphics.Color.parseColor(if (active) "#FF6B35" else "#1A1A2E"))
            chip.setTextColor(android.graphics.Color.parseColor(if (active) "#FFFFFF" else "#8888AA"))
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================
    private fun statCell(text: String, weight: Float): TextView {
        return statCell(text, weight, "#8888AA")
    }

    private fun statCell(text: String, weight: Float, color: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor(color))
            textSize = 12f
            typeface = if (text.all { it.isDigit() || it == '+' || it == '-' || it == '.' })
                android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            gravity = android.view.Gravity.CENTER
        }
    }

    companion object {
        val teamCnMap = mapOf(
            "Mexico" to "墨西哥", "South Africa" to "南非", "Korea Republic" to "韩国", "South Korea" to "韩国",
            "Czech Republic" to "捷克", "Czechia" to "捷克", "Canada" to "加拿大",
            "Bosnia-Herzegovina" to "波黑", "Bosnia and Herzegovina" to "波黑",
            "Qatar" to "卡塔尔", "Switzerland" to "瑞士", "Brazil" to "巴西", "Morocco" to "摩洛哥",
            "Haiti" to "海地", "Scotland" to "苏格兰", "United States" to "美国",
            "Paraguay" to "巴拉圭", "Australia" to "澳大利亚", "Turkey" to "土耳其",
            "Germany" to "德国", "Curaçao" to "库拉索", "Ivory Coast" to "科特迪瓦",
            "Ecuador" to "厄瓜多尔", "Netherlands" to "荷兰", "Japan" to "日本",
            "Sweden" to "瑞典", "Tunisia" to "突尼斯", "Spain" to "西班牙",
            "Cape Verde Islands" to "佛得角", "Cape Verde" to "佛得角",
            "Belgium" to "比利时", "Egypt" to "埃及", "Saudi Arabia" to "沙特阿拉伯",
            "Uruguay" to "乌拉圭", "Iran" to "伊朗", "New Zealand" to "新西兰",
            "France" to "法国", "Senegal" to "塞内加尔", "Iraq" to "伊拉克",
            "Norway" to "挪威", "Argentina" to "阿根廷", "Algeria" to "阿尔及利亚",
            "Austria" to "奥地利", "Jordan" to "约旦", "Portugal" to "葡萄牙",
            "DR Congo" to "刚果(金)", "Democratic Republic of the Congo" to "刚果(金)",
            "Uzbekistan" to "乌兹别克斯坦",
            "Colombia" to "哥伦比亚", "England" to "英格兰", "Croatia" to "克罗地亚",
            "Ghana" to "加纳", "Panama" to "巴拿马", "Denmark" to "丹麦",
            "Serbia" to "塞尔维亚", "Italy" to "意大利", "Nigeria" to "尼日利亚",
            "Cameroon" to "喀麦隆", "Chile" to "智利", "Peru" to "秘鲁",
            "Ukraine" to "乌克兰", "Costa Rica" to "哥斯达黎加", "Greece" to "希腊",
            "Russia" to "俄罗斯", "Slovakia" to "斯洛伐克", "Romania" to "罗马尼亚",
            "Venezuela" to "委内瑞拉", "Finland" to "芬兰", "Wales" to "威尔士",
            "Bolivia" to "玻利维亚", "Hungary" to "匈牙利", "Bulgaria" to "保加利亚",
            "Iceland" to "冰岛", "Albania" to "阿尔巴尼亚", "North Macedonia" to "北马其顿",
            "Slovenia" to "斯洛文尼亚", "Montenegro" to "黑山", "Georgia" to "格鲁吉亚",
            "Armenia" to "亚美尼亚", "Kosovo" to "科索沃", "Northern Ireland" to "北爱尔兰",
            "Republic of Ireland" to "爱尔兰", "Israel" to "以色列",
            "USA" to "美国", "Congo DR" to "刚果(金)",
            "TBD" to "待定"
        )
        val fifaCodeMap = mapOf(
            "Mexico" to "MEX", "South Africa" to "RSA", "Korea Republic" to "KOR", "South Korea" to "KOR",
            "Czech Republic" to "CZE", "Canada" to "CAN",
            "Bosnia-Herzegovina" to "BIH", "Bosnia and Herzegovina" to "BIH",
            "Qatar" to "QAT", "Switzerland" to "SUI", "Brazil" to "BRA", "Morocco" to "MAR",
            "Haiti" to "HAI", "Scotland" to "SCO", "United States" to "USA",
            "Paraguay" to "PAR", "Australia" to "AUS", "Turkey" to "TUR",
            "Germany" to "GER", "Curaçao" to "CUW", "Ivory Coast" to "CIV",
            "Ecuador" to "ECU", "Netherlands" to "NED", "Japan" to "JPN",
            "Sweden" to "SWE", "Tunisia" to "TUN", "Spain" to "ESP",
            "Cape Verde Islands" to "CPV", "Cape Verde" to "CPV",
            "Belgium" to "BEL", "Egypt" to "EGY", "Saudi Arabia" to "KSA",
            "Uruguay" to "URU", "Iran" to "IRN", "New Zealand" to "NZL",
            "France" to "FRA", "Senegal" to "SEN", "Iraq" to "IRQ",
            "Norway" to "NOR", "Argentina" to "ARG", "Algeria" to "ALG",
            "Austria" to "AUT", "Jordan" to "JOR", "Portugal" to "POR",
            "DR Congo" to "COD", "Democratic Republic of the Congo" to "COD",
            "Uzbekistan" to "UZB",
            "Colombia" to "COL", "England" to "ENG", "Croatia" to "CRO",
            "Ghana" to "GHA", "Panama" to "PAN", "Denmark" to "DEN",
            "Serbia" to "SRB", "Italy" to "ITA", "Nigeria" to "NGA",
            "Cameroon" to "CMR", "Chile" to "CHI", "Peru" to "PER"
        )

        /**
         * 3字母 TLA → 本地 FIFA 2字母代码 映射
         * football-data.org API 返回 3-letter TLA（如 "BRA", "KOR", "COD"）
         * 本地 matches.json 使用 2-letter FIFA 代码（如 "BR", "KR", "CD"）
         * 此映射用于 TLA→FIFA2 的精准匹配
         */
        val tlaToFifa2: Map<String, String> = mapOf(
            "MEX" to "MX", "RSA" to "ZA", "KOR" to "KR", "CZE" to "CZ",
            "CAN" to "CA", "BIH" to "BA", "QAT" to "QA", "SUI" to "CH",
            "BRA" to "BR", "MAR" to "MA", "HAI" to "HT", "SCO" to "XS",
            "USA" to "US", "PAR" to "PY", "AUS" to "AU", "TUR" to "TR",
            "GER" to "DE", "CUW" to "CW", "CIV" to "CI", "ECU" to "EC",
            "NED" to "NL", "JPN" to "JP", "SWE" to "SE", "TUN" to "TN",
            "ESP" to "ES", "CPV" to "CV", "BEL" to "BE", "EGY" to "EG",
            "KSA" to "SA", "URU" to "UY", "IRN" to "IR", "NZL" to "NZ",
            "FRA" to "FR", "SEN" to "SN", "IRQ" to "IQ", "NOR" to "NO",
            "ARG" to "AR", "ALG" to "DZ", "AUT" to "AT", "JOR" to "JO",
            "POR" to "PT", "COD" to "CD", "UZB" to "UZ", "COL" to "CO",
            "ENG" to "GB-ENG", "CRO" to "HR", "GHA" to "GH", "PAN" to "PA",
            "DEN" to "DK", "SRB" to "RS", "ITA" to "IT", "NGA" to "NG",
            "CMR" to "CM", "CHI" to "CL", "PER" to "PE", "UKR" to "UA",
            "CRC" to "CR", "GRE" to "GR", "WAL" to "GB-WLS",
            "ROU" to "RO", "VEN" to "VE", "FIN" to "FI", "BOL" to "BO",
            "HUN" to "HU", "BUL" to "BG", "ISL" to "IS", "ALB" to "AL",
            "MKD" to "MK", "SVN" to "SI", "MNE" to "ME", "GEO" to "GE",
            "SVK" to "SK", "IDN" to "ID"
        )
        private val BRACKET_ORDER_R32 = arrayOf(
            // 上半区 — 4对
            "537415", "537416",   // 德国vs巴拉圭 + 法国vs瑞典 → 1/8#1 (537376)
            "537417", "537418",   // 南非vs加拿大 + 荷兰vs摩洛哥 → 1/8#2 (537375)
            "537423", "537424",   // 巴西vs日本 + 科特迪瓦vs挪威 → 1/8#3 (537377)
            "537425", "537426",   // 墨西哥vs厄瓜多尔 + 英格兰vs刚果(金) → 1/8#4 (537378)
            // 下半区 — 4对
            "537419", "537420",   // 葡萄牙vs克罗地亚 + 西班牙vs奥地利 → 1/8#5 (537379)
            "537421", "537422",   // 美国vs波黑 + 比利时vs塞内加尔 → 1/8#6 (537380)
            "537427", "537428",   // 阿根廷vs佛得角 + 澳大利亚vs埃及 → 1/8#7 (537381)
            "537429", "537430"    // 瑞士vs阿尔及利亚 + 哥伦比亚vs加纳 → 1/8#8 (537382)
        )

        /** 1/8决赛晋级排列顺序 — 对应 R32 配对结果 */
        private val BRACKET_ORDER_R16 = arrayOf(
            "537376", "537375", "537377", "537378",  // 上半区
            "537379", "537380", "537381", "537382"   // 下半区
        )

        /** 1/4决赛排列顺序 */
        private val BRACKET_ORDER_QF = arrayOf(
            "537383", "537385",   // 上半区 QF
            "537384", "538386"    // 下半区 QF
        )

        /** 半决赛排列顺序 */
        private val BRACKET_ORDER_SF = arrayOf("537387", "537388")
    }
}
