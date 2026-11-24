import os, re, json, io, sys, urllib.request
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

import shutil
BASE = Path(__file__).resolve().parent
ROOT = BASE / "android"
LAYOUT = {
    "settings.gradle": "android/settings.gradle",
    "gradle.properties": "android/gradle.properties",
    "build.gradle": "android/build.gradle",
    "app.gradle": "android/app/build.gradle",
    "AndroidManifest.xml": "android/app/src/main/AndroidManifest.xml",
    "MainActivity.java": "android/app/src/main/java/com/webtoapk/app/MainActivity.java",
}
for _src, _dst in LAYOUT.items():
    _p = BASE / _dst
    _p.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(BASE / _src, _p)
(ROOT / "app/src/main/assets").mkdir(parents=True, exist_ok=True)

def die(m):
    print("ERROR:", m)
    sys.exit(1)

try:
    raw = json.loads(os.environ["CONFIG"])
except Exception:
    die("bad config")
if not isinstance(raw, dict):
    die("bad config")

def s(k, d=""):
    v = raw.get(k, d)
    return v if isinstance(v, str) else str(v)

def b(k, d=False):
    v = raw.get(k, d)
    return v if isinstance(v, bool) else d

name = re.sub(r"[\x00-\x1f<>&\"'\\%@?]", "", s("name", "My App")).strip()[:30] or "My App"
pkg = s("pkg", "com.myapp.app")
if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}", pkg):
    die("invalid package name")
url = s("url")
if not re.fullmatch(r"https?://[^\s\"'<>\\]+", url) or len(url) > 2000:
    die("invalid url")
ver = s("ver", "1.0.0")
if not re.fullmatch(r"[0-9A-Za-z.\-]{1,20}", ver):
    die("invalid version")
try:
    vc = int(s("vc", "1"))
except ValueError:
    die("invalid version code")
if not 1 <= vc <= 2100000000:
    die("invalid version code")
sdk = s("sdk", "26")
if sdk not in ("21", "24", "26", "29"):
    die("invalid sdk")
color = s("color", "#7c5cff")
if not re.fullmatch(r"#[0-9a-fA-F]{6}", color):
    die("invalid color")
ori = s("ori", "auto")
splash = s("splash", "fade")
ua = s("ua", "default")
if ori not in ("auto", "portrait", "landscape") or splash not in ("fade", "zoom", "slide-up", "pulse") or ua not in ("default", "desktop"):
    die("invalid option")

cfg = {"url": url, "color": color, "ori": ori, "splash": splash, "ua": ua}
for k in ("fs", "ptr", "off", "js", "dl", "cam", "push", "ext", "ss"):
    cfg[k] = b(k)
feed = s("feed", "").strip()
if feed and (not re.fullmatch(r"https?://[^\s\"'<>\\]+", feed) or len(feed) > 2000):
    die("invalid feed url")
cfg["feed"] = feed
aab = b("aab")

(ROOT / "app/src/main/assets/config.json").write_text(json.dumps(cfg))
(ROOT / "app.properties").write_text(f"APP_ID={pkg}\nVER_NAME={ver}\nVER_CODE={vc}\nMIN_SDK={sdk}\n")
res = ROOT / "app/src/main/res/values"
res.mkdir(parents=True, exist_ok=True)
(res / "strings.xml").write_text(f'<resources><string name="app_name">{name}</string></resources>')

perms = ""
if cfg["cam"]:
    perms += '<uses-permission android:name="android.permission.CAMERA"/>\n<uses-permission android:name="android.permission.RECORD_AUDIO"/>\n<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>\n<uses-feature android:name="android.hardware.camera" android:required="false"/>\n'
if cfg["push"]:
    perms += '<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>\n<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>\n'
mf = ROOT / "app/src/main/AndroidManifest.xml"
mf.write_text(mf.read_text().replace("<!--PERMS-->", perms))

def make_icon():
    icon_url = os.environ.get("ICON_URL", "").strip()
    if icon_url.startswith("https://"):
        try:
            req = urllib.request.Request(icon_url, headers={"User-Agent": "webtoapk"})
            data = urllib.request.urlopen(req, timeout=30).read(2_000_000)
            im = Image.open(io.BytesIO(data)).convert("RGBA")
            w, h = im.size
            m = min(w, h)
            l, t = (w - m) // 2, (h - m) // 2
            return im.crop((l, t, l + m, t + m))
        except Exception as e:
            print("icon failed:", e)
    im = Image.new("RGBA", (512, 512), color)
    try:
        d = ImageDraw.Draw(im)
        try:
            f = ImageFont.load_default(size=280)
        except TypeError:
            f = ImageFont.load_default()
        d.text((256, 256), name[0].upper(), fill="white", font=f, anchor="mm")
    except Exception as e:
        print("default icon text failed:", e)
    return im

icon = make_icon()
for folder, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    d = ROOT / f"app/src/main/res/mipmap-{folder}"
    d.mkdir(parents=True, exist_ok=True)
    icon.resize((px, px), Image.LANCZOS).save(d / "ic_launcher.png")

slug = re.sub(r"[^A-Za-z0-9]+", "-", name).strip("-") or "app"
with open(os.environ["GITHUB_OUTPUT"], "a") as f:
    f.write(f"aab={'true' if aab else 'false'}\nslug={slug}\n")
print("prepared", pkg, ver)
