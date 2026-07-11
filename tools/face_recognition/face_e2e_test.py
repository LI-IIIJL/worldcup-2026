#!/usr/bin/env python3
"""
端到端人脸匹配测试 (Faiss V2)
球员检测 → 人脸检测 → Faiss V2 匹配

用法:
  python face_e2e_test.py                    # 测试比赛1第一张截图
  python face_e2e_test.py --match 1          # 测试比赛1全部截图  
  python face_e2e_test.py --spain            # 测试西班牙4K截图(仅检测)
  python face_e2e_test.py -s <path>          # 单张截图
"""

import os, sys, io, json, argparse
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

from pathlib import Path
import numpy as np
from PIL import Image
import torch, torchvision
from torchvision import transforms
from torchvision.models.detection import fasterrcnn_resnet50_fpn_v2

TOOLS_DIR = Path(__file__).resolve().parent
MODULE_DIR = TOOLS_DIR.parent
FACE_DB_DIR = MODULE_DIR / "face_db"
SS_DIR = MODULE_DIR / "screenshots"
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

MATCHES = {
    1: (SS_DIR/"match_1", "mexico", "south_africa"),
    2: (SS_DIR/"match_2", "south_korea", "czech"),
    3: (SS_DIR/"match_3", "canada", "bosnia"),
    4: (SS_DIR/"match_4", "usa", "paraguay"),
}


# ============================================================
class FaceMatcherV2:
    def __init__(self):
        import faiss
        idx = FACE_DB_DIR / "face_index_v2.faiss"
        meta = FACE_DB_DIR / "face_metadata_v2.json"
        if not idx.exists():
            print("❌ 索引不存在"); return
        self.index = faiss.read_index(str(idx))
        self.players = json.loads(meta.read_text())["players"]
        self.teams = set(p["team"] for p in self.players)
        print(f"✅ Faiss V2: {self.index.ntotal}向量 {len(self.teams)}队")
    
    def search(self, emb, teams=None, k=3, mins=0.22):
        if self.index is None: return []
        emb = emb.reshape(1,-1).astype("float32")
        emb = emb / (np.linalg.norm(emb)+1e-8)
        sk = min(k*5 if teams else k, self.index.ntotal)
        scores, ids = self.index.search(emb, sk)
        out = []
        for i, s in zip(ids[0], scores[0]):
            if i<0 or i>=len(self.players): continue
            p = self.players[i]
            if teams and p["team"] not in teams: continue
            if s < mins: continue
            out.append({**p, "match_score": float(s)})
            if len(out)>=k: break
        return out


# ============================================================
class FaceEngine:
    def __init__(self):
        import insightface
        self.app = insightface.app.FaceAnalysis(
            name='buffalo_l', providers=['CPUExecutionProvider'],
            allowed_modules=['detection','recognition'])
        self.app.prepare(ctx_id=0, det_size=(640,640))
        print("✅ InsightFace ready")
    
    def detect(self, arr):
        faces = self.app.get(arr)
        out = []
        for f in faces:
            emb = f.get('embedding') or getattr(f,'embedding',None)
            if emb is None: continue
            b = f['bbox']; out.append({
                'bbox':[int(b[0]),int(b[1]),int(b[2]),int(b[3])],
                'score':float(f['det_score']), 'embedding':emb})
        return out


# ============================================================
class PlayerDetector:
    def __init__(self, conf=0.45, min_h=0.03):
        self.model = fasterrcnn_resnet50_fpn_v2(
            weights=torchvision.models.detection.FasterRCNN_ResNet50_FPN_V2_Weights.COCO_V1
        ).to(DEVICE).eval()
        self.conf = conf; self.min_h = min_h
    
    @torch.no_grad()
    def detect(self, img):
        iw, ih = img.size
        t = transforms.ToTensor()(img).to(DEVICE)
        out = self.model([t])[0]
        boxes = out["boxes"].cpu().numpy()
        scores = out["scores"].cpu().numpy()
        persons = []
        for box, sc in zip(boxes, scores):
            if sc < self.conf: continue
            x1,y1,x2,y2 = box; h=y2-y1
            if h < ih*self.min_h: continue
            x1c,y1c=max(0,int(x1)),max(0,int(y1))
            x2c,y2c=min(iw,int(x2)),min(ih,int(y2))
            persons.append({"bbox":(x1c,y1c,x2c,y2c),
                "crop":img.crop((x1c,y1c,x2c,y2c)),"conf":float(sc)})
        return sorted(persons, key=lambda p:p["bbox"][0])


# ============================================================
def analyze(img_path, matcher, teams=None):
    print(f"\n{'='*60}")
    print(f"📷 {Path(img_path).name}")
    print(f"{'='*60}")
    
    img = Image.open(img_path).convert("RGB")
    print(f"   尺寸: {img.size[0]}x{img.size[1]}")
    
    det = PlayerDetector()
    persons = det.detect(img)
    print(f"   👤 检测到 {len(persons)} 人")
    if not persons: return
    
    eng = FaceEngine()
    all_faces = []
    
    for i, p in enumerate(persons):
        crop = p["crop"]
        if crop.size[0]<30 or crop.size[1]<30: continue
        faces = eng.detect(np.array(crop))
        for f in faces:
            px,py = p["bbox"][0], p["bbox"][1]
            fw = f['bbox'][2]-f['bbox'][0]; fh = f['bbox'][3]-f['bbox'][1]
            all_faces.append({**f, 'player_idx':i,
                'global_bbox':[px+f['bbox'][0],py+f['bbox'][1],px+f['bbox'][2],py+f['bbox'][3]],
                'face_size':f'{fw:.0f}x{fh:.0f}'})
            print(f"   😀 球员#{i}: 面部 {fw:.0f}x{fh:.0f}px conf={f['score']:.2f}")
    
    print(f"\n   😀 面部检测: {len(all_faces)} 个")
    if not all_faces: return
    
    match_ok = 0
    for fr in all_faces:
        res = matcher.search(fr['embedding'], teams=teams, k=2, mins=0.20)
        if res:
            b=res[0]; match_ok+=1
            q = "✅" if b['match_score']>0.35 else ("🟡" if b['match_score']>0.25 else "🔴")
            print(f"   {q} #{b['player_number']} {b['player_name']:<22s} "
                  f"({b['team']}) score={b['match_score']:.3f}")
            if len(res)>1 and res[1]['match_score']>0.15:
                print(f"      备选: #{res[1]['player_number']} {res[1]['player_name']} "
                      f"({res[1]['team']}) score={res[1]['match_score']:.3f}")
        else:
            print(f"   ❓ 球员#{fr['player_idx']}: 无匹配")
    
    print(f"\n   📊 球员{len(persons)} | 面部{len(all_faces)} | 匹配{match_ok} "
          f"({match_ok/max(len(all_faces),1)*100:.0f}%)")
    return len(persons), len(all_faces), match_ok


# ============================================================
if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--match","-m",type=int,choices=[1,2,3,4])
    p.add_argument("--screenshot","-s",type=str)
    p.add_argument("--spain",action="store_true")
    args = p.parse_args()
    
    print("="*60)
    print("⚽ 世界杯球员识别 — 端到端人脸匹配 (Faiss V2)")
    print("="*60)
    
    matcher = FaceMatcherV2()
    
    if args.screenshot:
        analyze(args.screenshot, matcher)
    
    elif args.match:
        mid = args.match
        d, t1, t2 = MATCHES[mid]
        print(f"\n🏟 比赛{mid}: {t1} vs {t2}")
        print(f"   {t1} in DB={t1 in matcher.teams}, {t2} in DB={t2 in matcher.teams}")
        
        sss = sorted(d.glob("*.png"))
        print(f"   {len(sss)} 张截图")
        
        tp=tf=tm=0
        for ss in sss:
            r = analyze(ss, matcher, teams=(t1,t2))
            if r: tp+=r[0]; tf+=r[1]; tm+=r[2]
        
        print(f"\n{'='*60}")
        print(f"📊 汇总: {len(sss)}张 | {tp}球员 | {tf}面部 | {tm}匹配 "
              f"({tm/max(tf,1)*100:.0f}%)")
    
    elif args.spain:
        sp = Path(os.path.expanduser(
            "~/.workbuddy/clipboard-images/clipboard-2026-06-16T02-23-42-959Z-428756cd.jpg"))
        if sp.exists():
            print("\n⚠ 西班牙/佛得角不在DB中，仅测试面部检测")
            analyze(sp, matcher)
        else:
            print("❌ 截图不存在")
    
    else:
        # 默认: 比赛1第一张
        d,t1,t2 = MATCHES[1]
        ss = sorted(d.glob("*.png"))[0]
        print(f"\n🏟 比赛1: {t1} vs {t2}")
        print(f"   {t1} in DB={t1 in matcher.teams}, {t2} in DB={t2 in matcher.teams}")
        analyze(ss, matcher, teams=(t1,t2))
