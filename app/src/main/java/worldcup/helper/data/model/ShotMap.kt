package worldcup.helper.data.model

/**
 * 球员射门分布图数据模型
 *
 * 数据来源: BDL match_shots API
 *   GET /fifa/worldcup/v1/match_shots?match_ids[]={id}
 *
 * 聚合逻辑: 所有完赛比赛中该球员的射门数据按 player_name 过滤
 */
data class Shot(
    val matchId: Int,
    val x: Float,           // 射门位置 X (0-100, 球场宽度百分比)
    val y: Float,           // 射门位置 Y (0-100, 底线=0, 中线=100)
    val xg: Float,          // 预期进球
    val isGoal: Boolean,    // 是否进球
    val minute: Int,        // 比赛分钟
    val bodyPart: String,   // right_foot / left_foot / head
    val shotType: String    // open_play / penalty / free_kick
) {
    /** 射门结果枚举 */
    val result: ShotResult
        get() = when {
            isGoal -> ShotResult.GOAL
            shotType.contains("penalty", ignoreCase = true) -> ShotResult.PENALTY
            else -> ShotResult.MISS
        }
}

enum class ShotResult {
    GOAL,       // 进球 → 绿色
    PENALTY,    // 点球 → 紫色
    MISS        // 射偏/被扑 → 灰色
}

/**
 * 球员射门聚合数据
 */
data class ShotMap(
    val shots: List<Shot>,
    val totalXg: Float,         // 总预期进球
    val totalShots: Int,        // 总射门数
    val totalGoals: Int,        // 总进球数
    val matchCount: Int         // 有射门的比赛数
) {
    companion object {
        val EMPTY = ShotMap(emptyList(), 0f, 0, 0, 0)
    }
}
