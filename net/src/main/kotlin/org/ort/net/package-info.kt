/*
 * :net — the only module in this codebase permitted an HTTP client (technical design §16.1,
 * constitution V). Model acquisition (build-plan P18): fetch, resume, checksum-verify and
 * side-load a model onto disk. See README.md for the client choice and net/ModelAcquisition.kt
 * for the FR-AST-2/FR-AST-3/FR-ASR-8 semantics.
 */
package org.ort.net
