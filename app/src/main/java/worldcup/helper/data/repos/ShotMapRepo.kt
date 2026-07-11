package worldcup.helper.data.repos

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import worldcup.helper.network.BdlShot
import worldcup.helper.network.LiveApiClient

/**
 * 射门分布图 Repository
 *
 * 数据来源: BDL GOAT /fifa/worldcup/v1/match_shots
 * API 等级: 🟡 条件API（需 BDL GOAT $39.99/月，赛后才有数据）
 * 所属架构: ShotMapRepo (new_framework.md §4.5)
 *
 * 两种使用场景:
 * 1. 球队详情页 (TeamDetailActivity): 聚合该队所有已完赛比赛的射门
 * 2. 比赛详情页 (MatchDetailActivity): 单场比赛射门图
 */
class ShotMapRepo(context: Context) {

    companion object {
        private const val TAG = "ShotMapRepo"
    }

    /** 射门结果分类 */
    enum class ShotResult { GOAL, ON_TARGET, OFF_TARGET, BLOCKED, POST, UNKNOWN }

    /** 单脚射门记录 */
    data class ShotEntry(
        val playerName: String,
        val minute: Int,
        val x: Float,           // 球场坐标 0-100（已按攻防方向镜像）
        val y: Float,           // 球场坐标 0-100
        val xg: Double,
        val result: ShotResult,
        val bodyPart: String,
        val isGoal: Boolean,
        val isHome: Boolean = true  // 是否主队射门（用于坐标镜像判断）
    )

    /** 球队射门汇总 */
    data class TeamShotMap(
        val teamName: String,
        val totalShots: Int,
        val goals: Int,
        val shotsOnTarget: Int,
        val shots: List<ShotEntry>,      // 该队全部射门
        val matchCount: Int               // 参与统计的比赛场次
    )

    // BDL 球队名 → team_id 缓存
    private val bdlTeamNameToId = mutableMapOf<String, Int>()

    /**
     * 球队射门聚合（TeamDetailActivity 主入口）
     * @param teamName 球队英文名
     * @return 该队所有已完赛比赛的射门聚合，若无数据返回 null
     */
    suspend fun getTeamShotMap(teamName: String): TeamShotMap? = withContext(Dispatchers.IO) {
        try {
            val bdlTeamId = findBdlTeamId(teamName) ?: return@withContext null
            // 1. 获取该队所有已完赛的 BDL match_id
            val matchesResp = LiveApiClient.bdlApi.getMatches(
                teamIds = listOf(bdlTeamId), status = "completed"
            )
            val bdlMatchIds = matchesResp.data.map { it.id }
            if (bdlMatchIds.isEmpty()) {
                Log.d(TAG, "$teamName: 无已完赛比赛"); return@withContext null
            }

            // 2. 并行拉取每场比赛的射门数据
            val allShots = mutableListOf<Pair<BdlShot, Boolean>>() // (shot, isHome)
            coroutineScope {
                val deferred = bdlMatchIds.map { matchId ->
                    async {
                        try {
                            val resp = LiveApiClient.bdlApi.getMatchShots(listOf(matchId))
                            val match = matchesResp.data.find { it.id == matchId }
                            val isHome = match?.home_team?.id == bdlTeamId
                            resp.data.map { it to isHome }
                        } catch (_: Exception) { emptyList() }
                    }
                }
                deferred.awaitAll().forEach { allShots.addAll(it) }
            }

            // 3. 过滤该队射门 + 按方向镜像坐标
            val teamShots = allShots
                .filter { (shot, _) ->
                    shot.team_id == bdlTeamId
                }
                .map { (shot, isHome) -> bdlShotToEntry(shot, isHome) }

            if (teamShots.isEmpty()) {
                Log.d(TAG, "$teamName: 无该队射门数据"); return@withContext null
            }

            val goals = teamShots.count { it.isGoal }
            val onTarget = teamShots.count { it.result == ShotResult.ON_TARGET || it.result == ShotResult.GOAL }

            Log.d(TAG, "$teamName shots: ${teamShots.size} total, $goals goals, $onTarget on target (${bdlMatchIds.size} matches)")

            TeamShotMap(
                teamName = teamName,
                totalShots = teamShots.size,
                goals = goals,
                shotsOnTarget = onTarget,
                shots = teamShots,
                matchCount = bdlMatchIds.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "球队射门聚合失败", e); null
        }
    }

    /**
     * 单场射门数据（MatchDetailActivity 使用）
     */
    suspend fun getMatchShots(bdlMatchId: Int): List<ShotEntry> = withContext(Dispatchers.IO) {
        try {
            val resp = LiveApiClient.bdlApi.getMatchShots(listOf(bdlMatchId))
            resp.data.map { bdlShotToEntry(it) }
        } catch (_: Exception) { emptyList() }
    }

    // ======================== 内部方法 ========================

    private fun bdlShotToEntry(shot: BdlShot, isHome: Boolean = true): ShotEntry {
        val rawX = (shot.player_x ?: 50.0).toFloat()
        val rawY = (shot.player_y ?: 50.0).toFloat()
        // ⭐ 坐标镜像：BDL 坐标以主队进攻方向为准（左→右）
        // 副队射门需要翻转 X: 100 - x，让所有射门都从进攻方向视角展示
        val mappedX = if (isHome) rawX else 100f - rawX
        return ShotEntry(
            playerName = shot.player_name ?: "",
            minute = shot.minute ?: 0,
            x = mappedX,
            y = rawY,
            xg = shot.xg ?: 0.0,
            result = classifyShotResult(shot),
            bodyPart = shot.body_part ?: "",
            isGoal = shot.is_goal,
            isHome = isHome
        )
    }

    private fun classifyShotResult(shot: BdlShot): ShotResult {
        if (shot.is_goal) return ShotResult.GOAL

        // BDL API 的 result 字段多数为空，改用 xgot + shot_type 判定
        if (shot.xgot != null && shot.xgot > 0.0) {
            return ShotResult.ON_TARGET  // xgot 有值 = 射正
        }
        val st = shot.shot_type?.lowercase() ?: ""
        when {
            st.contains("saved") || st.contains("save") -> return ShotResult.ON_TARGET
            st.contains("block") -> return ShotResult.BLOCKED
            st.contains("miss") || st.contains("wide") ||
            st.contains("off_target") -> return ShotResult.OFF_TARGET
            st.contains("post") || st.contains("woodwork") ||
            st.contains("crossbar") -> return ShotResult.POST
        }
        return ShotResult.OFF_TARGET  // 无更多线索 → 射偏
    }

    /**
     * 通过球队英文名查找 BDL team_id
     * 先查本地缓存，查不到就调 BDL /teams API
     */
    private suspend fun findBdlTeamId(teamName: String): Int? {
        val lower = teamName.lowercase()
        bdlTeamNameToId[lower]?.let { return it }

        val resp = LiveApiClient.bdlApi.getTeams()
        for (team in resp.data) {
            val tLower = team.name.lowercase()
            if (lower.contains(tLower) || tLower.contains(lower)) {
                bdlTeamNameToId[lower] = team.id
                bdlTeamNameToId[tLower] = team.id
                return team.id
            }
        }
        Log.w(TAG, "未找到球队 $teamName 的 BDL ID")
        return null
    }
}
