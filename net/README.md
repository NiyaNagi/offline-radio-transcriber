# `:net`

The only module permitted an HTTP client (constitution principle V, technical design §16.1).
Model acquisition — fetch, resume, checksum-verify, side-load (build-plan P18, FR-ASR-8,
FR-AST-2, FR-AST-3).

## Why this exists

P12 wired a real ASR engine and a real Silero VAD binding into the running app, and both were
inert: no model file exists on the device, and `:pipeline`/`:capture-*` correctly refused to
fetch one — `:capture-*` may never depend on `:net` at all (`ModuleGraph`, enforced by
`dependencyRules`), and the capture/processing path must make no network call, ever (NFR-6).
This module is the declared channel technical design §16.3 requires: a `NetCapability` token
gates every entry point, and only `:app`'s UI (a later, tiny follow-up commit) can construct one
from a real user action.

## What is here

- `HttpRangeClient` — the one interface through which this module makes a network call.
  `real.RealHttpRangeClient` is the only implementation that actually opens a socket;
  `fake.FakeHttpRangeClient` is the behavioural fake every test in this module (and, later,
  `:app`'s) drives instead.
- `ModelAcquisition` — `fetch()` (network, resumable) and `sideload()` (local file, no network),
  both ending in the same checksum-verified, atomically-installed result. Mirrors
  `corpus/src/corpus/acquire.py`'s `_download`/`acquire_source` semantics: a `.part` file carries
  partial progress and resumes from its own size; a completed download is checksummed before
  being renamed into place; a `.sha256` marker makes a verified destination idempotent. One
  deliberate divergence from the Python side: on a checksum mismatch, the `.part` file is
  **deleted**, not kept — a corrupt partial cannot be fixed by appending more bytes to it, and
  FR-AST-2 requires that nothing half-written survive for a later run to mistake for progress.
- `:net`'s responsibility ends when verified bytes are on disk. Activation — signature check,
  probe-run before trust, keep-previous-on-failure — is `:asr-sherpa`'s `ModelActivation`
  (already built by P10), deliberately not duplicated here.

## Why `HttpURLConnection`, not OkHttp

The JDK's `java.net.HttpURLConnection` supports a ranged GET (`Range: bytes=<n>-`) and reading
the response code/stream, which is all a resumable download needs, with **zero new runtime
dependencies**. Nothing in this prompt's scope (progress callbacks, connection pooling,
HTTP/2, interceptors) needs OkHttp's extra surface, so it was not added — matching the standing
constraint to prefer the JDK client unless there is a concrete reason not to.

## What is genuinely verified vs. fake-verified

- `ModelAcquisitionFetchTest` and `ModelAcquisitionSideloadTest` drive real `ModelAcquisition`
  code — real file I/O, real SHA-256, real `.part`/marker-file handling — against
  `FakeHttpRangeClient`. The resumability test genuinely proves the byte offset requested on
  retry matches the partial file's own size, and the checksum-mismatch test genuinely proves
  nothing lands at the destination or survives as a resumable partial.
- `RealHttpRangeClient` itself (the actual `HttpURLConnection`/`Range`-header wiring) **is**
  exercised by `RealHttpRangeClientTest` (audit F-024), against `real/LoopbackHttpFixture` — a
  minimal `java.net.ServerSocket`-based HTTP/1.1 fixture, not `com.sun.net.httpserver.HttpServer`
  (still rejected for the JPMS reason above: `jdk.httpserver` is not on this Android-library
  module's unit test compile classpath). The fixture binds only to `127.0.0.1` on an ephemeral
  port. It covers a full fetch, an honoured `Range` request receiving only the tail, a non-2xx
  status surfacing as `HttpRangeResult.Failure` rather than a thrown exception, and a connection
  that closes mid-body. The last case caught a real bug: `HttpURLConnection` does **not** throw
  when the peer closes the socket before delivering as many bytes as its own `Content-Length`
  promised — a caller reading to EOF got a silently truncated body instead of an exception, which
  would have made a dropped connection indistinguishable from a genuinely complete-but-corrupt
  download and defeated `FR-AST-3` resumability (the caller only keeps the partial file for
  resume when the read throws). Fixed by wrapping the response body in a length-validating
  stream that throws `IOException` on a short EOF, matching `FakeHttpRangeClient.dropAfterBytes`'s
  documented contract exactly.
- No test in this module, or anywhere in the ordinary `./gradlew test` run, makes a real network
  call.
