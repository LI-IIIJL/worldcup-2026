package worldcup.helper.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

class PredictionData(context: android.content.Context) {

    data class Prediction(
        @SerializedName("matchId") val matchId: Int,
        @SerializedName("teamA") val teamA: TeamPred,
        @SerializedName("draw") val draw: Int,
        @SerializedName("teamB") val teamB: TeamPred,
        @SerializedName("predictedScore") val predictedScore: String,
        @SerializedName("confidence") val confidence: String,
        @SerializedName("keyFactors") val keyFactors: List<String>,
        @SerializedName("analysis") val analysis: String,
        @SerializedName("playersToWatch") val playersToWatch: List<StarPlayer>,
        // Monte Carlo fields
        @SerializedName("mcDraw") val mcDraw: Double = 0.0,
        @SerializedName("homeElo") val homeElo: Int = 0,
        @SerializedName("awayElo") val awayElo: Int = 0,
        @SerializedName("homeLambda") val homeLambda: Double = 0.0,
        @SerializedName("awayLambda") val awayLambda: Double = 0.0,
        @SerializedName("mostLikelyScore") val mostLikelyScore: String = "?"
    )

    data class TeamPred(
        @SerializedName("name") val name: String,
        @SerializedName("cnName") val cnName: String,
        @SerializedName("winProb") val winProb: Int,
        @SerializedName("mcWinProb") val mcWinProb: Double = 0.0
    )

    data class StarPlayer(
        @SerializedName("team") val team: String,
        @SerializedName("player") val player: String,
        @SerializedName("reason") val reason: String
    )

    private data class PredictionList(val predictions: List<Prediction>)

    val predictions: List<Prediction> = try {
        val json = context.assets.open("predictions.json")
            .bufferedReader().use { it.readText() }
        Gson().fromJson(json, PredictionList::class.java).predictions
    } catch (e: Exception) {
        android.util.Log.e("PredictionData", "Failed to load predictions", e)
        emptyList()
    }

    val predictionMap: Map<Int, Prediction> = predictions.associateBy { it.matchId }

    fun getPrediction(matchId: Int): Prediction? = predictionMap[matchId]
}
