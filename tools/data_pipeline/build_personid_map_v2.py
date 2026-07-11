"""build_personid_map_v2.py - 改进版，使用模糊匹配补全未匹配球员"""

import json
import re
import os

os.chdir(r"D:\WorldCupScanning")

# ============ 1. 读取数据源 ============
with open(r"outputs\football_teams_raw.json", encoding="utf-8") as f:
    teams_data = json.load(f)

with open(r"MainApp\app\src\main\assets\players_2026.json", encoding="utf-8") as f:
    players_2026_raw = json.load(f)

with open(r"MainApp\app\src\main\assets\players.json", encoding="utf-8") as f:
    players_json_raw = json.load(f)

import glob
apisports_dir = r"MachineLearning_Module\data\apisports"
apisports_data = {}
for fpath in glob.glob(f"{apisports_dir}/*.json"):
    with open(fpath, encoding="utf-8") as f:
        data = json.load(f)
        team_name = data.get("team", {}).get("name", "").lower()
        if team_name:
            apisports_data[team_name] = data

# ============ 2. 队伍名映射（football-data → 本地名） ============
TEAM_NAME_MAP = {
    "bosnia-herzegovina": "bosnia",
    "congo dr": "dr congo",
    "cape verde islands": "cape verde",
    "curaçao": "curacao",
    "united states": "usa",
    "czechia": "czech republic",  # football-data用Czechia, players.json用Czech Republic
}

def normalize_team_name(name):
    """统一队伍名用于匹配"""
    n = name.lower().strip()
    return TEAM_NAME_MAP.get(n, n)

def normalize_player_name(name):
    """标准化球员名用于匹配：去重音符号、去连字符、取最后一个词用于后缀匹配"""
    n = name.lower().strip()
    # 去常见重音
    replacements = {
        'é':'e','è':'e','ê':'e','ë':'e',
        'á':'a','à':'a','â':'a','ä':'a','ã':'a','å':'a',
        'í':'i','ì':'i','î':'i','ï':'i',
        'ó':'o','ò':'o','ô':'o','ö':'o','õ':'o','ø':'o',
        'ú':'u','ù':'u','û':'u','ü':'u',
        'ç':'c','ć':'c','č':'c',
        'ñ':'n','š':'s','ğ':'g','ü':'u','ı':'i',
        'đ':'d','ž':'z',
    }
    for a, b in replacements.items():
        n = n.replace(a, b)
    return n

def tokens(name):
    """提取标准化后的单词列表"""
    n = normalize_player_name(name)
    return re.findall(r"[a-z]+", n)

def partial_name_match(fb_name, local_name, min_overlap=1):
    """检查两个名字是否有足够的重叠部分"""
    fb_tokens = tokens(fb_name)
    local_tokens = tokens(local_name)
    if not fb_tokens or not local_tokens:
        return False
    
    # 策略1: 子串匹配 - 如果一方名字完全包含另一方
    fb_norm = normalize_player_name(fb_name)
    local_norm = normalize_player_name(local_name)
    if fb_norm == local_norm:
        return True
    if len(fb_norm) >= 4 and fb_norm in local_norm:
        return True
    if len(local_norm) >= 4 and local_norm in fb_norm:
        return True
    
    # 策略2: 计算交集
    common = set(fb_tokens) & set(local_tokens)
    return len(common) >= min_overlap

# ============ 3. 构建球员索引 ============

# 手动已知映射（football-data personId → 本地不存在但已知的数据）
MANUAL_FIXES = {
    7810: {"api_sports_id": None, "name_cn": "特雷沃·查洛巴", "jersey_number": None},  # Chalobah (不在本地)
    4693: {"api_sports_id": None, "name_cn": "洛根·罗杰森", "jersey_number": None},     # Rogerson (不在本地)
    217097: {"api_sports_id": None, "name_cn": "阿尔扬·马利奇", "jersey_number": None}, # Arjan Malic (不在本地)
    189969: {"api_sports_id": None, "name_cn": "罗-罗", "jersey_number": None},          # Ró-Ró (不在本地)
    212675: {"api_sports_id": None, "name_cn": "鲁斯兰别克·吉亚诺夫", "jersey_number": None}, # Jiyanov (不在本地)
}

def build_player_index(players_data, name_field="name", number_field=None, cn_field=None):
    """从球员列表构建索引"""
    index = []
    for team in players_data.get("teams", []):
        tname = normalize_team_name(team.get("name", ""))
        for p in team.get("players", []):
            entry = {
                "team": tname,
                "name": p.get(name_field, ""),
                "name_cn": p.get(cn_field) if cn_field else None,
                "number": p.get(number_field) if number_field else None,
                "api_sports_id": p.get("api_sports_id"),
                "photo_url": p.get("photo_url"),
            }
            index.append(entry)
    return index

idx_2026 = build_player_index(players_2026_raw, "name", "jerseyNumber", "nameCn")
idx_json = build_player_index(players_json_raw, "name", "number", "nameCn")

def lookup_player(fb_team_norm, fb_name):
    """在本地索引中查找最匹配的球员"""
    results = []
    
    # 第一轮：team 匹配 + name 精准匹配
    for entry in idx_2026 + idx_json:
        if entry["team"] == fb_team_norm:
            if partial_name_match(fb_name, entry["name"]):
                results.append(entry)
    
    # 按匹配质量排序（名字完全一样优先）
    def score(entry):
        fb_norm = normalize_player_name(fb_name)
        local_norm = normalize_player_name(entry["name"])
        s = 0
        if fb_norm == local_norm:
            s += 100  # 完美匹配
        elif len(fb_norm) >= 3 and fb_norm in local_norm:
            s += 50
        elif len(local_norm) >= 3 and local_norm in fb_norm:
            s += 50
        
        # 有中文名加分
        if entry.get("name_cn"):
            s += 30
        # 有api_sports_id加分
        if entry.get("api_sports_id"):
            s += 20
        # players_2026 比 players.json 优先
        if entry in idx_2026:
            s += 10
        return s
    
    results.sort(key=score, reverse=True)
    return results[0] if results else None

# ============ 4. Build final mapping ============
person_id_map = []
team_index = []

for team in teams_data.get("teams", []):
    team_id = team["id"]
    team_name = team["name"]
    team_tla = team.get("tla", "")
    team_name_lower = team_name.lower()
    
    team_index.append({
        "team_id": team_id,
        "name": team_name,
        "tla": team_tla
    })
    
    for player in team.get("squad", []):
        pid = player["id"]
        pname = player["name"]
        fb_team_norm = normalize_team_name(team_name_lower)
        
        matched = lookup_player(fb_team_norm, pname)
        
        if matched:
            api_sports_id = matched.get("api_sports_id")
            local_jersey = matched.get("number")
            name_cn = matched.get("name_cn")
            # 如果 matched 来自 players_2026，确认jerseyNumber
            for m in idx_2026:
                if m is matched and m.get("number"):
                    local_jersey = m["number"]
                    break
        else:
            api_sports_id = None
            local_jersey = None
            name_cn = None
        
        # 手动覆盖：已知不在本地数据中的球员
        if pid in MANUAL_FIXES:
            fix = MANUAL_FIXES[pid]
            api_sports_id = fix.get("api_sports_id") or api_sports_id
            name_cn = fix.get("name_cn") or name_cn
            local_jersey = fix.get("jersey_number") or local_jersey
        
        # 尝试从 api-sports 缓存补api_sports_id
        apisports_player_id = None
        team_key = fb_team_norm
        if team_key in apisports_data:
            for ap in apisports_data[team_key].get("players", []):
                if partial_name_match(pname, ap["name"]):
                    apisports_player_id = ap["id"]
                    break
        if not api_sports_id and apisports_player_id:
            api_sports_id = apisports_player_id
        
        entry = {
            "person_id": pid,
            "name": pname,
            "position": player.get("position", ""),
            "date_of_birth": player.get("dateOfBirth", ""),
            "nationality": player.get("nationality", ""),
            "team_id": team_id,
            "team_name": team_name,
            "team_tla": team_tla,
            "jersey_number": local_jersey,
            "name_cn": name_cn,
            "api_sports_id": api_sports_id,
        }
        person_id_map.append(entry)

# ============ 5. 输出 ============
output = {
    "total_teams": len(team_index),
    "total_players": len(person_id_map),
    "teams": team_index,
    "players": person_id_map
}

output_path = "outputs/football_data_person_id_map.json"
with open(output_path, "w", encoding="utf-8") as f:
    json.dump(output, f, indent=2, ensure_ascii=False)

# 统计
total = len(person_id_map)
has_api = sum(1 for p in person_id_map if p.get("api_sports_id"))
has_cn = sum(1 for p in person_id_map if p.get("name_cn"))
has_both = sum(1 for p in person_id_map if p.get("api_sports_id") and p.get("name_cn"))
neither = sum(1 for p in person_id_map if not p.get("api_sports_id") and not p.get("name_cn"))

print(f"✅ 完成：{len(team_index)} 支球队，{total} 名球员")
print(f"📁 输出: {output_path}")
print(f"📊 有 api_sports_id: {has_api}/{total}")
print(f"📊 有中文名: {has_cn}/{total}")
print(f"📊 两者都有: {has_both}/{total}")
print(f"📊 仍未匹配: {neither}/{total}")
print()

if neither > 0:
    print("--- 仍未匹配的球员 ---")
    for p in person_id_map:
        if not p.get("api_sports_id") and not p.get("name_cn"):
            print(f"  {p['person_id']:>6} | {p['team_tla']:>4} | {p['name']:30s}")
