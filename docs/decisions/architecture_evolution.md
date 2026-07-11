# WorldCupScan — 新架构框架（完整实现版）

> **版本**: v2.5 | **日期**: 2026-06-27
> **核心理念**: 从"以比赛为中心"转向**"以球员-球队为中心"**，统一数据层驱动全 Tab，点击球员/球队任意位置打开**同一个**详情页
> **v2.5 更新 (2026-06-27)**: 五大Bug修复——(1)gridRow复用崩溃→每行新建LinearLayout；(2)中场时钟冻结→新增liveStatusCache阶段感知(1H/HT/2H/ET/PEN/FT/AET)；(3)加时赛显示→"ET xx′ 🟡 加时赛"；(4)点球大战→"⚫ 点球大战"冻结时钟；(5)球员名字兜底→名字为空显示"#号码"。computeUiState/pollAll/updateClockAndScore 同步写入 liveStatusCache。
> **v2.3 更新 (2026-06-26)**: 球员卡新增射门分布图设计规格 — BDL `match_shots` → Canvas ShotMapView，含xG聚合+进球标记+点击交互。详见 `player_card_shot_map.md`。
> **v2.1 更新 (2026-06-25)**: Tab D 精益求精——球员榜从4榜扩展到8榜(新增🎯射正/🔑关键传球/💪抢断/⚡过人)，球员名/球队名点击跳转对应详情页；淘汰赛对阵图重构为左右半区结构；球队Tab中文拼音排序；球队详情页增加战绩概要、近况W/D/L条、下场卡片、时间感知赛程状态；球员头像Coil CircleCrop圆形裁剪+号码高亮。
> **与 requirement_list.md 的关系**：`requirement_list.md` = **功能需求**（做什么），`new_framework.md` = **架构蓝图**（怎么做）。两者配合，缺一不可。
> **与 DATA_CLASSIFICATION.md 的关系**：`DATA_CLASSIFICATION.md` = **数据来源规则**（从哪拿数据），本文档定义如何组织这些数据。
> **核心原则**：最高等级确保真实性和实时性，API 优先是底线，本地只做兜底和静态数据。

---

## 目录

1. [架构总览](#一架构总览)
2. [代码结构](#二代码结构--全项目文件清单)
3. [SharedRepository — 唯一数据入口](#三sharedrepository--唯一数据入口)
4. [五个子 Repository](#四五个子-repository-完整实现)
5. [通用页面（跨 Tab 复用）](#五通用页面跨-tab-复用)
6. [四类基础资料卡](#六四类基础资料卡)
7. [点击跳转协议（全 Tab 统一）](#七点击跳转协议全-tab-统一)
8. [Tab A 重构细节](#八tab-a-实时赛况重构细节)
9. [Tab B 重构细节](#九tab-b-ai-对话重构细节)
10. [Tab C 重构细节](#十tab-c-赛程查询重构细节)
11. [Tab D 重构细节](#十一tab-d-比赛数据重构细节)
12. [API 资源配置](#十二api-资源配置)
13. [数据分类速查](#十三数据分类速查)

---

## 一、架构总览

```
                    ┌─────────────────────────────────┐
                    │        SharedRepository          │
                    │   (全局单例，全项目唯一数据源)      │
                    └──┬──────────┬──────────┬─────────┘
                       │          │          │
              ┌────────┴──┐ ┌────┴─────┐ ┌─┴──────────┐
              │PlayerRepo │ │ TeamRepo  │ │ MatchRepo   │
              │ (已有实现) │ │ (新建)    │ │ (新建)      │
              └─────┬─────┘ └────┬──────┘ └──────┬─────┘
                    │            │                │
                    └──────┬─────┴────────┬──────┘
                           │              │
                    ┌──────▼──────┐ ┌─────▼──────┐
                    │StandingRepo │ │ StadiumRepo │
                    │ (新建)      │ │ (包装已有)   │
                    └─────────────┘ └─────────────┘
```

### 数据流向图

```
各 Tab UI ─→ SharedRepository.getInstance(context)
                  │
                  ├── .matches  → MatchRepo    → API优先(30s轮询) → matches.json 兜底
                  │                              football-data.org + api-sports.io + BDL
                  ├── .standings → StandingRepo → API优先 → BDL → 本地计算
                  │                              football-data.org + api-sports Pro
                  ├── .teams     → TeamRepo     → players_2026.json + football-data crest
                  │                              (API一次缓存→永久本地)
                  ├── .stadiums  → StadiumRepo  → bdl_stadiums.json (纯本地)
                  ├── .shotMap   → ShotMapRepo   → BDL match_shots（射门图，赛后）
                  └── .players   → PlayerRepoWrapper → PlayerRepository(已有)
                                   players_2026.json + football-data API + api-sports API + TrophyData
```

---

## 二、代码结构 — 全项目文件清单

### 2.1 数据层（新建）

```
MainApp/app/src/main/java/worldcup/helper/data/repos/
├── SharedRepository.kt      ← 全局单例，全项目唯一入口
├── MatchRepo.kt             ← 比赛：赛程/比分/事件/统计/阵容/H2H/MVP/轮询
├── StandingRepo.kt          ← 积分榜+四榜：API优先三源降级
├── TeamRepo.kt              ← 球队：资料/阵容/队徽/赛程/伤病
└── StadiumRepo.kt           ← 场馆：包装 StadiumData

MainApp/app/src/main/java/worldcup/helper/data/model/Models.kt
  (新增以下 data class)
  ├── MatchEvent             ← 比赛事件模型
  ├── TeamStatComparison     ← 球队统计对比
  ├── StatItem               ← 统计条目
  ├── TeamLineup             ← 球队阵容+阵型
  ├── LineupPlayer           ← 阵容球员
  ├── HeadToHead             ← 历史交锋
  ├── H2hMatch               ← 交锋单场
  └── BestPlayerResult       ← 全场最佳
```

### 2.2 UI 层（已有，已改造）

```
MainApp/app/src/main/java/worldcup/helper/ui/
├── match/
│   ├── MatchDetailActivity.kt    ← ✅ 已改造：球队名可点击→TeamDetailActivity
│   │                                   球员弹窗→"查看完整资料"→PlayerDetailActivity
│   └── PlayerDetailActivity.kt   ← ✅ 已改造：添加 EXTRA_PLAYER_NAME / EXTRA_TEAM_NAME
├── teams/
│   └── TeamDetailActivity.kt     ← ✅ 已改造：使用 TeamRepo，替代直接JSON读取
├── live/
│   └── LiveFragment.kt           ← ✅ 已改造：SharedRepository 替代 MatchData/StadiumData
├── data/
│   └── DataFragment.kt           ← ✅ 已改造：SharedRepository+StandingRepo 替代 DataRepository
└── ai/
    └── ChatViewModel.kt          ← ✅ 已改造：stadiums→sharedRepo.stadiums
```

### 2.3 已有但无需改动的核心基础设施

```
MainApp/app/src/main/java/worldcup/helper/data/
├── PlayerRepository.kt       ← ✅ 已有，被 SharedRepository 包装成 PlayerRepoWrapper
├── DataRepository.kt         ← ⚠️ 保留旧代码，新代码应使用 SharedRepository
├── MatchData.kt              ← 被 MatchRepo 内部使用
├── PlayerDatabase.kt         ← 被 TeamRepo 和 ChatViewModel 内部使用
├── StadiumData.kt            ← 被 StadiumRepo 包装
├── TrophyData.kt             ← ChatViewModel 保留直接使用（纯本地）
└── PredictionData.kt         ← MatchRepo 内部使用

MainApp/app/src/main/java/worldcup/helper/network/
├── ApiInterfaces.kt          ← 3 套 Retrofit API 接口定义（FootballDataApi / ApiSportsApi / BalldontlieApi）
└── LiveApiClient.kt          ← Retrofit 客户端单例

MainApp/app/src/main/java/worldcup/helper/ui/
├── teams/TeamsFragment.kt    ← 球队网格（Tab D 子Tab）
└── data/BracketView.kt       ← 淘汰赛对阵图 Canvas
```

---

## 三、SharedRepository — 唯一数据入口

### 3.1 获取方式（所有 Tab 统一）

```kotlin
import worldcup.helper.data.repos.SharedRepository

// 任何地方获取
val repo = SharedRepository.getInstance(context)

// 子 Repository 访问
repo.matches.getLiveMatches()     // MatchRepo
repo.standings.getStandings()     // StandingRepo
repo.teams.getTeamDetail("Argentina")  // TeamRepo
repo.stadiums.searchStadiums("AT&T")   // StadiumRepo
repo.players.getProfile("梅西")   // PlayerRepoWrapper → PlayerRepository
```

### 3.2 快捷方法

```kotlin
val repo = SharedRepository.getInstance(context)
repo.hasLiveMatch()          // 判断是否有直播
repo.getLiveMatches()        // 当前直播比赛列表
repo.getNextMatchday()       // 无直播时的下一比赛日
```

### 3.3 单例设计

```kotlin
class SharedRepository private constructor(context: Context) {
    companion object {
        fun getInstance(context: Context): SharedRepository  // 线程安全双重检查锁定
        fun resetInstance()  // 仅测试用
    }
    val matches: MatchRepo by lazy { MatchRepo(appContext) }
    val standings: StandingRepo by lazy { StandingRepo(appContext) }
    val teams: TeamRepo by lazy { TeamRepo(appContext) }
    val stadiums: StadiumRepo by lazy { StadiumRepo(appContext) }
    val shotMap: ShotMapRepo by lazy { ShotMapRepo(appContext) }
    val players: PlayerRepoWrapper by lazy { PlayerRepoWrapper(appContext) }
    val shotMap: ShotMapRepo by lazy { ShotMapRepo(appContext) }
}
```

---

## 四、五个子 Repository — 完整实现

### 4.1 MatchRepo — 比赛数据

**包路径**: `worldcup.helper.data.repos.MatchRepo`

**数据源**: football-data.org + api-sports.io + BDL GOAT + `matches.json` + `predictions.json`
**API等级**: 🔴 必须API

**完整方法列表**:

```kotlin
// === 赛程列表（本地 matches.json） ===
getAllMatches(): List<UnifiedMatch>
getMatchById(matchId: String): UnifiedMatch?
searchMatches(query: String): List<UnifiedMatch>

// === 分类赛程 ===
getLiveMatches(): List<UnifiedMatch>       // 🔴 用于 Tab A 直播判断
hasLiveMatch(): Boolean
getFinishedMatches(): List<UnifiedMatch>
getUpcomingMatches(): List<UnifiedMatch>
getNextMatchday(): List<UnifiedMatch>      // 🔴 用于 Tab A 无直播时
getMatchesGroupedByDate(): Map<String, List<UnifiedMatch>>

// === 球队赛程（供 TeamRepo 调用） ===
getTeamMatches(teamFifaCode: String): List<UnifiedMatch>

// === 比赛预测 ===
getPrediction(matchId: String): MatchPrediction?

// === API 优先 — 实时比分（30秒轮询） ===
fetchLiveScoresFromApi(): List<LiveScore>       // football-data /matches?status=LIVE
refreshAllScoresFromApi(): List<LiveScore>       // football-data /matches

// === API 优先 — 比赛事件 ===
fetchEventsFromApi(fixtureId: Int): List<MatchEvent>  // api-sports /fixtures/events

// === API 优先 — 比赛统计对比 ===
fetchStatisticsFromApi(fixtureId: Int): List<TeamStatComparison>  // api-sports /fixtures/statistics

// === API 优先 — 阵容+阵型 ===
fetchLineupsFromApi(fixtureId: Int): List<TeamLineup>  // api-sports /fixtures/lineups

// === API 优先 — 历史交锋 H2H ===
fetchH2hFromApi(homeTeamId: Int, awayTeamId: Int): HeadToHead?  // api-sports /fixtures/headtohead

// === API 优先 — 全场最佳 MVP ===
fetchBestPlayerFromApi(bdlMatchId: Int): BestPlayerResult?  // BDL /match_best_players

// === 轮询配置 ===
getPollingInterval(status: String): Long  // LIVE=30s / SCHEDULED=5min / FINISHED=0
```

**API 调用配置**:
| 调用 | 所属 API | 请求频率 | 失败兜底 |
|:-----|:---------|:---------|:---------|
| 实时比分 | football-data | 30秒轮询(LIVE) | matches.json |
| 比赛事件 | api-sports | 点开详情时 | match_events.json |
| 比赛统计 | api-sports | 点开详情时 | 本地预设值 |
| 阵容+阵型 | api-sports | 点开详情时 | players_2026.json |
| H2H | api-sports | 点开详情时 | 本地计算近5场 |
| 全场最佳 | BDL | 赛后拉取 | 显示"暂无" |

---

### 4.2 StandingRepo — 积分榜/球员榜

**包路径**: `worldcup.helper.data.repos.StandingRepo`

**数据源**: football-data.org + api-sports.io + BDL GOAT + `matches.json`
**API等级**: 🔴 必须API

```kotlin
// === 小组积分榜（三源降级） ===
suspend fun getStandings(): Map<String, List<StandingRow>>
  // Tier 1: football-data /standings
  // Tier 2: BDL /group_standings
  // Tier 3: matches.json 本地计算

// === 射手榜（API优先） ===
suspend fun getScorers(): List<ScorerRow>
  // API: football-data /scorers → 本地: match_events.json

// === 赛季排名（助攻/评分/牌 — api-sports Pro 优先） ===
suspend fun getSeasonRankings(context: Context): List<SeasonRanking>
  // API: api-sports players?team=X&season=2026 (遍历48支已完赛球队)
```

---

### 4.3 TeamRepo — 球队数据

**包路径**: `worldcup.helper.data.repos.TeamRepo`

**数据源**: `players_2026.json` + football-data.org + MatchRepo
**API等级**: 🟡 条件API(队徽) + 🟢 基础本地(阵容/球队资料)

```kotlin
// === 球队列表 ===
getAllTeams(): List<TeamBasicInfo>
getTeamsByGroup(group: String): List<TeamBasicInfo>
getAllGroups(): List<String>
findTeam(query: String): TeamBasicInfo?
getTeamByFifaCode(fifaCode: String): TeamBasicInfo?

// === 球队详情（含阵容+赛程） ===
getTeamDetail(teamName: String): TeamDetail?
getTeamRoster(teamName: String): List<PlayerSummary>   // 按位置分组
getTeamRosterSummary(teamName: String): String          // 文本摘要（供Tab B）

// === 队徽 crest（API一次缓存） ===
suspend fun getCrestUrls(): Map<String, String>   // football-data /teams
suspend fun getCrestUrl(fifaCode: String): String?

// === 伤病 ===
getInjuredPlayers(): List<PlayerSummary>

// === 赛程（从 MatchRepo 同步） ===
getTeamSchedule(fifaCode: String): List<UnifiedMatch>
```

**模型类**:
```kotlin
data class TeamBasicInfo(
    val nameEn: String, val nameCn: String, val fifaCode: String,
    val iso2: String, val group: String, val flagUrl: String = "",
    val crestUrl: String? = null, val countryCode: String = "", val elo: Int? = null
)
data class TeamDetail(
    val basic: TeamBasicInfo, val players: List<PlayerSummary>,
    val schedule: List<UnifiedMatch>, val elo: Int? = null
)
data class PlayerSummary(
    val name: String, val nameCn: String, val jerseyNumber: Int,
    val position: String, val positionCn: String, val club: String,
    val photoUrl: String?, val injured: Boolean, val marketValueMil: Double? = null,
    val apiSportsId: Int? = null
)
```

---

### 4.4 StadiumRepo — 场馆数据

**包路径**: `worldcup.helper.data.repos.StadiumRepo`

**数据源**: `bdl_stadiums.json`（16座球场，静态数据）
**API等级**: 🟢 基础本地

```kotlin
searchStadiums(query: String): List<StadiumData.Stadium>
findStadium(name: String): StadiumData.Stadium?
getAllStadiumsSummary(): String
getAllStadiums(): List<StadiumData.Stadium>
```

---

---

### 4.5 ShotMapRepo — 射门坐标图 ⭐ 新增

**包路径**: `worldcup.helper.data.repos.ShotMapRepo`

**数据源**: BDL GOAT `/fifa/worldcup/v1/match_shots?match_ids[]={bdl_match_id}`
**API等级**: 🟡 条件API（需 BDL GOAT $39.99/月，赛后才有数据）

**API 接口**（在 `BalldontlieApi` 中新增）:
```kotlin
@GET("fifa/worldcup/v1/match_shots")
suspend fun getMatchShots(@Query("match_ids[]") matchIds: List<Int>): BdlShotListResponse
```

**响应数据模型**:
```kotlin
data class BdlShotListResponse(val data: List<BdlShot> = emptyList())
data class BdlShot(
    val id: Int? = null, val match_id: Int? = null,
    val team_id: Int? = null, val player_id: Int? = null,
    val player_name: String? = null,
    val minute: Int? = null, val xg: Double? = null,
    val result: String? = null,
    val body_part: String? = null,
    val player_x: Double? = null, val player_y: Double? = null
)
```

**核心方法**:
```kotlin
// 球队射门聚合（TeamDetailActivity 使用 — 主入口）
suspend fun getTeamShotMap(teamName: String): TeamShotMap?
// 单场射门图（MatchDetailActivity 使用）
suspend fun getMatchShots(bdlMatchId: Int): List<ShotEntry>
```

**Canvas 渲染**: `ShotMapView.kt` — 足球场半场俯视图
- ⚽ 进球=绿色圆 / 🎯 射正=蓝圆 / ❌ 射偏=灰× / 🛑 被封堵=橙菱形
- 点大小与 xG 值成正比（xG≥0.3 大点）

**数据流**:
```
TeamDetailActivity
  → TeamRepo 查该队已完赛 BDL match_ids
  → ShotMapRepo.getTeamShotMap(teamName)
    → 并行调 BDL /match_shots (每场)
    → 过滤该队射门 → 按结果分类聚合
  → ShotMapView(shots, totalShots, goals, onTarget)
```

**BDL match_id 映射**: 通过 `BDL /matches?team_ids[]=X&status=completed` 获取

---


### 4.5 PlayerRepoWrapper — 球员数据（适配包装）

**包路径**: `worldcup.helper.data.repos.PlayerRepoWrapper`

**包装对象**: `worldcup.helper.data.PlayerRepository`（已有实现，7层数据聚合）

```kotlin
// 获取球员完整资料（核心方法）
suspend fun getProfile(playerName: String, teamName: String? = null): PlayerProfile
// 清空缓存
fun clearCache()
```

**PlayerProfile 包含以下层次的数据**:
```
1. players_2026.json            → 基础信息（毫秒级，内存缓存）
2. football_data_person_id_map  → ID映射（内存缓存）
3. football-data persons/{id}/matches → 世界杯累计统计（API异步）
4. api-sports players?team=X    → 赛季18项统计（API异步）
5. trophies_cache.json          → 生涯荣誉（本地缓存）
6. BDL player_match_stats       → xG/xA高级数据（API异步，可选）
7. api-sports fixtures/players  → 单场统计（API异步，可选）
```

---

## 五、通用页面（跨 Tab 复用）

### 5.1 PlayerDetailActivity — 球员资料页

**包路径**: `worldcup.helper.ui.match.PlayerDetailActivity`
**布局**: `R.layout.activity_player_detail`

**Intent 参数**:
```kotlin
const val EXTRA_PLAYER_NAME = "player_name"  // 球员名（中文/英文）
const val EXTRA_TEAM_NAME = "team_name"      // 球队名（可选，用于精确匹配）
```

**调用方式（任何 Tab）**:
```kotlin
startActivity(Intent(context, PlayerDetailActivity::class.java).apply {
    putExtra(PlayerDetailActivity.EXTRA_PLAYER_NAME, "梅西")
    putExtra(PlayerDetailActivity.EXTRA_TEAM_NAME, "Argentina")
})
```

**渲染内容**: 头像/姓名/号码/位置/球队/伤病/俱乐部/身价 → 12项累计统计 → 18项赛季详细 → 荣誉墙 → 五维雷达图

---

### 5.2 TeamDetailActivity — 球队资料页

**包路径**: `worldcup.helper.ui.teams.TeamDetailActivity`
**布局**: `R.layout.activity_team_detail`

**Intent 参数**:
```kotlin
const val EXTRA_TEAM_NAME = "team_name"  // 球队英文名
```

**调用方式（任何 Tab）**:
```kotlin
startActivity(Intent(context, TeamDetailActivity::class.java).apply {
    putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, "Argentina")
})
```

**渲染内容**: 球队名/国旗/队徽(crest) → 已赛统计(胜/平/负/进/失) → 赛程列表 → 阵容 RecyclerView(SquadAdapter)

---

### 5.3 MatchDetailActivity — 比赛详情页

**包路径**: `worldcup.helper.ui.match.MatchDetailActivity`
**布局**: `R.layout.activity_match_detail`

**Intent 参数**:
```kotlin
intent.getStringExtra("match_id")  // matchId (String, 来自 matches.json 的 id)
```

**跨页面导航（新架构添加）**:
```kotlin
// 球队名可点击 → TeamDetailActivity
findViewById<TextView>(R.id.tv_home_name).setOnClickListener {
    startActivity(Intent(this, TeamDetailActivity::class.java).apply {
        putExtra(TeamDetailActivity.EXTRA_TEAM_NAME, homeTeamEn)
    })
}

// 球员弹窗 → "查看完整资料" → PlayerDetailActivity
builder.setPositiveButton("查看完整资料") { _,_ ->
    startActivity(Intent(this, PlayerDetailActivity::class.java).apply {
        putExtra(PlayerDetailActivity.EXTRA_PLAYER_NAME, playerName)
        putExtra(PlayerDetailActivity.EXTRA_TEAM_NAME, teamName)
    })
}
```

---

## 六、四类基础资料卡

App 的核心展示单元是四种「资料卡」，所有 Tab 的内容都围绕这四种卡展开：

```
┌────────────────────────────────────────────────────────────┐
│                    四类基础资料卡                            │
│                                                            │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐ │
│  │  球员卡   │  │  球队卡   │  │ 完赛比赛卡│  │ 实时比赛卡  │ │
│  │ Player   │  │  Team    │  │  Match   │  │  LiveMatch │ │
│  │ 详情页    │  │ 详情页   │  │ 详情页    │  │  直播页    │ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └─────┬──────┘ │
│       │              │              │               │       │
│       ▼              ▼              ▼               ▼       │
│  PlayerDetail   TeamDetail    MatchDetail      MatchDetail │
│  Activity       Activity      Activity         Activity    │
│  (球员资料)     (球队资料)    (完赛模式)       (直播模式)   │
└────────────────────────────────────────────────────────────┘
```

### 6.1 球员卡 — PlayerDetailActivity

**物理位置**: `worldcup.helper.ui.match.PlayerDetailActivity`
**数据源**: PlayerRepo（7层聚合：本地JSON + football-data + api-sports + TrophyData）
**调用方式**:
```kotlin
Intent(context, PlayerDetailActivity::class.java).apply {
    putExtra(EXTRA_PLAYER_NAME, "梅西")       // 中文或英文名
    putExtra(EXTRA_TEAM_NAME, "Argentina")     // 可选，精确匹配用
}
```

| 展示区域 | 数据内容 | 来源 |
|:---------|:---------|:-----|
| **头部** | 照片、姓名(中/英)、号码、位置、球队、伤病、俱乐部、身价 | players_2026.json |
| **12项累计统计** | 出场/进球/助攻/分钟/黄牌/红牌/替补/点球 | football-data persons/{id}/matches |
| **18项赛季统计** | 评分/射门/传球/抢断/过人/对抗(四象限) | api-sports players?team=X&season=2026 |
| **荣誉墙** | 生涯奖杯列表（按类别分组） | TrophyData / trophies_cache.json |
| **五维雷达图** | 进球/助攻/出场/纪律/耐力 | 自定义 RadarChartView |
| **射门分布图** | 射门位置xG热力 | BDL `match_shots`→Canvas ShotMapView ⬜ 设计中 |
| **自信度标记** | 🟢API/🟡本地/⚪暂无 | PlayerRepository 数据管线集成 ✅ |

**示例布局**:
```
┌─────────────────────────────────────────┐
│ ← 返回   球员详情                        │
│  [照片] 梅西 Lionel Messi                │
│  #10 · 前锋 · 阿根廷 · 🏟 国际迈阿密     │
│  176cm · 左足                           │
│ ─────────────────────────────────────── │
│  ⚽ 3球  🅰 2助  📋 4场  ⭐ 8.2分       │
│  ⏱ 345分钟  🟨 1张                      │
│  [📊 雷达图]                            │
│ ─────────────────────────────────────── │
│  进攻: 射门9 射正7 关键传3               │
│  组织: 传球20 精准10 传中2                │
│  防守: 抢断1 拦截0 解围0                 │
│  纪律: 黄牌0 红牌0                       │
│ ─────────────────────────────────────── │
│  🏆 荣誉墙                              │
│  世界杯 2022 · 金球奖 ×8 · 欧冠 ×4       │
└─────────────────────────────────────────┘
```

---

### 6.2 球队卡 — TeamDetailActivity

**物理位置**: `worldcup.helper.ui.teams.TeamDetailActivity`
**数据源**: TeamRepo（players_2026.json + football-data crest + MatchRepo）
**调用方式**:
```kotlin
Intent(context, TeamDetailActivity::class.java).apply {
    putExtra(EXTRA_TEAM_NAME, "Argentina")   // 球队英文名
}
```

| 展示区域 | 数据内容 | 来源 |
|:---------|:---------|:-----|
| **头部** | 球队中文名、英文名、组别、国旗、队徽crest | players_2026.json + TeamRepo crest |
| **已赛统计** | 已赛/胜/平/负/进/失 | football-data standings API (3-tier fallback) |
| **球队数据** | 场均控球/射门/射正/角球/犯规 (来自最近5场) | BDL team_match_stats ⭐ v6.0 新增 |
| **射门热力图** | 最近一场比赛的射门分布（Canvas球场+彩色标记） | BDL match_shots ⭐ v6.0 新增 |
| **赛程列表** | 该队所有比赛（状态+比分+时间） | MatchRepo |
| **附加信息** | Elo评分/主场场馆/阵容规模 | TeamRepo |
| **阵容** | 按位置分组（门将/后卫/中场/前锋），头像+号码角标+姓名 | players_2026.json via TeamRepo |

**阵容点击行为**: 每名球员可点击 → PlayerDetailActivity (EXTRA_PLAYER_NAME + EXTRA_TEAM_NAME)

**BDL 球队数据方案 v6.0**:

球队详情页下方新增 **「📊 球队数据」** 和 **「🎯 射门热力图」** 两个区块。

#### 6.2.1 📊 球队数据卡（BDL team_match_stats）

| 数据 | 显示格式 | 来源 | 可靠性 |
|:-----|:---------|:-----|:------:|
| 场均控球率 | `控球率 54%` | BDL team_match_stats 最近5场平均 | ⭐⭐⭐ |
| 场均射门 | `场均射门 12.4` | BDL team_match_stats 最近5场平均 | ⭐⭐⭐ |
| 场均射正 | `场均射正 5.2` | BDL team_match_stats 最近5场平均 | ⭐⭐⭐ |
| 场均角球 | `场均角球 4.6` | BDL team_match_stats 最近5场平均 | ⭐⭐⭐ |
| 场均犯规 | `场均犯规 11.2` | BDL team_match_stats 最近5场平均 | ⭐⭐⭐ |

**实现方式**:
1. 取该队最近5场已完赛的比赛
2. 建立 BDL match_id 映射（复用 LiveViewModel 的 `teamNameMatch()` 逻辑）
3. 调 `BDL /team_match_stats?match_ids[]={id1},{id2},{id3},{id4},{id5}`
4. 按 `team_id` 过滤该队的数据
5. 计算各字段平均值
6. 渲染为紧凑的横向统计行（顶部标题 + 5项数据）

#### 6.2.2 🎯 射门热力图（BDL match_shots）

| 数据 | 显示格式 | 来源 | 可靠性 |
|:-----|:---------|:-----|:------:|
| 射门位置 | Canvas 球场 + 彩色标记 | BDL match_shots 最近1场 | ⚠️ 赛后1小时才填充 |

**实现方式**:
1. 取该队最近1场已完赛的比赛
2. 调 `BDL /match_shots?match_ids[]={id}`
3. 按 `team_id` 过滤该队的射门
4. 绘制 Canvas 球场半场俯视图（尺寸: match_parent, 最大高度 240dp）
5. 每脚射门一个圆形标记，颜色编码:
   - 🟢 绿色实心 = 进球 (shot_type 包含 "goal" 或从 events 补充)
   - 🟡 黄色半透明 = 扑出
   - 🔴 红色实心 = 被封堵
   - ⚪ 灰色空心 = 射偏/未中目标
6. 标记大小 = 6dp + (xG * 12dp) — xG 越高点越大
7. 使用 `player_x` (0=左底线, 1=右底线) 和 `player_y` (0=本方底线, 1=对方底线) 定位

**需新增代码**:
- `ApiInterfaces.kt`: `getMatchShots()` + `BdlShotMapResponse` + `BdlShotData`
- `TeamDetailActivity.kt`: `fetchTeamMatchStats()` + `renderShotMap()`
- 自定义 View 或 Canvas 直接绘制

#### 6.2.3 ⏱ 比赛势头曲线（BDL match_momentum，P2 可选）

| 数据 | 显示格式 | 来源 | 可靠性 |
|:-----|:---------|:-----|:------:|
| 每分钟势头 | Canvas 曲线图 | BDL match_momentum 最近1场 | ⚠️ 赛后才有 |

**实现方式**: Canvas 绘制双线图（主队绿线 / 客队蓝线），横轴0-90分钟，纵轴0-100。

---

### 6.3 完赛比赛卡 — MatchDetailActivity（完赛模式）

**物理位置**: `worldcup.helper.ui.match.MatchDetailActivity`
**数据源**: MatchRepo（football-data + api-sports + BDL + 本地JSON）
**调用方式**:
```kotlin
Intent(context, MatchDetailActivity::class.java).apply {
    putExtra("match_id", "537327")   // matches.json 中的 id
}
```

**两个子Tab**（v2.4 更新 — 阵容 Tab 已取消）:

| Tab | 内容 | 数据来源 |
|:----|:-----|:---------|
| **① 赛况** | 事件时间轴（中文名） + 比赛摘要 + 最佳球员 | api-sports events + BDL best_players |
| **② 数据** | 20项统计对比（控球/射门/角球等） | api-sports statistics |

**关键特性**:
- 🔴 **API优先比分**：打开时调 football-data 获取真实比分覆盖本地 0-0
- 🔴 **中文名映射**：事件和阵容球员名通过 `findChineseName()` 自动转中文
- 🟡 **预测条**：单条比例条（红:主队% / 灰:平% / 绿:客队%）
- 🟢 **场馆信息**：显示场馆名 + 容量 + 城市
- **点击球队名** → TeamDetailActivity
- **点击球员** → 弹窗→"查看完整资料" → PlayerDetailActivity
- **已移除**：历史交锋 Tab（H2H）

---

### 6.4 实时比赛卡 — MatchDetailActivity（直播模式）

**与完赛比赛卡使用同一个 Activity**，区别在于：

| 特性 | 完赛模式 | 直播模式 |
|:-----|:---------|:---------|
| 比分更新 | API一次拉取 | football-data 30秒轮询 |
| 事件 | 赛后一次性加载 | api-sports 30秒轮询 |
| 统计 | 赛后完整数据 | 赛中渐进填充 |
| 评分 | 赛后完整 | 半场后才出 |
| 状态显示 | 已结束 | 🔴 直播中（红色跳动） |

**直播模式触发条件**:
```kotlin
// MatchRepo 判断有 LIVE 比赛
if (repo.matches.hasLiveMatch()) {
    startActivity(MatchDetailActivity(直播模式))
}
```

**无直播时的降级**:
```
Tab A → 无LIVE → 显示「下一比赛日预测卡片」
        点击卡片 → 同样打开 MatchDetailActivity（赛前模式）
```

---

### 6.5 四类卡片的关系

```
       球员卡 ←────── 球队卡 ←────── 比赛卡
       (Player)       (Team)        (Match)
          ↑              ↑              ↑
          │              │              │
    PlayerRepo      TeamRepo       MatchRepo
    (7层聚合)       (球队资料)     (API优先轮询)
          │              │              │
          └──────────────┴──────────────┘
                      ↓
              SharedRepository
              (全局唯一数据源)
```

**跨卡导航**:
- 比赛卡 → 球队名点击 → 球队卡
- 比赛卡 → 球员点击 → 球员卡
- 球队卡 → 球员点击 → 球员卡
- 所有卡片 → SharedRepository → 数据一致性保证


## 七、点击跳转协议（全 Tab 统一）

### 6.1 完整导航映射表

| 点击位置 | 源文件 | Intent 目标 | Extra Key |
|:---------|:-------|:------------|:----------|
| 比赛卡片（Tab A/C） | `LiveFragment` / `ScheduleFragment` | `MatchDetailActivity` | `"match_id"` |
| 球队名（比赛详情页） | `MatchDetailActivity` | `TeamDetailActivity` | `TeamDetailActivity.EXTRA_TEAM_NAME` |
| 球员名（比赛详情弹窗） | `MatchDetailActivity.showPlayerDetail()` | `PlayerDetailActivity` | `PlayerDetailActivity.EXTRA_PLAYER_NAME` + `EXTRA_TEAM_NAME` |
| 球队名（阵容卡片 Tab A） | `LiveFragment.buildTeamPlayerCard()` | —（暂无，可扩展） | — |
| 球员chip（阵容卡片 Tab A） | `LiveFragment.buildTeamPlayerCard()` | `PlayerDetailActivity` | `EXTRA_PLAYER_NAME` + `EXTRA_TEAM_NAME` |
| 球员（球队详情 + SquadAdapter） | `TeamDetailActivity.SquadAdapter` | `PlayerDetailActivity` | `EXTRA_PLAYER_NAME` + `EXTRA_TEAM_NAME` |
| 积分榜球队行 | `DataFragment.renderStandings()` | `TeamDetailActivity` | `EXTRA_TEAM_NAME` |
| 球员榜球员行 | `DataFragment.renderRankingList()` | `PlayerDetailActivity` | `EXTRA_PLAYER_NAME` + `EXTRA_TEAM_NAME` |
| 球队网格 | `TeamsFragment` | `TeamDetailActivity` | `EXTRA_TEAM_NAME` |
| 淘汰赛已完赛 | `DataFragment` | `MatchDetailActivity` | `"match_id"` |

### 6.2 PlayerDetailActivity 参数解析优先级

```kotlin
// 当前仅支持：
// 1. player_name (String) — 中文或英文名
// 2. team_name (String) — 球队名（可选，用于精确匹配）
//
// 未来可扩展：
// 3. football_data_id (Int) — football-data.org personId
// 4. api_sports_id (Int) — api-sports.io playerId
```

---

## 八、Tab A（实时赛况）重构细节

### 8.1 核心逻辑（v3.0，2026-06-26 重构）

```kotlin
// LiveFragment → LiveViewModel v3.0 管线
// 
// computeUiState() 三步走:
//   Step 1: football-data /matches → apiScoreMap（比分+状态覆盖本地0-0）
//   Step 2: api-sports fixtures?live=all → liveClockMap（实时比赛分钟）
//   Step 3: 合并本地 LIVE 检测 → 返回 LiveUiState
//
// pollAll() 30秒循环:
//   api-sports live fixtures → 刷新时钟
//   football-data LIVE比分 → 刷新比分
//   api-sports events/statistics → 事件+统计
//   BDL lineups/best_players → 阵容+最佳

// 有直播 → 显示直播（单场或多场）
val liveMatches = repo.matches.getLiveMatches()
if (liveMatches.isNotEmpty()) {
    val clockMap = viewModel.liveClockMap.value ?: emptyMap()
    if (liveMatches.size == 1) {
        showLiveMatch(liveMatches.first(), clockMap)
    } else {
        showMultiLiveMatches(liveMatches, clockMap)
    }
}
// 无直播 → 显示下一比赛日预测
else {
    val nextMatchday = repo.matches.getNextMatchday()
    showPredictions(nextMatchday)
}
```

### 8.2 页面渲染逻辑

| 状态 | 渲染内容 | 数据来源 | 引入版本 |
|:-----|:---------|:---------|:--------|
| Loading | 加载指示器 | — | v1.0 |
| `LiveMatch` | 比分板 + 事件双队时间轴 + 统计对比 + 评分卡 + 阵容 | ViewModel 30秒轮询 + **api-sports live clock** | v1.0 → v3.0 |
| `MultiLiveMatches` | 🔴 **垂直多场卡片列表**（时钟/比分/轮次/事件预览） | api-sports live fixtures + football-data | **v3.0 新增** |
| `RecentMatch` | 最近一场回顾 | ViewModel 一次性拉取 | v1.0 |
| `Predictions` | 预测卡片列表 | MatchRepo.getNextMatchday() | v1.0 |
| `AllFinished` | "所有比赛已结束" | — | v1.0 |

### 8.3 关键数据流

```
LiveViewModel.v3 数据流:

init ─┬─ Step 1: football-data /matches ─→ apiScoreMap
      └─ Step 2: api-sports /fixtures?live=all ─→ liveClockMap (Map<matchId, elapsed>)
      ┌─────────────────────────────────────────────────────┐
      │ Step 3: 合并检测直播比赛                              │
      │   api-sports 直播中 × football-data 直播中 × 本地LIVE │
      └──────────────────────┬──────────────────────────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                              ▼
     多场(≥2) → MultiLiveMatches   单场→ LiveMatch
               (垂直卡片列表)          (全数据看板)
              │                              │
              └──────────────┬───────────────┘
                             ▼
                    pollAll() 30秒循环
   ┌─────────────┬──────────────┬──────────────┐
   │             │              │              │
   ▼             ▼              ▼              ▼
liveClock   football-data  api-sports     BDL
(刷新时钟)   (刷新比分)    events+stats   lineups+best
                           (刷新事件统计) (刷新阵容)
```

### 8.4 多场直播 vs 单场直播策略

| 条件 | 展示方式 | 说明 |
|:-----|:---------|:-----|
| 0 场直播 | 预测卡片 | 下一比赛日 |
| 1 场直播 | 全数据看板 | 比分板+事件+统计+评分+阵容 |
| ≥2 场直播 | 多场卡片列表 | 垂直卡片+点击聚焦单场详情 |

### 8.5 比赛阶段感知时钟系统（v2.5 新增）

**动机**: 淘汰赛阶段(加时赛+点球)中场休息时钟乱走、90分钟显示"已结束"、点球比分错乱。

**实现**:
```
LiveViewModel.liveStatusCache: Map<matchId, status.short>
  ↓
LiveFragment.formatMatchClock(phase, elapsed): String
```

| status.short | 显示文本 | 行为 |
|:-------------|:---------|:-----|
| `1H` | `"45′ 🟢 上半场"` | 正常走钟 |
| `HT` | `"⏸️ 中场休息 (45′)"` | 冻结时钟，5秒刷新 |
| `2H` | `"72′ 🟢 下半场"` | 正常走钟 |
| `ET` | `"ET 15′ 🟡 加时赛"` | 正常走钟，显示 ET 前缀 |
| `PEN` | `"⚫ 点球大战"` | 冻结时钟，5秒刷新 |
| `FT/AET` | `"✅ 已结束"` | 停止时钟循环 |

**数据流**:
```
fetchLiveFixturesFromApi()
  → updateClockAndScore(localId, elapsed, fixture) 
    → liveStatusCache[localId] = fixture.status.short
  → computeUiState() 首次同步
  → pollAll() 每30秒同步

getMatchPhase(matchId) → Fragment 获取阶段
  → formatMatchClock(phase, elapsed) → 生成显示文本
  → startPersistentClock() 根据阶段决定刷新频率(1s/5s/stop)
```
| ≥2 场直播 | 垂直卡片列表 | 每张卡含时钟/比分/轮次/事件预览，点击进详情 |

### 8.5 时钟策略 + 比赛匹配（v2.4 核心修复）

```
时钟来源优先级:
  1st: api-sports fixtures?live=all → elapsed
       └→ 真实比赛分钟（精确到分钟）
  2nd: 本地 Handler 1秒计算
       └→ System.currentTimeMillis() - kickoffMs（本地兜底）

比赛匹配优先级（v2.4 新增）:
  1st: fixture_id_map.json 精准查找
       └→ apiFixture.fixture.id → fixtureIdToLocalMap → local matchId
       └→ O(1) 查找，不需球队名匹配
  2nd: teamNameMatch() 球队名模糊匹配
       └→ 双向匹配确保覆盖
       └→ 示例: "Argentina" ↔ "阿根廷", "Mexico" ↔ "墨西哥"

数据流:
  loadFixtureIdToLocalMap() → Map<Int, String>
    ↓
  computeUiState() / pollAll()
    ├── Priority 1: fixtureIdToLocalMap[apiFixture.fixture.id]
    └── Priority 2: teamNameMatch(apiHome, m.homeTeam) && ...
    ↓
  updateClockAndScore(localId, elapsed, fixture)
    ├── liveClockCache[localId] = elapsed
    └── card.homeScore = fixture.goals.home / card.awayScore = fixture.goals.away
    ↓
  _liveClockMap.postValue(liveClockCache)
  _liveCards.postValue(matchDataMap.values)
    ↓
  Fragment 观察:
    ├── liveClockMap → updateClockDisplay(更新标题/卡片时钟)
    └── liveCards → updateScoreCardFromCardData(更新比分卡) + renderSingleMatchSections(更新详情)
```

### 8.6 分数卡轮询同步（v2.4 新增）

**问题**：v3.0 中 `_liveCards` observer 只更新 sections（事件/统计/阵容），不更新比分卡。
`tv_score`/`tv_match_info` 仅在 `uiState.observe` 设置一次，轮询期间不会刷新。

**修复**：新增 `updateScoreCardFromCardData(view, card)`：
- 从 `LiveMatchCardData.homeScore/awayScore` 更新 `tv_score`
- 从 `viewModel.getElapsedForMatch()` 更新 `tv_match_info` 状态描述
- 在 `_liveCards` observer 的单场/聚焦分支中调用

### 8.7 阵容策略（v2.4 更新 — 取消本地 JSON 回退）

```
阵容来源优先级:
  1st: BDL match_lineups API
       └→ 真实首发11人 + 阵型名（如 4-3-3）
       └→ 中文名通过 toChinese() 映射
       └→ 示例: #1 奥乔亚 (GK), #5 瓦斯克斯 (DF), #10 洛萨诺 (MF)
  2nd: 无（不依赖本地 JSON 回退）
       └→ v2.4 确认：本地 players_2026.json 仅用于中文名翻译，
           不再作为阵容数据源。BDL 失败时阵容区域整体隐藏。
```
       └→ 取球队列表前11人（非真实首发，仅是阵容）
```

### 8.7 事件显示策略（v3.0 修复）

```
事件按球队分组后按时间合并排序:
  homeEvents = 主队事件（api-sports events 中 teamName 匹配 homeTeam）
  awayEvents = 客队事件（teamName 匹配 awayTeam）
  sortedEvents = (homeEvents + awayEvents).sortedBy { elapsed }
  
  每条事件显示格式:
    "{elapsed}' 🏠 {icon} {playerCn}{detail}"  ← 主队
    "{elapsed}' ✈️ {icon} {playerCn}{detail}"  ← 客队
  
  示例:
    67' 🏠 ⚽ 洛萨诺
    55' ✈️ 🟨 穆夏拉
```

---

## 九、Tab B（AI 对话）重构细节

### 9.1 数据上下文来源变更

```kotlin
// 旧代码（已弃用）
// stadiumData = StadiumData(application)
// trophyData = TrophyData(application)

// 新代码
val sharedRepo = SharedRepository.getInstance(getApplication())
sharedRepo.stadiums.getAllStadiumsSummary()  // ← 场馆信息
trophyData.getTrophiesSummary(apiId)         // ← 荣誉信息（本地，保留直接使用）
```

### 9.2 意图引擎 + 数据注入

```
意图分类          → 注入数据                        → 回复策略
──────────────────────────────────────────────────────────────────
GREETING         → (无)                           → 本地问候秒回
RULE_QUESTION    → FaqKnowledge 42条               → FAQ命中→秒回 / 未命中→DeepSeek
MATCH_SCORE      → MatchRepo 比赛数据               → DeepSeek + 数据
SCHEDULE_QUERY   → MatchRepo 赛程数据               → DeepSeek + 数据
PLAYER_INFO      → PlayerRepo 球员资料 + TrophyData → DeepSeek + 数据
LINEUP_QUERY     → TeamRepo 阵容数据                → DeepSeek + 数据
STANDINGS_QUERY  → StandingRepo 积分榜              → DeepSeek + 数据
PREDICTION_QUERY → MatchRepo 预测数据               → DeepSeek + 数据
通用聊天          → 全量数据上下文注入                → DeepSeek
```

---

## 十、Tab C（赛程查询）重构细节

### 9.1 数据流向

```
ScheduleFragment
  ├── 初始化: ScheduleViewModel → MatchData(matches.json) → 赛程列表
  ├── 自动定位到下一场
  ├── 点击比赛卡片 → MatchDetailActivity(match_id)
  └── MatchDetailActivity
        ├── 球队名点击 → TeamDetailActivity(team_name)
        ├── 球员点击 → PlayerDetailActivity(player_name, team_name)
        ├── 实时比分轮询(30s): MatchRepo.fetchLiveScoresFromApi()
        ├── 事件: MatchRepo.fetchEventsFromApi(fixtureId)
        ├── 统计: MatchRepo.fetchStatisticsFromApi(fixtureId)
        ├── 阵容: MatchRepo.fetchLineupsFromApi(fixtureId)
        ├── H2H: MatchRepo.fetchH2hFromApi(homeId, awayId)
        └── MVP: MatchRepo.fetchBestPlayerFromApi(bdlMatchId)
```

### 9.2 比赛详情 Tab 切换（v2.4 更新 — 取消阵容 Tab）

```
Tab 0 = 赛况（事件时间轴 + 最佳球员）
Tab 1 = 数据（20项统计对比表）
（阵容 Tab 已于 v2.4 取消 — api-sports lineup API 不提供号码/位置，依赖本地JSON不符合实时正确要求）
```

---

## 十一、Tab D（比赛数据）重构细节

### 10.1 四个子 Tab

```
[积分榜] [球员榜] [球队] [淘汰赛]
```

**积分榜子 Tab**:
```kotlin
// DataFragment 使用 SharedRepository
val repo = SharedRepository.getInstance(requireContext())
fragmentScope.launch {
    val standings: Map<String, List<StandingRow>> = repo.standings.getStandings()
    renderStandings(container, standings, flagLoader)
}
// ✅ 每行点击跳转 TeamDetailActivity
// ✅ 排名显示已修复（第4名显示"4"而非"3"）
```

**球员榜子 Tab**（共8个子分类）:
```kotlin
// 射手榜
apiScorersData = repo.standings.getScorers()
// 助攻/评分/射正/关键传/抢断/过人/牌榜
apiRankingsData = repo.standings.getSeasonRankings(requireContext())
```
- 8个子分类: ⚽射手榜 🅰助攻榜 ⭐评分榜 🎯射正榜 🔑关键传 💪抢断榜 ⚡过人榜 🟨牌榜
- SeasonRanking 新增 `shotsOnTarget/keyPasses/tackles/dribbles` 字段
- ✅ 每行球员名→PlayerDetailActivity, 球队名→TeamDetailActivity

**球队子 Tab**: `TeamsFragment`（48队网格）
- ✅ 中文拼音排序（Collator + Locale.CHINESE）
- 卡片: 中文名·组别+英文名·球员数
- 点击 → `TeamDetailActivity`

**球队详情页**（`TeamDetailActivity`）增强:
- 小组排名异步查询 + 战绩概要("2胜1平0负·+3净胜·7分")
- 近5场W/D/L彩色方块条 + 下场/最近比赛卡片
- ✅ 时间感知赛程状态（`isMatchFinishedByTime()`）
- ✅ 球员头像 Coil CircleCropTransformation + 号码高亮

**淘汰赛子 Tab**: `BracketView`
- ✅ 重构为左右半区（上半区左列/下半区右列/决赛居中/三四名在决赛下方）
- 小组赛结束后自动填充晋级队伍（当前TBD）

---

## 十二、API 资源配置

### 11.1 三个主力 API

| API | Base URL | Auth Header | 套餐 | 配额 | 核心用途 |
|:----|:---------|:------------|:----|:-----|:---------|
| **football-data.org** | `https://api.football-data.org/v4/` | `X-Auth-Token: [REDACTED - 请联系作者获取]` | FREE_PLUS_LIVESCORES | 10次/分 | 比分+状态+积分榜+射手榜+球员累计 |
| **api-sports.io** | `https://v3.football.api-sports.io/` | `x-apisports-key: [REDACTED - 请联系作者获取]` | Pro ($19/月) | 7500次/天 | 阵容+号码+照片+18项统计+事件+统计+H2H |
| **BDL GOAT** | `https://api.balldontlie.io/fifa/worldcup/v1/` | `Authorization: [REDACTED - 请联系作者获取]` | GOAT ($39.99/月) | 600次/分 | xG/xA/长传/传中/头球/丢球权/阵型/最佳球员 |

### 11.2 Retrofit 客户端配置

```kotlin
// 文件位置: worldcup.helper.network.LiveApiClient
object LiveApiClient {
    val footballData: FootballDataApi  // baseUrl = "https://api.football-data.org/"
    val apiSports: ApiSportsApi        // baseUrl = "https://v3.football.api-sports.io/"
    val bdlApi: BalldontlieApi         // baseUrl = "https://api.balldontlie.io/"
}
// 所有 API Keys 硬编码在此类中
```

### 11.3 API 接口定义

```kotlin
// 文件位置: worldcup.helper.network.ApiInterfaces
interface FootballDataApi {
    suspend fun getMatches(status: String = "", season: Int = 2026): FootballMatchesResponse
    suspend fun getStandings(season: Int = 2026): FootballStandingsResponse
    suspend fun getScorers(season: Int = 2026, limit: Int = 50): FootballScorersResponse
    suspend fun getTeams(season: Int = 2026): FootballTeamsResponse
    suspend fun getPersonMatches(personId: Int, competitions: String = "2000", limit: Int = 20): FootballPersonMatchesResponse
}

interface ApiSportsApi {
    suspend fun getFixtures(teamId: Int? = null, date: String? = null): ApiSportsFixturesResponse
    suspend fun getFixturePlayers(fixture: Int): ApiSportsPlayersResponse
    suspend fun getFixtureEvents(fixture: Int): ApiSportsEventsResponse
    suspend fun getFixtureStatistics(fixture: Int): ApiSportsStatisticsResponse
    suspend fun getFixtureLineups(fixture: Int): ApiSportsLineupsResponse
    suspend fun getTeamSquad(team: Int): ApiSportsSquadResponse
    suspend fun getPlayersByTeam(team: Int): ApiSportsSeasonResponse  // season=2026
    suspend fun getPlayerTrophies(player: Int): ApiSportsTrophiesResponse
    suspend fun getHeadToHead(h2h: String): ApiSportsH2hResponse
}

interface BalldontlieApi {
    suspend fun getMatches(season: Int = 2026): BdlMatchesResponse
    suspend fun getTeams(season: Int = 2026): BdlTeamsResponse
    suspend fun getPlayerMatchStats(matchIds: String): BdlPlayerMatchStatsResponse
    suspend fun getMatchLineups(matchIds: String): BdlLineupsResponse
    suspend fun getMatchEvents(matchIds: String): BdlEventsResponse
    suspend fun getTeamMatchStats(matchIds: String): BdlTeamStatsResponse
    suspend fun getMatchBestPlayers(matchIds: String): BdlBestPlayersResponse
    suspend fun getMatchShots(matchIds: String): BdlShotMapResponse       // ⭐ v6.0 新增
    suspend fun getMatchMomentum(matchIds: String): BdlMomentumResponse   // ⭐ v6.0 新增
    suspend fun getMatchShots(matchIds: List<Int>): BdlShotListResponse
    suspend fun getGroupStandings(season: Int = 2026): BdlGroupStandingsResponse
}
```

### 11.4 Build 依赖

```kotlin
// build.gradle 已配置
implementation 'com.squareup.retrofit2:retrofit:2.9.0'
implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
implementation 'com.squareup.okhttp3:logging-interceptor:4.12.0'
implementation 'io.coil-kt:coil:2.5.0'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
implementation 'com.google.code.gson:gson:2.10.1'
```

---

## 十三、数据分类速查

### 13.1 三级分类

| 等级 | 含义 | 数量 | 示例 |
|:----:|:-----|:----:|:-----|
| 🔴 **必须API** | 比赛中动态变化，本地预设不准确 | 15个端点 | 实时比分、比赛事件、统计、阵容、H2H、积分榜、射手榜、球员累计 |
| 🟡 **条件API** | API有价值，本地缓存足够可靠 | 7个端点 | 球员荣誉(TrophyData)、队徽 crest(一次缓存)、比赛预测、BDL xG/xA |
| 🟢 **基础本地** | 静态数据，不存在"实时更新"概念 | 16个端点 | 球员中文名/号码/位置、球队分组、赛程时间、ID映射表、场馆信息 |

### 13.2 各 Repository API 调用配置

| Repository | 调用的 API | 请求频率 | 失败兜底 |
|:-----------|:-----------|:---------|:---------|
| **MatchRepo** | football-data /matches | 30秒轮询(LIVE) | matches.json + 本地计算 |
| | **api-sports fixtures?live=all** | **30秒轮询(新增v3.0)** | **liveClockMap 空降级** |
| | api-sports fixtures/events/statistics/lineups | 点开详情时 | match_events.json |
| | api-sports fixtures/headtohead | 点开详情时 | 本地计算 |
| | BDL match_best_players | 赛后拉取 | 显示"暂无" |
| **StandingRepo** | football-data /standings | 手动刷新 | BDL → 本地计算 |
| | football-data /scorers | 手动刷新 | match_events.json |
| | api-sports players?team=X | 手动刷新 | 显示"数据积累中" |
| **TeamRepo** | football-data /teams (crest) | App启动(永久缓存) | 空Map |
| | (本地) players_2026.json | App启动 | — |
| **PlayerRepo** | football-data persons/{id}/matches | 用户点开详情时 | players_2026.json |
| | api-sports players?team=X | 用户点开详情时 | football-data累计 |
| | (本地) TrophyData / trophies_cache.json | 用户点开详情时 | 空列表 |
| **StadiumRepo** | (纯本地) bdl_stadiums.json | App启动 | — |

### 13.3 数据一致性铁律

```
1. 任何 Tab 不得直接修改本地 JSON 文件
2. API 返回数据后 → Repository 更新缓存 → 所有 Tab 自动生效
3. 离线时静默使用本地数据，不弹 Toast
4. Tab B 的回答数据必须来自 SharedRepository
```

---

## 附录：已有代码 vs 新架构代码对照

| 功能 | 旧方式（不再推荐） | 新方式（推荐） |
|:-----|:-----------------|:--------------|
| 获取积分榜 | `DataRepository(context).getStandingsWithApi()` | `SharedRepository.getInstance(context).standings.getStandings()` |
| 获取射手榜 | `DataRepository(context).getScorersWithApi()` | `SharedRepository.getInstance(context).standings.getScorers()` |
| 获取赛事排名 | `DataRepository(context).getSeasonRankings()` | `SharedRepository.getInstance(context).standings.getSeasonRankings(context)` |
| 获取比赛列表 | `MatchData(context).matches` | `SharedRepository.getInstance(context).matches.getAllMatches()` |
| 获取实时比赛 | `MatchData(context).matches.filter{...}` | `SharedRepository.getInstance(context).matches.getLiveMatches()` |
| 获取场馆信息 | `StadiumData(context).getAllStadiumsSummary()` | `SharedRepository.getInstance(context).stadiums.getAllStadiumsSummary()` |
| 获取球员资料 | `PlayerRepository(context).getPlayerProfile(name)` | `SharedRepository.getInstance(context).players.getProfile(name)` |
| 获取阵容摘要 | `PlayerDatabase(context).getTeamRoster(name)` | `SharedRepository.getInstance(context).teams.getTeamRosterSummary(name)` |
| 获取球队详情 | 直接读JSON + MatchData | `SharedRepository.getInstance(context).teams.getTeamDetail(name)` |
| 队徽 crest | `DataRepository(context).getCrestUrls()` | `SharedRepository.getInstance(context).teams.getCrestUrls()` |

---

## 附录B：球员姓名匹配规则（2026-06-26 确立）

> 由于 `players_2026.json` 使用全名（"Mike Maignan"），而 API 返回缩写名（"M. Maignan"），需要精确的匹配规则避免错人。

### B.1 `findPlayerByName()` 匹配优先级

```kotlin
// PlayerRepository.kt
匹配优先级（由高到低）:
1. 精确匹配: key == query
2. 完整名匹配: 索引key等于查询串
3. 包含匹配(短查长): query是key的一部分 → 按key长度降序(长key优先)
4. 包含匹配(长查短): key是query的一部分 → 按key长度降序(长key优先)
```

**经典问题**: 搜"Rayan Cherki" → key"rayan chirki"和key"rayan"(巴西)都匹配 `q.contains(k)` → 旧代码用 `firstOrNull` 可能先返回短的巴西球员 → 已修复为按长度降序

### B.2 API 球员统计匹配规则

```
API返回 "M. Maignan"  ↔  players_2026 "Mike Maignan"
策略:
1. 精确匹配
2. 缩写展开: 解析"M. Maignan" → 首字母'm' + 姓氏'maignan'
   → 与全名首字母+姓氏比较 → 必须都一致
3. 去点号包含匹配: 去掉点号后做 contains 兜底
```

### B.3 api-sports 照片 ID 数据源可靠性排序

| 来源 | 可靠性 | 覆盖率 |
|:-----|:------:|:------:|
| `MachineLearning_Module/data/apisports/{team}.json`(47队) | ⭐⭐⭐⭐⭐ | 47队 |
| `player_photo_map.json` | ⭐⭐⭐⭐ | 1337人(部分队不全) |
| `football_data_person_id_map.json` | ⭐⭐⭐ | 1131人(部分重复) |
| `players_2026.json`(原始) | ⭐ | 687人(大量写错) |

### B.4 已知代码层面的匹配陷阱

| 陷阱 | 旧代码 | 修复 |
|:-----|:-------|:-----|
| `firstOrNull` + `contains` | `k.contains(q) \|\| q.contains(k)` — HashMap 无序 | 按优先级+长度排序 |
| API 缩写名含点号 | `"Mike Maignan".contains("M. Maignan")`→false | 缩写展开(首字母+姓氏) |
| NFKD 重音 | `"Hernández".contains("Hernandez")`→false | NFKD normalize 后匹配 |
| `contains` 短名优先 | "Rayan" 优先于 "Rayan Cherki" | 按 key 长度降序 |

---

> **这份文档是多份文档的集大成者**：
> - 架构蓝图 ← `new_framework.md`
> - 功能需求 ← `requirement_list.md`
> - 数据来源规则 ← `DATA_CLASSIFICATION.md`
> - API 端点清单 ← `API_RESOURCE.md`
> - 数据共享清单 ← `data_sharing_inventory.md`
>
> **开发者阅读本文档后，应能立即理解如何调取每一份数据、构建每一个页面、实现每一次跳转。**
