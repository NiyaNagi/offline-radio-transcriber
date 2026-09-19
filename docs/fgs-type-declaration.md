# Foreground service (FGS) type declaration checklist

Play Console requires every foreground service type an app declares to be justified — matched to a
real, permitted use case, with a manifest declaration and (as of the policies current when this was
written) a Play Console declaration form entry per type. This is the checklist for the `play`
variant's submission; it also documents what `buildSrc`'s `platformGuards` task now checks
structurally (`PlatformGuards.fgsTypeDeclarationViolations`, P23).

## What this product declares today

| Permission (declared in the module's own manifest) | `foregroundServiceType` | Declaring `<service>` | Why |
|---|---|---|---|
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` (`capture-android/src/main/AndroidManifest.xml`) | `microphone` | `.service.CaptureService` (`:capture-android`) | 8-hour, possibly unattended, background audio capture from the radio's audio interface (D1, FR-PLT-1). This is the product's core function — capture cannot be a foreground activity, since the operator may lock the screen or switch apps for the full session. |

`:pipeline` also declares a `<service>` with `android:foregroundServiceType="microphone"`
(`.capture.RealCaptureService`, `pipeline/src/main/AndroidManifest.xml`) — the v0 smoke-test capture
wiring documented in that class's own KDoc, not the product's real capture path (`CaptureService`
above is). It does not itself declare the `FOREGROUND_SERVICE_MICROPHONE` permission (it relies on
`:capture-android`'s), so it is out of scope for the per-module `platformGuards` check and for this
checklist's own manifest row — noted here so a reviewer does not mistake it for a second,
undocumented real service.

Nothing today requests `dataSync`; `ReprocessWorker` (`:pipeline` — reprocessing a previously-
captured transmission on demand) deliberately runs as an ordinary `WorkManager` job, not a
foreground service (see that class's own doc comment). `FGS_PERMISSION_TO_TYPE` in
`buildSrc/src/main/kotlin/org/ort/gradle/PlatformGuards.kt` recognizes `dataSync` too, so the guard
does not need editing again if that ever changes.

## Justification text for the Play Console form (per type declared)

**Microphone:**

> This app transcribes amateur and scanner radio traffic. Capture sessions run for up to eight
> hours and are frequently unattended — the operator starts a session, then locks their screen or
> uses other apps while radio traffic continues to be recorded and transcribed in the background.
> A foreground service with the `microphone` type keeps this session alive and visibly running (a
> persistent notification, per FR-PLT-1) for as long as the operator has explicitly started it.
> Ending the notification ends the session; there is no way to keep capturing without it.

## Checklist before a `play` variant submission

- [ ] Every `<service>` with a `FOREGROUND_SERVICE_<TYPE>` permission in the merged manifest has a
      matching `android:foregroundServiceType` — enforced by `./gradlew platformGuards`
      (`PlatformGuards.fgsTypeDeclarationViolations`).
- [ ] Every declared type has a justification entry in this document.
- [ ] The justification text above (or its current equivalent) is pasted into the Play Console
      "Foreground service permissions" declaration for each type, matching whatever categories the
      live form offers at submission time (Google revises these; re-check the wording against the
      current form rather than assuming this file's own phrasing still matches it verbatim).
- [ ] `docs/play-data-safety.md` is current (a new FGS type is not itself a data-safety fact, but a
      type change often accompanies a new data flow that is).
- [ ] `CaptureServiceTest` (`:capture-android`) and the pipeline's own equivalent still pass —
      they check the real manifest attribute value, not just that *a* type is declared.

## What the buildSrc guard does and does not prove

`PlatformGuards.fgsTypeDeclarationViolations` is a declared-artifact check, like every other check
in that file (`PlatformGuards`'s own class KDoc): it proves the manifest's permission and its
`foregroundServiceType` attribute agree by name. It does not prove the service is ever actually
started with that type at runtime, that the type is still an accurate description of what the
service does, or that Play's own current policy still accepts it — those are what this checklist's
manual steps, and the two `*ServiceTest` classes above, are for.
