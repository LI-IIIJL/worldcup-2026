# data/ - 数据层 (Data Layer)

## 功能
- 从 `assets/` 目录加载本地 JSON 数据
- 提供赛程、球员阵容的查询接口
- 不依赖任何网络 API（离线可用）

## 文件说明

| 文件 | 功能 | 数据来源 |
|------|------|---------|
| `MatchData.kt` | 赛程数据模型 + 状态判断（LIVE/TODAY/UPCOMING/FINISHED） | `assets/matches.json` |
| `PlayerDatabase.kt` | 球员数据库，按号码+球队查询 | `assets/players_2026.json` |
| `FlagUtil.kt` | 国旗 Emoji 备用工具 | 无（纯计算） |
| `CircleFlagLoader.kt` | 从 assets/flags/ 加载圆形 SVG 国旗（circle-flags 风格） | HatScripts/circle-flags |
| `models/ApiModels.kt` | 数据模型类（Team, Player, PlayerInfo, RosterEntry 等） | 无（纯定义） |
