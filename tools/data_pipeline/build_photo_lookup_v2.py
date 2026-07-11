"""
构建高精度球员照片查找表

策略：
1. 通过 person_id_map.json（1249人，588人有api_sports_id）获取正确的 api_sports_id
2. 用该ID构造 CDN URL
3. 如果 player_photo_map.json 有该ID，验证照片URL

对 person_id_map 中没有ID的球员（约661人），用智能名字匹配回退到 photo_map。
"""
import json, re, unicodedata

PLAYERS_FILE = r'D:\WorldCupScanning\MainApp\app\src\main\assets\players_2026.json'
PHOTO_MAP_FILE = r'D:\WorldCupScanning\MainApp\app\src\main\assets\player_photo_map.json'
PERSON_ID_FILE = r'D:\WorldCupScanning\MainApp\app\src\main\assets\football_data_person_id_map.json'
OUTPUT = r'D:\WorldCupScanning\MainApp\app\src\main\assets\photo_lookup.json'

def load_json(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)

def strip_accents(s):
    """去除所有重音符号，比如 é → e, ü → u"""
    nfkd = unicodedata.normalize('NFKD', s)
    return ''.join(c for c in nfkd if not unicodedata.combining(c))

players_2026 = load_json(PLAYERS_FILE)
photo_map = load_json(PHOTO_MAP_FILE)
person_id_map = load_json(PERSON_ID_FILE)

# 1. Build person_id_map index: player name -> api_sports_id
# Use accent-stripped, lowercased name as key for fuzzy matching
pid_index = {}       # original lower name -> api_sports_id
pid_index_plain = {} # accent-stripped lower name -> api_sports_id
for p in person_id_map.get('players', []):
    name = p.get('name', '')
    pid = p.get('api_sports_id')
    if name and pid is not None:
        key = name.lower().strip()
        pid_index[key] = int(pid)
        pid_index_plain[strip_accents(key)] = int(pid)

# 2. Build photo_map by ID index
photo_by_id = {}  # player_id -> photo_url
for name, info in photo_map.items():
    pid = info.get('player_id')
    if pid:
        photo_by_id[pid] = info['photo_url']

# 3. Build photo_map name index (for fuzzy matching)
# Index by: original, accent-stripped, last name
photo_index = {}  # accent-stripped lower name -> photo_url
photo_by_last = {}  # last_name.lower() -> [(photo_url, full_name)]

for name, info in photo_map.items():
    plain = strip_accents(name).lower().strip()
    photo_index[plain] = info['photo_url']
    
    last = name.split()[-1].lower().rstrip('.') if name.split() else name.lower()
    last_plain = strip_accents(last)
    if last_plain not in photo_by_last:
        photo_by_last[last_plain] = []
    photo_by_last[last_plain].append((info['photo_url'], name))

def find_in_photo_map(pname, pid):
    """
    Try to find a player's photo in player_photo_map.json
    Uses accent-stripped matching for robustness.
    Returns photo_url or None
    """
    # Method A: Look up by api_sports_id (most reliable)
    if pid and pid in photo_by_id:
        return photo_by_id[pid]
    
    pname_plain = strip_accents(pname).lower().strip()
    
    # Method B: Accent-stripped exact match
    if pname_plain in photo_index:
        return photo_index[pname_plain]
    
    p_plain_words = [w for w in pname_plain.split() if len(w) > 1]
    p_last_plain = p_plain_words[-1] if p_plain_words else pname_plain
    
    # Method C: Last name match, then verify by first initial
    if p_last_plain in photo_by_last:
        candidates = photo_by_last[p_last_plain]
        if len(candidates) == 1:
            return candidates[0][0]
        else:
            # Multiple with same last name - check first initial
            p_first = p_plain_words[0][0] if p_plain_words else ''
            for url, pm_name in candidates:
                pm_plain = strip_accents(pm_name).lower().strip()
                pm_words = [w for w in pm_plain.split() if len(w) > 1]
                pm_first = pm_words[0][0] if pm_words else ''
                if p_first and pm_first and p_first == pm_first:
                    return url
            # If no initial match, take first
            return candidates[0][0]
    
    # Method D: Check if full name contains each other
    for pm_plain, url in photo_index.items():
        if pm_plain in pname_plain or pname_plain in pm_plain:
            return url
    
    return None

def find_id_in_person_id_map(pname):
    """Try to find api_sports_id from person_id_map by accent-insensitive matching"""
    pname_plain = strip_accents(pname).lower().strip()
    
    # Exact accent-stripped match
    if pname_plain in pid_index_plain:
        return pid_index_plain[pname_plain]
    
    # Partial match - check if one contains the other
    for key, pid in pid_index.items():
        key_plain = strip_accents(key)
        if key_plain in pname_plain or pname_plain in key_plain:
            return pid
        # Check if last name matches and first initial matches
        key_words = [w for w in key_plain.split() if len(w) > 1]
        p_words = [w for w in pname_plain.split() if len(w) > 1]
        if key_words and p_words and key_words[-1] == p_words[-1]:
            # Last name matches, check first initial
            if key_words[0][0] == p_words[0][0]:
                return pid
    
    return None

# Build the lookup
lookup = {}
stats = {"via_person_id+construct": 0, "via_person_id+photo_map": 0, "via_fuzzy_photo_map": 0, "not_found": 0}

for team in players_2026.get('teams', []):
    team_name = team.get('name', '')
    for p in team.get('players', []):
        pname = p.get('name', '')
        existing_url = p.get('photo_url', '')
        
        url = None
        match = "not_found"
        
        # Step 1: Find correct api_sports_id from person_id_map (accent-insensitive)
        correct_pid = find_id_in_person_id_map(pname)
        
        # Step 2: Try photo_map lookup (by ID or by name)
        photo_map_url = find_in_photo_map(pname, correct_pid)
        
        if photo_map_url:
            url = photo_map_url
            match = "via_person_id+photo_map" if correct_pid else "via_fuzzy_photo_map"
        elif correct_pid:
            # Step 3: Construct CDN URL from correct ID
            url = f"https://media.api-sports.io/football/players/{correct_pid}.png"
            match = "via_person_id+construct"
        elif existing_url:
            # Step 4: Fall back to existing photo_url
            url = existing_url
            match = "existing_url_fallback"
        
        if match != "not_found":
            stats[match] = stats.get(match, 0) + 1
        else:
            stats["not_found"] += 1
        
        lookup[pname] = {
            "photo_url": url,
            "team": team_name,
            "match": match
        }

# Save simple lookup (name -> url)
simple_lookup = {}
for pname, info in lookup.items():
    simple_lookup[pname] = info["photo_url"]

output = {
    "lookup": simple_lookup,
    "stats": stats,
    "total": len(lookup),
    "coverage_pct": round((len(lookup) - stats.get("not_found", 0)) / len(lookup) * 100, 1)
}

with open(OUTPUT, 'w', encoding='utf-8') as f:
    json.dump(output, f, indent=2, ensure_ascii=False)

print(f"总球员数: {len(lookup)}")
print(f"匹配统计:")
for k, v in sorted(stats.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")
print(f"覆盖率: {output['coverage_pct']}% ({len(lookup)-stats.get('not_found',0)}/{len(lookup)})")

# Verify France players
print("\n=== France verification ===")
for team in players_2026.get('teams', []):
    if 'france' not in team.get('name', '').lower():
        continue
    for p in team.get('players', []):
        name = p.get('name', '')
        info = lookup.get(name, {})
        url = info.get('photo_url', '')
        match_type = info.get('match', '?')
        status = "✅" if url else "❌"
        print(f"  {status} {name:25s} [{match_type:25s}]")
