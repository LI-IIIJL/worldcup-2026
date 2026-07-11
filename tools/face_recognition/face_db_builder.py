#!/usr/bin/env python3
"""
两步构建面部数据库:
  Step 1: python face_db_builder.py --download     (快速下载图片)
  Step 2: python face_db_builder.py --extract      (提取embeddings + 建索引)
"""

import os, sys, io, json, time
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import argparse
from pathlib import Path
from typing import List
import numpy as np
import requests
from PIL import Image

TOOLS_DIR = Path(__file__).resolve().parent
MODULE_DIR = TOOLS_DIR.parent
FACE_DIR = MODULE_DIR / "face_db" / "images"
FACE_DIR.mkdir(parents=True, exist_ok=True)

sys.path.insert(0, str(TOOLS_DIR))
from player_database import SQUADS

# ============================================================
# Transfermarkt ID 映射 (知名球员)
# ============================================================
TM_IDS = {
    "Christian Pulisic": 315779, "Giovanni Reyna": 504148,
    "Weston McKennie": 332697, "Tyler Adams": 332701,
    "Tim Weah": 370845, "Yunus Musah": 503633,
    "Matt Turner": 425506, "Sergino Dest": 361092,
    "Antonee Robinson": 349701, "Ricardo Pepi": 815066,
    "Brenden Aaronson": 393323, "Folarin Balogun": 503770,
    "Miles Robinson": 359332, "Chris Richards": 476712,
    "Johnny Cardoso": 762619, "Malik Tillman": 600668,
    "Josh Sargent": 393317, "Luca de la Torre": 417398,
    "Cameron Carter-Vickers": 282099,

    "Miguel Almiron": 288905, "Julio Enciso": 939419,
    "Antonio Sanabria": 237238, "Ramon Sosa": 863975,
    "Mathias Villasanti": 397804, "Fabian Balbuena": 139382,
    "Diego Gomez": 927091, "Gustavo Gomez": 215260,
    "Omar Alderete": 509993, "Andres Cubas": 423050,

    "Son Heung-min": 91845, "Kim Min-jae": 503981,
    "Hwang Hee-chan": 316614, "Lee Kang-in": 557149,
    "Cho Gue-sung": 757794, "Kim Young-gwon": 126713,
    "Hwang In-beom": 452772, "Lee Jae-sung": 216320,

    "Patrik Schick": 242651, "Tomas Soucek": 283628,
    "Adam Hlozek": 552057, "Vladimir Coufal": 234591,
    "Antonin Barak": 272151, "Vaclav Cerny": 377731,
    "Alex Kral": 337347, "Ondrej Lingr": 556243,

    "Alphonso Davies": 424106, "Jonathan David": 533738,
    "Cyle Larin": 343463, "Tajon Buchanan": 742472,
    "Stephen Eustaquio": 463808, "Ismael Kone": 882269,
    "Alistair Johnston": 676260, "Jonathan Osorio": 358954,

    "Edin Dzeko": 28357, "Miralem Pjanic": 44162,
    "Sead Kolasinac": 118305, "Ermedin Demirovic": 417712,
    "Anel Ahmedhodzic": 377670, "Amar Dedic": 689492,
    "Benjamin Tahirovic": 803229, "Haris Tabakovic": 311615,

    "Santiago Gimenez": 523126, "Edson Alvarez": 370761,
    "Guillermo Ochoa": 14008, "Raul Jimenez": 206529,
    "Luis Chavez": 535555, "Johan Vasquez": 592150, "Cesar Montes": 313544,
    "Orbelin Pineda": 304078, "Alexis Vega": 462203, "Luis Romo": 427107,

    "Percy Tau": 291654, "Lyle Foster": 603181,
    "Ronwen Williams": 167214, "Themba Zwane": 200380,
    "Teboho Mokoena": 568848,
}

# ============================================================
# 下载
# ============================================================
def download_faces(teams: List[str]):
    """只下载图片, 不做embedding"""
    total, ok = 0, 0

    for team_key in teams:
        if team_key not in SQUADS:
            continue
        squad = SQUADS[team_key]
        team_dir = FACE_DIR / team_key
        team_dir.mkdir(exist_ok=True)

        for number, (name_en, pos, name_cn) in squad.items():
            total += 1
            fname = f"{number}_{name_en.replace(' ', '_').replace('/', '_')}.jpg"
            path = team_dir / fname

            if path.exists() and path.stat().st_size > 500:
                ok += 1
                continue

            downloaded = False

            # Transfermarkt
            if name_en in TM_IDS:
                pid = TM_IDS[name_en]
                for sz in ["header", "medium"]:
                    url = f"https://img.a.transfermarkt.technology/portrait/{sz}/{pid}.jpg"
                    try:
                        r = requests.get(url, headers={"User-Agent": "Mozilla/5.0"}, timeout=8)
                        if r.status_code == 200 and len(r.content) > 2000:
                            with open(path, "wb") as f:
                                f.write(r.content)
                            try:
                                Image.open(path).verify()
                                downloaded = True
                                ok += 1
                                break
                            except:
                                os.remove(path)
                    except:
                        continue

            # Wikipedia
            if not downloaded:
                try:
                    q = requests.utils.quote(name_en)
                    url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{q}"
                    r = requests.get(url, headers={"User-Agent": "WorldCupScanner/1.0"}, timeout=8)
                    if r.status_code == 200:
                        thumb = r.json().get("thumbnail", {}).get("source")
                        if thumb:
                            r2 = requests.get(thumb, headers={"User-Agent": "Mozilla/5.0"}, timeout=10)
                            if r2.status_code == 200 and len(r2.content) > 1000:
                                with open(path, "wb") as f:
                                    f.write(r2.content)
                                try:
                                    Image.open(path).verify()
                                    downloaded = True
                                    ok += 1
                                except:
                                    os.remove(path)
                except:
                    pass

            if downloaded:
                print(f"  ✅ #{number:2d} {name_en}")
            else:
                print(f"  ❌ #{number:2d} {name_en}")

    print(f"\n📊 下载完成: {ok}/{total}")
    return ok


# ============================================================
# 提取
# ============================================================
def extract_embeddings():
    """提取embeddings并构建Faiss索引"""
    from insightface.app import FaceAnalysis
    import faiss

    print("[InsightFace] 加载模型...", end=" ", flush=True)
    app = FaceAnalysis(name='buffalo_l', providers=['CPUExecutionProvider'])
    app.prepare(ctx_id=0)
    print("OK")

    records = []
    for team_dir in sorted(FACE_DIR.iterdir()):
        if not team_dir.is_dir():
            continue
        team_key = team_dir.name
        if team_key not in SQUADS:
            continue

        for img_path in sorted(team_dir.glob("*.jpg")):
            # 解析文件名: "10_Christian_Pulisic.jpg"
            stem = img_path.stem
            parts = stem.split("_", 1)
            if len(parts) < 2:
                continue
            try:
                number = int(parts[0])
            except:
                continue

            name_en_key = parts[1].replace("_", " ")
            squad = SQUADS[team_key]
            info = squad.get(number)
            if not info:
                continue

            name_en, pos, name_cn = info

            try:
                arr = np.array(Image.open(img_path).convert("RGB"))
                faces = app.get(arr)
                if not faces:
                    print(f"  ⚠️  {team_key}/#{number} {name_en} → 未检测到面部")
                    continue
                best = max(faces, key=lambda f: f.det_score)
                records.append({
                    "team": team_key, "name_en": name_en,
                    "name_cn": name_cn, "number": number,
                    "pos": pos, "embedding": best.embedding,
                })
                print(f"  ✅ {team_key}/#{number} {name_en}")
            except Exception as e:
                print(f"  ❌ {team_key}/#{number} {name_en}: {e}")

    if not records:
        print("\n❌ 无有效面部数据")
        return 0

    # 构建Faiss
    embs = np.stack([r["embedding"] for r in records]).astype("float32")
    embs = embs / np.linalg.norm(embs, axis=1, keepdims=True)

    index = faiss.IndexFlatIP(512)
    index.add(embs)

    meta = [{k: v for k, v in r.items() if k != "embedding"} for r in records]

    faiss.write_index(index, str(MODULE_DIR / "face_db" / "face_index.faiss"))
    with open(MODULE_DIR / "face_db" / "face_metadata.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    print(f"\n📊 索引构建完成: {len(records)} 个面部向量")
    return len(records)


# ============================================================
# CLI
# ============================================================
def main():
    parser = argparse.ArgumentParser(description="面部数据库两步构建")
    parser.add_argument("--download", action="store_true", help="下载面部图片")
    parser.add_argument("--extract", action="store_true", help="提取embeddings并建索引")
    parser.add_argument("--teams", default="all", help="队伍列表，逗号分隔")
    args = parser.parse_args()

    if args.teams == "all":
        teams = list(SQUADS.keys())
    else:
        teams = [t.strip() for t in args.teams.split(",")]

    if args.download:
        print(f"📥 下载面部图片: {len(teams)} 队")
        download_faces(teams)

    if args.extract:
        print(f"🧠 提取面部特征 + 构建Faiss索引")
        extract_embeddings()

    if not args.download and not args.extract:
        parser.print_help()


if __name__ == "__main__":
    main()
