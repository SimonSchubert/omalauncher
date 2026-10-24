OmaLauncher Backups 1.2
=======================

Install
-------

1. Extract this archive on the Linux side where OmaLauncher runs.
2. Open a terminal in the extracted folder and run:

   ./install.sh

The app appears in OmaLauncher's app drawer as "Sauvegardes OmaLauncher".
Python 3 is required (Arch package: python). The installer keeps backups of
any app or script it replaces. Existing backup history is never removed.

Features
--------

- Watches the OmaLauncher configuration and saves a snapshot after changes
  settle for two seconds.
- Stores file contents by hash, so unchanged files are shared between snapshots.
- Starts with automatic backups enabled and retains the newest 50 snapshots.
- The app lets you pause/resume automatic backups, set the retention limit,
  create a manual snapshot, and restore either selected files or a whole snapshot.
- Before any restore it saves the current files as a recovery snapshot.

The backup folder is ~/.local/share/omalauncher-backups. The app and watcher
cover ~/.config/omalauncher, ~/.local/share/omalauncher, and
~/.local/state/omalauncher, plus their own launcher files. Backups are not
included in this installer archive; a new install starts its own history.

Restore safety
--------------

Restoration is available in the app, but has not been tested yet. Before a
restore, the app creates a recovery snapshot of the current files.

Uninstall
---------

Run ./uninstall.sh from the extracted folder. It removes the app and watcher,
but intentionally leaves the backup folder and its history in place.
