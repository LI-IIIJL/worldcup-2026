package worldcup.helper.ui.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * FAQ 知识库 — 足球规则问答（42+ 条，覆盖主流话题）
 *
 * 数据来源：assets/faq_knowledge.json
 *
 * 匹配策略：
 * - 分词匹配：把用户输入切分成有意义的词，逐一匹配关键词
 * - 置信度分级：高(≥0.6)→本地直出，中(≥0.3)→注入DeepSeek，低(<0.3)→只走DeepSeek
 */
class FaqKnowledge(private val context: Context) {

    data class FaqEntry(val question: String, val answer: String, val keywords: List<String>)
    data class MatchResult(val entry: FaqEntry, val confidence: Float, val isExact: Boolean)

    private var loaded = false
    private val faqList = mutableListOf<FaqEntry>()

    fun ensureLoaded() {
        if (loaded) return
        loadFromJson()
        loaded = true
    }

    /** 搜索最匹配的 FAQ 条目，返回带置信度的结果 */
    fun search(query: String): MatchResult? {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return null

        var best: MatchResult? = null
        for (entry in faqList) {
            val (score, exact) = matchTokens(tokens, entry)
            if (score > 0 && (best == null || score > best.confidence)) {
                best = MatchResult(entry, score, exact)
            }
        }
        return best
    }

    /** 把查询切分成有意义的搜索词 */
    private fun tokenize(query: String): List<String> {
        val q = query.lowercase().trim()
        val result = mutableListOf<String>()

        // 整个查询作为一个词
        result.add(q)

        // 2-4 字滑动窗口（中文组合词）
        for (len in 2..minOf(4, q.length)) {
            for (i in 0..q.length - len) {
                val segment = q.substring(i, i + len)
                if (segment.any { it in '\u4E00'..'\u9FFF' } || segment.any { it in 'a'..'z' }) {
                    result.add(segment)
                }
            }
        }

        // 按常见分隔符切分
        result.addAll(q.split(Regex("[，。！？、；：\\s,!.?;: ]")))

        return result.distinct().filter { it.length >= 1 }
    }

    /** 计算单条 FAQ 的匹配分数 */
    private fun matchTokens(tokens: List<String>, entry: FaqEntry): Pair<Float, Boolean> {
        var hitCount = 0
        var exactMatch = false

        for (token in tokens) {
            if (token.length < 1) continue
            for (kw in entry.keywords) {
                if (token == kw) {
                    hitCount += 3  // 完全相等权重高
                    exactMatch = true
                } else if (kw.contains(token) || token.contains(kw)) {
                    hitCount += 2  // 包含关系
                } else {
                    // 模糊匹配：共同字符比例
                    val common = token.toSet().intersect(kw.toSet()).size
                    val ratio = if (token.length + kw.length > 0)
                        common.toFloat() / (token.toSet().union(kw.toSet()).size) else 0f
                    if (ratio >= 0.6f) hitCount += 1
                }
            }
        }

        if (hitCount == 0) return 0f to false

        // 归一化分数（最高 3 * token数 为满分，取 3 作为饱和点）
        val normalized = (hitCount.toFloat() / maxOf(entry.keywords.size * 1.5f, 3f)).coerceAtMost(1f)

        return normalized to exactMatch
    }

    /** 从 assets/faq_knowledge.json 加载知识库 */
    private fun loadFromJson() {
        try {
            val jsonString = context.assets.open("faq_knowledge.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val entries = root.getJSONArray("entries")

            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val question = item.getString("question")
                val answer = item.getString("answer")
                val keywords = mutableListOf<String>()

                val kwArray = item.getJSONArray("keywords")
                for (j in 0 until kwArray.length()) {
                    keywords.add(kwArray.getString(j).lowercase())
                }

                faqList.add(FaqEntry(question, answer, keywords))
            }
        } catch (e: Exception) {
            // JSON 加载失败时回退到空列表，不影响 App 其他功能
            android.util.Log.e("FaqKnowledge", "加载 faq_knowledge.json 失败: ${e.message}")
        }
    }
}
