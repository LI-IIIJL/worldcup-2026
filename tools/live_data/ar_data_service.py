#!/usr/bin/env python3
r"""
AR 实时比赛数据服务 V2
══════════════════════
三 API 融合: football-data.org + BDL GOAT + api-sports.io

数据管线:
  1. football-data.org → 赛程发现 + 实时比分
  2. BDL GOAT         → 首发阵容 + 阵型 + 替补 (已订阅 $39.99/月)
  3. api-sports.io    → 球员照片
  4. players_2026.json→ 俱乐部、身价、伤停

返回: 完整 AR 覆盖数据(首发 11 人 + 阵型 + 比分 + 替补)

用法:
  python ar_data_service.py                    # 当前直播比赛
  python ar_data_service.py --match-id 537327  # 指定FD比赛ID
  python ar_data_service.py --json             # 纯JSON
"""

import sys, io, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import argparse, urllib.request
from pathlib import Path
from datetime import datetime, timezone, timedelta

TOOLS_DIR = Path(__file__).resolve().parent
MODULE_DIR = TOOLS_DIR.parent
DATA_DIR = MODULE_DIR / "data" / "apisports"
PROJECT_ROOT = MODULE_DIR.parent
PLAYERS_2026_PATH = PROJECT_ROOT / "MainApp" / "app" / "src" / "main" / "assets" / "players_2026.json"

# ═══ API 配置 ═══
FD_KEY = "7170cad73b4549a3851b4f19e77715bf"
FD_BASE = "https://api.football-data.org/v4"
BDL_KEY = "04e88856-99e8-49d9-a2af-5818b2b68f14"
BDL_BASE = "https://api.balldontlie.io/fifa/worldcup/v1"
AP_KEY = "a1171ce3f1e015c2deb20a3292be9a40"
AP_BASE = "https://v3.football.api-sports.io"
TZ_BJ = timezone(timedelta(hours=8))

# ═══ 球队名称映射 ═══
TEAM_NAME_MAP = {
    "Czech Republic": "Czechia", "Bosnia-Herzegovina": "Bosnia",
    "Bosnia & Herzegovina": "Bosnia", "United States": "USA",
    "Korea Republic": "South Korea", "Côte d'Ivoire": "Ivory Coast",
    "Cape Verde Islands": "Cape Verde", "Congo DR": "DR Congo",
    "IR Iran": "Iran", "Türkiye": "Turkey",
}

# ═══ BDL 球队 ID 映射 (48队) ═══
BDL_TEAM_IDS = {
    "Mexico": 1, "South Africa": 2, "South Korea": 3, "Czechia": 4,
    "Canada": 5, "Bosnia & Herzegovina": 6, "Qatar": 7, "Switzerland": 8,
    "Brazil": 9, "Morocco": 10, "Haiti": 11, "Scotland": 12,
    "USA": 13, "Paraguay": 14, "Australia": 15, "Türkiye": 16,
    "Germany": 17, "Curaçao": 18, "Côte d'Ivoire": 19, "Ecuador": 20,
    "Netherlands": 21, "Japan": 22, "Sweden": 23, "Tunisia": 24,
    "Belgium": 25, "Egypt": 26, "Iran": 27, "New Zealand": 28,
    "Spain": 29, "Cabo Verde": 30, "Saudi Arabia": 31, "Uruguay": 32,
    "France": 33, "Senegal": 34, "Iraq": 35, "Norway": 36,
    "Argentina": 37, "Algeria": 38, "Austria": 39, "Jordan": 40,
    "Portugal": 41, "Colombia": 42, "Uzbekistan": 43, "DR Congo": 44,
    "England": 45, "Croatia": 46, "Ghana": 47, "Panama": 48,
}


def fd_get(endpoint):
    url = f"{FD_BASE}/{endpoint}"
    req = urllib.request.Request(url, headers={"X-Auth-Token": FD_KEY})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())

def bdl_get(endpoint):
    url = f"{BDL_BASE}/{endpoint}"
    req = urllib.request.Request(url, headers={"Authorization": BDL_KEY})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())

def ap_search_team(name):
    url = f"{AP_BASE}/teams?search={urllib.request.quote(name)}"
    req = urllib.request.Request(url, headers={"x-apisports-key": AP_KEY})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    for t in data.get("response", []):
        if t["team"]["national"] and "W" not in t["team"]["name"]:
            return t["team"]["id"]
    return None

def ap_get_squad(team_id):
    for f in DATA_DIR.glob("*.json"):
        if f.stem == "team_index": continue
        squad = json.loads(f.read_text())
        if squad.get("team", {}).get("id") == team_id:
            return squad["players"]
    try:
        url = f"{AP_BASE}/players/squads?team={team_id}"
        req = urllib.request.Request(url, headers={"x-apisports-key": AP_KEY})
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())["response"][0]["players"]
    except:
        return []


# ═══ players_2026.json 增强 ═══
_PLAYERS_DB = None

def get_players_db():
    global _PLAYERS_DB
    if _PLAYERS_DB is None and PLAYERS_2026_PATH.exists():
        db = {}
        for team in json.loads(PLAYERS_2026_PATH.read_text(encoding='utf-8'))["teams"]:
            db[team["name"]] = {p["jerseyNumber"]: p for p in team["players"]}
        _PLAYERS_DB = db
    return _PLAYERS_DB or {}

def enrich_player(fd_team_name, number):
    db = get_players_db()
    mapped = TEAM_NAME_MAP.get(fd_team_name, fd_team_name)
    team_data = db.get(mapped, {})
    p = team_data.get(number)
    if p:
        return {"club": p.get("club", ""), "market_value_mil": p.get("market_value_mil", 0),
                "injured": p.get("injured", False), "pos_short": p.get("position", "")}
    return {"club": "", "market_value_mil": 0, "injured": False, "pos_short": ""}


# ═══ BDL 比赛ID 查找 ═══
def find_bdl_match_id(home_name, away_name):
    """通过两个队名找到 BDL 比赛ID"""
    home_bdl = BDL_TEAM_IDS.get(home_name) or BDL_TEAM_IDS.get(TEAM_NAME_MAP.get(home_name))
    away_bdl = BDL_TEAM_IDS.get(away_name) or BDL_TEAM_IDS.get(TEAM_NAME_MAP.get(away_name))
    if not home_bdl or not away_bdl:
        return None
    data = bdl_get(f"matches?seasons[]=2026&team_ids[]={home_bdl}&team_ids[]={away_bdl}&per_page=10")
    for m in data.get("data", []):
        if m["home_team"]["id"] == home_bdl and m["away_team"]["id"] == away_bdl:
            return m["id"]
    return None


def get_live_match():
    matches = fd_get("competitions/2000/matches?status=LIVE")
    live = matches.get("matches", [])
    if live: return live[0]
    upcoming = fd_get("competitions/2000/matches?status=SCHEDULED").get("matches", [])
    if upcoming:
        m = upcoming[0]
        utc = datetime.fromisoformat(m["utcDate"].replace("Z", "+00:00"))
        m["_status_note"] = f"即将: {utc.astimezone(TZ_BJ).strftime('%H:%M')} 北京时间"
        return m
    return None


# ═══ 核心 ═══
def build_match_data(fd_match):
    home = fd_match["homeTeam"]
    away = fd_match["awayTeam"]
    score = fd_match["score"]

    # 1. BDL: 首发阵容 + 阵型
    bdl_mid = find_bdl_match_id(home["name"], away["name"])
    lineup_data = None
    if bdl_mid:
        try:
            lineup_data = bdl_get(f"match_lineups?match_ids[]={bdl_mid}&per_page=50")
        except:
            pass

    def extract_lineup(lineups, team_id, team_name):
        """从 BDL lineup 数据提取首发+替补, 并补上照片+增强数据"""
        team = [l for l in lineups if l["team_id"] == team_id]
        starters = sorted([l for l in team if l.get("is_starter")],
                         key=lambda x: x.get("shirt_number", 99))
        subs = sorted([l for l in team if l.get("is_substitute")],
                     key=lambda x: x.get("shirt_number", 99))
        formation = starters[0].get("formation", "?") if starters else "?"

        return {
            "formation": formation,
            "starters": [{
                "number": l["shirt_number"],
                "name": l["player"]["name"],
                "position": l.get("position", "?"),
                "photo": "",  # 稍后填充
                **enrich_player(team_name, l["shirt_number"]),
            } for l in starters],
            "substitutes": [{
                "number": l["shirt_number"],
                "name": l["player"]["name"],
            } for l in subs],
        }

    # 构建 lineup（来自 BDL）
    if lineup_data and lineup_data.get("data"):
        home_lu = extract_lineup(lineup_data["data"], 
            BDL_TEAM_IDS.get(home["name"]) or BDL_TEAM_IDS.get(TEAM_NAME_MAP.get(home["name"], home["name"])),
            home["name"])
        away_lu = extract_lineup(lineup_data["data"],
            BDL_TEAM_IDS.get(away["name"]) or BDL_TEAM_IDS.get(TEAM_NAME_MAP.get(away["name"], away["name"])),
            away["name"])
    else:
        # 无 BDL 数据时退回全队名单
        home_lu = away_lu = None

    # 2. api-sports.io: 球员照片 (加载全队, 按号码匹配到首发球员)
    home_ap_id = ap_search_team(home["name"])
    away_ap_id = ap_search_team(away["name"])
    home_squad = ap_get_squad(home_ap_id) if home_ap_id else []
    away_squad = ap_get_squad(away_ap_id) if away_ap_id else []

    def attach_photos(lineup, squad):
        if not lineup or not squad: return
        photo_map = {p["number"]: p.get("photo", "") for p in squad if p.get("number")}
        for st in lineup.get("starters", []):
            st["photo"] = photo_map.get(st["number"], "")

    attach_photos(home_lu, home_squad)
    attach_photos(away_lu, away_squad)

    # 3. 构建输出
    def player_list(lineup):
        """BDL 首发 + 替补合成完整列表 (如果有 BDL)"""
        if not lineup: return []
        return lineup["starters"] + [
            {**s, "position": "?", "photo": "", "club": "", "market_value_mil": 0, "injured": False, "pos_short": ""}
            for s in lineup.get("substitutes", [])
        ]

    return {
        "match_id": fd_match["id"],
        "bdl_match_id": bdl_mid,
        "status": fd_match.get("status", "?"),
        "stage": fd_match.get("stage", "?"),
        "group": fd_match.get("group", ""),
        "home": {
            "name": home["name"],
            "short": home.get("shortName", home["name"]),
            "score": score["fullTime"]["home"],
            **({"formation": home_lu["formation"]} if home_lu else {}),
            **({"starters": home_lu["starters"]} if home_lu else {}),
            **({"substitutes": home_lu["substitutes"]} if home_lu else {}),
            "players": player_list(home_lu) if home_lu else [
                {"number": p.get("number"), "name": p.get("name", "?"),
                 "position": p.get("position", "?"), "photo": p.get("photo", ""),
                 **enrich_player(home["name"], p.get("number"))}
                for p in home_squad
            ],
        },
        "away": {
            "name": away["name"],
            "short": away.get("shortName", away["name"]),
            "score": score["fullTime"]["away"],
            **({"formation": away_lu["formation"]} if away_lu else {}),
            **({"starters": away_lu["starters"]} if away_lu else {}),
            **({"substitutes": away_lu["substitutes"]} if away_lu else {}),
            "players": player_list(away_lu) if away_lu else [
                {"number": p.get("number"), "name": p.get("name", "?"),
                 "position": p.get("position", "?"), "photo": p.get("photo", ""),
                 **enrich_player(away["name"], p.get("number"))}
                for p in away_squad
            ],
        },
        "_meta": {
            "source": "BDL GOAT (lineup) + api-sports.io (photos) + players_2026.json (enhanced)",
            "generated_at": datetime.now(TZ_BJ).isoformat(),
        }
    }


# ═══ 显示 ═══
def format_display(data):
    for side_key in ["home", "away"]:
        side = data[side_key]
        print(f"\n{'='*55}")
        print(f"  {side['name']}  {side.get('formation', '?')}")
        print(f"{'='*55}")

        starters = side.get("starters", side.get("players", []))
        subs = side.get("substitutes", [])

        print(f"  首发 {len(starters)}:")
        for p in starters[:11]:
            club = f"[{p.get('club','')}]" if p.get('club') else ""
            print(f"    #{p['number']:>2} {p['name']:<23s} {p.get('position','?')} {club}")

        if subs:
            print(f"  替补 {len(subs)}:")
            names = [f"#{s['number']} {s['name']}" for s in subs[:8]]
            print(f"    {', '.join(names)}{'...' if len(subs)>8 else ''}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--match-id", type=int)
    p.add_argument("--json", action="store_true")
    p.add_argument("--live", action="store_true")
    args = p.parse_args()

    print("=" * 55)
    print("⚽ AR 数据服务 V2 (BDL + FD + AP)")
    print("=" * 55)

    if args.match_id:
        fd_match = fd_get(f"matches/{args.match_id}")
    else:
        fd_match = get_live_match()
        if not fd_match:
            print("❌ 无直播或无即将开始的比赛")
            sys.exit(1)

    data = build_match_data(fd_match)

    if args.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print(f"\n🏟 {data['home']['name']} {data['home']['score']}-{data['away']['score']} "
              f"{data['away']['name']}  [{data['status']}] {data.get('group','')}")
        format_display(data)
        out_path = DATA_DIR / "ar_live_data.json"
        out_path.write_text(json.dumps(data, ensure_ascii=False, indent=2))
        print(f"\n💾 {out_path}")
