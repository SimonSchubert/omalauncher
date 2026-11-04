# Home prompt attachments: upstream updates

This change lives on the isolated `feature/home-attachments` branch in a clone of Omalauncher's upstream repository. It does not modify the installed launcher or its release branch.

To catch up with upstream releases:

```sh
git fetch origin --prune --tags
git rebase origin/main
```

To base on a specific release instead, rebase onto that release tag. Keep this feature commit separate so it can be reapplied or dropped independently. The expected overlap points are `PromptBar.kt` (the button, chips, and send path), `Runtime.kt` (copying a picked URI into Linux `/tmp`), and the translated `strings_files.xml` resources. Resolve conflicts against the new prompt-bar and file-transfer APIs; do not overwrite newer upstream code with this patch wholesale.

Before carrying it into a personal fork or building an APK, run the upstream Android build/tests and exercise both photo-picker and document-picker paths on-device. This worktree has no Android SDK or device bridge, so it cannot produce or install a verified APK here.
