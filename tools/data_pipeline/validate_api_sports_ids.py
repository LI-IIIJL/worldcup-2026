"""
批量交叉验证 players_2026.json 的 api_sports_id
以 player_photo_map.json (api-sports 直接来源) 为基准，
找出并自动修复所有错误的 ID 映射。

用法: python validate_api_sports_ids.py [--dry-run]
  --dry-run: 仅报告不修改
"""

import json
import re
import sys
import os
import unicodedata

PLAYERS_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\players_2026.json"
PHOTO_MAP_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\player_photo_map.json"
FOOTBALL_DATA_MAP_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\football_data_person_id_map.json"
PHOTO_LOOKUP_PATH = r"D:\WorldCupScanning\MainApp\app\src\main\assets\photo_lookup.json"
OUTPUTS_MAP_PATH = r"D:\WorldCupScanning\outputs\football_data_person_id_map.json"

DRY_RUN = "--dry-run" in sys.argv


def normalize(s: str) -> str:
    """Unicode NFKD 标准化 + 小写 + 去重音"""
    s = unicodedata.normalize("NFKD", s)
    s = s.encode("ASCII", "ignore").decode("ascii")
    return s.lower().strip()


def parse_short_name(short: str):
    """
    解析缩写名 "L. Messi" → ("l", "messi")
    返回 (first_initial, last_name) 或 (None, None)
    """
    parts = short.replace(".", "").split()
    if len(parts) < 2:
        return None, None
    # 首字母为单个字符 → 是缩写
    if len(parts[0]) == 1:
        return parts[0].lower(), normalize(parts[-1])
    return None, None


def expand_short_name_to_full(short: str) -> str | None:
    """
    将缩写名展开为可能的完整匹配key:
    "L. Messi" → "lmessi" (初始+姓氏小写无空格)
    用于模糊匹配
    """
    parts = short.replace(".", "").split()
    if len(parts) < 2:
        return None
    if len(parts[0]) == 1:
        return (parts[0].lower() + normalize(parts[-1])).replace(" ", "")
    return None


def full_to_compact(full_name: str) -> str:
    """
    全名 → 紧致key: "Lionel Messi" → "lmessi"
    """
    parts = normalize(full_name).split()
    if len(parts) < 2:
        return normalize(full_name).replace(" ", "")
    return (parts[0][0] + parts[-1]).replace(" ", "")


# ====================================================================
# 加载数据
# ====================================================================
print("=" * 60)
print("加载 player_photo_map.json ...")
with open(PHOTO_MAP_PATH, "r", encoding="utf-8") as f:
    photo_map: dict = json.load(f)

# 构建查找索引:
# 1) compact_key → player_id
#    "lmessi" → 154   (来自 "L. Messi")
#    "emartinez" → 19599  (来自 "E. Martínez")
photo_by_compact: dict[str, int] = {}
# 2) 短名 → player_id (用于先试精确匹配)
photo_by_short: dict[str, int] = {}
# 3) 完整名 → player_id (如 photo_map 中恰好有完整名)
photo_by_full: dict[str, int] = {}

for full_name, info in photo_map.items():
    pid = info["player_id"]
    photo_by_short[full_name] = pid  # 原样key

    # 如果 photo_map 本身就是缩写，构建 compact_key
    compact = expand_short_name_to_full(full_name)
    if compact:
        photo_by_compact[compact] = pid

    # 也构建首字母+姓氏的 compact
    compact2 = full_to_compact(full_name)
    if compact2:
        photo_by_compact[compact2] = pid

    # normalized full name
    photo_by_full[normalize(full_name)] = pid

print(f"  player_photo_map.json: {len(photo_map)} 名球员")
print(f"  构建了 {len(photo_by_compact)} 个 compact key")

# 加载 players_2026.json
print("加载 players_2026.json ...")
with open(PLAYERS_PATH, "r", encoding="utf-8") as f:
    players_data = json.load(f)

# 收集所有有 api_sports_id 的球员
all_players: list[dict] = []
for team in players_data.get("teams", []):
    team_name = team.get("name", "?")
    for p in team.get("players", []):
        if "api_sports_id" in p:
            all_players.append({
                "team": team_name,
                "name": p.get("name", ""),
                "nameCn": p.get("nameCn", ""),
                "api_sports_id": p["api_sports_id"],
                "photo_url": p.get("photo_url", ""),
            })

print(f"  players_2026.json: {len(all_players)} 名球员有 api_sports_id")


# ====================================================================
# 交叉验证
# ====================================================================
print("\n" + "=" * 60)
print("开始交叉验证...\n")

FIXED = 0
WRONG = 0
MISSING = 0
CORRECT = 0

fixes: list[dict] = []


def lookup_id(player_name: str) -> int | None:
    """通过多种策略在 photo_map 中查找 player_id."""
    name = player_name.strip()

    # 策略1: 精确匹配 photo_map key
    pid = photo_by_short.get(name)
    if pid is not None:
        return pid

    # 策略2: 标准化匹配
    pid = photo_by_full.get(normalize(name))
    if pid is not None:
        return pid

    # 策略3: compact key 匹配
    # "Lionel Messi" → "lmessi" → 154
    compact = full_to_compact(name)
    pid = photo_by_compact.get(compact)
    if pid is not None:
        return pid

    # 策略4: 暴力遍历 photo_map，检查缩写匹配
    # "Lionel Messi" ↔ "L. Messi"
    parts = normalize(name).split()
    if len(parts) >= 2:
        first_initial = parts[0][0]
        last_name_norm = parts[-1]
        for pm_key, pm_pid in photo_by_short.items():
            fi, ln = parse_short_name(pm_key)
            if fi == first_initial and normalize(ln) == last_name_norm:
                return pm_pid
            # 也检查反过来: photo_map 用全名, players_2026 可能用短名
            if normalize(pm_key).replace(".", "").replace(" ", "") == normalize(name).replace(".", "").replace(" ", ""):
                return pm_pid

    return None


for player in all_players:
    name = player["name"]
    current_id = player["api_sports_id"]

    expected_id = lookup_id(name)

    if expected_id is None:
        MISSING += 1
        continue

    if current_id == expected_id:
        CORRECT += 1
    else:
        WRONG += 1
        print(f"  ❌ {name:30s} ({player['team']:15s}) 当前={current_id:<7d} 正确={expected_id}")
        fixes.append({"name": name, "nameCn": player["nameCn"],
                      "old_id": current_id, "new_id": expected_id,
                      "team": player["team"]})


print(f"\n{'='*60}")
print(f"验证结果:")
print(f"  正确: {CORRECT}")
print(f"  错误: {WRONG}")
print(f"  在 photo_map 中找不到: {MISSING}")
print(f"  总计有 api_sports_id 的球员: {len(all_players)}")

if fixes:
    print(f"\n需要修复的映射 ({len(fixes)} 处):")
    for f in fixes:
        print(f"  {f['name']:30s} ({f['team']:15s})  {f['old_id']} → {f['new_id']}")
else:
    print("\n🎉 所有 ID 都正确！")

if not DRY_RUN and fixes:
    # ======== 执行修复 ========

    name_to_new = {f["name"]: f["new_id"] for f in fixes}

    # 4a) 修复 players_2026.json
    print(f"\n{'='*60}")
    print("正在修复 players_2026.json ...")
    fixed_count = 0
    for team in players_data.get("teams", []):
        for p in team.get("players", []):
            name = p.get("name", "")
            if name in name_to_new:
                old_id = p.get("api_sports_id")
                new_id = name_to_new[name]
                if old_id != new_id:
                    p["api_sports_id"] = new_id
                    p["photo_url"] = f"https://media.api-sports.io/football/players/{new_id}.png"
                    fixed_count += 1
                    print(f"  ✅ {name:30s} api_sports_id: {old_id} → {new_id}")

    with open(PLAYERS_PATH, "w", encoding="utf-8") as f:
        json.dump(players_data, f, ensure_ascii=False, indent=2)
    print(f"  → 已更新 {fixed_count} 处 ({PLAYERS_PATH})")

    # 4b) 修复 football_data_person_id_map.json (assets)
    print(f"\n修复 football_data_person_id_map.json (assets) ...")
    with open(FOOTBALL_DATA_MAP_PATH, "r", encoding="utf-8") as f:
        fd_map = json.load(f)

    fd_fixed = 0
    for entry in fd_map:
        eid = entry.get("api_sports_id")
        name_en = entry.get("name", "")
        if eid is not None and name_en in name_to_new:
            expected = name_to_new[name_en]
            if eid != expected:
                entry["api_sports_id"] = expected
                fd_fixed += 1
                print(f"  ✅ {name_en:30s} api_sports_id: {eid} → {expected}")

    with open(FOOTBALL_DATA_MAP_PATH, "w", encoding="utf-8") as f:
        json.dump(fd_map, f, ensure_ascii=False, indent=2)
    print(f"  → 已更新 {fd_fixed} 处 ({FOOTBALL_DATA_MAP_PATH})")

    # 4c) 修复 photo_lookup.json
    print(f"\n修复 photo_lookup.json ...")
    with open(PHOTO_LOOKUP_PATH, "r", encoding="utf-8") as f:
        pl_map = json.load(f)

    pl_fixed = 0
    for f_item in fixes:
        key = f_item["name"]
        if key in pl_map.get("lookup", {}):
            old_url = pl_map["lookup"][key]
            expected_url = f"https://media.api-sports.io/football/players/{f_item['new_id']}.png"
            if old_url != expected_url:
                pl_map["lookup"][key] = expected_url
                pl_fixed += 1
                print(f"  ✅ {key:30s} {old_url.rsplit('/', 1)[-1]} → {f_item['new_id']}.png")

    with open(PHOTO_LOOKUP_PATH, "w", encoding="utf-8") as f:
        json.dump(pl_map, f, ensure_ascii=False, indent=2)
    print(f"  → 已更新 {pl_fixed} 处 ({PHOTO_LOOKUP_PATH})")

    # 4d) 修复 outputs/football_data_person_id_map.json
    if os.path.exists(OUTPUTS_MAP_PATH):
        print(f"\n修复 {OUTPUTS_MAP_PATH} ...")
        with open(OUTPUTS_MAP_PATH, "r", encoding="utf-8") as f:
            fd_out = json.load(f)

        fd_out_fixed = 0
        for entry in fd_out:
            eid = entry.get("api_sports_id")
            name_en = entry.get("name", "")
            if eid is not None and name_en in name_to_new:
                expected = name_to_new[name_en]
                if eid != expected:
                    entry["api_sports_id"] = expected
                    fd_out_fixed += 1

        with open(OUTPUTS_MAP_PATH, "w", encoding="utf-8") as f:
            json.dump(fd_out, f, ensure_ascii=False, indent=2)
        print(f"  → 已更新 {fd_out_fixed} 处 ({OUTPUTS_MAP_PATH})")

    print(f"\n{'='*60}")
    print(f"全部修复完成！共修复 {len(fixes)} 个 ID 映射错误。")

print("\n提示: 记得重建 App (assembleDebug) 使改动生效。")
