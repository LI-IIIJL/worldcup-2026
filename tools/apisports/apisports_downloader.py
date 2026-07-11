#!/usr/bin/env python3
"""
api-sports.io Player Data & Photo Downloader
Fetches squad data and player photos for 2026 World Cup teams.

Usage:
  python apisports_downloader.py              # Fetch all 8 test teams
  python apisports_downloader.py --all-48     # Fetch all 48 World Cup teams
  python apisports_downloader.py --photos-only  # Only download photos (use cached JSON)
  python apisports_downloader.py --data-only    # Only fetch data, no photo download
"""

import os
import sys
import json
import time
import urllib.request
import urllib.error
from pathlib import Path

# API config
API_KEY = "a1171ce3f1e015c2deb20a3292be9a40"
BASE_URL = "https://v3.football.api-sports.io"
HEADERS = {"x-apisports-key": API_KEY}

# Path config
SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
DATA_DIR = PROJECT_ROOT / "data" / "apisports"
PHOTO_DIR = PROJECT_ROOT / "face_db" / "images"

# 48 World Cup teams — all national teams
ALL_TEAMS_NAMES = [
    # Group A
    "Mexico", "South Africa", "South Korea", "Czechia",
    # Group B
    "Canada", "Bosnia & Herzegovina", "Qatar", "Switzerland",
    # Group C
    "Brazil", "Morocco", "Haiti", "Scotland",
    # Group D
    "USA", "Paraguay", "Australia", "Turkey",
    # Group E
    "Germany", "Curacao", "Ivory Coast", "Ecuador",
    # Group F
    "Netherlands", "Japan", "Sweden", "Tunisia",
    # Group G
    "Belgium", "Egypt", "Iran", "New Zealand",
    # Group H
    "Spain", "Cape Verde", "Saudi Arabia", "Uruguay",
    # Group I
    "France", "Senegal", "Iraq", "Norway",
    # Group J
    "Argentina", "Algeria", "Austria", "Jordan",
    # Group K
    "Portugal", "Colombia", "Uzbekistan", "DR Congo",
    # Group L
    "England", "Croatia", "Ghana", "Panama",
]

# 队名 → slug 映射
def team_to_slug(name):
    return name.lower().replace(" ", "_").replace("&", "and").replace("'", "")

# Build team lookup dict {slug: (search_name, slug)}
# Will be populated during runtime via API search
ALL_TEAMS = {}


def api_call(endpoint, params=None, max_retries=3):
    """Make an api-sports.io API call with retry logic."""
    url = f"{BASE_URL}/{endpoint}"
    if params:
        param_str = "&".join(f"{k}={urllib.request.quote(str(v))}" for k, v in params.items())
        url += f"?{param_str}"
    
    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read())
            
            # Check for rate limit
            if "errors" in data and data["errors"]:
                err = str(data["errors"])
                if "rate" in err.lower() or "limit" in err.lower():
                    wait_time = (attempt + 1) * 10
                    print(f"  ⏳ Rate limited, waiting {wait_time}s...")
                    time.sleep(wait_time)
                    continue
                print(f"  ⚠ API error: {err}")
                return None
            
            return data
        except urllib.error.HTTPError as e:
            if e.code == 429:  # Rate limit
                wait_time = (attempt + 1) * 10
                print(f"  ⏳ Rate limited (429), waiting {wait_time}s...")
                time.sleep(wait_time)
                continue
            print(f"  ❌ HTTP {e.code}: {e.reason}")
            return None
        except Exception as e:
            print(f"  ❌ Error: {e}")
            if attempt < max_retries - 1:
                time.sleep(3)
            else:
                return None
    return None


def fetch_squad(team_id, team_name):
    """Fetch full squad data for a team."""
    print(f"  Fetching squad for {team_name} (ID={team_id})...")
    data = api_call("players/squads", {"team": team_id})
    
    if not data or not data.get("response"):
        print(f"  ❌ No squad data for {team_name}")
        return None
    
    squad = data["response"][0]
    players = squad.get("players", [])
    print(f"  ✅ Got {len(players)} players for {team_name}")
    
    return squad


def download_photo(url, filepath, max_retries=3):
    """Download a player photo with retry."""
    if filepath.exists():
        return True  # Already downloaded
    
    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=10) as resp:
                photo_data = resp.read()
            
            # Verify it's an image (not HTML error page)
            if len(photo_data) < 500:
                if attempt < max_retries - 1:
                    time.sleep(2)
                    continue
                return False
            
            filepath.write_bytes(photo_data)
            return True
        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(2)
            else:
                return False
    return False


def search_team_id(team_name):
    """Search for a national team's api-sports.io ID."""
    # Check cached index first
    index_path = DATA_DIR / "team_index.json"
    if index_path.exists():
        index = json.loads(index_path.read_text())
        for slug, info in index.get("teams", {}).items():
            if info["name"].lower() == team_name.lower():
                return info["id"], info["name"], slug

    url = f"{BASE_URL}/teams?search={urllib.request.quote(team_name)}"
    try:
        req = urllib.request.Request(url, headers={"x-apisports-key": API_KEY})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read())
    except Exception as e:
        print(f"    ❌ Search failed: {e}")
        return None, None, None

    for t in data.get("response", []):
        if t["team"]["national"] and "W" not in t["team"]["name"]:
            tid = t["team"]["id"]
            name = t["team"]["name"]
            slug = team_to_slug(name)
            return tid, name, slug
    return None, None, None


def process_teams_by_name(team_names, download_photos=True, fetch_data=True):
    """Process teams by name: search ID → fetch squad → download photos."""
    results = {}
    total_photos = 0
    total_players = 0
    api_calls = 0

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)

    for team_name in team_names:
        print(f"\n{'='*60}")
        print(f"Team: {team_name}")

        slug = team_to_slug(team_name)
        json_path = DATA_DIR / f"{slug}.json"
        squad = None
        players = []
        team_id = 0
        resolved_name = team_name

        # Try cache first
        if json_path.exists():
            squad = json.loads(json_path.read_text())
            players = squad.get("players", [])
            team_id = squad.get("team", {}).get("id", 0)
            resolved_name = squad.get("team", {}).get("name", team_name)
            print(f"  📂 Cached: {len(players)} players")
        elif fetch_data:
            # Search for team ID
            team_id, resolved_name, found_slug = search_team_id(team_name)
            api_calls += 1
            if not team_id:
                print(f"  ❌ Could not find team: {team_name}")
                continue
            slug = found_slug or slug
            json_path = DATA_DIR / f"{slug}.json"
            print(f"  🔍 Found: {resolved_name} (ID={team_id})")

            # Fetch squad
            squad = fetch_squad(team_id, resolved_name)
            api_calls += 1
            if not squad:
                continue
            players = squad["players"]
            squad["team"]["search_name"] = team_name
            json_path.write_text(json.dumps(squad, indent=2, ensure_ascii=False))
            print(f"  💾 Saved: {json_path}")
            total_players += len(players)
        else:
            print(f"  ⏭ No cache, skipping (--photos-only mode)")
            continue

        time.sleep(0.6)

        # Download photos
        if download_photos and players:
            team_photo_dir = PHOTO_DIR / slug
            team_photo_dir.mkdir(parents=True, exist_ok=True)

            success_count = 0
            for player in players:
                photo_url = player.get("photo", "")
                if not photo_url:
                    continue
                number = player.get("number", 0) or 0
                name = player.get("name", "unknown").replace(" ", "_")
                filename = f"{number:02d}_{name}.png"
                filepath = team_photo_dir / filename
                if download_photo(photo_url, filepath):
                    success_count += 1

            print(f"  🖼 {success_count}/{len(players)} photos")
            total_photos += success_count

        results[slug] = {"team_id": team_id, "team_name": resolved_name, "players": players}
        time.sleep(0.2)

    # Summary
    print(f"\n{'='*60}")
    print(f"SUMMARY: {len(results)} teams, {total_players} players, {total_photos} photos, {api_calls} API calls")
    return results


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="api-sports.io data downloader")
    parser.add_argument("--all-48", action="store_true", help="Fetch all 48 World Cup teams")
    parser.add_argument("--photos-only", action="store_true", help="Only download photos (use cached JSON)")
    parser.add_argument("--data-only", action="store_true", help="Only fetch data, skip photos")
    args = parser.parse_args()

    teams = ALL_TEAMS_NAMES
    download_p = not args.data_only
    fetch_d = not args.photos_only

    print(f"api-sports.io Downloader V2")
    print(f"  Teams: {len(teams)}")
    print(f"  Fetch data: {fetch_d}")
    print(f"  Download photos: {download_p}")
    print()

    process_teams_by_name(teams, download_photos=download_p, fetch_data=fetch_d)
