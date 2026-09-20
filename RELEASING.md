# Releasing

The repeatable procedure for cutting a release of this app. Short on purpose — if a step here
needs its own explanation, that explanation belongs in a comment in the workflow file it
describes, not grown here.

## Version policy

Quoting the project owner directly: **stay on `0.1.x` and do not move to `0.2` until basic
transcribing works with actual models built in.** Concretely: bump the patch digit
(`0.1.1` → `0.1.2` → …) for every release until a build can actually transcribe real audio with
a real ASR/VAD model bundled — that is the only thing that earns `0.2.0`. Do not bump the minor
or major version for any other reason (UI work, audits, tooling), no matter how large.

## The two release shapes

| | Rolling `latest-build` | Versioned `vX.Y.Z` |
|---|---|---|
| **Trigger** | every push to `main` | pushing a `vX.Y.Z` tag |
| **Notes come from** | `RELEASES.md`'s `## Unreleased` section | `RELEASES.md`'s `## vX.Y.Z — date` section |
| **Purpose** | "download the current build", always one stable link | an actual numbered milestone |
| **GitHub release** | always the same one, replaced each time (`--prerelease`) | a new, permanent release |

Both are built and published by [`.github/workflows/release.yml`](.github/workflows/release.yml),
both attach the same debug APK CI already builds and verifies, and both get their body text from
[`tools/release_notes.py`](tools/release_notes.py) reading [`RELEASES.md`](RELEASES.md) — never
`CHANGELOG.md` directly; see `RELEASES.md`'s own header for why the two files are split.

## Signing — every build shares one certificate

Debug-fix session (2026-09-19): an operator reported the currently released build failing to
install with "App not installed. Package appears to be invalid." The published artifact itself
turned out to be fine — hash-verified against the release asset, and confirmed to install cleanly
via `adb install` and `pm install` on three real Android package-manager instances, including
genuine Android 16 (API 36) at 16 KB page size. The real defect: this workflow used to sign
`assembleDebug`'s output with AGP's own auto-generated `~/.android/debug.keystore`, which is
freshly created on **every** GitHub Actions run because the runner is a new VM each time — so
`v0.1.1` and successive `latest-build` releases each carried a *different* signing certificate for
the same `org.ort.app` package (confirmed directly with `apksigner verify --print-certs` against
two downloaded releases). Any operator updating from one build to another without first
uninstalling hits `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (reproduced directly), which most on-device
Package Installers do not give a distinct message — it shows as the same generic "Package appears
to be invalid" text a genuinely malformed APK produces.

The fix: `debug` now signs with a stable, checked-in keystore,
[`buildSrc/signing/ort-rolling-release.keystore`](buildSrc/signing/ort-rolling-release.keystore),
wired in [`ort.android-app.gradle.kts`](buildSrc/src/main/kotlin/ort.android-app.gradle.kts). Its
store/key password (`ort-rolling-release`) and alias (`ort-rolling-release`) are **not secret** —
the property this key needs is *stability across CI runs*, not concealment, exactly like AGP's own
public debug-keystore password (`android`). Every rolling and tagged release from now on carries
the certificate fingerprint pinned at
`PlatformGuards.PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256` (`buildSrc/src/main/kotlin/org/ort/
gradle/PlatformGuards.kt`), and `:app:verifyReleaseSigningStability` (wired into `:app:check`,
`ort.android-app.gradle.kts`) fails the build if the assembled APK's real signer certificate ever
drifts from that pin — via `com.android.tools.build:apksig`, the same library `apksigner` itself is
built from, so this needs no external SDK tool at guard-check time.

**Anyone who already installed a build signed with an old, ephemeral debug certificate must
uninstall `org.ort.app` once before installing a build carrying the new pinned certificate** — that
one-time uninstall is unavoidable (a signature change is exactly what breaks in-place updates);
every subsequent rolling/tagged release from this point on will update over it in place.

## Keep `RELEASES.md` current as you go

Add to `## Unreleased` **when a change lands**, not when a release is cut. A release is then
just moving what is already sitting under `## Unreleased` into a new dated section — never a
moment to sit down and reconstruct three days of `CHANGELOG.md` from memory. If you land a
user-visible change and its `CHANGELOG.md` entry doesn't also get a line under
`## Unreleased` here (in its plain-language form — see `RELEASES.md`'s header for the register:
no requirement ids, no commit hashes, no internal package names), the next release will be
missing it.

## Cutting a release

1. **Confirm `## Unreleased` reads the way you want the release notes to read.** Tidy the
   wording now if it doesn't — this is the last easy chance, before the section gets a
   permanent heading.
2. **Rename `## Unreleased` to `## vX.Y.Z — YYYY-MM-DD`** (today's date), matching the version
   policy above, and add a fresh, empty `## Unreleased` section above it for whatever lands
   next.
3. **Bump the version** in
   [`buildSrc/src/main/kotlin/ort.android-app.gradle.kts`](buildSrc/src/main/kotlin/ort.android-app.gradle.kts):
   `versionName = "X.Y.Z"` and increment `versionCode` by exactly 1 (it is a plain monotonic
   counter — Google Play and most installers key on it, not on `versionName`).
4. **Verify the notes render** before committing anything:
   `python tools/release_notes.py vX.Y.Z` and read the output — this is exactly what the
   GitHub release body will say.
5. **Commit** the three changes above together (`RELEASES.md`, the version bump, and the
   `CHANGELOG.md` entry this commit needs like any other) with a message such as
   `release: cut vX.Y.Z`.
6. **Tag and push the tag**: `git tag vX.Y.Z` then `git push origin vX.Y.Z` (a plain `git push`
   of `main` does not publish a versioned release — only the tag push does; pushing `main`
   itself still republishes the rolling `latest-build` from whatever `## Unreleased` says at
   that point, which should be empty right after step 2).
7. **The workflow then**: checks out the tag, runs the same test-and-assemble gate CI runs
   (`:data:testDebugUnitTest :net:testDebugUnitTest :rig-usb:testDebugUnitTest
   :capture-android:testDebugUnitTest :pipeline:testDebugUnitTest :app:testDebugUnitTest
   :app:assembleDebug dependencyRules`), runs `python tools/release_notes.py vX.Y.Z` to build
   the notes, and calls `gh release create vX.Y.Z` with the debug APK attached and those notes
   as the body. It does not touch the rolling `latest-build` release on a tag push.

## Verifying the result

- `gh release view vX.Y.Z` — confirm it exists, the notes read as expected, and `app-debug.apk`
  is attached.
- `gh release list` — confirm `latest-build` and every past `vX.Y.Z` are all still present;
  cutting a version never deletes an older one (only `latest-build` is ever replaced in place).
- Download the attached APK once and sideload it (see `RELEASES.md`'s own `### Install` section
  for the steps) — the release isn't done until an actual installable artifact was checked, not
  just a green workflow run.
