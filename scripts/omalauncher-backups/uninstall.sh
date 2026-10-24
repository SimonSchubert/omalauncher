#!/usr/bin/env bash
set -euo pipefail

home_dir="${HOME:?HOME must be set}"
systemctl --user disable --now omalauncher-backup.service >/dev/null 2>&1 || true
rm -f -- "$home_dir/.config/systemd/user/default.target.wants/omalauncher-backup.service"
rm -f -- "$home_dir/.config/systemd/user/omalauncher-backup.service"
rm -f -- "$home_dir/.config/autostart/omalauncher-backup.desktop"
rm -f -- "$home_dir/.config/omalauncher/widgets/omalauncher-backups.owl"
rm -f -- "$home_dir/.local/bin/omalauncher-backup"
systemctl --user daemon-reload >/dev/null 2>&1 || true
echo "Removed the app and watcher. Backup history was left untouched."
