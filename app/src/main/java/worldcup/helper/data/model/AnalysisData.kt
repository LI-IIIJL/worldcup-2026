package worldcup.helper.data.model

/**
 * 深入分析数据模型 (Living_Module_Integration.md §6)
 * 赛后深入分析所需的全部数据类
 */

/** 传球网络节点 */
data class PassNetworkNode(
    val playerId: Int,
    val playerName: String,
    val x: Float,
    val y: Float,
    val passesCompleted: Int,
    val passesAttempted: Int
)

/** 传球网络连线 */
data class PassNetworkEdge(
    val fromPlayerId: Int,
    val toPlayerId: Int,
    val count: Int
)

/** 传球网络 */
data class PassNetwork(
    val nodes: List<PassNetworkNode>,
    val edges: List<PassNetworkEdge>
)

/** 球员影响力指标 */
data class PlayerInfluence(
    val playerId: Int,
    val touches: Int,
    val passesReceived: Int,
    val progressivePasses: Int,
    val progressiveCarries: Int,
    val passesIntoFinalThird: Int,
    val passesIntoBox: Int
)

/** 球队阵型时间线（阵型变化） */
data class FormationTimeline(
    val minute: Int,
    val homeFormation: String,
    val awayFormation: String
)

/** 比赛关键事件摘要 */
data class MatchEventSummary(
    val minute: Int,
    val type: String,
    val description: String,
    val impactScore: Double = 0.0  // 0-1 事件影响力评分
)

/** 球队风格分析 */
data class TeamStyleAnalysis(
    val teamName: String,
    val buildUpPlaySpeed: String,        // slow / balanced / fast
    val buildUpPlayPositioning: String,  // organised / free_form
    val chanceCreationPassing: String,   // short / mixed / long
    val chanceCreationCrossing: Boolean,
    val chanceCreationShooting: Boolean,
    val defencePressure: String,         // deep / medium / high
    val defenceAggression: String,       // contain / press / double
    val defenceLine: String              // cover / offside_trap
)

/** 深度球员分析汇总 */
data class DeepPlayerAnalysisV2(
    val playerId: Int,
    val playerName: String,
    val teamName: String,
    val minutesPlayed: Int,
    val goals: Int,
    val assists: Int,
    val shotsTotal: Int,
    val shotsOnTarget: Int,
    val passAccuracy: Double,
    val keyPasses: Int,
    val tackles: Int,
    val interceptions: Int,
    val clearances: Int,
    val dribblesSuccess: Int,
    val aerialDuelsWon: Int,
    val fouls: Int,
    val offsides: Int,
    val dispossessed: Int,
    val rating: Double,
    val xG: Double?,
    val xA: Double?,
    val influence: PlayerInfluence?,
    val shots: List<ShotEntry> = emptyList()
)

/** API 跨平台 ID 映射 (requirement_list.md §B.2) */
data class TeamIdMapping(
    val footballDataId: Int,
    val apiSportsId: Int,
    val bdlId: Int,
    val fifaCode: String,
    val iso2: String
)
