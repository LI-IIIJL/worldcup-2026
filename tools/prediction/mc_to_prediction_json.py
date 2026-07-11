#!/usr/bin/env python3
"""
mc_to_prediction_json.py — 将 mc_predictions.json (MC引擎) 转换为 App 可用的 predictions.json

数据源: Predict_Module/data/mc_predictions.json
输出:   MainApp/app/src/main/assets/predictions.json

映射规则:
  MC字段              → App字段
  match_id            → matchId
  home                → teamA.name
  away                → teamB.name
  home_win_prob (取整) → teamA.winProb
  draw_prob (取整)     → draw
  away_win_prob (取整) → teamB.winProb
  most_likely_score    → predictedScore
  ELO差                → confidence (高/中/低)
  基于MC数据生成        → keyFactors (规则模板)
                        → analysis (占位文本)
                        → playersToWatch (空，留给LLM)
  cnName映射表          → teamA/teamB.cnName

用法:
    python mc_to_prediction_json.py
    python mc_to_prediction_json.py --output ../data/sample.json
"""

import json
import os
import sys
import math

# ======================== 中文队名映射 (48队) ========================
CN_NAME_MAP = {
    "Mexico": "墨西哥", "South Africa": "南非", "South Korea": "韩国", "Czechia": "捷克",
    "Canada": "加拿大", "Bosnia": "波黑", "Qatar": "卡塔尔", "Switzerland": "瑞士",
    "Brazil": "巴西", "Morocco": "摩洛哥", "Haiti": "海地", "Scotland": "苏格兰",
    "USA": "美国", "Paraguay": "巴拉圭", "Australia": "澳大利亚", "Turkey": "土耳其",
    "Germany": "德国", "Curacao": "库拉索", "Ivory Coast": "科特迪瓦", "Ecuador": "厄瓜多尔",
    "Netherlands": "荷兰", "Japan": "日本", "Sweden": "瑞典", "Tunisia": "突尼斯",
    "Belgium": "比利时", "Egypt": "埃及", "Iran": "伊朗", "New Zealand": "新西兰",
    "Spain": "西班牙", "Cape Verde": "佛得角", "Saudi Arabia": "沙特阿拉伯", "Uruguay": "乌拉圭",
    "France": "法国", "Senegal": "塞内加尔", "Iraq": "伊拉克", "Norway": "挪威",
    "Argentina": "阿根廷", "Algeria": "阿尔及利亚", "Austria": "奥地利", "Jordan": "约旦",
    "Portugal": "葡萄牙", "DR Congo": "刚果(金)", "Uzbekistan": "乌兹别克斯坦", "Colombia": "哥伦比亚",
    "England": "英格兰", "Croatia": "克罗地亚", "Ghana": "加纳", "Panama": "巴拿马",
}


def get_cn_name(name):
    """获取中文队名，占位符直接用原值"""
    return CN_NAME_MAP.get(name, name)


# ======================== 淘汰赛占位队名 ========================
# 1/16决赛: 对阵是小组名组合，直接保持原样
# 1/8决赛+: 使用 W{id} / L{id} 格式，保持原样


def is_ko_round(round_name):
    """判断是否为淘汰赛（球队未确定）"""
    return round_name not in ("小组赛",)


# ======================== 核心转换 ========================


def elo_diff_to_confidence(elo_a, elo_b):
    """基于ELO分差计算置信度"""
    diff = abs(elo_a - elo_b)
    if diff > 300:
        return "高"
    elif diff > 150:
        return "中"
    else:
        return "低"


def round_probs(home_p, draw_p, away_p):
    """将三个概率取整，确保总和=100"""
    h = round(home_p)
    d = round(draw_p)
    a = round(away_p)
    total = h + d + a
    if total == 100:
        return h, d, a
    # 找误差最大的项补偿
    errs = [
        (abs(h - home_p), "h"),
        (abs(d - draw_p), "d"),
        (abs(a - away_p), "a"),
    ]
    errs.sort(reverse=True, key=lambda x: x[0])
    adjust = 100 - total
    if adjust > 0:
        # 给最不精确的加1
        if errs[0][1] == "h":
            h += 1
        elif errs[0][1] == "d":
            d += 1
        else:
            a += 1
    elif adjust < 0:
        if errs[0][1] == "h":
            h -= 1
        elif errs[0][1] == "d":
            d -= 1
        else:
            a -= 1
    return h, d, a


def generate_keyfactors(mc, h_team, a_team, h_cn, a_cn, elo_diff, h_lambda, a_lambda, teams_tbd):
    """根据MC数据生成关键因素列表"""
    factors = []

    if teams_tbd:
        factors.append("淘汰赛阶段，对手未确定")
        factors.append("待小组赛结束后更新预测")
        return factors

    # ELO差距因素
    h_elo = mc["home_elo"]
    a_elo = mc["away_elo"]
    if elo_diff > 200:
        if h_elo > a_elo:
            factors.append(f"{h_cn}ELO评分{h_elo}，远高于{a_cn}的{a_elo}")
        else:
            factors.append(f"{a_cn}ELO评分{a_elo}，远高于{h_cn}的{h_elo}")
    elif elo_diff > 80:
        stronger_cn = h_cn if h_elo > a_elo else a_cn
        factors.append(f"{stronger_cn}实力占优，ELO领先{elo_diff}分")
    else:
        factors.append("双方实力接近，胜负难料")

    # 预期进球因素
    if max(h_lambda, a_lambda) > 2.0:
        strong_cn = h_cn if h_lambda > a_lambda else a_cn
        factors.append(f"{strong_cn}进攻火力强劲，预期进球{max(h_lambda, a_lambda):.2f}")
    if min(h_lambda, a_lambda) < 0.6:
        # lambda低的是弱队
        weak_cn = a_cn if h_lambda > a_lambda else h_cn
        factors.append(f"{weak_cn}进攻乏力，预期进球仅{min(h_lambda, a_lambda):.2f}")

    # 实力优势因素（世界杯无真正主客场，仅保留ELO高的一方有优势）
    if h_elo > a_elo:
        factors.append(f"{h_cn}整体实力占优")

    # 东道主因素 (美国/墨西哥/加拿大有host bonus)
    host_teams = ["USA", "Mexico", "Canada"]
    if mc["home"] in host_teams:
        factors.append(f"{h_cn}作为东道主，拥有额外士气加成")

    # 限制最多5条
    return factors[:5]


def generate_analysis(mc, h_cn, a_cn, teams_tbd):
    """生成分析文本占位"""
    if teams_tbd:
        return f"{h_cn} vs {a_cn}的淘汰赛对阵尚未确定，待小组赛结束后更新预测。"

    return (
        f"基于蒙特卡洛统计模型预测，{h_cn}胜率{mc['home_win_prob']:.1f}%，"
        f"平局概率{mc['draw_prob']:.1f}%，{a_cn}胜率{mc['away_win_prob']:.1f}%。"
        f"最可能比分为{mc['most_likely_score']}。"
    )


def convert_match(mc):
    """将一条MC预测记录转换为App PredictionData格式"""
    match_id = mc["match_id"]
    h_team = mc["home"]
    a_team = mc["away"]
    round_name = mc["round"]
    is_ko = is_ko_round(round_name)

    # 概率处理
    if h_team == "TBD" or a_team == "TBD":
        # 对手未定: 均等概率
        h_w, draw, a_w = 33, 33, 33
    else:
        h_w, draw, a_w = round_probs(
            mc["home_win_prob"], mc["draw_prob"], mc["away_win_prob"]
        )

    # 置信度
    if h_team == "TBD" or a_team == "TBD" or mc["home_elo"] == 0:
        confidence = "低"
    else:
        confidence = elo_diff_to_confidence(mc["home_elo"], mc["away_elo"])

    # 比分
    score = mc["most_likely_score"]

    teams_tbd = (h_team == "TBD" or a_team == "TBD")

    # 关键因素
    h_cn = get_cn_name(h_team)
    a_cn = get_cn_name(a_team)
    elo_diff = abs(mc["home_elo"] - mc["away_elo"])
    key_factors = generate_keyfactors(
        mc, h_team, a_team, h_cn, a_cn, elo_diff,
        mc["home_lambda"], mc["away_lambda"], teams_tbd
    )

    # 分析文本
    analysis = generate_analysis(mc, h_cn, a_cn, teams_tbd)

    return {
        "matchId": match_id,
        "teamA": {
            "name": h_team,
            "cnName": get_cn_name(h_team),
            "winProb": h_w
        },
        "draw": draw,
        "teamB": {
            "name": a_team,
            "cnName": get_cn_name(a_team),
            "winProb": a_w
        },
        "predictedScore": score,
        "mostLikelyScore": score,
        "confidence": confidence,
        "keyFactors": key_factors,
        "analysis": analysis,
        "playersToWatch": [],
        "mcDraw": mc["draw_prob"],
        "homeElo": mc["home_elo"],
        "awayElo": mc["away_elo"],
        "homeLambda": mc["home_lambda"],
        "awayLambda": mc["away_lambda"]
    }


# ======================== 主流程 ========================

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, "..", ".."))

    # 输入
    mc_path = os.path.join(project_root, "Predict_Module", "data", "mc_predictions.json")

    # 输出（默认覆盖MainApp的predictions.json）
    output_path = os.path.join(
        project_root, "MainApp", "app", "src", "main", "assets", "predictions.json"
    )

    # 检查命令行参数
    if "--output" in sys.argv:
        idx = sys.argv.index("--output")
        if idx + 1 < len(sys.argv):
            output_path = sys.argv[idx + 1]

    # 读取MC数据
    with open(mc_path, "r", encoding="utf-8") as f:
        mc_data = json.load(f)

    predictions_raw = mc_data["predictions"]
    print(f"✅ 读取 {len(predictions_raw)} 条MC预测记录")

    # 转换
    converted = [convert_match(m) for m in predictions_raw]

    # 统计
    ko_count = sum(1 for m in predictions_raw if is_ko_round(m["round"]))
    group_count = len(predictions_raw) - ko_count
    print(f"📊 小组赛: {group_count} 场, 淘汰赛: {ko_count} 场")

    # 验证概率和
    for p in converted:
        total = p["teamA"]["winProb"] + p["draw"] + p["teamB"]["winProb"]
        if total != 100:
            print(f"⚠️  matchId {p['matchId']}: 概率和={total}")

    # 输出
    output = {"predictions": converted}
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f"✅ 已写入 {output_path}")
    print(f"📋 共 {len(converted)} 场比赛预测，置信度分布:")
    conf_dist = {}
    for p in converted:
        c = p["confidence"]
        conf_dist[c] = conf_dist.get(c, 0) + 1
    for c in ["高", "中", "低"]:
        print(f"   {c}: {conf_dist.get(c, 0)} 场")


if __name__ == "__main__":
    main()
