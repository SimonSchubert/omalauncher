#!/usr/bin/env bash

# omalauncher-sync: keeps Omalauncher devices the same: widgets (their files and
# `persist` values), the theme and the background, both ways.
#
# Every device with Omalauncher answers requests over SSH: an Android phone runs
# sshd in its Linux (ComputerLink.kt in omarchy-proot) and lets a connected
# device's key in only for omalauncher-link, which hands each request to the
# launcher. This script is the other half: it connects to such devices and
# follows them. It runs on an Omarchy computer (the Omalauncher plugin, as
# `omarchy-widgets devices`) and in a phone's Linux (the launcher runs it, to
# follow another phone): the engine is the same, and only "this device" differs,
# in the adapter below.
#
# Two copies, kept the same: bin/omalauncher-sync in omalauncher-omarchy-plugin
# and app/src/main/assets/omalauncher-sync.sh in omarchy-proot.
#
#   omalauncher-sync connect            a QR code for a device to scan, then wait for it
#   omalauncher-sync                    the connected devices, if they answer
#   omalauncher-sync info               them as JSON
#   omalauncher-sync invite --png=FILE  the code alone: {link, code}; then wait
#   omalauncher-sync wait               waits for the device that scanned it
#   omalauncher-sync follow             keeps them in sync, until killed
#   omalauncher-sync sync [theme|background on|off | widget <name> on|off]
#   omalauncher-sync widgets|themes     a device's
#   omalauncher-sync push <file>...     widgets to every device
#   omalauncher-sync pull <name>...     a device's widgets here
#   omalauncher-sync remove <name>      a widget from a device
#   omalauncher-sync theme [name]       every device to this theme, or that one
#   omalauncher-sync disconnect [name]  forget a device
#   omalauncher-sync call <request>...  one request to a device, its answer as JSON: hello,
#                                       widgets, get <name>, put <name> (the file on stdin),
#                                       remove <name>, themes, theme <name> [repo], sync […],
#                                       synced, state get|put <name> ({savedAt, values} on
#                                       stdin), background <theme> <file>, bgget <file>,
#                                       bgput <theme> <file> (the image on stdin), watch [key]
#
# --device=<name or id> picks one; push, theme and sync widget go to every device
# without it, the rest ask which when there are several. --yes skips questions,
# --png=FILE writes the code there.

set -euo pipefail
# sleep without starting a program, where bash has it: on a phone every program
# started is traced by proot, and the loops below sleep all day.
enable -f /usr/lib/bash/sleep sleep 2>/dev/null || true

me=${OMALAUNCHER_SYNC_ME:-omalauncher-sync}
SHARE_LINK=https://simonschubert.github.io/omarchy-widgets/w#
PHONE_SERVICE=_omalauncher._tcp
PHONE_USER=omalink

# What this device is: an Omarchy computer, or a phone's Linux under Omalauncher.
if [[ -n ${OMALAUNCHER_SYNC_HOST-} ]]; then
  KIND=$OMALAUNCHER_SYNC_HOST
elif command -v omalauncher >/dev/null 2>&1 && ! command -v omarchy-theme-set >/dev/null 2>&1; then
  KIND=omalauncher
else
  KIND=omarchy
fi

if [[ $KIND == omalauncher ]]; then
  DIR=$HOME/.config/omalauncher/widgets
  STATE=${XDG_STATE_HOME:-$HOME/.local/state}/omalauncher
  PHONE=$STATE/sync
else
  DIR=${OWL_WIDGETS:-${XDG_CONFIG_HOME:-$HOME/.config}/omarchy-widgets}
  STATE=${XDG_STATE_HOME:-$HOME/.local/state}/omarchy-widgets
  PHONE=$STATE/phone
fi
PHONE_KEY=$PHONE/id_ed25519
PHONE_HOSTS=$PHONE/known_hosts
PHONES=$PHONE/phones
PHONE_PENDING=$PHONE/pending
# How long a code can be scanned and confirmed, from when it was made: its secret
# is deleted then, so a picture of the code or its link is worth nothing later.
# ComputerLink.INVITE_SECONDS on the device that scans it.
INVITE_SECONDS=120
BG_LINK=$HOME/.local/state/omarchy/current/background
THEME_NAME_FILE=$HOME/.local/state/omarchy/current/theme.name
# What this device just took from another, and from which: passed on to the
# others, never back, where it would undo a change made there since.
# "<theme or background path> <device id>".
FROM_PHONE_THEME=$PHONE/from-phone-theme
FROM_PHONE_BG=$PHONE/from-phone-bg

die() { echo "$me: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "$1 is not installed${2:+ ($2)}"; }
tilde() { [[ $1 == "$HOME"* ]] && echo "~${1#"$HOME"}" || echo "$1"; }
slug() { tr 'A-Z' 'a-z' | sed 's/[^a-z0-9]\{1,\}/-/g; s/^-//; s/-$//'; }
# slug of $1 into $REPLY, in bash alone: local_watch asks every few seconds.
slug_of() {
  local s=${1,,}
  s=${s//[^a-z0-9]/-}
  while [[ $s == *--* ]]; do s=${s//--/-}; done
  s=${s#-}
  REPLY=${s%-}
}
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi | cut -d' ' -f1; }
file_mtime() { stat -c %Y -- "$1" 2>/dev/null || echo 0; }
file_sha() { [[ -f $1 ]] && sha256 <"$1" || true; }
confirm() {
  ((yes)) && return 0
  [[ -t 0 ]] || return 1
  local answer
  read -r -p "$1 [y/N] " answer
  [[ $answer == [yY]* ]]
}

# ---- this device --------------------------------------------------------------
# What the engine asks of the device it runs on. On a phone, what changes the
# launcher's own state goes through it (`omalauncher link <verb> self`), as a
# connected device's requests do; files Linux sees are read directly. A change
# taken from a device it follows names that device (self:<id>), for the
# launcher's activity list.

local_name() {
  if [[ $KIND == omalauncher ]]; then echo "${OMALAUNCHER_SYNC_NAME:-phone}"; else hostname; fi
}

# The current theme, by the name Omalauncher and Omarchy both use (tokyo-night).
local_theme() {
  local t=
  [[ -s $THEME_NAME_FILE ]] && t=$(<"$THEME_NAME_FILE")
  if [[ -z $t && $KIND == omarchy ]] && command -v omarchy-theme-current >/dev/null 2>&1; then
    t=$(omarchy-theme-current 2>/dev/null) || t=
  fi
  [[ -n $t ]] || return 1
  slug_of "$t"
  printf %s "$REPLY"
}

local_theme_has() {
  if [[ $KIND == omalauncher ]]; then
    omalauncher link themes self 2>/dev/null | jq -e --arg t "$1" 'any(.[]; .name == $t)' >/dev/null
  else
    # Not grep -q: it leaves before the list ends, and under pipefail the cut-off list fails the whole check.
    command -v omarchy-theme-list >/dev/null 2>&1 && omarchy-theme-list 2>/dev/null | slug | grep -xF -- "$1" >/dev/null
  fi
}

local_theme_set() {
  if [[ $KIND == omalauncher ]]; then
    omalauncher link theme "self${PH:+:$PH}" "$1" >/dev/null 2>&1 || true
  else
    omarchy-theme-set "$1" >/dev/null 2>&1 || true
  fi
}

local_notify() {
  if [[ $KIND == omalauncher ]]; then
    omalauncher notify -- "$1" "$2" >/dev/null 2>&1 || true
  elif command -v notify-send >/dev/null 2>&1; then
    notify-send -a Omalauncher "$1" "$2" >/dev/null 2>&1 || true
  fi
}

# Where theme $1 came from, when someone made it: its GitHub repo, which goes
# along with it, so a device that hasn't got it can install it. "" otherwise.
local_theme_source() {
  local url=
  if [[ $KIND == omalauncher ]]; then
    [[ $(sed -n 1p /var/lib/omalauncher-link/theme 2>/dev/null) == "$1" ]] && url=$(sed -n 2p /var/lib/omalauncher-link/theme)
  elif [[ -d ${XDG_CONFIG_HOME:-$HOME/.config}/omarchy/themes/$1/.git ]]; then
    url=$(git -C "${XDG_CONFIG_HOME:-$HOME/.config}/omarchy/themes/$1" remote get-url origin 2>/dev/null) || url=
    url=${url%.git}
  fi
  [[ $url =~ ^https://github\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/?$ ]] && echo "$url" || true
}

# Whether the GitHub repo $1 is one of the themes on omarchy.org (its page,
# looked at once a day).
in_theme_catalog() {
  local page=$PHONE/theme-catalog.html repo
  if [[ ! -s $page ]] || (($(date +%s) - $(file_mtime "$page") > 86400)); then
    curl -fsSL --max-time 20 --max-filesize 8388608 -o "$page.tmp" https://omarchy.org/themes/ 2>/dev/null && mv "$page.tmp" "$page" || rm -f "$page.tmp"
  fi
  repo=$(sed -E 's|^https://github.com/||; s|/$||; s|\.git$||' <<<"$1" | tr 'A-Z' 'a-z')
  grep -oiE 'href="https://github\.com/[^"]+"' "$page" 2>/dev/null | sed -E 's|^href="https://github.com/||I; s|"$||; s|/$||; s|\.git$||' | tr 'A-Z' 'a-z' | grep -xF -- "$repo" >/dev/null
}

# A theme this device doesn't have, from the GitHub repo $2. A phone installs it
# at once: the launcher keeps a theme's files to what's safe. Omarchy installs
# a whole repo, its config files (Neovim's among them) too, so by itself only a
# theme on omarchy.org's list; any other when the user says so.
local_theme_offer() {
  local t=$1 src=$2 choice
  if [[ $KIND == omalauncher ]]; then
    printf '%s %s\n' "$t" "$PH" >"$FROM_PHONE_THEME"
    omalauncher link theme "self${PH:+:$PH}" "$t" "$src" >/dev/null 2>&1 || true
    from_phone_bg
    return
  fi
  command -v omarchy-theme-install >/dev/null 2>&1 || return 0
  (
    if in_theme_catalog "$src"; then
      choice=install
    else
      command -v notify-send >/dev/null 2>&1 || exit 0
      # Waits for the click in the background, so the device is followed meanwhile.
      choice=$(notify-send -a Omalauncher -A install=Install -A no="Not now" \
        "$(phone_info name) switched to $t" "This device doesn't have it, and it isn't on omarchy.org's list of themes. Install it from ${src#https://}?" 2>/dev/null) || exit 0
    fi
    [[ $choice == install ]] || exit 0
    if omarchy-theme-install "$src" >/dev/null 2>&1 && local_theme_has "$t"; then
      printf '%s %s\n' "$t" "$PH" >"$FROM_PHONE_THEME"
      local_theme_set "$t"
      from_phone_bg
    else
      local_notify "Couldn't install $t" "omarchy-theme-install $src failed."
    fi
  ) </dev/null >/dev/null 2>&1 9>&- &
}

# Where the backgrounds of theme $1 are, the user's added ones first; and where one sent here goes.
local_bg_dirs() {
  local t=$1
  printf '%s\n' "${XDG_CONFIG_HOME:-$HOME/.config}/omarchy/backgrounds/$t" \
    "$HOME/.local/state/omarchy/current/theme/backgrounds" \
    "${XDG_CONFIG_HOME:-$HOME/.config}/omarchy/themes/$t/backgrounds" \
    "$HOME/.local/share/omarchy/themes/$t/backgrounds" \
    "${OMARCHY_PATH:-/usr/share/omarchy}/themes/$t/backgrounds"
}
local_bg_store() { echo "${XDG_CONFIG_HOME:-$HOME/.config}/omarchy/backgrounds/$1"; }

# Shows background file $2 of theme $1.
local_bg_set() {
  if [[ $KIND == omalauncher ]]; then
    omalauncher link background "self${PH:+:$PH}" "$1" "$(basename -- "$2")" >/dev/null 2>&1 || true
  else
    omarchy-theme-bg-set "$2" >/dev/null 2>&1 || true
  fi
}

# A widget's persist values here: canonical JSON, and when they were saved.
local_state_get() {
  if [[ $KIND == omalauncher ]]; then
    omalauncher link state self get "$1" 2>/dev/null | jq -cS '.values // {}' || echo '{}'
  else
    jq -cS . "$STATE/$1.json" 2>/dev/null || echo '{}'
  fi
}
local_state_saved() {
  if [[ $KIND == omalauncher ]]; then
    omalauncher link state self get "$1" 2>/dev/null | jq -r '.savedAt // 0 | floor' || echo 0
  else
    file_mtime "$STATE/$1.json"
  fi
}
# Writes values $2 (JSON), saved at $3, for widget $1; a running widget takes them in.
local_state_set() {
  local tmp
  if [[ $KIND == omalauncher ]]; then
    tmp=$(mktemp /tmp/omalauncher-sync.XXXXXX)
    jq -c --argjson at "$3" '{savedAt: $at, values: .}' <<<"$2" >"$tmp"
    omalauncher link state "self${PH:+:$PH}" put "$1" "$tmp" >/dev/null 2>&1 || true
    rm -f "$tmp"
  else
    mkdir -p "$STATE"
    printf '%s\n' "$2" >"$STATE/$1.json.tmp" && mv "$STATE/$1.json.tmp" "$STATE/$1.json"
    # Saved when it was there, so the next sync sees both the same and doesn't ask for them again.
    touch -m -d "@$3" "$STATE/$1.json" 2>/dev/null || true
  fi
}

# What the synced widgets look like here, to notice a change. On a phone the
# launcher keeps a digest of them, its values included, which Linux can't see.
local_widgets_digest() {
  local n
  if [[ $KIND == omalauncher ]]; then
    local _
    { read -r _ && read -r _ && read -r _ && read -r n; } 2>/dev/null </var/lib/omalauncher-link/theme || true
    printf '%s\n' "${n-}"
    return
  fi
  cat "$PHONE"/synced-* 2>/dev/null | sort -u | while read -r n; do
    printf '%s %s %s\n' "$n" "$(file_mtime "$DIR/$n")" "$(file_mtime "$STATE/$n.json")"
  done | sha256
}

# "host port id" for each device announced on the network, IPv4 first. A phone
# can't run avahi; the launcher writes what Android finds instead.
local_discover() {
  if [[ $KIND == omalauncher ]]; then
    cat /run/user/0/omalauncher-devices 2>/dev/null || true
    return
  fi
  # =;iface;proto;name;type;domain;host;address;port;"id=…" "v=1"
  avahi-browse -rpt "$PHONE_SERVICE" 2>/dev/null | awk -F';' '
    $1 == "=" {
      id = "-"; if (match($10, /"id=[0-9a-f]+"/)) id = substr($10, RSTART + 4, RLENGTH - 5)
      print ($3 == "IPv4" ? 0 : 1), $8, $9, id
    }' | sort -u | sort -n | cut -d' ' -f2-
}
local_can_discover() {
  [[ $KIND == omalauncher || -n ${OMALAUNCHER_PHONE-} ]] || command -v avahi-browse >/dev/null 2>&1 ||
    die "avahi-browse is not installed (avahi), so devices can't be found; or set OMALAUNCHER_PHONE=<address>:8122"
}

# ---- connected devices ------------------------------------------------------------
# Each is $PHONES/<id>.json; one key and one known_hosts (by alias
# omalauncher-<id>) serve them all.
PH=

phone_migrate() {
  # Before more than one: the one was $PHONE/phone.json.
  if [[ -s $PHONE/phone.json ]]; then
    local id
    id=$(jq -r '.id // empty' "$PHONE/phone.json" 2>/dev/null)
    if [[ $id =~ ^[0-9a-f]+$ ]]; then
      mkdir -p "$PHONES"
      mv "$PHONE/phone.json" "$PHONES/$id.json"
    fi
  fi
  # The Omarchy theme hook sent theme changes before this looked for them itself.
  rm -f "${XDG_CONFIG_HOME:-$HOME/.config}/omarchy/hooks/theme-set.d/50-omalauncher-phone"
}

phone_ids() { local f; for f in "$PHONES"/*.json; do [[ -s $f ]] && basename "$f" .json; done; return 0; }
phone_count() { phone_ids | wc -l; }
phone_use() { PH=$1; }
phone_info() { [[ -n $PH && -s $PHONES/$PH.json ]] && jq -r ".$1 // empty" "$PHONES/$PH.json"; }

# The device --device=<name or id> names, or the only one; asks which when there are several.
phone_pick() {
  local id want=${device_arg-} names=
  [[ -n $(phone_ids) ]] || die "no device connected; connect one with: $me connect"
  if [[ -z $want ]]; then
    (($(phone_count) == 1)) && { PH=$(phone_ids); return; }
    for id in $(phone_ids); do names+=" $(jq -r .name "$PHONES/$id.json")"; done
    die "several devices are connected; say which with --device=<name>:$names"
  fi
  for id in $(phone_ids); do
    if [[ $id == "$want" || ${id:0:6} == "$want" ]] || [[ $(jq -r .name "$PHONES/$id.json" | tr 'A-Z' 'a-z') == "$(tr 'A-Z' 'a-z' <<<"$want")" ]]; then
      PH=$id
      return
    fi
  done
  die "no connected device called $want"
}

# The devices a command for all of them goes to: the one --device names (picked already), or each.
phone_targets() { if [[ -n ${device_arg-} ]]; then echo "$PH"; else phone_ids; fi; }

# The six digits a device shows beside "only connect it if … shows this code".
phone_code() {
  local h n
  h=$(printf %s "$1" | sha256 | cut -c1-8)
  n=$((16#$h % 1000000))
  printf '%03d %03d' $((n / 1000)) $((n % 1000))
}

phone_candidates() {
  if [[ -n ${OMALAUNCHER_PHONE-} ]]; then
    echo "${OMALAUNCHER_PHONE%:*} ${OMALAUNCHER_PHONE##*:} -"
    return
  fi
  local_discover
}

# ssh to the device at $1:$2 under the host key alias $3, running $4…
# Only this key, only a host key already in $PHONE_HOSTS, and nothing of the
# user's own ssh config that hands the other side more than the request: no
# agent, X11 or port forwarding, whatever `Host *` says there.
# Requests to a device share one connection (by its alias, so never another
# device's that got its address), kept two minutes after the last: a login
# costs a phone far more than a request, its sshd being traced by proot, and
# wakes it. Not while connecting: that one checks who answers, nor where the
# socket's path would be too long for one.
phone_ssh() {
  local host=$1 port=$2 alias=$3 mux=auto dir=$PHONE path
  shift 3
  if [[ -n ${XDG_RUNTIME_DIR-} && -d $XDG_RUNTIME_DIR && -w $XDG_RUNTIME_DIR ]]; then
    dir=$XDG_RUNTIME_DIR/omalauncher-sync
    [[ -d $dir ]] || mkdir -m 700 "$dir" 2>/dev/null || dir=$PHONE
  fi
  path=$dir/cm-%k
  if [[ $alias == pending ]] || ((${#dir} + 4 + ${#alias} >= 100)); then mux=no path=none; fi
  ssh -q -i "$PHONE_KEY" -p "$port" -l "$PHONE_USER" \
    -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=5 -o ServerAliveInterval=60 -o ServerAliveCountMax=2 \
    -o UserKnownHostsFile="$PHONE_HOSTS" -o StrictHostKeyChecking=yes -o HostKeyAlias="$alias" \
    -o PreferredAuthentications=publickey -o HostKeyAlgorithms=ssh-ed25519 \
    -o ForwardAgent=no -o ForwardX11=no -o ClearAllForwardings=yes -o PermitLocalCommand=no \
    -o ControlMaster=$mux -o ControlPath="$path" -o ControlPersist=120 -o RequestTTY=no \
    -- "$host" "$@"
}

# The device PH, wherever it is now: sets phone_host and phone_port.
phone_find() {
  [[ -n $(phone_info id) ]] || die "no device connected; connect one with: $me connect"
  local_can_discover
  local id host port cid last
  id=$(phone_info id)
  last=$(phone_info host)
  phone_host= phone_port=
  # Of several announced under its id, the address it was last reached at: anyone on the network can
  # announce an id, and though the pinned host key lets nobody else answer, the first taken blindly
  # would keep the real one from being reached.
  while read -r host port cid; do
    [[ -n $host ]] || continue
    if [[ $cid == "$id" || $cid == - ]]; then
      [[ -n $phone_host ]] || phone_host=$host phone_port=$port
      [[ $host == "$last" ]] && { phone_host=$host phone_port=$port; break; }
    fi
  done < <(phone_candidates)
  [[ -z $phone_host ]] || return 0
  # Not announced (a network without multicast, Tailscale): where it was last. Not probed first: a
  # connection that doesn't log in counts against this device on the other's sshd.
  if [[ -n $(phone_info host) && -n $(phone_info port) ]]; then
    phone_host=$(phone_info host) phone_port=$(phone_info port)
    return
  fi
  die "$(phone_info name) isn't on the network: is Omalauncher running, with Linux, on the same network?"
}

# Exits as the device's command did (1 nothing there, 2 refused, 3 not connected
# anymore), or with a word on why it wasn't reached.
phone_run() {
  phone_find
  local code=0
  phone_ssh "$phone_host" "$phone_port" "omalauncher-$(phone_info id)" "$@" || code=$?
  ((code != 255)) || die "can't reach $(phone_info name) at $phone_host:$phone_port, or it no longer lets this one in (disconnected there? $me connect)"
  return $code
}

# ---- connecting --------------------------------------------------------------------

# A new invite: this device's key (made the first time) and a fresh secret, kept
# in $PHONE/pending until a device proves it has it. Sets invite_link and
# invite_code; with --png, the QR code goes there too.
phone_invite() {
  need ssh-keygen
  need openssl
  mkdir -p "$PHONE"
  chmod 700 "$PHONE"
  [[ -s $PHONE_KEY ]] || ssh-keygen -q -t ed25519 -N '' -C "$me@$(local_name | tr -c 'A-Za-z0-9.-' '-')" -f "$PHONE_KEY" || die "couldn't make a key"
  local key secret data
  key=$(cut -d' ' -f1,2 "$PHONE_KEY.pub")
  secret=$(openssl rand -hex 16)
  (umask 077 && printf '%s\n' "$secret" >"$PHONE_PENDING")
  data=$(jq -cn --arg n "$(local_name)" --arg k "$key" --arg s "$secret" '{n: $n, k: $k, s: $s}' | base64 | tr -d '\n=' | tr '+/' '-_')
  invite_link="${SHARE_LINK}link1.$data"
  invite_code=$(phone_code "$key")
  if [[ -n $png ]]; then
    need qrencode
    qrencode -t PNG -l L -s 8 -m 2 -o "$png" "$invite_link" || die "qrencode failed"
  fi
}

# Waits for the device that scanned the pending invite, until the code is
# $INVITE_SECONDS old, and keeps it. The secret goes however this ends (connected,
# too late, or stopped), and one left behind by a wait that was killed is too old
# the next time. Its secret proves the device: whoever answers hello with the host key we
# saw, signed by it, is the one that scanned the code, not someone in between.
phone_wait() {
  need ssh
  need openssl
  local_can_discover
  [[ -s $PHONE_PENDING ]] || die "no code to wait for; make one with: $me connect"
  local secret left deadline host port cid scanned reply proof want tmp unproven= id pname
  # Its own secret only: a newer code made meanwhile is another wait's.
  wait_end() { rm -f "$wait_tmp"; [[ $(cat "$PHONE_PENDING" 2>/dev/null) != "$wait_secret" ]] || rm -f "$PHONE_PENDING"; }
  left=$((INVITE_SECONDS - ($(date +%s) - $(file_mtime "$PHONE_PENDING"))))
  if ((left <= 0 || left > INVITE_SECONDS)); then
    rm -f "$PHONE_PENDING"
    die "that code is too old; make a new one with: $me connect"
  fi
  deadline=$((SECONDS + left))
  secret=$(<"$PHONE_PENDING")
  tmp=$(mktemp)
  wait_tmp=$tmp wait_secret=$secret
  trap wait_end EXIT
  while ((SECONDS < deadline)); do
    while read -r host port cid; do
      [[ -n $host ]] || continue
      scanned=$(ssh-keyscan -T 3 -t ed25519 -p "$port" -- "$host" 2>/dev/null | awk '!/^#/ {print $2 " " $3; exit}') || true
      [[ -n $scanned ]] || continue
      echo "pending $scanned" >"$tmp"
      # Whoever answers here isn't proven yet: as phone_ssh, so it's handed nothing but the request.
      reply=$(PHONE_HOSTS=$tmp phone_ssh "$host" "$port" pending hello </dev/null 2>/dev/null) || continue
      proof=$(jq -r '.proof // empty' <<<"$reply" 2>/dev/null) || continue
      want=$(printf %s "$scanned" | openssl dgst -sha256 -hmac "$secret" | awk '{print $NF}')
      # A device this one was connected to before answers too, until the code is
      # confirmed on it; anyone else in between never has the proof.
      if [[ -z $proof || $proof != "$want" ]]; then
        unproven=$host
        continue
      fi
      id=$(jq -r .id <<<"$reply")
      pname=$(jq -r .phone <<<"$reply")
      [[ $id =~ ^[0-9a-f]+$ ]] || { rm -f "$tmp"; die "the device sent no id"; }
      # Beside the devices connected before; the same one connected again replaces its line.
      { grep -v "^omalauncher-$id " "$PHONE_HOSTS" 2>/dev/null || true; echo "omalauncher-$id $scanned"; } >"$PHONE_HOSTS.tmp" && mv "$PHONE_HOSTS.tmp" "$PHONE_HOSTS"
      mkdir -p "$PHONES"
      jq -n --arg id "$id" --arg name "$pname" --arg host "$host" --argjson port "$port" '{id: $id, name: $name, host: $host, port: $port}' >"$PHONES/$id.json"
      rm -f "$tmp" "$PHONE_PENDING"
      echo "Connected $pname"
      return
    done < <(phone_candidates)
    sleep 2
  done
  rm -f "$tmp"
  [[ -z $unproven ]] || die "the device at $unproven answered, but never with this code's proof (was it confirmed there?); nothing was saved"
  die "no device answered in $((INVITE_SECONDS / 60)) minutes; make a new code with: $me connect"
}

cmd_connect() {
  local_can_discover
  phone_invite
  if [[ -z $png && -t 1 ]] && command -v qrencode >/dev/null 2>&1; then
    qrencode -t ANSIUTF8 -l L -m 2 "$invite_link"
  elif [[ -z $png ]]; then
    echo "$invite_link"
  fi
  echo "Scan it with the device's camera; Omalauncher opens. Connect only if it shows: $invite_code"
  echo "Waiting for the device (Omalauncher, with Linux, on this network)…"
  phone_wait
  echo "Its widgets, theme and background sync from now on; $me lists the connected ones."
}

# ---- following -----------------------------------------------------------------
# Each device is watched (`watch` there waits for its theme, background or synced
# widgets to change); this device is looked at every few seconds. A change from
# one device is applied here and passed on to the others; neither side applies
# what it already has, so a change goes round once.

from_phone_bg() { local f; f=$(readlink -f "$BG_LINK" 2>/dev/null) && printf '%s %s\n' "$f" "$PH" >"$FROM_PHONE_BG" || true; }

# The device PH switched to theme $1 (from repo $2, for one someone made).
follow_apply() {
  local t=$1 src=${2-} cur
  cur=$(local_theme 2>/dev/null) || cur=
  [[ $t == "$cur" ]] && return 0
  if local_theme_has "$t"; then
    printf '%s %s\n' "$t" "$PH" >"$FROM_PHONE_THEME"
    local_theme_set "$t"
    # The theme's first background came with it: not a change made here either.
    from_phone_bg
  elif [[ $src =~ ^https://github\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/?$ ]]; then
    local_theme_offer "$t" "$src"
  else
    local_notify "$(phone_info name) switched to $t" "This device has no theme by that name, so it stays as it is."
  fi
}

bg_image() { [[ $1 =~ ^[A-Za-z0-9_][A-Za-z0-9._+-]*\.(jpg|jpeg|png|webp|JPG|JPEG|PNG|WEBP)$ ]]; }

# The device PH shows background $2 of theme $1: here too, fetched if need be.
follow_bg() {
  local t=$1 bg=$2 file= dir
  bg_image "$bg" || return 0
  [[ $(local_theme 2>/dev/null) == "$t" ]] || return 0
  [[ $(basename -- "$(readlink -f "$BG_LINK" 2>/dev/null)") == "$bg" ]] && return 0
  while read -r dir; do
    [[ -f $dir/$bg ]] && { file=$dir/$bg; break; }
  done < <(local_bg_dirs "$t")
  if [[ -z $file ]]; then
    dir=$(local_bg_store "$t")
    mkdir -p "$dir"
    file=$dir/$bg
    if ! (phone_run bgget "$bg" </dev/null >"$file.tmp") 2>/dev/null || [[ ! -s $file.tmp ]]; then
      rm -f "$file.tmp"
      return 0
    fi
    mv "$file.tmp" "$file"
  fi
  printf '%s %s\n' "$file" "$PH" >"$FROM_PHONE_BG"
  local_bg_set "$t" "$file"
  from_phone_bg
}

# Background file $2 of theme $1 to the device PH, the image too if it hasn't got it.
bg_send() {
  local code=0
  (phone_run background "$1" "$(basename -- "$2")" </dev/null >/dev/null 2>&1) || code=$?
  # 1: it hasn't got it, so it goes along.
  if ((code == 1)); then
    (phone_run bgput "$1" "$(basename -- "$2")" <"$2" >/dev/null 2>&1) || true
  fi
}

synced_names() { echo "$PHONE/synced-$PH"; }

# Brings the widgets synced with the device PH ($@, or all of them) into line
# with it. The newest wins: for a file by when it was last changed, for values
# by when they were last saved. The same values are never sent back.
phone_widget_sync() {
  local list n psha pmod psaved f lsha lmod lsaved pv lv names
  exec 8>"$PHONE/widgets.lock"
  flock 8
  list=$(phone_run synced </dev/null) || return 0
  # A name becomes a path here ($DIR/<name>, $STATE/<name>.json), so only a widget's file name is
  # taken, as the device itself takes them (ComputerLink.validName): never ../ or a path from it.
  list=$(jq -c '[.[] | select((.name | type) == "string" and (.name | length) <= 120 and (.name | test("\\A[A-Za-z0-9][A-Za-z0-9._-]*\\.owl\\z")))]' <<<"$list") || return 0
  names=$(synced_names)
  jq -r '.[].name' <<<"$list" >"$names.tmp" && mv "$names.tmp" "$names"
  mkdir -p "$DIR"
  while IFS=$'\t' read -r n psha pmod psaved; do
    [[ -n $n ]] || continue
    (($# == 0)) || [[ " $* " == *" $n "* ]] || continue
    f=$DIR/$n
    lsha=$(file_sha "$f")
    lmod=$(file_mtime "$f")
    if [[ -n $psha && $psha != "$lsha" ]] && { [[ -z $lsha ]] || ((pmod > lmod)); }; then
      phone_run get "$n" </dev/null | jq -j .text >"$f.tmp" && mv "$f.tmp" "$f" || rm -f "$f.tmp"
    elif [[ -n $lsha && $psha != "$lsha" ]]; then
      phone_run put "$n" <"$f" >/dev/null
    fi
    lsaved=$(local_state_saved "$n")
    # Saved at the same time on both: the same save, taken one way or the other before.
    ((psaved == lsaved)) && continue
    pv=$(phone_run state get "$n" </dev/null | jq -cS '.values // {}') || continue
    lv=$(local_state_get "$n")
    [[ $pv == "$lv" ]] && continue
    if ((psaved > lsaved)); then
      local_state_set "$n" "$pv" "$psaved"
    elif ((lsaved > 0)); then
      jq -c --argjson at "$lsaved" '{savedAt: $at, values: .}' <<<"$lv" | phone_run state put "$n" >/dev/null
    fi
  done < <(jq -r '.[] | [.name, .sha256, (.modified | floor), (.savedAt | floor)] | @tsv' <<<"$list")
}

# Looks at this device every few seconds and sends what changed to every device
# but the one it came from; ends with the follower ($1, its pid).
local_watch() {
  local main=$1 tprev tcur bprev bcur wprev wcur from id t src every=3
  [[ $KIND == omalauncher ]] && every=5
  tprev=$(local_theme 2>/dev/null) || tprev=
  bprev=$(readlink -f "$BG_LINK" 2>/dev/null) || bprev=
  wprev=$(local_widgets_digest)
  while kill -0 "$main" 2>/dev/null; do
    sleep $every
    # A synced widget changed here (its file or its values): to each device, the newest winning.
    wcur=$(local_widgets_digest)
    if [[ $wcur != "$wprev" ]]; then
      for id in $(phone_ids); do (phone_use "$id"; phone_widget_sync 2>/dev/null) || true; done
      wprev=$(local_widgets_digest)
    fi
    tcur=$(local_theme 2>/dev/null) || tcur=
    if [[ -n $tcur && $tcur != "$tprev" ]]; then
      tprev=$tcur
      from=
      if [[ $(cut -d' ' -f1 "$FROM_PHONE_THEME" 2>/dev/null) == "$tcur" ]]; then
        from=$(cut -d' ' -f2 "$FROM_PHONE_THEME")
        rm -f "$FROM_PHONE_THEME"
      fi
      src=$(local_theme_source "$tcur")
      for id in $(phone_ids); do
        [[ $id != "$from" ]] || continue
        (phone_use "$id"; phone_run theme "$tcur" ${src:+"$src"} </dev/null >/dev/null 2>&1) || true
      done
    fi
    [[ -L $BG_LINK || -e $BG_LINK ]] || continue
    bcur=$(readlink -f "$BG_LINK" 2>/dev/null) || continue
    [[ $bcur != "$bprev" ]] || continue
    bprev=$bcur
    from=
    if [[ $(cut -d' ' -f1 "$FROM_PHONE_BG" 2>/dev/null) == "$bcur" ]]; then
      from=$(cut -d' ' -f2 "$FROM_PHONE_BG")
      rm -f "$FROM_PHONE_BG"
    fi
    [[ -f $bcur ]] || continue
    bg_image "$(basename -- "$bcur")" || continue
    t=$(local_theme 2>/dev/null) || continue
    for id in $(phone_ids); do
      [[ $id != "$from" ]] || continue
      (phone_use "$id"; bg_send "$t" "$bcur") || true
    done
  done
}

# Runs until killed. One watch per device, started as devices are connected;
# each ends with its device or with this.
cmd_follow() {
  local id main=$$
  declare -A loops=()
  # One per user: a second (a shell restarted while the old one lingers) leaves.
  mkdir -p "$PHONE"
  exec 9>"$PHONE/follow.lock"
  flock -n 9 || exit 0
  local_watch "$main" 9>&- &
  while :; do
    for id in $(phone_ids); do
      if [[ -z ${loops[$id]-} ]] || ! kill -0 "${loops[$id]}" 2>/dev/null; then
        follow_one "$id" "$main" </dev/null >/dev/null 2>&1 9>&- &
        loops[$id]=$!
      fi
    done
    sleep 15
  done
}

# Follows the device $1 while it's connected and the follower ($2) runs.
follow_one() {
  local seen= out code t src bg synctheme key wait=30
  phone_use "$1"
  # What changed while apart: the newest wins.
  (phone_widget_sync) || true
  while [[ -s $PHONES/$PH.json ]] && kill -0 "$2" 2>/dev/null; do
    code=0
    # In a subshell: phone_run's die (the device gone from the network) ends only it.
    out=$(phone_run watch ${seen:+"$seen"} 2>/dev/null) || code=$?
    if ((code == 0)); then
      t=$(jq -r '.theme // empty' <<<"$out" 2>/dev/null) || t=
      src=$(jq -r '.source // empty' <<<"$out" 2>/dev/null) || src=
      bg=$(jq -r '.background // empty' <<<"$out" 2>/dev/null) || bg=
      synctheme=$(jq -r '.syncTheme' <<<"$out" 2>/dev/null) || synctheme=false
      key=$(jq -r '.key // empty' <<<"$out" 2>/dev/null) || key=
      # The first answer is where the device is now: taken as seen, not applied.
      if [[ -n $t && -n $seen && $key != "$seen" ]]; then
        [[ $synctheme != true || $t == "${seen%%/*}" ]] || follow_apply "$t" "$src"
        [[ -z $bg ]] || follow_bg "$t" "$bg"
        # A synced widget changed there.
        [[ ${key##*/} == "${seen##*/}" ]] || (phone_widget_sync) || true
      fi
      [[ -z $key ]] || seen=$key
      wait=30
      continue
    fi
    # 3: sync turned off there, or disconnected; else not reachable, asked again
    # less and less often (up to every 5 minutes): it may be away for the day.
    if ((code == 3)); then
      sleep 300
    else
      sleep $wait
      wait=$((wait * 2 > 300 ? 300 : wait * 2))
    fi
  done
}

# ---- commands --------------------------------------------------------------------

yes=0
png=
device_arg=
args=()
for a in "$@"; do
  case $a in
    --yes | -y) yes=1 ;;
    --png=*) png=${a#--png=} ;;
    --device=* | --phone=*) device_arg=${a#--*=} ;;
    *) args+=("$a") ;;
  esac
done
set -- "${args[@]+"${args[@]}"}"

usage() { sed -n '18,40p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

sub=${1:-status}
(($#)) && shift
phone_migrate
case $sub in connect | link | invite | wait | follow | disconnect | unlink | info | -h | --help | help) ;; *) [[ -z $device_arg ]] || phone_pick ;; esac
case $sub in
  connect | link) (($# == 0)) || usage; cmd_connect ;;
  invite) (($# == 0)) || usage; phone_invite; jq -cn --arg link "$invite_link" --arg code "$invite_code" '{link: $link, code: $code}' ;;
  wait) (($# == 0)) || usage; phone_wait ;;
  follow) (($# == 0)) || usage; cmd_follow ;;
  info)
    (($# == 0)) || usage
    for id in $(phone_ids); do cat "$PHONES/$id.json"; done | jq -cs .
    ;;
  sync)
    # A device keeps them (its settings, connected devices): this reads or sets them there.
    case $# in
      0) phone_pick; phone_run sync </dev/null ;;
      2) [[ $1 == theme || $1 == background ]] && [[ $2 == on || $2 == off ]] || usage
         phone_pick
         phone_run sync "$1" "$2" </dev/null ;;
      3) [[ $1 == widget ]] && [[ $3 == on || $3 == off ]] || usage
         n=${2##*/}
         [[ $n == *.owl ]] || n=$n.owl
         # With every device unless --device says one; each prints what it syncs now.
         for id in $(phone_targets); do
           (
             phone_use "$id"
             phone_run sync widget "$n" "$3" </dev/null | jq -c --arg id "$id" '. + {phone: $id}'
             # Marked: the two sides are made the same now, the newest winning.
             [[ $3 == off ]] || phone_widget_sync "$n" >/dev/null 2>&1 || true
           ) || true
         done
         ;;
      *) usage ;;
    esac
    ;;
  status)
    [[ -n $(phone_ids) ]] || { echo "No device connected: $me connect"; exit 0; }
    for id in $(phone_targets); do
      (
        phone_use "$id"
        (phone_find) >/dev/null 2>&1 || { echo "$(phone_info name): not on the network"; exit 0; }
        phone_find
        out=$(phone_ssh "$phone_host" "$phone_port" "omalauncher-$id" hello </dev/null 2>/dev/null) ||
          { echo "$(phone_info name): didn't answer (disconnected there? $me connect)"; exit 0; }
        echo "$(jq -r .phone <<<"$out") at $phone_host:$phone_port, Omalauncher $(jq -r .version <<<"$out")"
      )
    done
    ;;
  widgets) (($# == 0)) || usage; phone_pick; phone_run widgets </dev/null | jq -r '.[] | "\(.name)\t\(.size) bytes"' ;;
  push)
    (($#)) || usage
    for f in "$@"; do
      [[ -f $f ]] || die "no such file: $f"
      [[ $f == *.owl ]] || die "$f isn't a .owl file"
    done
    [[ -n $(phone_ids) ]] || die "no device connected; connect one with: $me connect"
    code=0
    for id in $(phone_targets); do
      for f in "$@"; do
        (phone_use "$id"; phone_run put "$(basename "$f")" <"$f" | jq -r --arg p "$(phone_info name)" 'if .created then "added \(.name) on \($p)" else "updated \(.name) on \($p)" end') || code=1
      done
    done
    exit $code
    ;;
  pull)
    (($#)) || usage
    phone_pick
    mkdir -p "$DIR"
    for n in "$@"; do
      [[ $n == *.owl ]] || n=$n.owl
      out=$DIR/$n
      if [[ -e $out ]] && ((!yes)); then
        confirm "Replace $(tilde "$out") with $(phone_info name)'s?" || continue
      fi
      phone_run get "$n" </dev/null | jq -j .text >"$out.tmp" && mv "$out.tmp" "$out" || { rm -f "$out.tmp"; die "couldn't get $n"; }
      echo "pulled $(tilde "$out")"
    done
    ;;
  remove)
    (($# == 1)) || usage
    phone_pick
    n=$1
    [[ $n == *.owl ]] || n=$n.owl
    ((yes)) || confirm "Delete $n from $(phone_info name)?" || exit 0
    phone_run remove "$n" </dev/null && echo "removed $n from $(phone_info name)"
    ;;
  themes) (($# == 0)) || usage; phone_pick; phone_run themes </dev/null | jq -r '.[] | (if .current then "* " else "  " end) + .name' ;;
  theme)
    (($# <= 1)) || usage
    t=${1-}
    [[ -n $t ]] || t=$(local_theme) || die "no theme here; name one: $me theme <name>"
    src=$(local_theme_source "$t")
    for id in $(phone_targets); do
      (phone_use "$id"; phone_run theme "$t" ${src:+"$src"} </dev/null && echo "$(phone_info name) switches to $t") || true
    done
    ;;
  disconnect | unlink)
    (($# <= 1)) || usage
    [[ -n $(phone_ids) ]] || { echo "No device connected."; exit 0; }
    [[ -z ${1-} ]] || device_arg=$1
    phone_pick
    n=$(phone_info name)
    rm -f "$PHONES/$PH.json" "$PHONE/synced-$PH"
    { grep -v "^omalauncher-$PH " "$PHONE_HOSTS" 2>/dev/null || true; } >"$PHONE_HOSTS.tmp" && mv "$PHONE_HOSTS.tmp" "$PHONE_HOSTS"
    echo "Forgot $n. Disconnect this one there too: settings, connected devices."
    ;;
  call)
    # What a connected device answers, as it is: for agents and scripts. The device takes only these
    # requests from this one, whatever is asked here.
    (($#)) || usage
    phone_pick
    phone_run "$@"
    ;;
  -h | --help | help) usage ;;
  *) usage ;;
esac
