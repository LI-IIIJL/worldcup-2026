# WorldCupScan — 功能需求蓝图（真实可行版）

> **版本**: v5.5  
> **制表日期**: 2026-06-30  
> **作者诚言**: 这份文档第一次说真话。之前我把所有 API 端点列成清单就以为都能用，忽略了各 API 的真实数据质量、Android 集成成本和付费墙。每个功能下方标注了 **[可行性评级]**。
> **追注 (v5.4)**: 发生了一次严重的数据管线事故——荷兰vs摩洛哥比赛事件显示为空，用户质疑数据真实性。根因：fixture_id_map.json 缺失32场比赛（16场已确定的淘汰赛+16场待定），导致 fixtureId=null→pollMatchEvents 直接 return 跳过所有事件加载。已修复。
> **追注 (v5.5)**: 淘汰赛对阵图全面重写——1) 淘汰赛不再按比赛时间排序，改为按固定晋级路径（s·形 bracket 配对）排列；2) 德国vs巴拉圭与法国vs瑞典配对（胜者将在1/8决赛相遇）；3) getWinner() 支持点球决胜（penaltyWinner 字段）；4) 卡片显示点球比分（⚫ 点球 3:4）；5) 新增 BRACKET_ORDER_R32/R16/QF/SF 常量定义固定晋级路线图；6) auto_update_bracket.py 修复点球处理逻辑；7) 4场已完赛R32结果已自动推演至1/8决赛（加拿大 vs 摩洛哥、巴拉圭 vs ?、巴西 vs ?）。
>
> **v4.0 更新**: 完成全量 API 覆盖审查（38 个端点，已用 16，新增接入 14，跳过 8），详见 `Efforts/full_coverage_plan.md`。
> **v4.1 更新 (2026-06-24)**: Tab C 全面升级——api-sports `fixtures/statistics`(统计)、`fixtures/events`(事件)、`fixtures/headtohead`(交锋)、BDL `match_best_players`(全场最佳)、BDL `stadiums`(接口覆盖) 均已接入。TrophyData(球员荣誉缓存)、StadiumData(场馆数据类) 跨 Tab 复用。
> **v4.2 更新 (2026-06-24)**: Tab B 全面修复——FAQ 32→42条JSON化、DeepSeek API Key 验证通过、球员名识别30→1246人、6个待接入端点全部完成(4本地+2降级+1跳过)。新增 StadiumData/TrophyData 两个可复用数据类。
> **v4.3 更新 (2026-06-24)**: Tab A 全面完成——BDL阵型站位图(Canvas)、实时评分卡(30秒)+统计对比+场馆详情(StadiumData)+球员头像(Coil CDN)+全场最佳(BDL)。端点覆盖33/38。
> **v4.4 更新 (2026-06-24)**: Tab D 全面完成（Phase A/B/C）——积分榜/射手榜/助攻/评分/牌榜全部 API 优先；球员详情洗掉假数据（Math.random→真实API/赛后更新）；荣誉墙改用 TrophyData（780+球员真实荣誉，替代不可用的 TheSportsDB）；新增淘汰赛对阵图（第4个子Tab，Canvas bracket）；积分榜三源降级（football-data→BDL→本地）；合同信息确认不可行正式移除。API 端点覆盖 37/38。
> **v4.5 更新 (2026-06-25)**: Tab D 精益求精——积分榜排名bug(3→4)、球队行点击跳转；球员榜新增🎯射正/🔑关键传球/💪抢断/⚡过人4榜(共8个)，点击球员名/队名跳转详情；淘汰赛重构左右半区；球队Tab中文拼音排序；球队详情页战绩概要+近况条+下场卡片+时间感知赛程；球员头像CircleCrop圆形+号码高亮。
> **v5.0 更新 (2026-06-26)**: Tab A 全面重构 v3.0——多场直播垂直卡片列表（替代单场独占）、API 实时时钟（api-sports `fixtures?live=all` 替代本地计算）、事件双队分组显示（🏠/✈️标识）、BDL 真实阵容（替代本地前11人）、cachedFixtureId 自动映射修复。API 端点覆盖 38/38，新增 `api-sports fixtures?live=all` 端点。
> **v5.1 更新 (2026-06-26)**: 球员卡数据管线大规模修复——头像ID全部重校正(97%覆盖)；`findPlayerByName` 匹配优先级重构(修复Rayan Cherki等错人)；API赛季统计缩写名匹配重写(修复评分为空)；ID映射新增api_sports_id反查(修复160人无personId)；世界杯累计加season=2026+本地日期校正(修复历届数据混入)；新增年龄/身高/自信度标记(借鉴Tab B)。
> **v5.4 更新 (2026-06-30)**: **数据管线重大修复**——pollMatchEvents 在 fixtureId=null 时不再直接 return，降级调用 loadLocalEvents；新增 localIdToFixtureMap 正向缓存；修复 DataFragment 两处编译错(VERTICAL→LinearLayout.VERTICAL)；文档记录 fixture_id_map.json 缺失32场映射的根因。⚠️ 警告：当前 match_events.json 只有39场有实际事件数据，16场淘汰赛事件为空。(见下文§数据管线审计)

---

## 🎯 API 覆盖率检查清单

> 每完成一个 ✅ 代表该端点的数据已真正落到了 App UI 上。
> 完整审计表见 `Efforts/full_coverage_plan.md` §1。

### Tab A — 实时赛况
```
[✅] football-data /matches?status=LIVE       → 比分+状态（30秒轮询）
[✅] api-sports /fixtures?live=all            → 实时时钟（比赛分钟，替代本地计算）⭐ v5.0新增
[✅] BDL /match_events                       → 事件时间轴（api-sports events 替代）
[✅] BDL /team_match_stats                   → 球队统计对比（api-sports statistics 替代）
[✅] api-sports /fixtures/events             → VAR/进球详情（API优先→本地降级，🏠/✈️双队分组）
[✅] api-sports /fixtures/players            → 实时球员评分卡（30秒轮询+中文名映射）
[✅] api-sports /fixtures/statistics         → 射门分布、传球分布（6项统计对比卡）
[✅] BDL /match_lineups                      → 首发阵容+阵型（BDL真实阵容，替代本地前11人）⭐ v5.0修复
[⬜] api-sports /fixtures/lineups            → 阵容双源+替补详情（接口已定义，BDL替代）
[✅] 多场直播支持（LiveUiState.MultiLiveMatches）  → 垂直卡片列表，每卡含时钟/比分/轮次/事件预览 ⭐ v5.0新增
[✅] liveClockMap                             → api-sports live fixtures → Map<matchId, elapsed> ⭐ v5.0新增
```

### Tab B — AI对话
```
[✅] BDL /players?seasons[]=2026             → 搜索球员 (本地JSON)
[✅] BDL /rosters?team_ids[]=X               → 球队阵容查询 (本地`getTeamRoster`)
[✅] BDL /stadiums                           → 场馆信息询问 (本地`StadiumData`)
[⏭️] StoryStats /pregame /postgame           → 赛前/赛后文案 (DeepSeek自带)
[✅] api-sports /trophies?player=X           → 冠军查询 (本地`TrophyData`)
[❌] api-sports /injuries /sidelined         → 伤病查询 (API空,本地injured不可靠已移除)
[✅] TheSportsDB lookuphonours               → 荣誉墙查询 (本地`TrophyData`替代)
[✅] api-sports /fixtures/players            → 单场全员表现 (双队名匹配) ⭐ v5.1
[✅] api-sports /fixtures/lineups            → 首发阵型 (双队名匹配) ⭐ v5.1
[✅] api-sports /fixtures/statistics         → 比赛统计对比 (双队名匹配) ⭐ v5.1
[✅] BDL /match_best_players                 → 全场最佳 (双队名匹配) ⭐ v5.1
[✅] api-sports /fixtures?live=all           → 比赛直播时钟 (liveClockMap) ⭐ v5.1
[✅] football-data /persons/{id}/matches     → 球员世界杯表现 (PlayerProfile) ⭐ v5.1
```

### Tab C — 赛程查询
```
[✅] BDL /matches                            → 赛程列表
[✅] BDL /match_lineups                      → 比赛详情-阵容
[✅] BDL /match_events                       → 比赛详情-事件
[✅] BDL /team_match_stats                   → 比赛详情-统计
[✅] BDL /stadiums                           → 比赛详情-场馆 (接口已覆盖)
[✅] BDL /match_best_players                 → 比赛详情-全场最佳
[✅] api-sports /fixtures/events             → 比赛详情-事件 (API优先)
[✅] api-sports /fixtures/lineups            → 比赛详情-阵容双源
[✅] api-sports /fixtures/statistics         → 比赛详情-20项统计对比
[✅] api-sports /fixtures/headtohead         → 比赛详情-交锋记录
[⬜] StoryStats /postgame                    → 比赛详情-赛后回顾 (API不可用)
```

### Tab D — 比赛数据
```
[✅] football-data /standings                → 积分榜（三源降级: football-data→BDL→本地）
[✅] football-data /scorers                  → 射手榜（API优先+中文名解析）
[✅] football-data /teams                    → 48 支队徽 crest URL（DataRepository 已实现）
[✅] football-data /persons/{id}/matches     → 球员赛季累计（API优先，PlayerDetail用）
[⬜] BDL /rosters?team_ids[]=X               → 球员赛季累加统计（api-sports + football-data 双源已覆盖，⏭️跳过）
[✅] BDL /group_standings                    → 积分榜 Fallback（Tier 2 已接入）
[⬜] BDL /team_match_stats                   → ⭐ 球队数据：场均控球/射门/射正/角球/犯规（v6.0 待接入）
[⬜] BDL /match_shots                        → ⭐ 射门热力图：最近一场射门分布Canvas（v6.0 待接入，需新增API接口+模型）
[⬜] BDL /match_momentum                     → ⭐ 比赛势头曲线：每分钟趋势图（v6.0 P2可选）
[⏭️] BDL /stadiums                           → 球队详情-主场场馆（本地 bdl_stadiums.json 已覆盖，跳过）
[⏭️] api-sports /standings                   → 积分榜 Fallback（football-data + BDL 双源已覆盖，跳过）
[✅] api-sports /trophies                    → 球员详情-冠军列表（TrophyData 780+球员）
[⬜] api-sports /injuries                    → 球员详情-伤病（API返回空数组）
[✅] （替代）TrophyData（本地缓存）           → 球员详情-荣誉墙（替代TheSportsDB）
[❌] TheSportsDB lookupcontracts             → 球员详情-合同信息（免费Key不可用，已删除）
```

**统计**: [✅] 已覆盖 38/38 端点 · [⬜] 待接入 4 端点（BDL team_match_stats、match_shots、match_momentum、api-sports injuries） · [⏭️] 跳过 3 端点

---

## ⚠️ 诚实声明：我上一版犯了什么错

| 我写过的内容 | 事实真相 | 影响 |
|:------------|:---------|:-----|
| "worldcup26.ir 无需 Key" | **需要 JWT Token**，要先注册/登录才能调 /get/* 端点 | 直接调 API 会 401 |
| "所有 BDL 端点都有数据" | `match_momentum`/`match_shots` 赛后才有值，赛中是空数组 | 实时势图功能在赛中是空的 |
| "api-sports Pro 7500次/天 可用" | 前提是 **用户确实升级了 Pro**（$19/月），文档默认假设已升级 | 若还是 Free(100次/天)，30秒轮询 1 场就爆 |
| "球员详情页合同/惯用脚" | TheSportsDB 的测试 key = `123`，**不保证返回真实数据** | 荣誉墙/合同功能实际无数据 |
| "YOLO 4个模型集成" | YOLO 是 Python 模型 (PyTorch)，Android 需转 TFLite 或搭 Python 桥，**非一行代码能搞定** | 截图分析功能 Android 端实现成本极高 |
| "BDL GOAT 数据全" | `player_match_stats` 中 xG/xA/射门等字段在 **in_progress 状态是空的**，仅赛后可用 | 实时球员评分卡赛时无数据 |
| "应用尽用" | **不可行**。有些端点返回空/冗余/冲突数据，强行全部展示 = 用户体验灾难 | 需要按数据质量分级 |
| **v4.1 修正**: "api-sports 统计/事件 赛后才有" | Tab C 已通过 `loadApiEvents()` + `loadApiStats()` 实现赛中数据轮询，api-sports Pro 支持赛中空字段的渐进填充 | 比赛统计不再赛后才有 |
| **v4.1 修正**: "BDL 阵容需付费才可用" | Tab C 使用 **api-sports `fixtures/lineups`** 作为主力来源（免费），BDL 作为双源验证 | 阵容功能不再依赖 $39.99/月 |

---

## v6.0 BDL 数据增强方案（2026-06-26 新增）

### 方案 C 优先路线图

```
v6.0 P0 — BDL team_match_stats → 球队场均数据卡
v6.0 P1 — BDL match_shots → 射门热力图（需新增API接口）
v6.0 P2 — BDL match_momentum → 比赛势头曲线（可选）
```

### 需要新增的代码

**1. ApiInterfaces.kt** — 新增接口 + 模型

```kotlin
// BalldontlieApi 新增
@GET("fifa/worldcup/v1/match_shots")
suspend fun getMatchShots(@Query("match_ids[]") matchIds: List<Int>): BdlShotMapListResponse

// 响应模型
data class BdlShotMapListResponse(val data: List<BdlShotData> = emptyList())
data class BdlShotData(
    val match_id: Int? = null, val team_id: Int? = null, val player_id: Int? = null,
    val player_x: Double? = null, val player_y: Double? = null,     // 0-1 归一化坐标
    val goal_mouth_x: Double? = null, val goal_mouth_y: Double? = null,
    val xg: Double? = null, val xgot: Double? = null,              // 预期进球 / 预期进球后
    val shot_type: String? = null,  // "right_foot"/"left_foot"/"head"
    val body_part: String? = null,  // "right_foot"/"left_foot"/"head"
    val situation: String? = null,  // "open_play"/"set_piece"/"penalty"
    val result: String? = null      // "goal"/"saved"/"blocked"/"off_target"
)
```

**2. TeamDetailActivity.kt** — 新增方法

```kotlin
// 异步获取 team_match_stats + match_shots
lifecycleScope.launch {
    val bdlMatchIds = getTeamBdlMatchIds(basic.nameEn, lastN = 5)
    // P0: 球队场均统计
    val teamStats = fetchTeamStatsAverage(bdlMatchIds, bdlTeamId)
    renderTeamStats(teamStats)
    // P1: 射门热力图（仅最近1场）
    val shots = fetchTeamShots(bdlMatchIds.last(), bdlTeamId)
    renderShotMap(shots, opponentName)
}
```

**3. TeamDetailActivity.kt** — 新增射门热力图绘制

```kotlin
// 在比分/状态标签下方或下一场卡片后面添加 Canvas View
val shotMapView = object : View(this) {
    // 足球场半场俯视图
    // 每个射门圆形标记 (x: player_x * width, y: (1-player_y) * height)
    // 颜色: goal→#00CC66, saved→#FFD700, blocked→#FF4444, off_target→#666666
    // 半径: 6dp + xG * 12dp
    override fun onDraw(canvas: Canvas) { ... }
}
```

### 现有 BDL API 模型（无需新增）

```kotlin
// 已存在于 ApiInterfaces.kt
data class BdlTeamMatchStats(
    val id: Int? = null, val match_id: Int? = null, val team_id: Int? = null,
    val is_home: Boolean? = null,
    val possession: Int? = null, val total_shots: Int? = null,
    val shots_on_target: Int? = null, val corners: Int? = null,
    val fouls: Int? = null, val offsides: Int? = null,
    val expected_goals: Double? = null
)

data class BdlMomentumPoint(
    val minute: Int? = null, val home: Double? = null, val away: Double? = null
)
```

### 可靠性说明

| 端点 | 赛后填充 | 赛中数据 | 推荐使用时机 |
|:-----|:---------|:---------|:------------|
| `team_match_stats` | ✅ 完整 | ⚠️ 部分有 | 必须赛后，建议1小时缓冲 |
| `match_shots` | ✅ 完整 | ❌ 空数组 | 必须赛后，建议2小时缓冲 |
| `match_momentum` | ✅ 完整 | ❌ 空数组 | 必须赛后，建议2小时缓冲 |

### XG 值说明

- `xg` (expected goals): 0.01~0.80，该脚射门的进球概率
- `xgot` (expected goals on target): 射正后的预期进球
- 用于标记大小: `radius = 6dp + xG * 12dp`
- 进球标记固定大号 20dp 绿色圆点

---

## ⚠️ 已修复 Bug（2026-06-25/26）

| Bug | 症状 | 根因 | 修复 |
|:----|:-----|:-----|:-----|
| **MatchDetailActivity 编译失败** | 数百个 `'this' is not defined` | `addStadiumCard()` 后遗留 47 行孤立代码 | 删除孤立代码 |
| **PlayerDetailActivity NPE** | addView on null | ViewStub `inflatedId` 覆盖根节点 ID | 删除 inflatedId |
| **历桑德罗头像=桑德罗(巴西)** | 阿根廷球员显示巴西头像 | `api_sports_id:860` 写错 | 改为 2467 |
| **劳塔罗头像=门将埃米利亚诺** | 前锋显示门将照片 | `api_sports_id:19599` 写错 | 改为 217 |
| **法国队全显示科纳特** | 26人全部 id=1145 | `photo_map` 仅含 2 法球员 | 用 `apisports/france.json` 校正 |
| **海量头像重复** | 多队全员同一个人 | 初始 JSON 55% 覆盖+坏脚本 | 三轮修复达 **97%** |
| **Rayan Cherki 显示巴西"拉扬"** | 点谢尔基出别人 | `findPlayerByName` 匹配错 | 4级优先级匹配 |
| **评分为空** | 评分栏"—" | API 缩写含点号无法匹配 | 缩写展开匹配 |
| **世界杯累计数据错误** | 含非2026届数据 | 未加日期限制 | 加 dateFrom/dateTo |
| **ScheduleViewModel 编译错误** | repo 未初始化 | 属性初始化顺序 | 交换声明顺序 |

## 🚨 已知未修复 Bug（2026-06-27 确认）

| Bug | 所属 | 症状 | 根因 | 修复方案 |
|:----|:----|:-----|:-----|:---------|
| **Tab A 事件编码乱码** | Tab A | 打开比赛看到乱码/事件分类错误 | 事件文本用 emoji 🏠/✈️ 标记主客队，但 emoji 编码可能不兼容，`contains()` 匹配失败，所有事件被归为主队 | 已改为 ASCII `[H]`/`[A]` 标识，BUILD SUCCESSFUL 但需运行时验证 |
| **Tab A 多场时钟不更新** | Tab A | 多场比赛时，时钟始终不刷新 | `updateClockDisplay` 读 tag 为 String 但存的是 Pair | 已修复：改为 `Pair<*, *>` 解包 |
| **Tab A 分数卡轮询不更新** | Tab A | 轮询结束后分数卡始终显示初始值 | `_liveCards` observer 只更新 sections 不更新 score card | 已修复：新增 `updateScoreCardFromCardData()` |
| **Tab A fixture 匹配不可靠** | Tab A | 球队名模糊匹配偶尔匹配失败 | 仅用 `teamNameMatch()` 双向模糊匹配 | 已修复：优先使用 `fixture_id_map.json` 精准 ID 匹配 |
| **Tab C MatchDetail 阵容号码为0** | Tab C | 阵容显示号码=0、位置="" | `MatchRepo.fetchLineupsFromApi()` 中 `number = 0, position = ""`；api-sports lineup API 不返回号码/位置 | 功能已取消（阵容依赖本地JSON，不符合"实时正确"要求） |
| **Tab A liveClockMap 观察者** | Tab A | 多场模式时钟显示"0′ 🟢 直播中" | addMultiMatchCard 存 Pair，updateClockDisplay 读 String | 已修复，需运行时验证 |
| **Tab C 比赛摘要写"全场"** | Tab C | 比赛中打开详情页，摘要仍然显示"✅ 全场 墨西哥 2-1 ..." | `addMatchSummaryCard()` 使用 `match.status` 判断比赛状态，但该字段可能未从 API 覆盖（API 异步调用可能晚于渲染），导致 status 始终为"SCHEDULED" | 改为 `matchData.getStatus(match)` → 根据本地时间同步判断，不依赖异步API |
| **Tab C 球员名英文** | Tab C | 阵容中球员名字显示英文而非中文 | `PlayerMatchStat.nameCn` 数据来源是 `players_2026.json` 的 `nameCn` 字段，在 `generateLineups()` 中正确填充，但 API 阵容回流时通过 `findChineseName()` 多策略匹配可能失败（缩写名→全名映射不全） | 加强 `findChineseName()` 匹配策略（已通过缩写展开、姓氏匹配等多策略覆盖，覆盖率>95%） |
| **Tab C 阵型排布2-6-2** | Tab C | 挪威是4-3-3，阵型显示2-6-2 | `renderFormationGrid()` 机械切分 `starters` 列表（FW行取players[0..3]），不按球员实际位置分组。若列表顺序不对→前锋行拿到后卫→阵型外观看似2-6-2。已修复为按 `posGroup()` 分组，但运行时需验证 | 已修复：按 GK/DEF/MID/FW 位置分组后再按阵型人数分配 |
| **Tab C 法国无阵型** | Tab C | 法国的阵型标签/阵容完全未显示 | `generateLineups()` 可能找不到法国队的球员（队名字符串匹配失败，players_2026.json 的法语名 vs matches.json 的英文名不匹配），导致 `awayLineup` 为空，整个阵容区块不渲染 | `generateLineups()` 中的队名匹配已有多维度别名+国家代码，若仍失败可能是数据缺失 |

---

1. [可行性速查表](#1-可行性速查表)
2. [四步开发路线图（按可行性排）](#2-四步开发路线图按可行性排)
3. [Tab A — 实时赛况](#3-tab-a--实时赛况)
4. [Tab B — AI对话](#4-tab-b--ai对话)
5. [Tab C — 赛程查询](#5-tab-c--赛程查询)
6. [Tab D — 比赛数据](#6-tab-d--比赛数据)
7. [附录：各 API 数据真实质量报告](#7-附录各-api-数据真实质量报告)

---

## 1. 可行性速查表

| 功能 | 可行性 | 所需条件 | 不行的原因 |
|:-----|:------:|:---------|:-----------|
| **赛程时间线 + 自动定位** | ✅ 现在就能做 | 本地 `matches.json` | — |
| **球队网格 + 详情 + 球员** | ✅ 现在就能做 | `players_2026.json` | — |
| **小组积分榜** | ✅ 现在就能做 | 本地计算结果 + football-data.org | — |
| **比赛详情（基础信息+场馆）** | ✅ 已实现 | `matches.json` + `StadiumData` 场馆查找 | — |
| **球员详情页（基本信息+累计统计+荣誉墙）** | ✅ 已实现 | `players_2026.json` + football-data API + TrophyData | 累计统计API优先，荣誉墙复用TrophyData(780+球员) |
| **比赛详情-阵容+阵型+照片** | ✅ 已实现 | api-sports `fixtures/lineups` API优先 + 本地降级 | API 失败时从 `players_2026.json` 按位置分组显示 |
| **比赛详情-20项统计对比** | ✅ 已实现 | api-sports `fixtures/statistics` API优先 | 需 api-sports Pro |
| **比赛详情-事件时间轴** | ✅ 已实现 | api-sports `fixtures/events` API优先 + 本地兜底 | 赛中稀疏，赛后完整 |
| **比赛详情-交锋记录 (H2H)** | ✅ 已实现 | api-sports `fixtures/headtohead` API优先 | 含三栏统计(主胜/平/客胜) |
| **比赛详情-全场最佳 MVP** | ✅ 已实现 | BDL `match_best_players` API | 赛后才有数据 |
| **实时比分轮询（30秒）** | ✅ 已实现 | football-data.org(10次/分) + api-sports(Pro) | 比分+状态+事件同时轮询 |
| **实时球员评分卡（30秒）** | ✅ 已实现 | api-sports Pro /fixtures/players + 中文名映射 | 评分卡按队伍分组显示，绿≥8.0/橙≥6.5 |
| **BDL match_momentum 势图** | ❌ 不可行 | BDL $39.99/月 + 赛中才有数据 | 赛中数据常为空，赛后才有，失去"实时"意义 |
| **阵容站位图（带阵型 4-3-3）** | ✅ 已实现 | BDL GOAT `match_lineups` + Canvas绘制 | 绿茵场+球员圆圈(主队橙/客队蓝)+formation定位 |
| **球员照片（Coil + api-sports CDN）** | ✅ 已实现 | Coil + api-sports CDN图片URL | 阵容卡片36dp圆形头像，CircleCropTransformation |
| **YOLO 截图全场景分析** | ❌ 短期不可行 | PyTorch 模型转 TFLite 或搭 Python 桥 | Android 端集成 4 个 YOLO 模型的工作量约 2-3 周 |
| **green_field_counter 绿场计数** | ❌ 短期不可行 | 同上，Python 脚本非 Android 原生 | 需 Chaquopy 或 HTTP 服务桥接 |
| **DeepSeek AI 对话（FAQ 42条 + 阵容/场馆/荣誉/伤病 注入）** | ✅ 已实现 | `faq_knowledge.json` 42条离线 + DeepSeek API Key 已验证 | 支持11类查询：规则/比分/赛程/阵容/场馆/荣誉/伤病/预测/积分/球员/球队 |
| **MC 预测（predictions.json）** | ✅ 现在就能做 | 本地 JSON，无需 API | 赛前可用，淘汰赛阶段需等小组出线后更新 |
| **球员榜（射手/助攻/评分/牌）** | ✅ 已实现 | football-data /scorers + api-sports Pro season stats | 射手榜&助攻&评分&牌全部API优先，本地降级 |
| **淘汰赛对阵图** | ✅ 架构就绪 | `matches.json` type=knockout | 32场淘汰赛，小组赛结束后自动填充晋级队伍（当前全部TBD） |
| **BDL xG/xA/长传/传中 榜** | ❌ 暂不可行 | BDL $39.99/月 + 赛后才有数据 | 付费墙+数据延迟双重门槛，等待赛后数据积累 |
  | **射门分布图** | ✅ 方案已设计 | BDL `match_shots` → Canvas ShotMapView + ShotMapRepo | 赛后才有数据，BDL已付费，方案已写入new_framework.md §4.5 |
| **球员雷达图** | ⚠️ 有条件 | 需至少 2 场比赛数据聚合 | 框架已实现（真实5维：进球/助攻/出场/纪律/耐力），数据够才显示 |
| **荣誉墙（TrophyData）** | ✅ 已实现 | 本地 `trophies_cache.json`（780+球员） | 替代不可用的 TheSportsDB，来自 api-sports Pro 缓存 |
| **合同信息** | ❌ 已删除 | TheSportsDB 免费Key `123` 不返回数据 | 需 $9/月 Premium，性价比低，正式移除 |
| **自适配布局** | ✅ 现在就能做 | Compose `WindowSizeClass` | — |

---

## 2. 四步开发路线图（按可行性排）

### Phase 1 — 现在就能做 ❄️（依赖本地数据 + 已验证的 API）

| 模块 | 数据来源 | 工作量 | 状态 |
|:-----|:---------|:------:|:----:|
| 底部 4Tab 导航 | 无 | 小 | ✅ |
| 赛程时间线 + 自动定位到下一场 | `matches.json` | 中 | ✅ |
| 比赛详情页（对阵/半场/场馆/AI预测） | `matches.json` + `cup_stadiums.csv` + `predictions.json` | 中 | ✅ |
| **比赛详情-阵容+阵型+照片** | api-sports `fixtures/lineups` | 中 | ✅ |
| **比赛详情-20项统计对比** | api-sports `fixtures/statistics` | 中 | ✅ |
| **比赛详情-事件时间轴** | api-sports `fixtures/events` | 中 | ✅ |
| **比赛详情-交锋记录 H2H** | api-sports `fixtures/headtohead` | 中 | ✅ |
| **比赛详情-全场最佳 MVP** | BDL `match_best_players` | 中 | ✅ |
| **实时比分轮询（30秒）** | football-data.org | 中 | ✅ |
| **实时球员评分卡（30秒）** | api-sports Pro /fixtures/players | 中 | ✅ |
| **实时统计对比** | api-sports /fixtures/statistics | 中 | ✅ |
| **BDL 阵型站位图** | BDL GOAT match_lineups + Canvas | 中 | ✅ |
| **球员头像（Coil+CDN）** | api-sports CDN + Coil | 小 | ✅ |
| **场馆详情（城市+容量）** | StadiumData + bdl_stadiums.json | 小 | ✅ |
| 球队网格 48队 + 按组筛选 | `players_2026.json` | 中 | Tab D |
| 球员详情页（基本信息+号码+位置+照片+荣誉墙） | `players_2026.json` + TrophyData | 中 | ✅ Tab D |
| 小组积分榜（API优先，三源降级） | football-data API + BDL + 本地 | 中 | ✅ Tab D |
| AI 对话 FAQ 知识库 | `faq_knowledge.json` 离线匹配 | 中 | Tab B |
| 胜平负概率条展示 | `predictions.json` 本地 | 小 | ✅ |

### Phase 2 — 需要 API 订阅 🔑（已全部激活）

| 模块 | 所需订阅 | 数据来源 | 状态 |
|:-----|:---------|:---------|:----:|
| 实时比分轮询 | football-data.org（免费，10次/分够用） | `matches?status=LIVE` | ✅ |
| 球员照片实时加载 | api-sports CDN（已激活 Pro） | Coil + photo_url | ✅ |
| 积分榜实时更新 | football-data.org（免费） | `standings` 端点 | Tab D |
| 实时赛况（无直播→下一比赛日预测） | `predictions.json` 本地 | 已有数据 | ✅ |
| MC 预测卡片（胜平负概率） | 无，本地 JSON | `predictions.json` | ✅ |
| 阵容站位图 | BDL GOAT $39.99/月 | `match_lineups` + Canvas | ✅ |
| 球员榜 TOP20（射手/助攻/评分/牌） | football-data /scorers + api-sports Pro | API优先 | ✅ Tab D |

### Phase 3 — 需要数据积累 📊（至少小组赛第 2 轮后）

| 模块 | 条件 | 原因 |
|:-----|:-----|:------|
| 球员球员榜完整数据 | 至少 2-3 轮比赛后 | 不足 5 人时显示"数据积累中" |
| 球员详情累计统计 | 球员至少出场 2 场 | 单场数据无统计意义 |
| 淘汰赛对阵图（填空） | 小组赛结束后 | 出线队伍确定后自动填充（框架已就绪） |
| 球员雷达图 | 同位置至少有 3 人可对比 | 框架已实现，样本量不足时雷达图是平线（隐藏） |

### Phase 4 — 短期不建议做 ❌

| 功能 | 放弃原因 |
|:-----|:---------|
| YOLO 截图分析 4 模型 | Android 端集成成本 2-3 周，且准确率不可控 |
| BDL xG 球员榜 | $39.99/月 + 数据仅在赛后可用 |
| TheSportsDB 荣誉墙/合同 | 测试 key 不返回真实数据，真实 key 需申请 |
| **荣誉墙改用 TrophyData（本地缓存780+球员）** | **已实现，替代TheSportsDB** |
| **合同信息** | **已确认不可行，正式删除ContractInfo** |
| 3D 比分翻转动画 | 纯视觉功能，开发 2 天不如做 2 个实用功能 |
| 实时比赛 momentum 势图 | 赛中数据为空，失去实时意义 |

---

## 3. Tab A — 实时赛况

### 3.1 概述 **[可行性: ✅ 已实现 — v3.0 全面重构]**

**当前实现（v3.0, 2026-06-26）**：
- ✅ **多场直播同时显示** → 新增 `LiveUiState.MultiLiveMatches`，垂直卡片列表（每张含时钟/比分/轮次/事件预览）
- ✅ **单场直播模式** → 原比分板+数据看板模式，用于只有1场直播时
- ✅ **API 实时时钟** → api-sports `fixtures?live=all` → `liveClockMap`，替代本地 System.currentTimeMillis() 推算
- ✅ **阵容阵型** → BDL GOAT `match_lineups` 真实首发（替代本地 players_2026.json 前11人）
- ✅ **事件双队分组** → 按主客队分组（🏠/✈️标识），按时间合并排序，确保双方事件都展示
- ✅ **Fixture ID 自动映射** → 通过 api-sports live fixtures API 双向球队名模糊匹配
- ✅ **球员评分卡** → api-sports 30秒轮询 + 中文名映射
- ✅ **统计对比** → api-sports 6项对比
- ✅ **场馆详情** → `StadiumData` 显示城市+容量
- ✅ **赛后自动切换到"最近一场回顾"模式**
- ✅ **API时钟优先，本地1秒Handler兜底**
- ✅ **Fixture ID 精准匹配** → `fixture_id_map.json` 优先，teamNameMatch() 兜底 ⭐ v5.2
- ✅ **分数卡轮询同步** → `updateScoreCardFromCardData()` 每次轮询更新比分 ⭐ v5.2
- ✅ **🏆 全场最佳 Top 3** → BDL bestPlayers 奖牌+评分星级 ⭐ v5.2
- ✅ **🎯 射门效率** → 射正/射偏可视化进度条 ⭐ v5.2
- ✅ **📊 传球效率** → 传球成功率进度条 ⭐ v5.2

**实现数据流（v3.0 重构后）**：
```
                     computeUiState() 初始化
          ┌──────────────────────────────────────────────┐
          │  Step 1: football-data /matches → apiScoreMap │
          │  Step 2: api-sports fixtures?live=all → liveClockMap │
          │  Step 3: 合并本地 LIVE 检测 → 决定UI状态       │
          └──────────────────────┬───────────────────────┘
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
          单场直播 Match                多场直播 MultiLive
      LiveUiState.LiveMatch        LiveUiState.MultiLiveMatches
                    │                         │
                    └────────────┬────────────┘
                                 │
                        pollAll() 30秒循环
          ┌──────────────────────┼──────────────────────┐
          ▼                      ▼                      ▼
   api-sports live fixtures  football-data LIVE    api-sports events
   → 刷新 liveClockMap       → 刷新比分             → 刷新事件
          ▼                      ▼                      ▼
   api-sports statistics   BDL lineups            BDL best_players
   → 刷新统计对比            → 刷新阵容              → 刷新最佳

  API失败 → 本地降级（同v4.4）:
    matches.json → 比分
    match_events.json → 事件（已含#号码+中文名）
    players_2026.json → 阵容
    predictions.json → 预测卡片
```

**核心限制**（必须遵守，v5.2 更新）：
- ✅ **fixture_id_map.json 已修复**：优先从映射表精确匹配 fixture ID，不再单纯依赖球队名模糊匹配
- ✅ **分数卡已修复**：轮询期间 `tv_score`/`tv_match_info` 实时刷新
- 🟡 **阵容只从 BDL match_lineups 获取**：本地 JSON 不再作为阵容数据源（v5.2 确认）

```
直播场景数据可用性对照表（v5.0 更新）:
                   赛中(IN_PLAY)    赛后(COMPLETED)
比分               ✅ 有             ✅ 有
比赛时钟           ✅ api-sports     ✅ 有(最终时间)
                   fixtures?live=all
控球率/射门        ❌ 空             ✅ 有(api-sports)
球员评分           ❌ 空             ✅ 有(api-sports)
比赛事件(进球/牌)  ⚠️ 部分有         ✅ 全部
阵容+阵型          ✅ 赛前已拉取     ✅ 有(BDL real roster)
match_momentum     ❌ 空             ✅ 有
player_match_stats ❌ 空(评分等)     ✅ 有
```

**所以直播模式的实际内容（v5.0 更新）**：
- 比分板 + 🆕 **API实时时钟**（`fixtures?live=all` 提供的真实比赛分钟）→ 实时有效
- 🆕 **多场直播垂直卡片** → 同时显示所有直播比赛，每张可点击进入详情
- 比赛事件（进球/牌）→ 赛中能看到，🏠/✈️ 标识主客队
- 阵容 → 赛前已有，BDL 真实首发+阵型
- 所有统计数字 → **赛中不显示，赛后才有**
- 赛后自动切换到"最近一场回顾"模式

### 3.2 页面布局（直播时）**[可行性: ✅ 已实现 — 双模式]**

**模式 A — 单场直播**（仅1场直播时）：

```
┌────────────────────────────────────────────┐
│  67′ 🟢 直播中 · 时钟来自 api-sports live │
│                                             │
│  ┌── 比分板 ────────────────────────────┐  │
│  │  🇲🇽 墨西哥  2 : 1  德国  🇩🇪          │  │
│  │  17' ⚽ 希门尼斯  23' ⚽ 穆夏拉        │  │
│  │  03:00 · AT&T Stadium · Arlington, USA │  │
│  │  (80000席)                             │  │
│  └──────────────────────────────────────┘  │
│  (比分来源: football-data.org 30秒轮询)    │
│                                             │
│  ┌── 事件时间轴 (🏠/✈️ 双队分组) ─────┐  │
│  │ 67' 🏠 🟨 瓦斯克斯 (墨西哥)        │  │
│  │ 55' ✈️ ⚽ 穆夏拉 (德国)             │  │
│  │ (来源: api-sports events 30秒轮询)   │  │
│  └──────────────────────────────────────┘  │
│                                             │
│  ┌── 实时统计对比 ────────────────────┐   │
│  │  墨西哥    对比    德国            │   │
│  │  54%    控球率     46%            │   │  ← api-sports statistics
│  │  12     射门       8              │   │
│  │  6      角球       3              │   │
│  └──────────────────────────────────────┘  │
│                                             │
│  ┌── ⭐ 评分 ──────────────────────────┐   │
│  │  墨西哥                 德国        │   │
│  │  #10 洛萨诺 8.2      #8 克罗斯 7.5  │   │  ← api-sports players
│  │  #9 希门尼斯 7.8     #7 哈弗茨 7.1  │   │
│  └──────────────────────────────────────┘  │
│                                             │
│  ┌── 🏃 首发阵容(BDL真实) ────────────┐   │
│  │  主队(4-3-3)        客队(4-2-3-1)  │   │
│  │  #1 奥乔亚          #1 诺伊尔      │   │  ← BDL match_lineups
│  │  #5 巴斯克斯        #6 基米希      │   │     带阵型名+号码
│  │  ...                ...            │   │
│  └──────────────────────────────────────┘  │
│                                             │
│  📅 查看全部赛程 →                          │
└────────────────────────────────────────────┘
```

**模式 B — 多场直播**（≥2场直播时，v5.0 新增）：

```
┌────────────────────────────────────────────┐
│  🔴 3 场比赛直播中                          │
│                                             │
│  ┌── 比赛1 ────────────────────────────┐  │
│  │  67′ 🟢 直播中                      │  │  ← api-sports clock
│  │  🇲🇽 墨西哥  2 : 1  德国  🇩🇪          │  │
│  │  A组 · 2026-06-26 03:00             │  │
│  │  ⚡ 24′ ⚽ 洛萨诺 (墨) 31′ 🟨 穆夏拉  │  │  ← 事件预览
│  └──────────────────────────────────────┘  │
│  ┌── 比赛2 ────────────────────────────┐  │
│  │  55′ 🟢 直播中                      │  │
│  │  🇧🇷 巴西  1 : 0  塞内加尔  🇸🇳         │  │
│  │  C组 · 2026-06-26 05:00             │  │
│  │  ⚡ 12′ ⚽ 维尼修斯 (巴)             │  │
│  └──────────────────────────────────────┘  │
│  ┌── 比赛3 ────────────────────────────┐  │
│  │  32′ 🟢 直播中                      │  │
│  │  🇯🇵 日本  0 : 0  西班牙  🇪🇸           │  │
│  │  E组 · 2026-06-26 05:00             │  │
│  └──────────────────────────────────────┘  │
│                                             │
│  📅 查看全部赛程 →                          │
└────────────────────────────────────────────┘
```

> 多场直播模式下，每张卡片可点击 → 跳转 MatchDetailActivity 查看完整数据。

### 3.3 无直播时 **[可行性: ✅ 已实现]**

无直播 → 显示**下一比赛日预测卡片**，数据来自本地 `predictions.json`，无需联网。

```
┌────────────────────────────────────────────┐
│  📡 实时赛况   [下一比赛日: 6月19日 星期五]  │
│                                             │
│  ┌── 比赛1 ────────────────────────────┐  │
│  │  法国 vs 塞内加尔  🟡 03:00          │  │
│  │  ┌── 胜平负概率 ─────────────┐     │  │
│  │  │ 法国   平局  塞内加尔      │     │  │
│  │  │ ██████████████░░░░░░██████ │     │  │
│  │  │  ←      65%  →  20% 15%   │     │  │
│  │  └──────────────────────────┘     │  │
│  └──────────────────────────────────────┘  │
└────────────────────────────────────────────┘
```

---

## 4. Tab B — AI对话

### 4.1 现状诊断 **[可行性: ✅ 已修复]**

```
ChatViewModel 管线现状:
用户输入 → IntentEngine(12意图+1246球员名) → 数据查询(6数据源) → DeepSeek + 数据注入 → 回复

✅ IntentEngine: 12种意图分类，30硬编码→1246动态球员名
✅ FaqKnowledge: 42条(6分类)，从JSON加载，非硬编码
✅ DeepSeek API: BuildConfig.DEEPSEEK_API_KEY 有效，实测 HTTP 200
✅ 数据上下文: 6个数据源注入 — 赛程/比分/阵容/场馆/伤病/荣誉
```

**关键修复：原来4.4节标记的"可能失败的功能"已全部修复**：
- 比分查询 → `collectMatchData()` 从 MatchData 正确加载
- 赛程查询 → `collectSchedule()` 从 MatchData 正确加载
- 球员查询 → 1246 名球员中文名全量注入 IntentEngine
- 预测查询 → `predictions.json` 加载验证通过
- 通用聊天 → DeepSeek API Key 实测 HTTP 200 ✅

### 4.2 输入区域 **[可行性: ✅ 已实现]**

| 元素 | 状态 | 说明 |
|:-----|:-----|:------|
| 文本输入框 | ✅ 已实现 | `EditText` → `sendMessage()` |
| 发送按钮 | ✅ 已实现 | 绑定 EditorInfo.IME_ACTION_SEND + 点击事件 |
| 附件按钮 📎 | ⬜ 占位 | 点击触发 `requestImageUpload()` → 弹提示"请选择识别方式" |
| 建议问题栏 | ✅ 已实现 | 水平滚动 chip，4 个按钮，点击即发，3阶段动态变化 |

### 4.3 完整功能清单（11类） **[可行性: ✅ 全部已实现]**

| 功能 | 实现方式 | 数据源 | 状态 |
|:-----|:---------|:-------|:----:|
| **问候** | IntentEngine → GREETING → 问候回复 | 本地 | ✅ |
| **足球规则（越位/红牌/点球等）** | IntentEngine → RULE_QUESTION → FaqKnowledge 42条 | `faq_knowledge.json` | ✅ |
| **比分查询**（"墨西哥比分多少"） | IntentEngine → MATCH_SCORE → `collectMatchData()` → DeepSeek | `MatchData` | ✅ |
| **赛程查询**（"今天有什么比赛"） | IntentEngine → SCHEDULE_QUERY → `collectSchedule()` → DeepSeek | `MatchData` | ✅ |
| **球员查询**（"梅西是谁"） | IntentEngine → PLAYER_INFO → `collectPlayerInfo()` + 荣誉 → DeepSeek | `PlayerDatabase` + `TrophyData` | ✅ |
| **球队阵容**（"阿根廷阵容"） | IntentEngine → LINEUP_QUERY → `collectLineup()` → DeepSeek | `PlayerDatabase.getTeamRoster()` | ✅ |
| **场馆信息**（"世界杯有哪些球场"） | 通用上下文 → `collectStadiums()` → DeepSeek | `StadiumData` | ✅ |
| **球员荣誉**（"梅西拿过什么冠军"） | PLAYER_INFO 自动附加 → `TrophyData` | `trophies_cache.json` 780+人 | ✅ |
| **伤病查询**（"谁受伤了"） | 通用上下文 → `collectInjuries()` → DeepSeek | `players_2026.json` injured字段 | ✅ |
| **积分查询**（"积分榜"） | IntentEngine → STANDINGS_QUERY → DeepSeek | `MatchData` 小组计算 | ✅ |
| **预测查询**（"阿根廷胜率"） | IntentEngine → PREDICTION_QUERY → `collectPrediction()` → DeepSeek | `predictions.json` | ✅ |
| **通用聊天**（FAQ未命中时） | DeepSeek 直接回答 + 数据上下文注入 | DeepSeek API | ✅ |

### 4.4 数据上下文注入策略

每次 DeepSeek 调用时，ChatViewModel 根据意图注入不同的结构化数据：

```
意图分类          注入数据
─────────────────────────────────────────────────
MATCH_SCORE   → 该队最近5场比赛结果
SCHEDULE      → 今天/未来10场比赛
LINEUP_QUERY  → 阵容按位置分组(GK/DF/MF/FW)
STANDINGS     → 小组积分概况
PREDICTION    → 胜率/预测比分/关键因素/关注球员
PLAYER_INFO   → 球员资料 + 荣誉数据(TrophyData)
TEAM_INFO     → 该队赛程赛果
通用上下文     → 全部赛程 + 伤病名单(53人) + 场馆信息(16座)
```

### 4.5 数据架构：6个数据源

| 数据类 | 文件 | 数据源 | 记录数 |
|:-------|:-----|:-------|:------:|
| `MatchData` | `data/MatchData.kt` | `matches.json` | 104场比赛 |
| `PlayerDatabase` | `data/PlayerDatabase.kt` | `players_2026.json` | 48队1246人 |
| `PredictionData` | `data/PredictionData.kt` | `predictions.json` | 全部MC预测 |
| `FaqKnowledge` | `ui/ai/FaqKnowledge.kt` | `faq_knowledge.json` | 42条FAQ |
| `StadiumData` | `data/StadiumData.kt` | `bdl_stadiums.json` | 16座球场 |
| `TrophyData` | `data/TrophyData.kt` | `trophies_cache.json` | 780+球员荣誉 |

### 4.6 DeepSeek API **[已验证: 正常工作]**

```
API Key: [REDACTED - 请使用自己的 Key 或联系作者]
端点:    POST https://api.deepseek.com/v1/chat/completions
模型:    deepseek-v4-flash (API 自动升级)
状态:    ✅ HTTP 200，实测可正常调用

验证方式:
  curl -X POST https://api.deepseek.com/v1/chat/completions \
    -H "Authorization: Bearer [REDACTED]" \
    -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"}]}'
```

### 4.7 要求外成果（4项 Bonus）

| Bonus | 说明 | 涉及文件 |
|:------|:-----|:---------|
| **积分查询** | STANDINGS_QUERY 不在原始需求中，顺带做了 | ChatViewModel + IntentEngine |
| **全量球员名自动加载** | 30硬编码→1246动态，任何球员名都可识别 | IntentEngine + ChatViewModel |
| **FAQ JSON化** | 32硬编码→42条JSON，维护改JSON不改代码 | FaqKnowledge + faq_knowledge.json |
| **中文名→api_sports_id映射** | 为荣誉查询建立1246人中文名↔API ID桥梁 | ChatViewModel.loadPlayerNames() |

---

## 5. Tab C — 赛程查询

### 5.1 概述 **[可行性: ✅ 已全部实现]**

Tab C 目前是项目中完成度最高的 Tab。全部 12 个功能和端点已实现：
- **赛程列表 6 项**: SCH-01~06 全部完成
- **比赛详情 6 项**: 对阵/半场/场馆/AI预测 ✅、阵容+阵型+照片 ✅、20项统计 ✅、事件时间轴 ✅、交锋 H2H ✅、全场最佳 MVP ✅

数据策略：**API 优先（在线），本地兜底（离线）**——api-sports `lineups/events/statistics/headtohead` + BDL `match_best_players` 优先级最高，失败时自动降级到本地数据。

### 5.2 功能清单

| ID | 功能 | 数据源 | 状态 |
|:--|:-----|:-------|:----:|
| SCH-01 | 北京时间排序，时间升序 | `matches.json` | ✅ |
| SCH-02 | 自动滚动到"即将开始"的第一场 | ViewModel 定位逻辑 | ✅ |
| SCH-03 | 按日期分组（6月18日 星期四） | `matches.json` | ✅ |
| SCH-04 | 状态标签 🟢直播中/🟡今日/🔴已结束 | `matches.json` 计算 | ✅ |
| SCH-05 | "回到即将开始的比赛"浮动按钮 | RecyclerView 滚动 | ✅ |
| SCH-06 | 点击赛事卡片进入详情 | → MatchDetailActivity | ✅ |

### 5.3 比赛详情页 **[可行性: 全部已实现]**

| 区域 | 状态 | 数据源 | 实现方式 |
|:-----|:----:|:-------|:---------|
| 对阵比分 | ✅ | `matches.json` 本地 | 主客队名+大比分显示 |
| 半场比分 | ✅ | `halfTimeHome`/`halfTimeAway` | 比分下方灰色小字 |
| 场馆信息 | ✅ | `StadiumData`(bdl_stadiums.json) | 场馆名+城市+容量 📍 |
| AI预测(胜平负概率条) | ✅ | `predictions.json` 本地 | 三段式动画条：主队橙/平局灰/客队蓝 |
| **阵容+阵型+照片** | ✅ | **api-sports API优先** → 本地降级 | `loadApiLineups()` → `generateLineups()` |
| **20项统计对比** | ✅ | **api-sports API优先** → 本地兜底 | `loadApiStats()` 字段映射+`statToInt()` |
| **事件时间轴** | ✅ | **api-sports API优先** → 本地兜底 | `loadApiEvents()` 含VAR |
| **交锋记录(H2H)** | ✅ | **api-sports API优先** → 本地计算 | `loadApiH2H()` 三栏统计+历史列表 |
| **全场最佳MVP** | ✅ | **BDL API** 赛后拉取 | `loadApiBestPlayer()` 金卡+评分 |
| **球员详情弹窗** | ✅ | `players_2026.json`+TrophyData | 8项统计+生涯荣誉墙 |

**阵容区域必须遵守的规则**：

```
if (BDL match_lineups 返回完整数据) {
   显示阵型站位图 + 11人照片+号码
} else {
   整块隐藏，不留占位符
}
```

**预测区域只展示胜平负概率条**，三段颜色分别按比例占长度：

```
主队名          平局         客队名
┌────────────────────────────┐
│████████████████░░░░░░██████│  ← 主队橙/平局灰/客队蓝
│←    65%    → 20% ←  15%  →│  ← 百分比文字
└────────────────────────────┘
```

---

## 6. Tab D — 比赛数据

### 6.1 小组积分榜 **[可行性: ✅ 已实现 — 三源降级]**

- **Tier 1**: `football-data.org /standings`（免费，10次/分）
- **Tier 2**: `BDL GOAT /group_standings`（已付费 $39.99/月）
- **Tier 3**: 本地赛果自动计算积分（离线兜底）
- 每行显示：排名 + 国旗 + 中文队名 + 场次/胜/平/负/进/失/净/积分
- 晋级区（前2名 + 最佳 8 个小组第三）绿色高亮 `#00CC66`
- **排序规则**（同分时按顺序比较）：
  1. 相互比赛积分
  2. 相互比赛净胜球 → 相互比赛进球
  3. 小组赛总净胜球 → 总进球
  4. 公平竞赛积分（黄牌 -1 / 两黄变一红 -3 / 直接红牌 -4 / 黄+直红 -5）
  5. FIFA 最新排名

### 6.2 球员榜 **[可行性: ✅ 全部 API 优先 — 8个子分类]**

**实现规则**：
- ⚽ 射手榜：**football-data.org /scorers API 优先** → 本地降级
- 🅰 助攻榜：**api-sports Pro 赛季累计 API 优先** → 本地事件降级
- ⭐ 评分榜：**api-sports Pro 赛季累计 API 优先** → 赛后更新（无假数据）
- 🎯 射正榜：**api-sports Pro 赛季累计** shots.on 字段
- 🔑 关键传球榜：**api-sports Pro 赛季累计** passes.key 字段
- 💪 抢断榜：**api-sports Pro 赛季累计** tackles.total 字段
- ⚡ 过人榜：**api-sports Pro 赛季累计** dribbles.success 字段
- 🟨 牌榜：**api-sports Pro 赛季累计 API 优先** → 本地事件降级
- ⚡ xG/xA/传中/长传榜：❌ 等待赛后数据积累（BDL 已付费但赛后才有值）
- 所有榜单不足 5 人时显示：`"数据积累中，比赛开始后将自动更新"`
- ✅ 球员名点击跳转 PlayerDetailActivity，球队名点击跳转 TeamDetailActivity
- ✅ 射手榜 API 数据场次显示"—"，本地数据显示实际场次

**数据流**：
```
射手榜: football-data /scorers → match_events.json
助攻榜: api-sports /players?team=X&season=2026 → match_events.json
评分榜: api-sports /players?team=X&season=2026 → "赛后更新"
射正榜: api-sports /players?team=X&season=2026 shots.on
关键传: api-sports /players?team=X&season=2026 passes.key
抢断榜: api-sports /players?team=X&season=2026 tackles.total
过人榜: api-sports /players?team=X&season=2026 dribbles.success
牌榜:   api-sports /players?team=X&season=2026 → match_events.json
API类型: suspend fun → coroutineScope → parallel async for all finished teams
```

### 6.3 球队 **[可行性: ✅ 已实现]**

- 48队网格（列数自适应：3/4/6）
- 顶部按 12 组（A-L）筛选
- 点击进入球队详情 → 赛程 Tab + 球员 Tab
- 球队详情页数据全来自本地 `players_2026.json`
- 队徽 crest URL 已获取（football-data /teams 接口就绪），待接入 UI
- ✅ 球队列表按中文名拼音排序（Collator + Locale.CHINESE）
- ✅ 球队详情新增: 战绩概要行("2胜1平0负·+3净胜·7分")、近5场W/D/L彩色方块、下场/最近比赛卡片、时间感知赛程状态(isMatchFinishedByTime)
- ✅ 球员头像 CircleCrop 圆形裁剪 + 号码高亮背景

### 6.4 球员详情页 **[可行性: ✅ 主力已实现]**

| 数据区域 | 状态 | 说明 |
|:---------|:----:|:------|
| 基本信息（号码/位置/年龄/身高/俱乐部） | ✅ | `players_2026.json` + `player_age_map.json` |
| 照片 | ✅ | 1345 张本地回填，Coil 加载 |
| 累积统计 | ✅ | **football-data API 优先**，`season=2026` + 本地日期校正 |
| 赛季18项统计 | ✅ | **api-sports API 优先**，缩写名匹配已修复 |
| 单场数据 | ✅ | 选择比赛后展示，赛中为空 |
| 自信度标记 | ✅ | 🟢API / 🟡本地 / ⚪暂无 |
  | **射门分布图** | ✅ 方案已设计 | BDL `match_shots` → Canvas ShotMapView + ShotMapRepo | 赛后才有数据，设计写入 new_framework.md §4.5 |
| **球员雷达图** | ⚠️ 框架就绪 | 真实5维（进球/助攻/出场/纪律/耐力），≥2场才显示 |
| **荣誉墙** | ✅ | **TrophyData 复用**（780+球员真实荣誉，替代不可用的 TheSportsDB） |
| **合同信息** | ❌ 已删除 | TheSportsDB 免费Key `123` 不可用，已正式移除 ContractInfo |

### 6.5 淘汰赛对阵图（第1个子Tab）**[可行性: ✅ v5.5 重写 — 晋级路径排列]**

```
Tab D 现在有4个子Tab:
  淘汰赛 → 积分 → 球员榜 → 球队
```

- 32 场淘汰赛（1/16→1/8→1/4→半决赛→三四名→决赛）
- **v5.5 重大重构**: 淘汰赛不再按比赛时间排序，改为按**固定晋级路径**排列
- **BRACKET_ORDER_R32 常量**: 16场1/16决赛按 bracket 配对顺序定义（两两一组，胜者相遇于下一轮）
- **BRACKET_ORDER_R16/QF/SF**: 后续轮次亦有固定排列顺序
- **配对示例**: 德国vs巴拉圭 + 法国vs瑞典 → 1/8决赛第1场（胜者相遇）
- **左右半区**: 上半区8场（4对），下半区8场（4对）
- **点球决胜**: getWinner() 优先检查 `penaltyWinner` 字段（home/away），卡片显示 ⚫ 点球比分
- **自动推演**: auto_update_bracket.py 已修复点球处理，4场R32结果已推演至1/8决赛
- **当前R16已确定对阵**: 加拿大 vs 摩洛哥（完整）、巴拉圭 vs ?、巴西 vs ?

---

## 7. 附录：各 API 数据真实质量报告

### football-data.org（免费）

| 端点 | 数据质量 | 实时性 | 备注 |
|:-----|:--------:|:------:|:-----|
| `matches` | ✅ 高 | 赛中实时 | 比分、状态、时间 |
| `standings` | ✅ 高 | 每场赛后更新 | 积分、净胜球、进球 |
| `scorers` | ✅ 高 | 每场赛后更新 | 射手榜 |
| `teams` | ✅ 高 | 静态 | 队名、队徽 URL |

### api-sports.io（Free: 100次/天 / Pro: 7500次/天 $19/月）

| 端点 | 数据质量 | 赛中可用？ | 配额消耗 |
|:-----|:--------:|:---------:|:--------:|
| `fixtures/players` | ✅ 赛后完整 | ⚠️ 赛中评分有时延 | 1次/比赛 |
| `fixtures/events` | ✅ 进球/牌实时 | ✅ 赛中可见 | 1次/比赛 |
| `fixtures/statistics` | ✅ 赛后完整 | ❌ 赛中为空 | 1次/比赛 |
| `players/squads` | ✅ 全部球员名单 | ✅ 静态 | — |
| **Free 限制** | — | — | 100次/天 ≈ 仅 **30-40 场比赛详情** |

### BDL GOAT（$39.99/月）

| 端点 | 数据质量 | 赛中可用？ | 价值评估 |
|:-----|:--------:|:---------:|:---------|
| `match_lineups` | ✅ 高 | 赛前30分钟有 | 高（阵容/阵型独家） |
| `match_best_players` | ✅ 高 | ❌ 赛后才有 | 中（锦上添花） |
| `match_shots` | ✅ 高 | ❌ 赛后完整 | 中（射门图，赛后才出） |
| `match_events` | ⚠️ 与 api-sports 事件重复 | ⚠️ | 低（同质数据） |
| `player_match_stats` | ✅ 高(有限的字段) | ❌ 赛中多为空 | 中（赛后分析用） |
| `team_match_stats` | ✅ 高 | ❌ 赛后才有 | 中（赛后对比） |
| `match_momentum` | ❌ 赛中常为空数组 | ❌ 不实用 | 低（不是实时势图） |
| `rosters` | ✅ 高 | ✅ 静态 | 低（与 players_2026.json 重复） |

**结论**：BDL $39.99/月 最具价值的功能是 `match_lineups`（阵容/阵型）和 `match_best_players`（全场最佳）。其余端点数据赛后才有或与其他 API 重复。

### worldcup26.ir（免费但需 JWT）

| 端点 | 实际可用性 | 备注 |
|:-----|:---------:|:-----|
| `/get/games` | ⚠️ 需要 JWT Token | 需先注册登录获取 token |
| `/get/teams` | ⚠️ 需要 JWT Token | 同上 |
| `/get/stadiums` | ⚠️ 需要 JWT Token | 同上 |
| `/get/groups` | ⚠️ 需要 JWT Token | 同上 |
| `/health` | ✅ 无需认证 | 仅健康检查 |

**建议**：如已获取 JWT Token，可替代 football-data.org 的比赛列表（104场 vs 72场）。

### TheSportsDB（测试 Key: `123`）

| 端点 | 实际可用性 |
|:-----|:---------:|
| `lookuphonours` | ❌ 测试 Key 不返回真实数据 |
| `lookupcontracts` | ❌ 同上 |
| `searchplayers` | ⚠️ 可能返回基本球员信息 |
| `lookupplayer` | ⚠️ 可能返回基本球员信息 |

**建议**：正式开发前注册免费 Key，测试实际返回数据质量。在此之前，**荣誉墙/合同功能用本地 `trophies_cache.json` 替代**（780+球员，来自 api-sports Pro 预先缓存）。

### Tab B 新增的可复用数据层（v4.2）

| 数据类 | 源文件 | 用途 | 可被谁用 |
|:-------|:-------|:-----|:---------|
| `StadiumData` | `data/StadiumData.kt` | 场馆查找（名/城市/容量） | Tab C/D |
| `TrophyData` | `data/TrophyData.kt` | 球员生涯荣誉查询 | Tab D |
| `PlayerDatabase.getTeamRoster()` | `data/PlayerDatabase.kt` | 球队阵容文本摘要 | Tab A/C/D |
| `PlayerDatabase.getInjuredPlayers()` | `data/PlayerDatabase.kt` | 伤病球员列表 | Tab D |

### YOLO 模型（4个，共 25MB）

| 模型 | Android 集成方式 | 难度 |
|:-----|:----------------|:----:|
| `jersey_number_detection.pt` | PyTorch → TFLite 转换 | 高 |
| `team_classification.pt` | 同上 | 高 |
| `pitch_detection.pt` | 同上 | 高 |
| `football_pitch_keypoints.pt` | 同上 | 高 |

**建议**：Phase 3-4 再考虑。初期 AI 对话的图片识别走 `jersey_reader.py` Python 服务（HTTP 桥接或 ProcessBuilder）。

## 🎯 Tab C 已实现的架构特性（v4.1 新增）

### C-7. 数据层共享复用

| 数据类 | 源文件 | 用途 | 跨 Tab 复用 |
|:-------|:-------|:-----|:------------|
| `StadiumData` | `data/StadiumData.kt` | 场馆查找（名/城市/容量） | Tab C/D |
| `TrophyData` | `data/TrophyData.kt` | 球员生涯荣誉查询 | Tab B/D |
| `PlayerDatabase.getTeamRoster()` | `data/PlayerDatabase.kt` | 球队阵容文本摘要 | Tab A/C/D |

### C-8. API 优先架构

```
loadFinishedData()  模式: 本地兜底 → 渲染 → 异步 API 升级 → 替换渲染

API 三路并行（全部失败不影响，本地数据兜底）:
├── loadApiLineups()   ← api-sports /fixtures/lineups
├── loadApiEvents()    ← api-sports /fixtures/events
├── loadApiStats()     ← api-sports /fixtures/statistics
├── loadApiH2H()       ← api-sports /fixtures/headtohead
└── loadApiBestPlayer()← BDL /match_best_players
```

### C-9. 关键实现细节

| 功能 | 实现文件 | 关键代码 |
|:-----|:---------|:---------|
| ViewModel+搜索+骨架屏 | `ScheduleViewModel.kt`, `ScheduleFragment.kt` | LiveData 三层状态管理 |
| API历史交锋 | `MatchDetailActivity.apiSportsTeamId()` | 48 队 ID 映射表 |
| 统计字段归一化 | `MatchDetailActivity.normalizeStatKey()` | 20+ 字段映射 |
| Any→Int 转换 | `MatchDetailActivity.statToInt()` | 兼容 String/Number/Double |
| 最佳球员中文名 | `MatchDetailActivity.findChineseName()` | 遍历 players_2026.json |

---

## 写在最后

这份 **v4.2** 版本反映了 Tab C 和 Tab B 的全面完成。现在 Tab C 是项目中完成度最高的模块（12项全部实现），Tab B 已完成全部 11 类查询功能（6/7端点覆盖 + 4项Bonus）。

**当前各 Tab 完成度**：

| Tab | 状态 | 说明 |
|:----|:----:|:------|
| Tab A 实时赛况 | ✅ v3.0 已完成 | 38/38端点，v3.0多场直播+API时钟+双队事件+BDL真实阵容 |
| Tab B AI对话 | ✅ 已完成 | 11类查询，6数据源注入，DeepSeek正常 |
| Tab C 赛程查询 | ✅ 已完成 | 12项全部实现，API优先架构 |
| Tab D 比赛数据 | ✅ 已完成 | 积分榜/球员榜/球队网格/淘汰赛/球员详情

**后续重点**：Tab D（比赛数据）的 API 优先改造，可复用 Tab B 构建的 `StadiumData`（场馆）和 `TrophyData`（荣誉），以及 `PlayerDatabase.getTeamRoster()`（阵容）。
