package worldcup.helper.data.repos

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import worldcup.helper.data.PlayerDatabase
import worldcup.helper.data.model.UnifiedMatch
import worldcup.helper.data.model.UnifiedPlayer
import worldcup.helper.network.LiveApiClient

/**
 * 球队数据 Repository
 *
 * 数据来源: football-data.org + players_2026.json + MatchRepo
 * API 等级: 🔴 必须API（队徽crest）+ 🟢 基础本地（阵容列表/球队资料）
 * 所属架构: TeamRepo (new_framework.md §2.1)
 *
 * 职责：
 * - 球队基本信息（48队，分组，FIFA代码）
 * - 球队阵容列表（按位置分组）
 * - 队徽 crest URL（football-data /teams API 一次拉取缓存）
 * - 球队赛程赛果（从 MatchRepo 获取）
 * - 供 Tab C 比赛详情中展示球队卡片
 * - 供 Tab D 球队网格、积分榜球队链接
 * - 供 TeamProfileActivity 展示完整球队资料
 *
 * 数据所有权: TeamRepo 是球队数据的写入/更新方
 * PlayerRepo / StandingRepo 只能读
 */
class TeamRepo(context: Context) {

    companion object {
        private const val TAG = "TeamRepo"
    }

    private val playerDatabase by lazy { PlayerDatabase(context) }
    private val matchRepo by lazy { MatchRepo(context) }
    private val gson = Gson()
    private val appContext = context.applicationContext

    // ========================================================================
    // 内存缓存
    // ========================================================================

    private var crestCache: Map<String, String>? = null
    private var teamsCache: List<TeamBasicInfo>? = null
    private var rosterCache: Map<String, List<PlayerSummary>>? = null

    /** 球队基础信息 */
    data class TeamBasicInfo(
        val nameEn: String,
        val nameCn: String,
        val fifaCode: String,
        val iso2: String,
        val group: String,
        val flagUrl: String = "",
        val crestUrl: String? = null,       // football-data crest
        val countryCode: String = "",
        val elo: Int? = null
    )

    /** 球队完整资料（含阵容+赛程） */
    data class TeamDetail(
        val basic: TeamBasicInfo,
        val players: List<PlayerSummary>,
        val schedule: List<UnifiedMatch>,
        val homeStadium: String? = null,
        val elo: Int? = null,
        val elo1yAgo: Int? = null,
        val isHost: Boolean = false
    )

    data class PlayerSummary(
        val name: String,
        val nameCn: String,
        val jerseyNumber: Int,
        val position: String,
        val positionCn: String,
        val club: String,
        val photoUrl: String?,
        val injured: Boolean,
        val marketValueMil: Double? = null,
        val apiSportsId: Int? = null
    )

    // ========================================================================
    // 球队列表（本地 players_2026.json + teams.json）
    // ========================================================================

    /** 获取所有48队基本资料 */
    fun getAllTeams(): List<TeamBasicInfo> {
        if (teamsCache != null) return teamsCache!!

        try {
            val json = appContext.assets.open("players_2026.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            val teams = root["teams"] as? List<Map<String, Any>> ?: emptyList()

            teamsCache = teams.map { t ->
                TeamBasicInfo(
                    nameEn = t["name"] as? String ?: "",
                    nameCn = t["nameCn"] as? String ?: "",
                    fifaCode = t["countryCode"] as? String ?: "",
                    iso2 = t["countryCode"] as? String ?: "",
                    group = t["group"] as? String ?: "",
                    countryCode = t["countryCode"] as? String ?: "",
                    elo = (t["elo"] as? Double)?.toInt()
                )
            }
            return teamsCache!!
        } catch (e: Exception) {
            Log.e(TAG, "加载球队列表失败", e)
            return emptyList()
        }
    }

    /** 按组获取球队 */
    fun getTeamsByGroup(group: String): List<TeamBasicInfo> {
        return getAllTeams().filter { it.group == group }
    }

    /** 所有小组 */
    fun getAllGroups(): List<String> {
        return getAllTeams().map { it.group }.distinct().sorted()
    }

    /** 按名称查找球队 */
    fun findTeam(query: String): TeamBasicInfo? {
        val q = query.lowercase().trim()
        return getAllTeams().firstOrNull {
            it.nameEn.lowercase().contains(q) ||
            it.nameCn.contains(q, ignoreCase = true) ||
            it.fifaCode.lowercase() == q
        }
    }

    /** 按FIFA代码查找球队 */
    fun getTeamByFifaCode(fifaCode: String): TeamBasicInfo? {
        return getAllTeams().firstOrNull { it.fifaCode == fifaCode }
    }

    // ========================================================================
    // 球队详情（阵容 + 赛程）
    // ========================================================================

    /**
     * 获取球队完整资料
     * 用于 TeamProfileActivity
     */
    fun getTeamDetail(teamName: String): TeamDetail? {
        val basic = findTeam(teamName) ?: return null

        // 阵容
        val players = getTeamRoster(basic.nameEn)

        // 赛程
        val schedule = matchRepo.getTeamMatches(basic.fifaCode)

        return TeamDetail(
            basic = basic,
            players = players,
            schedule = schedule,
            elo = basic.elo
        )
    }

    /** 获取球队阵容（按位置分组） */
    fun getTeamRoster(teamName: String): List<PlayerSummary> {
        rosterCache?.get(teamName)?.let { return it }
        loadRosterCache()
        return rosterCache?.get(teamName) ?: emptyList()
    }

    /** 加载所有球队阵容到缓存 */
    private fun loadRosterCache() {
        if (rosterCache != null) return
        try {
            val json = appContext.assets.open("players_2026.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            val teams = root["teams"] as? List<Map<String, Any>> ?: emptyList()

            val cache = mutableMapOf<String, List<PlayerSummary>>()
            for (t in teams) {
                val tName = t["name"] as? String ?: continue
                val tPlayers = t["players"] as? List<Map<String, Any>> ?: emptyList()

                val playerList = tPlayers.map { p ->
                    val pos = p["position"] as? String ?: ""
                    PlayerSummary(
                        name = p["name"] as? String ?: "",
                        nameCn = p["nameCn"] as? String ?: "",
                        jerseyNumber = when (val n = p["jerseyNumber"]) {
                            is Double -> n.toInt(); is Int -> n; else -> 0
                        },
                        position = pos,
                        positionCn = when (pos.uppercase()) {
                            "GK", "GOALKEEPER" -> "门将"
                            "DF", "DEFENDER", "DEF" -> "后卫"
                            "MF", "MIDFIELDER", "MID" -> "中场"
                            "FW", "FORWARD", "FWD", "ATT" -> "前锋"
                            "ATTACKER" -> "前锋"
                            else -> pos
                        },
                        club = p["club"] as? String ?: "",
                        photoUrl = p["photo_url"] as? String,
                        injured = p["injured"] as? Boolean ?: false,
                        marketValueMil = p["market_value_mil"] as? Double,
                        apiSportsId = (p["api_sports_id"] as? Double)?.toInt()
                    )
                }
                cache[tName] = playerList
            }
            rosterCache = cache
        } catch (e: Exception) {
            Log.e(TAG, "加载球员列表失败", e)
            rosterCache = emptyMap()
        }
    }

    /** 获取阵容文本摘要（供 Tab B 使用） */
    fun getTeamRosterSummary(teamName: String): String {
        return playerDatabase.getTeamRoster(teamName)
    }

    // ========================================================================
    // 队徽 Crest URL（API优先）
    // ========================================================================

    /**
     * 获取球队队徽 URL
     * 🟡 条件API: football-data /teams 一次拉取永久缓存
     */
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
            Log.w(TAG, "crest API failed", e)
            emptyMap()
        }
    }

    /** 获取单支球队队徽 URL */
    suspend fun getCrestUrl(fifaCode: String): String? {
        return getCrestUrls()[fifaCode]
    }

    // ========================================================================
    // 伤病球员
    // ========================================================================

    /** 获取所有受伤球员列表 */
    fun getInjuredPlayers(): List<PlayerSummary> {
        loadRosterCache()
        return rosterCache?.values?.flatten()?.filter { it.injured } ?: emptyList()
    }

    // ========================================================================
    // 球队赛程（从 MatchRepo 同步）
    // ========================================================================

    /** 获取某支球队的赛程赛果 */
    fun getTeamSchedule(fifaCode: String): List<UnifiedMatch> {
        return matchRepo.getTeamMatches(fifaCode)
    }
}
