package worldcup.helper.data.model

/**
 * 统一比赛模型 (requirement_list.md §C.1)
 * 整合 football-data.org + BDL GOAT + api-sports.io 数据
 */
data class UnifiedMatch(
    val id: String,
    val homeTeamEn: String,
    val homeTeamCn: String,
    val awayTeamEn: String,
    val awayTeamCn: String,
    val homeFifaCode: String,
    val awayFifaCode: String,
    val group: String? = null,
    val matchday: Int? = null,
    val round: String,
    val type: String = "group",         // group / r32 / r16 / qf / sf / third / final
    val datetime: String,                // ISO 8601 北京时间
    val stadium: String = "",
    val stadiumCity: String = "",
    val status: String = "SCHEDULED",    // SCHEDULED / LIVE / HALF_TIME / FINISHED / PENALTIES
    val homeScore: Int = 0,
    val awayScore: Int = 0,
    val halfTimeHome: Int? = null,
    val halfTimeAway: Int? = null,
    val penaltiesHome: Int? = null,      // BDL 独有
    val penaltiesAway: Int? = null,      // BDL 独有
    val clockDisplay: String? = null,    // BDL 独有 "67:23"
    val homeFormation: String? = null,   // BDL 独有 "4-3-3"
    val awayFormation: String? = null,   // BDL 独有 "4-2-3-1"
    val stadiumCapacity: Int? = null,
    val homeTeamCrest: String? = null,   // football-data.org crest URL
    val awayTeamCrest: String? = null,   // football-data.org crest URL
    val refereeName: String? = null,
    val refereeNationality: String? = null
)

/**
 * 统一球员模型 (requirement_list.md §C.2)
 */
data class UnifiedPlayer(
    val id: Int,
    val name: String,
    val nameCn: String = "",
    val jerseyNumber: Int,
    val position: String,               // GK / DF / MF / FW
    val teamName: String,
    val teamFifaCode: String,
    val teamIdFootballData: Int? = null,
    val teamIdApiSports: Int? = null,
    val teamIdBdl: Int? = null,
    val photoUrl: String? = null,        // api-sports.io
    val dateOfBirth: String? = null,
    val heightCm: Int? = null,
    val club: String = "",
    val nationality: String? = null,
    val totalGoals: Int = 0,
    val totalAssists: Int = 0,
    val totalAppearances: Int = 0,
    val totalMinutesPlayed: Int = 0,
    val totalYellowCards: Int = 0,
    val totalRedCards: Int = 0,
    val avgRating: Double? = null,
    val marketValueMil: Double? = null,
    val injured: Boolean = false,
    val honors: List<Honor> = emptyList()
)

data class Honor(
    val title: String,
    val year: String,
    val category: String = ""            // 冠军/最佳射手等
)

/**
 * 球员单场统计 (Living_Module_Integration.md §4.2)
 */
data class PlayerMatchStats(
    val matchId: String,
    val playerId: Int,
    val rating: Double?,                 // api-sports
    val minutesPlayed: Int,
    val goals: Int,
    val assists: Int,
    val shotsTotal: Int?,
    val shotsOnTarget: Int?,
    val passesTotal: Int?,
    val passesAccurate: Int?,
    val keyPasses: Int?,
    val tacklesTotal: Int?,
    val interceptions: Int?,
    val clearances: Int?,
    val foulsCommitted: Int?,
    val dribblesSuccess: Int?,
    val duelsWon: Int?,
    val offsides: Int?,
    // BDL GOAT 独有字段
    val expectedGoals: Double?,          // xG
    val expectedAssists: Double?,        // xA
    val crossesTotal: Int?,
    val crossesAccurate: Int?,
    val longBallsTotal: Int?,
    val longBallsAccurate: Int?,
    val possessionLost: Int?,
    val ballRecoveries: Int?,
    val duelsLost: Int?,
    val aerialDuelsWon: Int?,
    val aerialDuelsLost: Int?
)

/**
 * 射门数据 (Living_Module_Integration.md §4.1 / BDL match_shots)
 */
data class ShotEntry(
    val matchId: String,
    val playerId: Int,
    val playerName: String,
    val teamName: String,
    val minute: Int,
    val xGoal: Double?,                  // xG value
    val x: Double,                       // 射门坐标 (0-100)
    val y: Double,                       // 射门坐标 (0-100)
    val isGoal: Boolean,
    val isOnTarget: Boolean,
    val bodyPart: String? = null,        // head / left_foot / right_foot
    val situation: String? = null        // open_play / set_piece / penalty / free_kick / counter
)

/**
 * 球队模型 (requirement_list.md §C.3)
 */
data class Team(
    val id: Int,
    val nameEn: String,
    val nameCn: String,
    val fifaCode: String,
    val iso2: String,
    val crestUrl: String? = null,
    val group: String,
    val confederation: String = "",
    val players: List<UnifiedPlayer> = emptyList()
)

/**
 * 比赛预测模型
 */
data class MatchPrediction(
    val matchId: String,
    val homeWinProb: Int,
    val drawProb: Int,
    val awayWinProb: Int,
    val predictedScore: String,
    val confidence: String,
    val keyFactors: List<String>,
    val analysis: String,
    val playersToWatch: List<PlayerWatch>,
    val source: String = "local"         // llm / monte_carlo / local
)

data class PlayerWatch(
    val team: String,
    val player: String,
    val reason: String = ""
)

/**
 * 实时比分模型
 */
data class LiveScore(
    val matchId: String,
    val status: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val clock: String?,
    val homeScorers: List<String>?,
    val awayScorers: List<String>?
)

/**
 * 球员深入分析 (Living_Module_Integration.md §6)
 */
data class DeepPlayerAnalysis(
    val playerId: Int,
    val playerName: String,
    val teamName: String,
    val totalMatches: Int,
    val totalMinutes: Int,
    val goals: Int,
    val assists: Int,
    val shotsOnTargetPerGame: Double,
    val passAccuracy: Double,
    val keyPassesPerGame: Double,
    val tacklesPerGame: Double,
    val interceptionsPerGame: Double,
    val clearancesPerGame: Double,
    val dribbleSuccessRate: Double,
    val aerialDuelsWinRate: Double,
    val expectedGoalsTotal: Double?,
    val expectedAssistsTotal: Double?,
    val crossesAccuracy: Double?,
    val longBallsAccuracy: Double?,
    val possessionLostPerGame: Double?,
    val ballRecoveriesPerGame: Double?
)

/**
 * 排行榜条目
 */
data class RankingEntry(
    val rank: Int,
    val playerName: String,
    val playerNameCn: String,
    val teamName: String,
    val teamFifaCode: String,
    val value: Double,
    val matches: Int,
    val photoUrl: String? = null
)

/**
 * 小组积分榜行
 */
data class StandingRow(
    val rank: Int,
    val teamName: String,
    val teamNameCn: String,
    val fifaCode: String,
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDiff: Int,
    val points: Int,
    val isPromoted: Boolean = false,
    val isBestThird: Boolean = false
)

/**
 * 射手榜行（API优先模型）
 */
data class ScorerRow(
    val rank: Int,
    val nameCn: String,
    val teamName: String,
    val goals: Int,
    val assists: Int
)

/**
 * 奖牌荣誉模型 (TheSportsDB)
 */
data class SportHonor(
    val id: String,
    val title: String,
    val year: String,
    val team: String = ""
)

// ========================================================================
// MatchRepo 模型 (new_framework.md §4.1)
// ========================================================================

/**
 * 比赛事件模型
 */
data class MatchEvent(
    val elapsed: Int,
    val type: String,              // goal / yellow_card / red_card / second_yellow / substitution / var
    val playerName: String,
    val assistName: String = "",
    val teamName: String,
    val detail: String = "",
    val score: String = ""
)

/**
 * 球队统计对比
 */
data class TeamStatComparison(
    val teamName: String,
    val statistics: List<StatItem>
)

data class StatItem(
    val key: String,
    val value: String
)

/**
 * 球队阵容+阵型
 */
data class TeamLineup(
    val teamName: String,
    val formation: String,
    val players: List<LineupPlayer>,
    val substitutes: List<LineupPlayer>
)

data class LineupPlayer(
    val name: String,
    val number: Int,
    val position: String
)

/**
 * 历史交锋记录
 */
data class HeadToHead(
    val totalMatches: Int,
    val homeWins: Int,
    val draws: Int,
    val awayWins: Int,
    val matches: List<H2hMatch>
)

data class H2hMatch(
    val date: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int
)

/**
 * 全场最佳球员结果
 */
data class BestPlayerResult(
    val playerId: Int,
    val teamId: Int,
    val rating: Double,
    val isManOfMatch: Boolean,
    val reason: String
)
