package worldcup.helper.ui.live

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import worldcup.helper.data.MatchData
import worldcup.helper.data.PredictionData
import worldcup.helper.data.repos.SharedRepository
import worldcup.helper.network.LiveApiClient
import worldcup.helper.network.BdlLineupPlayer

/**
 * Tab 1 (实时赛况) 的 ViewModel — v3.0
 *
 * ⭐ 架构升级:
 *   - 🔴 api-sports fixtures?live=all → liveClockMap（实时比赛分钟）
 *   - 🔴 football-data matches → apiScoreMap（比分+状态）
 *   - 🔴 多场直播同时支持
 *   - 🔴 fixture ID 通过 live fixtures API 自动映射
 *   - 🟡 api-sports lineups + 本地 lineup 双源降级
 *
 * 数据流：
 *   init: football-data apiScoreMap + api-sports liveClockMap + 本地 enriched
 *   30s轮询: score + clock + events + stats + lineups + best_players
 */
class LiveViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LiveViewModel"
    }

    // ========================================================================
    // 数据源
    // ========================================================================

    val matchData: MatchData by lazy { MatchData(getApplication()) }
    val predictionData: PredictionData by lazy { PredictionData(getApplication()) }
    private val repo: SharedRepository by lazy { SharedRepository.getInstance(getApplication()) }

    // ========================================================================
    // 主要内容状态
    // ========================================================================

    private val _uiState = MutableLiveData<LiveUiState>(LiveUiState.Loading)
    val uiState: LiveData<LiveUiState> = _uiState

    // ========================================================================
    // 🔴 实时时钟 + 比赛阶段 — 来自 api-sports fixtures?live=all
    // ========================================================================

    private val _liveClockMap = MutableLiveData<Map<String, Int>>(emptyMap())
    val liveClockMap: LiveData<Map<String, Int>> = _liveClockMap
    private val liveClockCache = mutableMapOf<String, Int>()

    /** 比赛阶段缓存（status.short: 1H/HT/2H/ET/PEN/FT/AET） */
    private val liveStatusCache = mutableMapOf<String, String>()

    /** 获取比赛阶段短码 */
    fun getMatchPhase(matchId: String): String = liveStatusCache[matchId] ?: ""

    /** 判断比赛是否在真正进行中（不含中场休息和结束） */
    fun isMatchActivelyPlaying(matchId: String): Boolean {
        val phase = liveStatusCache[matchId] ?: return false
        return phase in listOf("1H", "2H", "ET")
    }

    // ========================================================================
    // 每场比赛独立数据（keyed by local match.id）
    // ========================================================================

    private val matchDataMap = mutableMapOf<String, LiveMatchCardData>()

    data class LiveMatchCardData(
        val match: MatchData.Match,
        var fixtureId: Int? = null,
        var bdlMatchId: Int? = null,
        var homeScore: Int = 0,
        var awayScore: Int = 0,
        var events: EventsInfo? = null,
        var stats: TeamStatsData? = null,
        var lineup: BdlLineupData? = null,
        var bestPlayers: List<BdlBestPlayerData> = emptyList(),
        var lineupsLoaded: Boolean = false,
        var playerLineup: List<PlayerMatchLineup> = emptyList()
    )

    private val _liveCards = MutableLiveData<List<LiveMatchCardData>>(emptyList())
    val liveCards: LiveData<List<LiveMatchCardData>> = _liveCards

    // ========================================================================
    // 保留旧 LiveData 兼容（主场比赛的第一场）
    // ========================================================================

    private val _liveScore = MutableLiveData<LiveScoreUpdate?>(null)
    val liveScore: LiveData<LiveScoreUpdate?> = _liveScore
    private val _eventsInfo = MutableLiveData<EventsInfo?>(null)
    val eventsInfo: LiveData<EventsInfo?> = _eventsInfo
    private val _teamStats = MutableLiveData<TeamStatsData?>(null)
    val teamStats: LiveData<TeamStatsData?> = _teamStats
    private val _bdlLineupData = MutableLiveData<BdlLineupData?>(null)
    val bdlLineupData: LiveData<BdlLineupData?> = _bdlLineupData
    private val _bdlBestPlayers = MutableLiveData<List<BdlBestPlayerData>>(emptyList())
    val bdlBestPlayers: LiveData<List<BdlBestPlayerData>> = _bdlBestPlayers

    // ========================================================================
    // BDL 映射缓存
    // ========================================================================

    private var bdlTeamIdMap: Map<String, Int> = emptyMap()
    private var bdlTeamIdMapLoaded = false

    // ========================================================================
    // 🔴 fixture_id_map.json （api-sports fixture ID → local match ID）
    // ========================================================================

    private var fixtureIdToLocalMap: Map<Int, String> = emptyMap()
    private var localIdToFixtureMap: Map<String, Int> = emptyMap()

    private fun loadFixtureIdToLocalMap() {
        if (fixtureIdToLocalMap.isNotEmpty()) return
        try {
            val ctx = getApplication<Application>()
            val json = ctx.assets.open("fixture_id_map.json").bufferedReader().use { it.readText() }
            val root = com.google.gson.Gson().fromJson(json, Map::class.java)
            val mapping = root["mapping"] as? Map<String, Any> ?: return
            val result = mutableMapOf<Int, String>()
            val reverse = mutableMapOf<String, Int>()
            for ((localId, apiIdObj) in mapping) {
                val apiId = when (apiIdObj) {
                    is Double -> apiIdObj.toInt()
                    is Int -> apiIdObj
                    is Long -> apiIdObj.toInt()
                    else -> continue
                }
                if (apiId > 0) {
                    result[apiId] = localId
                    reverse[localId] = apiId
                }
            }
            fixtureIdToLocalMap = result
            localIdToFixtureMap = reverse
            Log.d(TAG, "fixture_id_map loaded: ${result.size} entries")
        } catch (e: Exception) {
            Log.w(TAG, "fixture_id_map load failed", e)
        }
    }

    // ========================================================================
    // 轮询任务
    // ========================================================================

    private var pollingJob: Job? = null

    // ========================================================================
    // 英文名 → 中文名映射表
    // ========================================================================

    private val chineseNameMap: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        try {
            val ctx = getApplication<Application>()
            val json = ctx.assets.open("players_2026.json").bufferedReader().use { it.readText() }
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            val teams = root["teams"] as? List<Map<String, Any>> ?: return@lazy map
            for (team in teams) {
                val players = team["players"] as? List<Map<String, Any>> ?: continue
                for (p in players) {
                    val eng = (p["name"] as? String)?.lowercase()?.trim() ?: continue
                    val cn = p["nameCn"] as? String ?: continue
                    if (cn.isBlank()) continue
                    map[eng] = cn
                    val lastName = eng.split(" ").lastOrNull()
                    if (lastName != null && lastName.length > 2) map[lastName] = cn
                }
            }
        } catch (_: Exception) { }
        map
    }

    private fun toChinese(engName: String?): String {
        val key = engName?.lowercase()?.trim() ?: return ""
        chineseNameMap[key]?.let { return it }
        val lastName = key.split(" ").lastOrNull()
        if (lastName != null && lastName.length > 2) {
            chineseNameMap[lastName]?.let { return it }
            for ((k, v) in chineseNameMap) {
                if (k.contains(lastName) || lastName.contains(k)) return v
            }
        }
        return engName ?: ""
    }

    /** 球队名模糊匹配（用于 api-sports / football-data team name → 本地球队） */
    private fun teamNameMatch(apiName: String, localTeam: String, localTeamCn: String): Boolean {
        val a = apiName.lowercase().trim()
        val l = localTeam.lowercase().trim()
        val c = localTeamCn.lowercase().trim()
        return a.contains(l) || l.contains(a) || a.contains(c) || c.contains(a)
    }

    // ========================================================================
    // 公共方法
    // ========================================================================

    fun load() {
        viewModelScope.launch {
            try {
                _uiState.value = computeUiState()
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                _uiState.value = LiveUiState.Error("数据加载失败: ${e.message}")
            }
        }
    }

    /**
     * 🔴 API 优先：核心判断逻辑
     * 1. football-data API → apiScoreMap（比分+状态覆盖本地）
     * 2. api-sports fixtures?live=all → liveClockMap（实时比赛分钟）
     * 3. 决定显示：多直播 / 单直播 / 回顾 / 预测 / 全部结束
     */
    private suspend fun computeUiState(): LiveUiState {
        val todayStr = matchData.getTodayDateStr()
        val localMatches = matchData.matches

        // ── Step 1+2 (并行): football-data 比分 + api-sports 直播时钟 ──
        val (apiScores, liveFixtures) = coroutineScope {
            val scoresDeferred = async { repo.matches.fetchApiScoreMap() }
            val fixturesDeferred = async { fetchLiveFixturesFromApi() }
            Pair(scoresDeferred.await(), fixturesDeferred.await())
        }
        val enrichedMatches = if (apiScores.isNotEmpty()) {
            Log.d(TAG, "API scores loaded, enriching ${apiScores.size} matches")
            localMatches.map { m ->
                val score = apiScores[m.id]
                if (score != null) m.copy(
                    homeScore = score.homeScore,
                    awayScore = score.awayScore,
                    status = score.status
                ) else m
            }
        } else {
            Log.d(TAG, "API scores empty, using local data")
            localMatches
        }
        val liveFixturesById = liveFixtures.associateBy { it.fixture.id }

        // 🔴 加载 fixture_id_map.json 实现精准 ID 匹配
        loadFixtureIdToLocalMap()

        // 将 api-sports live fixtures 映射到本地比赛
        val liveClockMapLocal = mutableMapOf<String, Int>()
        val liveMatchIds = mutableSetOf<String>()

        for (apiFixture in liveFixtures) {
            val apiHome = apiFixture.teams.home.name
            val apiAway = apiFixture.teams.away.name
            val apiGoals = apiFixture.goals
            val elapsedMin = apiFixture.status?.elapsed ?: 0

            // Priority 1: fixture_id_map.json 精准查找
            var matched = fixtureIdToLocalMap[apiFixture.fixture.id]?.let { fid ->
                enrichedMatches.firstOrNull { it.id == fid }
            }
            // Priority 2: 球队名模糊匹配（兜底）
            if (matched == null) {
                matched = enrichedMatches.firstOrNull { m ->
                    teamNameMatch(apiHome, m.homeTeam, m.homeTeamCn) &&
                    teamNameMatch(apiAway, m.awayTeam, m.awayTeamCn)
                }
                if (matched != null) {
                    Log.d(TAG, "Fixture ${apiFixture.fixture.id} matched by team name: ${matched.id}")
                }
            } else {
                Log.d(TAG, "Fixture ${apiFixture.fixture.id} matched by ID map: ${matched.id}")
            }
            if (matched != null) {
                liveClockMapLocal[matched.id] = elapsedMin
                liveMatchIds.add(matched.id)
                // 用 api-sports 比分覆盖
                if (apiGoals != null) {
                    val idx = enrichedMatches.indexOf(matched)
                    if (idx >= 0) {
                        (enrichedMatches as MutableList)[idx] = matched.copy(
                            homeScore = apiGoals.home ?: matched.homeScore,
                            awayScore = apiGoals.away ?: matched.awayScore
                        )
                    }
                }
            }
        }
        liveClockCache.clear()
        liveClockCache.putAll(liveClockMapLocal)
        _liveClockMap.postValue(liveClockMapLocal.toMap())

        // 同步比赛阶段
        liveStatusCache.clear()
        for (apiFixture in liveFixtures) {
            // 用同样逻辑匹配 localId
            val apiFixtureId = apiFixture.fixture.id
            val localId = fixtureIdToLocalMap[apiFixtureId]
                ?: enrichedMatches.firstOrNull { m ->
                    teamNameMatch(apiFixture.teams.home.name, m.homeTeam, m.homeTeamCn) &&
                    teamNameMatch(apiFixture.teams.away.name, m.awayTeam, m.awayTeamCn)
                }?.id
            if (localId != null && !apiFixture.status?.short.isNullOrBlank()) {
                liveStatusCache[localId] = apiFixture.status!!.short
            }
        }

        Log.d(TAG, "Live fixtures from API: ${liveFixtures.size}, matched: ${liveMatchIds.size}")

        // ── Step 3: 本地状态检测（补充 API 未覆盖的） ──
        val sorted = enrichedMatches.sortedBy { matchData.getSortKey(it) }

        // 合并：API 检测到的 + 本地检测到的 LIVE
        val localLive = sorted.filter { matchData.getStatus(it) == MatchData.Status.LIVE }
        val allLiveIds = liveMatchIds + localLive.map { it.id }.toSet()
        val allLive = sorted.filter { it.id in allLiveIds }

        if (allLive.isNotEmpty()) {
            // 初始化每场比赛的数据
            matchDataMap.clear()
            val cards = allLive.map { m ->
                val card = LiveMatchCardData(
                    match = m,
                    homeScore = m.homeScore,
                    awayScore = m.awayScore
                )
                matchDataMap[m.id] = card
                card
            }
            _liveCards.postValue(cards)
            updateLegacyLiveData(cards.first())

            startPolling(allLive)

            // 🔴 修复：单场返回 LiveMatch，多场返回 MultiLiveMatches
            if (allLive.size == 1) {
                val m = allLive.first()
                val kickoffMs = parseKickoffMs(m)
                // 优先使用API时钟，否则本地计算
                val elapsedSec = liveClockCache[m.id]?.let { it * 60 } ?: getLocalElapsedSec(m)
                return LiveUiState.LiveMatch(m, elapsedSec, kickoffMs)
            } else {
                return LiveUiState.MultiLiveMatches(allLive, liveClockMapLocal)
            }
        }

        // ── 无直播 → 降级 ──
        stopPolling()

        // 今天已结束的比赛
        val todayFinished = sorted.filter {
            matchData.getStatus(it) == MatchData.Status.FINISHED && it.date == todayStr
        }
        if (todayFinished.isNotEmpty()) {
            val m = todayFinished.last()
            loadLocalEvents(m)
            return LiveUiState.RecentMatch(m)
        }

        // 近3天已结束比赛
        val recentDays = (1..3).firstOrNull { daysAgo ->
            val date = matchData.getDateDaysAgo(daysAgo)
            sorted.any { it.date == date && matchData.getStatus(it) == MatchData.Status.FINISHED }
        }
        if (recentDays != null) {
            val date = matchData.getDateDaysAgo(recentDays)
            val recentMatch = sorted.filter {
                it.date == date && matchData.getStatus(it) == MatchData.Status.FINISHED
            }.lastOrNull()
            if (recentMatch != null) {
                loadLocalEvents(recentMatch)
                return LiveUiState.RecentMatch(recentMatch)
            }
        }

        // 今天即将开始
        val todayUpcoming = sorted.filter {
            matchData.getStatus(it) == MatchData.Status.UPCOMING && it.date == todayStr
        }
        if (todayUpcoming.isNotEmpty()) {
            return LiveUiState.Predictions(matchData.getTodayDateLabel(), todayUpcoming)
        }

        // 下一比赛日
        val upcoming = sorted.filter { matchData.getStatus(it) == MatchData.Status.UPCOMING }
            .sortedBy { it.datetime }
        val next = upcoming.firstOrNull()
        if (next == null) return LiveUiState.AllFinished

        val nd = next.date
        return LiveUiState.Predictions(matchData.getDateLabel(next), upcoming.filter { it.date == nd })
    }

    /**
     * 🔴 从 api-sports 获取所有直播比赛（含实时分钟和比分）
     */
    private suspend fun fetchLiveFixturesFromApi(): List<worldcup.helper.network.ApiSportsFixture> {
        return try {
            val resp = LiveApiClient.apiSports.getFixtures(live = "all")
            resp.response
        } catch (e: Exception) {
            Log.w(TAG, "live fixtures API failed", e)
            emptyList()
        }
    }

    fun getPrediction(matchId: String): PredictionData.Prediction? =
        predictionData.getPrediction(matchId.toIntOrNull() ?: 0)

    fun getDateLabel(match: MatchData.Match): String = matchData.getDateLabel(match)

    fun getElapsedForMatch(matchId: String): Int = liveClockCache[matchId] ?: 0

    // ========================================================================
    // 轮询控制 — 支持多场直播
    // ========================================================================

    private fun startPolling(liveMatches: List<MatchData.Match>) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {

            // 首次：加载 BDL team ID 映射
            if (!bdlTeamIdMapLoaded) {
                try {
                    val teamsResp = LiveApiClient.bdlApi.getTeams()
                    bdlTeamIdMap = teamsResp.data.associate { it.name.lowercase() to it.id }
                    bdlTeamIdMapLoaded = true
                } catch (_: Exception) { }
            }

            // 首次：为每场直播比赛查找 fixture ID + BDL match ID
            for (card in matchDataMap.values.toList()) {
                val match = card.match

                // 查找 api-sports fixture ID（优先 fixture_id_map，次选 API 搜索）
                val fixtureId = if (card.fixtureId == null) {
                    // Priority 1: localIdToFixtureMap 缓存
                    localIdToFixtureMap[match.id]
                        ?: try {
                        val resp = LiveApiClient.apiSports.getFixtures(
                            date = match.date, season = 2026, league = 1
                        )
                        resp.response.firstOrNull { f ->
                            teamNameMatch(f.teams.home.name, match.homeTeam, match.homeTeamCn) &&
                            teamNameMatch(f.teams.away.name, match.awayTeam, match.awayTeamCn)
                        }?.fixture?.id
                    } catch (_: Exception) { null }
                } else card.fixtureId

                // 查找 BDL match ID
                val bdlMatchId = if (card.bdlMatchId == null) {
                    try {
                        val hi = bdlTeamIdMap[match.homeTeam.lowercase()]
                        val ai = bdlTeamIdMap[match.awayTeam.lowercase()]
                        val ids = listOfNotNull(hi, ai)
                        if (ids.isNotEmpty()) {
                            val matchResp = LiveApiClient.bdlApi.getMatches(teamIds = ids)
                            matchResp.data.firstOrNull()?.id
                        } else null
                    } catch (_: Exception) { null }
                } else card.bdlMatchId

                card.fixtureId = fixtureId
                card.bdlMatchId = bdlMatchId
                Log.d(TAG, "Match ${match.id}: fixtureId=$fixtureId, bdlMatchId=$bdlMatchId")

                // 首次：拉取阵容
                if (bdlMatchId != null && !card.lineupsLoaded) {
                    val lineup = fetchBdlLineup(bdlMatchId)
                    if (lineup != null) {
                        card.lineup = lineup
                        card.lineupsLoaded = true
                    }
                }
            }

            _liveCards.postValue(matchDataMap.values.toList())
            updateLegacyLiveData(matchDataMap.values.firstOrNull())

            // ── 30秒循环轮询 ──
            while (isActive) {
                delay(30_000)
                try {
                    pollAll()
                } catch (e: Exception) {
                    Log.w(TAG, "poll iteration failed", e)
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** 更新时钟、比分和比赛阶段 */
    private fun updateClockAndScore(localId: String, elapsed: Int, fixture: worldcup.helper.network.ApiSportsFixture) {
        liveClockCache[localId] = elapsed
        // 捕获比赛阶段 status.short
        val phase = fixture.status?.short
        if (!phase.isNullOrBlank()) {
            liveStatusCache[localId] = phase
        }
        val card = matchDataMap[localId] ?: return
        val g = fixture.goals
        if (g != null) {
            card.homeScore = g.home ?: card.homeScore
            card.awayScore = g.away ?: card.awayScore
        }
    }

    /**
     * 一次轮询：更新所有直播比赛的全部数据
     * 1. live clock （api-sports）
     * 2. live score （football-data）
     * 3. events + stats + ratings + lineups + best_players (per match)
     */
    private suspend fun pollAll() {
        // ── 0. 确保 fixture_id_map 已加载 ──
        loadFixtureIdToLocalMap()

        // ── 1. 刷新实时时钟 ──
        val liveFixtures = fetchLiveFixturesFromApi()
        for (fixture in liveFixtures) {
            val elapsed = fixture.status?.elapsed ?: 0
            val apiFixtureId = fixture.fixture.id
            // Priority 1: fixture_id_map.json 精准查找
            val localId = fixtureIdToLocalMap[apiFixtureId]
            if (localId != null && matchDataMap.containsKey(localId)) {
                updateClockAndScore(localId, elapsed, fixture)
                continue
            }
            // Priority 2: 球队名模糊匹配（兜底）
            for ((lid, card) in matchDataMap) {
                val m = card.match
                if (teamNameMatch(fixture.teams.home.name, m.homeTeam, m.homeTeamCn) &&
                    teamNameMatch(fixture.teams.away.name, m.awayTeam, m.awayTeamCn)) {
                    updateClockAndScore(lid, elapsed, fixture)
                    break
                }
            }
        }
        _liveClockMap.postValue(liveClockCache.toMap())

        // ── 2. football-data 比分（备用覆盖） ──
        val fdScores = repo.matches.fetchLiveScoresFromApi()
        for (fdScore in fdScores) {
            val card = matchDataMap[fdScore.matchId]
            if (card != null) {
                card.homeScore = fdScore.homeScore ?: card.homeScore
                card.awayScore = fdScore.awayScore ?: card.awayScore
            }
        }

        // ── 3. 每场比赛独立数据（并行） ──
        coroutineScope {
            matchDataMap.values.map { card ->
                async {
                    pollMatchEvents(card)
                    pollMatchStats(card)
                    fetchPlayerLineupFromApi(card)
                    if (card.bdlMatchId != null) {
                        pollBdlLineup(card)
                        pollBdlBestPlayer(card)
                    }
                }
            }
        }

        _liveCards.postValue(matchDataMap.values.toList())
        updateLegacyLiveData(matchDataMap.values.firstOrNull())
    }

    /** 更新旧 LiveData（兼容 Fragment 现有观察者） */
    private fun updateLegacyLiveData(card: LiveMatchCardData?) {
        if (card == null) return
        _liveScore.postValue(LiveScoreUpdate(card.homeScore, card.awayScore, card.match.status))
        if (card.events != null) _eventsInfo.postValue(card.events)
        if (card.stats != null) _teamStats.postValue(card.stats)
        if (card.lineup != null) _bdlLineupData.postValue(card.lineup)
        if (card.bestPlayers.isNotEmpty()) _bdlBestPlayers.postValue(card.bestPlayers)
    }

    // ========================================================================
    // 单场比赛数据轮询
    // ========================================================================

    /** 比赛事件 — API优先，无API时降级本地 */
    private suspend fun pollMatchEvents(card: LiveMatchCardData) {
        val fixtureId = card.fixtureId
        if (fixtureId != null) {
            try {
                val events = repo.matches.fetchEventsFromApi(fixtureId)
                if (events.isNotEmpty()) {
                    val recent = events.takeLast(8)
                    val sb = StringBuilder()
                    var homeShots = 0; var awayShots = 0
                    // 分组显示：主队事件、客队事件
                    val homeEvents = recent.filter {
                        teamNameMatch(it.teamName, card.match.homeTeam, card.match.homeTeamCn)
                    }
                    val awayEvents = recent.filter {
                        teamNameMatch(it.teamName, card.match.awayTeam, card.match.awayTeamCn)
                    }
                    // 按时间合并排序
                    val sortedEvents = (homeEvents + awayEvents).sortedBy { it.elapsed }
                    for (e in sortedEvents) {
                        val icon = when (e.type) {
                            "goal" -> "⚽"; "yellow_card", "second_yellow" -> "🟨"
                            "red_card" -> "🟥"; "substitution" -> "🔄"; "var" -> "📺"; else -> "▸"
                        }
                        val pName = toChinese(e.playerName)
                        val teamSide = if (teamNameMatch(e.teamName, card.match.homeTeam, card.match.homeTeamCn)) "[H]" else "[A]"
                        val line = when (e.type) {
                            "substitution" -> {
                                val onName = pName
                                val offName = toChinese(e.assistName)
                                if (offName.isNotEmpty()) "${e.elapsed}' $teamSide $icon ⬆️$onName ⬇️$offName"
                                else "${e.elapsed}' $teamSide $icon ⬆️$onName"
                            }
                            "goal" -> {
                                val detail = when {
                                    e.detail.contains("Penalty", ignoreCase = true) -> " (点球)"
                                    e.detail.contains("Own", ignoreCase = true) -> " (乌龙)"
                                    else -> ""
                                }
                                val assist = e.assistName.takeIf { it.isNotBlank() }?.let { toChinese(it) }
                                val goalLine = "${e.elapsed}' $teamSide $icon $pName$detail"
                                if (!assist.isNullOrBlank()) "$goalLine  🅰️$assist" else goalLine
                            }
                            else -> {
                                val detail = when (e.type) {
                                    "yellow_card" -> " 🟨"
                                    "red_card" -> " 🟥"
                                    "second_yellow" -> " 🟨🟨"
                                    else -> ""
                                }
                                "${e.elapsed}' $teamSide $icon $pName$detail"
                            }
                        }
                        sb.appendLine(line)
                        if (e.type == "goal") {
                            if (teamNameMatch(e.teamName, card.match.homeTeam, card.match.homeTeamCn)) homeShots++
                            else awayShots++
                        }
                    }
                    card.events = EventsInfo(sb.toString(), homeShots, awayShots)
                    return
                }
            } catch (_: Exception) { }
        }
        // 🔴 降级：本地事件（无论是否有 fixtureId，都尝试）
        loadLocalEvents(card.match)
    }

    /** 球队统计对比 */
    private suspend fun pollMatchStats(card: LiveMatchCardData) {
        val fixtureId = card.fixtureId ?: return
        val stats = repo.matches.fetchStatisticsFromApi(fixtureId)
        if (stats.size < 2) return

        // 判断哪队是主队
        val homeIdx = if (teamNameMatch(stats[0].teamName, card.match.homeTeam, card.match.homeTeamCn)) 0 else 1
        val awayIdx = 1 - homeIdx
        val home = stats[homeIdx]; val away = stats[awayIdx]

        fun getVal(key: String): Pair<String, String> {
            val v1 = home.statistics.firstOrNull { it.key.lowercase() == key.lowercase() }?.value ?: "—"
            val v2 = away.statistics.firstOrNull { it.key.lowercase() == key.lowercase() }?.value ?: "—"
            return v1 to v2
        }
        card.stats = TeamStatsData(
            possession = getVal("Ball Possession"),
            shotsOnTarget = getVal("Shots on Goal"),
            totalShots = getVal("Total Shots"),
            corners = getVal("Corner Kicks"),
            fouls = getVal("Fouls"),
            passesPct = getVal("Passes %")
        )
    }

    /** BDL 阵容（含本地兜底） */
    private suspend fun pollBdlLineup(card: LiveMatchCardData) {
        val matchId = card.bdlMatchId ?: return
        try {
            val resp = LiveApiClient.bdlApi.getMatchLineups(matchIds = listOf(matchId))
            if (resp.data.isNotEmpty()) {
                val homePlayers = resp.data.filter { it.is_home == true && it.is_starter == true }
                val awayPlayers = resp.data.filter { it.is_home != true && it.is_starter == true }
                val homeF = homePlayers.firstOrNull()?.formation
                val awayF = awayPlayers.firstOrNull()?.formation

                fun toLineupData(list: List<BdlLineupPlayer>): List<LineupPlayer> =
                    list.map { LineupPlayer(
                        name = toChinese(it.player_name),
                        number = it.shirt_number ?: 0,
                        position = it.position ?: ""
                    ) }

                card.lineup = BdlLineupData(
                    home = toLineupData(homePlayers),
                    away = toLineupData(awayPlayers),
                    homeFormation = homeF,
                    awayFormation = awayF,
                    homeTeamCn = card.match.homeTeamCn,
                    awayTeamCn = card.match.awayTeamCn
                )
                card.lineupsLoaded = true
                return
            }
        } catch (_: Exception) { }

        // ── 兜底：BDL 无数据时，从本地球员数据取前 11 人 ──
        loadLocalLineupFallback(card)
    }

    /** 从 players_2026.json 构建本地阵容兜底 */
    private fun loadLocalLineupFallback(card: LiveMatchCardData) {
        try {
            val ctx = getApplication<Application>()
            val json = ctx.assets.open("players_2026.json").bufferedReader().use { it.readText() }
            val gson = com.google.gson.Gson()
            val raw = gson.fromJson(json, Map::class.java)
            val root = raw as? Map<String, Any> ?: return
            val teams = root["teams"] as? List<Map<String, Any>> ?: return

            var homeLineup = emptyList<LineupPlayer>()
            var awayLineup = emptyList<LineupPlayer>()

            for (t in teams) {
                val tName = t["name"] as? String ?: ""
                val tCode = t["countryCode"] as? String ?: ""
                val players = t["players"] as? List<Map<String, Any>> ?: continue

                val isHome = tName == card.match.homeTeam || tCode == card.match.homeFifa ||
                    card.match.homeTeamCn.contains(tName, ignoreCase = true) ||
                    tName.contains(card.match.homeTeam, ignoreCase = true) ||
                    tName.contains(card.match.homeTeamCn, ignoreCase = true)
                val isAway = !isHome && (tName == card.match.awayTeam || tCode == card.match.awayFifa ||
                    card.match.awayTeamCn.contains(tName, ignoreCase = true) ||
                    tName.contains(card.match.awayTeam, ignoreCase = true) ||
                    tName.contains(card.match.awayTeamCn, ignoreCase = true))

                val top11 = players.take(11).map { p ->
                    val num = when (val n = p["jerseyNumber"]) { is Double -> n.toInt(); is Long -> n.toInt(); is Int -> n; else -> 0 }
                    val name = p["nameCn"] as? String ?: (p["name"] as? String ?: "球员")
                    LineupPlayer(name = name, number = num, position = "")
                }

                if (isHome) homeLineup = top11
                if (isAway) awayLineup = top11
            }

            if (homeLineup.isNotEmpty() || awayLineup.isNotEmpty()) {
                card.lineup = BdlLineupData(
                    home = homeLineup, away = awayLineup,
                    homeFormation = "", awayFormation = "",
                    homeTeamCn = card.match.homeTeamCn,
                    awayTeamCn = card.match.awayTeamCn
                )
            }
        } catch (_: Exception) { }
    }

    /** BDL 全场最佳 — 兜底使用 api-sports 球员评分数据 */
    private suspend fun pollBdlBestPlayer(card: LiveMatchCardData) {
        // 方案 A: 从 api-sports fixtures/players 按评分排序选取 Top 3
        val apiPlayers = card.playerLineup
        if (apiPlayers.size >= 2) {
            val sorted = apiPlayers
                .filter { it.rating > 0 }
                .sortedByDescending { it.rating }
                .take(3)
            if (sorted.isNotEmpty()) {
                card.bestPlayers = sorted.map {
                    BdlBestPlayerData(
                        name = it.nameCn.ifEmpty { it.nameEn },
                        rating = "%.1f".format(it.rating),
                        reason = if (it.goals > 0) "⚽进球 ${it.goals}" else if (it.assists > 0) "🅰️助攻 ${it.assists}" else "评分 ${"%.1f".format(it.rating)}"
                    )
                }
                return
            }
        }

        // 方案 B: BDL API
        val matchId = card.bdlMatchId ?: return
        try {
            val resp = LiveApiClient.bdlApi.getMatchBestPlayers(matchIds = listOf(matchId))
            card.bestPlayers = resp.data.map { BdlBestPlayerData(
                name = toChinese(it.player_name).ifEmpty { it.player_name ?: "" },
                rating = it.rating,
                reason = it.reason ?: ""
            ) }.filter { it.name.isNotBlank() }
        } catch (_: Exception) { }
    }

    /** 从 api-sports fixtures/players 拉取每名球员上场数据（含头像、评分、统计） */
    private suspend fun fetchPlayerLineupFromApi(card: LiveMatchCardData) {
        val fixtureId = card.fixtureId ?: return
        try {
            val resp = LiveApiClient.apiSports.getFixturePlayers(fixtureId)
            val teamStats = resp.response
            if (teamStats.size < 2) return

            val bdlStarters = mutableSetOf<String>()
            card.lineup?.home?.forEach { bdlStarters.add(it.name) }
            card.lineup?.away?.forEach { bdlStarters.add(it.name) }

            val result = mutableListOf<PlayerMatchLineup>()

            for (ts in teamStats) {
                val teamName = ts.team?.name ?: continue
                // 判断主客队
                val isHome = teamNameMatch(teamName, card.match.homeTeam, card.match.homeTeamCn)
                val isAway = !isHome && teamNameMatch(teamName, card.match.awayTeam, card.match.awayTeamCn)
                if (!isHome && !isAway) continue

                for (p in ts.players) {
                    val player = p.player ?: continue
                    val stat = p.statistics.firstOrNull() ?: continue
                    val games = stat.games ?: continue
                    val mins = games.minutes ?: 0
                    if (mins <= 0) continue // 没上场的跳过

                    val rating = games.rating?.toDoubleOrNull() ?: 0.0
                    val enName = player.name ?: ""

                    // 判断是否首发：通过 name 匹配 BDL 首发名单
                    val cnName = toChinese(enName)
                    val isStarter = bdlStarters.contains(cnName) || bdlStarters.contains(enName)

                    result.add(PlayerMatchLineup(
                        nameCn = cnName,
                        nameEn = enName,
                        photoUrl = player.photo ?: "",
                        number = games.number ?: 0,
                        isStarter = isStarter || mins >= 80,
                        isHome = isHome,
                        rating = rating,
                        minutes = mins,
                        goals = stat.goals?.total ?: 0,
                        assists = stat.goals?.assists ?: 0,
                        yellowCards = stat.cards?.yellow ?: 0,
                        redCards = stat.cards?.red ?: 0,
                        shots = stat.shots?.total ?: 0,
                        passes = stat.passes?.total ?: 0,
                        position = games.position ?: "",
                        apiSportsId = player.id ?: 0
                    ))
                }
            }

            if (result.isNotEmpty()) {
                card.playerLineup = result
            }
        } catch (_: Exception) { }
    }

    /** 首次获取 BDL 阵容（非轮询） */
    private suspend fun fetchBdlLineup(matchId: Int): BdlLineupData? {
        return try {
            val resp = LiveApiClient.bdlApi.getMatchLineups(matchIds = listOf(matchId))
            if (resp.data.isEmpty()) return null
            val homePlayers = resp.data.filter { it.is_home == true && it.is_starter == true }
            val awayPlayers = resp.data.filter { it.is_home != true && it.is_starter == true }
            BdlLineupData(
                home = homePlayers.map { LineupPlayer(name = toChinese(it.player_name), number = it.shirt_number ?: 0, position = it.position ?: "") },
                away = awayPlayers.map { LineupPlayer(name = toChinese(it.player_name), number = it.shirt_number ?: 0, position = it.position ?: "") },
                homeFormation = homePlayers.firstOrNull()?.formation,
                awayFormation = awayPlayers.firstOrNull()?.formation,
                homeTeamCn = "",
                awayTeamCn = ""
            )
        } catch (_: Exception) { null }
    }

    // ========================================================================
    // 本地降级方法
    // ========================================================================

    private fun loadLocalEvents(match: MatchData.Match) {
        try {
            val ctx = getApplication<Application>()
            val json = ctx.assets.open("match_events.json").bufferedReader().use { it.readText() }
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            val allEvents: Map<String, Any> = gson.fromJson(json, type)
            val ed = allEvents[match.id] as? Map<String, Any> ?: return
            val rawEvents = ed["events"] as? List<Map<String, Any>> ?: return

            val recent = rawEvents.takeLast(5)
            val sb = StringBuilder()
            for (evt in recent) {
                val evtType = evt["type"] as? String ?: ""
                val icon = when (evtType) { "goal" -> "⚽"; "yellow" -> "🟨"; "red" -> "🟥"; "sub" -> "🔄"; else -> "▸" }
                val minute = (evt["minute"] as? Double)?.toInt() ?: 0
                val number = (evt["number"] as? Double)?.toInt() ?: 0
                val playerCn = evt["playerCn"] as? String ?: (evt["player"] as? String ?: "")
                val teamCn = evt["teamCn"] as? String ?: ""
                val assistStr = (evt["assistCn"] as? String)?.let { " (助:$it)" } ?: ""
                sb.appendLine("$minute' $icon #$number $playerCn ($teamCn)$assistStr")
            }
            val sd = ed["stats"] as? Map<String, Any>
            val hs = ((sd?.get("home") as? Map<*,*>)?.get("shots") as? Double)?.toInt() ?: 0
            val aws = ((sd?.get("away") as? Map<*,*>)?.get("shots") as? Double)?.toInt() ?: 0
            _eventsInfo.value = EventsInfo(sb.toString(), hs, aws)
        } catch (_: Exception) { }
    }

    /** 本地计算已比赛分钟（兜底） */
    private fun getLocalElapsedSec(match: MatchData.Match): Int {
        return try {
            val nowMs = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            cal.set(java.util.Calendar.YEAR, match.datetime.substring(0, 4).toInt())
            cal.set(java.util.Calendar.MONTH, match.datetime.substring(5, 7).toInt() - 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, match.datetime.substring(8, 10).toInt())
            cal.set(java.util.Calendar.HOUR_OF_DAY, match.datetime.substring(11, 13).toInt())
            cal.set(java.util.Calendar.MINUTE, match.datetime.substring(14, 16).toInt())
            cal.set(java.util.Calendar.SECOND, 0)
            ((nowMs - cal.timeInMillis) / 1000).toInt().coerceIn(0, 7200)
        } catch (_: Exception) { 0 }
    }

    /** 从 liveClockMap 获取开球时间戳（用于本地1秒时钟滴答兜底） */
    private fun parseKickoffMs(match: MatchData.Match): Long {
        return try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            cal.set(java.util.Calendar.YEAR, match.datetime.substring(0, 4).toInt())
            cal.set(java.util.Calendar.MONTH, match.datetime.substring(5, 7).toInt() - 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, match.datetime.substring(8, 10).toInt())
            cal.set(java.util.Calendar.HOUR_OF_DAY, match.datetime.substring(11, 13).toInt())
            cal.set(java.util.Calendar.MINUTE, match.datetime.substring(14, 16).toInt())
            cal.set(java.util.Calendar.SECOND, 0)
            cal.timeInMillis
        } catch (_: Exception) { System.currentTimeMillis() }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

// ========================================================================
// 数据类
// ========================================================================

data class LiveScoreUpdate(val homeScore: Int, val awayScore: Int, val status: String)
data class EventsInfo(val eventsText: String, val shotsHome: Int, val shotsAway: Int)
data class LineupInfo(val homePlayers: List<String>, val awayPlayers: List<String>, val homeTeamCn: String, val awayTeamCn: String)

data class PlayerRatingData(
    val name: String, val teamName: String, val number: Int, val rating: Float?,
    val minutes: Int, val goals: Int, val assists: Int, val shots: Int, val passes: Int, val tackles: Int
)

data class TeamStatsData(
    val possession: Pair<String, String>, val shotsOnTarget: Pair<String, String>,
    val totalShots: Pair<String, String>, val corners: Pair<String, String>,
    val fouls: Pair<String, String>, val passesPct: Pair<String, String>
)

/** BDL 阵型数据 */
data class BdlLineupData(
    val home: List<LineupPlayer>,
    val away: List<LineupPlayer>,
    val homeFormation: String?,
    val awayFormation: String?,
    val homeTeamCn: String = "",
    val awayTeamCn: String = ""
)

/** BDL 全场最佳 */
data class BdlBestPlayerData(
    val name: String,
    val rating: String?,
    val reason: String
)

/** 单名球员上场数据（头像+中文名+实时统计） */
data class PlayerMatchLineup(
    val nameCn: String,
    val nameEn: String,
    val photoUrl: String,
    val number: Int,
    val isStarter: Boolean,
    val isHome: Boolean,
    val rating: Double,
    val minutes: Int,
    val goals: Int,
    val assists: Int,
    val yellowCards: Int,
    val redCards: Int,
    val shots: Int,
    val passes: Int,
    val position: String,
    val apiSportsId: Int
)

sealed class LiveUiState {
    object Loading : LiveUiState()
    data class LiveMatch(val match: MatchData.Match, val elapsedSec: Int, val kickoffMs: Long) : LiveUiState()
    data class MultiLiveMatches(
        val matches: List<MatchData.Match>,
        val clockMap: Map<String, Int>
    ) : LiveUiState()
    data class RecentMatch(val match: MatchData.Match) : LiveUiState()
    data class Predictions(val dateLabel: String, val matches: List<MatchData.Match>) : LiveUiState()
    object AllFinished : LiveUiState()
    data class Error(val message: String) : LiveUiState()
}
