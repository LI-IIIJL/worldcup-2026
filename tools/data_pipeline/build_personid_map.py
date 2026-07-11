"""
build_personid_map.py
从 football-data.org 的 teams 响应中提取所有球员的 personId，构建映射表。
同时关联本地已有数据（players_2026.json 的 api_sports_id）。
输出: outputs/football_data_person_id_map.json
"""

import json
import sys
import os

# 设置工作目录
os.chdir(r"D:\WorldCupScanning")

# 1. 读取 football-data.org 缓存的 teams 数据
with open(r"D:\WorldCupScanning\outputs\football_teams_raw.json", "r", encoding="utf-8") as f:
    teams_data = json.load(f)

# 2. 读取本地 players_2026.json
with open("MainApp/app/src/main/assets/players_2026.json", "r", encoding="utf-8") as f:
    players_2026_raw = json.load(f)
players_2026 = players_2026_raw.get("teams", [])

# 3. 读取本地 players.json（有号码映射）
with open("MainApp/app/src/main/assets/players.json", "r", encoding="utf-8") as f:
    players_json_raw = json.load(f)
players_json = players_json_raw.get("teams", [])

# 4. 读取 api-sports 所有球队数据作为ID映射参考
# 实际上 apisports 目录下所有球队都应该读取
import glob
apisports_dir = "MachineLearning_Module/data/apisports"
apisports_data = {}
for fpath in glob.glob(f"{apisports_dir}/*.json"):
    with open(fpath, "r", encoding="utf-8") as f:
        data = json.load(f)
        team_name = data.get("team", {}).get("name", "").lower()
        if team_name:
            apisports_data[team_name] = data

# 构建 players_2026 的快速查找（按球队+号码）
player_2026_by_teamnum = {}
for team in players_2026:
    tname = team.get("name", "").lower()
    for p in team.get("players", []):
        num = p.get("jerseyNumber")
        if num:
            key = (tname, num)
            player_2026_by_teamnum[key] = p

# 构建 players.json 的快速查找（按球队+号码）
player_json_by_teamnum = {}
for team in players_json:
    tname = team.get("name", "").lower()
    for p in team.get("players", []):
        num = p.get("number")
        if num:
            key = (tname, num)
            player_json_by_teamnum[key] = p

# 5. 构建映射表
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
        position = player.get("position", "")
        dob = player.get("dateOfBirth", "")
        nationality = player.get("nationality", "")
        
        # 从 players_2026.json 找关联数据
        api_sports_id = None
        local_jersey = None
        local_name_cn = None
        
        # 尝试按名字匹配
        for key, pdata in player_2026_by_teamnum.items():
            if key[0] == team_name_lower and pdata.get("name", "").lower() == pname.lower():
                api_sports_id = pdata.get("api_sports_id")
                local_jersey = pdata.get("jerseyNumber")
                local_name_cn = pdata.get("nameCn")
                break
        
        # 也检查 players.json
        if not local_jersey:
            for key, pdata in player_json_by_teamnum.items():
                if key[0] == team_name_lower and pdata.get("name", "").lower() == pname.lower():
                    local_jersey = pdata.get("number")
                    local_name_cn = pdata.get("nameCn")
                    break
        
        # 从 api-sports 缓存找关联
        apisports_player_id = None
        if team_name_lower in apisports_data:
            for ap in apisports_data[team_name_lower].get("players", []):
                if ap["name"].lower() in pname.lower() or pname.lower() in ap["name"].lower():
                    apisports_player_id = ap["id"]
                    break
        
        entry = {
            "person_id": pid,
            "name": pname,
            "position": position,
            "date_of_birth": dob,
            "nationality": nationality,
            "team_id": team_id,
            "team_name": team_name,
            "team_tla": team_tla,
            "jersey_number": local_jersey,
            "name_cn": local_name_cn,
            "api_sports_id": apisports_player_id or api_sports_id,
        }
        person_id_map.append(entry)

# 6. 输出映射表
output = {
    "total_teams": len(team_index),
    "total_players": len(person_id_map),
    "teams": team_index,
    "players": person_id_map
}

output_path = "outputs/football_data_person_id_map.json"
with open(output_path, "w", encoding="utf-8") as f:
    json.dump(output, f, indent=2, ensure_ascii=False)

print(f"✅ 完成：{len(team_index)} 支球队，{len(person_id_map)} 名球员")
print(f"📁 输出: {output_path}")

# 7. 统计带关联信息的情况
with_api = sum(1 for p in person_id_map if p["api_sports_id"] or p["name_cn"])
with_jersey = sum(1 for p in person_id_map if p["jersey_number"])
print(f"📊 有 api_sports_id 或中文名: {with_api}/{len(person_id_map)}")
print(f"📊 有球衣号码: {with_jersey}/{len(person_id_map)}")
