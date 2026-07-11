package worldcup.helper.ui.predict

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import worldcup.helper.R
import worldcup.helper.data.MatchData
import worldcup.helper.data.PredictionData

class PredictFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_predict, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val matchData = MatchData(requireContext())
        val predictionData = PredictionData(requireContext())

        val rv = view.findViewById<RecyclerView>(R.id.rv_predictions)
        rv.layoutManager = LinearLayoutManager(requireContext())

        val sortedMatches = matchData.matches.sortedBy { matchData.getSortKey(it) }
        val adapter = PredictionAdapter(sortedMatches, predictionData, matchData)
        rv.adapter = adapter
    }
}

class PredictionAdapter(
    private val matches: List<MatchData.Match>,
    private val predictionData: PredictionData,
    private val matchData: MatchData
) : RecyclerView.Adapter<PredictionAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRound: TextView = view.findViewById(R.id.tv_pred_round)
        val tvDate: TextView = view.findViewById(R.id.tv_pred_date)
        val tvConfidence: TextView = view.findViewById(R.id.tv_confidence_tag)
        val tvTeamA: TextView = view.findViewById(R.id.tv_team_a)
        val tvTeamB: TextView = view.findViewById(R.id.tv_team_b)
        val tvProbA: TextView = view.findViewById(R.id.tv_team_a_prob)
        val tvProbB: TextView = view.findViewById(R.id.tv_team_b_prob)
        val tvScore: TextView = view.findViewById(R.id.tv_pred_score_display)
        val tvDraw: TextView = view.findViewById(R.id.tv_draw_prob)
        val barA: View = view.findViewById(R.id.bar_team_a)
        val barDraw: View = view.findViewById(R.id.bar_draw)
        val barB: View = view.findViewById(R.id.bar_team_b)
        val tvStars: TextView = view.findViewById(R.id.tv_stars)
        val tvEloInfo: TextView = view.findViewById(R.id.tv_elo_info)
        val tvMcScore: TextView = view.findViewById(R.id.tv_mc_score)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_prediction, parent, false))
    }

    override fun getItemCount() = matches.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val match = matches[position]
        val pred = predictionData.getPrediction(match.id.toIntOrNull() ?: 0)

        holder.tvRound.text = match.round
        holder.tvDate.text = "${match.date} ${match.time}"

        if (pred != null) {
            holder.tvTeamA.text = pred.teamA.cnName
            holder.tvTeamB.text = pred.teamB.cnName
            holder.tvProbA.text = "${pred.teamA.winProb}%"
            holder.tvProbB.text = "${pred.teamB.winProb}%"
            holder.tvScore.text = pred.predictedScore
            holder.tvDraw.text = "平${pred.draw}%"
            holder.tvConfidence.text = "置信度: ${pred.confidence}"

            // Color confidence
            when (pred.confidence) {
                "高" -> holder.tvConfidence.setTextColor(0xFF4CAF50.toInt())
                "中" -> holder.tvConfidence.setTextColor(0xFFFFD700.toInt())
                "低" -> holder.tvConfidence.setTextColor(0xFF888888.toInt())
                else -> holder.tvConfidence.setTextColor(0xFFFFD700.toInt())
            }

            // Probability bars using MC winProbs for accurate weighting
            val total = pred.teamA.mcWinProb + pred.mcDraw + pred.teamB.mcWinProb
            val paramsA = holder.barA.layoutParams as LinearLayout.LayoutParams
            paramsA.weight = if (total > 0) pred.teamA.mcWinProb.toFloat() / total.toFloat() else 1f
            holder.barA.layoutParams = paramsA

            val paramsD = holder.barDraw.layoutParams as LinearLayout.LayoutParams
            paramsD.weight = if (total > 0) pred.mcDraw.toFloat() / total.toFloat() else 1f
            holder.barDraw.layoutParams = paramsD

            val paramsB = holder.barB.layoutParams as LinearLayout.LayoutParams
            paramsB.weight = if (total > 0) pred.teamB.mcWinProb.toFloat() / total.toFloat() else 1f
            holder.barB.layoutParams = paramsB

            // ELO + λ info
            if (pred.homeElo > 0 && pred.awayElo > 0) {
                holder.tvEloInfo.text = "ELO ${pred.homeElo} vs ${pred.awayElo}  |  λ ${String.format("%.2f", pred.homeLambda)} vs ${String.format("%.2f", pred.awayLambda)}"
            } else {
                holder.tvEloInfo.text = "对阵待定 — 小组赛结束后更新"
            }

            // Most likely score from MC
            if (pred.mostLikelyScore != "?") {
                holder.tvMcScore.text = "最可能 ${pred.mostLikelyScore}"
                holder.tvMcScore.visibility = View.VISIBLE
            } else {
                holder.tvMcScore.visibility = View.GONE
            }

            // Stars to watch
            if (pred.playersToWatch.isNotEmpty()) {
                val starText = pred.playersToWatch.joinToString("  ·  ") { "⭐ ${it.player} (${MatchData.getChineseName(it.team)})" }
                holder.tvStars.text = starText
                holder.tvStars.visibility = View.VISIBLE
            } else {
                holder.tvStars.visibility = View.GONE
            }
        } else {
            holder.tvTeamA.text = match.homeTeamCn
            holder.tvTeamB.text = match.awayTeamCn
            holder.tvProbA.text = "?"
            holder.tvProbB.text = "?"
            holder.tvScore.text = "?"
            holder.tvDraw.text = ""
            holder.tvConfidence.text = "暂无数据"
            holder.tvConfidence.setTextColor(0xFF888888.toInt())
            holder.tvEloInfo.text = ""
            holder.tvMcScore.visibility = View.GONE
            holder.tvStars.visibility = View.GONE
        }
    }
}
