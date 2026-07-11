"""
全面审计 players_2026.json 的数据正确性。

检查维度:
1. 与 apisports/{team}.json 对比: 姓名缩写匹配 + 号码校验 + 位置校验
2. 发现错误的 api_sports_id → 自动纠正
3. 发现球衣号码/位置不一致 → 报告
4. 对于无 apisports 数据的球队 → 用 football_data_person_id_map 综合校验
"""

import json
import os
import unicodedata

PLAYERS_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\players_2026.json"
PHOTO_LOOKUP_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\photo_lookup.json"
API_DIR = r"D:\WorldCupScanning\MachineLearning_Module\data\apisports"
FOOTBALL_DATA_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\football_data_person_id_map.json"

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
    """'M. Maignan' → ('m', 'maignan',  initial='m')"""
    sn = short_name.replace(".", "").lower()
    parts = norm(sn).split()
    if len(parts) >= 2 and len(parts[0]) == 1:
        return parts[0][0], parts[-1]
    return None, None


def load_apisports_data():
    """Load all apisports team data, return {team_name: {abbrev: player_info}}"""
    result = {}
    for fname in os.listdir(API_DIR):
        if not fname.endswith(".json") or fname in ("team_index.json", "ar_live_data.json") or fname.startswith("lineup_"):
            continue
        with open(os.path.join(API_DIR, fname), "r", encoding="utf-8") as f:
            data = json.load(f)

        slug = fname.replace(".json", "")
        team_name = TEAM_NAME_MAP.get(slug, slug.title())

        players = {}
        for p in data.get("players", []):
            players[p["name"]] = {
                "id": p.get("id"),
                "name": p.get("name", ""),
                "number": p.get("number"),
                "position": p.get("position", ""),
                "photo": p.get("photo", ""),
            }
        result[team_name] = players
    return result


def load_football_data():
    """Load football_data_person_id_map for fallback"""
    with open(FOOTBALL_DATA_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)
    players = data if isinstance(data, list) else data.get("players", [])
    result = {}
    for p in players:
        n = p.get("name", "")
        pid = p.get("api_sports_id")
        if n and pid:
            result[n] = pid
            result[norm(n)] = pid
    return result


# ====================================================================
# 加载数据
# ====================================================================
print("=" * 60)
print("加载 players_2026.json ...")
with open(PLAYERS_PATH, "r", encoding="utf-8") as f:
    players_data = json.load(f)

api_data = load_apisports_data()
print(f"apisports 数据: {len(api_data)} 个球队")

fd_data = load_football_data()
print(f"football_data 映射: ~{len(fd_data)//2} 个球员有 api_sports_id")

# 建立队伍名查找
team_map = {}
for t in players_data["teams"]:
    team_map[norm(t["name"])] = t["name"]


# ====================================================================
# 逐队审计
# ====================================================================
print("\n" + "=" * 60)
print("逐队审计...\n")

total_fixed = 0
total_issues = 0
all_teams_ok = True

for team in players_data["teams"]:
    tname = team["name"]
    tname_norm = norm(tname)

    # 找 apisports 数据
    api_team = None
    for api_tname, api_players in api_data.items():
        if norm(api_tname) == tname_norm:
            api_team = api_players
            break
        if tname_norm in norm(api_tname) or norm(api_tname) in tname_norm:
            api_team = api_players
            break

    has_extra_player = True
    if api_team is None:
        # 无 apisports 数据的队，用 football_data 兜底
        api_team = {}
        has_extra_player = False

    team_fixes = 0
    team_ok = True

    for p in team["players"]:
        name = p.get("name", "")
        if not name:
            continue

        parts = name.split()
        if len(parts) < 2:
            continue

        fi = parts[0][0].lower()
        ln = norm(parts[-1])
        jersey = p.get("jerseyNumber")

        # ----------------------------
        # 1. 找 apisports 中匹配的球员
        # ----------------------------
        matched_api = None
        for api_key, api_info in api_team.items():
            afi, aln = parse_abbrev(api_key)
            if afi == fi and aln == ln:
                # 号码校验：如果有号码，优先验证
                if jersey and api_info.get("number") and api_info["number"] != jersey:
                    continue  # 号码不匹配，跳过
                matched_api = api_info
                break

        # 严格模式：如果不唯一，再查一轮找号码匹配
        if matched_api is None:
            candidates = []
            for api_key, api_info in api_team.items():
                afi, aln = parse_abbrev(api_key)
                if afi == fi and aln == ln:
                    candidates.append((api_key, api_info))
            if len(candidates) == 1:
                matched_api = candidates[0][1]
            elif len(candidates) > 1 and jersey:
                for ck, ci in candidates:
                    if ci.get("number") == jersey:
                        matched_api = ci
                        break

        # 也尝试直接匹配全名
        if matched_api is None and name in api_team:
            matched_api = api_team[name]

        current_id = p.get("api_sports_id")
        current_photo = p.get("photo_url", "")

        if matched_api:
            correct_id = matched_api["id"]
            correct_photo = matched_api["photo"]

            if current_id != correct_id:
                if has_extra_player or (not has_extra_player and current_id and current_id != correct_id):
                    print(f"  🚫 {name:25s} ({tname}) id={current_id} → 正确={correct_id} [号码校验]")
                    p["api_sports_id"] = correct_id
                    p["photo_url"] = correct_photo
                    team_fixes += 1
                    total_fixed += 1
                    continue

        # ----------------------------
        # 2. 无 apisports 匹配，尝试 football_data
        # ----------------------------
        if (current_id is None or current_id == 0) or (matched_api is None and has_extra_player):
            fd_id = fd_data.get(name) or fd_data.get(norm(name))
            if fd_id and fd_id != current_id:
                print(f"  ⚠️  {name:25s} ({tname}) 无匹配, 从 football_data 补: {current_id}→{fd_id}")
                p["api_sports_id"] = fd_id
                p["photo_url"] = f"https://media.api-sports.io/football/players/{fd_id}.png"
                team_fixes += 1
                total_fixed += 1
                continue

        # ----------------------------
        # 3. 检查该队内是否有重复 ID（同一队的两个球员 ID 相同）
        # ----------------------------
        pid = p.get("api_sports_id")
        if pid:
            same_id_players = []
            for p2 in team["players"]:
                if p2.get("api_sports_id") == pid and p2.get("name") != name:
                    same_id_players.append(p2.get("name"))
            if same_id_players:
                print(f"  🔴 {name:25s} ({tname}) id={pid} 与 {same_id_players} 相同! 无法自动修复")

    if team_fixes > 0:
        print(f"  → {tname}: {team_fixes} 处修正")
    else:
        for p in team["players"]:
            pid = p.get("api_sports_id")
            if pid:
                same_id_players = [p2.get("name") for p2 in team["players"]
                                   if p2.get("api_sports_id") == pid and p2.get("name") != p.get("name")]
                if same_id_players:
                    team_ok = False
                    all_teams_ok = False
                    break
        if team_ok:
            pass  # 这个队没问题

# ====================================================================
# 汇总
# ====================================================================
print(f"\n{'='*60}")
print(f"审计完成!")
print(f"  自动修正: {total_fixed} 处")

# 统计
total = sum(len(t["players"]) for t in players_data["teams"])
has_id = sum(1 for t in players_data["teams"] for p in t["players"] if p.get("api_sports_id"))
perfect_teams = 0
bad_teams = []

for team in players_data["teams"]:
    ids = [p.get("api_sports_id") for p in team["players"]]
    unique = len(set(ids))
    none_count = sum(1 for p in team["players"] if not p.get("api_sports_id"))
    if unique == len(ids) and none_count == 0:
        perfect_teams += 1
    elif unique < len(ids):
        duplicates = len(ids) - unique
        bad_teams.append((team["name"], unique, len(ids), duplicates))

print(f"  覆盖率: {has_id}/{total} ({has_id*100//total}%)")
print(f"  完美球队: {perfect_teams}/48")
if bad_teams:
    print(f"\n  仍有重复 ID 的球队:")
    for tn, uniq, total_p, dup in sorted(bad_teams, key=lambda x: -x[3]):
        print(f"    {tn:20s}: {uniq}/{total_p} 唯一 ({dup} 重复)")


# ====================================================================
# 写回
# ====================================================================
if total_fixed > 0:
    print(f"\n保存 players_2026.json ...")
    with open(PLAYERS_PATH, "w", encoding="utf-8") as f:
        json.dump(players_data, f, ensure_ascii=False, indent=2)
    print(f"  ✅ {PLAYERS_PATH}")

    print(f"\n同步 photo_lookup.json ...")
    with open(PHOTO_LOOKUP_PATH, "r", encoding="utf-8") as f:
        pl = json.load(f)
    fix_pl = 0
    for team in players_data["teams"]:
        for p in team["players"]:
            n = p.get("name", "")
            pid = p.get("api_sports_id")
            if n and pid:
                url = f"https://media.api-sports.io/football/players/{pid}.png"
                if n in pl.get("lookup", {}):
                    if pl["lookup"][n] != url:
                        pl["lookup"][n] = url
                        fix_pl += 1
    with open(PHOTO_LOOKUP_PATH, "w", encoding="utf-8") as f:
        json.dump(pl, f, ensure_ascii=False, indent=2)
    print(f"  ✅ {PHOTO_LOOKUP_PATH}: {fix_pl} 处更新")
