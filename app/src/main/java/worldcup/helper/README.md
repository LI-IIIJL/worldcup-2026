# com.worldcup.scanner - 应用根包

## 包结构概览

```
com.worldcup.scanner/
├── MainActivity.kt       - 扫描入口，承载 ScanFragment
├── ui/                   - 界面层（3 个子模块）
│   ├── teamselect/       - [赛程模块] 赛程列表 + 球队选择
│   ├── scan/             - [扫描模块] CameraX 相机 + ML Kit OCR
│   └── player/           - [信息模块] 球员详情展示
├── data/                 - 数据层
│   ├── MatchData.kt      - 赛程数据模型（解析 matches.json）
│   ├── PlayerDatabase.kt - 本地球员库（解析 players_2026.json）
│   ├── FlagUtil.kt       - 国旗 CDN 工具
│   └── models/           - 数据模型类
└── network/              - 网络层（备用）
    └── BalldontlieClient.kt - BALLDONTLIE API 客户端
```

## 应用流程

```
TeamSelectActivity → MainActivity (ScanFragment) → PlayerDetailActivity
  (选比赛+球队)       (扫描号码+查询)              (展示球员信息)
```
