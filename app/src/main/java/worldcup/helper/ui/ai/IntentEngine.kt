package worldcup.helper.ui.ai

/** 聊天消息角色 */
enum class MessageRole { USER, AI, SYSTEM }

/** 回复样式 */
enum class ResponseStyle {
    TEXT_ONLY, KNOWLEDGE_CARD, PREDICTION_CARD, PLAYER_CARD, INFO_CARD, IMAGE_RESULT
}

/** 单条聊天消息 */
data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val text: String,
    val style: ResponseStyle = ResponseStyle.TEXT_ONLY,
    val structuredData: Any? = null,
    val imageUri: String? = null  // 用户上传图片的 URI
)

/** 建议问题 */
data class SuggestedQuestion(
    val text: String,
    val icon: String,
    val intent: IntentType = IntentType.UNKNOWN,
    val order: Int = 0
)

/** 意图类型 */
enum class IntentType {
    GREETING, RULE_QUESTION, PLAYER_RECOGNITION, MATCH_SCORE,
    LINEUP_QUERY, SCHEDULE_QUERY, STANDINGS_QUERY, PREDICTION_QUERY,
    PLAYER_INFO, TEAM_INFO, GENERAL_CHAT, UNKNOWN
}

/** 意图识别结果 */
data class IntentResult(
    val intent: IntentType,
    val confidence: Float,
    val entities: Map<String, String> = emptyMap()
)

/**
 * 意图引擎：根据用户输入判断意图类型
 *
 * 使用分数制匹配，而非简单的优先级抢先。
 * - 每个意图有专属关键词，命中累加分数
 * - 实体识别（队名/球员名）可提升相关意图的分数
 * - 取最高分意图，低于阈值时走 GENERAL_CHAT
 *
 * @param allPlayerNames 可选的全量球员中文名列表（由 ChatViewModel 提供），
 *                       与硬编码的知名球员名合并使用。提供后覆盖约 1246 名球员。
 */
class IntentEngine(allPlayerNames: List<String> = emptyList()) {

    /** 各意图的关键词及其权重 */
    private val intentPatterns = mapOf(
        IntentType.GREETING to KeywordGroup(
            keywords = listOf("你好", "您好", "嗨", "hello", "hi", "hey"),
            weight = 1.5f
        ),
        IntentType.RULE_QUESTION to KeywordGroup(
            keywords = listOf("越位", "VAR", "视频助理", "点球", "红牌", "黄牌",
                "角球", "界外球", "球门球", "犯规", "手球", "任意球",
                "帽子戏法", "德比", "金靴", "金球奖", "金手套",
                "补时", "加时赛", "换人规则", "规则是什么", "足球规则"),
            weight = 1.2f
        ),
        IntentType.MATCH_SCORE to KeywordGroup(
            keywords = listOf("比分", "多少分", "几比几", "结果", "谁赢了",
                "赢了", "输了", "平了", "战平", "获胜", "击败",
                "受伤", "伤病", "场馆", "球场", "体育场"),
            weight = 1.2f
        ),
        IntentType.SCHEDULE_QUERY to KeywordGroup(
            keywords = listOf("赛程", "比赛安排", "几点", "什么时间",
                "什么时候比赛", "什么时候", "今天比赛", "明天比赛",
                "几号比赛", "哪天比赛", "哪一天", "几号踢", "几号打",
                "今天", "明天", "比赛", "有什么比赛", "哪些比赛"),
            weight = 1.0f
        ),
        IntentType.STANDINGS_QUERY to KeywordGroup(
            keywords = listOf("积分榜", "排名", "小组出线", "晋级", "积分"),
            weight = 1.0f
        ),
        IntentType.LINEUP_QUERY to KeywordGroup(
            keywords = listOf("阵容", "首发", "谁上场", "球员名单", "排兵布阵"),
            weight = 1.0f
        ),
        IntentType.PREDICTION_QUERY to KeywordGroup(
            keywords = listOf("预测", "胜率", "概率", "夺冠", "谁能赢",
                "谁会赢", "谁赢", "赔率", "冠军"),
            weight = 1.0f
        ),
        IntentType.PLAYER_RECOGNITION to KeywordGroup(
            keywords = listOf("识别", "照片", "图片", "截图", "拍照", "上传"),
            weight = 1.2f
        ),
        IntentType.PLAYER_INFO to KeywordGroup(
            keywords = listOf("球员", "是谁", "个人资料", "介绍"),
            weight = 1.0f
        ),
        IntentType.TEAM_INFO to KeywordGroup(
            keywords = listOf("球队", "介绍", "历史", "资料"),
            weight = 1.0f
        )
    )

    private val teamKeywords = listOf(
        "墨西哥", "南非", "韩国", "捷克", "加拿大", "波黑", "卡塔尔", "瑞士",
        "巴西", "摩洛哥", "海地", "苏格兰", "美国", "巴拉圭", "澳大利亚", "土耳其",
        "德国", "库拉索", "科特迪瓦", "厄瓜多尔", "荷兰", "日本", "瑞典", "突尼斯",
        "比利时", "埃及", "沙特", "乌拉圭", "伊朗", "新西兰", "法国", "塞内加尔",
        "伊拉克", "挪威", "阿根廷", "阿尔及利亚", "奥地利", "约旦", "葡萄牙",
        "刚果", "英格兰", "克罗地亚", "加纳", "巴拿马", "乌兹别克斯坦", "哥伦比亚",
        "西班牙", "佛得角", "中国", "意大利", "荷兰", "法国", "英格兰", "西班牙",
        "德国", "巴西", "阿根廷", "葡萄牙"
    )

    /** 知名球员名（硬编码，覆盖"梅西""C罗"等常见称呼） */
    private val celebrityPlayers = listOf(
        "梅西", "C罗", "C罗", "姆巴佩", "内马尔", "莱万", "德布劳内",
        "希门尼斯", "普利西奇", "奥乔亚", "萨拉赫", "孙兴慜",
        "凯恩", "格列兹曼", "贝尔", "哈兰德", "贝林厄姆", "维尼修斯",
        "佩德里", "加维", "穆西亚拉", "萨卡", "福登", "大马丁",
        "马丁内斯", "诺伊尔", "库尔图瓦", "莫德里奇", "克洛泽",
        "罗纳尔多", "方丹", "盖德穆勒"
    )

    /** 全量球员名（硬编码知名 + ChatViewModel 注入的全量 1246 名） */
    private val playerKeywords = mutableListOf<String>().apply {
        addAll(celebrityPlayers)
        addAll(allPlayerNames)
    }.distinct().toMutableList()

    /**
     * 运行时追加球员名（playerDatabase 加载完成后调用）
     * @param names 来自 players_2026.json 的所有中文名
     */
    fun addPlayerNames(names: List<String>) {
        val seen = playerKeywords.toSet()
        for (name in names) {
            if (name !in seen) {
                playerKeywords.add(name)
            }
        }
    }

    fun classify(query: String): IntentResult {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return IntentResult(IntentType.UNKNOWN, 0f)

        // 1. 实体提取（支持多队名：如"日本vs瑞典"）
        val entities = mutableMapOf<String, String>()
        val foundTeams = mutableListOf<String>()
        var hasTeam = false
        for (team in teamKeywords) {
            if (q.contains(team.lowercase())) {
                foundTeams.add(team)
                if (foundTeams.size <= 2) {
                    entities["team${foundTeams.size}"] = team
                }
                hasTeam = true
            }
        }
        var hasPlayer = false
        val foundPlayers = mutableListOf<String>()
        for (player in playerKeywords) {
            if (q.contains(player.lowercase())) {
                foundPlayers.add(player)
                if (foundPlayers.size <= 2) {
                    entities["player${foundPlayers.size}"] = player
                }
                hasPlayer = true
            }
        }

        // 2. 分数制匹配：为每个意图计算分数
        val scores = mutableMapOf<IntentType, Float>()

        for ((intent, group) in intentPatterns) {
            var score = 0f
            for (keyword in group.keywords) {
                if (q.contains(keyword.lowercase())) {
                    score += group.weight
                }
            }
            if (score > 0f) {
                scores[intent] = score
            }
        }

        // 3. 实体关联加成：有队名时提升比赛/赛程/球队类意图的分数
        if (hasTeam) {
            scores[IntentType.MATCH_SCORE] = (scores[IntentType.MATCH_SCORE] ?: 0f) + 0.5f
            scores[IntentType.SCHEDULE_QUERY] = (scores[IntentType.SCHEDULE_QUERY] ?: 0f) + 0.5f
            scores[IntentType.LINEUP_QUERY] = (scores[IntentType.LINEUP_QUERY] ?: 0f) + 0.3f
            scores[IntentType.TEAM_INFO] = (scores[IntentType.TEAM_INFO] ?: 0f) + 0.3f

            // 如果查询内容是"队名+怎么/为什么"等，但没有其他关键词匹配，归为 MATCH_SCORE
            if (scores.isEmpty()) {
                scores[IntentType.MATCH_SCORE] = 0.4f
            }
        }

        if (hasPlayer) {
            scores[IntentType.PLAYER_INFO] = (scores[IntentType.PLAYER_INFO] ?: 0f) + 0.8f
            // 纯球员名查询（无其他关键词）归为 PLAYER_INFO
            if (q.length <= 6 && scores.isEmpty()) {
                scores[IntentType.PLAYER_INFO] = 0.5f
            }
        }

        // 4. 特殊规则：短查询不匹配 GREETING（"你好吗"不应算问候）
        if (scores.containsKey(IntentType.GREETING) && q.length > 8) {
            scores.remove(IntentType.GREETING)
        }

        // 5. 取最高分
        val best = scores.maxByOrNull { it.value }
        if (best != null && best.value >= 0.5f) {
            return IntentResult(best.key, best.value, entities)
        }

        // 6. 有实体但分数不够 → 走实体相关意图
        if (hasTeam) return IntentResult(IntentType.MATCH_SCORE, 0.4f, entities)
        if (hasPlayer) return IntentResult(IntentType.PLAYER_INFO, 0.4f, entities)

        return IntentResult(IntentType.GENERAL_CHAT, 0.3f, entities)
    }

    private data class KeywordGroup(
        val keywords: List<String>,
        val weight: Float = 1.0f
    )
}
