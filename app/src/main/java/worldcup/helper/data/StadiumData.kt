package worldcup.helper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地场馆数据（来自 bdl_stadiums.json）
 * 16座 2026 世界杯球场信息
 */
class StadiumData(context: Context) {

    data class Stadium(
        val id: Int,
        val name: String,
        val city: String,
        val country: String,
        val capacity: Int
    )

    private val stadiums: List<Stadium>

    init {
        stadiums = try {
            val json = context.assets.open("bdl_stadiums.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val data = root.optJSONArray("data") ?: JSONArray()
            val list = mutableListOf<Stadium>()
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                list.add(Stadium(
                    id = item.optInt("id", 0),
                    name = item.optString("name", ""),
                    city = item.optString("city", ""),
                    country = item.optString("country", ""),
                    capacity = item.optInt("capacity", 0)
                ))
            }
            list
        } catch (e: Exception) {
            android.util.Log.e("StadiumData", "加载场馆数据失败", e)
            emptyList()
        }
    }

    /** 搜索场馆（按名称或城市） */
    fun searchStadiums(query: String): List<Stadium> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return stadiums
        return stadiums.filter {
            it.name.lowercase().contains(q) ||
            it.city.lowercase().contains(q) ||
            it.country.lowercase().contains(q)
        }
    }

    /** 按场馆名称模糊查找（返回第一个匹配） */
    fun findStadium(name: String): Stadium? {
        if (name.isEmpty()) return null
        val q = name.lowercase().trim()
        return stadiums.firstOrNull {
            it.name.lowercase().contains(q) || q.contains(it.name.lowercase())
        }
    }

    /** 获取所有场馆文本摘要 */
    fun getAllStadiumsSummary(): String {
        return buildString {
            appendLine("【2026世界杯场馆】共${stadiums.size}座")
            stadiums.forEach { s ->
                val countryFlag = when (s.country) {
                    "USA" -> "🇺🇸"; "CAN" -> "🇨🇦"; "MEX" -> "🇲🇽"
                    else -> ""
                }
                appendLine("• ${s.name} — $countryFlag ${s.city}（容量: ${s.capacity}席）")
            }
        }
    }
}
