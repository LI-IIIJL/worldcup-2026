package worldcup.helper.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 比赛数据模型，从 assets/matches.json 加载。
 * 支持比赛状态检测（进行中/已结束/即将开始）。
 */
class MatchData(context: android.content.Context) {

    data class Match(
        val id: String,
        val homeTeam: String,
        val homeTeamCn: String,
        val awayTeam: String,
        val awayTeamCn: String,
        @SerializedName("homeFifaCode") val homeFifa: String = "",
        @SerializedName("awayFifaCode") val awayFifa: String = "",
        val group: String? = null,
        val matchday: Int? = null,
        val round: String,
        val type: String,
        val datetime: String,
        val stadium: String = "",
        val stadiumCity: String = "",
        val status: String = "SCHEDULED",
        val homeScore: Int = 0,
        val awayScore: Int = 0,
        @SerializedName("halfTimeHome") val htHome: Int? = null,
        @SerializedName("halfTimeAway") val htAway: Int? = null
    ) {
        val home: String get() = homeTeamCn
        val away: String get() = awayTeamCn
        val date: String get() = try { datetime.substring(0, 10) } catch (e: Exception) { "" }
        val time: String get() = try { datetime.substring(11, 16) } catch (e: Exception) { "" }
        val venue: String get() = if (stadiumCity.isNotEmpty()) "$stadiumCity $stadium" else stadium
        val isGroupStage: Boolean get() = type == "group"
    }

    enum class Status { LIVE, FINISHED, UPCOMING }

    val matches: List<Match> = try {
        val json = context.assets.open("matches.json")
            .bufferedReader().use { it.readText() }
        val gson = Gson()
        val arr = gson.fromJson(json, Array<Match>::class.java)
        arr.toList()
    } catch (e: Exception) {
        android.util.Log.e("MatchData", "加载比赛数据失败", e)
        emptyList()
    }

    fun getStatus(match: Match): Status {
        // 北京时间实时判断比赛状态
        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val matchTime = parseMatchTime(match.datetime) ?: return when (match.status) {
            "FINISHED" -> Status.FINISHED
            "LIVE", "IN_PLAY" -> Status.LIVE
            else -> Status.UPCOMING
        }
        val diffMs = now.timeInMillis - matchTime.timeInMillis
        val diffMin = TimeUnit.MILLISECONDS.toMinutes(diffMs)

        // 如果JSON明确标记为FINISHED，尊重数据
        if (match.status == "FINISHED") return Status.FINISHED

        return when {
            diffMin < 0 -> Status.UPCOMING           // 未开始
            diffMin in 0..125 -> Status.LIVE          // 比赛中（含加时）
            else -> Status.FINISHED                    // 已结束
        }
    }

    private fun parseMatchTime(datetime: String): Calendar? {
        return try {
            // 格式: 2026-06-23T11:00:00+08:00
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            cal.set(Calendar.YEAR, datetime.substring(0, 4).toInt())
            cal.set(Calendar.MONTH, datetime.substring(5, 7).toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, datetime.substring(8, 10).toInt())
            cal.set(Calendar.HOUR_OF_DAY, datetime.substring(11, 13).toInt())
            cal.set(Calendar.MINUTE, datetime.substring(14, 16).toInt())
            cal.set(Calendar.SECOND, 0)
            cal
        } catch (_: Exception) { null }
    }

    /** 获取今天有比赛的日期标签 */
    fun getTodayDateLabel(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val weekDays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        return "${m}月${d}日 ${weekDays[dayOfWeek - 1]}"
    }

    /** 获取今天的日期字符串 yyyy-MM-dd */
    fun getTodayDateStr(): String = formatDate(0)

    /** 获取 N 天前的日期字符串 yyyy-MM-dd */
    fun getDateDaysAgo(daysAgo: Int): String = formatDate(-daysAgo)

    private fun formatDate(dayOffset: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.add(Calendar.DAY_OF_MONTH, dayOffset)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val mm = if (m < 10) "0$m" else "$m"
        val dd = if (d < 10) "0$d" else "$d"
        return "${cal.get(Calendar.YEAR)}-$mm-$dd"
    }

    fun getDateLabel(match: Match): String {
        return try {
            val parts = match.date.split("-")
            val m = parts[1].toInt(); val d = parts[2].toInt()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            cal.set(java.util.Calendar.YEAR, 2026)
            cal.set(java.util.Calendar.MONTH, m - 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, d)
            val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
            val weekDays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
            "${m}月${d}日 ${weekDays[dayOfWeek - 1]}"
        } catch (e: Exception) {
            match.date
        }
    }

    fun getSortKey(match: Match): String {
        val order = when (getStatus(match)) {
            Status.LIVE -> "0"
            Status.FINISHED -> "1"
            Status.UPCOMING -> "2"
        }
        return "$order-${match.datetime}"
    }

    companion object {
        /** 中文化队名（用于数据中没有中文名的情况） */
        val chineseNames: Map<String, String> = mapOf(
            "Mexico" to "墨西哥", "South Africa" to "南非", "South Korea" to "韩国",
            "Korea Republic" to "韩国",
            "Czech Republic" to "捷克", "Czechia" to "捷克",
            "Canada" to "加拿大", "Bosnia and Herzegovina" to "波黑", "Bosnia" to "波黑",
            "Qatar" to "卡塔尔", "Switzerland" to "瑞士",
            "Brazil" to "巴西", "Morocco" to "摩洛哥", "Haiti" to "海地", "Scotland" to "苏格兰",
            "United States" to "美国", "USA" to "美国", "Paraguay" to "巴拉圭",
            "Australia" to "澳大利亚", "Turkey" to "土耳其",
            "Germany" to "德国", "Curaçao" to "库拉索", "Curacao" to "库拉索",
            "Ivory Coast" to "科特迪瓦", "Ecuador" to "厄瓜多尔",
            "Netherlands" to "荷兰", "Japan" to "日本", "Sweden" to "瑞典", "Tunisia" to "突尼斯",
            "Belgium" to "比利时", "Egypt" to "埃及", "Iran" to "伊朗",
            "New Zealand" to "新西兰", "Spain" to "西班牙", "Cape Verde" to "佛得角",
            "Saudi Arabia" to "沙特阿拉伯", "Uruguay" to "乌拉圭",
            "France" to "法国", "Senegal" to "塞内加尔", "Iraq" to "伊拉克", "Norway" to "挪威",
            "Argentina" to "阿根廷", "Algeria" to "阿尔及利亚", "Austria" to "奥地利",
            "Jordan" to "约旦", "Portugal" to "葡萄牙",
            "Democratic Republic of the Congo" to "刚果(金)", "DR Congo" to "刚果(金)",
            "Uzbekistan" to "乌兹别克斯坦", "Colombia" to "哥伦比亚",
            "England" to "英格兰", "Croatia" to "克罗地亚",
            "Ghana" to "加纳", "Panama" to "巴拿马"
        )

        fun getChineseName(teamName: String): String = chineseNames[teamName] ?: teamName

        private val countryCodes: Map<String, String> = mapOf(
            "Mexico" to "MX", "South Africa" to "ZA", "South Korea" to "KR",
            "Czech Republic" to "CZ", "Czechia" to "CZ",
            "Canada" to "CA", "Bosnia and Herzegovina" to "BA", "Bosnia" to "BA",
            "Qatar" to "QA", "Switzerland" to "CH",
            "Brazil" to "BR", "Morocco" to "MA", "Haiti" to "HT", "Scotland" to "XS",
            "United States" to "US", "USA" to "US", "Paraguay" to "PY",
            "Australia" to "AU", "Turkey" to "TR",
            "Germany" to "DE", "Curaçao" to "CW", "Curacao" to "CW",
            "Ivory Coast" to "CI", "Ecuador" to "EC",
            "Netherlands" to "NL", "Japan" to "JP", "Sweden" to "SE", "Tunisia" to "TN",
            "Belgium" to "BE", "Egypt" to "EG", "Iran" to "IR",
            "New Zealand" to "NZ", "Spain" to "ES", "Cape Verde" to "CV",
            "Saudi Arabia" to "SA", "Uruguay" to "UY",
            "France" to "FR", "Senegal" to "SN", "Iraq" to "IQ", "Norway" to "NO",
            "Argentina" to "AR", "Algeria" to "DZ", "Austria" to "AT", "Jordan" to "JO",
            "Portugal" to "PT", "Democratic Republic of the Congo" to "CD", "DR Congo" to "CD",
            "Uzbekistan" to "UZ", "Colombia" to "CO",
            "England" to "GB-ENG", "Croatia" to "HR",
            "Ghana" to "GH", "Panama" to "PA"
        )

        fun getCountryCode(teamName: String): String = countryCodes[teamName] ?: ""
    }
}
