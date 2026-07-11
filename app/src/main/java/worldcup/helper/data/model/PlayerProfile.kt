package worldcup.helper.data.model

/**
 * 球员赛季18项统计数据聚合（来源：api-sports /players?team=X&season=2026）
 */
data class PlayerSeasonStats(
    val rating: Double? = null,         // 场均评分
    val appearances: Int = 0,           // 出场次数
    val minutes: Int = 0,               // 总出场分钟
    val goals: Int = 0,                 // 总进球
    val assists: Int = 0,               // 总助攻
    val shotsTotal: Int = 0,            // 射门总数
    val shotsOnTarget: Int = 0,         // 射正数
    val passesTotal: Int = 0,           // 传球总数
    val passesKey: Int = 0,             // 关键传球
    val passesAccuracy: Int = 0,        // 传球成功数
    val tacklesTotal: Int = 0,          // 抢断
    val tacklesBlocks: Int = 0,         // 封堵
    val interceptions: Int = 0,         // 拦截
    val duelsTotal: Int = 0,            // 对抗总数
    val duelsWon: Int = 0,              // 对抗成功
    val dribblesSuccess: Int = 0,       // 过人成功
    val foulsDrawn: Int = 0,            // 被犯规
    val foulsCommitted: Int = 0,        // 犯规
    val yellowCards: Int = 0,           // 黄牌
    val redCards: Int = 0,              // 红牌
) {
    /** 射门转化率 */
    val shotAccuracy: Double
        get() = if (shotsTotal > 0) (shotsOnTarget.toDouble() / shotsTotal * 100) else 0.0
    /** 传球成功率 */
    val passAccuracy: Double
        get() = if (passesTotal > 0) (passesAccuracy.toDouble() / passesTotal * 100) else 0.0
    /** 对抗成功率 */
    val duelWinRate: Double
        get() = if (duelsTotal > 0) (duelsWon.toDouble() / duelsTotal * 100) else 0.0
    /** 每分钟进球效率 */
    val goalsPer90: Double
        get() = if (minutes > 0) (goals.toDouble() / minutes * 90) else 0.0
    /** 场均关键传球 */
    val keyPassesPerGame: Double
        get() = if (appearances > 0) (passesKey.toDouble() / appearances) else 0.0
}

/**
 * 球员高级统计数据（来源：BDL GOAT player_match_stats）
 */
data class PlayerAdvancedStats(
    val expectedGoals: Double? = null,          // xG
    val expectedAssists: Double? = null,        // xA
    val crossesTotal: Int? = null,              // 传中总数
    val crossesAccurate: Int? = null,            // 精准传中
    val longBallsTotal: Int? = null,             // 长传总数
    val longBallsAccurate: Int? = null,          // 精准长传
    val possessionLost: Int? = null,             // 丢球权
    val ballRecoveries: Int? = null,             // 球权恢复
    val aerialDuelsWon: Int? = null,             // 头球争顶赢
    val aerialDuelsLost: Int? = null,            // 头球争顶输
    val bigChancesCreated: Int? = null,          // 创造绝佳机会
    val bigChancesMissed: Int? = null,           // 错失绝佳机会
) {
    /** 头球争顶总数 */
    val aerialDuelsTotal: Int?
        get() = aerialDuelsWon?.let { w -> aerialDuelsLost?.let { l -> w + l } }
    /** 头球争顶成功率 */
    val aerialDuelsWinRate: Double?
        get() = aerialDuelsTotal?.let { total ->
            if (total > 0) (aerialDuelsWon!!.toDouble() / total * 100) else null
        }
    /** 传中成功率 */
    val crossAccuracy: Double?
        get() = crossesTotal?.let { total ->
            if (total > 0 && crossesAccurate != null) (crossesAccurate!!.toDouble() / total * 100) else null
        }
}

/**
 * 球员单场比赛数据（来源：api-sports fixtures/players）
 */
data class PlayerMatchProfile(
    val matchId: String = "",
    val opponent: String = "",
    val matchDate: String = "",
    val isHome: Boolean = true,
    val minutes: Int = 0,
    val rating: Double? = null,
    val goals: Int = 0,
    val assists: Int = 0,
    val shots: Int = 0,
    val shotsOn: Int = 0,
    val passes: Int = 0,
    val keyPasses: Int = 0,
    val tackles: Int = 0,
    val interceptions: Int = 0,
    val duelsWon: Int = 0,
    val dribblesSuccess: Int = 0,
    val foulsCommitted: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0,
    // BDL advanced (可选)
    val expectedGoals: Double? = null,
    val expectedAssists: Double? = null,
)

// ========================================================================
// 统一球员资料卡 — 聚合所有数据源的完整球员画像
// ========================================================================

/**
 * 统一球员资料卡
 *
 * 聚合所有数据源：
 * - 本地 players_2026.json → 基本信息
 * - 本地 football_data_person_id_map.json → ID映射
 * - 本地 trophies_cache.json → 荣誉
 * - football-data API → 世界杯累计统计
 * - api-sports API → 赛季18项统计 + 单场统计
 * - BDL GOAT API → xG/xA 高级数据
 */
data class PlayerProfile(
    // ═══════════════════════════════════════════
    // 第1层：基本信息（来源：players_2026.json）
    // ═══════════════════════════════════════════
    val id: Int = 0,                              // 本地编号
    val name: String = "",                         // 英文名
    val nameCn: String = "",                       // 中文名
    val jerseyNumber: Int = 0,                     // 球衣号码
    val position: String = "",                     // 英文位置 GK/DF/MF/FW
    val positionCn: String = "",                   // 中文位置
    val teamName: String = "",                     // 英格兰
    val teamNameCn: String = "",                   // 中国队英文
    val teamFifaCode: String = "",                 // FIFA代码（如 NOR）
    val club: String = "",                         // 俱乐部
    val photoUrl: String? = null,                  // 照片URL
    val injured: Boolean = false,                  // 是否受伤
    val marketValueMil: Double? = null,             // 身价（百万欧元）
    val heightCm: Int? = null,                     // 身高(cm)
    val age: Int? = null,                          // 年龄

    // ═══════════════════════════════════════════
    // 第2层：ID映射（来源：football_data_person_id_map.json）
    // ═══════════════════════════════════════════
    val personId: Int? = null,                     // football-data personId
    val apiSportsId: Int? = null,                  // api-sports squad ID
    val apiSportsTeamId: Int? = null,              // api-sports 球队ID

    // ═══════════════════════════════════════════
    // 第3层：世界杯累计统计（来源：football-data persons/{id}/matches）
    // ═══════════════════════════════════════════
    val wcMatchesOnPitch: Int = 0,                 // 出场次数
    val wcStartingXI: Int = 0,                     // 首发次数
    val wcMinutesPlayed: Int = 0,                  // 总出场分钟
    val wcGoals: Int = 0,                          // 世界杯总进球
    val wcOwnGoals: Int = 0,                       // 乌龙球
    val wcAssists: Int = 0,                        // 世界杯总助攻
    val wcPenalties: Int = 0,                      // 点球进球
    val wcSubbedIn: Int = 0,                       // 替补登场
    val wcSubbedOut: Int = 0,                      // 被换下
    val wcYellowCards: Int = 0,                    // 黄牌
    val wcRedCards: Int = 0,                       // 红牌

    // ═══════════════════════════════════════════
    // 第4层：赛季详细统计（来源：api-sports /players?team=X&season=2026）
    // ═══════════════════════════════════════════
    val seasonStats: PlayerSeasonStats? = null,

    // ═══════════════════════════════════════════
    // 第5层：各场比赛数据（来源：api-sports fixtures/players + BDL）
    // ═══════════════════════════════════════════
    val matchHistories: List<PlayerMatchProfile> = emptyList(),

    // ═══════════════════════════════════════════
    // 第6层：高级数据（来源：BDL GOAT player_match_stats）
    // ═══════════════════════════════════════════
    val advancedStats: PlayerAdvancedStats? = null,

    // ═══════════════════════════════════════════
    // 第7层：生涯荣誉（来源：trophies_cache.json）
    // ═══════════════════════════════════════════
    val honors: List<Honor> = emptyList(),

    // ═══════════════════════════════════════════
    // 第8层：射门分布图（来源：BDL match_shots）
    // ═══════════════════════════════════════════
    val shotMap: ShotMap? = null,
) {
    // 计算属性
    /** 场均进球 */
    val goalsPerMatch: Double
        get() = if (wcMatchesOnPitch > 0) wcGoals.toDouble() / wcMatchesOnPitch else 0.0

    /** 场均出场分钟 */
    val minutesPerMatch: Double
        get() = if (wcMatchesOnPitch > 0) wcMinutesPlayed.toDouble() / wcMatchesOnPitch else 0.0

    /** 性格简约描述（用于资料卡顶栏） */
    val positionDisplay: String
        get() = positionCn.ifEmpty {
            when (position) {
                "GK" -> "门将"; "DF" -> "后卫"; "MF" -> "中场"; "FW" -> "前锋"
                else -> position
            }
        }

    /** 俱乐部显示（含flag） */
    val clubDisplay: String
        get() = club.ifEmpty { "—" }

    /** 状态标签 */
    val statusTag: String
        get() = if (injured) "伤病" else "健康"
    val isInjured: Boolean get() = injured
}

// ========================================================================
// 辅助函数：PlayerSeasonStats → 攻/组/防三类指标
// ========================================================================

/**
 * 将赛季统计数据分解为三个维度，用于四象限展示
 */
data class PlayerStatsDimension(
    val attack: PlayerStatItem = PlayerStatItem(),
    val organization: PlayerStatItem = PlayerStatItem(),
    val defense: PlayerStatItem = PlayerStatItem(),
    val advanced: PlayerStatAdvancedItem = PlayerStatAdvancedItem(),
)

data class PlayerStatItem(
    val goals: Int = 0,
    val assists: Int = 0,
    val shots: Int = 0,
    val shotsOnTarget: Int = 0,
    val shotAccuracy: Double = 0.0,
    val passes: Int = 0,
    val keyPasses: Int = 0,
    val passAccuracy: Double = 0.0,
    val tackles: Int = 0,
    val interceptions: Int = 0,
    val duelsWon: Int = 0,
    val duelWinRate: Double = 0.0,
)

data class PlayerStatAdvancedItem(
    val expectedGoals: Double? = null,
    val expectedAssists: Double? = null,
    val crossesAccurate: Int? = null,
    val aerialDuelsWon: Int? = null,
    val possessionLost: Int? = null,
    val ballRecoveries: Int? = null,
)

/** 将 PlayerSeasonStats 分解为三维指标 */
fun PlayerSeasonStats.toDimensions(): PlayerStatsDimension {
    return PlayerStatsDimension(
        attack = PlayerStatItem(
            goals = goals, assists = assists,
            shots = shotsTotal, shotsOnTarget = shotsOnTarget,
            shotAccuracy = shotAccuracy
        ),
        organization = PlayerStatItem(
            passes = passesTotal, keyPasses = passesKey,
            passAccuracy = passAccuracy
        ),
        defense = PlayerStatItem(
            tackles = tacklesTotal, interceptions = interceptions,
            duelsWon = duelsWon, duelWinRate = duelWinRate
        )
    )
}

/** 用 BDL 高级数据补充四象限 */
fun PlayerStatsDimension.withAdvancedStats(advanced: PlayerAdvancedStats?): PlayerStatsDimension {
    if (advanced == null) return this
    return copy(
        advanced = PlayerStatAdvancedItem(
            expectedGoals = advanced.expectedGoals,
            expectedAssists = advanced.expectedAssists,
            crossesAccurate = advanced.crossesAccurate,
            aerialDuelsWon = advanced.aerialDuelsWon,
            possessionLost = advanced.possessionLost,
            ballRecoveries = advanced.ballRecoveries,
        )
    )
}

/**
 * 将 PlayerProfile 转换为五维雷达图数据（0-100 归一化）
 */
fun PlayerProfile.toRadarData(): RadarData {
    val goalsScore = (wcGoals.coerceAtMost(10) * 10).toFloat()
    val assistsScore = (wcAssists.coerceAtMost(5) * 20).toFloat()
    val appearanceScore = (wcMatchesOnPitch * 20).toFloat().coerceAtMost(100f)
    val disciplineScore = (100 - (wcYellowCards * 10 + wcRedCards * 30)).toFloat().coerceIn(0f, 100f)
    val staminaScore = if (wcMatchesOnPitch > 0) {
        (wcMinutesPlayed.toFloat() / wcMatchesOnPitch.toFloat() / 90f * 100f).coerceAtMost(100f)
    } else 0f

    return RadarData(
        labels = listOf("进球", "助攻", "出场", "纪律", "体能"),
        values = listOf(goalsScore, assistsScore, appearanceScore, disciplineScore, staminaScore)
    )
}

data class RadarData(
    val labels: List<String>,
    val values: List<Float>
)
