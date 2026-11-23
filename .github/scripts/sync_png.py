#!/usr/bin/env python3
"""
깃허브 사진 저장소(gapjatv/png)의 사진을 앱 기본 사진에 덮어 넣는다. (v2.8)

  png/phone/*.png → app/src/main/assets/templates_phone/   (핸드폰 앱에서 자동으로 올라온 사진)
  png/mumu/*.png  → app/src/main/assets/templates_mumu/    (뮤뮤 앱에서 자동으로 올라온 사진)

  • 저장소 맨 위(루트)에 있는 예전 사진들은 이미 앱에 넣어 두었으므로 건드리지 않는다.
  • 이 저장소(gapjamonitor)에 들어 있는 사진이 바탕이고, 같은 이름이면 png 저장소 사진이 이긴다.
    png 저장소에서 지운 사진은 다음 빌드부터 들어가지 않는다 (바탕 사진이 있으면 바탕 사진으로).
  • 이름·형식이 이상한 파일은 넣지 않는다 (PNG 가 아니거나, 보조 _3 이상, 너무 크거나 작은 사진).
  • 가져오지 못하면(인터넷·권한 문제) 경고만 남기고 바탕 사진으로 그대로 빌드한다.

마지막에 기본 자료 전체의 지문(asset_stamp.txt)을 만든다.
앱은 이 지문이 바뀌면 새 기본 사진을 풀어 놓는다 (AssetInstaller).
"""
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
REPO = "gapjatv/png"
MAPPING = [("phone", "templates_phone"), ("mumu", "templates_mumu")]
STAMP_DIRS = ["templates", "templates_phone", "templates_mumu", "display", "voices", "alarms"]

# 기본 사진({이름}.png) 또는 보조 1장({이름}_2.png) — 이름은 한글·영문·숫자·공백·밑줄
NAME_RE = re.compile(r"^[0-9A-Za-z가-힣 _]+\.png$")
OVER_LIMIT_RE = re.compile(r"_[3-9]\.png$")
MAX_BYTES = 3 * 1024 * 1024

summary = []


def say(msg):
    print(msg, flush=True)
    summary.append(msg)


def png_size(path):
    """PNG 가 맞으면 (가로, 세로), 아니면 None"""
    try:
        with open(path, "rb") as f:
            head = f.read(24)
        if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
            return None
        return struct.unpack(">II", head[16:24])
    except OSError:
        return None


def clone(dst):
    url = f"https://github.com/{REPO}.git"
    token = os.environ.get("PNG_TOKEN", "").strip()
    cmd = ["git"]
    if token:
        # 비공개 저장소면 PNG_TOKEN 비밀값으로 연다 (공개 저장소면 없어도 된다)
        cmd += ["-c", f"http.extraheader=Authorization: Bearer {token}"]
    cmd += ["clone", "--depth", "1", "--quiet", url, dst]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        say(f"⚠️ {REPO} 를 가져오지 못했습니다 — 저장소에 있던 사진으로 그대로 빌드합니다.")
        last = (r.stderr.strip().splitlines() or ["(오류 내용 없음)"])[-1]
        if token:
            last = last.replace(token, "***")     # 혹시라도 열쇠가 기록에 찍히지 않게
        say("   " + last)
        return False
    return True


def sync(src_root):
    total = 0
    for folder, asset_dir in MAPPING:
        src = os.path.join(src_root, folder)
        dst = os.path.join(ASSETS, asset_dir)
        if not os.path.isdir(src):
            say(f"· {REPO}/{folder}/ 폴더가 아직 없습니다 — 건너뜁니다.")
            continue
        os.makedirs(dst, exist_ok=True)
        added, replaced, same, skipped = [], [], 0, []
        for name in sorted(os.listdir(src)):
            p = os.path.join(src, name)
            if not os.path.isfile(p) or not name.lower().endswith(".png"):
                continue
            if not NAME_RE.match(name) or OVER_LIMIT_RE.search(name):
                skipped.append(f"{name}(이름)")
                continue
            size = os.path.getsize(p)
            wh = png_size(p)
            if wh is None or size > MAX_BYTES or size < 200 or not (16 <= wh[0] <= 2400 and 16 <= wh[1] <= 2400):
                skipped.append(f"{name}(형식)")
                continue
            out = os.path.join(dst, name)
            if os.path.exists(out):
                with open(out, "rb") as a, open(p, "rb") as b:
                    if a.read() == b.read():
                        same += 1
                        continue
                replaced.append(name)
            else:
                added.append(name)
            shutil.copyfile(p, out)
        total += len(added) + len(replaced)
        say(f"📷 {folder}/ → {asset_dir}: 새 사진 {len(added)}장 · 바뀐 사진 {len(replaced)}장 · 같음 {same}장"
            + (f" · 넣지 않음 {len(skipped)}장" if skipped else ""))
        for n in added:
            say(f"   + {n}")
        for n in replaced:
            say(f"   ~ {n}")
        for n in skipped:
            say(f"   ✗ {n}")
    return total


def write_stamp():
    h = hashlib.sha256()
    for d in STAMP_DIRS:
        base = os.path.join(ASSETS, d)
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            p = os.path.join(base, name)
            if not os.path.isfile(p):
                continue
            h.update(f"{d}/{name}\0".encode("utf-8"))
            with open(p, "rb") as f:
                h.update(hashlib.sha256(f.read()).digest())
    stamp = h.hexdigest()[:16]
    with open(os.path.join(ASSETS, "asset_stamp.txt"), "w", encoding="utf-8") as f:
        f.write(stamp + "\n")
    say(f"🔖 기본 자료 지문: {stamp}")


def main():
    tmp = tempfile.mkdtemp(prefix="png_")
    try:
        repo_dir = os.path.join(tmp, "png")
        if clone(repo_dir):
            n = sync(repo_dir)
            say(f"✅ {REPO} 사진 반영 완료 (바뀐 사진 {n}장)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    write_stamp()
    step = os.environ.get("GITHUB_STEP_SUMMARY")
    if step:
        with open(step, "a", encoding="utf-8") as f:
            f.write("### 깃허브 사진 저장소 반영\n\n```\n" + "\n".join(summary) + "\n```\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
