package worldcup.helper.data

import android.content.Context
import com.google.gson.Gson

/**
 * Local player database that loads pre-bundled World Cup 2026 roster data
 * from assets/players_2026.json.
 *
 * Replaces the BALLDONTLIE API for basic player lookups.
 */
class PlayerDatabase(context: Context) {

    private data class TeamData(
        val id: Int,
        val name: String,
        val shortName: String = "",
        val countryCode: String = "",
        val group: String = "",
        val players: List<PlayerData> = emptyList()
    )

    private data class PlayerData(
        val jerseyNumber: Int = 0,
        val name: String = "",
        val nameCn: String = "",
        val position: String = "",
        val club: String = "",
        @com.google.gson.annotations.SerializedName("photo_url")
        val photoUrl: String? = null,
        val injured: Boolean = false
    )

    private data class RootData(
        val teams: List<TeamData> = emptyList()
    )

    private val teams: List<TeamData>

    init {
        teams = try {
            val json = context.assets.open("players_2026.json")
                .bufferedReader()
                .use { it.readText() }
            val gson = Gson()
            val root = gson.fromJson(json, RootData::class.java)
            root.teams
        } catch (e: Exception) {
            android.util.Log.e("PlayerDatabase", "Failed to load player data", e)
            emptyList()
        }
    }

    /**
     * Get all teams for display in the team selector.
     */
    fun getAllTeams(): List<TeamSummary> {
        return teams.map { team ->
            TeamSummary(
                id = team.id,
                name = team.name,
                countryCode = team.countryCode,
                group = team.group,
                playerCount = team.players.size
            )
        }
    }

    /**
     * Find players by jersey number within specific teams.
     * @param jerseyNumber the scanned jersey number
     * @param teamIds list of team IDs selected by the user
     * @return list of PlayerInfo matching the number in the selected teams
     */
    fun findPlayersByNumber(jerseyNumber: Int, teamIds: List<Int>): List<PlayerInfo> {
        val result = mutableListOf<PlayerInfo>()

        for (team in teams) {
            if (team.id !in teamIds) continue

            for (player in team.players) {
                if (player.jerseyNumber == jerseyNumber) {
                    result.add(PlayerInfo(
                        name = player.name,
                        jerseyNumber = player.jerseyNumber,
                        teamName = team.name,
                        position = player.position,
                        countryCode = team.countryCode,
                        club = player.club,
                        heightCm = null,
                        goals = 0,
                        assists = 0,
                        appearances = 0,
                        avgRating = null,
                        photoUrl = player.photoUrl,
                        playerId = team.id * 100 + player.jerseyNumber,
                        teamId = team.id
                    ))
                }
            }
        }
        return result
    }

    /**
     * Get team details by ID.
     */
    fun getTeamName(teamId: Int): String {
        return teams.find { it.id == teamId }?.name ?: ""
    }

    /**
     * Search players by name (supports Chinese and English).
     */
    fun searchByName(query: String): List<PlayerInfo> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()

        val result = mutableListOf<PlayerInfo>()
        for (team in teams) {
            for (player in team.players) {
                val matchEnglish = player.name.lowercase().contains(q)
                val matchChinese = player.nameCn.lowercase().contains(q)
                if (matchEnglish || matchChinese) {
                    result.add(PlayerInfo(
                        name = player.name,
                        jerseyNumber = player.jerseyNumber,
                        teamName = team.name,
                        position = player.position,
                        countryCode = team.countryCode,
                        club = player.club,
                        heightCm = null,
                        photoUrl = player.photoUrl,
                        goals = 0, assists = 0, appearances = 0, avgRating = null,
                        playerId = team.id * 100 + player.jerseyNumber,
                        teamId = team.id,
                        injured = player.injured
                    ))
                }
            }
        }
        return result
    }

    /**
     * 获取所有伤病球员名单（本地数据，来自 squad 提交时的 injured 标记）
     * @return 伤病球员信息列表
     */
    fun getInjuredPlayers(): List<PlayerInfo> {
        val result = mutableListOf<PlayerInfo>()
        for (team in teams) {
            for (player in team.players) {
                if (player.injured) {
                    result.add(PlayerInfo(
                        name = player.name,
                        jerseyNumber = player.jerseyNumber,
                        teamName = team.name,
                        position = player.position,
                        countryCode = team.countryCode,
                        club = player.club,
                        heightCm = null,
                        photoUrl = player.photoUrl,
                        goals = 0, assists = 0, appearances = 0, avgRating = null,
                        playerId = team.id * 100 + player.jerseyNumber,
                        teamId = team.id,
                        injured = true
                    ))
                }
            }
        }
        return result
    }

    /** 中英文队名映射 */
    private val teamNameMap: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        for (team in teams) {
            map[team.name.lowercase()] = team.name
            // 常见简写
            map[team.name.lowercase().replace("republic of ", "").replace("republic ", "")] = team.name
        }
        // 手动维护中文映射
        val cnMap = mapOf(
            "墨西哥" to "Mexico", "南非" to "South Africa", "韩国" to "South Korea",
            "捷克" to "Czech Republic", "加拿大" to "Canada", "波黑" to "Bosnia-Herzegovina",
            "卡塔尔" to "Qatar", "瑞士" to "Switzerland", "巴西" to "Brazil",
            "摩洛哥" to "Morocco", "海地" to "Haiti", "苏格兰" to "Scotland",
            "美国" to "USA", "巴拉圭" to "Paraguay", "澳大利亚" to "Australia",
            "土耳其" to "Turkey", "德国" to "Germany", "库拉索" to "Curacao",
            "科特迪瓦" to "Ivory Coast", "厄瓜多尔" to "Ecuador", "荷兰" to "Netherlands",
            "日本" to "Japan", "瑞典" to "Sweden", "突尼斯" to "Tunisia",
            "比利时" to "Belgium", "埃及" to "Egypt", "沙特" to "Saudi Arabia",
            "乌拉圭" to "Uruguay", "伊朗" to "Iran", "新西兰" to "New Zealand",
            "法国" to "France", "塞内加尔" to "Senegal", "伊拉克" to "Iraq",
            "挪威" to "Norway", "阿根廷" to "Argentina", "阿尔及利亚" to "Algeria",
            "奥地利" to "Austria", "约旦" to "Jordan", "葡萄牙" to "Portugal",
            "刚果" to "Congo", "英格兰" to "England", "克罗地亚" to "Croatia",
            "加纳" to "Ghana", "巴拿马" to "Panama", "乌兹别克斯坦" to "Uzbekistan",
            "哥伦比亚" to "Colombia", "西班牙" to "Spain", "佛得角" to "Cape Verde",
            "中国" to "China", "意大利" to "Italy"
        )
        map.putAll(cnMap.map { it.key.lowercase() to it.value })
        map
    }

    /**
     * 获取球队完整阵容（按位置分组）
     * @param teamName 球队名（中文或英文）
     * @return 阵容文本描述
     */
    fun getTeamRoster(teamName: String): String {
        val key = teamName.lowercase().trim()
        val engName = teamNameMap[key] ?: return ""

        val team = teams.find { it.name == engName } ?: return ""
        return buildString {
            appendLine("【${team.name} 阵容】共${team.players.size}人")

            val byPosition = team.players.groupBy { normalizePosition(it.position) }
            val posOrder = listOf("GK", "DF", "MF", "FW")

            for (pos in posOrder) {
                val players = byPosition[pos] ?: continue
                val posLabel = when (pos) {
                    "GK" -> "门将"; "DF" -> "后卫"; "MF" -> "中场"; else -> "前锋"
                }
                append("▪️$posLabel（${players.size}人）: ")
                append(players.joinToString("、") {
                    val cn = it.nameCn.ifEmpty { it.name }
                    "#${it.jerseyNumber} $cn"
                })
                appendLine()
            }
        }
    }

    private fun normalizePosition(pos: String): String {
        return when {
            pos.equals("GK", ignoreCase = true) -> "GK"
            pos.startsWith("D", ignoreCase = true) || pos.equals("CB", ignoreCase = true)
                || pos.equals("LB", ignoreCase = true) || pos.equals("RB", ignoreCase = true)
                || pos.equals("WB", ignoreCase = true) -> "DF"
            pos.startsWith("M", ignoreCase = true) || pos.equals("CDM", ignoreCase = true)
                || pos.equals("CAM", ignoreCase = true) || pos.equals("CM", ignoreCase = true) -> "MF"
            pos.startsWith("F", ignoreCase = true) || pos.startsWith("S", ignoreCase = true)
                || pos.equals("LW", ignoreCase = true) || pos.equals("RW", ignoreCase = true)
                || pos.equals("CF", ignoreCase = true) || pos.equals("ST", ignoreCase = true)
                || pos.equals("W", ignoreCase = true) -> "FW"
            else -> pos
        }
    }
}

data class TeamSummary(
    val id: Int,
    val name: String,
    val countryCode: String,
    val group: String,
    val playerCount: Int
)

data class PlayerInfo(
    val name: String,
    val jerseyNumber: Int,
    val teamName: String,
    val position: String,
    val countryCode: String,
    val club: String,
    val heightCm: Int? = null,
    val goals: Int = 0,
    val assists: Int = 0,
    val appearances: Int = 0,
    val avgRating: Double? = null,
    val photoUrl: String? = null,
    val playerId: Int,
    val teamId: Int,
    val injured: Boolean = false
)
