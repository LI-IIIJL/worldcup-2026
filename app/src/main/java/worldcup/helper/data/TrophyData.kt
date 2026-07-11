package worldcup.helper.data

import android.content.Context
import org.json.JSONObject

/**
 * 球员荣誉数据（来自 trophies_cache.json）
 * 780+ 名球员的生涯荣誉
 */
class TrophyData(context: Context) {

    data class Trophy(
        val league: String = "",
        val country: String = "",
        val season: String = "",
        val place: String = ""
    )

    data class PlayerTrophies(
        val name: String = "",
        val team: String = "",
        val trophies: List<Trophy> = emptyList()
    )

    /** key = api_sports_id 字符串 */
    private val cache: Map<String, PlayerTrophies>

    init {
        cache = try {
            val json = context.assets.open("trophies_cache.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val map = mutableMapOf<String, PlayerTrophies>()
            for (key in root.keys()) {
                val item = root.getJSONObject(key)
                val trophiesArray = item.optJSONArray("trophies") ?: continue
                val trophies = mutableListOf<Trophy>()
                for (i in 0 until trophiesArray.length()) {
                    val t = trophiesArray.getJSONObject(i)
                    trophies.add(Trophy(
                        league = t.optString("league", ""),
                        country = t.optString("country", ""),
                        season = t.optString("season", ""),
                        place = t.optString("place", "")
                    ))
                }
                map[key] = PlayerTrophies(
                    name = item.optString("name", ""),
                    team = item.optString("team", ""),
                    trophies = trophies
                )
            }
            map
        } catch (e: Exception) {
            android.util.Log.e("TrophyData", "加载荣誉数据失败", e)
            emptyMap()
        }
    }

    /** 通过 api_sports_id 查询球员荣誉 */
    fun getTrophies(apiSportsId: Int): PlayerTrophies? {
        return cache[apiSportsId.toString()]
    }

    /** 获取荣誉摘要文本 */
    fun getTrophiesSummary(apiSportsId: Int): String {
        val pt = getTrophies(apiSportsId) ?: return ""
        val winnerTrophies = pt.trophies.filter { it.place.contains("Winner", ignoreCase = true) }
        return buildString {
            appendLine("【${pt.name} 荣誉】")
            if (winnerTrophies.isNotEmpty()) {
                appendLine("🏆 冠军荣誉（${winnerTrophies.size}项）:")
                winnerTrophies.forEach { t ->
                    appendLine("  • ${t.league}（${t.season}）")
                }
            }
            val others = pt.trophies.filter { !it.place.contains("Winner", ignoreCase = true) }
            if (others.isNotEmpty()) {
                appendLine("其他成就（${others.size}项）:")
                others.forEach { t ->
                    appendLine("  • ${t.league} — ${t.place}（${t.season}）")
                }
            }
        }
    }
}
