#!/usr/bin/env python3
"""
面部匹配模块
───────────
使用 Faiss 索引进行球员面部检索
"""

import json
from pathlib import Path
from typing import Optional, List, Dict
import numpy as np

MODULE_DIR = Path(__file__).resolve().parent.parent
FACE_DB_DIR = MODULE_DIR / "face_db"

class FaceMatcher:
    """球员面部匹配器"""

    def __init__(self):
        self.index = None
        self.metadata = []
        self.available = self._load()

    def _load(self) -> bool:
        """加载 Faiss 索引和元数据"""
        import faiss
        idx_path = FACE_DB_DIR / "face_index.faiss"
        meta_path = FACE_DB_DIR / "face_metadata.json"

        if not idx_path.exists() or not meta_path.exists():
            print("[FaceMatch] 索引不存在, 请先运行 face_db_builder.py")
            return False

        self.index = faiss.read_index(str(idx_path))
        with open(meta_path, "r", encoding="utf-8") as f:
            self.metadata = json.load(f)

        print(f"[FaceMatch] 加载 {self.index.ntotal} 个面部向量")
        return True

    def search(self, embedding: np.ndarray, team_filter: str = None,
               top_k: int = 5, min_score: float = 0.3) -> List[Dict]:
        """
        搜索最匹配的球员

        Args:
            embedding: 512维面部向量
            team_filter: 限制搜索队伍 (None=全库搜索)
            top_k: 返回前k个结果
            min_score: 最低相似度阈值

        Returns:
            [{name_en, name_cn, number, team, pos, score}, ...]
        """
        if self.index is None or len(self.metadata) == 0:
            return []

        embedding = embedding.reshape(1, -1).astype("float32")
        embedding = embedding / np.linalg.norm(embedding)

        # 全库搜索
        search_k = min(top_k * 3 if team_filter else top_k, self.index.ntotal)
        scores, indices = self.index.search(embedding, search_k)

        results = []
        for idx, score in zip(indices[0], scores[0]):
            if idx == -1 or idx >= len(self.metadata):
                continue

            meta = self.metadata[idx]

            # 队伍过滤
            if team_filter and meta["team"] != team_filter:
                continue

            if score < min_score:
                continue

            results.append({
                "name_en": meta["name_en"],
                "name_cn": meta["name_cn"],
                "number": meta["number"],
                "team": meta["team"],
                "pos": meta["pos"],
                "score": float(score),
            })

            if len(results) >= top_k:
                break

        return results


# ============================================================
# 快速测试
# ============================================================
if __name__ == "__main__":
    matcher = FaceMatcher()
    if matcher.available:
        print(f"数据库: {len(matcher.metadata)} 球员")

        # 测试检索
        test_emb = np.random.randn(512).astype("float32")
        results = matcher.search(test_emb, team_filter="usa", top_k=3)
        print(f"测试检索结果: {len(results)}")
        for r in results:
            print(f"  #{r['number']} {r['name_en']} ({r['name_cn']}) score={r['score']:.3f}")
    else:
        print("数据库未构建, 请先运行 face_db_builder.py")
