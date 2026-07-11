package worldcup.helper.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// ========================================================================
// 1. football-data.org  (FREE_PLUS_LIVESCORES, 10次/分)
// ========================================================================

interface FootballDataApi {
    @GET("v4/competitions/2000/matches")
    suspend fun getMatches(
        @Query("status") status: String = "",
        @Query("season") season: Int = 2026
    ): FootballMatchesResponse

    @GET("v4/competitions/2000/standings")
    suspend fun getStandings(@Query("season") season: Int = 2026): FootballStandingsResponse

    @GET("v4/competitions/2000/scorers")
    suspend fun getScorers(
        @Query("season") season: Int = 2026,
        @Query("limit") limit: Int = 50
    ): FootballScorersResponse

    @GET("v4/competitions/2000/teams")
    suspend fun getTeams(@Query("season") season: Int = 2026): FootballTeamsResponse

    @GET("v4/persons/{id}/matches")
    suspend fun getPersonMatches(
        @Path("id") personId: Int,
        @Query("competitions") competitions: String = "2000",
        @Query("season") season: Int = 2026,
        @Query("limit") limit: Int = 50
    ): FootballPersonMatchesResponse
}

// Tab D: 球队队徽 crest URL
data class FootballTeamsResponse(val count: Int = 0, val teams: List<FootballTeamDetail> = emptyList())
data class FootballTeamDetail(
    val id: Int, val name: String, val shortName: String? = null,
    val tla: String = "", val crest: String = "",
    val venue: FootballVenue? = null
)
data class FootballVenue(val name: String? = null, val city: String? = null)

// Response models for football-data.org
data class FootballMatchesResponse(val count: Int = 0, val matches: List<FootballMatch> = emptyList())
data class FootballMatch(
    val id: Int, val utcDate: String, val status: String,
    val matchday: Int? = null, val stage: String? = null, val group: String? = null,
    val homeTeam: FootballTeamBrief, val awayTeam: FootballTeamBrief,
    val score: FootballScore? = null
)
data class FootballTeamBrief(val id: Int, val name: String, val tla: String, val crest: String = "")
data class FootballScore(
    val winner: String? = null, val duration: String? = null,
    val fullTime: FootballHalfScore? = null, val halfTime: FootballHalfScore? = null
)
data class FootballHalfScore(val home: Int? = null, val away: Int? = null)

data class FootballStandingsResponse(val standings: List<FootballStandingTable> = emptyList())
data class FootballStandingTable(
    val stage: String? = null, val type: String? = null, val group: String? = null,
    val table: List<FootballStandingRow> = emptyList()
)
data class FootballStandingRow(
    val position: Int, val team: FootballTeamBrief,
    val playedGames: Int, val won: Int, val draw: Int, val lost: Int,
    val points: Int, val goalsFor: Int, val goalsAgainst: Int, val goalDifference: Int
)

data class FootballScorersResponse(val count: Int = 0, val scorers: List<FootballScorer> = emptyList())
data class FootballScorer(
    val player: FootballPlayerBrief, val team: FootballTeamBrief,
    val goals: Int, val assists: Int? = null, val playedMatches: Int? = null
)
data class FootballPlayerBrief(val id: Int, val name: String, val nationality: String? = null)

data class FootballPersonMatchesResponse(
    val person: FootballPersonBrief? = null,
    val aggregations: FootballAggregations? = null,
    val matches: List<FootballMatch> = emptyList()
)
data class FootballPersonBrief(val id: Int, val name: String)
data class FootballAggregations(
    val matchesOnPitch: Int = 0, val startingXI: Int = 0, val minutesPlayed: Int = 0,
    val goals: Int = 0, val ownGoals: Int = 0, val assists: Int = 0,
    val penalties: Int = 0, val subbedOut: Int = 0, val subbedIn: Int = 0,
    val yellowCards: Int = 0, val redCards: Int = 0
)

// ========================================================================
// 2. api-sports.io  (Pro, 7500次/天, $19/月)
// ========================================================================

interface ApiSportsApi {
    @GET("fixtures")
    suspend fun getFixtures(
        @Query("team") teamId: Int? = null,
        @Query("season") season: Int = 2026,
        @Query("league") league: Int = 1,
        @Query("date") date: String? = null,
        @Query("live") live: String? = null
    ): ApiSportsFixturesResponse

    @GET("fixtures")
    suspend fun getFixturesByDate(@Query("date") date: String, @Query("league") league: Int = 1): ApiSportsFixturesResponse

    @GET("fixtures/players")
    suspend fun getFixturePlayers(@Query("fixture") fixtureId: Int): ApiSportsPlayerStatsResponse

    @GET("fixtures/events")
    suspend fun getFixtureEvents(@Query("fixture") fixtureId: Int): ApiSportsEventsResponse

    @GET("fixtures/statistics")
    suspend fun getFixtureStatistics(@Query("fixture") fixtureId: Int): ApiSportsStatsResponse

    @GET("fixtures/lineups")
    suspend fun getFixtureLineups(@Query("fixture") fixtureId: Int): ApiSportsLineupResponse

    @GET("players/squads")
    suspend fun getTeamSquad(@Query("team") teamId: Int): ApiSportsSquadResponse

    // Tab D: 球员赛季累计统计（Pro 专属，league=1 必加）
    @GET("players")
    suspend fun getPlayersByTeam(
        @Query("team") teamId: Int,
        @Query("season") season: Int = 2026,
        @Query("league") league: Int = 1
    ): ApiSportsSeasonPlayersResponse

    // Tab D: 球员冠军列表
    @GET("trophies")
    suspend fun getPlayerTrophies(@Query("player") playerId: Int): ApiSportsTrophiesResponse

    // Tab C: 历史交锋记录
    @GET("fixtures/headtohead")
    suspend fun getHeadToHead(@Query("h2h") teamIds: String): ApiSportsH2HResponse
}

// Tab D: 球员赛季累计统计响应
data class ApiSportsSeasonPlayersResponse(val response: List<ApiSportsSeasonPlayer> = emptyList())
data class ApiSportsSeasonPlayer(
    val player: ApiSportsPlayerBrief,
    val statistics: List<ApiSportsSeasonStats> = emptyList()
)
data class ApiSportsSeasonStats(
    val team: ApiSportsTeamDetail? = null,
    val league: ApiSportsLeagueBrief? = null,
    val games: ApiSportsGamesStats? = null,
    val shots: ApiSportsShots? = null,
    val goals: ApiSportsGoals? = null,
    val passes: ApiSportsPasses? = null,
    val tackles: ApiSportsTackles? = null,
    val duels: ApiSportsDuels? = null,
    val dribbles: ApiSportsDribbles? = null,
    val fouls: ApiSportsFouls? = null,
    val cards: ApiSportsCards? = null,
    val penalty: ApiSportsPenalty? = null
)
data class ApiSportsLeagueBrief(val id: Int, val name: String, val country: String? = null, val logo: String? = null)
data class ApiSportsGamesStats(
    val appearences: Int? = null, val minutes: Int? = null,
    val lineups: Int? = null, val rating: String? = null,
    val captain: Boolean? = null
)
data class ApiSportsPenalty(
    val won: Int? = null, val committed: Int? = null,
    val scored: Int? = null, val missed: Int? = null, val saved: Int? = null
)

// Tab D: 球员冠军列表
data class ApiSportsTrophiesResponse(val response: List<ApiSportsTrophy> = emptyList())
data class ApiSportsTrophy(
    val league: String? = null, val country: String? = null,
    val season: String? = null, val place: String? = null
)

// Tab C: 历史交锋记录响应（复用比赛结构）
data class ApiSportsH2HResponse(val response: List<ApiSportsH2HMatch> = emptyList())
data class ApiSportsH2HMatch(
    val fixture: ApiSportsFixtureDetail? = null,
    val teams: ApiSportsTeams? = null,
    val goals: ApiSportsGoals? = null
)

// Response models for api-sports.io
data class ApiSportsFixturesResponse(val response: List<ApiSportsFixture> = emptyList())
data class ApiSportsFixture(
    val fixture: ApiSportsFixtureDetail,
    val teams: ApiSportsTeams,
    val goals: ApiSportsGoals? = null,
    val score: ApiSportsScoreDetail? = null,
    val status: ApiSportsStatus? = null
)
data class ApiSportsFixtureDetail(val id: Int, val date: String = "", val venue: ApiSportsVenue? = null, val status: ApiSportsStatus? = null)
data class ApiSportsVenue(val name: String? = null, val city: String? = null)
data class ApiSportsTeams(val home: ApiSportsTeamDetail, val away: ApiSportsTeamDetail)
data class ApiSportsTeamDetail(val id: Int, val name: String, val logo: String? = null)
data class ApiSportsGoals(val home: Int? = null, val away: Int? = null, val total: Int? = null, val assists: Int? = null, val saves: Int? = null, val conceded: Int? = null)
data class ApiSportsScoreDetail(val halftime: ApiSportsGoals? = null, val fulltime: ApiSportsGoals? = null)
data class ApiSportsStatus(val long: String? = null, val short: String? = null, val elapsed: Int? = null)

data class ApiSportsPlayerStatsResponse(val response: List<ApiSportsTeamPlayerStats> = emptyList())
data class ApiSportsTeamPlayerStats(
    val team: ApiSportsTeamDetail,
    val players: List<ApiSportsPlayerRaw> = emptyList()
)
data class ApiSportsPlayerRaw(
    val player: ApiSportsPlayerBrief,
    val statistics: List<ApiSportsPlayerStats> = emptyList()
)
data class ApiSportsPlayerBrief(val id: Int, val name: String, val photo: String? = null)
data class ApiSportsPlayerStats(
    val games: ApiSportsGames? = null, val shots: ApiSportsShots? = null,
    val goals: ApiSportsGoals? = null, val passes: ApiSportsPasses? = null,
    val tackles: ApiSportsTackles? = null, val duels: ApiSportsDuels? = null,
    val dribbles: ApiSportsDribbles? = null, val fouls: ApiSportsFouls? = null,
    val cards: ApiSportsCards? = null
)
data class ApiSportsGames(
    val minutes: Int? = null, val number: Int? = null, val position: String? = null,
    val rating: String? = null, val captain: Boolean? = null
)
data class ApiSportsShots(val total: Int? = null, val on: Int? = null)
data class ApiSportsPasses(val total: Int? = null, val key: Int? = null, val accuracy: String? = null)
data class ApiSportsTackles(val total: Int? = null, val blocks: Int? = null, val interceptions: Int? = null)
data class ApiSportsDuels(val total: Int? = null, val won: Int? = null)
data class ApiSportsDribbles(val attempts: Int? = null, val success: Int? = null)
data class ApiSportsFouls(val drawn: Int? = null, val committed: Int? = null)
data class ApiSportsCards(val yellow: Int? = null, val red: Int? = null)

data class ApiSportsEventsResponse(val response: List<ApiSportsEvent> = emptyList())
data class ApiSportsEvent(
    val time: ApiSportsEventTime? = null,
    val team: ApiSportsTeamDetail? = null,
    val player: ApiSportsEventPlayer? = null,
    val assist: ApiSportsEventPlayer? = null,
    val type: String? = null,       // Goal / Card / subst / Var
    val detail: String? = null,      // Yellow Card / Red Card / Normal Goal / ...
    val comments: String? = null     // VAR comments
)
data class ApiSportsEventTime(val elapsed: Int? = null, val extra: Int? = null)
data class ApiSportsEventPlayer(val id: Int? = null, val name: String? = null)

data class ApiSportsStatsResponse(val response: List<ApiSportsTeamStats> = emptyList())
data class ApiSportsTeamStats(
    val team: ApiSportsTeamDetail,
    val statistics: List<ApiSportsStatItem> = emptyList()
)
data class ApiSportsStatItem(val type: String? = null, val value: Any? = null)

data class ApiSportsLineupResponse(val response: List<ApiSportsLineupDetail> = emptyList())
data class ApiSportsLineupDetail(
    val team: ApiSportsTeamDetail,
    val formation: String? = null,           // "4-4-2"
    val startXI: List<ApiSportsLineupPlayer> = emptyList(),
    val substitutes: List<ApiSportsLineupPlayer> = emptyList(),
    val coach: ApiSportsCoach? = null
)
data class ApiSportsLineupPlayer(
    val player: ApiSportsPlayerBrief,
    val grid: String? = null  // "2:3" = row:col on formation grid
)
data class ApiSportsCoach(val name: String? = null)

data class ApiSportsSquadResponse(val response: List<ApiSportsSquadTeam> = emptyList())
data class ApiSportsSquadTeam(val team: ApiSportsTeamDetail, val players: List<ApiSportsSquadPlayer> = emptyList())
data class ApiSportsSquadPlayer(
    val id: Int, val name: String, val age: Int? = null,
    val number: Int? = null, val position: String? = null,
    val photo: String? = null
)

// ========================================================================
// 3. BALLDONTLIE FIFA GOAT  ($39.99/月, 600次/分)
// ========================================================================

interface BalldontlieApi {
    // Tab A: 比赛列表（获取 BDL match_id）
    @GET("fifa/worldcup/v1/matches")
    suspend fun getMatches(
        @Query("seasons[]") seasons: List<Int> = listOf(2026),
        @Query("team_ids[]") teamIds: List<Int>? = null,
        @Query("status") status: String? = null
    ): BdlMatchListResponse

    // Tab A: 球队列表（获取 BDL team_id）
    @GET("fifa/worldcup/v1/teams")
    suspend fun getTeams(@Query("seasons[]") seasons: List<Int> = listOf(2026)): BdlTeamListResponse

    @GET("fifa/worldcup/v1/player_match_stats")
    suspend fun getPlayerMatchStats(@Query("match_ids[]") matchIds: List<Int>): BdlStatsListResponse

    @GET("fifa/worldcup/v1/match_lineups")
    suspend fun getMatchLineups(@Query("match_ids[]") matchIds: List<Int>): BdlLineupListResponse

    @GET("fifa/worldcup/v1/match_events")
    suspend fun getMatchEvents(@Query("match_ids[]") matchIds: List<Int>): BdlEventListResponse

    @GET("fifa/worldcup/v1/team_match_stats")
    suspend fun getTeamMatchStats(@Query("match_ids[]") matchIds: List<Int>): BdlTeamStatsListResponse

    @GET("fifa/worldcup/v1/match_best_players")
    suspend fun getMatchBestPlayers(@Query("match_ids[]") matchIds: List<Int>): BdlBestPlayersListResponse

    @GET("fifa/worldcup/v1/match_momentum")
    suspend fun getMatchMomentum(@Query("match_ids[]") matchIds: List<Int>): BdlMomentumListResponse

    // Tab D: 射门分布图 (Shot Map)
    @GET("fifa/worldcup/v1/match_shots")
    suspend fun getMatchShots(@Query("match_ids[]") matchIds: List<Int>): BdlShotListResponse

    // Tab D: 小组积分榜 Fallback
    @GET("fifa/worldcup/v1/group_standings")
    suspend fun getGroupStandings(@Query("seasons[]") seasons: List<Int> = listOf(2026)): BdlGroupStandingsResponse
}


// BDL 比赛列表模型（Tab A 用）
data class BdlMatchListResponse(val data: List<BdlMatch> = emptyList())
data class BdlMatch(
    val id: Int, val datetime: String? = null, val status: String? = null,
    val clock_display: String? = null, val clock_seconds: Int? = null,
    val home_team: BdlTeamBrief? = null, val away_team: BdlTeamBrief? = null,
    val home_score: Int? = null, val away_score: Int? = null,
    val home_formation: String? = null, val away_formation: String? = null
)
data class BdlTeamBrief(val id: Int? = null, val name: String? = null, val abbreviation: String? = null)

// BDL 球队列表模型
data class BdlTeamListResponse(val data: List<BdlTeam> = emptyList())
data class BdlTeam(val id: Int, val name: String, val abbreviation: String? = null)

// Tab D 新模型
data class BdlRosterListResponse(val data: List<BdlRosterPlayer> = emptyList())
data class BdlRosterPlayer(
    val id: Int? = null, val team_id: Int? = null,
    val player: BdlRosterPlayerDetail? = null,
    val goals: Int? = null, val assists: Int? = null,
    val appearances: Int? = null, val minutes_played: Int? = null,
    val yellow_cards: Int? = null, val red_cards: Int? = null
)
data class BdlRosterPlayerDetail(
    val id: Int, val name: String, val jersey_number: String? = null,
    val position: String? = null, val height_cm: Int? = null,
    val date_of_birth: String? = null, val country_code: String? = null
)

data class BdlStadiumListResponse(val data: List<BdlStadium> = emptyList())
data class BdlStadium(
    val id: Int? = null, val name: String? = null, val city: String? = null,
    val country: String? = null, val capacity: Int? = null,
    val latitude: Double? = null, val longitude: Double? = null
)

data class BdlGroupStandingsResponse(val data: List<BdlGroupStanding> = emptyList())
data class BdlGroupStanding(
    val season: BdlSeasonBrief? = null,
    val team: BdlTeamBrief? = null,
    val group: BdlGroupBrief? = null,
    val position: Int? = null,
    val played: Int? = null, val won: Int? = null, val drawn: Int? = null, val lost: Int? = null,
    val goals_for: Int? = null, val goals_against: Int? = null,
    val goal_difference: Int? = null, val points: Int? = null
)
data class BdlSeasonBrief(val id: Int? = null, val year: Int? = null)
data class BdlGroupBrief(val id: Int? = null, val name: String? = null)

// Response models for BDL
data class BdlStatsListResponse(val data: List<BdlPlayerMatchStats> = emptyList())
data class BdlPlayerMatchStats(
    val id: Int? = null, val match_id: Int? = null, val player_id: Int? = null,
    val player_name: String? = null, val team_id: Int? = null,
    val minutes_played: Int? = null, val rating: String? = null,
    val goals: Int? = null, val assists: Int? = null,
    val total_shots: Int? = null, val shots_on_target: Int? = null,
    val passes_total: Int? = null, val passes_accurate: Int? = null,
    val key_passes: Int? = null, val tackles: Int? = null,
    val interceptions: Int? = null, val clearances: Int? = null,
    val fouls_committed: Int? = null, val dribbles_success: Int? = null,
    val duels_won: Int? = null, val offsides: Int? = null,
    val expected_goals: Double? = null, val expected_assists: Double? = null,
    val crosses_total: Int? = null, val crosses_accurate: Int? = null,
    val long_balls_total: Int? = null, val long_balls_accurate: Int? = null,
    val possession_lost: Int? = null, val ball_recoveries: Int? = null,
    val duels_lost: Int? = null, val aerial_duels_won: Int? = null, val aerial_duels_lost: Int? = null
)

data class BdlLineupListResponse(val data: List<BdlLineupPlayer> = emptyList())
data class BdlLineupPlayer(
    val id: Int? = null, val match_id: Int? = null, val team_id: Int? = null,
    val player_id: Int? = null, val player_name: String? = null,
    val shirt_number: Int? = null, val position: String? = null,
    val formation: String? = null, val is_starter: Boolean? = null, val is_home: Boolean? = null
)

data class BdlEventListResponse(val data: List<BdlEvent> = emptyList())
data class BdlEvent(
    val id: Int? = null, val match_id: Int? = null,
    val minute: Int? = null, val type: String? = null,
    val team_id: Int? = null, val player_name: String? = null,
    val detail: String? = null
)

data class BdlTeamStatsListResponse(val data: List<BdlTeamMatchStats> = emptyList())
data class BdlTeamMatchStats(
    val id: Int? = null, val match_id: Int? = null, val team_id: Int? = null,
    val is_home: Boolean? = null, val possession: Int? = null,
    val total_shots: Int? = null, val shots_on_target: Int? = null,
    val corners: Int? = null, val fouls: Int? = null,
    val yellow_cards: Int? = null, val red_cards: Int? = null,
    val offsides: Int? = null, val expected_goals: Double? = null
)

data class BdlBestPlayersListResponse(val data: List<BdlBestPlayer> = emptyList())
data class BdlBestPlayer(
    val id: Int? = null, val match_id: Int? = null, val player_id: Int? = null,
    val player_name: String? = null, val team_id: Int? = null,
    val rating: String? = null, val reason: String? = null
)

data class BdlMomentumListResponse(val data: List<BdlMomentumPoint> = emptyList())
data class BdlMomentumPoint(val minute: Int? = null, val home: Double? = null, val away: Double? = null)

// Shot Map (射门分布图) 模型
data class BdlShotListResponse(val data: List<BdlShot> = emptyList())
data class BdlShot(
    val match_id: Int? = null,
    val player_id: Int? = null,
    val player_name: String? = null,
    val team_id: Int? = null,
    val player_x: Double? = null,
    val player_y: Double? = null,
    val goal_mouth_x: Double? = null,
    val goal_mouth_y: Double? = null,
    val xg: Double? = null,
    val xgot: Double? = null,
    val shot_type: String? = null,
    val body_part: String? = null,
    val is_goal: Boolean = false,
    val minute: Int? = null,
    val result: String? = null    // "goal" / "saved" / "blocked" / "missed" / "shotonpost" 
)
