#!/usr/bin/env bash
set -euo pipefail

package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
home_dir="${HOME:?HOME must be set}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 is required (Arch package: python)." >&2
  exit 1
fi

backup_old() {
  local target="$1"
  if [[ -e "$target" ]]; then
    cp -p -- "$target" "$target.previous.$(date +%Y%m%d%H%M%S)"
    echo "Kept previous file: $target.previous.*"
  fi
}

app_dir="$home_dir/.config/omalauncher/widgets"
bin_dir="$home_dir/.local/bin"
service_dir="$home_dir/.config/systemd/user"
autostart_dir="$home_dir/.config/autostart"
backup_dir="$home_dir/.local/share/omalauncher-backups"

mkdir -p "$app_dir" "$bin_dir" "$service_dir" "$autostart_dir" "$backup_dir"

backup_old "$app_dir/omalauncher-backups.owl"
backup_old "$bin_dir/omalauncher-backup"
backup_old "$service_dir/omalauncher-backup.service"
backup_old "$autostart_dir/omalauncher-backup.desktop"
install -m 644 "$package_dir/omalauncher-backups.owl" "$app_dir/omalauncher-backups.owl"
install -m 755 "$package_dir/omalauncher-backup" "$bin_dir/omalauncher-backup"
install -m 644 "$package_dir/omalauncher-backup.service" "$service_dir/omalauncher-backup.service"
sed "s|@HOME@|$home_dir|g" "$package_dir/omalauncher-backup.desktop.in" > "$autostart_dir/omalauncher-backup.desktop"
chmod 644 "$autostart_dir/omalauncher-backup.desktop"
ln -sfn ../omalauncher-backup.service "$service_dir/default.target.wants/omalauncher-backup.service" 2>/dev/null || {
  mkdir -p "$service_dir/default.target.wants"
  ln -sfn ../omalauncher-backup.service "$service_dir/default.target.wants/omalauncher-backup.service"
}

if [[ ! -e "$backup_dir/settings.json" ]]; then
  printf '{"auto": true, "keep": 50}\n' > "$backup_dir/settings.json"
fi

if systemctl --user daemon-reload >/dev/null 2>&1; then
  systemctl --user enable --now omalauncher-backup.service || true
else
  nohup "$bin_dir/omalauncher-backup" watch >/dev/null 2>&1 </dev/null &
fi

echo "Installed. Open 'Sauvegardes OmaLauncher' from the app drawer."
echo "Existing snapshots, if any, were kept in $backup_dir."
