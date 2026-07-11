package worldcup.helper.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import worldcup.helper.data.model.*
import worldcup.helper.network.ApiSportsSeasonPlayer
import worldcup.helper.network.ApiSportsPlayerStats
import worldcup.helper.network.FootballAggregations
import worldcup.helper.network.BdlShotListResponse
import worldcup.helper.network.LiveApiClient

/**
 * 球员数据仓储层
 *
 * 职责：聚合所有数据源，提供完整的 [PlayerProfile] 加载能力
 * 原则：在线优先，本地兜底
 *
 * 数据获取顺序：
 * 1. players_2026.json → 基础信息（内存缓存，秒回）
 * 2. football_data_person_id_map.json → ID映射（内存缓存，秒回）
 * 3. football-data API → 世界杯累计统计（异步）
 * 4. api-sports API → 赛季18项统计（异步）
 * 5. trophies_cache.json → 生涯荣誉（本地缓存）
 * 6. BDL API → 高级数据（异步，可选）
 * 7. api-sports fixtures/players → 单场统计（异步，可选）
 */
class PlayerRepository(context: Context) {

    companion object {
        private const val TAG = "PlayerRepository"
    }

    private val appContext = context.applicationContext
    private val gson = Gson()

    // ========================================================================
    // 内存缓存
    // ========================================================================

    /** 48队球员缓存：teamName -> List<PlayerData> */
    data class PlayerData(
        val name: String = "",
        val nameCn: String = "",
        val jerseyNumber: Int = 0,
        val position: String = "",
        val club: String = "",
        val photoUrl: String? = null,
        val injured: Boolean = false,
        val marketValueMil: Double? = null,
        val teamName: String = "",
        val teamNameCn: String = "",
        val teamFifaCode: String = "",
    )

    private var playersCache: Map<String, List<PlayerData>>? = null
    private var playerNameIndex: Map<String, PlayerData>? = null  // name.lowercase() -> PlayerData

    /** ID映射表缓存 */
    private data class IdMapping(
        val personId: Int? = null,
        val apiSportsId: Int? = null,
        val nameCn: String = "",
    )
    private var idMapCache: Map<String, IdMapping>? = null  // name.lowercase() -> IdMapping
    private var idMapByPersonId: Map<Int, IdMapping>? = null
    private var idMapByApiSportsId: Map<Int, IdMapping>? = null

    /** api-sports 球队ID缓存 */
    private var apiSportsTeamIdCache: Map<String, Int>? = null

    /** 荣誉缓存 */
    private var trophiesCache: Map<String, List<Honor>>? = null

    /** 照片查找表（预构建，覆盖88.9%球员） */
    private var photoLookup: Map<String, String?>? = null  // playerName -> photoUrl

    /** 年龄/身高数据表 */
    private var ageMap: Map<String, Map<String, Any?>>? = null

    /** 球员资料缓存（避免重复请求同一个球员） */
    private val profileCache = mutableMapOf<String, PlayerProfile>()

    // ========================================================================
    // 加载基础数据
    // ========================================================================

    /** 加载 players_2026.json 到内存 */
    private fun ensurePlayersLoaded() {
        if (playersCache != null) return
        try {
            val json = appContext.assets.open("players_2026.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            val teams = root["teams"] as? List<Map<String, Any>> ?: emptyList()

            val cache = mutableMapOf<String, List<PlayerData>>()
            val index = mutableMapOf<String, PlayerData>()

            for (t in teams) {
                val tName = t["name"] as? String ?: ""
                val tNameCn = t["nameCn"] as? String ?: ""
                val code = t["countryCode"] as? String ?: ""
                val teamPlayers = t["players"] as? List<Map<String, Any>> ?: emptyList()

                val pdList = teamPlayers.map { p ->
                    val name = p["name"] as? String ?: ""
                    val cn = p["nameCn"] as? String ?: ""
                    PlayerData(
                        name = name,
                        nameCn = cn,
                        jerseyNumber = when (val n = p["jerseyNumber"]) {
                            is Double -> n.toInt(); is Int -> n; else -> 0
                        },
                        position = p["position"] as? String ?: "",
                        club = p["club"] as? String ?: "",
                        photoUrl = p["photo_url"] as? String,
                        injured = p["injured"] as? Boolean ?: false,
                        marketValueMil = p["market_value_mil"] as? Double,
                        teamName = tName,
                        teamNameCn = tNameCn,
                        teamFifaCode = code,
                    )
                }

                cache[tName] = pdList
                for (pd in pdList) {
                    index[pd.name.lowercase()] = pd
                    if (pd.nameCn.isNotEmpty()) index[pd.nameCn] = pd
                }
            }

            playersCache = cache
            playerNameIndex = index
            Log.d(TAG, "球员基础数据加载完成: ${index.size} 人")
        } catch (e: Exception) {
            Log.e(TAG, "加载 players_2026.json 失败", e)
            playersCache = emptyMap()
            playerNameIndex = emptyMap()
        }
    }

    /** 加载 football_data_person_id_map.json 到内存 */
    private fun ensureIdMapLoaded() {
        if (idMapCache != null) return
        try {
            val json = appContext.assets.open("football_data_person_id_map.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            val playersList = root["players"] as? List<Map<String, Any>> ?: emptyList()

            val cache = mutableMapOf<String, IdMapping>()
            val byPersonId = mutableMapOf<Int, IdMapping>()
            val byApiSportsId = mutableMapOf<Int, IdMapping>()

            for (p in playersList) {
                val name = p["name"] as? String ?: continue
                val personId = (p["person_id"] as? Double)?.toInt()
                val apiId = (p["api_sports_id"] as? Double)?.toInt()
                val cn = p["name_cn"] as? String ?: ""

                val mapping = IdMapping(personId, apiId, cn)
                cache[name.lowercase()] = mapping
                if (personId != null) byPersonId[personId] = mapping
                if (apiId != null) byApiSportsId[apiId] = mapping
            }

            idMapCache = cache
            idMapByPersonId = byPersonId
            idMapByApiSportsId = byApiSportsId
            Log.d(TAG, "ID映射表加载完成: ${cache.size} 人")
        } catch (e: Exception) {
            Log.e(TAG, "加载 ID 映射表失败", e)
            idMapCache = emptyMap()
            idMapByPersonId = emptyMap()
            idMapByApiSportsId = emptyMap()
        }
    }

    /** 加载 api-sports 球队ID映射 */
    private fun ensureApiSportsTeamIds() {
        if (apiSportsTeamIdCache != null) return
        apiSportsTeamIdCache = mapOf(
            "Mexico" to 16, "South Africa" to 1531, "South Korea" to 17,
            "Czech Republic" to 770, "Canada" to 5529,
            "Bosnia and Herzegovina" to 1113, "Qatar" to 1569,
            "Switzerland" to 15, "Brazil" to 6, "Morocco" to 31,
            "Haiti" to 2386, "Scotland" to 1108, "USA" to 2384,
            "Paraguay" to 2380, "Australia" to 20, "Turkey" to 777,
            "Turkiye" to 777, "Germany" to 25, "Curacao" to 5530,
            "Ivory Coast" to 1501, "Ecuador" to 2382, "Netherlands" to 1118,
            "Japan" to 12, "Sweden" to 5, "Tunisia" to 28, "Belgium" to 1,
            "Egypt" to 32, "Iran" to 22, "New Zealand" to 4673, "Spain" to 9,
            "Cape Verde" to 1533, "Saudi Arabia" to 23, "Uruguay" to 7,
            "France" to 2, "Senegal" to 13, "Iraq" to 1567, "Norway" to 1090,
            "Argentina" to 26, "Algeria" to 1532, "Austria" to 775,
            "Jordan" to 1548, "Portugal" to 27, "Congo DR" to 1508,
            "Uzbekistan" to 1568, "Colombia" to 8, "England" to 10,
            "Croatia" to 3, "Ghana" to 1504, "Panama" to 11,
        )
    }

    /** 加载荣誉缓存 */
    private fun ensureTrophiesLoaded() {
        if (trophiesCache != null) return
        try {
            val json = appContext.assets.open("trophies_cache.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)

            val cache = mutableMapOf<String, List<Honor>>()
            for ((key, value) in root) {
                @Suppress("UNCHECKED_CAST")
                val entry = value as? Map<String, Any> ?: continue
                val trophiesList = entry["trophies"] as? List<Map<String, Any>> ?: continue
                val honors = trophiesList.mapNotNull { t ->
                    val league = t["league"] as? String ?: return@mapNotNull null
                    val season = t["season"] as? String ?: ""
                    val place = t["place"] as? String ?: ""
                    Honor(
                        title = "$league - $place",
                        year = season,
                        category = place
                    )
                }
                cache[key] = honors
            }
            trophiesCache = cache
            Log.d(TAG, "荣誉缓存加载完成: ${cache.size} 人")
        } catch (e: Exception) {
            Log.e(TAG, "加载荣誉缓存失败", e)
            trophiesCache = emptyMap()
        }
    }

    /** 加载 photo_lookup.json 照片查找表（预构建，精准匹配） */
    private fun ensurePhotoLookupLoaded() {
        if (photoLookup != null) return
        try {
            val json = appContext.assets.open("photo_lookup.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            @Suppress("UNCHECKED_CAST")
            val lookup = root["lookup"] as? Map<String, Any> ?: emptyMap()
            val result = mutableMapOf<String, String?>()
            for ((name, url) in lookup) {
                result[name] = url as? String
            }
            photoLookup = result
            Log.d(TAG, "照片查找表加载完成: ${result.size} 人")
        } catch (e: Exception) {
            Log.e(TAG, "加载照片查找表失败", e)
            photoLookup = emptyMap()
        }
    }

    /** 加载 player_age_map.json 年龄/身高数据 */
    private fun ensureAgeMapLoaded() {
        if (ageMap != null) return
        try {
            val json = appContext.assets.open("player_age_map.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Map<String, Any?>>>() {}.type
            ageMap = gson.fromJson(json, type)
            Log.d(TAG, "年龄数据加载完成: ${ageMap?.size} 人")
        } catch (e: Exception) {
            Log.e(TAG, "加载年龄数据失败", e)
            ageMap = emptyMap()
        }
    }

    // ========================================================================
    // 查找球员
    // ========================================================================

    /**
     * 按名称查找球员（支持中文/英文）
     * 匹配优先级: 精确匹配 > 完整名匹配 > 包含匹配
     * @return 匹配的 PlayerData，未找到返回 null
     */
    fun findPlayerByName(query: String): PlayerData? {
        ensurePlayersLoaded()
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null

        // 优先级1: 精确匹配
        playerNameIndex?.get(q)?.let { return it }

        // 优先级2: 完整名匹配 - 索引key等于查询串
        val exactEntry = playerNameIndex?.entries?.firstOrNull { (k, _) ->
            k == q
        }
        if (exactEntry != null) return exactEntry.value

        // 优先级3: 查询串是索引key的一部分（查询串较短，如姓氏搜索）
        // 按索引key长度降序排列(长key优先)，避免"Rayan"优先于"Rayan Cherki"
        val partialMatches = playerNameIndex?.entries?.filter { (k, _) ->
            k.contains(q) && k != q
        }?.sortedByDescending { (k, _) -> k.length }
        if (partialMatches != null && partialMatches.isNotEmpty()) {
            return partialMatches.first().value
        }

        // 优先级4: 索引key是查询串的一部分（查询串较长，如全名搜索）
        val reverseMatches = playerNameIndex?.entries?.filter { (k, _) ->
            q.contains(k) && k != q
        }?.sortedByDescending { (k, _) -> k.length }
        if (reverseMatches != null && reverseMatches.isNotEmpty()) {
            return reverseMatches.first().value
        }

        return null
    }

    /**
     * 获取球队所有球员
     */
    fun getTeamPlayers(teamName: String): List<PlayerData> {
        ensurePlayersLoaded()
        // 精确匹配
        playersCache?.get(teamName)?.let { return it }
        // 大小写不敏感
        val entry = playersCache?.entries?.firstOrNull {
            it.key.lowercase() == teamName.lowercase()
        }
        return entry?.value ?: emptyList()
    }

    // ========================================================================
    // 照片解析（预构建查找表）
    // ========================================================================

    /**
     * 解析球员照片URL
     *
     * 使用预构建的 photo_lookup.json 查找表（覆盖88.9%的球员）。
     * 查找表由 build_photo_lookup_v2.py 通过三重跨引用生成：
     * 1. players_2026.json → 球员名
     * 2. football_data_person_id_map.json → 正确的 api_sports_id（带重音容错）
     * 3. player_photo_map.json → 验证照片URL正确性
     *
     * 如果查找表中没有，则尝试用 api_sports_id 构造 URL 兜底。
     */
    fun resolvePhotoUrl(
        playerName: String,
        apiSportsId: Int?,
        existingPhotoUrl: String?
    ): String? {
        // 第1层: photo_lookup.json 预构建查找表（精准名字匹配，含重音处理）
        ensurePhotoLookupLoaded()
        photoLookup?.get(playerName)?.let { url ->
            if (url != null) return url
        }

        // 第2层: 通过 api_sports_id 构造 URL（兜底）
        if (apiSportsId != null && apiSportsId > 0) {
            return "https://media.api-sports.io/football/players/$apiSportsId.png"
        }

        // 第3层: 已有的 photo_url（最不可靠，数据可能有误）
        if (!existingPhotoUrl.isNullOrEmpty() && existingPhotoUrl.length > 20) {
            return existingPhotoUrl
        }

        return null
    }

    // ========================================================================
    // 获取球员完整资料（核心方法）
    // ========================================================================

    /**
     * 获取球员完整资料卡
     *
     * 策略：
     * 1. 立即从本地返回基本资料（毫秒级）
     * 2. 并行拉取 API 数据（后台更新）
     * 3. 合并后返回完整 PlayerProfile
     *
     * @param playerName 球员英文名
     * @param teamName 球队英文名（可选，精确匹配用）
     * @param callback 回调接收最终 PlayerProfile
     */
    suspend fun getPlayerProfile(
        playerName: String,
        teamName: String? = null,
    ): PlayerProfile {
        val cacheKey = "$teamName|$playerName"
        profileCache[cacheKey]?.let { return it }

        // 第1步：加载本地基础数据（确保缓存就绪）
        ensurePlayersLoaded()
        ensureIdMapLoaded()
        ensureApiSportsTeamIds()
        ensureTrophiesLoaded()
        ensureAgeMapLoaded()

        val basic = findPlayerByName(playerName) ?: run {
            // 从球队列表找
            var found: PlayerData? = null
            if (teamName != null) {
                val teamPlayers = getTeamPlayers(teamName)
                found = teamPlayers.find { it.name.equals(playerName, ignoreCase = true) }
            }
            found ?: return PlayerProfile(name = playerName, nameCn = playerName)
        }

        // ID映射（精确匹配优先，再尝试包含匹配）
        val playerNameLower = basic.name.lowercase()
        val nameBasedMapping = idMapCache?.get(playerNameLower) ?:
            idMapCache?.entries?.filter { (k, _) ->
                k.contains(playerNameLower) || playerNameLower.contains(k)
            }?.sortedByDescending { (k, _) -> k.length }
            ?.firstOrNull()?.value

        // 如果名匹配失败，尝试用 api_sports_id 从 photo_url 反查
        val apiSportsIdFromPhoto = basic.photoUrl?.let {
            Regex("""/players/(\d+)\.png""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        val apiBasedMapping = if (nameBasedMapping == null && apiSportsIdFromPhoto != null) {
            idMapByApiSportsId?.get(apiSportsIdFromPhoto)
        } else null

        val idMapping = nameBasedMapping ?: apiBasedMapping

        val personId = idMapping?.personId
        val apiSportsId = idMapping?.apiSportsId ?: apiSportsIdFromPhoto

            // 照片解析（三层降级）：CDN URL → api_sports_id构造 → photo_map查找
            val resolvedPhotoUrl = resolvePhotoUrl(
                playerName = basic.name,
                apiSportsId = apiSportsId,
                existingPhotoUrl = basic.photoUrl
            )

        // 球队 api-sports ID（精确匹配优先）
        val teamApiId = apiSportsTeamIdCache?.get(basic.teamName) ?:
            apiSportsTeamIdCache?.entries?.firstOrNull { (k, _) ->
                k.equals(basic.teamName, ignoreCase = true) ||
                basic.teamName.contains(k, ignoreCase = true) ||
                k.contains(basic.teamName, ignoreCase = true)
            }?.value

        // 第2步：并行拉取 API 数据
        var wcAgg: FootballAggregations? = null
        var seasonStats: PlayerSeasonStats? = null
        var matchHistories: List<PlayerMatchProfile> = emptyList()
        var advancedStats: PlayerAdvancedStats? = null
        var shotMapResult: ShotMap? = null

        coroutineScope {
            // 2a: football-data 累计统计（season=2026 确保只限本届世界杯）
            val wcJob = async {
                try {
                    if (personId != null) {
                        val resp = withContext(Dispatchers.IO) {
                            LiveApiClient.footballData.getPersonMatches(personId)
                        }
                        val apiAgg = resp.aggregations

                        // 安全性检查: 如果 API 返回的匹配数不合理(>7)，说明包含历史比赛
                        // 尝试从 raw matches 列表中过滤 2026 日期做本地校正
                        val rawMatches = resp.matches
                        val wc2026Matches = rawMatches.filter { m ->
                            m.utcDate.startsWith("2026-06") || m.utcDate.startsWith("2026-07")
                        }
                        if (wc2026Matches.isNotEmpty() && apiAgg != null) {
                            // 以本地日期过滤为准修正 matchesOnPitch
                            val localCount = wc2026Matches.size
                            if (apiAgg.matchesOnPitch > 7) {
                                Log.w(TAG, "${basic.name}: API aggregations 包含历史数据 (${apiAgg.matchesOnPitch}场)，用本地日期过滤校正为 $localCount 场")
                                apiAgg.copy(
                                    matchesOnPitch = localCount.coerceAtMost(7),
                                    startingXI = apiAgg.startingXI.coerceAtMost(localCount),
                                    minutesPlayed = apiAgg.minutesPlayed.coerceAtMost(localCount * 90)
                                )
                            } else {
                                apiAgg
                            }
                        } else {
                            apiAgg
                        }
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "世界杯累计数据 API 失败: ${basic.name}", e)
                    null
                }
            }

            // 2b: api-sports 赛季统计
            val seasonJob = async {
                try {
                    if (teamApiId != null) {
                        val resp = withContext(Dispatchers.IO) {
                            LiveApiClient.apiSports.getPlayersByTeam(teamApiId)
                        }
                        // 在当前球队数据中找到该球员（精准度优先）
                        // API 返回的名字是缩写如 "M. Maignan", "L. Messi"
                        // players_2026 中是全名如 "Mike Maignan", "Lionel Messi"
                        val playerEntry = resp.response.find { p ->
                            p.player?.name?.let { apiName ->
                                val nl = apiName.lowercase()
                                val bl = basic.name.lowercase()

                                // 策略1: 精确匹配
                                if (nl == bl) return@let true

                                // 策略2: 缩写展开匹配
                                // 先标准化 Jr.→Junior, 去重音
                                val nlClean = nl.replace(".", "")
                                    .replace(" jr ", " junior ")
                                    .replace(" jr.", " junior")
                                    .trim()
                                    .replace("  ", " ")  // collapse double spaces
                                val blClean = bl.replace(".", "")
                                    .replace(" jr ", " junior ")
                                    .replace(" jr.", " junior")
                                    .trim()
                                    .replace("  ", " ")
                                val parts = nlClean.split(" ").filter { it.isNotEmpty() }
                                if (parts.size >= 2) {
                                    val apiFi = parts[0].firstOrNull()
                                    val apiLn = parts.last()
                                    val blParts = blClean.split(" ").filter { it.isNotEmpty() }
                                    if (blParts.size >= 2) {
                                        val blFi = blParts[0].firstOrNull()
                                        val blLn = blParts.last()
                                        // 首字母 + 姓氏匹配
                                        if (apiFi == blFi && apiLn == blLn) return@let true
                                    }
                                    // 对于单名姓氏特别处理: 姓氏匹配且全名包含名字首字母
                                    if (blParts.size >= 2) {
                                        val blLn = blParts.last()
                                        if (apiLn == blLn && blClean.startsWith(apiFi.toString())) return@let true
                                    }
                                }

                                // 策略3: 去点号后的包含匹配（用已清洗的 nlClean/blClean）
                                val nlc = nlClean.replace(" ", "")
                                val blc = blClean.replace(" ", "")
                                if (blc.contains(nlc) || nlc.contains(blc)) return@let true

                                return@let false
                            } ?: false
                        }
                        playerEntry?.let { toSeasonStats(it) }
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "赛季统计 API 失败: ${basic.name}", e)
                    null
                }
            }

            // 2c: BDL 射门分布图（赛后数据）
            val shotMapJob = async {
                try {
                    if (teamName != null && basic.teamFifaCode.isNotEmpty()) {
                        fetchShotMap(basic.name, basic.teamName)
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "射门分布图加载失败: ${basic.name}", e)
                    null
                }
            }

            wcAgg = wcJob.await()
            seasonStats = seasonJob.await()
            shotMapResult = shotMapJob.await()
        }

        // 第3步：荣誉
        val honors = apiSportsId?.let { sid ->
            trophiesCache?.get(sid.toString()) ?: emptyList()
        } ?: emptyList()

        // 第3.5步：年龄/身高
        ensureAgeMapLoaded()
        val ageInfo = ageMap?.get(basic.name)
        val playerAge = ageInfo?.get("age") as? Double ?: ageInfo?.get("age") as? Int ?: null
        val height = ageInfo?.get("height_cm") as? Double ?: ageInfo?.get("height_cm") as? Int ?: null

        // 第4步：组装 PlayerProfile
        val profile = PlayerProfile(
            // 基础信息
            name = basic.name,
            nameCn = basic.nameCn.ifEmpty { idMapping?.nameCn ?: basic.name },
            jerseyNumber = basic.jerseyNumber,
            position = basic.position,
            positionCn = when (basic.position) {
                "GK", "Goalkeeper" -> "门将"
                "DF", "Defender" -> "后卫"
                "MF", "Midfielder" -> "中场"
                "FW", "Forward" -> "前锋"
                "ATT" -> "前锋"
                else -> basic.position
            },
            teamName = basic.teamName,
            teamNameCn = basic.teamNameCn,
            teamFifaCode = basic.teamFifaCode,
            club = basic.club,
            photoUrl = resolvedPhotoUrl,
            injured = basic.injured,
            marketValueMil = basic.marketValueMil,
            id = basic.jerseyNumber,
            age = playerAge?.toInt(),
            heightCm = height?.toInt(),

            // ID映射
            personId = personId,
            apiSportsId = apiSportsId,
            apiSportsTeamId = teamApiId,

            // 世界杯累计
            wcMatchesOnPitch = wcAgg?.matchesOnPitch ?: 0,
            wcStartingXI = wcAgg?.startingXI ?: 0,
            wcMinutesPlayed = wcAgg?.minutesPlayed ?: 0,
            wcGoals = wcAgg?.goals ?: 0,
            wcOwnGoals = wcAgg?.ownGoals ?: 0,
            wcAssists = wcAgg?.assists ?: 0,
            wcPenalties = wcAgg?.penalties ?: 0,
            wcSubbedIn = wcAgg?.subbedIn ?: 0,
            wcSubbedOut = wcAgg?.subbedOut ?: 0,
            wcYellowCards = wcAgg?.yellowCards ?: 0,
            wcRedCards = wcAgg?.redCards ?: 0,

            // 详细统计
            seasonStats = seasonStats,
            matchHistories = matchHistories,
            advancedStats = advancedStats,

            // 荣誉
            honors = honors,

            // 射门分布图
            shotMap = shotMapResult,
        )

        profileCache[cacheKey] = profile
        Log.d(TAG, "球员资料加载完成: ${profile.nameCn} (${profile.name})")
        return profile
    }

    // ========================================================================
    // 数据转换
    // ========================================================================

    /** ApiSportsSeasonPlayer → PlayerSeasonStats */
    private fun toSeasonStats(player: ApiSportsSeasonPlayer): PlayerSeasonStats? {
        val stats = player.statistics.firstOrNull() ?: return null
        val games = stats.games
        val goals = stats.goals
        val passes = stats.passes
        val tackles = stats.tackles
        val duels = stats.duels
        val shots = stats.shots
        val dribbles = stats.dribbles
        val fouls = stats.fouls
        val cards = stats.cards

        val ratingStr = games?.rating
        val rating = try { ratingStr?.toDouble() } catch (_: Exception) { null }

        return PlayerSeasonStats(
            rating = rating,
            appearances = games?.appearences ?: 0,
            minutes = games?.minutes ?: 0,
            goals = goals?.total ?: 0,
            assists = goals?.assists ?: 0,
            shotsTotal = shots?.total ?: 0,
            shotsOnTarget = shots?.on ?: 0,
            passesTotal = passes?.total ?: 0,
            passesKey = passes?.key ?: 0,
            passesAccuracy = passes?.accuracy?.let {
                try { it.toInt() } catch (_: Exception) { 0 }
            } ?: 0,
            tacklesTotal = tackles?.total ?: 0,
            tacklesBlocks = tackles?.blocks ?: 0,
            interceptions = tackles?.interceptions ?: 0,
            duelsTotal = duels?.total ?: 0,
            duelsWon = duels?.won ?: 0,
            dribblesSuccess = dribbles?.success ?: 0,
            foulsDrawn = fouls?.drawn ?: 0,
            foulsCommitted = fouls?.committed ?: 0,
            yellowCards = cards?.yellow ?: 0,
            redCards = cards?.red ?: 0,
        )
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    /** 清除缓存（强制重新加载） */
    fun clearCache() {
        profileCache.clear()
        playersCache = null
        playerNameIndex = null
        idMapCache = null
        idMapByPersonId = null
        idMapByApiSportsId = null
        apiSportsTeamIdCache = null
        trophiesCache = null
        photoLookup = null
    }

    /** 从 BDL match_shots 提取球员射门分布图数据 */
    private suspend fun fetchShotMap(playerName: String, teamName: String): ShotMap? {
        return try {
            // BDL match_shots 每次只查少量 match_ids 最稳定
            // 并行拉取 1-52 号比赛 (覆盖全部小组赛)
            val allShots = mutableListOf<worldcup.helper.network.BdlShot>()
            coroutineScope {
                val batchSize = 4
                val ranges = (1..52).chunked(batchSize)
                val deferred = ranges.map { ids ->
                    async {
                        try {
                            withContext(Dispatchers.IO) {
                                LiveApiClient.bdlApi.getMatchShots(ids)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "match_shots batch ${ids.first()}..${ids.last()} failed", e)
                            BdlShotListResponse(emptyList())
                        }
                    }
                }
                deferred.forEach { d -> allShots.addAll(d.await().data) }
            }

            Log.d(TAG, "match_shots total: ${allShots.size} records")

            // 按球员名匹配
            val shots = allShots
                .filter { s -> s.player_name != null && matchesPlayerName(s.player_name ?: "", playerName) }
                .mapNotNull { s ->
                    val x = s.player_x?.toFloat() ?: return@mapNotNull null
                    val y = s.player_y?.toFloat() ?: return@mapNotNull null
                    Shot(
                        matchId = s.match_id ?: 0,
                        x = x,
                        y = y,
                        xg = s.xg?.toFloat() ?: 0f,
                        isGoal = s.is_goal,
                        minute = s.minute ?: 0,
                        bodyPart = s.body_part ?: "",
                        shotType = s.shot_type ?: ""
                    )
                }

            if (shots.isEmpty()) {
                Log.d(TAG, "$playerName: 无射门数据 (所有比赛无该球员记录)")
                return null
            }

            val totalXg = shots.sumOf { it.xg.toDouble() }.toFloat()
            Log.d(TAG, "$playerName 射门: ${shots.size}次, ${shots.count{it.isGoal}}球, xG=${String.format("%.2f", totalXg)}")
            ShotMap(
                shots = shots,
                totalXg = totalXg,
                totalShots = shots.size,
                totalGoals = shots.count { it.isGoal },
                matchCount = shots.map { it.matchId }.distinct().size
            )
        } catch (e: Exception) {
            Log.w(TAG, "射门分布图加载失败: $playerName — ${e.message}", e)
            null
        }
    }

    /** 缩写名 ↔ 全名匹配（如 "M. Maignan" vs "Mike Maignan"） */
    private fun matchesPlayerName(shotName: String, playerName: String): Boolean {
        // Jr. → Junior 标准化
        val sn = shotName.replace(".", "")
            .replace(" jr ", " junior ")
            .replace(" jr.", " junior").trim()
            .replace("  ", " ").lowercase()
        val pn = playerName.replace(".", "")
            .replace(" jr ", " junior ")
            .replace(" jr.", " junior").trim()
            .replace("  ", " ").lowercase()
        if (sn == pn) return true
        val sParts = sn.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val pParts = pn.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (sParts.size >= 2 && pParts.size >= 2) {
            return sParts[0].firstOrNull() == pParts[0].firstOrNull() &&
                   sParts.last() == pParts.last()
        }
        return sn.contains(pn) || pn.contains(sn)
    }

}
