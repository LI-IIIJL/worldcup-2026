"""
从 api-sports 拉取所有球队的赛程，构建 football-data match ID → api-sports fixture ID 映射
"""
import json, os, time, re, urllib.request

API_KEY = "a1171ce3f1e015c2deb20a3292be9a40"
API_SPORTS_DIR = "D:/WorldCupScanning/MachineLearning_Module/data/apisports"
OUTPUT = "D:/WorldCupScanning/outputs/fixture_id_map.json"
MATCHES_FILE = "D:/WorldCupScanning/MainApp/app/src/main/assets/matches.json"

# 队名标准化映射
NAME_NORMALIZE = {
    "czech republic": "czechia",
    "turkey": "türkiye",
    "united states": "usa",
    "bosnia and herzegovina": "bosnia & herzegovina",
    "cape verde": "cape verde islands",
    "curaçao": "curacao",
    "ivory coast": "côte d'ivoire",
    "democratic republic of the congo": "congo dr",
}

def normalize_name(name):
    n = name.lower().strip()
    return NAME_NORMALIZE.get(n, n)

# 1. 读取所有球队的 api-sports team ID
team_ids = {}
for f in sorted(os.listdir(API_SPORTS_DIR)):
    if not f.endswith(".json") or f in ("team_index.json","ar_live_data.json") or f.startswith("lineup_"):
        continue
    path = os.path.join(API_SPORTS_DIR, f)
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        team = data.get("team", {})
        tid = team.get("id")
        tname = team.get("name", "").lower()
        if tid and tname:
            team_ids[tname] = tid
    except:
        pass

# 补充第48队 DR Congo（不在apisports缓存中，单独查）
print(f"找到 {len(team_ids)} 支球队的 api-sports ID")

# 2. 读取 matches.json
with open(MATCHES_FILE, encoding="utf-8") as f:
    matches_data = json.load(f)
fixtures_md = matches_data if isinstance(matches_data, list) else matches_data.get("matches", [])
print(f"matches.json 中有 {len(fixtures_md)} 场比赛")

fixture_map = {}
all_api_fixtures = {}

# 3. 为每支球队查 fixtures
for i, (tname_lower, tid) in enumerate(team_ids.items()):
    url = f"https://v3.football.api-sports.io/fixtures?team={tid}&season=2026&league=1"
    req = urllib.request.Request(url)
    req.add_header("x-apisports-key", API_KEY)
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        data = json.loads(resp.read())
        fixtures_list = data.get("response", [])
        for fx in fixtures_list:
            f_id = fx["fixture"]["id"]
            f_date = fx["fixture"]["date"][:10]
            home = fx["teams"]["home"]["name"]
            away = fx["teams"]["away"]["name"]
            home_norm = normalize_name(home)
            away_norm = normalize_name(away)
            key = f"{home_norm} vs {away_norm}"
            if key not in all_api_fixtures:
                all_api_fixtures[key] = {"api_id": f_id, "date": f_date, "home": home, "away": away}
        print(f"  [{i+1}/{len(team_ids)}] {tname_lower} (id={tid})", end="")
        # 只显示涉及世界杯比赛的
        wc_matches = [fx for fx in fixtures_list if fx.get("league",{}).get("id") == 1]
        print(f" → {len(wc_matches)} 场世界杯比赛")
    except Exception as e:
        print(f"  [{i+1}/{len(team_ids)}] {tname_lower} (id={tid}) → 失败: {e}")
    time.sleep(0.35)

# 4. 针对未匹配的比赛中的球队，额外查询
unmatched_teams = set()
for m in fixtures_md:
    fd_id = str(m.get("id", ""))
    home = m.get("homeTeam", {}).get("name", "") if isinstance(m.get("homeTeam"), dict) else m.get("homeTeam", "")
    away = m.get("awayTeam", {}).get("name", "") if isinstance(m.get("awayTeam"), dict) else m.get("awayTeam", "")
    if "TBD" in home or "TBD" in away:
        continue
    # 看看能否被匹配
    home_norm = normalize_name(home)
    away_norm = normalize_name(away)
    key = f"{home_norm} vs {away_norm}"
    rev_key = f"{away_norm} vs {home_norm}"
    if key not in all_api_fixtures and rev_key not in all_api_fixtures:
        unmatched_teams.add(home)
        unmatched_teams.add(away)

print(f"\n未匹配球队: {unmatched_teams}")

# 5. 匹配
matched = 0
for m in fixtures_md:
    fd_id = str(m.get("id", ""))
    home = m.get("homeTeam", {}).get("name", "") if isinstance(m.get("homeTeam"), dict) else m.get("homeTeam", "")
    away = m.get("awayTeam", {}).get("name", "") if isinstance(m.get("awayTeam"), dict) else m.get("awayTeam", "")
    if not home or not away or "TBD" in home or "TBD" in away:
        continue
    home_norm = normalize_name(home)
    away_norm = normalize_name(away)
    key = f"{home_norm} vs {away_norm}"
    rev_key = f"{away_norm} vs {home_norm}"
    
    if key in all_api_fixtures:
        fixture_map[fd_id] = all_api_fixtures[key]["api_id"]
        matched += 1
    elif rev_key in all_api_fixtures:
        fixture_map[fd_id] = all_api_fixtures[rev_key]["api_id"]
        matched += 1

result = {
    "mapping": fixture_map,
    "total_football_data": len(fixtures_md),
    "matched": matched,
    "unmatched": len(fixtures_md) - matched,
    "note": "key = football-data match ID, value = api-sports fixture ID. TBD matches (knockout) cannot be mapped until opponents are known."
}

with open(OUTPUT, "w", encoding="utf-8") as f:
    json.dump(result, f, indent=2, ensure_ascii=False)

print(f"\n匹配结果: {matched}/{len(fixtures_md)} 场比赛匹配成功")
unmatched_list = []
for m in fixtures_md:
    fd_id = str(m.get("id", ""))
    if fd_id not in fixture_map:
        home = m.get("homeTeam", {}).get("name", "") if isinstance(m.get("homeTeam"), dict) else m.get("homeTeam", "")
        away = m.get("awayTeam", {}).get("name", "") if isinstance(m.get("awayTeam"), dict) else m.get("awayTeam", "")
        unmatched_list.append(f"{fd_id}: {home} vs {away}")
        print(f"  ⚠️ {fd_id}: {home} vs {away}")
print(f"未匹配详情: {len(unmatched_list)} 场")
# TBD数量
tbd_count = sum(1 for u in unmatched_list if "TBD" in u)
print(f"  其中 TBD（淘汰赛阶段）: {tbd_count} 场")
print(f"  其余: {len(unmatched_list) - tbd_count} 场（需额外处理）")
