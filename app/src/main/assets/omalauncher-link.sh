#!/bin/bash
# omalauncher-link: what a linked computer runs on the phone (ComputerLink.kt). Its ssh key is let in only for this,
# as the omalink user, with the computer's id as the argument; what it asked for is in SSH_ORIGINAL_COMMAND. Each
# request goes to the launcher through `omalauncher link`, which checks the computer is still linked, does it, and
# answers in JSON; a widget sent with put comes on stdin.
set -u
id=${1-}
# Elsewhere only for the tests.
omalauncher=${OMALAUNCHER_COMMAND:-omalauncher}
tmpdir=${OMALAUNCHER_LINK_TMP:-/tmp}
# The picked theme's name, which the launcher keeps up to date (ComputerLink.linuxChanged).
themefile=${OMALAUNCHER_LINK_THEME:-/var/lib/omalauncher-link/theme}
# The most a widget may be, and a background; the launcher refuses bigger ones too.
max=1048576
bgmax=33554432

usage() {
  cat >&2 <<USAGE
omalauncher-link: one of
  hello              this phone: {phone, id, version, proof}
  widgets            the widgets: [{name, sha256, size, modified}]
  get <name>         one widget: {name, text}
  put <name>         adds or replaces one, sent on stdin: {name, sha256, created}
  remove <name>      deletes one
  themes             the themes the phone can switch to: [{name, current}]
  theme <name> [repo]
                     switches to one; one it hasn't got, from that GitHub repo,
                     is installed first
  watch [key]        waits until the phone's theme/background/widgets is no longer
                     key, up to five minutes: {theme, source, background, key,
                     syncTheme}; source is the GitHub repo of a theme someone
                     made, background "" unless it syncs
  background <theme> <file>
                     shows that background of the theme, if the phone is on it;
                     exit 1 if the phone hasn't got it
  bgput <theme> <file>
                     adds the image sent on stdin to the theme's backgrounds
                     and shows it
  bgget <file>       one of the current theme's backgrounds, the image itself
  sync [theme|background on|off | widget <name> on|off]
                     what this computer syncs, as the phone's settings show it,
                     or one of them set: {theme, background, widgets}
  synced             the synced widgets: [{name, sha256, modified, savedAt}]
  state get <name>   a synced widget's persist values: {savedAt, values}
  state put <name>   the same, sent on stdin; kept if newer: {taken}
USAGE
  exit 2
}

[ -n "$id" ] || usage
read -r -a words <<<"${SSH_ORIGINAL_COMMAND-}"
[ ${#words[@]} -ge 1 ] || usage
verb=${words[0]}
case $verb in
  hello | widgets | themes) [ ${#words[@]} -eq 1 ] || usage ;;
  get | remove) [ ${#words[@]} -eq 2 ] || usage ;;
  theme) [ ${#words[@]} -eq 2 ] || [ ${#words[@]} -eq 3 ] || usage ;;
  background) [ ${#words[@]} -eq 3 ] || usage ;;
  sync) [ ${#words[@]} -eq 1 ] || [ ${#words[@]} -eq 3 ] || { [ ${#words[@]} -eq 4 ] && [ "${words[1]}" = widget ]; } || usage ;;
  synced) [ ${#words[@]} -eq 1 ] || usage ;;
  state)
    [ ${#words[@]} -eq 3 ] || usage
    case ${words[1]} in
      get) ;;
      put)
        tmp=$(mktemp "$tmpdir/omalauncher-link.XXXXXX") || exit 1
        head -c $((max + 1)) >"$tmp"
        if [ "$(wc -c <"$tmp")" -gt $max ]; then
          rm -f "$tmp"
          echo "omalauncher-link: a widget's values are at most $((max / 1024)) KB" >&2
          exit 2
        fi
        "$omalauncher" link state "$id" put "${words[2]}" "$tmp"
        code=$?
        rm -f "$tmp"
        exit $code
        ;;
      *) usage ;;
    esac
    ;;
  bgget)
    [ ${#words[@]} -eq 2 ] || usage
    answer=$("$omalauncher" link bgget "$id" "${words[1]}") || exit $?
    path=$(printf '%s' "$answer" | sed -n 's/.*"path":"\([^"]*\)".*/\1/p')
    case $path in "$tmpdir"/omalauncher-bg-* | /tmp/omalauncher-bg-*) ;; *) exit 1 ;; esac
    cat "$path"
    code=$?
    rm -f "$path"
    exit $code
    ;;
  bgput)
    [ ${#words[@]} -eq 3 ] || usage
    tmp=$(mktemp "$tmpdir/omalauncher-link.XXXXXX") || exit 1
    head -c $((bgmax + 1)) >"$tmp"
    if [ "$(wc -c <"$tmp")" -gt $bgmax ]; then
      rm -f "$tmp"
      echo "omalauncher-link: a background is at most $((bgmax >> 20)) MB" >&2
      exit 2
    fi
    "$omalauncher" link bgput "$id" "${words[1]}" "${words[2]}" "$tmp"
    code=$?
    rm -f "$tmp"
    exit $code
    ;;
  watch)
    # A computer that follows the phone's theme keeps asking this. Allowed once,
    # it reads the file every 2 s rather than asking the launcher, and answers
    # within five minutes, or as soon as what devices may follow changed (the
    # file's fifth line), so it's asked again then: each request wakes the phone.
    [ ${#words[@]} -le 2 ] || usage
    seen=${words[1]-}
    allowed=$("$omalauncher" link watch "$id") || exit $?
    synctheme=false syncbg=
    case $allowed in *'"theme":true'*) synctheme=true ;; esac
    case $allowed in *'"background":true'*) syncbg=1 ;; esac
    enable -f /usr/lib/bash/sleep sleep 2>/dev/null
    theme= source= bg= widgets= key= links= allowedlinks=
    for ((i = 0; i < 150; i++)); do
      theme= source= bg= widgets= links=
      { read -r theme; read -r source; read -r bg; read -r widgets; read -r links; } 2>/dev/null <"$themefile"
      [ "$i" -gt 0 ] || allowedlinks=$links
      case $widgets in *[!A-Za-z0-9]*) widgets= ;; esac
      case $theme in *[!A-Za-z0-9._-]*) theme= ;; esac
      # The launcher writes only GitHub URLs; anything else in the file isn't passed on.
      case $source in https://github.com/*) case $source in *[!A-Za-z0-9._/:-]*) source= ;; esac ;; *) source= ;; esac
      case $bg in *[!A-Za-z0-9._+-]*) bg= ;; esac
      [ -n "$syncbg" ] || bg=
      key=$theme/$bg/$widgets
      [ -n "$theme" ] && [ "$key" != "$seen" ] && break
      [ "$links" = "$allowedlinks" ] || break
      sleep 2
    done
    printf '{"theme":"%s","source":"%s","background":"%s","key":"%s","syncTheme":%s}\n' "$theme" "$source" "$bg" "$key" "$synctheme"
    exit 0
    ;;
  put)
    [ ${#words[@]} -eq 2 ] || usage
    tmp=$(mktemp "$tmpdir/omalauncher-link.XXXXXX") || exit 1
    head -c $((max + 1)) >"$tmp"
    if [ "$(wc -c <"$tmp")" -gt $max ]; then
      rm -f "$tmp"
      echo "omalauncher-link: a widget is at most $((max / 1024)) KB" >&2
      exit 2
    fi
    # The launcher reads it and deletes it.
    "$omalauncher" link put "$id" "${words[1]}" "$tmp"
    code=$?
    rm -f "$tmp"
    exit $code
    ;;
  *) usage ;;
esac
exec "$omalauncher" link "$verb" "$id" "${words[@]:1}"
