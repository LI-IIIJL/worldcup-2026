"""
pull_all_trophies.py - 从api-sports拉取所有球员的生涯荣誉并缓存到本地
"""
import json, urllib.request, time, os

os.chdir(r"D:\WorldCupScanning")

API_KEY = "a1171ce3f1e015c2deb20a3292be9a40"
BASE = "https://v3.football.api-sports.io"

with open("outputs/football_data_person_id_map.json", encoding="utf-8") as f:
    data = json.load(f)

# 筛选有 api_sports_id 的球员
players = [p for p in data["players"] if p.get("api_sports_id")]
print(f"需拉取荣誉的球员: {len(players)}")

# 已有缓存则跳过
cache_path = "MachineLearning_Module/data/trophies_cache.json"
existing = {}
if os.path.exists(cache_path):
    with open(cache_path, encoding="utf-8") as f:
        existing = json.load(f)

existing_ids = set(int(k) for k in existing.keys())
to_fetch = [p for p in players if p["api_sports_id"] not in existing_ids]
print(f"已有缓存: {len(existing_ids)}, 待拉取: {len(to_fetch)}")

if len(to_fetch) == 0:
    print("全部已缓存，无需拉取")
    exit(0)

# 分批拉取（api-sports 7500/天，不限速）
batch_size = 50
for i in range(0, len(to_fetch), batch_size):
    batch = to_fetch[i:i+batch_size]
    for p in batch:
        aid = p["api_sports_id"]
        url = f"{BASE}/trophies?player={aid}"
        req = urllib.request.Request(url, headers={"x-apisports-key": API_KEY})
        try:
            resp = urllib.request.urlopen(req, timeout=10)
            raw = json.loads(resp.read())
            trophies = raw.get("response", [])
            existing[str(aid)] = {
                "person_id": p["person_id"],
                "name": p["name"],
                "trophies": trophies
            }
            # 每拉一个打印进度
            print(f"  [{i*50+batch.index(p)+1}/{len(to_fetch)}] {p['name']:25s} -> {len(trophies)} trophies")
        except Exception as e:
            print(f"  [ERR] {p['name']}: {e}")
        time.sleep(0.3)  # 300ms间隔，安全速率
    
    # 每批保存一次
    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(existing, f, indent=2, ensure_ascii=False)
    print(f"  --- saved {len(existing)} entries ---")

print(f"\n完成！共缓存 {len(existing)} 名球员的荣誉数据")
