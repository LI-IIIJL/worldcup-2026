"""
fetch_all_player_photos.py — 批量下载 48 队球员照片 (api-sports Pro)

流程：
1. 从 team_id_map.json 读取 48 队 api-sports ID
2. 调用 /players/squads?team={id} 获取每队球员信息（含 photo URL）
3. 下载所有照片到本地缓存目录
4. 生成 player_photo_map.json（name → photo_url 映射）
5. 回填到 players_2026.json

用法：
    pip install requests
    python fetch_all_player_photos.py

配额：
    api-sports Pro（7,500次/天）
    48 队 × 1 次 = 48 次请求 ← 一次跑完
"""

import json
import os
import sys
import requests
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

API_KEY = "a1171ce3f1e015c2deb20a3292be9a40"
BASE = "https://v3.football.api-sports.io"
HEADERS = {"x-apisports-key": API_KEY}

# 项目路径
PROJECT_ROOT = r"D:\WorldCupScanning"
PLAYERS_JSON = os.path.join(PROJECT_ROOT, "MainApp", "app", "src", "main", "assets", "players_2026.json")
PHOTO_DIR = os.path.join(PROJECT_ROOT, "MachineLearning_Module", "data", "player_photos")
OUTPUT_MAP = os.path.join(PROJECT_ROOT, "MachineLearning_Module", "data", "player_photo_map.json")

# api-sports 球队 ID 列表（世界杯 48 队）
API_SPORTS_TEAM_IDS = [
    6,    # Brazil
    7,    # Portugal
    8,    # Spain
    9,    # Colombia
    10,   # Argentina
    11,   # Uruguay
    12,   # Italy
    13,   # England
    14,   # Netherlands
    15,   # France
    16,   # Mexico
    17,   # Belgium
    18,   # Germany
    19,   # Chile
    20,   # Ecuador
    21,   # Croatia
    22,   # Paraguay
    23,   # Peru
    24,   # Denmark
    25,   # Germany
    26,   # Switzerland
    27,   # Japan
    28,   # South Korea
    29,   # Nigeria
    30,   # Ghana
    31,   # Egypt
    32,   # Senegal
    33,   # Morocco
    34,   # Tunisia
    35,   # Cameroon
    36,   # Ivory Coast
    37,   # Algeria
    38,   # Saudi Arabia
    39,   # Iran
    40,   # Australia
    41,   # USA
    42,   # Canada
    43,   # Costa Rica
    44,   # Jamaica
    45,   # Honduras
    46,   # Panama
    2118, # Curaçao
    3858, # South Africa
    3859, # Cape Verde
    3860, # Congo DR
    3861, # Zambia
    3862, # Iraq
    3863, # Jordan
    3864, # UAE
]

def fetch_squad(team_id: int) -> list[dict]:
    """调用 api-sports /players/squads?team={id}"""
    url = f"{BASE}/players/squads"
    resp = requests.get(url, headers=HEADERS, params={"team": team_id}, timeout=15)
    if resp.status_code != 200:
        print(f"  [ERR] Team {team_id}: HTTP {resp.status_code}")
        return []
    data = resp.json()
    players = data.get("response", [])
    if not players:
        return []
    # players[0] 的结构: {"team": {...}, "players": [...]}
    return players[0].get("players", [])

def download_photo(url: str, save_path: str) -> bool:
    """下载单张照片"""
    if os.path.exists(save_path):
        return True  # 已存在
    try:
        resp = requests.get(url, timeout=10)
        if resp.status_code == 200:
            os.makedirs(os.path.dirname(save_path), exist_ok=True)
            with open(save_path, "wb") as f:
                f.write(resp.content)
            return True
    except Exception as e:
        print(f"    [DL ERR] {url[:60]}: {e}")
    return False

def normalize_name(name: str) -> str:
    """标准化球员名用于匹配"""
    return name.strip().lower().replace("'", "").replace("-", " ").replace(".", "")

def main():
    os.makedirs(PHOTO_DIR, exist_ok=True)
    
    # 1. 加载现有 players_2026.json
    with open(PLAYERS_JSON, encoding="utf-8") as f:
        players_data = json.load(f)
    
    # 建立本地球员索引 (jerseyNumber + teamName → player)
    # 由于 team name 不统一，我们按 jerseyNumber 配队名模糊匹配
    print(f"已加载 {len([p for t in players_data['teams'] for p in t['players']])} 名本地球员")
    
    # 2. 遍历所有 api-sports 球队
    photo_map = {}  # player_name_in_json → photo_url
    total_downloaded = 0
    total_skipped = 0
    failed_teams = []
    
    for i, team_id in enumerate(API_SPORTS_TEAM_IDS):
        print(f"\n[{i+1}/{len(API_SPORTS_TEAM_IDS)}] 处理球队 ID={team_id}...")
        players = fetch_squad(team_id)
        if not players:
            print(f"  ⚠️ 无数据")
            failed_teams.append(team_id)
            continue
        
        print(f"  → {len(players)} 名球员")
        
        for player in players:
            pid = player.get("id")
            name = player.get("name", "")
            photo_url = player.get("photo", "")
            jersey = player.get("number")
            position = player.get("position", "")
            
            if not photo_url or not name:
                continue
            
            # 文件名：{player_id}_{normalized_name}.jpg
            safe_name = name.lower().replace(" ", "_").replace("'", "").replace(".","")
            filename = f"{pid}_{safe_name}.jpg"
            save_path = os.path.join(PHOTO_DIR, filename)
            
            # 下载
            if download_photo(photo_url, save_path):
                total_downloaded += 1
            else:
                total_skipped += 1
            
            # 记录映射 (key = 球员名, value = photo_url)
            photo_map[name] = {
                "player_id": pid,
                "photo_url": photo_url,
                "local_path": f"player_photos/{filename}",
                "jersey_number": jersey,
                "position": position
            }
        
        # 礼貌延迟
        if i < len(API_SPORTS_TEAM_IDS) - 1:
            time.sleep(0.5)
    
    # 3. 保存 photo map
    with open(OUTPUT_MAP, "w", encoding="utf-8") as f:
        json.dump(photo_map, f, ensure_ascii=False, indent=2)
    
    print(f"\n{'='*50}")
    print(f"下载完成: {total_downloaded} 张")
    print(f"跳过: {total_skipped} 张")
    print(f"失败球队: {len(failed_teams)} 队")
    print(f"映射文件: {OUTPUT_MAP}")
    print(f"照片目录: {PHOTO_DIR}")
    print(f"{'='*50}")
    
    # 4. 尝试回填到 players_2026.json
    # 由于球员名字可能不完全匹配，先打印统计
    mapped = 0
    for team in players_data["teams"]:
        for player in team["players"]:
            pname = player.get("name", "")
            norm = normalize_name(pname)
            # 模糊匹配
            for api_name, info in photo_map.items():
                if normalize_name(api_name) == norm or \
                   normalize_name(api_name.split()[-1]) in norm:  # 匹配姓氏
                    player["photo_url"] = info["photo_url"]
                    player["api_sports_id"] = info["player_id"]
                    mapped += 1
                    break
    
    # 保存回填后的 JSON
    with open(PLAYERS_JSON, "w", encoding="utf-8") as f:
        json.dump(players_data, f, ensure_ascii=False, indent=2)
    
    print(f"\n回填 players_2026.json: {mapped}/{sum(len(t['players']) for t in players_data['teams'])} 名球员匹配成功")

if __name__ == "__main__":
    main()
