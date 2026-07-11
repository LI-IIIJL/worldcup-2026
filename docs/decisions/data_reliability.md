# 球员卡数据管线可靠性报告

**日期**: 2026-06-26 | **状态**: 已修复关键问题，仍需持续改进

---

## 一、数据管线总览

```
用户打开球员卡 → PlayerDetailActivity
    │
    ├── [0] 本地 JSON (players_2026.json)      → 基础信息 ✅ 可靠
    ├── [1] football_data_person_id_map        → ID 映射 ⚠️ 已修复
    ├── [2] football-data API                  → 世界杯累计统计 ⚠️
    ├── [3] api-sports API                     → 赛季 18 项统计 ⚠️
    ├── [4] photo_lookup.json                  → 照片 URL 映射 ⚠️
    └── [5] trophies_cache.json                → 生涯荣誉 ✅ 可靠
```

## 二、各管线可靠性评估

### [0] 本地 JSON — 基础信息 ✅ 可靠

| 字段 | 可靠性 | 覆盖率 |
|------|:------:|:------:|
| name (英文名) | ✅ 100% | 1246/1246 |
| nameCn (中文名) | ✅ 100% | 1246/1246 |
| jerseyNumber | ✅ 100% | 1246/1246 |
| position | ✅ 100% | 1246/1246 |
| club | ✅ 100% | 1246/1246 |
| injured | ✅ 100% | 1246/1246 |
| marketValueMil | ⚠️ 部分为 null | ~80% |

**风险**: 无。纯本地数据，不依赖网络。

---

### [1] ID 映射 — football_data_person_id_map ⚠️ 已修复 (2026-06-26)

| 问题 | 修复前 | 修复后 |
|:-----|:------:|:------:|
| 名匹配失败无 person_id | **160 人** (12.8%) | **0 人** |
| 修复方法 | — | 增加 api_sports_id 反查 |

**旧代码**: 只通过 name 匹配 `football_data_person_id_map`。当两个数据源的名字不同时（如 "Mostafa Shobeir" vs "Oufa Shobeir"），person_id 为 null → 不能调 football-data API → 累计统计全为 0。

**修复**: 当名匹配失败时，用 `api_sports_id`（从 photo_url 提取）在 `idMapByApiSportsId` 中反查，找到正确的 `person_id`。

**验证**:
```python
# Mostafa Shobeir 修复验证
players_2026: "Mostafa Shobeir", api_sports_id=269174
football_data: "Oufa Shobeir", person_id=251169, api_sports_id=269174
旧代码: "mostafa shobeir" ↔ "oufa shobeir" → 不匹配 → person_id=null
新代码: api_sports_id=269174 → idMapByApiSportsId → person_id=251169 ✅
```

---

### [2] football-data API — 世界杯累计统计 ⚠️

| 条件 | 结果 |
|:-----|:-----|
| person_id 正确 + 网络正常 | ✅ 返回正确累计数据 |
| person_id 正确 + 网络离线 | ⚠️ 显示 0（静默降级，不弹 Toast） |
| person_id 为 null | ⚠️ API 不调 → 显示 0 |
| **API 参数** | **备注** |
| `competitions=2000` | ✅ football-data 标准参数，可正确过滤世界杯比赛 |
| `dateFrom=2026-06-01` / `dateTo=2026-07-31` | ❓ 未在文档中标示，可能被忽略 |

**已知限界**:
- 该 API 返回的是 football-data 官网的累计统计
- 同一个人可能在不同比赛中有不同 person_id
- 服务端统计可能有延迟（赛后 30 分钟内更新）

**建议**:
- 移除未确认的 `dateFrom`/`dateTo` 参数（保留 `competitions=2000`）
- 如数据不更新，等待 football-data 赛后刷新（通常 5-30 分钟）

---

### [3] api-sports API — 赛季 18 项统计 ⚠️

| 条件 | 结果 |
|:-----|:-----|
| 球员有出场 + API 正常 | ✅ 返回评分/射门/传球等 |
| 球员 0 出场或队未比赛 | ⚠️ statistics 为空 → 返回 null → 评分显示"—" |
| 网络离线 | ⚠️ 返回 null |
| 球员名匹配失败 | ❌ 已修复（新加缩写展开匹配） |

**已修复匹配问题**:
```
旧: "M. Maignan".contains("Mike Maignan") → false (因点号)
新: 缩写展开 "M. Maignan" → fi='m', ln='maignan'
    "Mike Maignan" → fi='m', ln='maignan' → MATCH ✅
```

**API 限制** (league=1 = 世界杯):
- 仅返回有出场记录的球员统计 → 0 出场球员无评分
- 评分字段 `games.rating` 是 String 类型，可能为 null

---

### [4] photo_lookup.json — 照片 URL ⚠️

| 覆盖率 | 状态 |
|:------:|:----:|
| 1217/1246 (97%) | ✅ 主要队伍都覆盖 |
| 29/1246 (3%) | ⚠️ DR Congo / Saudi Arabia 等无数据 |

**数据源可靠性排序**:
1. `apisports/{team}.json` (47 队) → ⭐⭐⭐⭐⭐
2. `player_photo_map.json` (1337 人) → ⭐⭐⭐⭐
3. `football_data_person_id_map` (1131 人) → ⭐⭐⭐

---

### [5] trophies_cache.json — 生涯荣誉 ✅ 可靠

全本地数据，不依赖网络。覆盖率约 780+ 球员。

---

## 三、已知限界（需告知用户）

| # | 限界 | 原因 | 能否修复 |
|---|:-----|:-----|:---------|
| 1 | 部分球员(如 Shobeir) 累计数据为 0 | 已修复 ID 映射，但 football-data 需有人建檔 | ✅ 已修 |
| 2 | 评分赛后 30 分钟才出 | api-sports 服务器端统计延迟 | ❌ API 限制 |
| 3 | 0 出场球员评分为空 | 无数据可统计 | ✅ 可显示"暂无" |
| 4 | 离线时 API 数据全为 0 | 网络不可用 | ⚠️ 可接受 |
| 5 | 部分亚洲/非洲球队无人建档 | football-data 覆盖不全 | ❌ 数据源限制 |

## 四、可增加的有趣数据（可行性评估）

| 数据 | 来源 | 可行性 | 可靠性 | 工作量 |
|:-----|:-----|:------:|:------:|:------:|
| **球员年龄/身高/体重** | `MachineLearning_Module/data/apisports/{team}.json` | ✅ 高 | ✅ 高 | 小 (解析 JSON) |
| **国籍国旗** | 球队 ISO 代码 → flag_cdn | ✅ 高 | ✅ 高 | 小 |
| **身价走势图** | players_2026.json (仅当前值) | ⚠️ 只有当前值 | ⚠️ 无历史 | 中 |
| **国家队出场/进球** | football-data persons/{id} | ❌ 需不同端点 | ⚠️ 需验证 | 中 |
| **赛季俱乐部统计** | api-sports players?team=X&league=XXX | ⚠️ 需指定非世界杯联赛 | ✅ 高 | 中 |
| **单场逐场数据** | BDL player_match_stats (赛后) | ⚠️ 赛后才有 | ✅ 高 | 中 |
| **球员风格对比图** | api-sports 18项 → 另一 Canvas | ✅ 高 | ✅ 高 | 中 |
| **热卖推荐(类似球员)** | api-sports 同位置筛选 | ✅ 高 | ⚠️ 算法质量 | 大 |
| **社交媒体提及热度** | 第三方 API | ❌ 无免费 API | ❌ | 大 |

**推荐优先添加**:
1. **球员年龄/身高/体重** — 数据已在 `apisports/{team}.json` 中，只需解析，可靠性高
2. **赛季俱乐部统计** — 新增 api-sports 调用，展示该球员在俱乐部的表现

---

## 五、关键代码位置

| 管线 | 文件 | 行号 |
|:-----|:-----|:----:|
| 基础信息加载 | `PlayerRepository.kt` | `ensurePlayersLoaded()` |
| ID 映射 | `PlayerRepository.kt` | `ensureIdMapLoaded()` |
| 照片查找 | `PlayerRepository.kt` | `ensurePhotoLookupLoaded()` |
| 世界杯累计 API | `PlayerRepository.kt` | `wcJob` (约 line 434) |
| 赛季统计 API | `PlayerRepository.kt` | `seasonJob` (约 line 448) |
| 缩写名匹配 (已修复) | `PlayerRepository.kt` | API 球员查找块 |
| api_sports_id 反查 (已修复) | `PlayerRepository.kt` | ID 映射块 |
