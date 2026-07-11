package worldcup.helper.ui.schedule

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import worldcup.helper.R
import worldcup.helper.data.CircleFlagLoader
import worldcup.helper.data.MatchData
import worldcup.helper.ui.match.MatchDetailActivity

class ScheduleFragment : Fragment() {

    private lateinit var viewModel: ScheduleViewModel
    private lateinit var flagLoader: CircleFlagLoader
    private lateinit var adapter: MatchAdapter

    // Views
    private var _contentView: View? = null
    private val contentView get() = _contentView!!
    private lateinit var rv: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var shimmerContainer: View
    private lateinit var emptyState: View
    private lateinit var backToTop: TextView
    private lateinit var statsSummary: TextView
    private lateinit var etSearch: EditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _contentView = inflater.inflate(R.layout.fragment_schedule, container, false)
        return contentView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[ScheduleViewModel::class.java]
        flagLoader = CircleFlagLoader(requireContext())

        initViews()
        setupRecyclerView()
        setupSearch()
        setupChips()
        setupSwipeRefresh()
        setupBackToTop()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // 从其他 Tab 切回来时刷新状态（比赛状态可能变化）
        viewModel.refreshStatus()
    }

    private fun initViews() {
        rv = contentView.findViewById(R.id.rv_matches)
        swipeRefresh = contentView.findViewById(R.id.swipe_refresh)
        shimmerContainer = contentView.findViewById(R.id.shimmer_container)
        emptyState = contentView.findViewById(R.id.empty_state)
        backToTop = contentView.findViewById(R.id.btn_back_to_top)
        statsSummary = contentView.findViewById(R.id.tv_stats_summary)
        etSearch = contentView.findViewById(R.id.et_search)

        // 今日日期
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        val todayStr = "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
        contentView.findViewById<TextView>(R.id.tv_today_label).text = "📅 $todayStr"
    }

    private fun setupRecyclerView() {
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = MatchAdapter(emptyList(), viewModel, flagLoader) { match ->
            val intent = Intent(requireContext(), MatchDetailActivity::class.java).apply {
                putExtra("match_id", match.id)
            }
            startActivity(intent)
        }
        rv.adapter = adapter
    }

    private fun setupSearch() {
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.setSearchQuery(etSearch.text.toString())
                true
            } else false
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupChips() {
        val chips = mapOf(
            R.id.chip_knockout to "knockout",
            R.id.chip_all to "all",
            R.id.chip_group_stage to "group"
        )
        chips.forEach { (id, mode) ->
            contentView.findViewById<TextView>(id).setOnClickListener {
                viewModel.setFilterMode(mode)
                chips.keys.forEach { chipId ->
                    val chip = contentView.findViewById<TextView>(chipId)
                    if (chipId == id) {
                        chip.setBackgroundResource(R.drawable.bg_chip_active)
                        chip.setTextColor(0xFFFFFFFF.toInt())
                    } else {
                        chip.setBackgroundResource(R.drawable.bg_chip_inactive)
                        chip.setTextColor(0xFF555577.toInt())
                    }
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(
            0xFF2ECC71.toInt(),
            0xFFFFD700.toInt(),
            0xFFFF4444.toInt()
        )
        swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
    }

    private fun setupBackToTop() {
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                backToTop.visibility = if (!rv.canScrollVertically(-1)) View.GONE else View.VISIBLE
            }
        })
        backToTop.setOnClickListener {
            val currentList = adapter.filteredMatches
            val targetIdx = currentList.indexOfFirst {
                viewModel.getStatus(it) == MatchData.Status.UPCOMING
            }
            rv.smoothScrollToPosition(if (targetIdx >= 0) targetIdx else 0)
        }
    }

    private fun observeViewModel() {
        // 加载状态
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            shimmerContainer.visibility = if (loading) View.VISIBLE else View.GONE
            swipeRefresh.visibility = if (loading) View.GONE else View.VISIBLE
        }

        // 下拉刷新状态
        viewModel.isRefreshing.observe(viewLifecycleOwner) { refreshing ->
            swipeRefresh.isRefreshing = refreshing
        }

        // 筛选后的比赛列表
        viewModel.filteredMatches.observe(viewLifecycleOwner) { matches ->
            adapter.updateData(matches)
            val hasQuery = viewModel.searchQuery.value?.isNotEmpty() == true
            emptyState.visibility = if (matches.isEmpty() && !hasQuery) View.GONE
                else if (matches.isEmpty()) View.VISIBLE else View.GONE
        }

        // 统计汇总
        viewModel.statsSummary.observe(viewLifecycleOwner) { stats ->
            if (stats.total > 0) {
                statsSummary.text = "共 ${stats.total} 场 · ${BuildStatusText(stats)}"
                statsSummary.visibility = View.VISIBLE
            } else {
                statsSummary.visibility = View.GONE
            }
        }
    }

    private fun BuildStatusText(stats: ScheduleViewModel.StatsSummary): String {
        val parts = mutableListOf<String>()
        if (stats.live > 0) parts.add("🟢 直播 ${stats.live} 场")
        if (stats.upcoming > 0) parts.add("🟡 未开始 ${stats.upcoming} 场")
        if (stats.finished > 0) parts.add("🔴 已完赛 ${stats.finished} 场")
        return parts.joinToString(" · ")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _contentView = null
    }
}

// ========================================================================
// MatchAdapter
// ========================================================================
class MatchAdapter(
    private var matches: List<MatchData.Match>,
    private val viewModel: ScheduleViewModel,
    private val flagLoader: CircleFlagLoader,
    private val onClick: (MatchData.Match) -> Unit
) : RecyclerView.Adapter<MatchAdapter.ViewHolder>() {

    var filteredMatches: List<MatchData.Match> = matches
        private set
    private var prevDate = ""

    fun updateData(newMatches: List<MatchData.Match>) {
        filteredMatches = newMatches
        prevDate = ""
        notifyDataSetChanged()
    }

    fun filter(mode: String) {
        // 筛选逻辑已移到 ViewModel，此方法保留兼容
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateHeader: TextView = view.findViewById(R.id.tv_date_header)
        val matchCard: View = view.findViewById(R.id.match_card)
        val statusBand: View = view.findViewById(R.id.status_band)
        val tvTime: TextView = view.findViewById(R.id.tv_match_time)
        val tvRound: TextView = view.findViewById(R.id.tv_round_label)
        val tvStatusTag: TextView = view.findViewById(R.id.tv_status_tag)
        val ivHome: ImageView = view.findViewById(R.id.iv_home_flag)
        val tvHome: TextView = view.findViewById(R.id.tv_home_name)
        val tvScore: TextView = view.findViewById(R.id.tv_score)
        val ivAway: ImageView = view.findViewById(R.id.iv_away_flag)
        val tvAway: TextView = view.findViewById(R.id.tv_away_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_match, parent, false)
        )
    }

    override fun getItemCount() = filteredMatches.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val match = filteredMatches[position]
        val dateLabel = viewModel.getDateLabel(match)

        holder.tvTime.text = match.time
        holder.tvHome.text = match.homeTeamCn
        holder.tvAway.text = match.awayTeamCn
        holder.tvRound.text = match.round

        val status = viewModel.getStatus(match)
        when (status) {
            MatchData.Status.FINISHED -> {
                holder.tvScore.text = "${match.homeScore}:${match.awayScore}"
                holder.tvScore.setTextColor(0xFFFFFFFF.toInt())
                holder.statusBand.setBackgroundColor(0xFF666666.toInt())
                holder.tvStatusTag.text = "已结束"
                holder.tvStatusTag.setTextColor(0xFF666666.toInt())
            }
            MatchData.Status.LIVE -> {
                holder.tvScore.text = "${match.homeScore}:${match.awayScore}"
                holder.tvScore.setTextColor(0xFFFF4444.toInt())
                holder.statusBand.setBackgroundColor(0xFFFF4444.toInt())
                holder.tvStatusTag.text = "直播中"
                holder.tvStatusTag.setTextColor(0xFFFF4444.toInt())
            }
            else -> {
                val isToday = try {
                    val today = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
                    val parts = match.date.split("-")
                    parts.size == 3 && parts[1].toInt() == today.get(java.util.Calendar.MONTH) + 1 &&
                        parts[2].toInt() == today.get(java.util.Calendar.DAY_OF_MONTH)
                } catch (e: Exception) { false }

                if (isToday) {
                    holder.tvScore.text = "即将"
                    holder.tvScore.setTextColor(0xFF4488FF.toInt())
                    holder.statusBand.setBackgroundColor(0xFF4488FF.toInt())
                    holder.tvStatusTag.text = "今日"
                    holder.tvStatusTag.setTextColor(0xFF4488FF.toInt())
                } else {
                    holder.tvScore.text = "—:—"
                    holder.tvScore.setTextColor(0xFF555577.toInt())
                    holder.statusBand.setBackgroundColor(0xFF2A2A4A.toInt())
                    holder.tvStatusTag.text = "未开始"
                    holder.tvStatusTag.setTextColor(0xFF555577.toInt())
                }
            }
        }

        // 日期标题（只显示一次）
        if (dateLabel != prevDate) {
            holder.dateHeader.text = "─── $dateLabel ───"
            holder.dateHeader.visibility = View.VISIBLE
            prevDate = dateLabel
        } else {
            holder.dateHeader.visibility = View.GONE
        }

        loadFlag(holder.ivHome, match.homeFifa)
        loadFlag(holder.ivAway, match.awayFifa)
        holder.matchCard.setOnClickListener { onClick(match) }
    }

    private fun loadFlag(iv: ImageView, fifaCode: String) {
        val drawable = flagLoader.loadFlag(fifaCode)
        if (drawable != null) {
            iv.setImageDrawable(drawable)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }
}
