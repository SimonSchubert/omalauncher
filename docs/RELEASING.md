# Releasing

The Play listing lives in `fastlane/metadata/android/en-US` (title, short and
full description, `changelogs/<versionCode>.txt`, images). Publishing runs
locally; only the maintainer can upload. Release builds are signed with the upload key described in
`keystore.properties` at the repo root (gitignored), otherwise with the debug
key, which Play rejects:

```properties
storeFile=../omalauncher-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

`storeFile` is relative to the repo root; keep the keystore outside it.
fastlane needs the Play service-account key at `fastlane/play-store-key.json`
(gitignored).

```sh
./scripts/fetch-proot.sh                  # jniLibs aren't in git; a release build fails without them
bundle exec fastlane android metadata     # listing text and images only
bundle exec fastlane android internal     # build + upload to internal testing
bundle exec fastlane android promote      # internal -> production
bundle exec fastlane android deploy       # build + upload straight to production
git tag v<versionName> && git push origin v<versionName>
```

The tag starts `.github/workflows/release.yml`: it runs the tests, builds the
signed APK and attaches it to a GitHub release with `SHA256SUMS` and the R8
`mapping.txt`, using `changelogs/<versionCode>.txt` as the notes (it fails
without one). The mapping matches the Play build's, so it retraces Play crashes.

Bump `versionCode` for every upload and add its changelog. Add the release's
most important changes to `CHANGELOG.md` as they land under "Unreleased", and
retitle that section with the version when releasing.

## Store assets

- `art/`: the SVG masters. `scripts/make-icons.sh` renders the app and agent
  icons from them, including the Play Store icon; the feature graphic is
  exported by hand to `fastlane/metadata/android/en-US/images/featureGraphic.png`.
- `store/screenshots/`: `frame.html` adds the captions and frames to the raw
  captures in `raw/`; `scripts/render-screenshots.sh` renders the Play
  screenshots from it (needs Google Chrome).
- `store/omalauncher.txt`: the source of the privacy policy that the
  listing links to. Upload the new version before releasing a build that
  changes what the app accesses.
- `store/foreground-service-demo.mp4`: the recording for Play's
  foreground-service declaration.

## New permissions

A permission the last Play release didn't have sends the next one through a
fresh review, so ship the privacy policy that explains it first.
`ACCESS_HIDDEN_PROFILES` (the private space, Android 15+) is a normal
permission: Play has no declaration form for it, but Android asks a launcher
holding it to keep the private space's apps in a container of their own, let
the user lock and unlock it and hide it while locked, and show none of its apps
while it's locked, search included. The drawer's private tab does all of it,
and private apps stay off home and out of app usage and `android.apps`; keep it
that way when changing the drawer or what widgets see. To check before an
upload: set up a private space on an Android 15+ phone or emulator, make
Omalauncher the home app, and lock, unlock, hide and search it.

Widget store previews are made with `scripts/store-previews.sh`; see its header.
