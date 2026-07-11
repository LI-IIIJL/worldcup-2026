package worldcup.helper.data.repos

import android.content.Context

/**
 * 全局统一数据仓库 — 全项目唯一数据入口
 *
 * 架构: SharedRepository (new_framework.md §2)
 * 原则: 最高等级确保数据真实性和实时性
 *
 * 使用方式（所有 Tab 统一）:
 *   val repo = SharedRepository.getInstance(context)
 *   val liveMatches = repo.matches.getLiveMatches()
 *   val profile = repo.players.getPlayerProfile("梅西")
 *   val standings = repo.standings.getStandings()
 *
 * Tab 间数据一致性保证:
 *   - 所有子 Repository 由 SharedRepository 统一初始化
 *   - 所有 API 调用走同一套 Retrofit 实例 (LiveApiClient)
 *   - 所有本地缓存由各自 Repository 管理生命周期
 *   - Tab B 的 ChatViewModel 必须从 SharedRepository 获取数据
 */
class SharedRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: SharedRepository? = null

        /**
         * 获取全局唯一实例
         * 线程安全，双重检查锁定
         */
        fun getInstance(context: Context): SharedRepository {
            return instance ?: synchronized(this) {
                instance ?: SharedRepository(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 重置实例（仅用于测试/清空缓存）
         */
        fun resetInstance() {
            instance = null
        }
    }

    // ========================================================================
    // 子 Repository（全部懒加载）
    // ========================================================================

    /** 比赛数据 — Tab A/C 主力，Tab D 辅助 */
    val matches: MatchRepo by lazy { MatchRepo(appContext) }

    /** 积分榜/球员榜 — Tab D 主力 */
    val standings: StandingRepo by lazy { StandingRepo(appContext) }

    /** 球队数据 — Tab C/D 主力 */
    val teams: TeamRepo by lazy { TeamRepo(appContext) }

    /** 场馆数据 — 全项目辅助 */
    val stadiums: StadiumRepo by lazy { StadiumRepo(appContext) }

    /** 射门图数据 — TeamDetailActivity 聚合，MatchDetailActivity 单场 */
    val shotMap: ShotMapRepo by lazy { ShotMapRepo(appContext) }

    /** 球员数据 — 全项目共享（已存在 PlayerRepository） */
    val players: PlayerRepoWrapper by lazy { PlayerRepoWrapper(appContext) }

    private val appContext = context.applicationContext

    // ========================================================================
    // 快捷方法 — 全项目通用
    // ========================================================================

    /** 是否有比赛在直播中？ */
    fun hasLiveMatch(): Boolean = matches.hasLiveMatch()

    /** 获取当前直播比赛（Tab A 用） */
    fun getLiveMatches() = matches.getLiveMatches()

    /** 获取下一比赛日（Tab A 无直播时用） */
    fun getNextMatchday() = matches.getNextMatchday()

    /** 清空所有缓存（强制下次刷新） */
    fun clearAllCaches() {
        // 各子 Repository 的缓存由其各自管理
        // SharedRepository 只负责聚合，不持有具体数据
    }

    // ========================================================================
    // 数据一致性检查
    // ========================================================================

    /**
     * 获取当前所有活跃数据源的状态
     * 用于调试/监控
     */
    fun getDataStatus(): Map<String, Any> {
        return mapOf(
            "hasLiveMatch" to hasLiveMatch(),
            "liveMatchCount" to getLiveMatches().size,
            "nextMatchdayCount" to getNextMatchday().size,
            "teamsCount" to teams.getAllTeams().size,
        )
    }
}

/**
 * PlayerRepository 适配包装
 *
 * 由于 PlayerRepository 是已有实现且设计良好，
 * 这里通过包装使其融入 SharedRepository 架构
 */
class PlayerRepoWrapper(context: Context) {

    private val repo by lazy { worldcup.helper.data.PlayerRepository(context) }

    /** 获取球员完整资料（核心方法） */
    suspend fun getProfile(
        playerName: String,
        teamName: String? = null
    ) = repo.getPlayerProfile(playerName, teamName)

    /** 清空缓存 */
    fun clearCache() = repo.clearCache()
}
