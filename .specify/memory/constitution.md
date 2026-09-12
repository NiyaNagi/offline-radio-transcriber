<!--
SYNC IMPACT REPORT
==================
Version change: 1.1.0 → 1.2.0

Bump rationale (1.2.0, MINOR): materially expanded guidance in Principle VIII (a change that
  touches a screen re-runs the visual verification, triggered by the diff, and what that
  verification consists of) and in Development Workflow (the debugging loop; "green" means green
  on CI and on the Release workflow; tests never read the real bundled model). Distilled from the
  2026-09-10/12 capture-modes program, whose defects were found by re-capturing screens after
  fixes that nobody had classified as visual (a nav-host padding change moved a live bar 125 px;
  three font-scale fixes passed at 360 dp and collapsed on the 390 dp device) and whose first
  three hosted runs were red for reasons no local gate could show (a task never scheduled for the
  unit tests; a 555 MB asset inflated into a test worker). No principle removed or redefined.

Documents updated in the same change:
  ✅ AGENTS.md — status and map brought current; working agreement gains the visual
       re-verification trigger and the debugging loop; commands gain the tour.
  ✅ docs/debug-fix-session-prompt.md — new; the way to start a debugging and fix session, which
       runs the re-verification automatically when a diff touches a screen.
  ✅ docs/ui-fix-session-prompt.md — marked superseded as an entry point; kept as the technique
       reference.
  ✅ spec/test-plan.md §7.5 — already carries the mechanics (390 dp, state-based settle); no change.

-- prior --
Version change: 1.0.1 → 1.1.0

Bump rationale (1.1.0, MINOR): a new principle (VIII, Visual Conformance Is Evidence-Backed) and
  materially expanded guidance in Principle II (test integrity) and in Development Workflow
  (the gate is cross-platform). All three are distilled from the 2026-09-07/10 UI conformance
  programme, which audited 103 screens against 95 artboards, catalogued 338 findings, and — in
  the course of closing them — produced several defects that a passing test suite actively hid:
  two tests asserting on a driver's error-message wording (green on Windows, red on Linux), two
  asserting the absence of a loose row while never checking what the grouped row contained, one
  scrolling with `performScrollTo()` that stopped the instant its target touched the viewport,
  and a storage bar whose empty-state guard compared a locale-formatted string. None of these
  were caught by review; all were caught by comparing a screenshot to its artboard, or by making
  a test fail on purpose.

Principles added:
  VIII.  Visual Conformance Is Evidence-Backed

Documents updated in the same change:
  ✅ spec/test-plan.md — new §7.5 documents the mechanics (artboards, the screenshot tour,
       parallel capture review, the finding register, the confirmation sweep).

-- prior --
Version change: 1.0.0 → 1.0.1

Bump rationale (1.0.1, PATCH): the single Governance "Outstanding" item — the missing LICENSE —
  is resolved. The repository now carries an Apache-2.0 LICENSE (build-plan P1). No principle
  text changed; this is a clarification of status only. README's licence note updated in the
  same change.

-- prior --
Version change: (none) → 1.0.0

Bump rationale: Initial ratification. Derived from the project's existing decision record
  (D1–D32), the three unrelaxable rules in technical design §17, the risk register (R1–R16)
  and the testing approach agreed in test-plan.md. MAJOR baseline because it establishes
  binding governance where none previously existed.

Principles defined (VIII added at 1.1.0):
  I.    Uncertainty Is Content (NON-NEGOTIABLE)
  II.   Test-Backed Change (NON-NEGOTIABLE)
  III.  Audio Is The Source Of Truth
  IV.   Capture Never Blocks, Never Drops, Never Lies
  V.    Nothing Leaves The Device Except By A Declared Channel
  VI.   Measurement Discipline
  VII.  Boundaries Are Structural, Not Conventional

Added sections:
  - Scope And Precedence
  - Development Workflow & Quality Gates
  - Governance

Documents reviewed for alignment:
  ✅ spec/functional-spec.md — principles are derived from it; it stays authoritative on
       requirements. No change needed.
  ✅ spec/technical-design.md — §17's three rules are absorbed into Principles III, IV
       and VII. No change needed.
  ✅ spec/test-plan.md — Principle II codifies its approach; §7's manual device gates
       needed governance, added under Principle II.
  ⚠️ spec/build-plan.md — every prompt updated to carry a Constitution Check; P1 gained
       the LICENSE and minimum-API obligations; P6 gained third-party fold reporting;
       P11 gained the attribution-display obligation.

Follow-up TODOs:
  - LICENSE file is still absent while the repository is public (Principle VII, Governance).
-->

# Offline Radio Transcriber Constitution

This project builds an Android application that transcribes amateur and scanner radio traffic
entirely offline and resolves callsigns by matching a lexicon against the audio itself. It is
built by one person with heavy AI assistance (D18), against an unusually complete specification.

These principles are derived from decisions already made and recorded. They are **binding on
every change**, including changes made by an AI agent, and they exist because this product's
characteristic failures are **silent**: a confident wrong callsign, a session that died at 2 a.m.,
a number measured on the wrong fold. Nearly every rule below is a guard against something that
would otherwise look like success.

## Scope And Precedence

- The **functional specification is authoritative on requirements**; this constitution is
  authoritative on *how work is done*. Where an implementation conflicts with a principle, the
  implementation changes — not the principle.
- Where a principle appears to conflict with a requirement, that is a specification defect:
  raise it, amend the spec, and record the decision. Do not resolve it silently in code.
- **Principles apply to the shipped application.** Desktop tooling under `corpus/` is governed
  by Principles II and VI only; it may use the network freely, and does.

## Core Principles

### I. Uncertainty Is Content (NON-NEGOTIABLE)

A log that silently guesses is worse than one that admits it does not know, because it will be
used to send QSL cards to people who were never there.

- **The four attribution states are a closed set** — `CONFIRMED`, `INFERRED`, `AMBIGUOUS`,
  `UNKNOWN` — and every attribution MUST carry one. The API that returns an attribution MUST
  make the state non-optional (FR-SPK-10); this is a type-level obligation, not a UI convention.
- **Precision outranks recall at every tier** (NFR-1a). Where a target cannot be met, the system
  sacrifices recall — moving results to `AMBIGUOUS` or `UNKNOWN` — and never asserts.
- **A weaker device may know less; it MUST NOT be more wrong** (NFR-1b).
- **`CONFIRMED` means heard and resolved in *this* transmission.** No voice match, however
  strong, and no cross-session inference may produce it (FR-SPK-13).
- **Every machine conclusion MUST be inspectable** — the lattice, the candidates, each prior's
  contribution (FR-UI-8, P2). A conclusion that cannot be explained cannot be corrected.
- **The system MUST NOT generate claims about a person** beyond what was transmitted
  (FR-DIG-12). It logs what was said; it does not profile who said it.

**Rationale:** G4 — trust the record — is the requirement that makes the product worth having.
Every other accuracy gain is worthless if the user cannot tell what was heard from what was
guessed.

### II. Test-Backed Change (NON-NEGOTIABLE)

- **Strict TDD.** The test is written, run, and seen to fail *for the right reason* before the
  code that satisfies it. A test that passes before its implementation exists is testing nothing.
- **Tests are named for the requirement they establish** (`AC_47_...`, `FR_RUN_10a_...`) so the
  coverage matrix is generated rather than maintained.
- **Failure paths are tested, not just happy paths.** §12 of the functional spec enumerates
  twenty-two failure modes precisely because most of them are silent.
- **Every model-bearing interface ships with a behavioural fake**, in the same change. A fake
  that cannot be told to fail, hang or return a hallucination is a stub, and stubs test nothing
  that matters.
- **A module is not done until its definition of done holds** (test-plan §5), including that it
  builds and tests against `:core` and fakes alone.
- **Manual gates carry equivalent discipline.** Device tests (test-plan §7) cannot be
  test-first, so each MUST have a written protocol, a recorded prediction where one applies, and
  a result committed under `results/` with the device, build and date. An unrecorded manual test
  did not happen.
- **A session that cannot meet its exit criteria stops and says so.** Under AI-assisted
  throughput a skipped test is an unmet requirement wearing a disguise.
- **A test MUST be shown to discriminate.** Before a fix is reported done, revert the production
  change and watch the new test fail; restore it and watch it pass. A test that passes both ways
  is a false "this is covered" signal, which is worse than no test because it stops anyone
  looking again. This is not a formality: four such tests were found in one sweep.
- **Assertions MUST NOT depend on prose, locale or a library's message text.** Assert the state
  that survives — the rows in the table, the node that carries the action, the measured bounds —
  never the wording of an exception, a formatted number, or user-facing copy that a designer may
  legitimately change tomorrow. Message text differs by platform and by locale; two `:data` tests
  passed on Windows and failed on Linux for exactly this reason, and the same pattern in
  production would have crashed the storage screen on any comma-decimal locale.
- **Prefer a capability probe to exception forensics.** Deciding what a system can do by parsing
  the failure it produced when it could not is guesswork; ask it directly and cache the answer.
- **When a test and a screenshot disagree, the screenshot wins** until the discrepancy is
  explained. Robolectric's merged semantics tree, its text measurement and its idle detection all
  differ from a device; a test asserting a node exists says nothing about whether a user can see
  or reach it.

**Rationale:** D18 makes implementation cheap and review expensive. Tests are the only artifact
that makes "it works" checkable rather than asserted — which is precisely why a test whose
premise is wrong is more dangerous here than in a project with slower throughput.

### III. Audio Is The Source Of Truth

- **Every pass is a pure function of `(audio, lexicon snapshot, model set, config)`** and
  records the fingerprint of what produced it (technical design §3.4). No pass may consume
  another pass's output where the audio is available.
- **Retained audio MUST remain sufficient to re-run every pass** (FR-REP-4). The retention codec
  is therefore an accuracy decision: lossy retention requires a measured justification against
  Pass C, not against file size (FR-STO-2b, R10).
- **Segmentation is the one exception, and it is permanent.** Boundaries cannot be redone from
  gated audio, so segmentation parameters MUST NOT vary by tier (FR-SEG-7), pre- and post-roll
  are generous by policy (FR-SEG-8), and the segmenter MUST NOT accept a tier argument at all.
- **Nothing is deleted quietly.** Rejected segments, superseded transcripts and overwritten
  attributions remain reachable (P9); retention deletion is announced in advance.

**Rationale:** This is what makes reprocessing, cross-tier improvement and the entire tier
inversion true rather than aspirational. AC-39 is only satisfiable because of it.

### IV. Capture Never Blocks, Never Drops, Never Lies

- **Capture MUST proceed with every processing pass stalled**, indefinitely (FR-RUN-1). Enforced
  structurally: `:capture-*` has no compile-time dependency on `:asr-*`, `:lexicon` or
  `:identity`.
- **Audio is never dropped for processing pressure** (D16). Overload sheds passes in a documented
  order and defers to a durable queue; only storage exhaustion stops capture, loudly.
- **A route that is not the selected device halts capture** (FR-CAP-3, FR-CAP-3a). Recording the
  room instead of the radio is the highest-consequence silent failure in the system.
- **Silence that was never listened to MUST be distinguishable from silence that was**
  (FR-RUN-12, FR-UI-12). A gap is data.
- **Liveness is established empirically, never by API.** `isIgnoringBatteryOptimizations()` is a
  hint that lies on the reference device (NFR-8).

**Rationale:** R5 is the top risk and the product is worthless if a night's capture silently
did not happen.

### V. Nothing Leaves The Device Except By A Declared Channel

- **The capture and processing paths make no network call, ever** (NFR-6). Enforced by module
  boundary and by capability token, not by policy.
- **There are exactly two outbound channels**: user-initiated actions (asset download, export,
  QRZ) and the corpus contribution channel, which is off until enabled and never runs during
  capture (FR-CON-1, FR-CON-2).
- **No analytics, telemetry or crash reporting**, in any build (FR-OBS-5).
- **Four categories never leave the device at all**: voiceprints and embeddings (FR-SPK-20),
  user-supplied names (FR-SPK-25), station knowledge (FR-DIG-13), and location finer than a grid
  square (FR-LEX-24). The contribution payload is **recomputed from a closed field list**, never
  serialised from an entity graph, so a new column cannot leak by being added.
- **Contributed audio is never published** (D31). Derived artifacts may be.

**Rationale:** The product records identifiable third parties who did not consent. The
device-local guarantee is what makes that defensible, and it is why FR-SPK-20 is load-bearing
rather than precautionary.

### VI. Measurement Discipline

- **Three folds: `train`, `dev`, `eval`.** The eval fold is sealed until M11 and the harness
  refuses it without an explicit flag (FR-TST-7). Sealing by discipline alone does not survive a
  debugging session at 2 a.m.
- **No number without its provenance.** Every reported figure carries fold, machine, execution
  provider, thread count, model version and run fingerprint. A number whose fold is unstated is
  not evidence.
- **Determinism is bounded and the bound is stated.** Byte-identical output holds within a fixed
  (machine, provider, thread count, runtime version) and not across them.
- **Synthetic data never enters the eval fold** (FR-TST-9), and metrics are reported **per
  source** as well as aggregate (FR-TST-8) — a system that scores well on generated audio and
  badly on real traffic is the expected failure mode of the corpus strategy (R13).
- **Third-party corpora carry their own splits.** Report them as such; do not silently merge
  them into this project's fold structure.
- **Targets are provisional until measured.** Every accuracy figure in §10 is transferred from
  another domain until the harness produces it here.

**Rationale:** R8 — a contaminated eval set — makes every number in the project unfalsifiable,
and it is not recoverable. You cannot un-see the eval fold.

### VII. Boundaries Are Structural, Not Conventional

- **Module dependency rules are enforced by the build**, not by review. A forbidden edge fails
  `./gradlew dependencyRules`.
- **Guarantees are expressed as types where possible**: a non-optional attribution state, a
  segmenter that cannot accept a tier, a network entry point that requires a capability token.
  A rule a person must remember is a rule that will eventually be forgotten.
- **`:core`, `:lexicon`, `:eval` and the `-api` modules have no Android dependency**, so the
  highest-uncertainty work runs on a desktop JVM with fast tests.
- **Assets — models, lexicon data, calibration, descriptors — share one lifecycle**: install,
  verify, activate, roll back, remove, with integrity checked before activation.
- **Every user-visible surface obeys the accessibility floor**: the four states distinguishable
  without colour, WCAG 2.2 AA contrast, no clipping at maximum font scale.

**Rationale:** Under D18, implementation is fast and vigilance is scarce. Structural enforcement
is the only kind that survives a long project built by one person and an agent.

### VIII. Visual Conformance Is Evidence-Backed

The interface is specified by drawing. `design/design-intent.md` inventories every screen and
state; `design/canvas/*.dc.html` is the artboard for each; `design/design-guide.md` holds the
tokens. A screen is not "done" because it renders — it is done when a capture of the built screen
has been compared against its own artboard and the difference is either absent or recorded.

- **Every screen in the inventory MUST have an artboard, and every artboard MUST be reachable by
  a capture.** A screen no capture can reach is not verified, however many tests it has. Where a
  state is genuinely interaction-only, record that in the inventory with the reason, so it is a
  known exclusion rather than an oversight.
- **Findings live in one register** (`results/ui-audit/register.md`), one row each, with an owner
  and a severity. A row moves to `fixed` on a builder's report but reaches **`closed` only on
  evidence**: a capture of the built screen, or a device dump for anything about accessibility or
  touch targets. "The builder says so" is not evidence.
- **Rows sitting at `fixed` MUST be swept periodically** and each one confirmed or reopened
  against the current captures. Unverified-but-assumed is the state in which real defects hide;
  one sweep of 124 such rows found four screens still broken and twelve never captured at all.
- **Judge a screen at the operator's text size as well as the default**, and judge the first
  frame together with its scrolled-to-end companion. Content below the fold is not a defect if
  scrolling reaches it; content unreachable after scrolling is.
- **Accessibility is judged on a device, never from the test tree.** The merged semantics tree
  routinely shows a labelled control where a real screen reader finds an unlabelled one.
- **Artboards assume a fixed logical width** (390 dp here). Layout that is correct in `dp` can
  still be wrong on a physically larger screen; verify against the geometry the operator's own
  device reports, not the emulator's defaults.
- **The design inventory records accepted deviations in the row itself**, with the register row
  that decided it. A deviation that is not written down will be re-raised forever.
- **A change that touches a screen re-runs the visual verification, and the diff is the
  trigger.** Whether a change is "visual" is not a judgement anyone makes: if the diff touches the
  UI surface (`app/src/main/kotlin/org/ort/app/ui/**`, `app/src/main/res/**`, `design/**`, the
  debug scenarios or tour steps that decide what a step shows, or any `*Content`, `*Screen`,
  `*ViewState`, `*ViewData` or `*Mapper` file under `:app`), the change is not done until its
  screens have been captured again by the tour — at font scale 1.0 and 2.0, and scrolled to the
  end where the screen scrolls — and compared against their artboards, with the capture path in
  the register row. A defect at 2.0, or any change to a row, column, badge or banner layout,
  also gets a layout test at the tour's own width (390 dp, native graphics) asserting bounds.
  Anything about labels, touch targets or reading order gets a device dump. A fix report that
  carries no capture for a diff in those paths is sent back. This exists because a padding
  change in the navigation host, classified by everyone as plumbing, moved the live bar 125 px
  on every screen, and three font-scale fixes passed their tests and collapsed on the device.
- **Only a full canonical tour replaces the committed capture set** under `results/ui-audit/`;
  experiments and scoped re-captures go to a scratch directory, so the committed set always
  matches the shipped build named in its manifest.

**Rationale:** This product is a reading instrument for someone deciding whether to trust a
record. A misaligned column or a clipped sentence is not cosmetic here — it is the same class of
failure as a confident wrong callsign, arriving through the eye instead of the data layer. The
technique above exists because 338 differences between the built app and its own design were
found this way, and almost none of them by reading code.

## Development Workflow & Quality Gates

- **Work proceeds by the build plan's waves.** Within a wave, units touch disjoint files; a unit
  MUST NOT modify files another unit in the same wave owns.
- **Every session begins with a Constitution Check** — state which principles bear on the work
  — and ends by confirming the module's definition of done.
- **CI gates every push**: lint, dependency rules, spec-integrity checks, unit tests, the golden
  pipeline, and the coverage-matrix delta. Device and endurance tests are deliberately excluded;
  pretending a hosted runner can prove them would be false confidence.
- **The gate is not green until it is green on CI and on the Release workflow.** A local run
  proves one operating system, one filesystem, one locale and one machine size; the CI job and the
  Release job differ again (the Release job packages every bundled asset and runs the tests in
  the same invocation). Push often enough that the hosted runs are a short feedback loop rather
  than an archaeology exercise: one 620-commit gap hid a locale-dependent crash, a
  filesystem-ordering failure and a test-isolation defect simultaneously; one 296-commit gap hid
  a task never scheduled for the unit tests and a test worker that inflated a 555 MB asset.
- **No unit test reads a real bundled model.** Robolectric inflates a compressed asset whole into
  the test worker; a scenario or tour sweep takes the fixture-sized source, and the one narrow
  test that installs a real asset does so once. A bigger heap is not the fix.
- **Debugging follows one loop, whatever the symptom**: reproduce in the environment that fails;
  classify (product, design, test, tooling, environment, not a defect); file the row with its
  evidence; route to the owning package; require the discriminating test; gate; merge; push;
  watch the hosted runs; close on evidence — a fresh capture, a device dump, a green run id or a
  re-run reproduction — never on a report. `docs/debug-fix-session-prompt.md` is the session
  that runs this loop, with the visual re-verification above built in.
- **Generated files MUST be byte-identical wherever they are generated.** Anything derived from a
  directory walk is sorted before it is rendered, or the same facts produce a different file on
  another machine and the delta gate fails for no reason.
- **Machine-specific tuning is derived, not hardcoded.** Fork counts, heap sizes and parallelism
  come from the machine the build is running on; a number tuned on a workstation will not hold on
  a two-core runner, and discovering that at release time is expensive.
- **Diagnose before repairing, and instrument the environment that actually fails.** Two blind
  fixes for one CI failure cost more than the single instrumented run that produced the answer.
- **Spec-integrity checks are part of CI**, covering ID contiguity, dangling references,
  traceability sync and question/decision agreement.
- **Decisions are recorded where they are made**: a decision of record in functional spec §3, a
  technical decision in the technical design's open-decisions table, a measured result under
  `results/`.
- **Commit messages state what changed and why the alternative was rejected.**

## Governance

- **Authority.** These principles are binding gates. A conflict between an implementation and a
  MUST is resolved by changing the implementation, the spec, or the plan — never by diluting a
  principle.
- **Amendments** require a recorded rationale, a version bump per the policy below, and
  propagation to `AGENTS.md`, the build plan's prompts and any affected spec section **in the
  same change**, noted in the Sync Impact Report at the top of this file.
- **Versioning (SemVer for governance).** MAJOR = a principle removed or redefined
  incompatibly; MINOR = a new principle or section, or materially expanded guidance;
  PATCH = clarification.
- **Compliance.** Any deviation MUST be justified in the change that introduces it. An
  unjustified violation is a defect regardless of whether tests pass.
- **Outstanding.** None. *(Resolved 2026-09-07: the repository now carries an `Apache-2.0`
  `LICENSE`, chosen for the explicit patent grant and the Play Store path — D11, build-plan P1.)*

**Version**: 1.2.0 | **Ratified**: 2026-09-07 | **Last Amended**: 2026-09-12
