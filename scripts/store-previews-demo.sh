#!/usr/bin/env bash
# Runs in the guest, for scripts/store-previews.sh: demo data for the store widgets that
# otherwise only say how to set them up. `gh` answers like a signed-in account, Home
# Assistant's states are files that curl reads, `curl` answers Cursor's dashboard, and
# the Tesla tile reads its own fixture, go-limit reads an OpenCode database, `python3` answers
# google-calendar's feed script and lastfm's helper, and two sleeping `claude` processes stand in for claude-top's
# Claude Code instances. `python` answers
# codex-quota's and photoprism-random's scripts, widget-maker finds its script set up and a
# draft waiting, and `ssh` streams a Flipper menu to flipper-zero. `agy` answers agy-limits, agenda
# reads two calendars from local .ics files, 9g420's codex-quota reads a Codex session, and the
# voice widgets find themselves set up with a reply read and a sentence dictated. `python3` also
# answers simkl's helper, `ip` and `getent` put tailscale on a tailnet, ppt-maker, DEV LOGS,
# flashcards, script-launcher, layout-saver, radio, photo, gb-widget and neon-ytdlp find saved
# files, `fastfetch` greets terminal-profiles, cleaner finds caches worth clearing, `adb` answers
# private-space like a phone with its Private Space open, `perl` answers wifi-radar's scan with a
# made-up home network, `ssh` answers server-pulse's collector, speedtest finds a finished run,
# share-bridge a link that's up, and `websocat` answers nostr-inbox's relays. `ping` answers network
# with real-looking round trips (the emulator's own replies all say 0 ms), and `python3` answers vacuum's
# status like a robot on its dock. With "off", puts everything back.
set -u
demo=/tmp/store-previews-demo
ha=~/.config/omarchy-proot/home-assistant
cursor=~/.config/cursor/auth.json
tesla=~/.config/omarchy-tesla/fixture.json
opencode=~/.local/share/opencode/opencode.db
history=~/.claude/history.jsonl
photoprism=~/.config/photoprism-random/session.json
owlmaker=~/.local/share/owlmaker/VERSION
owlstatus=~/.cache/owlmaker/status.json
flipper=~/.config/owl-flipper/host
agy=~/.gemini/antigravity-cli/antigravity-oauth-token
agenda=~/.config/gcal-agenda/urls
voice=~/.local/share/oma-voice/store-version
voicestate=~/.cache/oma-voice/state.json
dictated=~/.cache/oma-speak/latest.txt
codex=~/.codex/sessions/demo/rollout-demo.jsonl
codexcache=~/.cache/codex-quota-community/account.json
apkver=~/.local/share/apk-maker/VERSION
apkbin=~/.local/bin/apk-maker
apkcache=~/.cache/apk-maker
simkl=~/.config/omarchy-simkl/token
pptver=~/.local/share/pptmaker/VERSION
pptsetup=~/.cache/ppt/setup.json
pptstatus=~/.cache/ppt/status.json
devlogsver=~/.local/share/oma-devlogs/store-version
devlogsbin=~/.local/bin/oma-devlogs
devlogserrors=~/.local/share/oma-devlogs/system_errors.py
flashcards=~/.local/share/omalauncher/flashcards/flashcards.json
scripts=~/.config/omalauncher/scripts
layout=~/.local/share/omalauncher-layout
layoutlog=~/.cache/owl-layout.log
radio=~/.cache/owl-radio
photo=~/.cache/omalauncher-photos/downloads/photo_demo.jpg
gameboy=~/.local/state/omalauncher/gameboy/screen.json
neondl=~/.cache/omalauncher-neondl
privspace=~/.cache/private-space.json
speedtest=~/.local/share/omalauncher/speedtest/status.json
bridge=~/.local/share/omalauncher/share-bridge/status.json
nostr=~/.cache/nostr-inbox/009ce97e22b376c406740f9b842f780ca2e92c2093a5f11d12f43f6150d8e6a1

# Moves a real file aside before the demo writes its own; "off" puts it back.
aside() { [ -f "$1" ] && [ ! -f "$1.before-demo" ] && mv "$1" "$1.before-demo"; mkdir -p "${1%/*}"; }

if [ "${1:-}" = off ]; then
  for p in $(cat "$demo/claude.pids" 2>/dev/null); do kill "$p" 2>/dev/null; rm -f ~/.claude/sessions/"$p".json; done
  pid=$(cat ~/.cache/owl-flipper/feed.pid 2>/dev/null) && kill -- "-$pid" 2>/dev/null
  [ -f "$demo/agenda-cache-new" ] && rm -rf ~/.cache/gcal-agenda
  [ -f "$demo/nostr-cache-new" ] && rm -rf ~/.cache/nostr-inbox
  [ -f "$demo/cleardarksky-cache-new" ] && rm -rf ~/.cache/cleardarksky
  rm -f "$nostr".*
  rm -f ~/.cache/owl-flipper/frame.json ~/.cache/photoprism-random/demo.jpg ~/.cache/owlmaker/pending/made-demo-moon.owl
  rm -f ~/.cache/uv/store-previews-demo ~/.cache/thumbnails/large/store-previews-demo.png
  for d in $(cat "$demo/cleaner-dirs" 2>/dev/null); do rm -rf "$d"; done
  rm -rf ~/Decks; [ -d ~/Decks.before-demo ] && mv ~/Decks.before-demo ~/Decks
  rm -rf "$layout"; [ -d "$layout.before-demo" ] && mv "$layout.before-demo" "$layout"
  rm -rf "$demo" /usr/local/bin/gh /usr/local/bin/curl /usr/local/bin/python3 /usr/local/bin/pgrep \
    /usr/local/bin/python /usr/local/bin/claude /usr/local/bin/ssh /usr/local/bin/agy /usr/local/bin/ip \
    /usr/local/bin/getent /usr/local/bin/fastfetch /usr/local/bin/yt-dlp /usr/local/bin/ffmpeg \
    /usr/local/bin/ffprobe /usr/local/bin/adb /usr/local/bin/perl /usr/local/bin/websocat \
    /usr/local/bin/ping
  for f in "$ha" "$cursor" "$tesla" "$opencode" "$history" "$photoprism" "$owlmaker" "$owlstatus" "$flipper" \
    "$agy" "$agenda" "$voice" "$voicestate" "$dictated" "$codex" "$codexcache" "$apkver" "$apkbin" \
    "$apkcache/setup.json" "$apkcache/apps.json" "$apkcache/job.json" "$apkcache/log.txt" "$simkl" \
    "$pptver" "$pptsetup" "$pptstatus" "$devlogsver" "$devlogsbin" "$devlogserrors" "$flashcards" \
    "$scripts/backup-dotfiles.sh" "$scripts/clean-downloads.sh" "$scripts/pull-repos.sh" \
    "$scripts/serve-here.sh" "$scripts/update-system.sh" "$layoutlog" "$radio/pid" "$radio/now" "$photo" \
    "$gameboy" "$neondl/state.json" "$neondl/version.txt" "$privspace" "$speedtest" "$bridge"; do
    [ -f "$f.before-demo" ] && mv "$f.before-demo" "$f" || rm -f "$f"
  done
  exit 0
fi

mkdir -p "$demo/api/states" /usr/local/bin
cat > /usr/local/bin/gh <<'EOF'
#!/bin/sh
case "$*" in
  auth*) exit 0 ;;
  api*) echo 4 ;;
  *review-requested*) echo 2 ;;
  search*) cat <<'JSON'
[{"title":"Add previews for every widget","url":"https://github.com/","repository":{"name":"omarchy-widgets"},"updatedAt":"2026-10-07T09:00:00Z"},
 {"title":"Store: categories, search and a detail page","url":"https://github.com/","repository":{"name":"omarchy-widgets"},"updatedAt":"2026-10-06T09:00:00Z"},
 {"title":"Fix wrapping of long command lines","url":"https://github.com/","repository":{"name":"omarchy-proot"},"updatedAt":"2026-10-05T09:00:00Z"},
 {"title":"Bump the checker to owl 2","url":"https://github.com/","repository":{"name":"omarchy-widgets"},"updatedAt":"2026-10-04T09:00:00Z"}]
JSON
    ;;
esac
EOF
chmod +x /usr/local/bin/gh

state() { printf '{"entity_id":"%s","state":"%s","attributes":{"friendly_name":"%s","unit_of_measurement":"%s"}}' "$1" "$2" "$3" "${4:-}" > "$demo/api/states/$1"; }
state light.living_room on "Living room"
state light.kitchen off "Kitchen"
state sensor.indoor 21.5 "Indoor" "°C"
state sensor.power 342 "Power use" "W"
state lock.front_door locked "Front door"
state switch.coffee_machine on "Coffee machine"
aside "$ha"
printf 'file://%s\ndemo\nlight.living_room\nlight.kitchen\nsensor.indoor\nsensor.power\nlock.front_door\nswitch.coffee_machine\n' "$demo" > "$ha"

# Cursor's Pro+ plan, from the cursor-plan author's own picture. Everything else goes to curl.
aside "$cursor"
echo '{"accessToken":"demo"}' > "$cursor"
cat > /usr/local/bin/curl <<'EOF'
#!/bin/sh
case "$*" in
  *DashboardService/GetCurrentPeriodUsage*) echo '{"billingCycleEnd":"1792627200000","planUsage":{"totalPercentUsed":77,"apiPercentUsed":96,"includedSpend":7000,"limit":7000}}' ;;
  *DashboardService/GetPlanInfo*) echo '{"planInfo":{"planName":"Pro+","price":"$60/mo"}}' ;;
  *) exec /usr/bin/curl "$@" ;;
esac
EOF
chmod +x /usr/local/bin/curl

# The made-up car from the tesla README: Bluebird, parked at 54% with an 80% charge limit.
aside "$tesla"
cat > "$tesla" <<'JSON'
{"name":"Bluebird","vin":"5YJ3E1EA0PF000001","car_type":"model 3","state":"online","shift":"P","driving":false,
 "speed_unit":"mph","battery":54,"charge_limit":80,"charging":"Disconnected","range":168,"range_unit":"mi",
 "odometer":12840,"locked":true,"sentry":false,"climate_on":false,"inside_temp":72,"outside_temp":64,"temp_unit":"°F",
 "tyres":{"min":41,"max":42},"tyre_fl":42,"tyre_fr":42,"tyre_rl":41,"tyre_rr":41,"tyre_unit":"psi",
 "software":"2026.32.6","open":[],"vehicles":[{"vin":"5YJ3E1EA0PF000001","name":"Bluebird"}]}
JSON

# A day of OpenCode Go on GLM-5.3-Flash, like the go-limit author's picture: quiet until a few
# hours ago, then bursts, about $1.50 in the 5-hour window.
aside "$opencode"
now=$(date +%s)
sqlite3 "$opencode" "
  create table message (id integer primary key, time_created integer, data text);
  with recursive n(i) as (select 0 union all select i + 1 from n where i < 39)
  insert into message (time_created, data)
  select ($now - (i * 37 % 41) * 900 - 60) * 1000,
    json_object('providerID', 'opencode-go', 'role', 'assistant', 'modelID', 'glm-5.3-flash',
      'tokens', json_object('input', 150000 + i * 3000, 'output', 15000, 'reasoning', 4000,
        'cache', json_object('read', 900000, 'write', 0)))
  from n"

# Two calendars of made-up events over the next few days, in place of the Google feeds the
# calendar's saved state points at (scripts/store-previews.sh seeds that state).
cat > "$demo/gcal.py" <<'PY'
import json, time
from datetime import datetime, timedelta
now = time.time()
day = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
def at(days, h, m=0): return int((day + timedelta(days=days, hours=h, minutes=m)).timestamp())
def soon(hours): return int(now // 1800 * 1800 + hours * 3600)
timed = [
    ("Call with Mom", "", 0, soon(1), 30), ("Standup", "", 1, at(1, 9, 30), 15),
    ("Lunch with Sam", "Café Lindt", 0, at(1, 12, 30), 60), ("Design review", "", 1, at(1, 16), 60),
    ("Dentist", "Dr. Weber", 0, at(2, 8, 30), 45), ("1:1 with Alex", "", 1, at(2, 14), 30),
    ("Sprint planning", "", 1, at(3, 10), 90), ("Climbing", "Boulderhalle", 0, at(3, 19), 120),
]
events = [{"t": t, "l": l, "c": c, "d": datetime.fromtimestamp(s).date().isoformat(), "s": s, "e": s + mins * 60, "a": False}
          for t, l, c, s, mins in timed]
events.append({"t": "Mia's birthday", "l": "", "c": 0, "d": (day + timedelta(days=1)).date().isoformat(), "a": True, "s": 0, "e": 0, "n": 1})
events.sort(key=lambda x: (x["d"], x["s"]))
print(json.dumps({"events": events, "errors": {}, "names": {"0": "Personal", "1": "Work"}}, ensure_ascii=False))
PY
# A Last.fm listener for abinanthankv's lastfm: one track playing, the rest scrobbled earlier.
cat > "$demo/lastfm.py" <<'PY'
import json, time
now = int(time.time())
played = [("Radiohead", "Weird Fishes/Arpeggi", 0), ("Bonobo", "Kerala", 4), ("Khruangbin", "Maria También", 9),
          ("Phoebe Bridgers", "Motion Sickness", 13), ("Tame Impala", "Let It Happen", 21), ("Fleetwood Mac", "Dreams", 29),
          ("Daft Punk", "Digital Love", 34), ("Massive Attack", "Teardrop", 40), ("Beach House", "Space Song", 46),
          ("Nick Drake", "Pink Moon", 52)]
tracks = [{"artist": a, "title": t, "url": "https://www.last.fm/music/" + a.replace(" ", "+"),
           "at": now - m * 60, "nowplaying": m == 0, "art": ""} for a, t, m in played]
print(json.dumps({"tracks": tracks, "stats": {"scrobbles": 48213, "artists": 2147, "albums": 4380, "tracks": 11952,
                  "url": "https://www.last.fm/user/demo"}}, ensure_ascii=False))
PY
# simkl: signed in, with a made-up week of TV, anime and a film from `python3`, each with a
# plain gradient poster.
aside "$simkl"
echo demo > "$simkl"
cat > "$demo/simkl.py" <<'PY'
import json, os, struct, time, zlib
from datetime import datetime, timedelta
posters = "/tmp/store-previews-demo/simkl"
os.makedirs(posters, exist_ok=True)
day = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
def at(days, h, m=0): return int((day + timedelta(days=days, hours=h, minutes=m)).timestamp())
# A poster per show: a plain two-color gradient, as a PNG written by hand.
def poster(name, top, bottom, w=80, h=120):
    path = "%s/%s.png" % (posters, name)
    if not os.path.exists(path):
        rows = b"".join(b"\0" + bytes(round(top[c] + (bottom[c] - top[c]) * y / (h - 1)) for _ in range(w) for c in range(3)) for y in range(h))
        chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
        with open(path, "wb") as f:
            f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))
    return path
shows = [
    ("Northern Lines", "shows", "S3E4 · The Night Train", at(0, 21), "", (40, 60, 110), (10, 15, 35)),
    ("Kaze no Tabi", "anime", "E11 · Across the Pass", at(1, 17, 30), "", (240, 170, 90), (150, 60, 70)),
    ("The Lighthouse", "shows", "S1E8 · Low Tide", at(2, 22), "season finale", (90, 140, 150), (20, 40, 50)),
    ("Paper Moons", "movies", "in cinemas", at(3, 0), "", (230, 210, 170), (120, 90, 60)),
    ("Copper & Salt", "shows", "S2E2 · Old Debts", at(4, 21), "", (170, 90, 50), (50, 25, 20)),
    ("Mecha Garden", "anime", "E4 · Sprouts", at(6, 16), "", (120, 200, 120), (30, 80, 60)),
]
items = [{"t": t, "kind": k, "title": title, "sub": sub, "url": "https://simkl.com/", "fin": fin,
          "type": "movie" if k == "movies" else "series", "imdb": "", "tmdb": "", "season": None, "episode": None,
          "poster": poster("%d" % i, top, bottom)} for i, (title, k, sub, t, fin, top, bottom) in enumerate(shows)]
print(json.dumps({"user": "Sam", "items": items}, ensure_ascii=False))
PY
# vacuum: a robot charged on its dock after a sweep and mop of the flat.
printf '%s\n' '{"ok":true,"ip":"192.168.1.40","did":"123456789","status":4,"status_text":"Charging","busy":false,"fault":2103,"fault_warn":false,"fault_text":"docked","battery":87,"mode":1,"mode_text":"Sweep + mop","sweep_type":0,"sweep_text":"Global","suction":2,"suction_text":"Medium","water":1,"water_text":"Mid","mop_route":1,"mop_text":"Y","minutes":42,"area":58,"map_url":"","consumables":{"side_brush":71,"main_brush":84,"filter":63,"mop":38}}' > "$demo/vacuum.json"
cat > /usr/local/bin/python3 <<'EOF'
#!/bin/sh
case "$*" in
  *owl-gcal*) exec /usr/bin/python3 /tmp/store-previews-demo/gcal.py ;;
  *lastfm.py\ fetch*) exec /usr/bin/python3 /tmp/store-previews-demo/lastfm.py ;;
  *simkl.py\ fetch*) exec /usr/bin/python3 /tmp/store-previews-demo/simkl.py ;;
  *vacuum.py\ status*) cat /tmp/store-previews-demo/vacuum.json ;;
  *) exec /usr/bin/python3 "$@" ;;
esac
EOF
chmod +x /usr/local/bin/python3

# Claude Code instances for claude-top: processes named claude (python, so the memory is
# real), one busy on and off, with the session files and prompts Claude Code would keep.
# Every proot kills what it runs when it exits, this script's too, so `pgrep` starts them
# from the widget's own command, the first time it looks for claude.
aside "$history"
cat > "$demo/claude.sh" <<'EOF'
demo=/tmp/store-previews-demo
mkdir -p "$demo/bin" ~/.claude/sessions ~/Projects/omarchy-widgets ~/Projects/website
ln -sf /usr/bin/python3 "$demo/bin/claude"
ms=$(( $(date +%s) * 1000 ))
claude() {
  local cwd=$1 sid=$2 status=$3 mb=$4 busy=$5 prompt=$6 ago=$7
  (cd "$cwd" && exec setsid "$demo/bin/claude" -c "
import time
x = bytearray($mb << 20)
for i in range(0, len(x), 4096): x[i] = 1
while True:
    t = time.time() + $busy
    while time.time() < t: pass
    time.sleep(1 - $busy)" </dev/null >/dev/null 2>&1) &
  local pid=$!
  echo "$pid" >> "$demo/claude.pids"
  printf '{"sessionId":"%s","name":"%s","status":"%s","cwd":"%s","version":"2.4.1","statusUpdatedAt":%s,"startedAt":%s}\n' \
    "$sid" "${cwd##*/}" "$status" "$cwd" $(( ms - 40000 )) $(( ms - ago * 60000 )) > ~/.claude/sessions/"$pid".json
  printf '{"display":"%s","sessionId":"%s"}\n' "$prompt" "$sid" >> ~/.claude/history.jsonl
}
claude ~/Projects/omarchy-widgets demo-widgets busy 410 0.3 "render the themed previews for the new widgets" 52
claude ~/Projects/website demo-website idle 280 0 "fix the header on small screens" 140
sleep 0.5
EOF
cat > /usr/local/bin/pgrep <<'EOF'
#!/bin/sh
if [ "$*" = "-x claude" ] && [ -f /tmp/store-previews-demo/claude.sh ] && [ ! -f /tmp/store-previews-demo/claude.pids ]; then
  bash /tmp/store-previews-demo/claude.sh
fi
exec /usr/bin/pgrep "$@"
EOF
chmod +x /usr/local/bin/pgrep

# rcrohmana's codex-quota and photoprism-random hand their script to `python -`: a Plus account part way
# through both limits, and a photo of Moraine Lake (public domain, Wikimedia Commons).
aside "$photoprism"
echo '{"url": "https://photos.example.com", "username": "demo", "token": "demo", "preview": "public", "scope": "all", "album": ""}' > "$photoprism"
mkdir -p ~/.cache/photoprism-random
/usr/bin/curl -sf -A "omalauncher-store-previews" -o ~/.cache/photoprism-random/demo.jpg \
  "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Moraine_Lake_17092005.jpg/960px-Moraine_Lake_17092005.jpg"
cat > /usr/local/bin/python <<'EOF'
#!/bin/sh
if [ "$*" = - ]; then
  s=$(cat)
  now=$(date +%s)
  case "$s" in
    *wham/usage*) printf '{"five":{"pct":38,"reset":%s},"week":{"pct":61,"reset":%s},"plan":"plus","checked":%s,"error":null}\n' $((now + 7920)) $((now + 273600)) "$now"; exit 0 ;;
    *photoprism-random*) echo ~/.cache/photoprism-random/demo.jpg; exit 0 ;;
  esac
  printf '%s\n' "$s" | exec /usr/bin/python -
fi
exec /usr/bin/python "$@"
EOF
chmod +x /usr/local/bin/python

# widget-maker: its script set up, Claude Code on the PATH, and a finished draft waiting.
aside "$owlmaker"
echo 7 > "$owlmaker"
printf '#!/bin/sh\nexit 0\n' > /usr/local/bin/claude
chmod +x /usr/local/bin/claude
mkdir -p ~/.cache/owlmaker/pending
cat > ~/.cache/owlmaker/pending/made-demo-moon.owl <<'EOF'
widget "moon phase" {
  meta { description: "Tonight's moon phase as a big emoji with its name"; version: "1.0"; license: "MIT"; category: "time" }
  Text { text: "🌔"; fontSize: 48; align: center }
}
EOF
aside "$owlstatus"
printf '{"state":"done","req":"moon phase as a big emoji with its name below","file":"made-demo-moon.owl","summary":"","error":"","agent":"claude","edit":"","t":%s}\n' "$(date +%s)" > "$owlstatus"

# flipper-zero: `ssh` answers the bridge's stream with one frame of the main menu.
aside "$flipper"
echo flipper@desktop > "$flipper"
/usr/bin/python3 - > "$demo/flipper.json" <<'PY'
import json
bits = bin(int("0000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000040000700000000000000000000000000401e03001ddc000000000000000000004033dbc036dbe0000000000000000000403cdb6fb0fac0000000000000000000400edb603ed9800000000000000000004026db6036db60000000000000000000403c7fc01fdfe00000000000000000004000000000000000000000000000000040000000000000000000000000000000400000000000000000000000000000004000000000000000000000000000000040000000000000000000000000000000400c73e0700000000000000000000000403cdb0031dc007dfbdf000000000000400c1bc036dbe036c18d800000000000400c33603cfac036f18d800000000000400c606038d9803cc18d800000000000400cda603cdb6036c18d800000000000403ffbc037dfe07be3df000000000000400000000000000000000000000000004000000000000000000000000000000043fffffffffffffffffffffffffffff847fffffffffffffffffffffffffffffce7fffffffffffffffffffffffffffffce7fffffffffffffffffffffffffffffce7c8061ffffffffffffffffffffffffce7c53c9ffffffffffffffffffffffffce7c50cfffffffffffffffffffffffffce7c93cfffffffffffffffffffffffffce7c93c9ffffffffffffffffffffffffce7cc1e3ffffffffffffffffffffffffce7fffffffffffffffffffffffffffffce3fffffffffffffffffffffffffffff8e0000000000000000000000000000000e0000000000000000000000000000000e0000000000000000000000000000000400000e0000003800000000000000000403c01800000018000000000000000004019b3eddcddc78000000000000000004018d98776776d8000000000000000004018d9861e63ed8000000000000000004018d98636630d800000000000000000403cdbef3ff1e7c00000000000000000400000000000000000000000000000004000000000000000000000000000000040000000000000000000000000000000400000000000000000000000000000004000000000000000000000000000000040000000000000000000000000000000401df3c70000000000000000000000004036d98d8000000000000000000000004030d98d800000000000000000000000403ef18d8000000000000000000000004036c18d800000000000000000000000401fe3c70000000000000000000000004000000000000000000000000000000040000000000000000000000000000000400000000000000000000000000000004000000000000000000000000000000040000000000000000000000000000000400000000000000000000000000000000", 16))[2:].zfill(8192)
rows = ["".join("x" if b == "1" else "." for b in bits[i:i + 128]) for i in range(0, 8192, 128)]
print(json.dumps({"ok": True, "connected": True, "name": "Flipper", "fw": "1.3.4", "battery": 84, "charging": False, "rows": rows}, separators=(",", ":")))
PY
cat > /usr/local/bin/ssh <<'EOF'
#!/bin/sh
case "$*" in
  *server-pulse-status) exec sh /tmp/store-previews-demo/server-pulse.sh ;;
  *"-O check"*) exit 0 ;;
  */stream*) cat /tmp/store-previews-demo/flipper.json; exec sleep 600 ;;
  *) exec /usr/bin/ssh "$@" ;;
esac
EOF
chmod +x /usr/local/bin/ssh

# agy-limits: `agy` answers /usage like a signed-in account part way through its limits.
aside "$agy"
echo demo > "$agy"
cat > /usr/local/bin/agy <<'EOF'
#!/bin/sh
at() { date -u -d "@$(( $(date +%s) + $1 ))" +%Y-%m-%dT%H:%M:%SZ; }
cat <<JSON
{"command":{"data":{"groups":[
 {"name":"Gemini Models","buckets":[{"window":"5h","remaining_fraction":0.58,"reset_time":"$(at 8400)"},{"window":"weekly","remaining_fraction":0.81,"reset_time":"$(at 302400)"}]},
 {"name":"Claude and GPT Models","buckets":[{"window":"5h","remaining_fraction":0.27,"reset_time":"$(at 5100)"},{"window":"weekly","remaining_fraction":0.64,"reset_time":"$(at 302400)"}]}]}}}
JSON
EOF
chmod +x /usr/local/bin/agy

# agenda: two made-up calendars over the next days as .ics files, read through file:// URLs,
# with a weekly event so the recurrence goes through the real parser too.
[ -d ~/.cache/gcal-agenda ] || touch "$demo/agenda-cache-new"
/usr/bin/python3 - "$demo" <<'PY'
import sys
from datetime import datetime, timedelta
demo = sys.argv[1]
day = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
def stamp(days, h, m=0): return (day + timedelta(days=days, hours=h, minutes=m)).strftime("%Y%m%dT%H%M%S")
def date(days): return (day + timedelta(days=days)).strftime("%Y%m%d")
def cal(events):
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//store previews//EN"]
    for i, e in enumerate(events):
        lines += ["BEGIN:VEVENT", "UID:demo-%d@previews" % i, "DTSTAMP:20260101T000000Z"] + e + ["END:VEVENT"]
    return "\r\n".join(lines + ["END:VCALENDAR"]) + "\r\n"
personal = [
    ["SUMMARY:Call with Mom", "DTSTART:" + stamp(0, 19), "DTEND:" + stamp(0, 19, 30)],
    ["SUMMARY:Mia's birthday", "DTSTART;VALUE=DATE:" + date(1), "DTEND;VALUE=DATE:" + date(2)],
    ["SUMMARY:Dentist", "LOCATION:Dr. Weber", "DTSTART:" + stamp(2, 8, 30), "DTEND:" + stamp(2, 9, 15)],
    ["SUMMARY:Climbing", "LOCATION:Boulderhalle", "DTSTART:" + stamp(3, 19), "DTEND:" + stamp(3, 21)],
    ["SUMMARY:Farmers market", "DTSTART:" + stamp(5, 10), "DTEND:" + stamp(5, 12)],
]
work = [
    ["SUMMARY:Standup", "DTSTART:" + stamp(1, 9, 30), "DTEND:" + stamp(1, 9, 45), "RRULE:FREQ=WEEKLY;COUNT=4"],
    ["SUMMARY:Design review", "DTSTART:" + stamp(1, 14), "DTEND:" + stamp(1, 15)],
    ["SUMMARY:Sprint planning", "DTSTART:" + stamp(4, 10), "DTEND:" + stamp(4, 11, 30)],
]
open(demo + "/personal.ics", "w").write(cal(personal))
open(demo + "/work.ics", "w").write(cal(work))
PY
aside "$agenda"
printf 'Personal = file://%s/personal.ics\nWork = file://%s/work.ics\n' "$demo" "$demo" > "$agenda"

# terminal-voice and dictation: set up (the version their widget file checks for), the last
# Codex reply read aloud, and a sentence dictated. The voice service itself isn't installed, so
# nothing overwrites these while the previews are drawn.
aside "$voice"
printf '%s' '3.4-1acdf3a4' > "$voice"
aside "$voicestate"
printf '{"source":"codex","status":"播放完成","status_ui":"Playback complete","awaiting":false,"reply_at":%s,"summary":"Fixed the form validation on the login page and added two tests for an empty password; all tests pass."}\n' "$(( $(date +%s) - 240 ))" > "$voicestate"
aside "$dictated"
printf '%s' 'Make the title on the home page bold, then run the tests again.' > "$dictated"

# 9g420's codex-quota reads the rate limits Codex writes into its session files: a Plus account
# part way through both limits. Its saved snapshot goes aside so it reads this one.
aside "$codexcache"
aside "$codex"
now=$(date +%s)
printf '{"timestamp":"%s","type":"event_msg","payload":{"type":"token_count","rate_limits":{"limit_id":"codex","primary":{"used_percent":38,"window_minutes":300,"resets_at":%s},"secondary":{"used_percent":61,"window_minutes":10080,"resets_at":%s}}}}\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" $((now + 7920)) $((now + 273600)) > "$codex"

# apk-maker: its backend set up (a version above any it would reinstall), every agent installed, nothing
# missing, and three apps built, the last one just installed. Without this the tile shows its installer.
aside "$apkver"
echo 999 > "$apkver"
aside "$apkbin"
cat > "$apkbin" <<'EOF'
#!/bin/sh
case "${1:-}" in
  agents) echo "claude codex opencode" ;;
  adb) echo offline ;;
esac
exit 0
EOF
chmod +x "$apkbin"
for f in setup.json apps.json job.json log.txt; do aside "$apkcache/$f"; done
now=$(date +%s)
printf '{"state":"done","step":5,"steps":5,"msg":"ready","updated":%s,"pid":0,"version":14}\n' $((now - 86400)) > "$apkcache/setup.json"
app() { printf '{"slug":"%s","label":"%s","pkg":"dev.apkmaker.%s","agent":"%s","status":"installed","apk":"%s.apk","created":%s,"updated":%s,"history":[{"t":%s,"agent":"%s","prompt":"%s"}]}' "$1" "$2" "$1" "$3" "$1" "$4" "$4" "$4" "$3" "$5"; }
printf '[%s,%s,%s]\n' \
  "$(app tipsplit TipSplit claude $((now - 600)) "a tip calculator that splits the bill between friends")" \
  "$(app habits Habits codex $((now - 90000)) "a habit tracker with a streak per habit")" \
  "$(app game2048 2048 opencode $((now - 260000)) "the 2048 puzzle game with swipe controls")" > "$apkcache/apps.json"
printf '{"kind":"app","slug":"tipsplit","agent":"claude","prompt":"a tip calculator that splits the bill between friends","phase":"done","msg":"installed on this phone","started":%s,"updated":%s,"at":"installing","pid":0}\n' $((now - 840)) $((now - 600)) > "$apkcache/job.json"
printf '%s\n' "== [agent] claude is writing the app" "→ MainActivity.kt" "→ BillScreen.kt" "== [building] gradle assembleDebug" "BUILD SUCCESSFUL in 1m 52s" "== [installing] installing on this phone" "== [done] installed on this phone" > "$apkcache/log.txt"

# gbenselum's tailscale: `ip` answers like a phone with the VPN up (an address on tun0 and five
# peers as host routes on it) and `getent` names them through MagicDNS. Made-up 100.x addresses.
cat > "$demo/tailnet" <<'EOF'
100.71.203.18 framework.tail4c2e1f.ts.net
100.88.14.102 homelab.tail4c2e1f.ts.net
100.101.57.33 macbook-air.tail4c2e1f.ts.net
100.97.180.6 pixel-tablet.tail4c2e1f.ts.net
100.115.42.91 raspberrypi.tail4c2e1f.ts.net
EOF
cat > /usr/local/bin/ip <<'EOF'
#!/bin/sh
case "$*" in
  "-o -4 route show table all")
    echo "100.64.0.0/10 dev tun0 table 52"
    echo "100.100.100.100 dev tun0 table 52"
    while read -r ip name; do echo "$ip dev tun0 table 52"; done < /tmp/store-previews-demo/tailnet ;;
  "-o -4 addr show")
    echo "1: lo    inet 127.0.0.1/8 scope host lo"
    echo "27: tun0    inet 100.86.12.40/32 scope global tun0" ;;
  *) exec /usr/bin/ip "$@" ;;
esac
EOF
cat > /usr/local/bin/getent <<'EOF'
#!/bin/sh
if [ "$1" = hosts ]; then
  line=$(awk -v ip="$2" '$1 == ip' /tmp/store-previews-demo/tailnet)
  [ -n "$line" ] && { echo "$line"; exit 0; }
fi
exec /usr/bin/getent "$@"
EOF
chmod +x /usr/local/bin/ip /usr/local/bin/getent

# ppt-maker: set up (the version its widget file checks for, Claude Code is widget-maker's `claude`
# above), a deck on vaccines just made and four older ones. A real ~/Decks goes aside as a whole.
aside "$pptver"
echo 5 > "$pptver"
aside "$pptsetup"
aside "$pptstatus"
[ -d ~/Decks ] && [ ! -e ~/Decks.before-demo ] && mv ~/Decks ~/Decks.before-demo
mkdir -p ~/Decks
now=$(date +%s)
deck() { touch -d "@$((now - $2))" ~/Decks/"$1-$(date -d "@$((now - $2))" +%m%d-%H%M).pptx"; }
deck How-vaccines-work 300
deck Intro-to-sourdough 90000
deck Q3-team-update 262000
deck Berlin-weekend-trip 520000
deck The-history-of-Unix 1040000
# t 0: the widget's notified starts at 0, so it doesn't post "deck ready" while it's drawn.
printf '{"state":"done","topic":"How vaccines work","file":"%s","slides":12,"t":0}\n' \
  "$(cd ~/Decks && ls -t | head -1)" > "$pptstatus"

# DEV LOGS: set up (the version its widget file checks for), with stand-ins for its status
# command and error collector: a week of agent log entries, the last one from earlier today, and
# two system errors. Its other commands answer {}, which only the full screen asks for.
aside "$devlogsver"
printf '%s' '2.0-0c7f884e9fb3' > "$devlogsver"
aside "$devlogsbin"
cat > "$devlogsbin" <<'PY'
import datetime, json, sys
now = datetime.datetime.now()
if sys.argv[1:] == ["status"]:
    at = (now - datetime.timedelta(minutes=38)).strftime("%H:%M")
    print(json.dumps({"notes": 7, "entries": 23, "day": now.date().isoformat(), "updated": now.strftime("%H:%M:%S"),
                      "latest": at + " · [weather-app] Fixed the hourly chart skipping midnight; added a test, all 41 pass."}))
else:
    print("{}")
PY
aside "$devlogserrors"
printf '%s\n' 'print("{\"error_total\": 2}")' > "$devlogserrors"

# hirschnase's flashcards: a Spanish deck part way through, some cards marked to come up more
# or less, and two left out of learning.
aside "$flashcards"
cat > "$flashcards" <<'JSON'
{"nextId":25,"name":"Spanish basics","cards":[
 {"id":1,"front":"la manzana","back":"the apple","bias":"normal"},{"id":2,"front":"el perro","back":"the dog","bias":"less"},
 {"id":3,"front":"la biblioteca","back":"the library","bias":"more"},{"id":4,"front":"despertarse","back":"to wake up","bias":"more"},
 {"id":5,"front":"el ayuntamiento","back":"the town hall","bias":"more"},{"id":6,"front":"la cuenta","back":"the bill","bias":"normal"},
 {"id":7,"front":"hola","back":"hello","bias":"never"},{"id":8,"front":"gracias","back":"thank you","bias":"never"},
 {"id":9,"front":"el tenedor","back":"the fork","bias":"normal"},{"id":10,"front":"la estación","back":"the station","bias":"normal"},
 {"id":11,"front":"a menudo","back":"often","bias":"more"},{"id":12,"front":"todavía","back":"still, yet","bias":"more"},
 {"id":13,"front":"el gato","back":"the cat","bias":"less"},{"id":14,"front":"la llave","back":"the key","bias":"normal"},
 {"id":15,"front":"el sobre","back":"the envelope","bias":"normal"},{"id":16,"front":"aprovechar","back":"to make the most of","bias":"more"},
 {"id":17,"front":"la sombra","back":"the shade","bias":"normal"},{"id":18,"front":"el mercado","back":"the market","bias":"less"},
 {"id":19,"front":"pedir","back":"to order, to ask for","bias":"normal"},{"id":20,"front":"el desayuno","back":"breakfast","bias":"less"},
 {"id":21,"front":"la izquierda","back":"the left","bias":"normal"},{"id":22,"front":"quedarse","back":"to stay","bias":"normal"},
 {"id":23,"front":"el billete","back":"the ticket","bias":"normal"},{"id":24,"front":"sin embargo","back":"however","bias":"more"}]}
JSON

# mr-ples's script-launcher: a few everyday scripts in its folder, edited over the last weeks.
for s in backup-dotfiles clean-downloads pull-repos serve-here update-system; do aside "$scripts/$s.sh"; done
script() { printf '#!/bin/sh\n# %s\n%s\n' "$2" "$3" > "$scripts/$1.sh"; chmod +x "$scripts/$1.sh"; touch -d "$4 days ago" "$scripts/$1.sh"; }
script backup-dotfiles "copy ~/.config to the backup folder" 'rsync -a --delete ~/.config/ ~/backup/config/' 2
script clean-downloads "delete downloads older than 30 days" 'find ~/Downloads -type f -mtime +30 -delete' 9
script pull-repos "git pull every repo in ~/Projects" 'for d in ~/Projects/*/; do git -C "$d" pull --ff-only; done' 0
script serve-here "share this folder on port 8000" 'exec python -m http.server 8000' 16
script update-system "pacman -Syu, then trim the package cache" 'pacman -Syu && paccache -rk1' 5

# jrpixel666's layout-saver: a layout saved four times this week and uploaded, the tile itself
# the one widget added since. The helper's own files are ignored, so only widgets count as unsaved.
[ -d "$layout/.git" ] && [ ! -d "$layout.before-demo" ] && mv "$layout" "$layout.before-demo"
rm -rf "$layout"
mkdir -p "$layout/widgets" "$layout/style"
aside "$layoutlog"
(
  cd "$layout" && git init -q -b main && git config user.name demo && git config user.email demo@localhost
  printf '%s\n' owl-layout install.sh README.md 'packages-*.txt' > .gitignore
  set -- ~/.config/omalauncher/widgets/*.owl
  commit() {
    local t=$(( $(date +%s) - $1 * 3600 ))
    git add -A
    GIT_AUTHOR_DATE="@$t" GIT_COMMITTER_DATE="@$t" git commit -q --allow-empty -m "layout $(date -d "@$t" '+%Y-%m-%d %H:%M')"
  }
  cp -p "$1" widgets/; commit 150
  cp -p "$@" widgets/; commit 98
  cp -p ~/.config/omalauncher/style/style.toml style/ 2>/dev/null; commit 75
  cp -p ~/.config/omalauncher/agent . 2>/dev/null; commit 2
  git remote add origin https://github.com/demo/omalauncher-layout.git
  git update-ref refs/remotes/origin/main HEAD
)
t=$(( $(date +%s) - 7200 ))
printf '%s saved: %s\n%s uploaded to demo/omalauncher-layout\n' "$(date -d "@$t" +%H:%M:%S)" "$(git -C "$layout" log -1 --format=%s)" \
  "$(date -d "@$((t + 6))" +%H:%M:%S)" > "$layoutlog"

# radio: the station scripts/store-previews.sh seeds, playing a track. The tile checks the player
# with `kill -0 $(cat pid)`, and pid 0 is the checking shell's own process group, so it reads
# as playing without anything running.
aside "$radio/pid"
aside "$radio/now"
echo 0 > "$radio/pid"
echo "ICY-META: StreamTitle='Parcels - Tieduprightnow';" > "$radio/now"

# zyanmd's photo: a picture already set (scripts/store-previews.sh seeds the path), Avalanche
# Lake in Glacier National Park (public domain, Wikimedia Commons).
aside "$photo"
/usr/bin/curl -sf -A "omalauncher-store-previews" -o "$photo" \
  "https://upload.wikimedia.org/wikipedia/commons/thumb/3/34/Avalanche_Lake%2C_Glacier_National_Park.jpg/960px-Avalanche_Lake%2C_Glacier_National_Park.jpg"

# gb-widget: a game saved mid-level, a made-up platformer frame in the handheld's four shades,
# written as one picture per shade like its own save does.
aside "$gameboy"
/usr/bin/python3 - "$demo" <<'PY'
import math, struct, sys, zlib
W, H = 160, 144
px = [[0] * W for _ in range(H)]
def rect(x, y, w, h, c):
    for j in range(y, min(y + h, H)):
        for i in range(x, min(x + w, W)): px[j][i] = c
def sprite(x, y, rows):
    for j, r in enumerate(rows):
        for i, ch in enumerate(r):
            if ch != ".": px[y + j][x + i] = int(ch)
FONT = {"0": "75557", "1": "26227", "2": "71747", "4": "55711", "5": "74717", "7": "71111", "S": "74717", "C": "74447",
        "O": "75557", "R": "65655", "E": "74647", "T": "72222", "A": "25755", "G": "74557", "-": "00700", "x": "05250", " ": "00000"}
def text(s, x, y):
    for ch in s:
        for j, v in enumerate(FONT[ch]):
            for i in range(3):
                if int(v) >> (2 - i) & 1: px[y + j][x + i] = 3
        x += 4
# Hatched hills, clouds, a row of bricks with a ? block, coins and a pipe on blocky ground.
for x in range(W):
    for h, off, f in ((22, 0, 0.056), (14, 40, 0.081)):
        for y in range(104 - int(h * (0.5 + 0.5 * math.sin((x + off) * f))), 112):
            if (x + y) % 2 == 0 or y > 104 - h // 2: px[y][x] = 1 if (x * 3 + y) % 4 else 0
cloud = ["...2222.....", "..200002222.", ".20000000002", "200000000002", ".2222222222."]
sprite(18, 26, cloud); sprite(104, 40, cloud)
for x in range(0, W, 8):
    for y in range(112, H, 8):
        rect(x, y, 8, 8, 3); rect(x + 1, y + 1, 6, 1, 2); rect(x + 1, y + 1, 1, 6, 2)
for i, x in enumerate(range(56, 96, 8)):
    rect(x, 72, 8, 8, 3); rect(x + 1, 73, 6, 6, 2); rect(x, 75, 8, 1, 3); rect(x + (3 if i % 2 else 0), 72, 1, 4, 3)
rect(73, 73, 6, 6, 1); sprite(74, 74, ["1331", "1113", "1131", "1111", "1131"])
for x in (60, 70, 80): sprite(x, 58, [".33.", "3223", "3203", "3203", "3223", ".33."])
rect(126, 92, 22, 8, 3); rect(127, 93, 20, 6, 2); rect(129, 93, 3, 6, 0)
rect(128, 100, 18, 12, 3); rect(129, 100, 16, 12, 2); rect(131, 100, 3, 12, 0)
# The hero mid-jump, a blob walking along, and the score.
sprite(36, 84, ["...3333...", "..333333..", "..3022203.", "..3220223.", "...22222..", "..333333..", ".23333332.",
                ".22333322.", "...3..3...", "..33..33.."])
sprite(102, 106, ["..3333..", ".322223.", "32022023", "32222223", ".333333.", "33....33"])
text("SCORE 004250", 4, 3); text("x07", 72, 3); text("STAGE 1-2", 120, 3)
# One PNG per shade, white where that shade is, like the widget's own publish() writes them.
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
for level in range(4):
    raw = b"".join(b"\0" + b"".join(b"\xff\xff\xff" + (b"\xff" if v == level else b"\0") for v in row) for row in px)
    open("%s/gb-%d.png" % (sys.argv[1], level), "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
                                                          + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
PY
printf '{"layers":["%s/gb-0.png","%s/gb-1.png","%s/gb-2.png","%s/gb-3.png"],"rom":"%s/dot.gb","saved":%s}\n' \
  "$demo" "$demo" "$demo" "$demo" "${gameboy%/*}" "$(date +%s)" > "$gameboy"

# terminal-profiles: `fastfetch` greets its seeded profile with a made-up phone instead of this one.
cat > /usr/local/bin/fastfetch <<'EOF'
#!/bin/sh
row() { printf '\033[1;36m%-16s%s\033[0m%s\n' "$1" "$2" "${3:+: $3}"; }
printf '\033[1;36m%-16s\033[1mroot\033[0m@\033[1;36mlocalhost\033[0m\n' '      /\'
printf '\033[1;36m%-16s\033[0m--------------\n' '     /  \'
row '    /\   \' OS 'Arch Linux ARM aarch64'
row '   /      \' Host 'Omalauncher (Android 15)'
row '  /   ,,   \' Kernel 'Linux 6.1.99-android14'
row ' /   |  |  -\' Uptime '3 days, 4 hours'
row "/_-''    ''-_\\" Packages '418 (pacman)'
row '' Shell 'bash 5.3.3'
row '' Terminal 'omalauncher'
row '' CPU '8 cores @ 2.80 GHz'
row '' Memory '3.14 GiB / 7.47 GiB (42%)'
row '' 'Disk (/)' '9.84 GiB / 52.3 GiB (19%)'
echo
printf '%16s' ''; printf '\033[4%sm   ' 0 1 2 3 4 5 6 7; printf '\033[0m\n'
printf '%16s' ''; printf '\033[10%sm   ' 0 1 2 3 4 5 6 7; printf '\033[0m\n\n'
EOF
chmod +x /usr/local/bin/fastfetch

# cleaner: a uv cache and thumbnails worth clearing, as sparse files (du -b counts their
# apparent size, so they take no room), beside the guest's own pip and npm caches.
for d in ~/.cache/uv ~/.cache/thumbnails; do [ -d "$d" ] || echo "$d" >> "$demo/cleaner-dirs"; done
mkdir -p ~/.cache/uv ~/.cache/thumbnails/large
truncate -s 1288M ~/.cache/uv/store-previews-demo
truncate -s 143M ~/.cache/thumbnails/large/store-previews-demo.png

# neon-ytdlp: yt-dlp and FFmpeg set up (stand-ins that answer its checks) and a made-up video
# analysed, with its formats listed. The widget's own status call keeps these fields.
aside "$neondl/state.json"
aside "$neondl/version.txt"
printf '#!/bin/sh\necho 2026.09.21\n' > /usr/local/bin/yt-dlp
printf '#!/bin/sh\nexit 0\n' > /usr/local/bin/ffmpeg
cp /usr/local/bin/ffmpeg /usr/local/bin/ffprobe
chmod +x /usr/local/bin/yt-dlp /usr/local/bin/ffmpeg /usr/local/bin/ffprobe
f() { printf '{"id":"%s","spec":"%s","mode":"%s","cat":"%s","ext":"%s","res":"%s","kind_en":"%s","kind_es":"%s","size":"%s","has_v":%s,"has_a":%s}' "$@"; }
printf '{"installed":true,"has_ytdlp":true,"has_ffmpeg":true,"ytdlp_ver":"2026.09.21","phase":"idle","progress":100,"step_msg":"FORMATS_READY","error":"","dl_dir":"%s","active_dl":"","url":"https://www.youtube.com/watch?v=Xk9m2LpQe7s","title":"Lofi Coding Session - 2 Hours of Chill Beats","uploader":"Quiet Hours","duration":"2:00:14","site":"YouTube","formats":[%s],"last_file":"","recent":[],"updated_at":1}\n' "$HOME/Downloads" "$(
  f va-137 137+bestaudio/137 video va MP4 1080p "VIDEO + AUDIO" "VIDEO + AUDIO" "412.6 MB" true true; echo ,
  f va-136 136+bestaudio/136 video va MP4 720p "VIDEO + AUDIO" "VIDEO + AUDIO" "214.3 MB" true true; echo ,
  f va-135 135+bestaudio/135 video va MP4 480p "VIDEO + AUDIO" "VIDEO + AUDIO" "118.9 MB" true true; echo ,
  f va-18 18 video va MP4 360p "VIDEO + AUDIO" "VIDEO + AUDIO" "76.2 MB" true true; echo ,
  f vo-137 137 video_only vo MP4 1080p "VIDEO (NO AUDIO)" "VIDEO SIN AUDIO" "297.1 MB" true false; echo ,
  f vo-136 136 video_only vo MP4 720p "VIDEO (NO AUDIO)" "VIDEO SIN AUDIO" "98.8 MB" true false; echo ,
  f ao-mp3 ba/b mp3 ao MP3 "MP3 HQ" "SOLO AUDIO (MP3)" "SOLO AUDIO (MP3)" "115.5 MB" false true; echo ,
  f ao-m4a "ba[ext=m4a]/ba/b" m4a ao M4A "M4A AAC" "SOLO AUDIO (M4A)" "SOLO AUDIO (M4A)" "115.5 MB" false true; echo ,
  f ao-251 251 audio_raw ao WEBM 160k "SOLO AUDIO" "SOLO AUDIO" "122.4 MB" false true)" | tr -d '\n' > "$neondl/state.json"
echo >> "$neondl/state.json"

# private-space: `adb` answers like a phone paired over wireless debugging, its Private Space
# unlocked with five apps.
aside "$privspace"
cat > /usr/local/bin/adb <<'EOF'
#!/bin/sh
case "$*" in
  devices*) printf 'List of devices attached\nadb-DEMO0001-Pv7xQe._adb-tls-connect._tcp\tdevice\n\n' ;;
  *"cmd user list"*) printf 'Users:\n\t0: id=0, name=Owner, type=full.SYSTEM, flags=ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM (running) (current)\n\t1: id=11, name=Private space, type=profile.PRIVATE, flags=HIDE_IN_SETTINGS|INITIALIZED|PROFILE (running)\n' ;;
  *"pm list packages -3"*) printf 'package:com.whatsapp\npackage:com.instagram.android\n' ;;
  *query-activities*) printf '%s\n' com.whatsapp/.Main com.instagram.android/.activity.MainTabActivity \
    com.android.chrome/com.google.android.apps.chrome.Main com.google.android.apps.photos/.home.HomeActivity \
    com.google.android.apps.nbu.files/.home.HomeActivity ;;
esac
exit 0
EOF
chmod +x /usr/local/bin/adb

# network: the emulator answers every ping itself in 0 ms, which network reads as no reply, so
# `ping` prints a reply from 1.1.1.1 the way a phone on Wi-Fi would see one.
cat > /usr/local/bin/ping <<'EOF'
#!/bin/sh
printf '64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=%s ms\n' "$(awk 'BEGIN { srand(); printf "%.1f", 16 + rand() * 9 }')"
EOF
chmod +x /usr/local/bin/ping

# wifi-radar: `perl` answers its scan with a made-up home network, so the preview never shows
# the host's real Wi-Fi or LAN names.
printf '%s\n' '{"ok":true,"mode":"lan","selfIp":"192.168.1.23","selfName":"Phone","gwIp":"192.168.1.1","gwName":"router","nodes":[{"id":1,"ip":"192.168.1.1","name":"router","kind":"router","online":true,"rtt":2.4,"dist":0.267,"angle":35,"x":0.153,"y":0.219},{"id":10,"ip":"192.168.1.10","name":"nas","kind":"pc","online":true,"rtt":3.1,"dist":0.287,"angle":86,"x":0.286,"y":0.020},{"id":77,"ip":"192.168.1.77","name":"hue-bridge","kind":"iot","online":true,"rtt":4.6,"dist":0.329,"angle":343,"x":-0.096,"y":0.314},{"id":31,"ip":"192.168.1.31","name":"Pixel-8a","kind":"phone","online":true,"rtt":6.8,"dist":0.403,"angle":137,"x":0.275,"y":-0.294},{"id":42,"ip":"192.168.1.42","name":"living-room-tv","kind":"tv","online":true,"rtt":12.5,"dist":0.474,"angle":189,"x":-0.074,"y":-0.468},{"id":57,"ip":"192.168.1.57","name":"thinkpad","kind":"pc","online":true,"rtt":18.9,"dist":0.554,"angle":240,"x":-0.480,"y":-0.277},{"id":64,"ip":"192.168.1.64","name":"chromecast","kind":"tv","online":true,"rtt":38.2,"dist":0.679,"angle":292,"x":-0.630,"y":0.254},{"id":88,"ip":"192.168.1.88","name":"printer","kind":"iot","online":false,"rtt":null,"dist":0.928,"angle":15,"x":0.240,"y":0.896}]}' > "$demo/wifi-radar.json"
cat > /usr/local/bin/perl <<'EOF'
#!/bin/sh
case "$*" in
  *"Active Wi-Fi radar"*) cat /tmp/store-previews-demo/wifi-radar.json ;;
  *) exec /usr/bin/perl "$@" ;;
esac
EOF
chmod +x /usr/local/bin/perl

# server-pulse: `ssh` answers its collector for the made-up host store-previews.sh seeds: a quiet home
# server on mains power whose byte counters climb at a steady rate (about 18 Mb/s in, 3 Mb/s out).
cat > "$demo/server-pulse.sh" <<'EOF'
now=$(date +%s)
t=$((now - 1700000000))
printf '{"schema":1,"checked_at":%s,"uptime_seconds":1998000,"load_1m":0.42,"cpu_percent":23.5,' "$now"
printf '"memory":{"total_bytes":17179869184,"available_bytes":10136238080,"used_percent":41},'
printf '"root_disk":{"total_bytes":512110190592,"free_bytes":215086280048,"used_percent":58},'
printf '"network":{"interface":"enp3s0","rx_bytes":%s,"tx_bytes":%s,"link_mbps":1000},' $((t * 2300000)) $((t * 410000))
printf '"power":{"external_power":true,"battery_percent":100,"battery_state":"full","battery_power_w":0},"temperature_c":46,'
printf '"services":[{"name":"nginx","status":"active","expected":"active"},{"name":"jellyfin","status":"active","expected":"active"},{"name":"docker","status":"active","expected":"active"}]}\n'
EOF

# speedtest: a finished Fast.com run on a fast Berlin line, after a few earlier ones. The address is
# a documentation one (TEST-NET-3), never this machine's.
aside "$speedtest"
printf '{"running":false,"phase":"DONE","step":"Test complete · Tap to re-run","progress":100,"down":248.6,"up":41.3,"ping":11,"jitter":2,"ip":"203.0.113.24","isp":"Berlin, DE","server":"Berlin","engine":"fast","ts":%s,"history":[212.4,238.9,195.2,241.7,226.3,248.6]}\n' "$(date +%s)" > "$speedtest"

# share-bridge: a link that's up. No pid, so the widget doesn't look for a server process, which
# wouldn't outlive this script's proot anyway.
aside "$bridge"
echo '{"url":"http://127.0.0.1:8765/store-preview-demo/"}' > "$bridge"

# nostr-inbox: a made-up account's last day on nostr, dated from when it's asked. `websocat` answers the
# relay queries that name it or the people in it; its profile, relays and mute list are the ones the
# widget keeps (without a mute list it shows nothing at all).
[ -d ~/.cache/nostr-inbox ] || touch "$demo/nostr-cache-new"
mkdir -p ~/.cache/nostr-inbox
cat > "$demo/nostr.py" <<'PY'
import hashlib, json, sys, time
what, me = sys.argv[1:]
now = int(time.time())
def h(s): return hashlib.sha256(s.encode()).hexdigest()
note = h("demo note")
people = {n: h("demo person " + n) for n in ["Mara", "Jonas", "Ines", "Theo", "Lukas", "Yuki"]}
def event(kind, who, ago, content="", tags=()):
    return {"id": h("demo event %d %s %d" % (kind, who, ago)), "pubkey": who, "created_at": now - ago, "kind": kind,
            "tags": [list(t) for t in tags], "content": content, "sig": "0" * 128}
def to(kind, name, ago, content=""): return event(kind, people[name], ago, content, [["e", note], ["p", me]])
def zap(name, ago, sats, text):
    req = {"kind": 9734, "pubkey": people[name], "created_at": now - ago, "content": text, "tags": [["p", me], ["amount", str(sats * 1000)]]}
    return event(9735, h("demo zapper"), ago, "", [["p", me], ["e", note], ["bolt11", "lnbc%dn1pdemo" % (sats * 10)], ["description", json.dumps(req)]])
if what == "keys":
    print("\n".join([me] + list(people.values())))
elif what == "profile":
    print(json.dumps({"name": "Lena", "relays": ["wss://relay.damus.io", "wss://nos.lol"], "outbox": ["wss://relay.damus.io"]}))
elif what == "mutes":
    print(json.dumps(event(10000, me, 86400 * 60)))
else:
    events = [zap("Mara", 720, 2100, "great write-up on relays"), to(1, "Jonas", 2400, "Same here, nos.lol has been quick all week"),
              to(7, "Ines", 3900, "+"), zap("Theo", 9000, 500, ""), to(6, "Lukas", 14000), to(7, "Yuki", 21000, "🔥"),
              event(1, people["Ines"], 30000, "Running my own relay at home now, thanks to your guide", [["p", me]]),
              to(7, "Mara", 40000, "+"), zap("Jonas", 190000, 21, "🤙"), to(1, "Theo", 260000, "Which client do you use on Android?")] \
        if what == "inbox" else [event(0, pk, 86400 * 30, json.dumps({"name": n.lower(), "display_name": n})) for n, pk in people.items()]
    for e in events: print(json.dumps(["EVENT", "w", e], ensure_ascii=False))
PY
/usr/bin/python3 "$demo/nostr.py" keys "${nostr##*/}" > "$demo/nostr-keys"
/usr/bin/python3 "$demo/nostr.py" profile "${nostr##*/}" > "$nostr.json"
/usr/bin/python3 "$demo/nostr.py" mutes "${nostr##*/}" > "$nostr.mutelist.json"
cat > /usr/local/bin/websocat <<'EOF'
#!/bin/sh
d=/tmp/store-previews-demo
IFS= read -r req
if printf '%s\n' "$req" | grep -qFf $d/nostr-keys; then
  case $req in
    *9735*) /usr/bin/python3 $d/nostr.py inbox "$(head -1 $d/nostr-keys)" ;;
    *'"kinds":[0]'*) /usr/bin/python3 $d/nostr.py names "$(head -1 $d/nostr-keys)" ;;
  esac
  echo '["EOSE","w"]'
  exit 0
fi
{ printf '%s\n' "$req"; cat; } | /usr/bin/websocat "$@"
EOF
chmod +x /usr/local/bin/websocat

# clear-sky: the two charts store-previews.sh seeds, already downloaded. Its command first runs
# before the saved sites are back, so it only lists what's in the cache.
[ -d ~/.cache/cleardarksky ] || touch "$demo/cleardarksky-cache-new"
mkdir -p ~/.cache/cleardarksky
for k in KittAZ BrycCNPUT; do
  ls ~/.cache/cleardarksky/"$k"-*.gif >/dev/null 2>&1 ||
    /usr/bin/curl -fsm 20 -o ~/.cache/cleardarksky/"$k-$(date +%s).gif" "https://www.cleardarksky.com/c/${k}csk.gif"
  [ -s ~/.cache/cleardarksky/"$k".name ] || /usr/bin/curl -fsm 10 "https://www.cleardarksky.com/c/${k}key.html" |
    grep -o '<title>[^<]*' | head -1 | sed 's/<title>//; s/ Clear Sky Chart$//' > ~/.cache/cleardarksky/"$k".name
done
