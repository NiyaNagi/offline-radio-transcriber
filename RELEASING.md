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

## Signing — every published build shares one certificate

Debug-fix session (2026-09-19): an operator reported the currently released build failing to
install with "App not installed. Package appears to be invalid." The published artifact itself
turned out to be fine — hash-verified against the release asset, and confirmed to install cleanly
via `adb install` and `pm install` on three real Android package-manager instances, including
genuine Android 16 (API 36) at 16 KB page size. The real defect: this workflow used to sign the
published `full`-flavor debug APK with AGP's own auto-generated `~/.android/debug.keystore`, which
is freshly created on **every** GitHub Actions run because the runner is a new VM each time — so
`v0.1.1` and successive `latest-build` releases each carried a *different* signing certificate for
the same `org.ort.app` package (confirmed directly with `apksigner verify --print-certs` against
two downloaded releases). Any operator updating from one build to another without first
uninstalling hits `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (reproduced directly), which most on-device
Package Installers do not give a distinct message — it shows as the same generic "Package appears
to be invalid" text a genuinely malformed APK produces.

**A first version of this fix checked a signing keystore into the repository. That was wrong and
was reworked before merging**: this repository is public, and a published private key would let
anyone sign an APK Android accepts as an update to the real one — the opposite of what this fix is
for. The key now lives **only** as GitHub Actions secrets, decoded to a temp file at build time and
deleted afterward; nothing about it is ever committed.

### One-time setup — do this once, in order

1. **Generate a dedicated release keystore.** Do this somewhere private, never inside this
   checkout:
   ```bash
   keytool -genkeypair -v \
     -keystore ort-release.keystore -storetype PKCS12 \
     -alias ort-release -keyalg RSA -keysize 2048 -validity 10950 \
     -dname "CN=Offline Radio Transcriber, O=Offline Radio Transcriber, C=US"
   ```
   `keytool` prompts for a keystore password and a key password twice each — pick strong, distinct
   values and keep them (a password manager, not a note in this repo). `-validity 10950` is 30
   years, matching AGP's own debug-keystore convention, so this is not a recurring chore.

2. **Set the four secrets** (`gh secret set` reads the value from stdin with `--body -`, so nothing
   touches shell history):
   ```bash
   base64 -w0 ort-release.keystore | gh secret set ORT_RELEASE_KEYSTORE_BASE64 --repo <owner>/<repo>
   gh secret set ORT_RELEASE_KEYSTORE_PASSWORD --repo <owner>/<repo>   # paste the keystore password
   gh secret set ORT_RELEASE_KEY_ALIAS         --repo <owner>/<repo>   # "ort-release", if you used the command above
   gh secret set ORT_RELEASE_KEY_PASSWORD      --repo <owner>/<repo>   # paste the key password
   ```
   (`base64 -w0` avoids line wraps; on macOS use `base64 -i ort-release.keystore | gh secret set ...`
   instead, since macOS's `base64` has no `-w`.) `gh secret set NAME` with no `--body`/pipe opens an
   editor or reads stdin — piping or typing the value directly is what keeps it out of your shell's
   history either way.

3. **Read the certificate's digest and pin it**, so the structural guard (below) can start
   enforcing it:
   ```bash
   keytool -exportcert -keystore ort-release.keystore -alias ort-release -rfc | \
     openssl x509 -noout -fingerprint -sha256
   # or, once you have a build signed with it:
   apksigner verify --print-certs app-full-debug.apk   # "Signer #1 certificate SHA-256 digest"
   ```
   Take that digest, strip the colons, lowercase it, and paste it in as the only non-comment line
   of [`buildSrc/signing/release-certificate.sha256`](buildSrc/signing/release-certificate.sha256),
   replacing the placeholder `UNSET`. Commit that one-line change normally — it is a public digest,
   not a secret.

4. **Delete the local keystore file and its password notes from wherever you generated them**
   once the secret is set and confirmed (`gh secret list --repo <owner>/<repo>` shows the name,
   never the value) — the secret store is now the only copy `release.yml` needs.

### What happens without the secrets

`build-and-release` checks for `ORT_RELEASE_KEYSTORE_BASE64` before it signs or publishes
anything. If it is absent (a fork before its owner sets it up, or upstream before step 2 above),
the job **fails loudly** — a `::error::` annotation plus a job-summary explanation — rather than
publishing an APK signed with a fresh, throwaway key, which would silently reintroduce the exact
bug this section exists to close. A red Release run in that state means exactly what it looks
like: nothing new was published, on purpose, until the secrets exist.

### How it works once configured

`release.yml` decodes `ORT_RELEASE_KEYSTORE_BASE64` to `$RUNNER_TEMP/ort-release.keystore` for the
one job that needs it, passes it and the three password/alias secrets to Gradle as environment
variables (`ORT_RELEASE_SIGNING_STORE_FILE`/`_STORE_PASSWORD`/`_KEY_ALIAS`/`_KEY_PASSWORD`) for the
step that runs `:app:assembleFullDebug`, and removes the temp file in an `if: always()` step
afterward. [`ort.android-app.gradle.kts`](buildSrc/src/main/kotlin/ort.android-app.gradle.kts)
only creates and applies that `signingConfig` when those variables are present — **a plain local
`assembleFullDebug` is unaffected and keeps using AGP's own per-machine debug key**, exactly as
before this session; only `release.yml`'s own build can produce the artifact this section pins.

`:app:verifyReleaseSigningStability` (wired into `:app:check`) always checks that the packaged
`full`-flavor debug APK is verifiably signed with at least a v2 scheme. It additionally checks the
certificate against the digest in `buildSrc/signing/release-certificate.sha256` (or the
`ortReleaseCertificateSha256` Gradle property, which overrides the file) **only** when invoked with
`-PortEnforcePinnedReleaseSigning=true` — `release.yml` passes that flag right after building with
the injected secrets, so a plain local build is never held to a pin it was never signed against. If
enforcement is requested while the digest file still reads `UNSET`, the guard fails and says so —
it never silently skips the check it was asked to run.

### The one-time cost this cannot avoid

**Anyone who already installed a build signed with an old, ephemeral debug certificate must
uninstall `org.ort.app` once before a build signed with the new, pinned key will install over
it** — a certificate change is exactly what breaks in-place updates, and there is no way around
that for the build that makes the switch. Every release after that first one, from every operator
who has done this once, updates in place normally.

## Keep `RELEASES.md` current as you go

Add to `## Unreleased` **when a change lands**, not when a release is cut. A release is then
just moving what is already sitting under `## Unreleased` into a new dated section — never a
moment to sit down and reconstruct three days of `CHANGELOG.md` from memory. If you land a
user-visible change and its `CHANGELOG.md` entry doesn't also get a line under
`## Unreleased` here (in its plain-language form — see `RELEASES.md`'s header for the register:
no requirement ids, no commit hashes, no internal package names), the next release will be
missing it.

## Before a *public* release (D49)

The field-report channel's destination (D37/D38) is `github.com/NiyaNagi/offline-radio-transcriber`
today — a **public** repository, accepted on the record for testing while the operator was the
only recipient. **D49 requires the destination to move to a private repository before this
project's first public release**, with the destination itself build-configurable and FR-OBS-10's
visibility guard staying in force regardless; an uploaded bundle's retention is 90 days. This is
not automated anywhere (no CI check can know what "public release" means for this project) —
confirm the destination has actually moved before cutting the release that first reaches anyone
outside the operator, and record that confirmation in this section's own history once it happens.

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
   :capture-android:testDebugUnitTest :pipeline:testDebugUnitTest :app:testFullDebugUnitTest
   :app:assembleFullDebug dependencyRules`), runs `python tools/release_notes.py vX.Y.Z` to build
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
