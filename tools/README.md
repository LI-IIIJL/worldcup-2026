# 🛠️ Python 数据管线工具集

这些 Python 脚本是世界杯项目的数据基础设施——负责数据采集、清洗、建模、识别。

## 目录结构

```
tools/
├── prediction/         比分预测模拟
├── face_recognition/   人脸识别管线
├── data_pipeline/      数据采集 + ID 映射
├── apisports/          api-sports.io 数据采集
└── live_data/          实时数据服务
```

---

### 📊 prediction/ — 比分预测

| 脚本 | 功能 |
|------|------|
| `monte_carlo.py` | 蒙特卡洛模拟引擎：基于 ELO + 泊松分布模拟 10,000 场比赛 |
| `predictor.py` | 预测器，整合模拟结果 |
| `mc_to_prediction_json.py` | 将模拟结果序列化为 JSON，供给 Android App 使用 |
| `merge_squads.py` | 阵容数据合并工具 |

### 🧑‍🤝‍🧑 face_recognition/ — 人脸识别管线

| 脚本 | 功能 |
|------|------|
| `face_db_builder.py` | 从 API 下载球员面部图片，构建面部数据库 |
| `face_extractor.py` | 使用 InsightFace (buffalo_l) 提取 512 维面部 embedding + 构建 Faiss 索引 |
| `face_matcher.py` | 给定一张面部照片，在 Faiss 索引中搜索最相似的球员 |
| `face_e2e_test.py` | 端到端测试：下载 → 提取 → 匹配完整流程 |

### 🔗 data_pipeline/ — 数据采集 + ID 映射

| 脚本 | 功能 |
|------|------|
| `build_fixture_map.py` | 构建足球数据 API 与本地 JSON 的对阵 ID 映射表 |
| `build_personid_map.py` | 构建 football-data.org personId → api-sports ID 的跨源映射 |
| `build_photo_lookup.py` | 构建球员名 → 照片 URL 的查询索引 |
| `build_player_age_map.py` | 构建球员年龄数据映射 |
| `cache_new_data.py` | 自动缓存 API 数据到本地 |
| `pull_all_trophies.py` | 从 TheSportsDB 采集球员荣誉数据 |
| `audit_and_fix_players.py` | 审计并修复球员数据质量问题 |
| `validate_api_sports_ids.py` | 验证 api-sports ID 的有效性 |

### 🌐 apisports/ — API 数据采集

| 脚本 | 功能 |
|------|------|
| `apisports_downloader.py` | 从 api-sports.io 下载 48 队全部球员照片 |
| `fetch_all_photos.py` | 全量照片爬取 |
| `fix_all_teams_from_apisports.py` | 基于 api-sports 数据修复球队信息 |

### ⚡ live_data/ — 实时数据服务

| 脚本 | 功能 |
|------|------|
| `ar_data_service.py` | 一键拉取完整比赛数据（赛程、阵容、统计、事件） |

---

## 使用方式

大多数脚本需要配置 API Key（见脚本开头的配置区）。推荐在 Python 3.11+ 虚拟环境中运行：

```bash
pip install requests opencv-python insightface faiss-cpu numpy
python tools/prediction/monte_carlo.py
```
