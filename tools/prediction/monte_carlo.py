#!/usr/bin/env python3
"""
monte_carlo.py — 蒙特卡洛比赛预测引擎

基于 ELO + 泊松模型，从 prediction-market 项目移植的核心算法。
生成每场比赛的统计概率（胜/平/负），与 LLM 文本预测形成"双保险"。

数据来源：teams.json（ELO评分） + matches.json（赛程）

用法：
    python monte_carlo.py                              # 预测所有104场比赛
    python monte_carlo.py --match "Argentina vs Algeria" # 指定单场
    python monte_carlo.py --sims 50000                   # 提高模拟精度
    python monte_carlo.py --output ../data/mc_predictions.json
"""

import argparse
import json
import math
import os
import random
import sys
from collections import Counter
from dataclasses import dataclass, asdict
from typing import Optional

# ===== 核心常量（移植自 TypeScript sim 引擎） =====
BASE_LAMBDA = 1.30       # 基础进球期望
ALPHA = 0.18              # ELO 差敏感度
LAMBDA_MIN = 0.15
LAMBDA_MAX = 6.0
HOST_BONUS = 100          # 东道主优势 ELO 加成
RECENT_ALPHA = 0.20       # 近期状态融合系数
RECENT_CAP_ELO = 150      # 近期状态调整上限

# ===== 路径配置 =====
SCRIPT_DIR = os.path.dirname(__file__)
TEAMS_JSON = os.path.join(SCRIPT_DIR, "..", "..", "Github_Support",
    "2026-worldcup-prediction-market-main", "src", "data", "teams.json")
MATCHES_JSON = os.path.join(SCRIPT_DIR, "..", "..", "MainApp", "app", "src", "main", "assets", "matches.json")
OUTPUT_DEFAULT = os.path.join(SCRIPT_DIR, "..", "data", "mc_predictions.json")

# 主队国（适用东道主优势）
HOST_TEAMS = {"USA", "Mexico", "Canada"}

# 球队名 → 3字母代码 映射
TEAM_NAME_TO_CODE = {
    "Argentina": "ARG", "Brazil": "BRA", "France": "FRA", "England": "ENG",
    "Germany": "GER", "Spain": "ESP", "Portugal": "POR", "Netherlands": "NED",
    "Norway": "NOR", "Sweden": "SWE", "Belgium": "BEL", "Croatia": "CRO",
    "Switzerland": "SUI", "Austria": "AUT", "Czechia": "CZE", "Turkey": "TUR",
    "USA": "USA", "United States": "USA",
    "Paraguay": "PAR", "Australia": "AUS", "Morocco": "MAR",
    "Scotland": "SCO", "Mexico": "MEX", "South Africa": "RSA", "South Korea": "KOR",
    "Canada": "CAN",
    "Bosnia": "BIH", "Bosnia and Herzegovina": "BIH",
    "Qatar": "QAT", "Haiti": "HAI",
    "Curacao": "CUW", "Ivory Coast": "CIV", "Ecuador": "ECU", "Japan": "JPN",
    "Tunisia": "TUN", "Egypt": "EGY", "Iran": "IRN", "New Zealand": "NZL",
    "Cape Verde": "CPV", "Saudi Arabia": "KSA", "Uruguay": "URU", "Senegal": "SEN",
    "Iraq": "IRQ", "Algeria": "ALG", "Jordan": "JOR",
    "DR Congo": "COD", "Democratic Republic of the Congo": "COD",
    "Uzbekistan": "UZB", "Colombia": "COL", "Ghana": "GHA", "Panama": "PAN",
}

# 淘汰赛 round 关键词（用于判断是否使用东道主优势）
KO_ROUND_KEYWORDS = {"1/16决赛", "1/8决赛", "1/4决赛", "半决赛", "决赛"}


@dataclass
class TeamData:
    """球队数据"""
    id: str
    name_en: str
    elo: int
    elo_1y_ago: Optional[int] = None
    is_host: bool = False


@dataclass
class MatchPrediction:
    """单场比赛预测结果"""
    match_id: int
    home: str
    away: str
    group: str
    round: str
    home_win_prob: float    # 主队胜率 (%)
    draw_prob: float        # 平局概率 (%)
    away_win_prob: float    # 客队胜率 (%)
    home_elo: int
    away_elo: int
    home_lambda: float      # 主队预期进球
    away_lambda: float      # 客队预期进球
    most_likely_score: str  # 最可能比分
    source: str = "monte_carlo"


def load_teams() -> dict[str, TeamData]:
    """加载 teams.json 到 TeamData 字典"""
    with open(TEAMS_JSON, "r", encoding="utf-8") as f:
        raw = json.load(f)["teams"]

    teams = {}
    # 为每支球队注册别名，以兼容 matches.json 中不同阶段使用不同队名
    extra_aliases = {}
    for t in raw:
        name = t.get("name_en", "")
        # 修正 teams.json 中的名称 → matches.json 中使用的名称
        name_map = {
            "Côte d'Ivoire": "Ivory Coast",
            "Cabo Verde": "Cape Verde",
        }
        display_name = name_map.get(name, name)
        teams[display_name] = TeamData(
            id=t["id"],
            name_en=display_name,
            elo=t["elo"],
            elo_1y_ago=t.get("elo_1y_ago"),
            is_host=t.get("is_host", False),
        )
        # 注册别名（处理 group vs knockout 命名不一致）
        if name == "United States":
            extra_aliases["USA"] = teams[display_name]
        elif name == "Bosnia & Herzegovina":
            extra_aliases["Bosnia"] = teams[display_name]
            extra_aliases["Bosnia and Herzegovina"] = teams[display_name]
        elif name == "DR Congo":
            extra_aliases["Democratic Republic of the Congo"] = teams[display_name]

    teams.update(extra_aliases)
    return teams


def load_matches() -> list[dict]:
    """加载 matches.json（新格式：平铺数组，含 104 场）"""
    with open(MATCHES_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)
    # 新格式：直接是数组；旧格式：{matches: [...]} 兜底
    if isinstance(data, list):
        return data
    return data.get("matches", data)


def recent_form_adjustment(team: TeamData) -> int:
    """近期状态调整：过去12个月ELO变化的20%，上限±150"""
    if team.elo_1y_ago is None:
        return 0
    adj = int(RECENT_ALPHA * (team.elo - team.elo_1y_ago))
    return max(-RECENT_CAP_ELO, min(RECENT_CAP_ELO, adj))


def effective_elo(team: TeamData) -> int:
    """有效ELO = 基础ELO + 近期状态调整"""
    return team.elo + recent_form_adjustment(team)


def host_bonus(team_name: str, is_knockout: bool) -> int:
    """东道主优势：小组赛阶段 +100 ELO"""
    if is_knockout:
        return 0
    return HOST_BONUS if team_name in HOST_TEAMS else 0


def lambda_for(elo_self: int, elo_opp: int, bonus: int) -> float:
    """计算预期进球数 λ = clamp(1.30 + 0.18 * (elo_diff + bonus) / 100, 0.15, 6.0)"""
    raw = BASE_LAMBDA + (ALPHA * (elo_self - elo_opp + bonus)) / 100
    return max(LAMBDA_MIN, min(LAMBDA_MAX, raw))


def sample_poisson(lambda_: float) -> int:
    """泊松采样（Knuth 算法）"""
    L = math.exp(-lambda_)
    k = 0
    p = 1.0
    while True:
        k += 1
        p *= random.random()
        if p <= L:
            return k - 1
        if k > 50:
            return k - 1  # 安全保护


def predict_match(
    home_name: str,
    away_name: str,
    round_name: str,
    match_type: str,
    match_id: int,
    group: str,
    status: str,
    home_score_actual: Optional[int],
    away_score_actual: Optional[int],
    teams: dict[str, TeamData],
    num_sims: int = 10000,
) -> MatchPrediction:
    """蒙特卡洛模拟单场比赛"""
    is_ko = match_type == "knockout" or round_name in KO_ROUND_KEYWORDS

    # TBD 比赛：对手未定，均等概率
    if home_name == "TBD" or away_name == "TBD":
        return MatchPrediction(
            match_id=match_id, home=home_name, away=away_name,
            group=group, round=round_name,
            home_win_prob=33.3, draw_prob=33.3, away_win_prob=33.3,
            home_elo=0, away_elo=0,
            home_lambda=0, away_lambda=0,
            most_likely_score="TBD",
        )

    # 球队未找到：均等概率
    home_team = teams.get(home_name)
    away_team = teams.get(away_name)
    if not home_team or not away_team:
        return MatchPrediction(
            match_id=match_id, home=home_name, away=away_name,
            group=group, round=round_name,
            home_win_prob=33.3, draw_prob=33.3, away_win_prob=33.3,
            home_elo=0, away_elo=0,
            home_lambda=0, away_lambda=0,
            most_likely_score="0-0",
        )

    elo_h = effective_elo(home_team)
    elo_a = effective_elo(away_team)
    bonus_h = host_bonus(home_name, is_ko)
    bonus_a = host_bonus(away_name, is_ko)

    lambda_h = lambda_for(elo_h, elo_a, bonus_h)
    lambda_a = lambda_for(elo_a, elo_h, bonus_a)

    # 蒙特卡洛模拟
    wins_h = 0
    draws = 0
    wins_a = 0
    score_counts = Counter()

    for _ in range(num_sims):
        gh = sample_poisson(lambda_h)
        ga = sample_poisson(lambda_a)
        if gh > ga:
            wins_h += 1
        elif gh == ga:
            draws += 1
        else:
            wins_a += 1
        score_counts[f"{gh}-{ga}"] += 1

    most_likely = score_counts.most_common(1)[0][0]

    # 已完赛：用蒙特卡洛概率，但比分用实际结果（看起来统一）
    if status == "FINISHED" and home_score_actual is not None and away_score_actual is not None:
        actual_score = f"{home_score_actual}-{away_score_actual}"
    else:
        actual_score = most_likely

    return MatchPrediction(
        match_id=match_id,
        home=home_name,
        away=away_name,
        group=group,
        round=round_name,
        home_win_prob=round(wins_h / num_sims * 100, 1),
        draw_prob=round(draws / num_sims * 100, 1),
        away_win_prob=round(wins_a / num_sims * 100, 1),
        home_elo=elo_h,
        away_elo=elo_a,
        home_lambda=round(lambda_h, 3),
        away_lambda=round(lambda_a, 3),
        most_likely_score=actual_score,
    )


def main():
    parser = argparse.ArgumentParser(
        description="蒙特卡洛比赛预测引擎（基于 ELO + 泊松模型）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--sims", type=int, default=10000, help="每场比赛模拟次数（默认: 10000）")
    parser.add_argument("--match", type=str, help="预测单场比赛，格式: 'Home vs Away'")
    parser.add_argument("--output", type=str, default=OUTPUT_DEFAULT, help="输出 JSON 路径")
    parser.add_argument("--pretty", action="store_true", help="美化输出")
    parser.add_argument("--seed", type=int, default=42, help="随机种子（默认: 42）")
    args = parser.parse_args()

    random.seed(args.seed)

    print(f"加载数据...", file=sys.stderr)
    teams = load_teams()
    all_matches = load_matches()

    print(f"ELO 数据: {len(teams)} 队", file=sys.stderr)
    print(f"赛程: {len(all_matches)} 场", file=sys.stderr)
    print(f"模拟次数/场: {args.sims:,}", file=sys.stderr)
    print(file=sys.stderr)

    # 过滤或全部
    if args.match:
        parts = args.match.split(" vs ")
        if len(parts) != 2:
            print(f"错误：--match 格式应为 'Home vs Away'，收到: {args.match}", file=sys.stderr)
            sys.exit(1)
        home, away = parts[0].strip(), parts[1].strip()
        matches_to_run = [m for m in all_matches
                         if m.get("homeTeam", m.get("home", "")) == home
                         and m.get("awayTeam", m.get("away", "")) == away]
        if not matches_to_run:
            print(f"错误：未找到比赛 {home} vs {away}", file=sys.stderr)
            sys.exit(1)
        label = f"单场: {home} vs {away}"
    else:
        matches_to_run = all_matches
        label = f"全部 {len(all_matches)} 场"

    print(f"模拟 {label}...", file=sys.stderr)

    results = []
    for i, m in enumerate(matches_to_run):
        home_name = m.get("homeTeam", m.get("home", ""))
        away_name = m.get("awayTeam", m.get("away", ""))
        round_name = m.get("round", m.get("type", "小组赛"))
        match_type = m.get("type", "group")
        match_id = m["id"]
        match_group = m.get("group", "")
        status = m.get("status", "")
        home_score_actual = m.get("homeScore")
        away_score_actual = m.get("awayScore")

        pred = predict_match(
            home_name=home_name,
            away_name=away_name,
            round_name=round_name,
            match_type=match_type,
            match_id=match_id,
            group=match_group,
            status=status,
            home_score_actual=home_score_actual,
            away_score_actual=away_score_actual,
            teams=teams,
            num_sims=args.sims,
        )
        results.append(asdict(pred))

        # 进度
        if (i + 1) % 10 == 0 or i == 0 or i == len(matches_to_run) - 1:
            pct = (i + 1) / len(matches_to_run) * 100
            print(f"  [{i+1}/{len(matches_to_run)}] {pct:.0f}% - {home_name:25s} vs {away_name:<25s}: "
                  f"{pred.home_win_prob:5.1f}% / {pred.draw_prob:4.1f}% / {pred.away_win_prob:4.1f}%",
                  file=sys.stderr)

    # 输出
    output = {
        "meta": {
            "model": "ELO + Poisson Monte Carlo",
            "source": "prediction-market 项目 (Dexoryn)",
            "num_simulations_per_match": args.sims,
            "num_matches": len(results),
            "base_lambda": BASE_LAMBDA,
            "alpha": ALPHA,
            "host_bonus_elo": HOST_BONUS,
            "recent_alpha": RECENT_ALPHA,
            "recent_cap_elo": RECENT_CAP_ELO,
        },
        "predictions": results,
    }

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2 if args.pretty else None)

    print(f"\n完成！({len(results)} 场)", file=sys.stderr)
    print(f"输出: {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
