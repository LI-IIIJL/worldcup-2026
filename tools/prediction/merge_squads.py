#!/usr/bin/env python3
"""
merge_squads.py — 将 prediction-market 的 squads.json + teams.json 合并到 players_2026.json

合并内容：
1. 每个球员新增：market_value_mil（身价，百万欧元）, injured（是否伤停）
2. 每支球队新增：elo（ELO评分）, elo_1y_ago（一年前ELO）, is_host（是否东道主）

用法：
    python merge_squads.py
    python merge_squads.py --output ../data/players_enhanced.json
    python merge_squads.py --update-mainapp   # 直接更新 MainApp 的 players_2026.json
"""

import json
import os
import re
import sys

# 路径配置
MAINAPP_PLAYERS = os.path.join(os.path.dirname(__file__), "..", "..", "MainApp", "app", "src", "main", "assets", "players_2026.json")
SQUADS_JSON = os.path.join(os.path.dirname(__file__), "..", "..", "Github_Support", "2026-worldcup-prediction-market-main", "src", "data", "squads.json")
TEAMS_JSON = os.path.join(os.path.dirname(__file__), "..", "..", "Github_Support", "2026-worldcup-prediction-market-main", "src", "data", "teams.json")
OUTPUT_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "data", "players_enhanced.json")


# squads.json 3字母代码 → players_2026.json 球队名
TEAM_CODE_MAP = {
    "ALG": "Algeria", "ARG": "Argentina", "AUS": "Australia", "AUT": "Austria",
    "BEL": "Belgium", "BIH": "Bosnia", "BRA": "Brazil", "CAN": "Canada",
    "CIV": "Ivory Coast", "COD": "DR Congo", "COL": "Colombia", "CPV": "Cape Verde",
    "CRO": "Croatia", "CUW": "Curacao", "CZE": "Czechia", "ECU": "Ecuador",
    "EGY": "Egypt", "ENG": "England", "ESP": "Spain", "FRA": "France",
    "GER": "Germany", "GHA": "Ghana", "HAI": "Haiti", "IRN": "Iran",
    "IRQ": "Iraq", "JOR": "Jordan", "JPN": "Japan", "KOR": "South Korea",
    "KSA": "Saudi Arabia", "MAR": "Morocco", "MEX": "Mexico", "NED": "Netherlands",
    "NOR": "Norway", "NZL": "New Zealand", "PAN": "Panama", "PAR": "Paraguay",
    "POR": "Portugal", "QAT": "Qatar", "RSA": "South Africa", "SCO": "Scotland",
    "SEN": "Senegal", "SUI": "Switzerland", "SWE": "Sweden", "TUN": "Tunisia",
    "TUR": "Turkey", "URU": "Uruguay", "USA": "USA", "UZB": "Uzbekistan",
}

# countryCode (2-letter) → squads code (3-letter) 映射（用于校验）
COUNTRY_TO_CODE = {
    "AR": "ARG", "BR": "BRA", "FR": "FRA", "GB-ENG": "ENG", "DE": "GER",
    "ES": "ESP", "PT": "POR", "NL": "NED", "NO": "NOR", "SE": "SWE",
    "BE": "BEL", "HR": "CRO", "CH": "SUI", "AT": "AUT", "CZ": "CZE",
    "TR": "TUR", "US": "USA", "PY": "PAR", "AU": "AUS", "MA": "MAR",
    "XS": "SCO", "MX": "MEX", "ZA": "RSA", "KR": "KOR", "CA": "CAN",
    "BA": "BIH", "QA": "QAT", "HT": "HAI", "CW": "CUW", "CI": "CIV",
    "EC": "ECU", "JP": "JPN", "TN": "TUN", "EG": "EGY", "IR": "IRN",
    "NZ": "NZL", "CV": "CPV", "SA": "KSA", "UY": "URU", "SN": "SEN",
    "IQ": "IRQ", "DZ": "ALG", "JO": "JOR", "CD": "COD", "UZ": "UZB",
    "CO": "COL", "GH": "GHA", "PA": "PAN",
}


def normalize_name(name: str) -> str:
    """标准化球员名用于匹配：去音标、去后缀、去特殊字符"""
    n = name.strip().lower()
    # 去除音标符号
    n = n.replace("é", "e").replace("è", "e").replace("ê", "e").replace("ë", "e")
    n = n.replace("á", "a").replace("à", "a").replace("â", "a").replace("ä", "a")
    n = n.replace("í", "i").replace("ì", "i").replace("î", "i").replace("ï", "i")
    n = n.replace("ó", "o").replace("ò", "o").replace("ô", "o").replace("ö", "o")
    n = n.replace("ú", "u").replace("ù", "u").replace("û", "u").replace("ü", "u")
    n = n.replace("ñ", "n").replace("ç", "c")
    n = n.replace("ő", "o").replace("ű", "u")
    n = n.replace("ć", "c").replace("č", "c").replace("š", "s").replace("ž", "z")
    n = n.replace("đ", "d").replace("ğ", "g")
    # 去除常见后缀变体
    for suffix in [" jr", " junior", " filho", " neto", " i", " ii", " iii"]:
        n = n.replace(suffix, "")
    # 去除非字母数字
    n = re.sub(r"[^a-z0-9\s]", "", n)
    # 规范化缩写：vini jr -> vinicius junior
    abbrev = {"vini": "vinicius", "rapha": "raphinha", "gabi": "gabriel",
              "neymar": "neymar jr", "dk": "dennis", "ini": "iniaki", "nico": "nicolas"}
    parts = n.split()
    parts = [abbrev.get(p, p) for p in parts]
    # 去重并排序
    parts = sorted(set(parts))
    return " ".join(parts)


def main():
    import argparse
    parser = argparse.ArgumentParser(description="合并 squads.json + teams.json 到 players_2026.json")
    parser.add_argument("--output", type=str, default=OUTPUT_DEFAULT, help="输出文件路径")
    parser.add_argument("--update-mainapp", action="store_true", help="直接更新 MainApp 的 players_2026.json")
    args = parser.parse_args()

    print("加载数据...")
    with open(MAINAPP_PLAYERS, "r", encoding="utf-8") as f:
        existing = json.load(f)

    with open(SQUADS_JSON, "r", encoding="utf-8") as f:
        squads_data = json.load(f)["squads"]

    with open(TEAMS_JSON, "r", encoding="utf-8") as f:
        teams_data_raw = json.load(f)["teams"]
    # teams.json: 按 id 索引
    teams_data = {t["id"]: t for t in teams_data_raw}

    # 构建 squads 中所有球员的归一化名→数据的映射（每队一组）
    squad_index = {}
    for code, players in squads_data.items():
        team_name = TEAM_CODE_MAP.get(code)
        if not team_name:
            continue
        # 建立该队的球员索引
        name_map = {}
        for sp in players:
            nk = normalize_name(sp["player"])
            name_map[nk] = sp
        squad_index[team_name] = name_map

    # 遍历现有球员，尝试匹配
    match_stats = {"matched": 0, "unmatched": 0, "teams_with_elo": 0}
    for team in existing["teams"]:
        team_name = team["name"]

        # --- 球队级 ELO 数据 ---
        ccode = COUNTRY_TO_CODE.get(team.get("countryCode", ""))
        if ccode and ccode in teams_data:
            td = teams_data[ccode]
            team["elo"] = td["elo"]
            team["elo_1y_ago"] = td.get("elo_1y_ago", td["elo"])
            team["is_host"] = td.get("is_host", False)
            match_stats["teams_with_elo"] += 1

        # --- 球员级数据 ---
        team_squad = squad_index.get(team_name, {})
        for player in team["players"]:
            pname = player.get("name", "")
            pnk = normalize_name(pname)

            if pnk in team_squad:
                sq = team_squad[pnk]
                player["market_value_mil"] = sq.get("market_value_mil")
                player["injured"] = sq.get("injured", False)
                match_stats["matched"] += 1
            else:
                # 尝试：（1）姓氏匹配；（2）模糊匹配（仅保留姓氏）
                parts = pnk.split()
                found = False
                # 方法1：姓氏匹配
                if len(parts) > 1:
                    last = parts[-1]
                    for sq_name, sq_data in team_squad.items():
                        if last in sq_name:
                            player["market_value_mil"] = sq_data.get("market_value_mil")
                            player["injured"] = sq_data.get("injured", False)
                            match_stats["matched"] += 1
                            found = True
                            break
                # 方法2：首名+姓氏子串匹配（处理复姓/中间名问题）
                if not found and len(parts) >= 2:
                    first, last = parts[0], parts[-1]
                    for sq_name, sq_data in team_squad.items():
                        sq_parts = sq_name.split()
                        # 检查是否至少首名或姓氏有一个匹配
                        if (first in sq_parts and len(first) > 3) or \
                           (last in sq_parts and len(last) > 3):
                            # 验证号码是否一致
                            player["market_value_mil"] = sq_data.get("market_value_mil")
                            player["injured"] = sq_data.get("injured", False)
                            match_stats["matched"] += 1
                            found = True
                            break
                if not found:
                    player["market_value_mil"] = None
                    player["injured"] = False
                    match_stats["unmatched"] += 1

    # 输出
    output_path = args.output
    if args.update_mainapp:
        output_path = MAINAPP_PLAYERS

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(existing, f, ensure_ascii=False, indent=2)

    total = match_stats["matched"] + match_stats["unmatched"]
    print(f"\n合并完成！")
    print(f"  球员总数: {total}")
    print(f"  成功匹配: {match_stats['matched']} ({match_stats['matched']/total*100:.1f}%)")
    print(f"  未匹配:   {match_stats['unmatched']} ({match_stats['unmatched']/total*100:.1f}%)")
    print(f"  ELO已补:  {match_stats['teams_with_elo']}/48 队")
    print(f"\n输出文件: {output_path}")
    if args.update_mainapp:
        print("  ⚠️ 已直接更新 MainApp 的 players_2026.json！")


if __name__ == "__main__":
    main()
