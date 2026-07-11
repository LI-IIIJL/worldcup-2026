package worldcup.helper.data.repos

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import worldcup.helper.data.MatchData
import worldcup.helper.data.model.*
import worldcup.helper.network.FootballStandingRow
import worldcup.helper.network.FootballScorer
import worldcup.helper.network.LiveApiClient

/**
 * 积分榜/球员榜 Repository
 *
 * 数据来源: football-data.org + api-sports.io + BDL GOAT + matches.json
 * API 等级: 🔴 必须API（积分榜/射手榜/球员榜全部API优先）
 * 所属架构: StandingRepo (new_framework.md §2.1)
 *
 * 职责：
 * - 小组积分榜（三源降级: football-data → BDL → 本地）
 * - 射手榜（API优先: football-data /scorers → 本地）
 * - 助攻/评分/牌榜（API优先: api-sports Pro 遍历48队 → 本地）
 * - 供 Tab D 展示全部榜单
 * - 淘汰赛数据（小组赛结束后自动填充）
 */
class StandingRepo(context: Context) {

    companion object {
        private const val TAG = "StandingRepo"
    }

    private val matchData by lazy { MatchData(context) }
    private val matchRepo by lazy { MatchRepo(context) }
    private val gson = Gson()

    // ========================================================================
    // 48队 api-sports ID 映射
    // ========================================================================

    private val apiSportsTeamIds: Map<String, Int> by lazy {
        mapOf(
            "Mexico" to 16, "South Africa" to 1531, "South Korea" to 17, "Korea Republic" to 17,
            "Czech Republic" to 770, "Czechia" to 770, "Canada" to 5529,
            "Bosnia and Herzegovina" to 1113, "Bosnia-Herzegovina" to 1113, "Bosnia" to 1113,
            "Qatar" to 1569, "Switzerland" to 15, "Brazil" to 6, "Morocco" to 31,
            "Haiti" to 2386, "Scotland" to 1108, "United States" to 2384, "USA" to 2384,
            "Paraguay" to 2380, "Australia" to 20, "Turkey" to 777, "Turkiye" to 777,
            "Germany" to 25, "Curacao" to 5530, "Curaçao" to 5530,
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

    // ========================================================================
    // 中文名映射（API 球员 → 中文名）
    // ========================================================================

    private var personNameMap: Map<Int, String>? = null
    private var apiSportsNameMap: Map<Int, String>? = null

    private fun loadIdMaps(context: Context) {
        if (personNameMap != null) return
        try {
            val json = context.assets.open("football_data_person_id_map.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            val playersList = root["players"] as? List<Map<String, Any>> ?: emptyList()

            val byPerson = mutableMapOf<Int, String>()
            val byApi = mutableMapOf<Int, String>()
            for (p in playersList) {
                val cn = p["name_cn"] as? String ?: ""
                if (cn.isEmpty()) continue
                (p["person_id"] as? Double)?.toInt()?.let { byPerson[it] = cn }
                (p["api_sports_id"] as? Double)?.toInt()?.let { byApi[it] = cn }
            }
            personNameMap = byPerson
            apiSportsNameMap = byApi
        } catch (_: Exception) {
            personNameMap = emptyMap()
            apiSportsNameMap = emptyMap()
        }
    }

    // ========================================================================
    // 1. 小组积分榜（三源降级）
    // ========================================================================

    /**
     * 获取小组积分榜
     *
     * Tier 1: football-data /standings
     * Tier 2: BDL /group_standings
     * Tier 3: 本地 matches.json 计算
     */
    suspend fun getStandings(): Map<String, List<StandingRow>> {
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
            Log.w(TAG, "Tier 1 (football-data) standings failed", e)
        }

        // Tier 2: BDL GOAT
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
            Log.w(TAG, "Tier 2 (BDL) standings failed", e)
        }

        // Tier 3: 本地计算
        return getStandingsLocal()
    }

    /** 本地赛果计算积分榜（降级方案） */
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
    // 2. 射手榜（API优先）
    // ========================================================================

    /**
     * 获取射手榜
     * 🔴 必须API: football-data /scorers → 本地降级
     */
    suspend fun getScorers(): List<ScorerRow> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                LiveApiClient.footballData.getScorers(limit = 50)
            }
            if (resp.scorers.isEmpty()) getScorersLocal()
            else resp.scorers.map { it.toScorerRow() }
        } catch (e: Exception) {
            Log.w(TAG, "Scorers API failed, fallback local", e)
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
    // 3. 赛季排名（助攻/评分/牌 — api-sports Pro 优先）
    // ========================================================================

    data class SeasonRanking(
        val playerName: String,
        val playerNameCn: String,
        val teamName: String,
        val assists: Int = 0,
        val rating: Double = 0.0,
        val cards: Int = 0,
        val appearances: Int = 0,
        // 新增榜单字段
        val shotsOnTarget: Int = 0,     // 🎯 射正
        val keyPasses: Int = 0,         // 🔑 关键传球
        val tackles: Int = 0,           // 💪 抢断
        val dribbles: Int = 0           // ⚡ 过人成功
    )

    /**
     * 获取全赛季排名（助攻/评分/牌）
     * 🔴 必须API: api-sports Pro 遍历48队
     */
    suspend fun getSeasonRankings(context: Context): List<SeasonRanking> {
        loadIdMaps(context)

        val finishedTeams = matchData.matches
            .filter { matchData.getStatus(it) == MatchData.Status.FINISHED }
            .flatMap { listOf(it.homeTeam, it.awayTeam) }
            .distinct()

        if (finishedTeams.isEmpty()) return emptyList()

        return coroutineScope {
            val jobs = finishedTeams.mapNotNull { teamEn ->
                val teamId = apiSportsTeamIds[teamEn] ?: return@mapNotNull null
                async {
                    try {
                        withContext(Dispatchers.IO) {
                            LiveApiClient.apiSports.getPlayersByTeam(teamId)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "season ranking API failed: $teamEn", e)
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
                    val shots = stats.shots
                    val passes = stats.passes
                    val tackles = stats.tackles
                    val dribbles = stats.dribbles

                    val appearences = games?.appearences ?: 0
                    if (appearences == 0) continue

                    val ratingStr = games?.rating
                    val rating = try { ratingStr?.toDouble() ?: 0.0 } catch (_: Exception) { 0.0 }
                    val assists = goals?.assists ?: 0
                    val yellows = cards?.yellow ?: 0
                    val reds = cards?.red ?: 0

                    val name = player.player?.name ?: ""
                    val nameCn = apiSportsNameMap?.get(player.player?.id ?: 0) ?: name

                    result.add(SeasonRanking(
                        playerName = name,
                        playerNameCn = nameCn,
                        teamName = teamName,
                        assists = assists,
                        rating = rating,
                        cards = yellows + reds,
                        appearances = appearences,
                        shotsOnTarget = shots?.on ?: 0,
                        keyPasses = passes?.key ?: 0,
                        tackles = tackles?.total ?: 0,
                        dribbles = dribbles?.success ?: 0
                    ))
                }
            }

            result
        }
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private fun loadMatchEvents(): Map<String, Map<String, Any>> = emptyMap()

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

    /** FootballScorer → ScorerRow（关联中文名） */
    private fun FootballScorer.toScorerRow(): ScorerRow {
        val cn = personNameMap?.get(player.id) ?: player.name
        return ScorerRow(0, cn, team.name, goals, assists ?: 0)
    }

    /** 本地计算辅助类 */
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
}
