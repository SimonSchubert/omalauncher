# Linux files in Android apps

When Linux's home exists, Android's document picker and Files app show a **Linux**
root. Android apps can open or save individual files, or request a folder using
`ACTION_OPEN_DOCUMENT_TREE`. Reads, writes, creation, renaming and deletion use
`files/arch/root` directly; Linux does not have to be running.

Only the guest home is exposed, not the rest of the rootfs or the launcher's
private files. Dotfiles, including `.ssh` and `.config`, are visible. Choose an
individual file or a narrower folder when an app does not need the entire home.
Proot's `.l2s.*` backing files are not exposed independently.

## Implementation

`LinuxDocumentsProvider` is protected by `MANAGE_DOCUMENTS` and uses
`${applicationId}.linux`; the existing `${applicationId}.files` FileProvider is
unchanged. The root has the launcher icon, local-only and tree-grant support,
and is omitted when home does not exist. Writable folders support creation;
non-root entries support deletion and renaming. Home cannot be deleted or
renamed, including through a link to home.

Document IDs remain `home` and `home/<relative path>`, as in the original
`documents` branch. `DocumentsContract` encodes an ID as a URI segment, so literal
percent signs, plus signs and Unicode filenames need no second encoding layer.
Empty, dot, parent and proot-internal path segments are refused. Every lookup
checks canonical containment; directory listings omit broken or escaping links
and non-regular files. A tree descendant must be both a lexical and a resolved
descendant of the selected folder, so links cannot widen a subfolder grant.

Links within a selected tree remain usable. Rename and delete retain the link's
own path rather than operating on its resolved target. Recursive deletion uses
`Files.walkFileTree` without following links and revokes deleted descendants'
URI permissions. Names are sanitized, and collisions, including dangling links,
receive numbered names instead of overwriting an existing entry.

For alias discovery, rename and deletion list each physical home directory once
per operation, resolve symlinks rather than repeatedly canonicalizing unrelated regular files,
and expand only affected descendant and alias IDs before changing the filesystem.
The graph uses already-canonical `File` paths with separator-aware descent checks.
Android supplies file types using public `Os.lstat`/`Os.stat`, avoiding per-entry
NIO path encoding; JVM tests retain the NIO metadata fallback and exercise the
injected-reader contract.
The graph is not retained between operations, so guest-created aliases do not
rely on a watcher or a stale cache. Discovery still scales with home size; if a
searchable directory cannot be listed, mutation fails rather than silently
retaining a potentially affected grant. Unsearchable mode000 bind placeholders
cannot expose descendant documents and do not block unrelated mutations. This
also conservatively revokes grants to
unchanged aliases of affected linked content;
those files may need selecting again. Grants to the lexical containing folders
are retained. A no-op or failed rename revokes nothing; Android handles the
renamed document's own grant after the provider returns.

Document and child cursors register notification URIs. Provider mutations and
completed writable descriptors notify their document and parent listing. Linux
installation and removal notify the roots URI so the picker can refresh its
sidebar. Guest writes made outside the provider do not have a directory watcher;
reopen the listing to refresh them. Canonical-path checks do not provide race-free
isolation from a guest concurrently replacing filesystem entries.

## Runtime verification

Measure mutation latency in an optimized, non-debuggable build on the target device.
On the 2026-10-22 Pixel run, JIT was disabled and ART forced the debuggable APK to
`verify` even when `cmd package compile -m speed` was requested. Debug-provider
rename/delete operations took tens of seconds; a non-debuggable, AOT-compiled
fork port of the same provider took 1,379/622 ms through the shell probe and
1,034/944 ms through an ordinary Android client. These are observations, not
constant-time guarantees: discovery still scales with the home directory.
Changing the build type does not justify changing the device's global JIT policy.

The separate client held real Android URI permissions, not the shell's
`MANAGE_DOCUMENTS` access. Tests covered temporary service-grant expiry, persisted
grants surviving a client force-stop, descendant and symlink-alias revocation on
rename/delete, continued parent/unrelated access, and no access resurrection when
old paths were recreated. The live tests used an in-place, same-signer fork port;
they are not a claim that an upstream-main APK was installed. System-picker UI
verification is recorded separately from these Intent-granted client tests.

## Consolidation

This combines [Person1873's documents branch](https://github.com/Person1873/omalauncher/tree/documents)
with the filesystem-test approach and regression cases from
[amirinanto-r37's PR #37](https://github.com/SimonSchubert/omalauncher/pull/37).
The latter's unusual-name, traversal, broken-link, escaping-link, internal-link,
collision and root cases are adapted to the existing IDs in `LinuxDocumentsTest`.
The second provider and its incompatible ID scheme are not installed alongside
the first. The separate Android-to-Linux file chooser and desktop portal branches
are not dependencies.

## Verification

Run the existing suites and build checks:

```sh
./gradlew :owl:test :app:testDebugUnitTest :owl:owlJar
./gradlew :app:assembleDebug :app:lintDebug
```

`LinuxDocumentsTest` exercises the real filesystem without an Android context:
name and root round-trips, malformed IDs, symlink containment, tree-grant
boundaries, protected home, no-follow recursive deletion, descendant revocation
callbacks, safe names and collision handling.

Manual Android checks:

1. Without Linux installed, verify the Linux root is absent. With home present,
   browse it through a document picker while Linux is stopped.
2. Grant home and a subfolder separately. Open top-level and nested files;
   verify a subfolder grant cannot open a sibling through traversal or a link.
3. Create, save, rename and delete files and folders. Verify listings refresh
   after provider operations and home has no delete or rename action.
4. Open filenames containing spaces, `#`, `%`, `+` and Unicode. Confirm dotfiles
   appear but `.l2s.*`, broken links and escaping links do not.
5. Delete a folder containing links and verify the targets remain intact.
6. Keep an app's persisted grant to a child, rename its parent in another client,
   recreate the original path, and verify the old child grant cannot read the
   replacement. Repeat with a separately selected nested tree and a directory
   alias. Confirm a same-name rename preserves access.

Actual execution results are recorded in the combined pull request; this manual
checklist is not a claim that those device checks have been run.
