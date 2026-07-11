#!/usr/bin/env python3
"""
Face Embedding Extractor & Faiss Index Builder
Uses InsightFace (buffalo_l) to extract 512-dim face embeddings
from api-sports.io player photos, then builds a Faiss cosine-similarity index.

Requires:
  pip install insightface faiss-cpu numpy pillow tqdm

Usage:
  python face_extractor.py              # Extract all teams
  python face_extractor.py --team mexico  # Extract single team
"""

import os
import sys
import json
import time
from pathlib import Path
import numpy as np
from PIL import Image, ImageFile
ImageFile.LOAD_TRUNCATED_IMAGES = True

# Path config
SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
PHOTO_DIR = PROJECT_ROOT / "face_db" / "images"
DATA_DIR = PROJECT_ROOT / "data" / "apisports"
FACE_DB_DIR = PROJECT_ROOT / "face_db"

# Config
MIN_FACE_SIZE = 60  # Minimum face size in pixels
MIN_CONFIDENCE = 0.5  # Minimum face detection confidence


class FaceExtractor:
    """Extract face embeddings using InsightFace and build Faiss index."""
    
    def __init__(self):
        self.detector = None
        self.face_dim = 512
        self._init_model()
    
    def _init_model(self):
        """Initialize InsightFace model."""
        try:
            import insightface
            self.detector = insightface.app.FaceAnalysis(
                name='buffalo_l',
                providers=['CPUExecutionProvider'],
                allowed_modules=['detection', 'recognition'],
            )
            self.detector.prepare(ctx_id=0, det_size=(640, 640))
            print("✅ InsightFace (buffalo_l) initialized")
        except Exception as e:
            print(f"❌ Failed to initialize InsightFace: {e}")
            raise
    
    def extract_face_embedding(self, image_path, player_name="unknown"):
        """Extract face embedding from a player photo.
        
        Returns: (512-dim numpy array, confidence, bbox) or (None, 0, None)
        """
        try:
            img = Image.open(image_path).convert('RGB')
            img_array = np.array(img)
            
            faces = self.detector.get(img_array)
            
            if not faces:
                return None, 0, None
            
            # Select best face (largest area, highest confidence)
            best = max(faces, key=lambda f: f['det_score'] * 
                       (f['bbox'][2] - f['bbox'][0]) * (f['bbox'][3] - f['bbox'][1]))
            
            conf = best['det_score']
            bbox = best['bbox'].tolist()
            # embedding is L2-normalized by InsightFace, use normed_embedding if available
            embedding = best.get('embedding')
            if embedding is None:
                embedding = getattr(best, 'normed_embedding', None)
            if embedding is None:
                embedding = getattr(best, 'embedding', None)
            if embedding is None:
                return None, conf, bbox
            
            # Quality checks
            face_w = bbox[2] - bbox[0]
            face_h = bbox[3] - bbox[1]
            if face_w < MIN_FACE_SIZE or face_h < MIN_FACE_SIZE:
                return None, conf, bbox
            if conf < MIN_CONFIDENCE:
                return None, conf, bbox
            
            return embedding, conf, bbox
            
        except Exception as e:
            print(f"  ⚠ Error processing {player_name}: {e}")
            return None, 0, None
    
    def extract_team(self, team_slug, team_data):
        """Extract embeddings for all players in a team."""
        team_photo_dir = PHOTO_DIR / team_slug
        if not team_photo_dir.exists():
            print(f"  ❌ Photo directory not found: {team_photo_dir}")
            return [], []
        
        players = team_data.get("players", [])
        embeddings = []
        metadata = []
        
        photo_files = list(team_photo_dir.glob("*.png"))
        photo_map = {p.stem: p for p in photo_files}
        print(f"  Found {len(photo_files)} photo files for {team_slug}")
        
        for player in players:
            number = player.get("number", 0) or 0
            name = player.get("name", "unknown")
            name_safe = name.replace(" ", "_")
            filename = f"{number:02d}_{name_safe}"
            
            if filename not in photo_map:
                # Try without leading zero
                alt_name = f"{int(number)}_{name_safe}"
                if alt_name in photo_map:
                    filename = alt_name
                else:
                    # Fallback: search by partial name
                    candidates = [p for p in photo_files if name_safe in str(p.stem)]
                    if candidates:
                        print(f"  ⚠ Fuzzy match: {name} -> {candidates[0].name}")
                        filename = candidates[0].stem
                    else:
                        print(f"  ⚠ No photo for #{number} {name}")
                        continue
            
            image_path = photo_map[filename]
            embedding, conf, bbox = self.extract_face_embedding(
                str(image_path), f"#{number} {name}")
            
            if embedding is not None:
                embeddings.append(embedding)
                metadata.append({
                    "team": team_slug,
                    "team_name": team_data.get("team_name", team_slug),
                    "team_id": team_data.get("team_id", 0),
                    "player_name": name,
                    "player_number": number,
                    "position": player.get("position", "?"),
                    "photo_path": str(image_path.relative_to(PROJECT_ROOT)),
                    "confidence": round(float(conf), 4),
                    "bbox": bbox,
                })
                print(f"  ✅ #{number} {name:<25s} conf={conf:.3f} (face={bbox[2]-bbox[0]:.0f}x{bbox[3]-bbox[1]:.0f})")
            else:
                print(f"  ⚠ #{number} {name:<25s} NO FACE (conf={conf:.3f})")
        
        return embeddings, metadata


def build_index(extractor):
    """Extract all teams and build Faiss index."""
    # Load team data from JSON caches
    team_files = sorted(DATA_DIR.glob("*.json"))
    if not team_files:
        print("❌ No team data found. Run apisports_downloader.py first.")
        return
    
    # Exclude team_index.json
    team_files = [f for f in team_files if f.stem != "team_index"]
    
    print(f"Processing {len(team_files)} teams...")
    all_embeddings = []
    all_metadata = []
    
    total_players = 0
    total_faces = 0
    
    for team_file in team_files:
        team_slug = team_file.stem
        team_data = json.loads(team_file.read_text())
        team_name = team_data.get("team", {}).get("name", team_slug)
        team_id = team_data.get("team", {}).get("id", 0)
        
        print(f"\n{'='*60}")
        print(f"Team: {team_name} (slug={team_slug}, id={team_id})")
        
        # Embed team info into player list
        player_data = {
            "players": team_data.get("players", []),
            "team_name": team_name,
            "team_id": team_id,
        }
        
        embeddings, metadata = extractor.extract_team(team_slug, player_data)
        
        team_players = len(player_data["players"])
        team_faces = len(embeddings)
        
        total_players += team_players
        total_faces += team_faces
        
        all_embeddings.extend(embeddings)
        all_metadata.extend(metadata)
        
        print(f"  Summary: {team_faces}/{team_players} players with faces extracted")
    
    # Build Faiss index
    print(f"\n{'='*60}")
    print(f"Building Faiss index...")
    print(f"  Total players: {total_players}")
    print(f"  Total faces: {total_faces}")
    
    if total_faces == 0:
        print("❌ No faces extracted! Cannot build index.")
        return
    
    embeddings_array = np.array(all_embeddings, dtype=np.float32)
    print(f"  Embeddings shape: {embeddings_array.shape}")
    
    import faiss
    dimension = embeddings_array.shape[1]
    index = faiss.IndexFlatIP(dimension)  # Inner Product = Cosine similarity (normalized)
    index.add(embeddings_array)
    
    # Save index
    index_path = FACE_DB_DIR / "face_index_v2.faiss"
    faiss.write_index(index, str(index_path))
    print(f"  💾 Faiss index saved: {index_path} ({index.ntotal} vectors)")
    
    # Save metadata
    meta_path = FACE_DB_DIR / "face_metadata_v2.json"
    meta_path.write_text(json.dumps({
        "total_players": total_players,
        "total_faces": total_faces,
        "model": "InsightFace buffalo_l",
        "normalized": True,
        "similarity": "cosine (via inner product)",
        "players": all_metadata,
    }, indent=2, ensure_ascii=False))
    print(f"  💾 Metadata saved: {meta_path}")
    
    # Quick test: self-search top-3 for first player
    if total_faces > 0:
        print(f"\n{'='*60}")
        print("Quick self-test (should return same player as top match):")
        test_vec = embeddings_array[0:1]
        distances, indices = index.search(test_vec, 3)
        for i, (dist, idx) in enumerate(zip(distances[0], indices[0])):
            meta = all_metadata[idx]
            print(f"  [{i+1}] distance={dist:.4f} | #{meta['player_number']} {meta['player_name']} ({meta['team']})")
    
    return index, all_metadata


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Face embedding extractor")
    parser.add_argument("--team", type=str, help="Extract only a single team (by slug)")
    args = parser.parse_args()
    
    print("Face Embedding Extractor v2 (api-sports.io photos)")
    print(f"Photo directory: {PHOTO_DIR}")
    print(f"Data directory: {DATA_DIR}")
    print()
    
    extractor = FaceExtractor()
    
    if args.team:
        # Single team mode
        team_file = DATA_DIR / f"{args.team}.json"
        if not team_file.exists():
            print(f"❌ No data for team: {args.team}")
            sys.exit(1)
        
        team_data = json.loads(team_file.read_text())
        team_name = team_data.get("team", {}).get("name", args.team)
        
        player_data = {
            "players": team_data.get("players", []),
            "team_name": team_name,
            "team_id": team_data.get("team", {}).get("id", 0),
        }
        
        embeddings, metadata = extractor.extract_team(args.team, player_data)
        print(f"\nDone: {len(embeddings)} faces extracted for {team_name}")
    else:
        # Full extraction
        build_index(extractor)
