package worldcup.helper.ui.live

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import coil.load
import worldcup.helper.R
import worldcup.helper.data.CircleFlagLoader
import worldcup.helper.data.MatchData
import worldcup.helper.data.PredictionData
import worldcup.helper.data.repos.SharedRepository
import worldcup.helper.ui.match.MatchDetailActivity
import worldcup.helper.ui.teams.TeamDetailActivity

/**
 * Tab A: 实时赛况看板 — v3.0
 *
 * ⭐ 改进:
 *   - 🕐 API实时时钟（来自 api-sports fixtures?live=all）
 *   - 🔴 多场直播同时显示
 *   - 📊 事件按主客队分组
 *   - 🏃 BDL真实阵容（非本地前11人）
 */
class LiveFragment : Fragment() {

    private lateinit var flagLoader: CircleFlagLoader
    private lateinit var viewModel: LiveViewModel
    private lateinit var repo: SharedRepository

    /** 1秒时钟滴答（仅兜底，优先用API时钟） */
    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockRunnable: Runnable? = null
    private var isLiveMode = false
    private var currentLiveMatchIds: List<String> = emptyList()

    /** 多场 → 单场详情聚焦 */
    private var focusedMatchId: String? = null
    private var multiMatchList: List<MatchData.Match> = emptyList()
    private var multiClockMap: Map<String, Int> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_live, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        flagLoader = CircleFlagLoader(requireContext())
        repo = SharedRepository.getInstance(requireContext())
        viewModel = ViewModelProvider(this)[LiveViewModel::class.java]

        view.findViewById<View>(R.id.btn_goto_schedule).setOnClickListener {
            val bottomNav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            bottomNav?.selectedItemId = R.id.nav_schedule
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state -> renderState(view, state) }

        // 直播卡片数据观察（单场模式 → 渲染事件/统计/阵容等详细内容 + 同步比分）
        viewModel.liveCards.observe(viewLifecycleOwner) { cards ->
            if (cards.isNotEmpty() && currentLiveMatchIds.isNotEmpty()) {
                if (currentLiveMatchIds.size == 1) {
                    // 单场模式 → 更新比分卡 + 渲染详情 section
                    val firstId = currentLiveMatchIds.first()
                    val cardData = cards.find { it.match.id == firstId }
                    if (cardData != null) {
                        updateScoreCardFromCardData(view, cardData)
                    }
                    renderSingleMatchSections(view, cards)
                } else {
                    // 多场模式
                    val focusId = focusedMatchId
                    if (focusId != null) {
                        // 聚焦到某场比赛详情 → 更新比分卡 + 该场数据
                        val cardData = cards.find { it.match.id == focusId }
                        if (cardData != null) {
                            updateScoreCardFromCardData(view, cardData)
                            val container = view.findViewById<ScrollView>(R.id.prediction_container)
                            val list = container.findViewById<LinearLayout>(R.id.prediction_list)
                            list.removeAllViews()
                            renderSingleMatchSectionsInternal(list, cardData)
                        }
                    } else {
                        // 概览模式 → 更新卡片比分和事件预览
                        updateMultiMatchCards(view, cards)
                    }
                }
            }
        }

        viewModel.liveClockMap.observe(viewLifecycleOwner) { clockMap ->
            if (clockMap.isNotEmpty()) {
                updateClockDisplay(view, clockMap)
            }
        }

        viewModel.load()
    }

    /** 防重复渲染标记 */
    private var listNeedsStat = true
    private var listNeedsLineup = true

    // ════════════════════════════════════════════
    //  状态分发
    // ════════════════════════════════════════════

    private fun renderState(view: View, state: LiveUiState) {
        stopClock()
        when (state) {
            is LiveUiState.Loading -> showLoading(view)
            is LiveUiState.LiveMatch -> showLiveMatch(view, state)
            is LiveUiState.MultiLiveMatches -> showMultiLiveMatches(view, state)
            is LiveUiState.RecentMatch -> showRecentMatch(view, state)
            is LiveUiState.Predictions -> showPredictions(view, state)
            is LiveUiState.AllFinished -> showAllFinished(view)
            is LiveUiState.Error -> showError(view, state.message)
        }
    }

    // ════════════════════════════════════════════
    //  单场直播
    // ════════════════════════════════════════════

    private fun showLiveMatch(view: View, state: LiveUiState.LiveMatch) {
        currentLiveMatchIds = listOf(state.match.id)
        val match = state.match

        view.findViewById<View>(R.id.live_content).visibility = View.VISIBLE
        view.findViewById<View>(R.id.tv_empty).visibility = View.GONE
        view.findViewById<View>(R.id.score_card).visibility = View.VISIBLE
        view.findViewById<View>(R.id.prediction_container).visibility = View.VISIBLE
        view.findViewById<View>(R.id.tv_prediction).visibility = View.GONE
        view.findViewById<View>(R.id.tv_match_info).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tv_venue_info).visibility = View.VISIBLE

        // 标题：用 API 时钟（优先）或本地计算兜底
        updateLiveTitleClock(view, match.id, state.elapsedSec)

        // 启动1秒时钟循环（优先显示API时钟，API不可用时兜底）
        startPersistentClock(view, match)

        renderScoreboard(view, match)
        renderMatchInfo(view, match)
        renderSectionsFromLiveCards(view, match.id)

        view.findViewById<View>(R.id.score_card).setOnClickListener {
            startActivity(Intent(requireContext(), MatchDetailActivity::class.java).apply {
                putExtra("match_id", match.id)
            })
        }

        // 点击主客队名 → 跳转球队详情
        view.findViewById<TextView>(R.id.tv_home_name)?.setOnClickListener {
            startActivity(Intent(requireContext(), TeamDetailActivity::class.java).apply {
                putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, match.homeTeam)
            })
        }
        view.findViewById<TextView>(R.id.tv_away_name)?.setOnClickListener {
            startActivity(Intent(requireContext(), TeamDetailActivity::class.java).apply {
                putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, match.awayTeam)
            })
        }
    }

    /** 从 liveCards 数据渲染事件/统计/阵容 section */
    private fun renderSectionsFromLiveCards(view: View, matchId: String) {
        val cards = viewModel.liveCards.value
        if (cards.isNullOrEmpty()) return
        val cardData = cards.find { it.match.id == matchId } ?: return
        val container = view.findViewById<ScrollView>(R.id.prediction_container)
        val list = container.findViewById<LinearLayout>(R.id.prediction_list)
        list.removeAllViews()
        renderSingleMatchSectionsInternal(list, cardData)
    }

    /**
     * 🆕 从 LiveMatchCardData 同步更新比分卡
     * 在轮询期间实时刷新 tv_score / tv_match_info / tv_ht_score
     */
    private fun updateScoreCardFromCardData(view: View, card: LiveViewModel.LiveMatchCardData) {
        val match = card.match
        view.findViewById<TextView>(R.id.tv_score)?.let {
            it.text = "${card.homeScore} : ${card.awayScore}"
        }
        view.findViewById<TextView>(R.id.tv_home_name)?.let {
            it.text = match.homeTeamCn
        }
        view.findViewById<TextView>(R.id.tv_away_name)?.let {
            it.text = match.awayTeamCn
        }
        // 状态描述 — 比赛阶段感知（由 3分钟周期时钟统一管理，此处仅对非直播状态补充）
        view.findViewById<TextView>(R.id.tv_match_info)?.let {
            val phase = viewModel.getMatchPhase(match.id)
            if (phase.isNotBlank() && (phase == "HT" || phase == "PEN" || phase == "FT" || phase == "AET")) {
                // 冻结/结束状态由 startPersistentClock 同步
            } else if (phase.isBlank() && match.status != "IN_PLAY") {
                // 非直播状态：直接显示日期时间
                val info = when {
                    match.status == "FINISHED" -> "✅ 已结束"
                    match.status == "PAUSED" -> "⏸️ 暂停"
                    else -> "%s %s".format(match.date, match.time)
                }
                it.text = info
            }
            // 直播中状态由 startPersistentClock 管理，此处不写
        }
        // 半场比分（API 已结束的比赛可能有半场数据）
        if (card.homeScore > 0 || card.awayScore > 0) {
            view.findViewById<TextView>(R.id.tv_ht_score)?.let { ht ->
                if (match.status == "FINISHED" && (card.homeScore > 0 || card.awayScore > 0)) {
                    ht.visibility = View.VISIBLE
                }
            }
        }
    }

    // ════════════════════════════════════════════
    //  多场直播（新增）
    // ════════════════════════════════════════════

    private fun showMultiLiveMatches(view: View, state: LiveUiState.MultiLiveMatches) {
        currentLiveMatchIds = state.matches.map { it.id }
        multiMatchList = state.matches
        multiClockMap = state.clockMap
        focusedMatchId = null

        view.findViewById<View>(R.id.live_content).visibility = View.GONE
        view.findViewById<View>(R.id.score_card).visibility = View.GONE
        view.findViewById<View>(R.id.tv_empty).visibility = View.GONE
        view.findViewById<View>(R.id.tv_prediction).visibility = View.GONE
        view.findViewById<View>(R.id.tv_match_info).visibility = View.GONE
        view.findViewById<TextView>(R.id.tv_venue_info).visibility = View.GONE

        renderMultiMatchOverview(view)

        // 启动本地时钟更新（兜底）
        isLiveMode = true
        clockRunnable?.let { clockHandler.removeCallbacks(it) }
        clockRunnable = object : Runnable {
            override fun run() {
                if (!isLiveMode) return
                clockHandler.postDelayed(this, 5000)
            }
        }
        clockHandler.post(clockRunnable!!)
    }

    /** 渲染多场直播概览列表 */
    private fun renderMultiMatchOverview(view: View) {
        val liveCount = multiMatchList.size
        view.findViewById<TextView>(R.id.tv_live_title).text = "🔴 $liveCount 场比赛直播中"

        val container = view.findViewById<ScrollView>(R.id.prediction_container)
        val list = container.findViewById<LinearLayout>(R.id.prediction_list)
        list.removeAllViews()
        container.visibility = View.VISIBLE

        for (match in multiMatchList) {
            val elapsed = multiClockMap[match.id] ?: 0
            addMultiMatchCard(list, match, elapsed)
        }
    }

    /** 从多场概览聚焦到单场比赛详情 */
    private fun focusOnMatch(view: View, matchId: String) {
        focusedMatchId = matchId

        // 更新标题：显示返回按钮提示
        val match = multiMatchList.find { it.id == matchId } ?: return
        view.findViewById<TextView>(R.id.tv_live_title).text = "← 返回  |  ${match.homeTeamCn} vs ${match.awayTeamCn}"
        view.findViewById<TextView>(R.id.tv_live_title).setOnClickListener {
            // 点击标题 → 回到多场概览
            focusedMatchId = null
            renderMultiMatchOverview(view)
        }

        // 动态渲染单场比赛详情（重用 liveCards 数据）
        val cards = viewModel.liveCards.value ?: return
        val cardData = cards.find { it.match.id == matchId } ?: return
        val container = view.findViewById<ScrollView>(R.id.prediction_container)
        val list = container.findViewById<LinearLayout>(R.id.prediction_list)
        list.removeAllViews()

        renderSingleMatchSectionsInternal(list, cardData)
    }

    /** 为多场直播添加每场比赛的卡片 */
    private fun addMultiMatchCard(list: LinearLayout, match: MatchData.Match, elapsedMin: Int) {
        val ctx = requireContext()
        val mId = match.id
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 12) }
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (!isAdded) return@setOnClickListener
                val rootView = this@LiveFragment.view ?: return@setOnClickListener
                // 🆕 点击 → 在本页面聚焦到该场比赛详情
                focusOnMatch(rootView, mId)
            }
        }

        // ── 时钟行 (index 0) — 比赛阶段感知 ──
        val clockTv = TextView(ctx).apply {
            text = getPhaseAwareClockText(mId, elapsedMin * 60)
            setTextColor(0xFF00FF88.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        card.addView(clockTv)

        // ── 比分行 (index 1) ──
        val scoreValueTv = TextView(ctx).apply {
            text = "${match.homeScore} : ${match.awayScore}"
            setTextColor(0xFFFF6B35.toInt()); textSize = 28f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(12, 0, 12, 0)
            id = View.generateViewId() // 用于后续更新
        }
        val scoreRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = match.homeTeamCn; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    startActivity(Intent(ctx, TeamDetailActivity::class.java).apply {
                        putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, match.homeTeam)
                    })
                }
            })
            addView(scoreValueTv)
            addView(TextView(ctx).apply {
                text = match.awayTeamCn; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    startActivity(Intent(ctx, TeamDetailActivity::class.java).apply {
                        putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, match.awayTeam)
                    })
                }
            })
        }
        card.addView(scoreRow)

        // ── 轮次信息 (index 2) ──
        card.addView(TextView(ctx).apply {
            text = "${match.round} · ${match.date} ${match.time}"
            setTextColor(0xFF8888AA.toInt()); textSize = 11f; setPadding(0, 6, 0, 0)
        })

        // ── 事件预览 (index 3) ──
        val eventPreview = TextView(ctx).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 11f
            setPadding(0, 6, 0, 0); maxLines = 3
        }
        card.addView(eventPreview)

        // 存引用以便后续更新
        card.tag = Pair(match.id, scoreValueTv.id)
        list.addView(card)
    }

    /** 更新多场直播卡片中的数据（每30秒由 liveCards 触发） */
    private fun updateMultiMatchCards(view: View, cards: List<LiveViewModel.LiveMatchCardData>) {
        if (currentLiveMatchIds.size <= 1) return // 单场模式不更新
        val container = view.findViewById<ScrollView>(R.id.prediction_container)
        val list = container.findViewById<LinearLayout>(R.id.prediction_list)

        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val tag = child.tag as? Pair<*, *> ?: continue
            val matchId = tag.first as? String ?: continue
            val scoreViewId = tag.second as? Int ?: continue
            val cardData = cards.find { it.match.id == matchId } ?: continue

            // 更新比分
            val scoreTv = child.findViewById<TextView>(scoreViewId)
            scoreTv?.text = "${cardData.homeScore} : ${cardData.awayScore}"

            // 更新事件预览（非空时）
            if (cardData.events != null && child is LinearLayout && child.childCount > 3) {
                val eventPreview = child.getChildAt(3) as? TextView
                val lines = cardData.events!!.eventsText.lines().filter { it.isNotBlank() }
                val preview = lines.take(3).joinToString("\n")
                if (preview.isNotBlank()) {
                    eventPreview?.text = "⚡ $preview"
                }
            }
        }
    }

    // ════════════════════════════════════════════
    //  单场模式：事件/统计/阵容渲染
    // ════════════════════════════════════════════

    private fun renderSingleMatchSections(view: View, cards: List<LiveViewModel.LiveMatchCardData>) {
        val card = cards.firstOrNull() ?: return
        val container = view.findViewById<ScrollView>(R.id.prediction_container)
        val list = container.findViewById<LinearLayout>(R.id.prediction_list)
        list.removeAllViews()
        renderSingleMatchSectionsInternal(list, card)
    }

    /** 内部方法：直接在 list 中写入事件/统计/阵容 + 深度分析 */
    private fun renderSingleMatchSectionsInternal(list: LinearLayout, card: LiveViewModel.LiveMatchCardData) {
        // Section 1: 双列事件时间轴
        addEventsTimeline(list, card)
        // Section 2: 统计对比
        addStatsComparison(list, card)
        // Section 3: 阵容
        addLineupSection(list, card)
        // Section 4: 全场最佳
        addBestPlayersSection(list, card)
        // Section 5: 射门效率
        addShotEfficiencySection(list, card)
        // Section 6: 传球效率
        addPassEfficiencySection(list, card)
    }

    /**
     * 🆕 双列事件时间轴
     * 
     * 布局: 
     * ┌──────────────┬────┬──────────────┐
     * │   主队事件    │    │   客队事件    │
     * │  55' ⚽ 洛萨诺 │──●──│              │
     * │              │    │  67' 🟨 穆夏拉 │
     * └──────────────┴────┴──────────────┘
     */
    private fun addEventsTimeline(list: LinearLayout, card: LiveViewModel.LiveMatchCardData) {
        val ctx = requireContext()
        val eventsText = card.events?.eventsText ?: return
        val rawLines = eventsText.lines().filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return

        // 解析事件行: "67' [H] ⚽ 洛萨诺 🅰️助攻者" 或 "55' [A] 🟨 穆夏拉"
        // 或 "70' [H] 🔄 ⬆️上场 ⬇️下场"
        val homeEvents = mutableListOf<EventLine>()
        val awayEvents = mutableListOf<EventLine>()

        for (line in rawLines) {
            val clean = line.replace(" [H] ", " ").replace(" [A] ", " ")
            val evt = EventLine(clean, detectEventColor(clean))
            if (line.contains("[H]")) homeEvents.add(evt)
            else if (line.contains("[A]")) awayEvents.add(evt)
            else homeEvents.add(evt)
        }

        // ── Section 标题 ──
        list.addView(makeSectionHeader(ctx, "⚡  比赛事件"))

        // 主客队队名行（颜色区分）
        val teamHeaderRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 6)
        }
        teamHeaderRow.addView(TextView(ctx).apply {
            text = card.match.homeTeamCn
            setTextColor(0xFFFF7043.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val divider = View(ctx).apply {
            setBackgroundColor(0xFF334466.toInt())
            layoutParams = LinearLayout.LayoutParams(2, 18.dpToPx(ctx))
        }
        teamHeaderRow.addView(divider)
        teamHeaderRow.addView(TextView(ctx).apply {
            text = card.match.awayTeamCn
            setTextColor(0xFF42A5F5.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        list.addView(teamHeaderRow)

        // 分隔线
        list.addView(View(ctx).apply {
            setBackgroundColor(0xFF334466.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                .apply { setMargins(0, 0, 0, 4) }
        })

        // 双列事件时间轴（按时间合并排列）
        val maxRows = maxOf(homeEvents.size, awayEvents.size).coerceAtLeast(1)
        for (i in 0 until maxRows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 4, 4, 4)
                if (i % 2 == 0) setBackgroundColor(0x08FFFFFF.toInt()) // 斑马纹
            }

            // 左列 — 主队事件（右对齐，颜色编码）
            val homeEvt = homeEvents.getOrNull(i)
            row.addView(TextView(ctx).apply {
                text = homeEvt?.text ?: ""
                setTextColor(homeEvt?.color ?: 0xFF888888.toInt()); textSize = 12f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, 8, 0)
                maxLines = 2
            })

            // 中线圆点（按事件类型变色）
            val awayEvt = awayEvents.getOrNull(i)
            val dotColor = when {
                homeEvt?.text?.contains("⚽") == true -> 0xFFFFD700.toInt()
                homeEvt?.text?.contains("🟥") == true -> 0xFFE53935.toInt()
                homeEvt?.text?.contains("🟨") == true -> 0xFFFFEB3B.toInt()
                awayEvt?.text?.contains("⚽") == true -> 0xFFFFD700.toInt()
                awayEvt?.text?.contains("🟥") == true -> 0xFFE53935.toInt()
                awayEvt?.text?.contains("🟨") == true -> 0xFFFFEB3B.toInt()
                else -> 0xFF4488FF.toInt()
            }
            val dotContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(14.dpToPx(ctx), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            dotContainer.addView(View(ctx).apply {
                setBackgroundColor(dotColor)
                val sz = 8.dpToPx(ctx)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    setMargins(3.dpToPx(ctx), 0, 3.dpToPx(ctx), 0)
                }
            })
            row.addView(dotContainer)

            // 右列 — 客队事件（左对齐）
            row.addView(TextView(ctx).apply {
                text = awayEvt?.text ?: ""
                setTextColor(awayEvt?.color ?: 0xFF888888.toInt()); textSize = 12f
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(8, 0, 0, 0)
                maxLines = 2
            })

            list.addView(row)
        }

        list.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
        })
    }

    /** 事件颜色：根据事件类型返回对应主题色 */
    private fun detectEventColor(text: String): Int {
        return when {
            text.contains("⚽") -> 0xFFFFD700.toInt()    // 进球 → 金色
            text.contains("🟥") -> 0xFFEF5350.toInt()    // 红牌 → 红色
            text.contains("🟨") -> 0xFFFFF176.toInt()    // 黄牌 → 亮黄
            text.contains("🔄") -> 0xFFCE93D8.toInt()    // 换人 → 紫色
            text.contains("📺") -> 0xFF81D4FA.toInt()    // VAR → 淡蓝
            else -> 0xFFB0BEC5.toInt()                   // 其他 → 灰白
        }
    }

    /** 单行事件数据 */
    private data class EventLine(val text: String, val color: Int)

    /** 统计对比 Section */
    private fun addStatsComparison(list: LinearLayout, card: LiveViewModel.LiveMatchCardData) {
        val stats = card.stats ?: return
        val ctx = requireContext()

        list.addView(makeSectionHeader(ctx, "📊  球队统计"))

        data class StatRow(val label: String, val home: String, val away: String)
        val rows = listOf(
            StatRow("控球率", stats.possession.first, stats.possession.second),
            StatRow("射正", stats.shotsOnTarget.first, stats.shotsOnTarget.second),
            StatRow("总射门", stats.totalShots.first, stats.totalShots.second),
            StatRow("角球", stats.corners.first, stats.corners.second),
            StatRow("犯规", stats.fouls.first, stats.fouls.second),
            StatRow("传球%", stats.passesPct.first, stats.passesPct.second)
        )

        for ((i, row) in rows.withIndex()) {
            val rowView = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 8, 8, 8)
                if (i % 2 == 0) setBackgroundColor(0x0AFFFFFF.toInt())
            }
            rowView.addView(TextView(ctx).apply {
                text = row.home; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            rowView.addView(TextView(ctx).apply {
                text = row.label; setTextColor(0xFF90A4AE.toInt()); textSize = 12f
                gravity = Gravity.CENTER; setPadding(16, 0, 16, 0)
            })
            rowView.addView(TextView(ctx).apply {
                text = row.away; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            list.addView(rowView)
        }

        list.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
        })
    }

    /** 阵容 Section — 球员卡片网格（头像+中文名+实时数据） */
    private fun addLineupSection(list: LinearLayout, card: LiveViewModel.LiveMatchCardData) {
        val players = card.playerLineup
        if (players.isEmpty()) {
            // 兜底：BDL 阵容数据
            val hadLegacy = addLegacyLineupSection(list, card)
            if (!hadLegacy) {
                // 终极兜底：显示提示
                val ctx = requireContext()
                list.addView(makeSectionHeader(ctx, "🏃  上场球员"))
                list.addView(TextView(ctx).apply {
                    text = "⏳ 阵容加载中..."
                    setTextColor(0xFF78909C.toInt()); textSize = 12f
                    gravity = Gravity.CENTER; setPadding(0, 12, 0, 12)
                })
            }
            return
        }
        val ctx = requireContext()
        val homeCn = card.match.homeTeamCn
        val awayCn = card.match.awayTeamCn

        list.addView(makeSectionHeader(ctx, "🏃  上场球员"))

        // ── 分割两队 ──
        val homePlayers = players.filter { it.isHome }
        val awayPlayers = players.filter { !it.isHome }

        addTeamPlayerCards(list, ctx, homeCn, homePlayers, true, card.lineup?.homeFormation)
        addTeamPlayerCards(list, ctx, awayCn, awayPlayers, false, card.lineup?.awayFormation)

        list.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
        })
    }

    /** 渲染单队球员卡片区 */
    private fun addTeamPlayerCards(list: LinearLayout, ctx: android.content.Context,
                                    teamName: String, players: List<PlayerMatchLineup>,
                                    isHome: Boolean, formation: String?) {
        val accentColor = if (isHome) 0xFFFF7043.toInt() else 0xFF42A5F5.toInt()
        val bgColor = if (isHome) 0x0AFF7043.toInt() else 0x0A42A5F5.toInt()

        // 队名标题行
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 4)
        }
        headerRow.addView(TextView(ctx).apply {
            text = teamName
            setTextColor(accentColor); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        if (!formation.isNullOrBlank()) {
            headerRow.addView(TextView(ctx).apply {
                text = "  $formation"
                setTextColor(0xFF78909C.toInt()); textSize = 11f
                setPadding(6, 1, 0, 0)
            })
        }
        // 上场人数
        headerRow.addView(TextView(ctx).apply {
            text = "  ${players.size}人上场"
            setTextColor(0xFF90A4AE.toInt()); textSize = 11f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.END }
        })
        list.addView(headerRow)

        // 球员网格（一行2-3个卡片）— 每行创建新 LinearLayout，避免复用崩溃
        var gridRow: LinearLayout? = null
        for ((i, p) in players.withIndex()) {
            if (i == 0 || i % 3 == 0) {
                gridRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 2, 0, 4)
                }
                list.addView(gridRow)
            }
            gridRow?.addView(createPlayerCard(ctx, p, isHome))
        }

        // 分隔线
        if (players.size > 3) {
            list.addView(View(ctx).apply {
                setBackgroundColor(0xFF1C2E40.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    .apply { setMargins(4, 0, 4, 0) }
            })
        }
    }

    /** 创建单名球员卡片 */
    private fun createPlayerCard(ctx: android.content.Context,
                                  p: PlayerMatchLineup,
                                  isHome: Boolean): android.widget.LinearLayout {
        val avatarSize = 40.dpToPx(ctx)
        val cardWidth = (resources.displayMetrics.widthPixels - 48) / 3  // 三等分

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(3, 4, 3, 4) }
            setPadding(4, 6, 4, 6)
            setBackgroundColor(if (isHome) 0x08FFFFFF.toInt() else 0x04FFFFFF.toInt())
        }

        // 头像（圆形 Coil）
        val avatarIv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        if (p.photoUrl.isNotEmpty()) {
            try {
                avatarIv.load(p.photoUrl) {
                    crossfade(true)
                    placeholder(android.R.color.darker_gray)
                }
            } catch (_: Exception) { }
        } else {
            avatarIv.setBackgroundColor(if (isHome) 0x33FF7043.toInt() else 0x3342A5F5.toInt())
        }
        card.addView(avatarIv)

        // 号码角标
        val numberBadge = TextView(ctx).apply {
            text = "#${p.number}"
            setTextColor(if (isHome) 0xFFFF7043.toInt() else 0xFF42A5F5.toInt())
            textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(numberBadge)

        // 中文名
        card.addView(TextView(ctx).apply {
            text = p.nameCn
            setTextColor(0xFFFFFFFF.toInt()); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; maxLines = 1
            setPadding(0, 1, 0, 0)
        })

        // 评分行
        val ratingColor = when {
            p.rating >= 8.0 -> 0xFFFFD700.toInt()   // 金色
            p.rating >= 6.5 -> 0xFF81C784.toInt()   // 绿色
            p.rating >= 5.0 -> 0xFFFFF176.toInt()   // 黄色
            else -> 0xFFEF5350.toInt()               // 红色
        }
        card.addView(TextView(ctx).apply {
            text = String.format("%.1f", p.rating)
            setTextColor(ratingColor); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        // 关键数据行（⚽🅰️🟨🟥）
        val statParts = mutableListOf<String>()
        if (p.goals > 0) statParts.add("⚽${p.goals}")
        if (p.assists > 0) statParts.add("🅰️${p.assists}")
        if (p.yellowCards > 0) statParts.add("🟨${p.yellowCards}")
        if (p.redCards > 0) statParts.add("🟥${p.redCards}")
        if (statParts.isNotEmpty()) {
            card.addView(TextView(ctx).apply {
                text = statParts.joinToString(" ")
                setTextColor(0xFFB0BEC5.toInt()); textSize = 10f
                gravity = Gravity.CENTER
            })
        }

        return card
    }

    /** 旧版阵容展示（BDL 兜底） — 返回 true=有数据渲染 */
    private fun addLegacyLineupSection(list: LinearLayout, card: LiveViewModel.LiveMatchCardData): Boolean {
        val lineup = card.lineup ?: return false
        val ctx = requireContext()
        list.addView(makeSectionHeader(ctx, "🏃  首发阵容"))
        val formationRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL; setPadding(8, 4, 8, 8)
        }
        formationRow.addView(TextView(ctx).apply {
            text = "${card.match.homeTeamCn}  ${lineup.homeFormation ?: ""}"
            setTextColor(0xFFFF7043.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        formationRow.addView(TextView(ctx).apply {
            text = "${lineup.awayFormation ?: ""}  ${card.match.awayTeamCn}"
            setTextColor(0xFF42A5F5.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        list.addView(formationRow)
        list.addView(View(ctx).apply {
            setBackgroundColor(0xFF223355.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, 0, 0, 4) }
        })
        val homePlayers = lineup.home.take(11)
        val awayPlayers = lineup.away.take(11)
        val maxRows = maxOf(homePlayers.size, awayPlayers.size)
        for (i in 0 until maxRows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 6, 4, 6)
                if (i % 2 == 0) setBackgroundColor(0x0AFFFFFF.toInt())
            }
            val homePlayer = homePlayers.getOrNull(i)
            val homeCell = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (homePlayer != null) {
                val displayName = if (homePlayer.name.isNotBlank()) homePlayer.name else "#${homePlayer.number}"
                homeCell.addView(TextView(ctx).apply { text = "#${homePlayer.number}"; setTextColor(0xFFFF7043.toInt()); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; minWidth = 28.dpToPx(ctx) })
                homeCell.addView(TextView(ctx).apply { text = displayName; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                homeCell.addView(TextView(ctx).apply { text = homePlayer.position.take(2); setTextColor(0xFF78909C.toInt()); textSize = 10f; setPadding(0, 0, 4, 0) })
            }
            row.addView(homeCell)
            row.addView(View(ctx).apply { setBackgroundColor(0xFF1C2E40.toInt()); layoutParams = LinearLayout.LayoutParams(1, 18.dpToPx(ctx)).apply { setMargins(4, 0, 4, 0) } })
            val awayPlayer = awayPlayers.getOrNull(i)
            val awayCell = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (awayPlayer != null) {
                val displayName = if (awayPlayer.name.isNotBlank()) awayPlayer.name else "#${awayPlayer.number}"
                awayCell.addView(TextView(ctx).apply { text = awayPlayer.position.take(2); setTextColor(0xFF78909C.toInt()); textSize = 10f; setPadding(4, 0, 0, 0) })
                awayCell.addView(TextView(ctx).apply { text = displayName; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.END })
                awayCell.addView(TextView(ctx).apply { text = "#${awayPlayer.number}"; setTextColor(0xFF42A5F5.toInt()); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; minWidth = 28.dpToPx(ctx); gravity = Gravity.END })
            }
            row.addView(awayCell)
            list.addView(row)
        }
        list.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8) })
        return true
    }

    // ════════════════════════════════════════════
    //  新增深度分析 Section
    // ════════════════════════════════════════════

    /**
     * Section 4: 🏆 全场最佳 — 卡片式设计，名字大而清晰
     */
    private fun addBestPlayersSection(list: LinearLayout, card: LiveViewModel.LiveMatchCardData) {
        val best = card.bestPlayers
        if (best.isEmpty()) return
        val ctx = requireContext()

        list.addView(makeSectionHeader(ctx, "🏆  全场最佳"))

        for ((idx, player) in best.take(3).withIndex()) {
            val cardBg = when (idx) {
                0 -> 0x33FFD700.toInt()  // 金色背景 — 第一
                1 -> 0x1EC0C0C0.toInt()  // 银色背景 — 第二
                else -> 0x18CD7F32.toInt() // 铜色背景 — 第三
            }
            val medalText = when (idx) { 0 -> "🥇"; 1 -> "🥈"; else -> "🥉" }
            val ratingVal = player.rating?.toFloatOrNull() ?: 0f
            val starCount = (ratingVal / 2f).toInt().coerceIn(0, 5)

            // 外卡片容器
            val cardView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cardBg)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
                setPadding(12, 10, 12, 10)
            }

            // 首行：奖牌 + 名字 + 评分
            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // 奖牌
            topRow.addView(TextView(ctx).apply {
                text = medalText; textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 10, 0) }
            })
            // 球员名（占满）— 中文优先，英文兜底
            topRow.addView(TextView(ctx).apply {
                text = player.name
                setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // 评分数字（醒目黄色）
            topRow.addView(TextView(ctx).apply {
                text = player.rating ?: ""
                setTextColor(0xFFFFD700.toInt()); textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(8, 0, 0, 0)
            })
            cardView.addView(topRow)

            // 第二行：队名 + 位置 + 星星
            val subRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(30, 4, 0, 0)  // 与奖牌对齐
            }
            subRow.addView(TextView(ctx).apply {
                text = if (idx == 0) "全场最佳" else if (idx == 1) "第二名" else "第三名"
                setTextColor(0xFFB0BEC5.toInt()); textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            subRow.addView(TextView(ctx).apply {
                text = "⭐".repeat(starCount)
                textSize = 11f; setPadding(0, 0, 4, 0)
            })
            cardView.addView(subRow)

            // 入选理由（仅 top1，单独一行）
            if (idx == 0 && player.reason.isNotBlank()) {
                cardView.addView(TextView(ctx).apply {
                    text = "\"${player.reason}\""
                    setTextColor(0xFF90A4AE.toInt()); textSize = 11f
                    setPadding(30, 4, 0, 0)
                })
            }

            list.addView(cardView)
        }

        list.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
        })
    }

    /**
     * Section 5: 🎯 射门效率对比 — 带标注进度条
     */
    private fun addShotEfficiencySection(list: LinearLayout, card: LiveViewModel.LiveMatchCardData) {
        val stats = card.stats ?: return
        val ctx = requireContext()

        val homeShotTot = stats.totalShots.first.toIntOrNull() ?: return
        val awayShotTot = stats.totalShots.second.toIntOrNull() ?: return
        val homeShotOn = stats.shotsOnTarget.first.toIntOrNull() ?: 0
        val awayShotOn = stats.shotsOnTarget.second.toIntOrNull() ?: 0
        if (homeShotTot == 0 && awayShotTot == 0) return

        list.addView(makeSectionHeader(ctx, "🎯  射门效率"))

        addShotBar(list, card.match.homeTeamCn, homeShotOn, homeShotTot, 0xFFFF7043.toInt())
        addShotBar(list, card.match.awayTeamCn, awayShotOn, awayShotTot, 0xFF42A5F5.toInt())

        list.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
        })
    }

    /** 单条射门进度条：射正(绿) + 射偏(灰暗) + 清晰数字标注 */
    private fun addShotBar(list: LinearLayout, teamName: String, onTarget: Int, total: Int, color: Int) {
        val ctx = requireContext()
        val offTarget = (total - onTarget).coerceAtLeast(0)
        val pct = if (total > 0) (onTarget.toFloat() / total * 100).toInt() else 0

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 6, 8, 6)
        }

        // 第一行：队名 + 射正/总数 + 百分比
        val infoRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoRow.addView(TextView(ctx).apply {
            text = teamName
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        infoRow.addView(TextView(ctx).apply {
            text = "$onTarget 射正 / $total 射门"
            setTextColor(0xFF90A4AE.toInt()); textSize = 12f; setPadding(0, 0, 8, 0)
        })
        infoRow.addView(TextView(ctx).apply {
            text = "$pct%"
            setTextColor(color); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(infoRow)

        // 进度条（圆角感：2dp高度增到10dp）
        val barWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10.dpToPx(ctx))
                .apply { setMargins(0, 5, 0, 0) }
            setBackgroundColor(0xFF1E2A38.toInt())
        }
        if (onTarget > 0) {
            barWrap.addView(View(ctx).apply {
                setBackgroundColor(0xFF00C853.toInt())  // 鲜绿 — 射正
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, onTarget.toFloat())
            })
        }
        if (offTarget > 0) {
            barWrap.addView(View(ctx).apply {
                setBackgroundColor(0xFF37474F.toInt())  // 深灰 — 射偏
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, offTarget.toFloat())
            })
        }
        container.addView(barWrap)
        list.addView(container)
    }

    /**
     * Section 6: 📊 传球效率条 — 带渐变感的双队对比
     */
    private fun addPassEfficiencySection(list: LinearLayout, card: LiveViewModel.LiveMatchCardData) {
        val stats = card.stats ?: return
        val ctx = requireContext()

        val homePassPct = stats.passesPct.first.removeSuffix("%").toIntOrNull() ?: return
        val awayPassPct = stats.passesPct.second.removeSuffix("%").toIntOrNull() ?: return

        list.addView(makeSectionHeader(ctx, "📊  传球效率"))

        addPassBar(list, card.match.homeTeamCn, homePassPct, 0xFFFF7043.toInt())
        addPassBar(list, card.match.awayTeamCn, awayPassPct, 0xFF42A5F5.toInt())

        list.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12)
        })
    }

    /** 单条传球进度条 */
    private fun addPassBar(list: LinearLayout, teamName: String, pct: Int, color: Int) {
        val ctx = requireContext()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 6, 8, 6)
        }

        // 队名 + 百分比数字
        val infoRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoRow.addView(TextView(ctx).apply {
            text = teamName
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        infoRow.addView(TextView(ctx).apply {
            text = "$pct%"
            setTextColor(color); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(infoRow)

        // 进度条（带底色轨道）
        val fillWidth = pct.coerceIn(0, 100)
        val barContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10.dpToPx(ctx))
                .apply { setMargins(0, 5, 0, 0) }
            setBackgroundColor(0xFF1E2A38.toInt())
        }
        if (fillWidth > 0) {
            barContainer.addView(View(ctx).apply {
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fillWidth.toFloat())
            })
        }
        if (fillWidth < 100) {
            barContainer.addView(View(ctx).apply {
                setBackgroundColor(0x00000000.toInt()) // 透明
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - fillWidth).toFloat())
            })
        }
        container.addView(barContainer)
        list.addView(container)
    }

    /**
     * 统一 Section 标题：左侧橙色竖线 + 白色文字
     * 替代旧的 "─── 标题 ───" 暗色样式
     */
    private fun makeSectionHeader(ctx: android.content.Context, title: String): LinearLayout {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 8) }
        }
        // 左侧装饰竖线
        container.addView(View(ctx).apply {
            setBackgroundColor(0xFFFF7043.toInt())
            layoutParams = LinearLayout.LayoutParams(3.dpToPx(ctx), 18.dpToPx(ctx))
                .apply { setMargins(0, 0, 10, 0) }
        })
        container.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        return container
    }

    companion object {
        /** dp → px 转换 */
        private fun Int.dpToPx(ctx: android.content.Context): Int {
            return (this * ctx.resources.displayMetrics.density).toInt()
        }
    }

    // ════════════════════════════════════════════
    //  比赛阶段感知的时钟显示
    // ════════════════════════════════════════════

    /**
     * 根据 api-sports status.short 返回适合显示的时钟文本
     *
     * 1H → "45′ 🟢 上半场"
     * HT → "⏸️ 中场休息"
     * 2H → "72′ 🟢 下半场"
     * ET → "ET 105′ 🟡 加时赛"
     * PEN → "⚫ 点球大战"
     * FT → "✅ 已结束"
     * AET → "✅ 加时结束"
     * 其他 → "xx′ 🟢 直播中"（兼容旧逻辑）
     */
    private fun formatMatchClock(phase: String, elapsed: Int): String {
        return when (phase) {
            "1H" -> "%d′ 🟢 上半场".format(elapsed.coerceIn(0, 45))
            "HT" -> "⏸️ 中场休息 (45′)"
            "2H" -> {
                val et = elapsed.coerceIn(45, 90)
                "%d′ 🟢 下半场".format(et)
            }
            "ET" -> {
                val etExtra = (elapsed - 90).coerceAtLeast(0)
                "ET %d′ 🟡 加时赛".format(etExtra)
            }
            "PEN" -> "⚫ 点球大战"
            "FT", "AET" -> "✅ 已结束"
            else -> {
                if (elapsed > 0) "%d′ 🟢 直播中".format(elapsed)
                else "🟢 直播中"
            }
        }
    }

    /** 从 ViewModel 获取某个比赛的阶段+时钟，生成显示文本 */
    private fun getPhaseAwareClockText(matchId: String, fallbackSec: Int = 0): String {
        val phase = viewModel.getMatchPhase(matchId)
        val elapsed = viewModel.getElapsedForMatch(matchId)
        if (phase.isNotBlank()) {
            return formatMatchClock(phase, elapsed)
        }
        // 无阶段信息 → 兜底
        return if (elapsed > 0) "%d′ 🟢 直播中".format(elapsed)
        else if (fallbackSec > 0) "%02d:%02d 🟢 直播中".format(fallbackSec / 60, fallbackSec % 60)
        else "🟢 直播中"
    }

    private fun updateClockDisplay(view: View, clockMap: Map<String, Int>) {
        if (currentLiveMatchIds.size <= 1) {
            // 单场：标题时钟由 startPersistentClock 的 3分钟周期管理，此处不覆盖
        } else {
            // 多场：更新每张卡片里的时钟
            val container = view.findViewById<ScrollView>(R.id.prediction_container)
            val list = container.findViewById<LinearLayout>(R.id.prediction_list)
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                val tag = child.tag as? Pair<*, *> ?: continue
                val matchId = tag.first as? String ?: continue
                val elapsed = clockMap[matchId] ?: continue
                // 更新卡片中的第一个 TextView（时钟行）
                if (child is LinearLayout && child.childCount > 0) {
                    val clockTv = child.getChildAt(0) as? TextView
                    if (clockTv != null) {
                        val phase = viewModel.getMatchPhase(matchId)
                        clockTv.text = formatMatchClock(phase, elapsed)
                    }
                }
            }
        }
    }

    /** 更新标题时钟 — 仅显示 API 时钟，不自己算（真实比赛时间与真实世界时间不同步） */
    private fun updateLiveTitleClock(view: View, matchId: String, fallbackSec: Int) {
        view.findViewById<TextView>(R.id.tv_live_title)?.let {
            val apiElapsed = viewModel.getElapsedForMatch(matchId)
            it.text = if (apiElapsed > 0) "%d′ 🟢 直播中".format(apiElapsed) else "🟢 直播中"
        }
    }

    /** 
     * 🔴 持续1秒时钟循环（比赛阶段感知 v4 — 纯API驱动）
     *
     * 设计：
     * - 完全依赖 API elapsed（比赛真实分钟，不是现实世界时钟）
     * - 每 3 分钟（180 ticks）从 API 刷新一次 elapsed
     * - 3分钟内保持当前 API 值不变（不自己计时，因为足球比赛时间≠现实时间）
     * - HT/PEN → 冻结，FT/AET → 停止
     */
    private fun startPersistentClock(view: View, match: MatchData.Match) {
        stopClock()
        isLiveMode = true

        // 当前显示的 elapsed 值（纯API驱动，不自己算）
        var displayElapsed = 0

        val runnable = object : Runnable {
            private var tickCount = 0

            override fun run() {
                if (!isLiveMode) return
                val tvTitle = view?.findViewById<TextView>(R.id.tv_live_title) ?: return
                val tvMatchInfo = view?.findViewById<TextView>(R.id.tv_match_info)

                val phase = viewModel.getMatchPhase(match.id)
                val apiElapsed = viewModel.getElapsedForMatch(match.id)

                // ── 阶段检测（HT/PEN/FT/AET 优先） ──
                if (phase.isNotBlank()) {
                    when (phase) {
                        "HT" -> {
                            val text = "⏸️ 中场休息 (45′)"
                            tvTitle.text = text; tvMatchInfo?.text = text
                            clockHandler.postDelayed(this, 5000)
                            return
                        }
                        "PEN" -> {
                            val text = "⚫ 点球大战"
                            tvTitle.text = text; tvMatchInfo?.text = text
                            clockHandler.postDelayed(this, 5000)
                            return
                        }
                        "FT", "AET" -> {
                            val text = "✅ 已结束"
                            tvTitle.text = text; tvMatchInfo?.text = text
                            stopClock(); return
                        }
                        else -> { /* 1H/2H/ET → 继续 */ }
                    }
                }

                tickCount++

                // ── 每 3 分钟（180 ticks）从 API 刷新比赛时间 ──
                if (tickCount % 180 == 0 || tickCount == 1) {
                    if (apiElapsed > 0) {
                        displayElapsed = apiElapsed
                    }
                }

                if (displayElapsed <= 0 && apiElapsed > 0) {
                    // API 在周期中间更新了 → 立即采用
                    displayElapsed = apiElapsed
                }

                val clockText = when {
                    phase == "ET" && displayElapsed > 90 -> {
                        "ET ${displayElapsed - 90}′ 🟡 加时赛"
                    }
                    displayElapsed > 0 -> {
                        "%d′ 🟢 直播中".format(displayElapsed)
                    }
                    else -> "🟢 直播中"
                }
                tvTitle.text = clockText
                tvMatchInfo?.text = clockText
                clockHandler.postDelayed(this, 1000)
            }
        }
        clockRunnable = runnable
        clockHandler.post(runnable)
    }

    private fun stopClock() {
        isLiveMode = false
        clockHandler.removeCallbacksAndMessages(null)
        clockRunnable = null
    }

    // ════════════════════════════════════════════
    //  已结束 / 回顾
    // ════════════════════════════════════════════

    private fun showRecentMatch(view: View, state: LiveUiState.RecentMatch) {
        currentLiveMatchIds = emptyList()
        val match = state.match
        view.findViewById<View>(R.id.live_content).visibility = View.VISIBLE
        view.findViewById<View>(R.id.tv_empty).visibility = View.GONE
        view.findViewById<View>(R.id.score_card).visibility = View.VISIBLE
        view.findViewById<View>(R.id.prediction_container).visibility = View.VISIBLE

        view.findViewById<TextView>(R.id.tv_live_title).text = "✅ ${viewModel.getDateLabel(match)} · ${match.round}"
        renderScoreboard(view, match)
        renderMatchInfo(view, match)

        if (match.htHome != null && match.htAway != null) {
            view.findViewById<TextView>(R.id.tv_ht_score).text = "半场 ${match.htHome}:${match.htAway}"
            view.findViewById<TextView>(R.id.tv_ht_score).visibility = View.VISIBLE
        }

        listNeedsStat = true; listNeedsLineup = true
        view.findViewById<ScrollView>(R.id.prediction_container)
            .findViewById<LinearLayout>(R.id.prediction_list).removeAllViews()

        view.findViewById<View>(R.id.score_card).setOnClickListener {
            startActivity(Intent(requireContext(), MatchDetailActivity::class.java).apply {
                putExtra("match_id", match.id)
            })
        }
    }

    // ════════════════════════════════════════════
    //  通用渲染方法
    // ════════════════════════════════════════════

    private fun renderScoreboard(view: View, match: MatchData.Match) {
        val hf = flagLoader.loadFlag(match.homeFifa); val af = flagLoader.loadFlag(match.awayFifa)
        view.findViewById<ImageView>(R.id.iv_home_flag).apply { if (hf != null) setImageDrawable(hf) }
        view.findViewById<ImageView>(R.id.iv_away_flag).apply { if (af != null) setImageDrawable(af) }
        view.findViewById<TextView>(R.id.tv_home_name).text = match.homeTeamCn
        view.findViewById<TextView>(R.id.tv_away_name).text = match.awayTeamCn
        view.findViewById<TextView>(R.id.tv_score).text = "${match.homeScore} : ${match.awayScore}"
    }

    private fun renderMatchInfo(view: View, match: MatchData.Match) {
        val venue = repo.stadiums.findStadium(match.stadium)
        val venueText = if (venue != null) "${match.stadium} · ${venue.city}, ${venue.country}（${venue.capacity}席）" else match.stadium
        // 初始时钟：由 startPersistentClock 接管，此处留空
        view.findViewById<TextView>(R.id.tv_match_info).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tv_venue_info).text = venueText
        view.findViewById<TextView>(R.id.tv_venue_info).visibility = View.VISIBLE
    }

    // ════════════════════════════════════════════
    //  预测 / Loading / Error / AllFinished
    // ════════════════════════════════════════════

    private fun showPredictions(view: View, state: LiveUiState.Predictions) {
        currentLiveMatchIds = emptyList()
        view.findViewById<View>(R.id.live_content).visibility = View.VISIBLE
        view.findViewById<View>(R.id.tv_empty).visibility = View.GONE
        view.findViewById<TextView>(R.id.tv_live_title).text = "📡 下一比赛日 · ${state.dateLabel}"
        view.findViewById<View>(R.id.score_card).visibility = View.GONE
        view.findViewById<View>(R.id.tv_match_info).visibility = View.GONE
        view.findViewById<TextView>(R.id.tv_venue_info).visibility = View.GONE
        view.findViewById<View>(R.id.tv_prediction).visibility = View.GONE

        val container = view.findViewById<ScrollView>(R.id.prediction_container)
        val list = container.findViewById<LinearLayout>(R.id.prediction_list)
        list.removeAllViews()
        container.visibility = View.VISIBLE

        list.addView(TextView(requireContext()).apply {
            text = "─── AI 赛前预测 ───"
            setTextColor(0xFF555577.toInt()); textSize = 11f
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 12)
        })
        for (match in state.matches) {
            addPredictionCard(list, match, viewModel.getPrediction(match.id))
        }
    }

    private fun addPredictionCard(list: LinearLayout, match: MatchData.Match, pred: PredictionData.Prediction?) {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 10) }
            isClickable = true; isFocusable = true
            setOnClickListener { startActivity(Intent(ctx, MatchDetailActivity::class.java).apply { putExtra("match_id", match.id) }) }
        }
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply { text = "🟡 ${match.time}"; setTextColor(0xFF4488FF.toInt()); textSize = 12f })
            addView(TextView(ctx).apply { text = "  ${match.round}"; setTextColor(0xFF8888AA.toInt()); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        })
        val vsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 8) }
        vsRow.addView(TextView(ctx).apply { text = match.homeTeamCn; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.START })
        vsRow.addView(TextView(ctx).apply { text = "VS"; setTextColor(0xFFFF6B35.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setPadding(8, 0, 8, 0) })
        vsRow.addView(TextView(ctx).apply { text = match.awayTeamCn; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.END })
        card.addView(vsRow)
        if (pred != null) {
            val hw = pred.teamA.winProb.coerceAtLeast(5).toFloat()
            val dw = pred.draw.coerceAtLeast(5).toFloat()
            val aw = pred.teamB.winProb.coerceAtLeast(5).toFloat()
            card.addView(LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 4, 0, 4)
                addView(View(ctx).apply { setBackgroundColor(0xFFFF6B35.toInt()); layoutParams = LinearLayout.LayoutParams(0, 12, hw) })
                addView(View(ctx).apply { setBackgroundColor(0xFF555577.toInt()); layoutParams = LinearLayout.LayoutParams(0, 12, dw) })
                addView(View(ctx).apply { setBackgroundColor(0xFF4488FF.toInt()); layoutParams = LinearLayout.LayoutParams(0, 12, aw) })
            })
            card.addView(LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 0)
                addView(TextView(ctx).apply { text = "${pred.teamA.winProb}%"; setTextColor(0xFFFF6B35.toInt()); textSize = 11f; typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(ctx).apply { text = "  平 ${pred.draw}%  "; setTextColor(0xFF555577.toInt()); textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.CENTER })
                addView(TextView(ctx).apply { text = "${pred.teamB.winProb}%"; setTextColor(0xFF4488FF.toInt()); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END })
            })
            if (pred.playersToWatch.isNotEmpty()) {
                card.addView(TextView(ctx).apply {
                    text = pred.playersToWatch.take(2).joinToString(" · ") { "⭐ ${it.player}" }
                    setTextColor(0xFF8888AA.toInt()); textSize = 10f; setPadding(0, 4, 0, 0)
                })
            }
        } else {
            card.addView(TextView(ctx).apply { text = "🔮 预测数据准备中"; setTextColor(0xFF555577.toInt()); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, 6, 0, 0) })
        }
        list.addView(card)
    }

    private fun showLoading(view: View) {
        currentLiveMatchIds = emptyList()
        view.findViewById<View>(R.id.live_content).visibility = View.GONE
        view.findViewById<View>(R.id.prediction_container).visibility = View.GONE
        view.findViewById<TextView>(R.id.tv_empty).apply { text = "⏳ 加载中..."; visibility = View.VISIBLE }
    }

    private fun showError(view: View, message: String) {
        currentLiveMatchIds = emptyList()
        view.findViewById<View>(R.id.live_content).visibility = View.GONE
        view.findViewById<View>(R.id.prediction_container).visibility = View.GONE
        view.findViewById<TextView>(R.id.tv_empty).apply { text = "⚠️ 数据加载失败\n$message\n\n下拉刷新重试"; visibility = View.VISIBLE }
    }

    private fun showAllFinished(view: View) {
        currentLiveMatchIds = emptyList()
        view.findViewById<View>(R.id.live_content).visibility = View.GONE
        view.findViewById<View>(R.id.prediction_container).visibility = View.GONE
        view.findViewById<TextView>(R.id.tv_empty).apply { text = "🏆 2026世界杯已全部结束！\n感谢陪伴 🎉"; visibility = View.VISIBLE }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopClock()
    }
}
