package worldcup.helper.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import worldcup.helper.network.FootballStandingRow
import worldcup.helper.network.FootballScorer
import worldcup.helper.network.LiveApiClient
import worldcup.helper.data.model.*
import java.net.UnknownHostException

/**
 * 数据仓储层 (requirement_list.md §D.2)
 * 统一管理本地 Assets 数据 + API 数据聚合
 * API-first: 优先调在线 API，失败后降级到本地
 */
class DataRepository(private val context: Context) {

    private val matchData by lazy { MatchData(context) }
    private val playerDatabase by lazy { PlayerDatabase(context) }
    private val predictionData by lazy { PredictionData(context) }

    // ========================================================================
    // 比赛数据
    // ========================================================================

    fun getMatches(): List<UnifiedMatch> {
        return matchData.matches.map { it.toUnifiedMatch() }
    }

    fun getMatchById(matchId: String): UnifiedMatch? {
        return matchData.matches.find { it.id == matchId }?.toUnifiedMatch()
    }

    fun getLiveMatches(): List<UnifiedMatch> {
        return matchData.matches
            .filter { matchData.getStatus(it) == MatchData.Status.LIVE }
            .map { it.toUnifiedMatch() }
    }

    fun getFinishedMatches(): List<UnifiedMatch> {
        return matchData.matches
            .filter { matchData.getStatus(it) == MatchData.Status.FINISHED }
            .sortedByDescending { it.datetime }
            .map { it.toUnifiedMatch() }
    }

    fun getUpcomingMatches(): List<UnifiedMatch> {
        return matchData.matches
            .filter { matchData.getStatus(it) == MatchData.Status.UPCOMING }
            .sortedBy { it.datetime }
            .map { it.toUnifiedMatch() }
    }

    fun getNextMatchday(): List<UnifiedMatch> {
        val upcoming = getUpcomingMatches()
        val nextDate = upcoming.firstOrNull()?.datetime?.substring(0, 10) ?: return emptyList()
        return upcoming.filter { it.datetime.startsWith(nextDate) }
    }

    // ========================================================================
    // 积分榜 — API优先
    // ========================================================================

    /**
     * 获取小组积分榜（三源降级）
     * Tier 1: football-data /standings
     * Tier 2: BDL /group_standings
     * Tier 3: 本地 matches.json 计算
     */
    suspend fun getStandingsWithApi(): Map<String, List<StandingRow>> {
        // Tier 1: football-data
        try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.footballData.getStandings()
            }
            val apiData = mutableMapOf<String, List<StandingRow>>()
            for (table in resp.standings) {
                val groupName = table.group?.removePrefix("GROUP_") ?: continue
                if (table.type != "TOTAL") continue
                val rows = table.table.map { it.toStandingRow() }
                apiData[groupName] = rows
            }
            if (apiData.isNotEmpty()) return apiData
        } catch (e: Exception) {
            android.util.Log.w("DataRepository", "football-data积分榜失败", e)
        }

        // Tier 2: BDL GOAT group_standings
        try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.bdlApi.getGroupStandings()
            }
            if (resp.data.isNotEmpty()) {
                val grouped = resp.data.groupBy { it.group?.name?.removePrefix("Group ") ?: "?" }
                return grouped.mapValues { (_, standings) ->
                    standings.mapIndexed { i, s ->
                        val teamName = s.team?.name ?: ""
                        val cn = MatchData.getChineseName(teamName)
                        StandingRow(
                            rank = s.position ?: (i + 1),
                            teamName = teamName,
                            teamNameCn = cn,
                            fifaCode = s.team?.abbreviation ?: "",
                            played = s.played ?: 0,
                            wins = s.won ?: 0,
                            draws = s.drawn ?: 0,
                            losses = s.lost ?: 0,
                            goalsFor = s.goals_for ?: 0,
                            goalsAgainst = s.goals_against ?: 0,
                            goalDiff = s.goal_difference ?: (s.goals_for ?: 0) - (s.goals_against ?: 0),
                            points = s.points ?: 0,
                            isPromoted = (s.position ?: 99) <= 2
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DataRepository", "BDL积分榜失败", e)
        }

        // Tier 3: 本地计算
        return getStandingsLocal()
    }

    /** 本地赛果计算积分榜（降级方案） */
    private data class StandingAccumulator(
        val name: String, val nameCn: String, val fifaCode: String,
        var played: Int = 0, var wins: Int = 0, var draws: Int = 0, var losses: Int = 0,
        var goalsFor: Int = 0, var goalsAgainst: Int = 0, var points: Int = 0
    ) {
        val goalDiff get() = goalsFor - goalsAgainst
        fun toRow(rank: Int, promoted: Boolean) = StandingRow(
            rank, name, nameCn, fifaCode, played, wins, draws, losses,
            goalsFor, goalsAgainst, goalDiff, points, promoted
        )
    }

    fun getStandingsLocal(): Map<String, List<StandingRow>> {
        val groups = matchData.matches
            .filter { it.isGroupStage }
            .groupBy { it.group ?: "?" }

        return groups.mapValues { (_, matches) ->
            val teamStats = mutableMapOf<String, StandingAccumulator>()

            for (m in matches) {
                if (matchData.getStatus(m) != MatchData.Status.FINISHED) continue
                val home = m.homeTeam
                val away = m.awayTeam
                teamStats.putIfAbsent(home, StandingAccumulator(home, m.homeTeamCn, m.homeFifa))
                teamStats.putIfAbsent(away, StandingAccumulator(away, m.awayTeamCn, m.awayFifa))

                val h = teamStats[home]!!
                val a = teamStats[away]!!
                h.played++; a.played++
                h.goalsFor += m.homeScore; h.goalsAgainst += m.awayScore
                a.goalsFor += m.awayScore; a.goalsAgainst += m.homeScore
                when {
                    m.homeScore > m.awayScore -> { h.wins++; a.losses++; h.points += 3 }
                    m.homeScore == m.awayScore -> { h.draws++; a.draws++; h.points++; a.points++ }
                    else -> { h.losses++; a.wins++; a.points += 3 }
                }
            }

            teamStats.values
                .sortedWith(compareByDescending<StandingAccumulator> { it.points }
                    .thenByDescending { it.goalDiff }
                    .thenByDescending { it.goalsFor })
                .mapIndexed { i, s -> s.toRow(i + 1, i < 2) }
        }
    }

    // ========================================================================
    // 射手榜 — API优先
    // ========================================================================

    /**
     * 获取射手榜（API优先）
     * 1. 调 football-data /scorers
     * 2. 通过 person_id_map 关联中文名
     * 3. 失败 → 本地 match_events.json 计算
     */
    suspend fun getScorersWithApi(): List<ScorerRow> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.footballData.getScorers(limit = 50)
            }
            if (resp.scorers.isEmpty()) getScorersLocal()
            else resp.scorers.map { it.toScorerRow() }
        } catch (e: Exception) {
            android.util.Log.w("DataRepository", "Scorers API失败, 降级本地", e)
            getScorersLocal()
        }
    }

    /** 本地 match_events.json 计算射手榜（降级方案） */
    fun getScorersLocal(): List<ScorerRow> {
        val events = loadMatchEvents()
        val goalMap = mutableMapOf<String, Int>()
        val teamMap = mutableMapOf<String, String>()

        for ((_, ed) in events) {
            val rawEvents = ed["events"] as? List<Map<String, Any>> ?: continue
            for (evt in rawEvents) {
                if (evt["type"] as? String != "goal") continue
                val player = evt["player"] as? String ?: ""
                val team = evt["team"] as? String ?: ""
                if (player.isBlank()) continue
                goalMap[player] = (goalMap[player] ?: 0) + 1
                teamMap[player] = teamMap.getOrDefault(player, team)
            }
        }

        return goalMap.entries
            .sortedByDescending { it.value }
            .take(30)
            .mapIndexed { i, entry ->
                ScorerRow(i + 1, entry.key, teamMap[entry.key] ?: "", entry.value, 0)
            }
    }

    // ========================================================================
    // 赛季排名 — api-sports Pro 优先（助攻/评分/牌）
    // ========================================================================

    /** 48 支球队的 api-sports 队号映射 */
    private val apiSportsTeamIds: Map<String, Int> by lazy {
        mapOf(
            "Mexico" to 16, "South Africa" to 1531, "South Korea" to 17, "Korea Republic" to 17,
            "Czech Republic" to 770, "Czechia" to 770, "Canada" to 5529,
            "Bosnia and Herzegovina" to 1113, "Bosnia-Herzegovina" to 1113, "Bosnia" to 1113,
            "Qatar" to 1569, "Switzerland" to 15, "Brazil" to 6, "Morocco" to 31,
            "Haiti" to 2386, "Scotland" to 1108, "United States" to 2384, "USA" to 2384,
            "Paraguay" to 2380, "Australia" to 20, "Turkey" to 777, "Turkiye" to 777,
            "Germany" to 25, "Curaçao" to 5530, "Curacao" to 5530,
            "Ivory Coast" to 1501, "Ecuador" to 2382, "Netherlands" to 1118, "Japan" to 12,
            "Sweden" to 5, "Tunisia" to 28, "Belgium" to 1, "Egypt" to 32, "Iran" to 22,
            "New Zealand" to 4673, "Spain" to 9, "Cape Verde" to 1533, "Cape Verde Islands" to 1533,
            "Saudi Arabia" to 23, "Uruguay" to 7, "France" to 2, "Senegal" to 13,
            "Iraq" to 1567, "Norway" to 1090, "Argentina" to 26, "Algeria" to 1532,
            "Austria" to 775, "Jordan" to 1548, "Portugal" to 27,
            "DR Congo" to 3860, "Democratic Republic of the Congo" to 3860, "Congo DR" to 3860,
            "Uzbekistan" to 1568, "Colombia" to 8, "England" to 10, "Croatia" to 3,
            "Ghana" to 1504, "Panama" to 11
        )
    }

    data class SeasonRanking(
        val playerName: String,
        val playerNameCn: String,
        val teamName: String,
        val assists: Int = 0,
        val rating: Double = 0.0,
        val cards: Int = 0,          // 黄+红
        val appearances: Int = 0
    )

    /**
     * 从 api-sports Pro 获取全赛季排名（助攻/评分/牌）
     * 遍历48支球队，调 players?team=X&season=2026&league=1
     * Pro 7500次/天，48次完全够用
     */
    suspend fun getSeasonRankings(): List<SeasonRanking> {
        // 获取已完赛的球队列表（只调有比赛数据的球队）
        val finishedTeams = matchData.matches
            .filter { matchData.getStatus(it) == MatchData.Status.FINISHED }
            .flatMap { listOf(it.homeTeam, it.awayTeam) }
            .distinct()

        if (finishedTeams.isEmpty()) return emptyList()

        // 并行调所有球队的赛季数据
        return coroutineScope {
            val jobs = finishedTeams.mapNotNull { teamEn ->
                val teamId = apiSportsTeamIds[teamEn] ?: return@mapNotNull null
                async {
                    try {
                        withContext(Dispatchers.IO) {
                            LiveApiClient.apiSports.getPlayersByTeam(teamId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("DataRepository", "赛季排名API失败: $teamEn", e)
                        null
                    }
                }
            }

            val result = mutableListOf<SeasonRanking>()

            for (job in jobs) {
                val resp = job.await() ?: continue
                val teamName = resp.response.firstOrNull()?.statistics?.firstOrNull()?.team?.name
                    ?: continue

                for (player in resp.response) {
                    val stats = player.statistics.firstOrNull() ?: continue
                    val games = stats.games
                    val goals = stats.goals
                    val cards = stats.cards

                    val appearences = games?.appearences ?: 0
                    if (appearences == 0) continue

                    val ratingStr = games?.rating
                    val rating = try { ratingStr?.toDouble() ?: 0.0 } catch (_: Exception) { 0.0 }
                    val assists = goals?.assists ?: 0
                    val yellows = cards?.yellow ?: 0
                    val reds = cards?.red ?: 0

                    val name = player.player?.name ?: ""
                    val nameCn = resolveChineseNameByApiSportsId(player.player?.id ?: 0) ?: name

                    result.add(SeasonRanking(
                        playerName = name,
                        playerNameCn = nameCn,
                        teamName = teamName,
                        assists = assists,
                        rating = rating,
                        cards = yellows + reds,
                        appearances = appearences
                    ))
                }
            }

            result
        }
    }

    /** 通过 api_sports_id 查找中文名 */
    private var apiSportsNameMap: Map<Int, String>? = null

    private fun resolveChineseNameByApiSportsId(apiSportsId: Int): String? {
        if (apiSportsNameMap == null) {
            apiSportsNameMap = try {
                val json = context.assets.open("football_data_person_id_map.json")
                    .bufferedReader().use { it.readText() }
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val root: Map<String, Any> = Gson().fromJson(json, type)
                val playersList = root["players"] as? List<Map<String, Any>> ?: emptyList()
                playersList.filter { it["api_sports_id"] is Double }
                    .associate { (it["api_sports_id"] as Double).toInt() to (it["name_cn"] as? String ?: "") }
            } catch (_: Exception) { emptyMap() }
        }
        return apiSportsNameMap?.get(apiSportsId)?.takeIf { it.isNotEmpty() }
    }

    // ========================================================================
    // 队徽 Crest URL
    // ========================================================================

    private var crestCache: Map<String, String>? = null

    suspend fun getCrestUrls(): Map<String, String> {
        if (crestCache != null) return crestCache!!
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.footballData.getTeams()
            }
            val map = resp.teams.associate { it.tla to it.crest }
            crestCache = map
            map
        } catch (e: Exception) {
            android.util.Log.w("DataRepository", "Teams API失败", e)
            emptyMap()
        }
    }

    // ========================================================================
    // 球员数据
    // ========================================================================

    fun searchPlayers(query: String): List<UnifiedPlayer> {
        return playerDatabase.searchByName(query).map { it.toUnifiedPlayer() }
    }

    fun getPlayerByName(name: String): UnifiedPlayer? {
        return playerDatabase.searchByName(name).firstOrNull()?.toUnifiedPlayer()
    }

    // ========================================================================
    // 预测
    // ========================================================================

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
    // 球队
    // ========================================================================

    fun getTeamSchedule(teamFifaCode: String): List<UnifiedMatch> {
        return matchData.matches
            .filter { it.homeFifa == teamFifaCode || it.awayFifa == teamFifaCode }
            .sortedBy { it.datetime }
            .map { it.toUnifiedMatch() }
    }

    // ========================================================================
    // 内部工具
    // ========================================================================

    /** 加载 match_events.json */
    private fun loadMatchEvents(): Map<String, Map<String, Any>> {
        return try {
            val json = context.assets.open("match_events.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
            Gson().fromJson(json, type)
        } catch (_: Exception) { emptyMap() }
    }

    /** FootballStandingRow → StandingRow */
    private fun FootballStandingRow.toStandingRow(): StandingRow {
        val cn = MatchData.getChineseName(team.name)
        return StandingRow(
            position, team.name, cn, team.tla,
            playedGames, won, draw, lost,
            goalsFor, goalsAgainst, goalDifference, points,
            position <= 2
        )
    }

    /** FootballScorer → ScorerRow（尝试关联中文名） */
    private fun FootballScorer.toScorerRow(): ScorerRow {
        val cn = resolveChineseName(player.id, player.name)
        return ScorerRow(0, cn, team.name, goals, assists ?: 0)
    }

    /** 通过 person_id_map.json 获取中文名 */
    private var personNameMap: Map<Int, String>? = null

    private fun resolveChineseName(personId: Int, fallback: String): String {
        if (personNameMap == null) {
            personNameMap = try {
                val json = context.assets.open("football_data_person_id_map.json")
                    .bufferedReader().use { it.readText() }
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val list: List<Map<String, Any>> = Gson().fromJson(json, type)
                list.filter { it["person_id"] is Double }
                    .associate { (it["person_id"] as Double).toInt() to (it["name_cn"] as? String ?: "") }
            } catch (_: Exception) { emptyMap() }
        }
        return personNameMap?.get(personId)?.takeIf { it.isNotEmpty() } ?: fallback
    }

    // ========================================================================
    // 深入分析 (Living_Module_Integration.md §4)
    // ========================================================================

    /** 获取射门数据（BDL match_shots — 待API激活） */
    fun getMatchShots(matchId: Int): List<ShotEntry> = emptyList()

    /**
     * 获取球员单场统计（含 xG/xA 等 BDL 独有字段）
     * 从 match_events.json 计算基础数据
     */
    fun getPlayerStatsForMatch(matchId: String, playerName: String): PlayerMatchStats? {
        return try {
            val gson = com.google.gson.Gson()
            val json = context.assets.open("match_events.json").bufferedReader().use { it.readText() }
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            val allEvents: Map<String, Any> = gson.fromJson(json, type)
            val ed = allEvents[matchId] as? Map<String, Any> ?: return null
            val rawEvents = ed["events"] as? List<Map<String, Any>> ?: return null

            var goals = 0; var assists = 0; var yellows = 0; var reds = 0
            for (evt in rawEvents) {
                val pc = evt["playerCn"] as? String ?: ""
                val p = evt["player"] as? String ?: ""
                if (!pc.contains(playerName, true) && !p.contains(playerName, true)) continue
                when (evt["type"] as? String) {
                    "goal" -> goals++; "assist" -> assists++
                    "yellow" -> yellows++; "red" -> reds++
                }
            }
            PlayerMatchStats(
                matchId = matchId,
                playerId = 0,
                rating = null,
                minutesPlayed = 90,
                goals = goals,
                assists = assists,
                shotsTotal = null,
                shotsOnTarget = null,
                passesTotal = null,
                passesAccurate = null,
                keyPasses = null,
                tacklesTotal = null,
                interceptions = null,
                clearances = null,
                foulsCommitted = null,
                dribblesSuccess = null,
                duelsWon = null,
                offsides = null,
                expectedGoals = null,
                expectedAssists = null,
                crossesTotal = null,
                crossesAccurate = null,
                longBallsTotal = null,
                longBallsAccurate = null,
                possessionLost = null,
                ballRecoveries = null,
                duelsLost = null,
                aerialDuelsWon = null,
                aerialDuelsLost = null
            )
        } catch (_: Exception) { null }
    }

    /** 实时轮询配置 (API_RESOURCE.md §10.4) */
    data class PollingConfig(
        val enabled: Boolean = false,
        val intervalBeforeMatch: Long = 5 * 60 * 1000L,
        val intervalLive: Long = 30 * 1000L,
        val intervalAfterMatch: Long = 0L
    )

    companion object {
        const val API_SPORTS_PRO_DAILY_LIMIT = 7500
        const val API_SPORTS_PRO_MATCH_COST = 122
    }
}

/** MatchData.Match → UnifiedMatch 扩展函数 */
fun MatchData.Match.toUnifiedMatch() = UnifiedMatch(
    id = id, homeTeamEn = homeTeam, homeTeamCn = homeTeamCn,
    awayTeamEn = awayTeam, awayTeamCn = awayTeamCn,
    homeFifaCode = homeFifa, awayFifaCode = awayFifa,
    group = group, matchday = matchday, round = round, type = type,
    datetime = "$date $time", stadium = stadium, stadiumCity = stadiumCity,
    status = status, homeScore = homeScore, awayScore = awayScore,
    halfTimeHome = htHome, halfTimeAway = htAway
)

/** PlayerInfo → UnifiedPlayer 扩展函数 */
fun PlayerInfo.toUnifiedPlayer() = UnifiedPlayer(
    id = playerId, name = name, nameCn = "", jerseyNumber = jerseyNumber,
    position = position, teamName = teamName, teamFifaCode = countryCode,
    club = club, heightCm = heightCm,
    totalGoals = goals, totalAssists = assists, totalAppearances = appearances,
    avgRating = avgRating, injured = false
)
