package worldcup.helper.data.repos

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import worldcup.helper.data.MatchData
import worldcup.helper.data.PredictionData
import worldcup.helper.data.model.*
import worldcup.helper.data.toUnifiedMatch
import worldcup.helper.network.LiveApiClient

/**
 * 比赛数据 Repository
 *
 * 数据来源: football-data.org + api-sports.io + BDL GOAT + matches.json
 * API 等级: 🔴 必须API（比分/事件/统计/阵容/H2H/MVP）
 * 所属架构: MatchRepo (new_framework.md §2.1)
 *
 * 职责：
 * - 赛程列表（时间线、按日期分组、自动定位）
 * - 实时比分轮询（30秒，仅 LIVE 状态）
 * - 比赛详情（事件、统计对比、H2H、阵容、MVP）
 * - 比赛预测
 * - 供 Tab A 获取当前直播 / 下一场比赛
 * - 供 Tab C 获取赛程列表 + 比赛详情
 * - 供 TeamRepo 获取球队赛程赛果
 *
 * 数据流:
 *   Tab A/C ─→ MatchRepo ─→ API优先(30秒轮询) ─→ 本地 matches.json 兜底
 *                                                 ↗
 *                                          StadiumRepo（场馆信息）
 */
class MatchRepo(context: Context) {

    companion object {
        private const val TAG = "MatchRepo"
        const val POLL_INTERVAL_LIVE = 30_000L      // 比赛中: 30秒
        const val POLL_INTERVAL_PRE = 300_000L      // 赛前: 5分钟
        const val POLL_INTERVAL_POST = 0L           // 赛后: 不轮询
    }

    private val matchData by lazy { MatchData(context) }

    /** 供 ScheduleViewModel 使用（内部 MatchData 类型） */
    fun getRawMatchData(): MatchData = matchData
    private val predictionData by lazy { PredictionData(context) }
    private val stadiumRepo by lazy { StadiumRepo(context) }

    // ========================================================================
    // 赛程列表（本地 matches.json + API 增强）
    // ========================================================================

    /** 所有比赛（转 UnifiedMatch） */
    fun getAllMatches(): List<UnifiedMatch> {
        return matchData.matches.map { it.toUnifiedMatch() }
    }

    /** 按 matchId 查找比赛 */
    fun getMatchById(matchId: String): UnifiedMatch? {
        return matchData.matches.find { it.id == matchId }?.toUnifiedMatch()
    }

    /** 搜索比赛（按球队名） */
    fun searchMatches(query: String): List<UnifiedMatch> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return getAllMatches()
        return matchData.matches.filter {
            it.homeTeam.lowercase().contains(q) ||
            it.awayTeam.lowercase().contains(q) ||
            it.homeTeamCn.contains(q, ignoreCase = true) ||
            it.awayTeamCn.contains(q, ignoreCase = true)
        }.map { it.toUnifiedMatch() }
    }

    // ========================================================================
    // 分类赛程
    // ========================================================================

    /** 🔴 实时比赛 — 供 Tab A 使用 */
    fun getLiveMatches(): List<UnifiedMatch> {
        return matchData.matches
            .filter { matchData.getStatus(it) == MatchData.Status.LIVE }
            .map { it.toUnifiedMatch() }
    }

    /** 有直播吗？ */
    fun hasLiveMatch(): Boolean = getLiveMatches().isNotEmpty()

    /** 已结束比赛 */
    fun getFinishedMatches(): List<UnifiedMatch> {
        return matchData.matches
            .filter { matchData.getStatus(it) == MatchData.Status.FINISHED }
            .sortedByDescending { it.datetime }
            .map { it.toUnifiedMatch() }
    }

    /** 即将进行的比赛 */
    fun getUpcomingMatches(): List<UnifiedMatch> {
        return matchData.matches
            .filter { matchData.getStatus(it) == MatchData.Status.UPCOMING }
            .sortedBy { it.datetime }
            .map { it.toUnifiedMatch() }
    }

    /** 下一个比赛日的所有比赛 — 供 Tab A 无直播时使用 */
    fun getNextMatchday(): List<UnifiedMatch> {
        val upcoming = getUpcomingMatches()
        val nextDate = upcoming.firstOrNull()?.datetime?.substring(0, 10) ?: return emptyList()
        return upcoming.filter { it.datetime.startsWith(nextDate) }
    }

    /** 按日期分组赛程 — 供 Tab C 使用 */
    fun getMatchesGroupedByDate(): Map<String, List<UnifiedMatch>> {
        return getAllMatches().groupBy { it.datetime.substring(0, 10) }
    }

    // ========================================================================
    // 球队赛程 — 供 TeamRepo 使用
    // ========================================================================

    /** 某球队的所有比赛 */
    fun getTeamMatches(teamFifaCode: String): List<UnifiedMatch> {
        return matchData.matches
            .filter { it.homeFifa == teamFifaCode || it.awayFifa == teamFifaCode }
            .sortedBy { it.datetime }
            .map { it.toUnifiedMatch() }
    }

    // ========================================================================
    // 比赛预测
    // ========================================================================

    /** 获取比赛预测 */
    fun getPrediction(matchId: String): MatchPrediction? {
        val id = matchId.toIntOrNull() ?: return null
        val pred = predictionData.getPrediction(id) ?: return null
        return MatchPrediction(
            matchId = matchId,
            homeWinProb = pred.teamA.winProb,
            drawProb = pred.draw,
            awayWinProb = pred.teamB.winProb,
            predictedScore = pred.predictedScore,
            confidence = pred.confidence,
            keyFactors = pred.keyFactors,
            analysis = pred.analysis,
            playersToWatch = pred.playersToWatch.map {
                PlayerWatch(team = it.team, player = it.player, reason = it.reason)
            },
            source = if (pred.homeElo > 0) "monte_carlo" else "local"
        )
    }

    // ========================================================================
    // API 优先 — 实时比分
    // ========================================================================

    /**
     * 从 football-data API 拉取实时比分
     * 🔴 必须API: 比分每30秒变化一次，本地预设0-0不准确
     */
    suspend fun fetchLiveScoresFromApi(): List<LiveScore> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.footballData.getMatches(status = "LIVE")
            }
            resp.matches.map { match ->
                LiveScore(
                    matchId = match.id.toString(),
                    homeScore = match.score?.fullTime?.home ?: 0,
                    awayScore = match.score?.fullTime?.away ?: 0,
                    status = match.status ?: "SCHEDULED",
                    clock = null,
                    homeScorers = null,
                    awayScorers = null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "live scores API failed", e)
            emptyList()
        }
    }

    /**
     * 从 football-data API 获取多场比赛比分
     * 用于批量更新比赛比分+状态
     */
    suspend fun refreshAllScoresFromApi(): List<LiveScore> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.footballData.getMatches()
            }

            resp.matches.map { match ->
                LiveScore(
                    matchId = match.id.toString(),
                    homeScore = match.score?.fullTime?.home ?: 0,
                    awayScore = match.score?.fullTime?.away ?: 0,
                    status = match.status ?: "SCHEDULED",
                    clock = null,
                    homeScorers = null,
                    awayScorers = null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "refresh all scores API failed", e)
            emptyList()
        }
    }

    // ========================================================================
    // 🔴 必须API：核心方法 — 获取所有比赛的真实比分+状态（覆盖本地0-0）
    // ========================================================================

    /**
     * 从 football-data API 拉取全部比赛的实时比分和状态
     *
     * 这是 Tab A/C 的 API 优先入口：
     *   1. 调 football-data /matches → 获取全部104场的真实比分
     *   2. 返回 Map<matchId, ScoreInfo> 供 UI 覆盖本地 matches.json 的 0-0
     *   3. API 失败时返回空 Map，UI 降级到本地数据
     *
     * 调用方（ScheduleViewModel / LiveViewModel）:
     *   val apiScores = matchRepo.fetchApiScoreMap()
     *   if (apiScores.isNotEmpty()) { 使用 API 数据 }
     *   else { 使用本地 matchData.matches }
     */
    suspend fun fetchApiScoreMap(): Map<String, ScoreInfo> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.footballData.getMatches()
            }
            resp.matches.mapNotNull { match ->
                val id = match.id.toString()
                val home = match.score?.fullTime?.home
                val away = match.score?.fullTime?.away
                // 只返回有比分的比赛（API 有比分才有意义覆盖本地）
                if (home != null || away != null) {
                    id to ScoreInfo(
                        homeScore = home ?: 0,
                        awayScore = away ?: 0,
                        status = match.status ?: "SCHEDULED"
                    )
                } else null
            }.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "fetchApiScoreMap failed, fallback to local", e)
            emptyMap()
        }
    }

    data class ScoreInfo(
        val homeScore: Int,
        val awayScore: Int,
        val status: String
    )

    // ========================================================================
    // API 优先 — 比赛事件
    // ========================================================================

    /**
     * 从 api-sports 拉取比赛事件（进球/红黄牌/VAR）
     * 🔴 必须API: 赛中实时出现，本地只有赛后数据
     */
    suspend fun fetchEventsFromApi(fixtureId: Int): List<MatchEvent> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.apiSports.getFixtureEvents(fixtureId)
            }
            resp.response.map { evt ->
                MatchEvent(
                    elapsed = evt.time?.elapsed ?: 0,
                    type = when (evt.type?.lowercase()) {
                        "goal" -> "goal"
                        "card" -> when (evt.detail?.lowercase()) {
                            "red card" -> "red_card"
                            "yellow card" -> "yellow_card"
                            "second yellow card" -> "second_yellow"
                            else -> evt.type?.lowercase() ?: "event"
                        }
                        "subst" -> "substitution"
                        "var" -> "var"
                        else -> evt.type?.lowercase() ?: "event"
                    },
                    playerName = evt.player?.name ?: "",
                    assistName = evt.assist?.name ?: "",
                    teamName = evt.team?.name ?: "",
                    detail = evt.detail ?: "",
                    score = evt.time?.elapsed?.toString() ?: ""
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "events API failed for fixture=$fixtureId", e)
            emptyList()
        }
    }

    // ========================================================================
    // API 优先 — 比赛统计对比
    // ========================================================================

    /**
     * 从 api-sports 拉取比赛统计对比（射门/控球/角球等20项）
     * 🔴 必须API: 赛后才有真实数据
     */
    suspend fun fetchStatisticsFromApi(fixtureId: Int): List<TeamStatComparison> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.apiSports.getFixtureStatistics(fixtureId)
            }
            resp.response.map { teamStat ->
                TeamStatComparison(
                    teamName = teamStat.team?.name ?: "",
                    statistics = teamStat.statistics?.map { stat ->
                        StatItem(
                            key = stat.type ?: "",
                            value = stat.value?.toString() ?: "0"
                        )
                    } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "statistics API failed for fixture=$fixtureId", e)
            emptyList()
        }
    }

    // ========================================================================
    // API 优先 — 阵容+阵型
    // ========================================================================

    /**
     * 从 api-sports 拉取阵容+阵型
     * 🔴 必须API: 赛前30分钟才确定
     */
    suspend fun fetchLineupsFromApi(fixtureId: Int): List<TeamLineup> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.apiSports.getFixtureLineups(fixtureId)
            }
            resp.response.map { lineup ->
                TeamLineup(
                    teamName = lineup.team?.name ?: "",
                    formation = lineup.formation ?: "",
                    players = lineup.startXI?.map { p ->
                        LineupPlayer(
                            name = p.player?.name ?: "",
                            number = 0,
                            position = ""
                        )
                    } ?: emptyList(),
                    substitutes = lineup.substitutes?.map { p ->
                        LineupPlayer(
                            name = p.player?.name ?: "",
                            number = 0,
                            position = ""
                        )
                    } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "lineups API failed for fixture=$fixtureId", e)
            emptyList()
        }
    }

    // ========================================================================
    // API 优先 — 历史交锋 H2H
    // ========================================================================

    /**
     * 从 api-sports 拉取历史交锋记录
     * 🔴 必须API: API 才有历史交锋数据
     */
    suspend fun fetchH2hFromApi(homeTeamId: Int, awayTeamId: Int): HeadToHead? {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.apiSports.getHeadToHead("$homeTeamId-$awayTeamId")
            }
            val allMatches = resp.response
            if (allMatches.isEmpty()) return null

            var homeWins = 0; var draws = 0; var awayWins = 0
            val history = allMatches.map { m ->
                val hScore = m.goals?.home ?: 0
                val aScore = m.goals?.away ?: 0
                when {
                    hScore > aScore -> homeWins++
                    hScore == aScore -> draws++
                    else -> awayWins++
                }
                H2hMatch(
                    date = m.fixture?.date ?: "",
                    homeTeam = m.teams?.home?.name ?: "",
                    awayTeam = m.teams?.away?.name ?: "",
                    homeScore = hScore,
                    awayScore = aScore
                )
            }

            HeadToHead(
                totalMatches = allMatches.size,
                homeWins = homeWins,
                draws = draws,
                awayWins = awayWins,
                matches = history.take(10)  // 最近10场
            )
        } catch (e: Exception) {
            Log.w(TAG, "H2H API failed for ${homeTeamId}-${awayTeamId}", e)
            null
        }
    }

    // ========================================================================
    // API 优先 — 全场最佳 MVP
    // ========================================================================

    /**
     * 从 BDL 拉取全场最佳球员
     * 🔴 必须API: 赛后才有数据
     */
    suspend fun fetchBestPlayerFromApi(bdlMatchId: Int): BestPlayerResult? {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.bdlApi.getMatchBestPlayers(listOf(bdlMatchId))
            }
            val best = resp.data.firstOrNull() ?: return null
            BestPlayerResult(
                playerId = best.player_id ?: 0,
                teamId = best.team_id ?: 0,
                rating = best.rating?.toDoubleOrNull() ?: 0.0,
                isManOfMatch = true,
                reason = best.reason ?: ""
            )
        } catch (e: Exception) {
            Log.w(TAG, "best player API failed for match=$bdlMatchId", e)
            null
        }
    }

    // ========================================================================
    // 轮询配置
    // ========================================================================

    /**
     * 根据比赛状态返回推荐轮询间隔
     * 用于 Tab A / Tab C 的轮询调度
     */
    fun getPollingInterval(status: String): Long {
        return when (status.uppercase()) {
            "LIVE", "IN_PLAY", "PAUSED" -> POLL_INTERVAL_LIVE
            "SCHEDULED", "TIMED" -> POLL_INTERVAL_PRE
            "FINISHED", "COMPLETED" -> POLL_INTERVAL_POST
            else -> POLL_INTERVAL_PRE
        }
    }
}
