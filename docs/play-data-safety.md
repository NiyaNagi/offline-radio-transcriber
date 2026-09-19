# Play Data Safety answer sheet

Answers for the Play Console "Data safety" form (Play Policy Center, App content → Data safety),
for the `play` variant (FR-AST-13). Written from functional spec §7.13d (FR-ANL-1..14), §7.13
(FR-OBS, FR-CON) and constitution V — if an answer here and the spec disagree, the spec is wrong
and must be amended first (spec is source of truth, AGENTS.md).

**Does your app collect or share any of the required user data types?** Yes, but only what an
operator explicitly turns on — every category below defaults off except crash/diagnostics and
app-performance data (tier 1, FR-ANL-1).

## Data collection and sharing

| Data type | Collected? | Shared with third parties? | Purpose | Ephemeral? | Notes |
|---|---|---|---|---|---|
| Audio recordings | Only if the operator opts in (contribution, field report, or analytics tier 3) | No | App functionality; account/analytics improvement, by the operator's explicit choice | No | Never collected during capture; never collected without an explicit opt-in per FR-ANL-4/FR-CON-1/FR-OBS-5a |
| Voice or sound recordings used for identity | Only via the field-report channel, per-category, off by default | No | Diagnostics, by explicit operator choice | No | FR-SPK-20, FR-OBS-9/10, D38 — the one narrow exception to "voiceprints never leave the device" |
| App activity — app interactions | Yes (tier 1, on by default, can be disabled) | No | Analytics | No | Which screens/actions were used, not their content (FR-ANL-2) |
| App activity — in-app search history | No | — | — | — | Search is entirely on-device (FTS5, `:data`) |
| App info and performance — crash logs | Yes (tier 1, on by default, can be disabled) | No | Analytics, app functionality | No | FR-ANL-2 |
| App info and performance — diagnostics | Yes (tier 1, on by default, can be disabled) | No | Analytics | No | Per-pass latency, real-time factor, capture uptime/heartbeat gaps, setup funnel outcomes (FR-ANL-2) |
| Messages — other in-app messages (transcript text) | Only if the operator opts in (tier 2) | No | Analytics; account/product improvement, by explicit operator choice | No | FR-ANL-3 |
| Personal identifiers — user IDs | Yes (a resettable, random install id, always attached to any analytics event) | No | Analytics provenance (constitution VI) | No | FR-ANL-8; operator can reset it at any time, purging prior rows (FR-ANL-11) |
| Location | No | — | — | — | Never collected in any tier or channel finer than what the operator manually attaches to a station note, which itself never leaves the device (FR-ANL-5) |
| Personal info — name | No, never leaves the device | — | — | — | FR-ANL-5, FR-SPK-25 |
| Financial info | No | — | — | — | Not applicable — this product has no purchases or billing (D47) |
| Contacts | No | — | — | — | Not requested, not read |
| Files and docs | No | — | — | — | Corrected/contributed transcript text is reported under "Messages" above, not as a generic file upload |

## Security practices

- **Data is encrypted in transit.** Any upload uses HTTPS (the one HTTP client in this codebase,
  confined to `:net` and enforced by a structural build guard, `platformGuards`).
- **The operator can request data deletion.** Resetting the install id purges every analytics row
  previously associated with it at the destination (FR-ANL-11). A field-report bundle is retained
  for a fixed 90 days regardless (D49) and can be deleted sooner by request to the destination's
  operator.
- **Data is not sold.** No data collected by this product is sold to any third party. Analytics, when
  enabled, is sent only to a destination the app's own operator configures and controls (D48,
  self-hosted) — never a third-party analytics vendor's SDK. No telemetry or crash-reporting SDK is
  linked into this product at all; this is checked on every build (`PlatformGuards.telemetryViolations`).
- **Collection is not required.** Every one of the three analytics tiers, contribution and field
  reports can be declined; every core function (capture, transcription, callsign resolution, the
  log, the digest) works fully with all of them off (FR-ANL-10, FR-CON-1).

## Notes for whoever fills in the Play Console form

- This sheet answers the form's own categories; it does not replace reading the current form, which
  Google revises. Re-check this sheet's category names against the live form before submitting.
- The `full` variant (GitHub/sideload only) is not distributed through Play and does not need this
  form — it exists for the `play` variant (FR-AST-13).
- If a future FR-ANL amendment changes what a tier collects, this sheet MUST be updated in the same
  change (AGENTS.md working agreement — spec first, then everything derived from it).
