package worldcup.helper.ui.ai

/**
 * 聊天会话管理 — 保持上下文（最近 10 轮）
 */
class ChatSession {

    val messages = mutableListOf<ChatMessage>()

    private val maxContextRounds = 10

    fun addWelcomeMessage() {
        if (messages.isEmpty()) {
            messages.add(ChatMessage(
                id = -1L,
                role = MessageRole.AI,
                text = "👋 你好！我是世界杯 AI 助手 ⚽\n\n" +
                        "我可以帮你：\n" +
                        "• ⚽ 回答足球规则问题（越位、VAR等）\n" +
                        "• 📊 查询比赛信息（比分、赛程、积分榜）\n" +
                        "• 🔮 查看预测结果（胜率、推荐）\n" +
                        "• 📸 上传比赛截图识别球员\n\n" +
                        "试试上方的建议问题吧！👇",
                style = ResponseStyle.INFO_CARD
            ))
        }
    }

    fun getContextText(): String {
        val recent = messages.takeLast(maxContextRounds * 2)
        return recent.joinToString("\n") { msg ->
            "[${msg.role}] ${msg.text.take(200)}"
        }
    }
}
