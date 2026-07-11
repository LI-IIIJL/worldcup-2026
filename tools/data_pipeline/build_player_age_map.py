"""
从 MachineLearning_Module/data/apisports/{team}.json 提取球员年龄/身高/体重
生成 MainApp/assets/player_age_map.json

输出格式:
{
  "Mostafa Shobeir": { "age": 25, "height_cm": 185, "weight_kg": 78 },
  "Lionel Messi":    { "age": 38, "height_cm": 170, "weight_kg": 72 },
  ...
}
"""

import json
import os
import unicodedata

API_DIR = r"D:\WorldCupScanning\MachineLearning_Module\data\apisports"
PLAYERS_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\players_2026.json"
OUTPUT_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\player_age_map.json"

TEAM_NAME_MAP = {
    "cape_verde_islands": "Cape Verde", "curaçao": "Curacao",
    "czech": "Czechia", "türkiye": "Turkey", "usa": "USA",
    "bosnia": "Bosnia and Herzegovina", "ivory_coast": "Ivory Coast",
    "new_zealand": "New Zealand", "south_africa": "South Africa",
    "south_korea": "South Korea", "saudi_arabia": "Saudi Arabia",
}


def norm(s):
    s = unicodedata.normalize("NFKD", s)
    s = s.encode("ASCII", "ignore").decode("ascii")
    return s.lower().strip()


def parse_abbrev(short_name):
    """'M. Maignan' → ('m', 'maignan')"""
    sn = short_name.replace(".", "").lower()
    parts = norm(sn).split()
    if len(parts) >= 2 and len(parts[0]) == 1:
        return parts[0][0], parts[-1]
    return None, None


# Step 1: Load apisports data
print("Loading apisports data...")
api_players = {}  # (fi, ln) -> {age, height, weight, name}

for fname in os.listdir(API_DIR):
    if not fname.endswith(".json") or fname in ("team_index.json", "ar_live_data.json") or fname.startswith("lineup_"):
        continue
    with open(os.path.join(API_DIR, fname), "r", encoding="utf-8") as f:
        data = json.load(f)
    for p in data.get("players", []):
        pname = p.get("name", "")
        fi, ln = parse_abbrev(pname)
        if fi and ln:
            key = (fi, ln)
            if key not in api_players:
                api_players[key] = {
                    "age": p.get("age"),
                    "height": None,  # height not in basic apisports data
                    "weight": None,
                    "api_name": pname,
                }

print(f"  {len(api_players)} players in apisports data")

# Step 2: Match with players_2026.json
print("\nMatching with players_2026.json...")
with open(PLAYERS_PATH, "r", encoding="utf-8") as f:
    pd = json.load(f)

age_map = {}
matched = 0

for team in pd["teams"]:
    for p in team.get("players", []):
        name = p.get("name", "")
        if not name:
            continue
        parts = name.split()
        if len(parts) < 2:
            continue
        fi = parts[0][0].lower()
        ln = norm(parts[-1])
        key = (fi, ln)

        if key in api_players:
            api_info = api_players[key]
            entry = {"age": api_info["age"]}
            if api_info["height"]:
                entry["height_cm"] = api_info["height"]
            if api_info["weight"]:
                entry["weight_kg"] = api_info["weight"]
            age_map[name] = entry
            matched += 1

print(f"  Matched: {matched}/{sum(len(t.get('players',[])) for t in pd['teams'])} players")

# Step 3: Write output
print(f"\nWriting {OUTPUT_PATH}...")
with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
    json.dump(age_map, f, ensure_ascii=False, indent=2)
print(f"  ✅ Done! {len(age_map)} entries")
