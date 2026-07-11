package worldcup.helper.ui.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.json.JSONObject
import worldcup.helper.BuildConfig
import worldcup.helper.ai.DeepSeekClient
import worldcup.helper.data.MatchData
import worldcup.helper.data.PlayerDatabase
import worldcup.helper.data.PlayerInfo
import worldcup.helper.data.TrophyData
import worldcup.helper.data.repos.MatchRepo
import worldcup.helper.data.repos.SharedRepository
import worldcup.helper.network.LiveApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AI 对话 ViewModel
 *
 * 核心设计：GREETING + FAQ 命中走本地（秒回），
 * 其余全部走 DeepSeek + 数据注入，让 AI 自然回答。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // ==================== 核心组件 ====================
    private val intentEngine = IntentEngine()
    private val faqKnowledge = FaqKnowledge(application)
    private val deepSeekClient = DeepSeekClient(apiKey = BuildConfig.DEEPSEEK_API_KEY)
    private val chatSession = ChatSession()

    // 数据源（通过 SharedRepository 统一获取）
    private var matchData: MatchData? = null
    private var playerDatabase: PlayerDatabase? = null
    /** 中文球员名 -> api_sports_id 映射 */
    private val nameToApiId = mutableMapOf<String, Int>()
    /** API 实时比分缓存（API优先，覆盖本地JSON） */
    private var apiScoreMap: Map<String, MatchRepo.ScoreInfo> = emptyMap()
    /** API 实时比赛分钟（api-sports 提供，无则用本地推算） */
    private var liveClockMap: Map<String, Int> = emptyMap()

    /** SharedRepository — 全Tab统一数据源 */
    private val sharedRepo: SharedRepository by lazy {
        SharedRepository.getInstance(getApplication())
    }

    // 本地荣誉数据（🟢基础本地，不依赖API）
    private val trophyData: TrophyData by lazy { TrophyData(getApplication()) }

    // ==================== 可观测数据 ====================
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _suggestions = MutableStateFlow<List<SuggestedQuestion>>(emptyList())
    val suggestions: StateFlow<List<SuggestedQuestion>> = _suggestions.asStateFlow()

    // ==================== 初始化 ====================
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                matchData = MatchData(application)
                playerDatabase = PlayerDatabase(application)
                // 加载全量球员中文名并注入 IntentEngine
                loadPlayerNames(application)
                // 🔴 API优先: 从 football-data 拉取真实比分，覆盖本地 JSON
                val freshScores = sharedRepo.matches.fetchApiScoreMap()
                if (freshScores.isNotEmpty()) {
                    apiScoreMap = freshScores
                }
                // 🔴 API优先: 从 api-sports 拉取实时比赛分钟（上下半场真实时钟）
                try {
                    val liveResp = worldcup.helper.network.LiveApiClient.apiSports.getFixtures(live = "all")
                    val fixtureIdMapping = loadFixtureIdToLocalIdMap(application)
                    val clocks = mutableMapOf<String, Int>()
                    for (r in liveResp.response) {
                        val apiFixtureId = r.fixture.id
                        val elapsed = r.fixture.status?.elapsed
                        if (elapsed != null) {
                            val localId = fixtureIdMapping[apiFixtureId]
                            if (localId != null) {
                                clocks[localId] = elapsed
                            }
                        }
                    }
                    liveClockMap = clocks
                } catch (_: Exception) { }
            } catch (_: Exception) { }
            faqKnowledge.ensureLoaded()
            updateSuggestions()
            chatSession.addWelcomeMessage()
            notifyMessagesChanged()
        }
    }

    /** 从 players_2026.json 提取所有中文名，注入 IntentEngine */
    private fun loadPlayerNames(application: Application) {
        try {
            val json = application.assets.open("players_2026.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val teams = root.getJSONArray("teams")
            val names = mutableListOf<String>()
            for (i in 0 until teams.length()) {
                val team = teams.getJSONObject(i)
                val players = team.getJSONArray("players")
                for (j in 0 until players.length()) {
                    val player = players.getJSONObject(j)
                    if (player.has("nameCn")) {
                        val cn = player.getString("nameCn")
                        names.add(cn)
                        // 建立中文名 -> api_sports_id 映射（用于荣誉查询）
                        if (player.has("api_sports_id") && !player.isNull("api_sports_id")) {
                            val apiId = player.getInt("api_sports_id")
                            // 避免覆盖，优先保留更全的映射
                            if (apiId > 0) {
                                nameToApiId[cn] = apiId
                                // 也加上不含间隔号的短名
                                nameToApiId[cn.replace("·", "")] = apiId
                            }
                        }
                    }
                }
            }
            intentEngine.addPlayerNames(names)
        } catch (_: Exception) { }
    }

    // ==================== 公开方法 ====================

    fun sendMessage(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        chatSession.messages.add(ChatMessage(
            id = System.currentTimeMillis(), role = MessageRole.USER, text = query
        ))
        notifyMessagesChanged()
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val reply = generateReply(query)
            _isLoading.value = false
            chatSession.messages.add(reply)
            notifyMessagesChanged()
            updateSuggestions()
        }
    }

    /**
     * 添加用户上传的图片到对话
     * @param imageUri 图片的 content:// URI 字符串
     */
    fun addImageMessage(imageUri: String) {
        // 用户图片消息
        chatSession.messages.add(ChatMessage(
            id = System.currentTimeMillis(), role = MessageRole.USER,
            text = "📸 [上传图片]", imageUri = imageUri
        ))
        notifyMessagesChanged()

        // AI 回复（暂不支持图片识别，告知用户）
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = false
            chatSession.messages.add(ChatMessage(
                id = System.currentTimeMillis() + 1, role = MessageRole.AI,
                text = "📸 已收到你的图片！\n\n图片识别功能正在开发中，上线后我可以通过球衣号码识别球员身份。\n\n目前你可以试试：\n• 问我足球规则（越位、VAR等）\n• 查询比分和赛程\n• 查看预测数据"
            ))
            notifyMessagesChanged()
        }
    }

    fun requestImageUpload() { /* 由 Fragment 直接处理 */ }

    fun getContextText(): String = chatSession.getContextText()
    fun getMessageCount(): Int = chatSession.messages.size

    // ==================== 回复生成 ====================

    /**
     * 策略：
     * - GREETING / FAQ命中 → 本地秒回
     * - 其余 → DeepSeek + 数据注入
     */
    private suspend fun generateReply(query: String): ChatMessage {
        val time = System.currentTimeMillis()
        val intentResult = intentEngine.classify(query)
        return when (intentResult.intent) {
            IntentType.GREETING -> greetingReply(time)

            IntentType.RULE_QUESTION -> {
                val faq = faqKnowledge.search(query)
                when {
                    faq == null -> deepSeekReply(query, intentResult, time, faqContext = "")
                    faq.confidence >= 0.6f -> ChatMessage(
                        id = time, role = MessageRole.AI,
                        text = "⚽ **${faq.entry.question}**\n\n${faq.entry.answer}",
                        style = ResponseStyle.KNOWLEDGE_CARD
                    )
                    faq.confidence >= 0.3f -> {
                        // 中度命中：FAQ 回答 + DeepSeek 补充
                        val faqText = "用户问了关于「${faq.entry.question}」的问题，以下是我的知识库回答：\n${faq.entry.answer}\n\n请在此基础上给用户一个更完整、自然的回答，可以补充相关背景知识。"
                        val answer = deepSeekClient.chatWithData(query, faqText, chatSession.getContextText())
                        ChatMessage(id = time, role = MessageRole.AI, text = answer, style = ResponseStyle.TEXT_ONLY)
                    }
                    else -> deepSeekReply(query, intentResult, time,
                        faqContext = "用户可能想问「${faq.entry.question}」，相关知识：${faq.entry.answer.take(200)}")
                }
            }

            IntentType.PLAYER_RECOGNITION -> ChatMessage(id = time, role = MessageRole.AI, text = "📸 请点击输入框左侧的📎按钮，上传比赛截图或球员照片，我会尝试识别球衣号码和球员身份！")
            else -> deepSeekReply(query, intentResult, time, faqContext = "")
        }
    }

    private fun greetingReply(time: Long): ChatMessage {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val g = when { hour < 6 -> "🌙 夜深了"; hour < 12 -> "🌅 早上好"; hour < 14 -> "☀️ 中午好"; hour < 18 -> "🌤️ 下午好"; else -> "🌆 晚上好" }
        return ChatMessage(id = time, role = MessageRole.AI, text = "$g！我是世界杯 AI 助手 ⚽\n\n📸 截图识别 · ⚽ 足球知识 · 🔮 预测 · 📊 赛事信息\n\n试试上方的建议问题吧！👇")
    }

    // ==================== DeepSeek 智能回复 ====================

    /**
     * DeepSeek 统一入口
     * @param faqContext 可选的 FAQ 上下文（中度命中时注入，让AI补充）
     */
    private suspend fun deepSeekReply(query: String, intentResult: IntentResult, time: Long, faqContext: String = ""): ChatMessage {
        return try {
            // 动态计算当前时间信息
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.CHINA).format(cal.time)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val dayOfWeek = arrayOf("星期日","星期一","星期二","星期三","星期四","星期五","星期六")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]

            // 推断赛事阶段
            val phase = when {
                month < 6 -> "赛前准备阶段"
                month == 6 && day < 11 -> "赛前倒计时"
                month == 6 && day <= 30 -> "小组赛阶段"
                month == 7 && day <= 4 -> "1/16决赛(32强)"
                month == 7 && day <= 9 -> "1/8决赛"
                month == 7 && day <= 13 -> "1/4决赛"
                month == 7 && day <= 16 -> "半决赛"
                month == 7 && day == 18 -> "三四名决赛"
                month == 7 && day == 19 -> "🏆 决赛日"
                else -> "赛事进行中"
            }

            val timeContext = buildString {
                appendLine("【当前时间】北京时间 $now")
                appendLine("【日期】${month}月${day}日 $dayOfWeek（2026世界杯 $phase）")
                appendLine("【注意】本回答中涉及的所有比赛日期，都以上面这个「当前时间」为基准。今天是${month}月${day}日。")
            }

            val dataContext = collectDataContext(intentResult)
            val fullContext = buildString {
                append(timeContext)
                if (dataContext.isNotBlank()) append("\n$dataContext")
                if (faqContext.isNotBlank()) append("\n\n【相关知识库参考】\n$faqContext")
            }
            val context = chatSession.getContextText()
            val answer = deepSeekClient.chatWithData(query, fullContext, context)
            ChatMessage(id = time, role = MessageRole.AI, text = answer, style = ResponseStyle.TEXT_ONLY)
        } catch (_: Exception) {
            fallbackReply(time)
        }
    }

    /** 根据意图抓取相关数据 */
    private suspend fun collectDataContext(r: IntentResult): String {
        val team = r.entities["team1"] ?: r.entities["team"] ?: ""
        val team2 = r.entities["team2"] ?: ""
        val player = r.entities["player1"] ?: r.entities["player"] ?: ""
        val player2 = r.entities["player2"] ?: ""

        // 双队名 → 查该场比赛的多维度数据
        if (team.isNotEmpty() && team2.isNotEmpty()) {
            val parts = mutableListOf<String>()
            collectMatchPlayerStats(team, team2).let { if (it.isNotEmpty()) parts.add(it) }
            collectLineupWithFormation(team, team2).let { if (it.isNotEmpty()) parts.add(it) }
            collectMatchStatistics(team, team2).let { if (it.isNotEmpty()) parts.add(it) }
            collectMatchBestPlayer(team, team2).let { if (it.isNotEmpty()) parts.add(it) }
            if (parts.isNotEmpty()) return parts.joinToString("\n")
        }

        // 双球员 → 分别查各自信息（含下一场比赛），让DeepSeek判断是否相遇
        if (player.isNotEmpty() && player2.isNotEmpty()) {
            val player1Info = collectPlayerInfo(player)
            val player2Info = collectPlayerInfo(player2)
            val both = mutableListOf<String>()
            if (player1Info.isNotEmpty()) both.add(player1Info)
            if (player2Info.isNotEmpty()) both.add(player2Info)
            if (both.isNotEmpty()) return both.joinToString("\n")
        }

        val result = when (r.intent) {
            IntentType.MATCH_SCORE -> collectMatchData(team)
            IntentType.SCHEDULE_QUERY -> collectSchedule(team)
            IntentType.PREDICTION_QUERY -> collectPrediction(team)
            IntentType.PLAYER_INFO -> collectPlayerInfo(player)
            IntentType.TEAM_INFO -> collectTeamInfo(team)
            IntentType.LINEUP_QUERY -> collectLineup(team)
            IntentType.STANDINGS_QUERY -> collectStandings()
            else -> ""
        }
        // ⚠️ 重要：如果查询同时包含球员名和赛程/比分意图（如"姆巴佩踢了几场"），
        // 则除了赛程数据外，也注入该球员的基本资料，让 DeepSeek 能关联起来
        val extraPlayerInfo = if (player.isNotEmpty() && r.intent in listOf(
                IntentType.MATCH_SCORE, IntentType.SCHEDULE_QUERY,
                IntentType.PREDICTION_QUERY, IntentType.GENERAL_CHAT
            )) {
            "\n" + collectPlayerInfo(player)
        } else ""
        // 如果是通用问题且没有团队指定，仍然注入上下文让AI了解
        if (result.isEmpty() && team.isEmpty() && r.intent != IntentType.GREETING) {
            return collectSchedule("") + "\n" + collectStadiums()
        }
        return result + extraPlayerInfo
    }

    /** 获取比赛的真实比分和状态（API优先，本地兜底） */
    private fun getScoreAndStatus(match: MatchData.Match): Triple<Int, Int, String> {
        val apiScore = apiScoreMap[match.id]
        if (apiScore != null && (apiScore.homeScore > 0 || apiScore.awayScore > 0 || apiScore.status != "SCHEDULED")) {
            val clock = liveClockMap[match.id]
            val statusEmoji = when (apiScore.status) {
                "LIVE", "IN_PLAY" -> if (clock != null) "🔴${clock}′" else "🔴进行中"
                "FINISHED" -> "✅已结束"
                "PAUSED" -> "⏸️暂停"
                else -> "🟡未开始"
            }
            return Triple(apiScore.homeScore, apiScore.awayScore, statusEmoji)
        }
        // 本地兜底
        val md = matchData ?: return Triple(match.homeScore, match.awayScore, "⚪未知")
        val statusEmoji = when (md.getStatus(match)) {
            MatchData.Status.LIVE -> {
                val clock = liveClockMap[match.id]
                if (clock != null) "🔴${clock}′" else "🔴进行中"
            }
            MatchData.Status.FINISHED -> "✅已结束"
            MatchData.Status.UPCOMING -> "🟡未开始"
        }
        return Triple(match.homeScore, match.awayScore, statusEmoji)
    }

    /** 加载 api-sports fixture_id → 本地 match_id 映射 */
    private fun loadFixtureIdToLocalIdMap(application: android.app.Application): Map<Int, String> {
        return try {
            val json = application.assets.open("fixture_id_map.json").bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(json)
            val mapping = root.optJSONObject("mapping") ?: return emptyMap()
            val result = mutableMapOf<Int, String>()
            for (key in mapping.keys()) {
                val localId = key
                val apiId = mapping.optInt(key, 0)
                if (apiId > 0) result[apiId] = localId
            }
            result
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * 获取某场比赛双方队员的详细表现数据
     * 调用 api-sports fixtures/players API
     */
    private suspend fun collectMatchPlayerStats(team1: String, team2: String): String {
        val lookup = findMatchAndFixture(team1, team2) ?: return ""
        // 调 api-sports 获取双方球员数据
        return try {
            val resp = worldcup.helper.network.LiveApiClient.apiSports.getFixturePlayers(lookup.fixtureId)
            if (resp.response.isEmpty()) return ""
            buildString {
                appendLine("【${lookup.match.homeTeamCn} vs ${lookup.match.awayTeamCn} 球员表现】")
                for (teamStats in resp.response) {
                    val teamName = teamStats.team?.name ?: continue
                    appendLine("━━━ ${teamName}（${teamStats.players.size}人）━━━")
                    for (raw in teamStats.players.take(11)) {
                        val name = raw.player?.name ?: "?"
                        val stats = raw.statistics.firstOrNull() ?: continue
                        val g = stats.games
                        val rating = g?.rating?.let { r ->
                            try { "⭐${"%.1f".format(r.toDouble())}" } catch (_: Exception) { "" }
                        } ?: ""
                        val goals = stats.goals?.total ?: 0
                        val assists = stats.goals?.assists ?: 0
                        val shots = stats.shots?.total ?: 0
                        val shotsOn = stats.shots?.on ?: 0
                        val passes = stats.passes?.total ?: 0
                        val keyPasses = stats.passes?.key ?: 0
                        val tackles = stats.tackles?.total ?: 0
                        val interceptions = stats.tackles?.interceptions ?: 0
                        val duelsWon = stats.duels?.won ?: 0
                        val dribbles = stats.dribbles?.success ?: 0
                        val fouls = stats.fouls?.committed ?: 0
                        val yellow = stats.cards?.yellow ?: 0
                        val red = stats.cards?.red ?: 0
                        val minutes = g?.minutes ?: 0

                        append("• $name $rating")
                        if (goals > 0) append(" ⚽$goals")
                        if (assists > 0) append(" 🅰$assists")
                        append(" ${minutes}分钟")
                        if (shots > 0) append(" | 射门$shots($shotsOn)")
                        if (passes > 0) append(" | 传球$passes(${keyPasses}关键)")
                        if (tackles > 0) append(" | 抢断$tackles")
                        if (interceptions > 0) append(" | 拦截$interceptions")
                        if (duelsWon > 0) append(" | 对抗胜$duelsWon")
                        if (dribbles > 0) append(" | 过人次$dribbles")
                        if (fouls > 0) append(" | 犯规$fouls")
                        if (yellow > 0 || red > 0) append(" | 🟨$yellow 🟥$red")
                        appendLine()
                    }
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** 找比赛 + 查 fixture_id（三个方法的公共逻辑） */
    private data class MatchLookup(val match: MatchData.Match, val fixtureId: Int)
    private fun findMatchAndFixture(team1: String, team2: String): MatchLookup? {
        val md = matchData ?: return null
        val t1 = team1.lowercase().trim()
        val t2 = team2.lowercase().trim()
        val match = md.matches.firstOrNull { m ->
            (m.homeTeamCn.lowercase().contains(t1) || m.homeTeam.contains(t1, ignoreCase = true)) &&
            (m.awayTeamCn.lowercase().contains(t2) || m.awayTeam.contains(t2, ignoreCase = true))
        } ?: md.matches.firstOrNull { m ->
            (m.homeTeamCn.lowercase().contains(t2) || m.homeTeam.contains(t2, ignoreCase = true)) &&
            (m.awayTeamCn.lowercase().contains(t1) || m.awayTeam.contains(t1, ignoreCase = true))
        } ?: return null
        val fixtureId = try {
            val json = getApplication<android.app.Application>().assets
                .open("fixture_id_map.json").bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(json)
            root.optJSONObject("mapping")?.optInt(match.id) ?: 0
        } catch (_: Exception) { 0 }
        if (fixtureId <= 0) return null
        return MatchLookup(match, fixtureId)
    }

    /** 🔴 首发阵型 — 调 api-sports fixtures/lineups */
    private suspend fun collectLineupWithFormation(team1: String, team2: String): String {
        val lookup = findMatchAndFixture(team1, team2) ?: return ""
        return try {
            val resp = worldcup.helper.network.LiveApiClient.apiSports.getFixtureLineups(lookup.fixtureId)
            if (resp.response.isEmpty()) return ""
            buildString {
                appendLine("【${lookup.match.homeTeamCn} vs ${lookup.match.awayTeamCn} 首发阵容】")
                for (detail in resp.response) {
                    val teamName = detail.team?.name ?: continue
                    val formation = detail.formation ?: "未知"
                    appendLine("━━━ $teamName（阵型: $formation）━━━")
                    for (p in detail.startXI) {
                        val name = p.player?.name ?: "?"
                        val grid = p.grid ?: ""
                        appendLine("  # $name$grid")
                    }
                    if (detail.substitutes.isNotEmpty()) {
                        append("替补: ")
                        append(detail.substitutes.take(7).joinToString("、") { it.player?.name ?: "?" })
                        appendLine()
                    }
                }
            }
        } catch (_: Exception) { "" }
    }

    /** 🔴 比赛统计对比 — 调 api-sports fixtures/statistics */
    private suspend fun collectMatchStatistics(team1: String, team2: String): String {
        val lookup = findMatchAndFixture(team1, team2) ?: return ""
        return try {
            val resp = worldcup.helper.network.LiveApiClient.apiSports.getFixtureStatistics(lookup.fixtureId)
            if (resp.response.isEmpty()) return ""
            buildString {
                appendLine("【${lookup.match.homeTeamCn} vs ${lookup.match.awayTeamCn} 统计数据】")
                val homeStats = resp.response.firstOrNull()?.statistics ?: emptyList()
                val awayStats = resp.response.getOrNull(1)?.statistics ?: emptyList()
                for (i in 0 until minOf(homeStats.size, awayStats.size)) {
                    val type = homeStats[i].type ?: continue
                    val homeVal = homeStats[i].value
                    val awayVal = awayStats[i].value
                    val h = if (homeVal is Number) homeVal.toInt().toString() else homeVal?.toString() ?: "0"
                    val a = if (awayVal is Number) awayVal.toInt().toString() else awayVal?.toString() ?: "0"
                    appendLine("  $type: $h | $a")
                }
            }
        } catch (_: Exception) { "" }
    }

    /** 🔴 全场最佳 — 调 BDL match_best_players */
    private suspend fun collectMatchBestPlayer(team1: String, team2: String): String {
        val lookup = findMatchAndFixture(team1, team2) ?: return ""
        val matchIdInt = lookup.match.id.toIntOrNull() ?: return ""
        return try {
            val resp = worldcup.helper.network.LiveApiClient.bdlApi.getMatchBestPlayers(listOf(matchIdInt))
            val best = resp.data.firstOrNull() ?: return ""
            val playerName = best.player_name ?: "?"
            val rating = best.rating ?: "?"
            val reason = best.reason ?: ""
            buildString {
                appendLine("【${lookup.match.homeTeamCn} vs ${lookup.match.awayTeamCn} 全场最佳】")
                appendLine("  🏅 $playerName ⭐$rating")
                if (reason.isNotEmpty()) appendLine("  $reason")
            }
        } catch (_: Exception) { "" }
    }

    private fun collectMatchData(team: String): String {
        val md = matchData ?: return ""
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(cal.time)

        val matches = md.matches.filter { m ->
            m.homeTeamCn.contains(team, ignoreCase = true) || m.awayTeamCn.contains(team, ignoreCase = true) ||
            m.homeTeam.contains(team, ignoreCase = true) || m.awayTeam.contains(team, ignoreCase = true)
        }.sortedByDescending { it.datetime }.take(5)
        if (matches.isEmpty()) return ""
        return buildString {
            appendLine("【${team}相关比赛数据（基准今天=$todayStr）】")
            matches.forEach { m ->
                val (hs, aws, s) = getScoreAndStatus(m)
                val todayTag = if (m.date == todayStr) " 📌今天" else ""
                appendLine("- ${m.homeTeamCn} vs ${m.awayTeamCn}$todayTag")
                appendLine("  $s | 比分${hs}:${aws} | ${m.date} ${m.time} | ${m.round}")
            }
        }
    }

    private fun collectSchedule(team: String): String {
        val md = matchData ?: return ""
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(cal.time)
        val todayMonth = cal.get(java.util.Calendar.MONTH) + 1
        val todayDay = cal.get(java.util.Calendar.DAY_OF_MONTH)

        val matches = if (team.isNotEmpty()) md.matches.filter { m ->
            m.homeTeamCn.contains(team, ignoreCase = true) || m.awayTeamCn.contains(team, ignoreCase = true)
        }.sortedBy { it.datetime } else {
            // 无特定球队：先展示今天的，再展示最近的未来比赛
            val todayMatches = md.matches.filter { it.date == todayStr }.sortedBy { it.datetime }
            val upcoming = md.matches.filter { it.date > todayStr && md.getStatus(it) == MatchData.Status.UPCOMING }.sortedBy { it.datetime }.take(10)
            todayMatches + upcoming
        }

        if (matches.isEmpty()) return ""

        return buildString {
            appendLine("【赛程数据（基准今天=$todayStr）】")
            matches.forEach { m ->
                val (hs, aws, status) = getScoreAndStatus(m)
                val dateLabel = try {
                    val p = m.date.split("-")
                    val mMonth = p[1].toInt(); val mDay = p[2].toInt()
                    val relative = when {
                        m.date == todayStr -> "📌今天"
                        m.date == getTomorrow(todayStr) -> "📌明天"
                        else -> "${mMonth}月${mDay}日"
                    }
                    relative
                } catch (_: Exception) { m.date }
                if (hs > 0 || aws > 0 || status.contains("结束")) {
                    appendLine("- $dateLabel ${m.time} ${m.homeTeamCn} ${hs}:${aws} ${m.awayTeamCn} ($status · ${m.round})")
                } else {
                    appendLine("- $dateLabel ${m.time} ${m.homeTeamCn} vs ${m.awayTeamCn} ($status · ${m.round})")
                }
            }
        }
    }

    /** 获取明天的日期字符串 yyyy-MM-dd */
    private fun getTomorrow(today: String): String {
        try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val date = fmt.parse(today) ?: return ""
            cal.time = date; cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return fmt.format(cal.time)
        } catch (_: Exception) { return "" }
    }

    private fun collectPrediction(team: String): String {
        return try {
            val app = getApplication<android.app.Application>()
            val predData = worldcup.helper.data.PredictionData(app)
            val md = matchData ?: return ""
            val match = md.matches.firstOrNull { m ->
                (m.homeTeamCn.contains(team, ignoreCase = true) || m.awayTeamCn.contains(team, ignoreCase = true)) &&
                md.getStatus(m) != MatchData.Status.FINISHED
            } ?: return ""
            val mid = match.id.toIntOrNull() ?: return ""
            val pred = predData.getPrediction(mid) ?: return ""
            buildString {
                appendLine("【${match.homeTeamCn} vs ${match.awayTeamCn} 预测】")
                appendLine("${pred.teamA.cnName}胜率${pred.teamA.winProb}% | 平局${pred.draw}% | ${pred.teamB.cnName}胜率${pred.teamB.winProb}%")
                appendLine("预测比分: ${pred.mostLikelyScore} | 置信度: ${pred.confidence}")
                if (pred.keyFactors.isNotEmpty()) { appendLine("关键因素:"); pred.keyFactors.forEach { appendLine("  • $it") } }
                if (pred.playersToWatch.isNotEmpty()) { appendLine("关注球员:"); pred.playersToWatch.forEach { appendLine("  • ${it.player} (${it.team}) — ${it.reason}") } }
            }
        } catch (_: Exception) { "" }
    }

    private suspend fun collectPlayerInfo(player: String): String {
        val db = playerDatabase ?: return ""
        val md = matchData
        // 先按传入的文本搜，如果搜不到尝试用 nameToApiId 的反向查找
        var players = db.searchByName(player)
        if (players.isEmpty()) {
            // 尝试去掉间隔号、"·"、空格等再搜
            val cleaned = player.replace("·", "").replace(" ", "").replace("-", "")
            players = db.searchByName(cleaned)
        }
        if (players.isEmpty()) return ""

        val first = players.first()
        val teamName = first.teamName

        val result = buildString {
            appendLine("【${first.name} 球员资料】")
            players.take(3).forEach { p ->
                appendLine("- #${p.jerseyNumber} ${p.name} | ${posToCn(p.position)} | ${p.teamName}")
                if (p.club.isNotBlank()) appendLine("  俱乐部: ${p.club}")
            }
            // 附加该球员所在球队的已赛战绩
            if (md != null) {
                val teamMatches = md.matches.filter { m ->
                    m.homeTeamCn.contains(teamName, ignoreCase = true) ||
                    m.awayTeamCn.contains(teamName, ignoreCase = true) ||
                    m.homeTeam.contains(teamName, ignoreCase = true) ||
                    m.awayTeam.contains(teamName, ignoreCase = true)
                }
                val finished = teamMatches.filter { md.getStatus(it) == MatchData.Status.FINISHED }
                if (finished.isNotEmpty()) {
                    val apiFinished = finished.map { m ->
                        val (hs, aws, _) = getScoreAndStatus(m)
                        Triple(m, hs, aws)
                    }
                    appendLine("【${teamName} 已赛${apiFinished.size}场】")
                    apiFinished.sortedBy { it.first.datetime }.forEach { (m, hs, aws) ->
                        val isHome = m.homeTeamCn.contains(teamName, ignoreCase = true) || m.homeTeam.contains(teamName, ignoreCase = true)
                        val ourScore = if (isHome) hs else aws
                        val theirScore = if (isHome) aws else hs
                        val opponent = if (isHome) m.awayTeamCn else m.homeTeamCn
                        val resultEmoji = when {
                            ourScore > theirScore -> "✅胜"
                            ourScore == theirScore -> "🤝平"
                            else -> "❌负"
                        }
                        appendLine("  $resultEmoji ${opponent} ${ourScore}:${theirScore} | ${m.date}")
                    }
                }
                // 附加该队**下一场比赛**（防止AI瞎猜未来赛程）
                if (md != null) {
                    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
                    val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(cal.time)
                    val nextMatch = md.matches.filter { m ->
                        (m.homeTeamCn.contains(teamName, ignoreCase = true) || m.awayTeamCn.contains(teamName, ignoreCase = true) ||
                         m.homeTeam.contains(teamName, ignoreCase = true) || m.awayTeam.contains(teamName, ignoreCase = true)) &&
                        m.date >= todayStr && md.getStatus(m) != MatchData.Status.FINISHED
                    }.sortedBy { it.datetime }.firstOrNull()
                    if (nextMatch != null) {
                        val (nHs, nAw, nStatus) = getScoreAndStatus(nextMatch)
                        val opponent = if (nextMatch.homeTeamCn.contains(teamName, ignoreCase = true) || nextMatch.homeTeam.contains(teamName, ignoreCase = true))
                            nextMatch.awayTeamCn else nextMatch.homeTeamCn
                        appendLine("【下一场】${nextMatch.date} ${nextMatch.time} vs $opponent（$nStatus）")
                    }
                }
            }
        }

        // 🔴 附加球员详细表现数据（通过 SharedRepository 调 API）
        val playerName = first.name
        if (playerName.isNotBlank()) {
            try {
                // 同步等待 getProfile（协程已在 Dispatchers.IO）
                val profile = sharedRepo.players.getProfile(playerName)
                if (profile.wcMatchesOnPitch > 0 || profile.wcGoals > 0 || profile.seasonStats != null) {
                    val perf = buildString {
                        appendLine()
                        appendLine("【${profile.nameCn.ifEmpty { profile.name }} 世界杯表现】")
                        if (profile.wcMatchesOnPitch > 0) {
                            appendLine("• 出场 ${profile.wcMatchesOnPitch} 场（首发${profile.wcStartingXI}次）")
                            appendLine("• 进球 ${profile.wcGoals} | 助攻 ${profile.wcAssists}")
                            appendLine("• 出场总时间 ${profile.wcMinutesPlayed} 分钟")
                            if (profile.wcYellowCards > 0 || profile.wcRedCards > 0) {
                                appendLine("• 黄牌 ${profile.wcYellowCards} | 红牌 ${profile.wcRedCards}")
                            }
                        }
                        val ss = profile.seasonStats
                        if (ss != null && ss.appearances > 0) {
                            appendLine("• 场均评分 ${"%.1f".format(ss.rating ?: 0.0)}")
                            appendLine("• 射门 ${ss.shotsTotal}（射正${ss.shotsOnTarget}）转化率${"%.0f".format(ss.shotAccuracy)}%")
                            appendLine("• 传球 ${ss.passesTotal}（关键${ss.passesKey}）成功率${"%.0f".format(ss.passAccuracy)}%")
                            appendLine("• 抢断 ${ss.tacklesTotal} | 拦截 ${ss.interceptions}")
                            appendLine("• 过人成功 ${ss.dribblesSuccess} | 对抗成功${ss.duelsWon}")
                        }
                        if (profile.wcGoals > 0) {
                            appendLine("• 场均进球 ${"%.2f".format(profile.goalsPerMatch)}")
                        }
                    }
                    return result + perf
                }
            } catch (_: Exception) { }
        }

        // 附加荣誉信息
        for (p in players.take(3)) {
            val apiId = findApiId(p.name)
            if (apiId > 0) {
                val trophyInfo = trophyData.getTrophiesSummary(apiId)
                if (trophyInfo.isNotEmpty()) return result + "\n" + trophyInfo
            }
        }
        return result
    }

    /** 通过球员名查找 api_sports_id（先查中文名映射，再查英文名） */
    private fun findApiId(name: String): Int {
        // 直接查中文名映射
        nameToApiId[name]?.let { return it }
        // 去掉间隔号再查
        nameToApiId[name.replace("·", "")]?.let { return it }
        return 0
    }

    private fun buildPlayerCardData(player: PlayerInfo): Map<String, Any> {
        return mapOf(
            "name" to player.name,
            "jerseyNumber" to player.jerseyNumber,
            "teamName" to player.teamName,
            "position" to player.position,
            "club" to player.club,
            "stats" to mapOf(
                "进球" to player.goals.toString(),
                "助攻" to player.assists.toString(),
                "出场" to player.appearances.toString(),
                "评分" to (player.avgRating?.let { "%.1f".format(it) } ?: "—")
            )
        )
    }

    private fun collectTeamInfo(team: String): String {
        val md = matchData ?: return ""; val matches = md.matches.filter { m -> m.homeTeamCn.contains(team, ignoreCase = true) || m.awayTeamCn.contains(team, ignoreCase = true) }
        if (matches.isEmpty()) return ""
        val f = matches.filter { md.getStatus(it) == MatchData.Status.FINISHED }; val u = matches.filter { md.getStatus(it) == MatchData.Status.UPCOMING }
        return buildString {
            appendLine("【${team}赛况】总${matches.size}场 已结束${f.size}场 待进行${u.size}场")
            f.take(5).forEach { appendLine("  ${it.homeTeamCn} ${it.homeScore}:${it.awayScore} ${it.awayTeamCn}") }
            u.take(3).forEach { appendLine("  ${it.homeTeamCn} vs ${it.awayTeamCn} (${it.date} ${it.time})") }
        }
    }

    /** 球队阵容查询（来自本地 roster 数据） */
    private fun collectLineup(team: String): String {
        val db = playerDatabase ?: return ""
        if (team.isBlank()) return ""
        return db.getTeamRoster(team)
    }

    /** 积分榜数据（注入让AI知道排名情况） */
    private fun collectStandings(): String {
        val md = matchData ?: return ""
        val groups = md.matches.groupBy { it.round.substringBefore(" ").take(1) }
        if (groups.isEmpty()) return ""
        return buildString {
            appendLine("【小组积分概况】")
            groups.entries.take(6).forEach { (group, _) ->
                appendLine("• $group 组: ${md.matches.count { it.round.contains(group) }}场比赛")
            }
        }
    }

    /** 场馆信息 */
    private fun collectStadiums(): String {
        return sharedRepo.stadiums.getAllStadiumsSummary()
    }

    // ==================== 工具方法 ====================

    /** 位置缩写 → 中文 */
    private fun posToCn(pos: String): String = when {
        pos.equals("GK", ignoreCase = true) -> "门将"
        pos.startsWith("D", ignoreCase = true) -> "后卫"
        pos.startsWith("M", ignoreCase = true) -> "中场"
        pos.startsWith("F", ignoreCase = true) || pos.startsWith("S", ignoreCase = true) -> "前锋"
        pos.equals("RW", ignoreCase = true) || pos.equals("LW", ignoreCase = true) -> "边锋"
        pos.equals("CF", ignoreCase = true) || pos.equals("ST", ignoreCase = true) -> "中锋"
        pos.equals("CAM", ignoreCase = true) || pos.equals("CM", ignoreCase = true) -> "中场"
        pos.equals("CDM", ignoreCase = true) -> "后腰"
        pos.equals("CB", ignoreCase = true) -> "中后卫"
        pos.equals("LB", ignoreCase = true) || pos.equals("RB", ignoreCase = true) -> "边后卫"
        pos.equals("WB", ignoreCase = true) -> "翼卫"
        else -> pos
    }

    // ==================== 兜底 ====================

    private fun fallbackReply(time: Long): ChatMessage {
        return ChatMessage(id = time, role = MessageRole.AI, text = "🤔 这个问题我还在学习中，不过你可以试试问我这些：\n\n" +
                "⚽ **足球规则** — 越位、VAR、红黄牌、点球规则\n" +
                "📊 **比赛信息** — 比分查询、赛程、积分榜\n" +
                "👥 **球队阵容** — 查看48支球队的大名单\n" +
                "🏆 **球员荣誉** — 梅西、C罗等球星的冠军记录\n" +
                "🏟️ **场馆信息** — 2026世界杯16座球场\n" +
                "🔮 **预测分析** — 比赛胜率、夺冠概率\n" +
                "📸 **截图识别** — 上传球员截图识别身份")
    }

    // ==================== 建议问题 ====================

    private fun updateSuggestions() {
        val n = chatSession.messages.size
        val list = when {
            n <= 3 -> listOf(
                SuggestedQuestion("越位是什么意思？", "⚽", IntentType.RULE_QUESTION, 1),
                SuggestedQuestion("今天有什么比赛？", "📅", IntentType.SCHEDULE_QUERY, 2),
                SuggestedQuestion("阿根廷阵容有哪些球员？", "👥", IntentType.LINEUP_QUERY, 3),
                SuggestedQuestion("阿根廷夺冠概率多少？", "🔮", IntentType.PREDICTION_QUERY, 4)
            )
            n <= 10 -> listOf(
                SuggestedQuestion("红牌和两黄变一红有什么区别？", "🟡", IntentType.RULE_QUESTION, 1),
                SuggestedQuestion("2026世界杯有哪些场馆？", "🏟️", IntentType.MATCH_SCORE, 2),
                SuggestedQuestion("梅西拿过什么冠军？", "🏆", IntentType.PLAYER_INFO, 3),
                SuggestedQuestion("世界杯小组赛同分怎么算？", "📊", IntentType.RULE_QUESTION, 4)
            )
            else -> listOf(
                SuggestedQuestion("VAR越位划线怎么工作的？", "⚙️", IntentType.RULE_QUESTION, 1),
                SuggestedQuestion("中国队进过世界杯吗？", "🇨🇳", IntentType.TEAM_INFO, 2),
                SuggestedQuestion("大力神杯值多少钱？", "🏆", IntentType.RULE_QUESTION, 3),
                SuggestedQuestion("阿根廷阵容什么阵型？", "📋", IntentType.LINEUP_QUERY, 4)
            )
        }
        _suggestions.value = list
    }

    private fun notifyMessagesChanged() {
        _messages.value = chatSession.messages.toList()
    }
}
