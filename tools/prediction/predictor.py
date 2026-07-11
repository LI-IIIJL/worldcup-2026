#!/usr/bin/env python3
"""
WorldCup 2026 Match Predictor

调用 LLM API（OpenAI 兼容接口）获取世界杯比赛预测。
支持 DeepSeek / GPT / Qwen / Claude 等模型。

用法：
    python predictor.py --home "Argentina" --away "Algeria" --api-key "sk-xxx"
    python predictor.py --home "Mexico" --away "South Africa" --model "gpt-4o" --api-key "sk-xxx"
    python predictor.py --list-matches    # 查看可预测的比赛

输出：
    JSON 格式的预测结果，包含胜率、预测比分、关键球员等。
"""

import argparse
import json
import os
import sys
import requests

# 默认配置
DEFAULT_MODEL = "deepseek-chat"
DEFAULT_API_URL = "https://api.deepseek.com/v1/chat/completions"

# skill.md 路径（相对于本脚本所在目录）
SKILL_PATH = os.path.join(os.path.dirname(__file__), "..", "core", "skill.md")


def load_skill() -> str:
    """加载 skill.md 系统提示词"""
    if not os.path.exists(SKILL_PATH):
        print(f"错误：找不到 skill.md 文件 ({SKILL_PATH})", file=sys.stderr)
        sys.exit(1)
    with open(SKILL_PATH, "r", encoding="utf-8") as f:
        return f.read()


def get_available_matches() -> list[dict]:
    """
    从预测引擎资料库中提取可预测的比赛。
    返回 12 个小组、48 支球队的分组信息。
    """
    groups = [
        ("A", ["Mexico", "South Africa", "South Korea", "Czechia"]),
        ("B", ["Canada", "Bosnia", "Qatar", "Switzerland"]),
        ("C", ["Brazil", "Morocco", "Haiti", "Scotland"]),
        ("D", ["USA", "Paraguay", "Australia", "Turkey"]),
        ("E", ["Germany", "Curacao", "Ivory Coast", "Ecuador"]),
        ("F", ["Netherlands", "Japan", "Sweden", "Tunisia"]),
        ("G", ["Belgium", "Egypt", "Iran", "New Zealand"]),
        ("H", ["Spain", "Cape Verde", "Saudi Arabia", "Uruguay"]),
        ("I", ["France", "Senegal", "Iraq", "Norway"]),
        ("J", ["Argentina", "Algeria", "Austria", "Jordan"]),
        ("K", ["Portugal", "Congo DR", "Uzbekistan", "Colombia"]),
        ("L", ["England", "Croatia", "Ghana", "Panama"]),
    ]
    matches = []
    for group, teams in groups:
        for i in range(len(teams)):
            for j in range(i + 1, len(teams)):
                matches.append({
                    "group": group,
                    "home": teams[i],
                    "away": teams[j],
                })
    return matches


def list_matches():
    """列出所有可预测的比赛（按小组分组）"""
    groups_matches = {}
    for m in get_available_matches():
        g = m["group"]
        if g not in groups_matches:
            groups_matches[g] = []
        groups_matches[g].append(f"  {m['home']} vs {m['away']}")

    print(f"可预测的比赛（共 {len(get_available_matches())} 场）:\n")
    for g in sorted(groups_matches.keys()):
        print(f"组 {g}:")
        for match in groups_matches[g]:
            print(match)
        print()


def build_user_message(home: str, away: str) -> str:
    """构建用户消息，要求预测指定比赛"""
    return (
        f"请预测 2026 世界杯 {home} 对 {away} 的比赛结果。"
        f"严格按照输出格式返回 JSON。"
    )


def predict(
    home: str,
    away: str,
    api_key: str,
    model: str = DEFAULT_MODEL,
    api_url: str = DEFAULT_API_URL,
    temperature: float = 0.3,
) -> dict:
    """
    调用 LLM API 获取比赛预测。

    Args:
        home: 主队名称（英文）
        away: 客队名称（英文）
        api_key: API Key
        model: 模型名称
        api_url: API 端点 URL
        temperature: 温度参数（越低越稳定）

    Returns:
        预测结果 JSON 字典
    """
    system_prompt = load_skill()
    user_message = build_user_message(home, away)

    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    }

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ],
        "response_format": {"type": "json_object"},
        "temperature": temperature,
        "max_tokens": 1024,
    }

    print(f"[请求] 模型: {model}, 预测: {home} vs {away}", file=sys.stderr)
    resp = requests.post(api_url, headers=headers, json=payload, timeout=60)

    if resp.status_code != 200:
        print(f"[错误] API 返回 {resp.status_code}: {resp.text}", file=sys.stderr)
        sys.exit(1)

    result = resp.json()
    content = result["choices"][0]["message"]["content"]

    try:
        prediction = json.loads(content)
    except json.JSONDecodeError:
        print(f"[错误] 无法解析 LLM 输出为 JSON:\n{content}", file=sys.stderr)
        sys.exit(1)

    return prediction


def main():
    parser = argparse.ArgumentParser(
        description="WorldCup 2026 比赛预测工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python predictor.py --home Argentina --away Algeria --api-key sk-xxx
  python predictor.py --home "South Korea" --away Czechia --model gpt-4o --api-key sk-xxx
  python predictor.py --list-matches
  python predictor.py --home Mexico --away "South Africa" --temperature 0.5 --api-key sk-xxx
        """,
    )

    # 比赛参数
    parser.add_argument("--home", type=str, help="主队名称（英文）")
    parser.add_argument("--away", type=str, help="客队名称（英文）")

    # API 参数
    parser.add_argument("--api-key", type=str, help="LLM API Key（也可用 LLM_API_KEY 环境变量）")
    parser.add_argument("--model", type=str, default=DEFAULT_MODEL, help=f"模型名称（默认: {DEFAULT_MODEL}）")
    parser.add_argument("--api-url", type=str, default=DEFAULT_API_URL, help=f"API 端点（默认: {DEFAULT_API_URL}）")
    parser.add_argument("--temperature", type=float, default=0.3, help="温度参数 0-1（默认: 0.3）")

    # 辅助命令
    parser.add_argument("--list-matches", action="store_true", help="列出所有可预测的比赛")
    parser.add_argument("--pretty", action="store_true", help="美化 JSON 输出")
    parser.add_argument("--output", type=str, help="将预测结果保存到文件")

    args = parser.parse_args()

    # 处理辅助命令
    if args.list_matches:
        list_matches()
        return

    # 验证必填参数
    if not args.home or not args.away:
        print("错误：必须指定 --home 和 --away（或使用 --list-matches 查看可预测的比赛）", file=sys.stderr)
        sys.exit(1)

    # 获取 API Key（优先级：命令行参数 > 环境变量）
    api_key = args.api_key or os.environ.get("LLM_API_KEY")
    if not api_key:
        print(
            "错误：需要 API Key。请通过 --api-key 参数或 LLM_API_KEY 环境变量提供。",
            file=sys.stderr,
        )
        sys.exit(1)

    # 执行预测
    result = predict(
        home=args.home,
        away=args.away,
        api_key=api_key,
        model=args.model,
        api_url=args.api_url,
        temperature=args.temperature,
    )

    # 输出结果
    indent = 2 if args.pretty else None
    output_str = json.dumps(result, ensure_ascii=False, indent=indent)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(output_str)
        print(f"预测结果已保存到: {args.output}", file=sys.stderr)
    else:
        print(output_str)


if __name__ == "__main__":
    main()
