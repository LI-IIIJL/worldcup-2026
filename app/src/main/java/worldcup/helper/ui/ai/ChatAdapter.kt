package worldcup.helper.ui.ai

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import coil.request.ImageRequest
import worldcup.helper.R

/**
 * 聊天消息 RecyclerView 适配器
 *
 * 支持两种气泡样式：
 * - USER → 右对齐，暖橙色背景
 * - AI   → 左对齐，深色背景
 */
class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).role) {
            MessageRole.USER -> VIEW_TYPE_USER
            MessageRole.AI -> VIEW_TYPE_AI
            MessageRole.SYSTEM -> VIEW_TYPE_SYSTEM
        }
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
        private val ivImage: ImageView? = itemView.findViewById(R.id.iv_chat_image)
        private val root: LinearLayout = itemView.findViewById(R.id.message_root)

        fun bind(msg: ChatMessage) {
            // 图片消息
            if (msg.imageUri != null) {
                showImage(msg)
                return
            }

            // 隐藏图片控件
            ivImage?.visibility = View.GONE
            tvMessage.visibility = View.VISIBLE
            // AI结构化卡片渲染
            if (msg.role == MessageRole.AI && msg.style != ResponseStyle.TEXT_ONLY && msg.structuredData != null) {
                renderStructuredCard(msg)
                return
            }

            // 默认纯文本渲染
            tvMessage.text = renderFormattedText(msg.text)

            when (msg.role) {
                MessageRole.USER -> {
                    // 用户气泡：右对齐，暖橙色背景
                    root.gravity = androidx.core.view.GravityCompat.END
                    val lp = tvMessage.layoutParams as LinearLayout.LayoutParams
                    lp.gravity = androidx.core.view.GravityCompat.END
                    tvMessage.layoutParams = lp
                    tvMessage.setBackgroundResource(R.drawable.bg_user_bubble)
                    tvMessage.setTextColor(0xFFFFFFFF.toInt())
                    tvMessage.setPadding(14, 10, 14, 10)
                }
                MessageRole.AI -> {
                    // AI 气泡：左对齐，深蓝背景
                    root.gravity = androidx.core.view.GravityCompat.START
                    val lp = tvMessage.layoutParams as LinearLayout.LayoutParams
                    lp.gravity = androidx.core.view.GravityCompat.START
                    tvMessage.layoutParams = lp
                    tvMessage.setBackgroundResource(R.drawable.bg_ai_bubble)
                    tvMessage.setTextColor(0xFFCCCCCC.toInt())
                    tvMessage.setPadding(14, 10, 14, 10)
                }
                MessageRole.SYSTEM -> {
                    // 系统消息：居中，小字
                    root.gravity = android.view.Gravity.CENTER
                    val lp = tvMessage.layoutParams as LinearLayout.LayoutParams
                    lp.gravity = android.view.Gravity.CENTER
                    tvMessage.layoutParams = lp
                    tvMessage.background = null
                    tvMessage.setTextColor(0xFF555577.toInt())
                    tvMessage.textSize = 11f
                    tvMessage.setPadding(14, 4, 14, 4)
                }
            }
        }

        /**
         * 渲染结构化卡片（PLAYER_CARD / INFO_CARD 等）
         */
        private fun renderStructuredCard(msg: ChatMessage) {
            // 更换消息框样式：去掉气泡背景用卡片样式
            tvMessage.setBackgroundResource(R.drawable.bg_card)
            tvMessage.setPadding(12, 10, 12, 10)
            tvMessage.setTextColor(0xFFCCCCCC.toInt())

            when (msg.style) {
                ResponseStyle.PLAYER_CARD -> renderPlayerCard(msg)
                ResponseStyle.KNOWLEDGE_CARD -> renderKnowledgeCard(msg)
                ResponseStyle.PREDICTION_CARD -> renderPredictionCard(msg)
                ResponseStyle.INFO_CARD -> renderInfoCard(msg)
                else -> { tvMessage.text = renderFormattedText(msg.text) }
            }
        }

        private fun renderPlayerCard(msg: ChatMessage) {
            val data = msg.structuredData as? Map<*, *> ?: run {
                tvMessage.text = renderFormattedText(msg.text); return
            }
            val sb = StringBuilder()
            sb.appendLine("━━━ 🆔 球员信息 ━━━")
            sb.appendLine()
            val name = data["name"] as? String ?: ""
            val number = data["jerseyNumber"]?.toString() ?: "—"
            sb.appendLine("👤  $name  #$number")
            val team = data["teamName"] as? String ?: ""
            val pos = data["position"] as? String ?: ""
            sb.appendLine("🏳️  $team  ·  $pos")
            val club = data["club"] as? String ?: ""
            if (club.isNotEmpty()) sb.appendLine("🏟️  $club")
            val stats = data["stats"] as? Map<*, *>
            if (stats != null && stats.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("📊 统计")
                stats.forEach { (k, v) -> sb.appendLine("  $k: $v") }
            }
            sb.appendLine()
            sb.appendLine("━━━━━━━━━━━━━━")
            sb.appendLine("💡 点击进入球员详情页")
            tvMessage.text = sb.toString()
        }

        private fun renderKnowledgeCard(msg: ChatMessage) {
            val data = msg.structuredData as? Map<*, *> ?: run {
                tvMessage.text = renderFormattedText(msg.text); return
            }
            val sb = StringBuilder()
            val title = data["title"] as? String ?: ""
            val category = data["category"] as? String ?: ""
            sb.appendLine("━━━ $title ━━━")
            if (category.isNotEmpty()) sb.appendLine("[$category]")
            sb.appendLine()
            sb.appendLine(data["summary"] as? String ?: "")
            sb.appendLine()
            val details = data["details"] as? String
            if (details != null) sb.appendLine(details)
            val related = data["relatedTopics"] as? List<*>
            if (related != null && related.isNotEmpty()) {
                sb.appendLine("━━━━━━━━━━━━━━")
                sb.append("📚 延伸阅读: ${related.joinToString(", ")}")
            }
            tvMessage.text = sb.toString()
        }

        private fun renderPredictionCard(msg: ChatMessage) {
            val data = msg.structuredData as? Map<*, *> ?: run {
                tvMessage.text = renderFormattedText(msg.text); return
            }
            val sb = StringBuilder()
            sb.appendLine("━━━ 🔮 比赛预测 ━━━")
            val teamA = data["teamA"] as? String ?: ""
            val teamB = data["teamB"] as? String ?: ""
            sb.appendLine("$teamA  vs  $teamB")
            val score = data["predictedScore"] as? String ?: "—"
            sb.appendLine("预测比分: $score")
            sb.appendLine()
            val a = (data["teamAWinProb"] as? Number)?.toInt() ?: 0
            val d = (data["draw"] as? Number)?.toInt() ?: 0
            val b = (data["teamBWinProb"] as? Number)?.toInt() ?: 0
            sb.appendLine("🟠 $teamA: ${"█".repeat(a/5)} $a%")
            sb.appendLine("⬜ 平局: ${"░".repeat(d/5)} $d%")
            sb.appendLine("🔵 $teamB: ${"█".repeat(b/5)} $b%")
            val factors = data["keyFactors"] as? List<*>
            if (factors != null && factors.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("📊 关键因素")
                factors.forEach { sb.appendLine("  • $it") }
            }
            sb.appendLine("━━━━━━━━━━━━━━")
            sb.appendLine("💡 数据来源: AI预测模型")
            tvMessage.text = sb.toString()
        }

        private fun renderInfoCard(msg: ChatMessage) {
            val data = msg.structuredData as? Map<*, *> ?: run {
                tvMessage.text = renderFormattedText(msg.text); return
            }
            val sb = StringBuilder()
            val title = data["title"] as? String ?: ""
            sb.appendLine("━━━ $title ━━━")
            sb.appendLine()
            val items = data["items"] as? List<*>
            if (items != null) {
                for (item in items) {
                    val m = item as? Map<*, *> ?: continue
                    val label = m["label"] as? String ?: ""
                    val value = m["value"] as? String ?: ""
                    sb.appendLine("$label  $value")
                }
            }
            val footer = data["footer"] as? String
            if (footer != null) {
                sb.appendLine()
                sb.appendLine("━━━━━━━━━━━━━━")
                sb.append("💡 $footer")
            }
            tvMessage.text = sb.toString()
        }

        /**
         * 渲染 Markdown 风格的格式化文本
         * 支持：**粗体**、换行、Emoji
         */
        private fun renderFormattedText(text: String): CharSequence {
            val sb = SpannableStringBuilder()
            val lines = text.split("\n")

            for ((i, line) in lines.withIndex()) {
                if (i > 0) sb.append("\n")

                // 解析粗体 **text**
                val parts = parseBold(line)
                sb.append(parts)
            }
            return sb
        }

        private fun parseBold(text: String): CharSequence {
            val sb = SpannableStringBuilder()
            val regex = Regex("""\*\*(.+?)\*\*""")
            var lastEnd = 0

            for (match in regex.findAll(text)) {
                // 普通文本
                if (match.range.first > lastEnd) {
                    sb.append(text.substring(lastEnd, match.range.first))
                }
                // 粗体文本
                val boldText = match.groupValues[1]
                val start = sb.length
                sb.append(boldText)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
                lastEnd = match.range.last + 1
            }
            // 剩余文本
            if (lastEnd < text.length) {
                sb.append(text.substring(lastEnd))
            }
            return sb
        }

        /** 显示图片消息 */
        private fun showImage(msg: ChatMessage) {
            tvMessage.visibility = View.GONE
            ivImage?.let { iv ->
                iv.visibility = View.VISIBLE
                iv.scaleType = ImageView.ScaleType.CENTER_CROP

                // 用 Coil 加载本地图片 URI
                val context = iv.context
                val request = ImageRequest.Builder(context)
                    .data(msg.imageUri)
                    .crossfade(true)
                    .target(iv)
                    .build()
                Coil.imageLoader(context).enqueue(request)

                // 用户图片靠右，AI图片靠左
                val lp = iv.layoutParams as LinearLayout.LayoutParams
                lp.gravity = if (msg.role == MessageRole.USER) GravityCompat.END else GravityCompat.START
                iv.layoutParams = lp
            }
            // 设置根布局对齐
            root.gravity = if (msg.role == MessageRole.USER) GravityCompat.END else GravityCompat.START
        }
    }

    companion object {
        const val VIEW_TYPE_USER = 0
        const val VIEW_TYPE_AI = 1
        const val VIEW_TYPE_SYSTEM = 2
    }
}

class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem == newItem
    }
}
