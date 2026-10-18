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
# voice widgets find themselves set up with a reply read and a sentence dictated. With "off", puts everything back.
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

# Moves a real file aside before the demo writes its own; "off" puts it back.
aside() { [ -f "$1" ] && [ ! -f "$1.before-demo" ] && mv "$1" "$1.before-demo"; mkdir -p "${1%/*}"; }

if [ "${1:-}" = off ]; then
  for p in $(cat "$demo/claude.pids" 2>/dev/null); do kill "$p" 2>/dev/null; rm -f ~/.claude/sessions/"$p".json; done
  pid=$(cat ~/.cache/owl-flipper/feed.pid 2>/dev/null) && kill -- "-$pid" 2>/dev/null
  [ -f "$demo/agenda-cache-new" ] && rm -rf ~/.cache/gcal-agenda
  rm -f ~/.cache/owl-flipper/frame.json ~/.cache/photoprism-random/demo.jpg ~/.cache/owlmaker/pending/made-demo-moon.owl
  rm -rf "$demo" /usr/local/bin/gh /usr/local/bin/curl /usr/local/bin/python3 /usr/local/bin/pgrep \
    /usr/local/bin/python /usr/local/bin/claude /usr/local/bin/ssh /usr/local/bin/agy
  for f in "$ha" "$cursor" "$tesla" "$opencode" "$history" "$photoprism" "$owlmaker" "$owlstatus" "$flipper" \
    "$agy" "$agenda" "$voice" "$voicestate" "$dictated" "$codex" "$codexcache" "$apkver" "$apkbin" \
    "$apkcache/setup.json" "$apkcache/apps.json" "$apkcache/job.json" "$apkcache/log.txt"; do
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
cat > /usr/local/bin/python3 <<'EOF'
#!/bin/sh
case "$*" in
  *owl-gcal*) exec /usr/bin/python3 /tmp/store-previews-demo/gcal.py ;;
  *lastfm.py\ fetch*) exec /usr/bin/python3 /tmp/store-previews-demo/lastfm.py ;;
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
