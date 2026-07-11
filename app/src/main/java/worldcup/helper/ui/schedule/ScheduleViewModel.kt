package worldcup.helper.ui.schedule

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import worldcup.helper.data.MatchData
import worldcup.helper.data.repos.SharedRepository

/**
 * Tab 3 — 赛程查询 ViewModel
 *
 * 🔴 必须API：打开 Tab C 时先调 football-data API 获取所有比赛的实时比分和状态
 *  API 成功 → 用 API 比分覆盖本地 matches.json 的 0-0
 *  API 失败 → 降级到本地 matches.json
 *
 * 数据流:
 *   loadMatches() → MatchRepo.fetchApiScoreMap() → football-data API
 *       │                                            ↓ 失败
 *       ↓                                     matchData.matches（本地兜底）
 *   覆盖比分 → 显示
 */
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ScheduleVM"
    }

    private val repo = SharedRepository.getInstance(application)
    private val matchData = repo.matches.getRawMatchData()

    /** 原始全量比赛 */
    private val _allMatches = MutableLiveData<List<MatchData.Match>>()
    val allMatches: LiveData<List<MatchData.Match>> = _allMatches

    /** 当前筛选后的比赛 */
    private val _filteredMatches = MutableLiveData<List<MatchData.Match>>()
    val filteredMatches: LiveData<List<MatchData.Match>> = _filteredMatches

    /** 当前筛选模式 */
    private val _filterMode = MutableLiveData("knockout")
    val filterMode: LiveData<String> = _filterMode

    /** 搜索关键词 */
    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    /** 是否正在加载 */
    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    /** 是否正在刷新（下拉刷新） */
    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    /** 统计汇总：总场次 / 已完赛 / 直播中 */
    data class StatsSummary(val total: Int, val finished: Int, val live: Int, val upcoming: Int)
    private val _statsSummary = MutableLiveData(StatsSummary(0, 0, 0, 0))
    val statsSummary: LiveData<StatsSummary> = _statsSummary

    /** 直播轮询 Job */
    private var pollingJob: Job? = null

    init {
        loadMatches()
    }

    /**
     * 🔴 API 优先：加载赛程数据
     *
     * 1. 先调 football-data API 获取全部真实比分
     * 2. 如果成功，覆盖本地 matches.json 的 0-0
     * 3. 如果失败，直接使用本地数据
     */
    private fun loadMatches() {
        viewModelScope.launch {
            _isLoading.value = true

            // 第1步：本地数据（保证秒出）
            val localMatches = matchData.matches

            // 第2步：API 优先 — 获取实时比分
            val apiScores = repo.matches.fetchApiScoreMap()

            // 第3步：API 比分覆盖本地 0-0
            val matches = if (apiScores.isNotEmpty()) {
                Log.d(TAG, "API scores loaded: ${apiScores.size} matches")
                localMatches.map { m ->
                    val score = apiScores[m.id]
                    if (score != null) {
                        m.copy(
                            homeScore = score.homeScore,
                            awayScore = score.awayScore,
                            status = score.status
                        )
                    } else m
                }
            } else {
                Log.d(TAG, "API scores empty, using local data")
                localMatches
            }

            _allMatches.value = matches
            applyFilterAndSearch()
            updateStats(matches)
            _isLoading.value = false
            startPollingIfNeeded()
        }
    }

    /** 切换筛选模式 */
    fun setFilterMode(mode: String) {
        if (_filterMode.value == mode) return
        _filterMode.value = mode
        applyFilterAndSearch()
    }

    /** 设置搜索关键词 */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilterAndSearch()
    }

    /**
     * 🔴 API 优先：下拉刷新
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            delay(300)

            val localMatches = matchData.matches
            val apiScores = repo.matches.fetchApiScoreMap()

            val matches = if (apiScores.isNotEmpty()) {
                localMatches.map { m ->
                    val score = apiScores[m.id]
                    if (score != null) m.copy(
                        homeScore = score.homeScore,
                        awayScore = score.awayScore,
                        status = score.status
                    ) else m
                }
            } else {
                localMatches
            }

            _allMatches.value = matches
            applyFilterAndSearch()
            updateStats(matches)
            _isRefreshing.value = false
            startPollingIfNeeded()
        }
    }

    /**
     * 🔴 API 优先：onResume 时刷新（不显示动画）
     */
    fun refreshStatus() {
        viewModelScope.launch {
            val localMatches = matchData.matches
            val apiScores = repo.matches.fetchApiScoreMap()

            val matches = if (apiScores.isNotEmpty()) {
                localMatches.map { m ->
                    val score = apiScores[m.id]
                    if (score != null) m.copy(
                        homeScore = score.homeScore,
                        awayScore = score.awayScore,
                        status = score.status
                    ) else m
                }
            } else {
                localMatches
            }

            _allMatches.value = matches
            applyFilterAndSearch()
            updateStats(matches)
            startPollingIfNeeded()
        }
    }

    /** 获取比赛状态 */
    fun getStatus(match: MatchData.Match): MatchData.Status = matchData.getStatus(match)

    /** 获取日期标签 */
    fun getDateLabel(match: MatchData.Match): String = matchData.getDateLabel(match)

    /** 获取排序 key */
    fun getSortKey(match: MatchData.Match): String = matchData.getSortKey(match)

    /** 获取球队中文名对照 */
    fun getChineseName(teamName: String): String = MatchData.getChineseName(teamName)

    // ========================================================================
    // 内部方法
    // ========================================================================

    private fun applyFilterAndSearch() {
        val all = _allMatches.value ?: return
        val query = _searchQuery.value?.trim()?.lowercase() ?: ""
        val mode = _filterMode.value ?: "all"

        val filtered = all
            .filter { match ->
                if (query.isNotEmpty()) {
                    match.homeTeamCn.contains(query, ignoreCase = true) ||
                    match.homeTeam.contains(query, ignoreCase = true) ||
                    match.awayTeamCn.contains(query, ignoreCase = true) ||
                    match.awayTeam.contains(query, ignoreCase = true) ||
                    MatchData.getChineseName(match.homeTeam).contains(query, ignoreCase = true) ||
                    MatchData.getChineseName(match.awayTeam).contains(query, ignoreCase = true)
                } else true
            }
            .filter { match ->
                when (mode) {
                    "group" -> match.isGroupStage
                    "knockout" -> !match.isGroupStage
                    else -> true
                }
            }
            .sortedBy { matchData.getSortKey(it) }

        _filteredMatches.value = filtered
    }

    private fun updateStats(matches: List<MatchData.Match>) {
        val total = matches.size
        val live = matches.count { matchData.getStatus(it) == MatchData.Status.LIVE }
        val finished = matches.count { matchData.getStatus(it) == MatchData.Status.FINISHED }
        val upcoming = total - live - finished
        _statsSummary.value = StatsSummary(total, finished, live, upcoming)
    }

    /** 对 LIVE 比赛启动轮询 */
    private fun startPollingIfNeeded() {
        pollingJob?.cancel()
        val liveCount = _allMatches.value?.count {
            matchData.getStatus(it) == MatchData.Status.LIVE
        } ?: 0
        if (liveCount == 0) return

        pollingJob = viewModelScope.launch {
            while (true) {
                delay(30_000L)
                // 轮询时也走 API 优先
                val localMatches = matchData.matches
                val apiScores = repo.matches.fetchApiScoreMap()
                val matches = if (apiScores.isNotEmpty()) {
                    localMatches.map { m ->
                        val score = apiScores[m.id]
                        if (score != null) m.copy(
                            homeScore = score.homeScore,
                            awayScore = score.awayScore,
                            status = score.status
                        ) else m
                    }
                } else {
                    localMatches
                }
                _allMatches.value = matches
                applyFilterAndSearch()
                updateStats(matches)
                val stillLive = matches.any { matchData.getStatus(it) == MatchData.Status.LIVE }
                if (!stillLive) {
                    Log.d(TAG, "直播结束，停止轮询")
                    break
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
