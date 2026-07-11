package worldcup.helper.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 客户端
 *
 * 底层使用 OkHttp + Gson。
 * API Key 通过 BuildConfig.DEEPSEEK_API_KEY 注入（来自 local.properties）。
 *
 * 核心设计：所有回复走 DeepSeek，本地数据作为上下文注入。
 * 本地只做两件事：FAQ 直达命中、数据获取与注入。
 */
class DeepSeekClient(private val apiKey: String) {

    companion object {
        private const val API_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-chat"

        /**
         * 系统提示词 — 给足上下文让 AI 表现自然
         */
        private const val SYSTEM_PROMPT = """你叫"世界杯AI助手"，是2026美加墨世界杯官方观赛App的内置AI助手。

## 你的性格
- 热情、专业、聊得来。像一个懂球的朋友，不是冷冰冰的客服。
- 回答简短有力，除非用户问得很深。
- 可以适度调侃、兴奋、表达情绪。⚽🏆🥅

## 知识边界
- 你对足球规则、世界杯历史、球队球员背景了如指掌。
- 比分、赛程等实时数据由App提供，你会基于数据说话。
- **⚠️ 重要：App会在「【当前时间】」字段中告诉你现在的准确日期和时间。你必须以这个日期为基准来回答所有"今天/明天/几号"等问题！**
- **⚠️ 重要：App会在「【赛程数据】」字段中告诉你所有比赛的准确日期和状态。直接引用这些日期，不要说"某一天"！**
- 如果App提供了比赛数据，优先用数据回答；如果没提供或数据不全，坦白说"目前没有查到这场的数据"。
- 不要编造比分或比赛结果！

## 数据能力
App会传递给你以下数据（可能部分为空）：
- 比赛比分列表（含已结束/进行中/未开始）
- 比赛赛程
- 积分榜
- 球员信息
- 球队预测数据（胜率、关注球员）
- 对话历史

## 回复风格
- 用中文，简洁自然
- 涉及多个条目时分点列出
- 不知道的就说"这个我还不确定"，不要编造
- 推荐行为：引导用户使用App内的截图识别、预测、赛程等功能"""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()
    private var available = apiKey.isNotBlank()

    /** 简单问答（FAQ 未命中时的规则解释） */
    suspend fun chat(query: String): String = withContext(Dispatchers.IO) {
        checkAvailable()
        val payload = buildPayload(
            messages = listOf(
                mapOf("role" to "user", "content" to query)
            )
        )
        execute(payload)
    }

    /** 带对话上下文的通用聊天 */
    suspend fun chatWithContext(query: String, context: String): String = withContext(Dispatchers.IO) {
        checkAvailable()
        val ctx = context.take(3000)
        val payload = buildPayload(
            messages = listOf(
                mapOf("role" to "assistant", "content" to "以下是最近的对话历史：\n$ctx"),
                mapOf("role" to "user", "content" to query)
            )
        )
        execute(payload)
    }

    /**
     * 带数据上下文的智能问答
     *
     * @param query 用户问题
     * @param dataContext App 查到的结构化数据描述（比分/赛程/球员等）
     * @param context 对话历史（可选）
     */
    suspend fun chatWithData(query: String, dataContext: String, context: String = ""): String = withContext(Dispatchers.IO) {
        checkAvailable()
        val messages = mutableListOf<Map<String, String>>()

        // 如果有对话历史，先注入
        if (context.isNotBlank()) {
            messages.add(mapOf("role" to "assistant", "content" to "对话历史：\n${context.take(2000)}"))
        }

        // 注入 App 数据上下文
        if (dataContext.isNotBlank()) {
            messages.add(mapOf("role" to "system", "content" to "【App实时数据】\n$dataContext"))
        }

        messages.add(mapOf("role" to "user", "content" to query))

        val payload = buildPayload(messages = messages)
        execute(payload)
    }

    // ==================== 私有 ====================

    private fun checkAvailable() {
        if (!available) throw IllegalStateException("DeepSeek API key 未配置")
    }

    private fun buildPayload(messages: List<Map<String, String>>): String {
        val fullMessages = mutableListOf<Map<String, String>>(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT)
        )
        fullMessages.addAll(messages)

        return gson.toJson(
            mapOf(
                "model" to MODEL,
                "messages" to fullMessages,
                "temperature" to 0.8,   // 提高温度让回复更自然
                "max_tokens" to 1000
            )
        )
    }

    private fun execute(payload: String): String {
        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonType))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorMsg = try {
                val obj = JsonParser.parseString(body).asJsonObject
                obj.get("error")?.asJsonObject?.get("message")?.asString ?: "HTTP ${response.code}"
            } catch (_: Exception) {
                "HTTP ${response.code}"
            }
            throw RuntimeException("API 请求失败: $errorMsg")
        }

        return parseResponse(body)
    }

    private fun parseResponse(json: String): String {
        val obj = JsonParser.parseString(json).asJsonObject
        val choices = obj.getAsJsonArray("choices") ?: return ""
        if (choices.size() == 0) return ""
        val message = choices[0].asJsonObject.getAsJsonObject("message")
        return message?.get("content")?.asString?.trim() ?: ""
    }
}
