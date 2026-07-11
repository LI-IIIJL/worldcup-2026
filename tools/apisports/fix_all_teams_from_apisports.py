"""
用 MachineLearning_Module/data/apisports/{team}.json 中的权威 api-sports 数据
校正 players_2026.json 所有球队的 api_sports_id 和 photo_url。

匹配策略: 缩写展开 + 队伍上下文
  apisports: "M. Maignan" → fi='m', ln='maignan'
  players_2026: "Mike Maignan" → fi='m', ln='maignan' → MATCH!
"""

import json
import os
import unicodedata

PLAYERS_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\players_2026.json"
PHOTO_LOOKUP_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\photo_lookup.json"
API_DATA_DIR = r"D:\WorldCupScanning\MachineLearning_Module\data\apisports"

# team filename → players_2026 team name mapping
TEAM_NAME_MAP = {
    "cape_verde_islands": "Cape Verde",
    "curaçao": "Curacao",
    "czech": "Czechia",
    "türkiye": "Turkey",
    "usa": "USA",
    "bosnia": "Bosnia and Herzegovina",
    "ivory_coast": "Ivory Coast",
    "new_zealand": "New Zealand",
    "south_africa": "South Africa",
    "south_korea": "South Korea",
    "saudi_arabia": "Saudi Arabia",
}


def norm(s):
    """NFKD + ascii + lowercase"""
    s = unicodedata.normalize("NFKD", s)
    s = s.encode("ASCII", "ignore").decode("ascii")
    return s.lower().strip()


# ====================================================================
# Step 1: Load data
# ====================================================================
print("=" * 60)
print("加载 players_2026.json ...")

with open(PLAYERS_PATH, "r", encoding="utf-8") as f:
    players_data = json.load(f)

# Build team name index
team_index = {}
for team in players_data["teams"]:
    tname = team.get("name", "")
    team_index[tname] = team


# ====================================================================
# Step 2: Process each apisports file
# ====================================================================
print(f"\n扫描 {API_DATA_DIR}/*.json ...")
api_files = sorted(f for f in os.listdir(API_DATA_DIR)
                   if f.endswith(".json")
                   and f not in ("team_index.json", "ar_live_data.json")
                   and not f.startswith("lineup_"))

total_corrected = 0
total_skipped = 0

for api_file in api_files:
    team_slug = api_file.replace(".json", "")
    team_name = TEAM_NAME_MAP.get(team_slug, team_slug.title())

    # Load apisports data
    with open(os.path.join(API_DATA_DIR, api_file), "r", encoding="utf-8") as f:
        api_data = json.load(f)

    api_players = api_data.get("players", [])
    if not api_players:
        continue

    # Find matching team in players_2026.json
    target_team = None
    for tname, team in team_index.items():
        if norm(tname) == norm(team_name):
            target_team = team
            break
        # Also try containing match
        if norm(team_name) in norm(tname) or norm(tname) in norm(team_name):
            target_team = team
            break

    if target_team is None:
        print(f"  ⚠️  未找到队伍: {team_name} ({api_file})")
        total_skipped += 1
        continue

    # Build short-name → id map
    short_to_id = {}
    for p in api_players:
        pname = p.get("name", "")
        if pname:
            short_to_id[pname] = p["id"]

    # Match and correct each player
    team_corrected = 0
    team_no_match = 0
    for player in target_team["players"]:
        full_name = player.get("name", "")
        if not full_name:
            continue

        # Parse full name
        parts = full_name.split()
        if len(parts) < 2:
            continue

        fi = parts[0][0].lower()
        ln = norm(parts[-1])

        # Find matching short name in apisports data
        matched_id = None
        candidates = []
        for sname, sid in short_to_id.items():
            # Parse short name: "M. Maignan" → fi='m', ln='maignan'
            sname_nodot = sname.replace(".", "").lower()
            sparts = norm(sname_nodot).split()
            if len(sparts) >= 2:
                s_fi = sparts[0][0]
                s_ln = sparts[-1]
                if s_fi == fi and s_ln == ln:
                    candidates.append((sname, sid))

        if len(candidates) == 1:
            matched_id = candidates[0][1]
        elif len(candidates) > 1:
            # Multiple with same initials+lastname → use jersey number to disambiguate
            jersey = player.get("jerseyNumber")
            if jersey:
                for sname, sid in candidates:
                    api_player = next((p for p in api_players if p["name"] == sname), None)
                    if api_player and api_player.get("number") == jersey:
                        matched_id = sid
                        break
            if matched_id is None:
                # Still ambiguous, skip
                continue

        if matched_id is None:
            # Try direct full name match (for Kylian Mbappe style)
            matched_id = short_to_id.get(full_name)

        if matched_id is None:
            team_no_match += 1
            continue

        old_id = player.get("api_sports_id", 0)
        if old_id != matched_id:
            player["api_sports_id"] = matched_id
            player["photo_url"] = f"https://media.api-sports.io/football/players/{matched_id}.png"
            team_corrected += 1
            # print(f"  ✅ {full_name:25s} {old_id} → {matched_id} ({team_name})")

    if team_corrected > 0:
        print(f"  {team_name:20s} {team_corrected:3d} 处修正 ({team_no_match} 未匹配)")
    total_corrected += team_corrected

print(f"\n总计: {total_corrected} 处修正, {total_skipped} 个文件跳过")


# ====================================================================
# Step 3: Write players_2026.json
# ====================================================================
if total_corrected > 0:
    print(f"\n写出 players_2026.json ...")
    with open(PLAYERS_PATH, "w", encoding="utf-8") as f:
        json.dump(players_data, f, ensure_ascii=False, indent=2)
    print(f"  ✅ {PLAYERS_PATH}")


# ====================================================================
# Step 4: Update photo_lookup.json
# ====================================================================
print(f"\n更新 photo_lookup.json ...")
name_to_id = {}
for team in players_data["teams"]:
    for p in team.get("players", []):
        n = p.get("name", "")
        pid = p.get("api_sports_id")
        if n and pid:
            name_to_id[n] = pid

with open(PHOTO_LOOKUP_PATH, "r", encoding="utf-8") as f:
    pl_map = json.load(f)

pl_fixed = 0
lookup = pl_map["lookup"]
for name, pid in name_to_id.items():
    expected_url = f"https://media.api-sports.io/football/players/{pid}.png"
    if name in lookup:
        old_url = lookup[name]
        if old_url != expected_url:
            lookup[name] = expected_url
            pl_fixed += 1
            # print(f"  📸 {name:30s} → {pid}.png")

with open(PHOTO_LOOKUP_PATH, "w", encoding="utf-8") as f:
    json.dump(pl_map, f, ensure_ascii=False, indent=2)
print(f"  ✅ photo_lookup.json: {pl_fixed} 处更新")

print(f"\n{'='*60}")
print(f"全部完成！{total_corrected} 名球员已修正, {pl_fixed} 个照片 URL 已更新")
print("提示: 记得 assembleDebug 使改动生效。")
