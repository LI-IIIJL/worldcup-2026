"""
构建球员照片查找表
将 players_2026.json 中每个球员的名字匹配到 player_photo_map.json 的正确ID，
输出 players_2026 名 → CDN URL 的完整映射。

三层匹配策略：
1. API ID 精确匹配（如果 players_2026.json 有 photo_url 且 photo_map 有相同ID）
2. 姓氏精确匹配（将 players_2026 全名简化为姓，与 photo_map 的缩写匹配）
3. 姓氏模糊匹配（取玩家名和 photo_map 条目的共同词）
"""
import json, re

PLAYERS_FILE = r'D:\WorldCupScanning\MainApp\app\src\main\assets\players_2026.json'
PHOTO_MAP_FILE = r'D:\WorldCupScanning\MainApp\app\src\main\assets\player_photo_map.json'
PERSON_ID_FILE = r'D:\WorldCupScanning\MainApp\app\src\main\assets\football_data_person_id_map.json'
OUTPUT = r'D:\WorldCupScanning\MainApp\app\src\main\assets\photo_lookup.json'

def load_json(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)

players_2026 = load_json(PLAYERS_FILE)
photo_map = load_json(PHOTO_MAP_FILE)
person_id_map = load_json(PERSON_ID_FILE)

# Build lookup indexes
# photo_map: name -> {player_id, photo_url}
# name in photo_map is like "W. Saliba", "K. Mbappé", "Mike Maignan"

def extract_last_name(name):
    """从全名中提取姓氏"""
    name = name.strip()
    # Handle "K. Mbappé" -> "Mbappé"
    parts = name.split()
    if not parts:
        return name.lower()
    # Last part is the surname
    last = parts[-1].lower()
    return last

def extract_initial(name):
    """从全名中提取首字母+姓氏"""
    name = name.strip()
    parts = name.split()
    if len(parts) >= 2:
        first_initial = parts[0][0].lower() if parts[0] else ''
        last = parts[-1].lower()
        return f"{first_initial}. {last}"
    return name.lower()

def name_similarity(a, b):
    """计算两个名字的相似度"""
    a_lower = a.lower().strip()
    b_lower = b.lower().strip()
    
    # Exact match
    if a_lower == b_lower:
        return 100
    
    # Contains match
    if a_lower in b_lower or b_lower in a_lower:
        return 80
    
    # Last name match
    a_last = extract_last_name(a)
    b_last = extract_last_name(b)
    if a_last == b_last:
        return 90
    
    # Initial + last name match
    a_init = extract_initial(a)
    b_init = extract_initial(b)
    if a_init == b_init:
        return 85
    
    # Last name contains or is contained by
    if a_last in b_last or b_last in a_last:
        return 70
    
    # Common words
    a_words = set(re.sub(r'[\.\s]+', ' ', a_lower).split())
    b_words = set(re.sub(r'[\.\s]+', ' ', b_lower).split())
    common = a_words & b_words
    # Remove very short words
    common = {w for w in common if len(w) > 1}
    if len(common) >= 1:
        return 60
    
    return 0

# Build photo_map indexes
photo_by_id = {}   # player_id -> (photo_url, name)
photo_by_lastname = {}  # last_name -> [(photo_url, name, player_id)]

for name, info in photo_map.items():
    pid = info['player_id']
    url = info['photo_url']
    last = extract_last_name(name)
    
    photo_by_id[pid] = (url, name)
    if last not in photo_by_lastname:
        photo_by_lastname[last] = []
    photo_by_lastname[last].append((url, name, pid))

# Also index person_id_map by name for additional ID lookup
person_id_by_name = {}
for p in person_id_map.get('players', []):
    pname = p.get('name', '')
    pid = p.get('api_sports_id')
    if pname and pid:
        person_id_by_name[pname.lower()] = int(pid)

# Build the lookup for all players in players_2026.json
results = []
stats = {"exact_id": 0, "lastname_match": 0, "fuzzy_match": 0, "person_id_fallback": 0, "not_found": 0}

for team in players_2026.get('teams', []):
    team_name = team.get('name', '')
    for p in team.get('players', []):
        pname = p.get('name', '')
        pid = p.get('api_sports_id')
        existing_url = p.get('photo_url', '')
        
        best_url = None
        match_type = "not_found"
        
        # Strategy 1: Exact ID match (if players_2026 has an ID that exists in photo_map)
        if pid and pid in photo_by_id:
            best_url = photo_by_id[pid][0]
            match_type = "exact_id"
        
        # Strategy 2: Last name match (players_2026 full name -> photo_map abbreviated name)
        if best_url is None:
            p_last = extract_last_name(pname)
            if p_last in photo_by_lastname:
                candidates = photo_by_lastname[p_last]
                if len(candidates) == 1:
                    best_url = candidates[0][0]
                    match_type = "lastname_match"
                else:
                    # Multiple candidates with same last name - pick best by similarity
                    best_score = 0
                    for url, pm_name, pm_id in candidates:
                        score = name_similarity(pname, pm_name)
                        if score > best_score:
                            best_score = score
                            best_url = url
                    if best_url:
                        match_type = "lastname_match"
        
        # Strategy 3: Fuzzy name match across all photo_map entries
        if best_url is None:
            best_score = 0
            for pm_name, info in photo_map.items():
                score = name_similarity(pname, pm_name)
                if score > best_score and score >= 60:
                    best_score = score
                    best_url = info['photo_url']
            if best_url:
                match_type = "fuzzy_match"
        
        # Strategy 4: Look up in person_id_map by name, then match by ID
        if best_url is None:
            pname_lower = pname.lower()
            if pname_lower in person_id_by_name:
                lookup_id = person_id_by_name[pname_lower]
                if lookup_id in photo_by_id:
                    best_url = photo_by_id[lookup_id][0]
                    match_type = "person_id_fallback"
        
        # Strategy 5: Use existing photo_url as is
        if best_url is None and existing_url:
            best_url = existing_url
            match_type = "existing_url"
        
        if match_type != "not_found":
            results.append({
                "name": pname,
                "team": team_name,
                "photo_url": best_url,
                "match_type": match_type
            })
            stats[match_type] = stats.get(match_type, 0) + 1
        else:
            stats["not_found"] += 1
            results.append({
                "name": pname,
                "team": team_name,
                "photo_url": None,
                "match_type": "not_found"
            })

# Save as a simple name -> url lookup
lookup = {}
for r in results:
    lookup[r["name"]] = {
        "photo_url": r["photo_url"],
        "team": r["team"],
        "match_type": r["match_type"]
    }

output = {
    "lookup": {r["name"]: r["photo_url"] for r in results},
    "stats": stats,
    "total": len(results),
    "coverage": f"{sum(v for k,v in stats.items() if k != 'not_found')}/{len(results)} ({sum(v for k,v in stats.items() if k != 'not_found')/len(results)*100:.1f}%)"
}

with open(OUTPUT, 'w', encoding='utf-8') as f:
    json.dump(output, f, indent=2, ensure_ascii=False)

print(f"总球员数: {len(results)}")
print(f"匹配统计: {json.dumps(stats, indent=2)}")
print(f"覆盖率: {output['coverage']}")
print(f"\n输出: {OUTPUT}")
print(f"照片映射大小: {len(lookup)} 条")
