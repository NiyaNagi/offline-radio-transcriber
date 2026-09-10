/*
 * :llm-api — the on-device LLM contract (FR-DIG-3, FR-DIG-3b, FR-ASR-15/16, D36). Post-hoc
 * only: it rescores ASR n-best output and drafts the digest after Pass D/E have already
 * resolved callsigns, and it is never in the callsign path (D5, constitution I — a language
 * model does not get to assert an identity or an over's content, only to rephrase or rank what
 * the audio-grounded passes already produced). Every engine ships with a behavioural fake
 * (constitution II) — one that can be told to fail, hang or hallucinate, not a stub that only
 * succeeds.
 *
 * Module :llm-api is scaffolded per technical design section 2: wired into the build graph
 * with its permitted dependencies (see build.gradle.kts) but not yet implemented.
 * The build-plan wave that owns it is in spec/build-plan.md (Wave F, P20).
 */
package org.ort.llm
