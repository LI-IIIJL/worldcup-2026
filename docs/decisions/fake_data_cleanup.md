# Tab D Phase B — 球员详情增强 + 积分榜Fallback链

> **日期**: 2026-06-24  
> **作者**: WorkBuddy (Tab D 开发)  
> **状态**: 编译通过

---

## 改动总览

### 1. PlayerDetailActivity.kt — 洗掉全部假数据 + 接入真实数据源

| 区域 | 之前（假数据） | 之后（真实数据） |
|:-----|:---------------|:-----------------|
| **累计统计** | `Math.random() * 2.0` 假评分（line 133） | football-data `/persons/{id}/matches` API 优先，本地降级 |
| **荣誉墙** | 俱乐部名判断："若皇马/巴萨/曼城 → 冠军"（line 542-548） | `trophies_cache.json`（780+球员真实生涯荣誉） |
| **单场评分** | `6.5 + Math.random() * 1.5`（line 372） | 显示"赛后统计详情"文本，移除假评分 |
| **雷达图维度** | "射门"=进球×20, "速度"=sin(进球)（line 437-441） | 真实数据: 进球/助攻/出场/纪律/耐力 |
| **本地评分** | `6.0 + Math.random() * 2.0`（line 133） | 评分设为 0.0，显示"赛后更新" |

**新增方法**：
- `fetchPlayerCumulativeStats()` — `suspend fun` 调 football-data API
- `calculateLocalStats()` — 本地降级（无 fake rating）
- `loadRealHonors()` — 从 trophies_cache.json 加载真荣誉
- `loadPersonIdMap()` — 加载 person_id_map 用于 API 调用

### 2. DataRepository.kt — 积分榜三源降级

| Tier | API | 来源 | 状态 |
|:----:|:----|:-----|:----:|
| 1 | football-data `/standings` | FREE 10次/分 | ✅ 免费优先 |
| 2 | BDL `/group_standings` | GOAT $39.99/月 | ✅ 已付费 |
| 3 | 本地 `matches.json` 计算 | 离线 | ⬇️ 最终兜底 |

### 3. ApiInterfaces.kt — 修复 BdlTeamBrief 重复声明

合并了 `BdlTeamBrief` 的两份声明（字段不可空 vs 可空），统一为可空版本。

---

## API 调用链（球员详情页）

```
PlayerDetailActivity.loadPlayerData(playerName)
  ├→ players_2026.json → 基本信息（本地）
  ├→ renderPlayerHeader → 照片、号码、位置
  ├→ calculatePlayerStats(playerName, callback)
  │    └→ fetchPlayerCumulativeStats()  [suspend]
  │         └→ LiveApiClient.footballData.getPersonMatches(personId)
  │              └→ aggregations { goals, assists, matchesOnPitch, minutesPlayed, cards }
  │    ⬇️ 失败 → calculateLocalStats()  [local, no fake rating]
  ├→ renderCareerStats → 显示 API 或本地数据，评分显示"赛后更新"
  ├→ renderMatchStats → 本地数据
  ├→ renderRadarChart → 基于真实统计数据（有数据才画）
  └→ renderHonors → loadRealHonors()
       └→ trophies_cache.json → 按 api_sports_id 匹配荣誉
            └→ 780+球员真实荣誉，无匹配时隐藏荣誉区
```

## 已移除的假数据

| 位置 | 代码 | 替换 |
|:-----|:-----|:-----|
| calculatePlayerStats:133 | `totalRating += 6.0 + Math.random() * 2.0` | 无评分数据，显示"赛后更新" |
| renderSingleMatch:372 | `6.5 + Math.random() * 1.5` | "📊 赛后统计详情" |
| renderRadarChart:437 | `kotlin.math.sin(stats.goals * 0.7)` | 真实统计数据 |
| renderHonors:542-548 | if(Real/Barcelona/City/Bayern) → 假冠军 | trophies_cache.json 真数据 |
