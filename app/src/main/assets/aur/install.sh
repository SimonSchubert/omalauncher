#!/bin/sh
# Builds and installs an AUR package with yay. Runs as (proot's fake) root;
# /usr/local/bin/yay runs the build as the unprivileged `builder`.
set -e
if [ ! -r /dev/omalauncher-aur-approval ]; then
    echo "AUR installs are blocked unless approved from Omalauncher's package screen" >&2
    exit 77
fi
pkg="$1"
case "$pkg" in
    '' | -* | *[!a-z0-9@._+-]*) echo "invalid package name: $pkg" >&2; exit 2 ;;
esac

# A full sync first, like repo installs: never a partial upgrade.
pacman -Syu --noconfirm --needed base-devel git

# yay is our wrapper (assets/aur/yay): it builds as the unprivileged builder,
# getting yay itself first if needed.
# aur/ keeps yay from picking a repo package that provides the same name.
yay -S --noconfirm --needed --answerdiff None --answerclean None --answeredit None --removemake --cleanafter "aur/$pkg"

rm -rf /var/cache/pacman/pkg/* /home/builder/.cache/yay
