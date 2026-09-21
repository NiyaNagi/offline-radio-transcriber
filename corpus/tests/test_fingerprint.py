"""Tests for corpus/src/corpus/fingerprint.py (R-1122, register - constitution VI: "every
reported figure carries fold, machine, execution provider, thread count, model version and run
fingerprint"). Before this fix, `run_fingerprint` hashed the manifest, fold, config, git revision
and Python version only - two runs on different machines, with different execution providers or
different thread counts, produced the identical fingerprint as long as those five agreed, which
made the fingerprint useless as the thing constitution VI calls "no number without its
provenance": it could not tell two genuinely different runs apart.

Each test is named for what it establishes, per AGENTS.md's convention (`R_1122_...`). Per
constitution II, each of these must be shown to discriminate: revert the payload change in
`run_fingerprint` and each of the three "differs across X" tests fails for the right reason
(either a `TypeError` for the now-unknown keyword, or the two fingerprints collapsing to the same
value) - see CHANGELOG.md's R-1122 entry for the transcript.
"""
from __future__ import annotations

import platform

from corpus.fingerprint import (
    execution_provider,
    machine_descriptor,
    run_fingerprint,
    thread_count,
)


def test_R_1122_fingerprint_differs_across_machines():
    """Same manifest, fold and config; only the machine differs. Before the fix, `machine` was
    not part of the hashed payload at all, so this collapsed to one value."""
    manifest_doc = {"sources": []}
    fp_a = run_fingerprint(manifest_doc, "dev", {}, machine="host-A", provider="p", threads=4)
    fp_b = run_fingerprint(manifest_doc, "dev", {}, machine="host-B", provider="p", threads=4)
    assert fp_a != fp_b


def test_R_1122_fingerprint_differs_across_execution_provider():
    """A CPU run and a GPU (or different EP version) run of the identical fold/config/code must
    not be mistaken for the same run - the whole point of "determinism is bounded... within a
    fixed (machine, provider, thread count, runtime version)"."""
    manifest_doc = {"sources": []}
    fp_a = run_fingerprint(manifest_doc, "dev", {}, machine="host-A", provider="cpu-ep-v1", threads=4)
    fp_b = run_fingerprint(manifest_doc, "dev", {}, machine="host-A", provider="cpu-ep-v2", threads=4)
    assert fp_a != fp_b


def test_R_1122_fingerprint_differs_across_thread_count():
    """Thread count is named explicitly alongside machine and provider as a determinism bound."""
    manifest_doc = {"sources": []}
    fp_a = run_fingerprint(manifest_doc, "dev", {}, machine="host-A", provider="p", threads=1)
    fp_b = run_fingerprint(manifest_doc, "dev", {}, machine="host-A", provider="p", threads=8)
    assert fp_a != fp_b


def test_R_1122_fingerprint_is_stable_for_identical_inputs():
    """Sanity companion: the new fields must not introduce nondeterminism of their own - two
    calls with every argument identical (including the explicit provenance overrides) agree."""
    manifest_doc = {"sources": ["x"]}
    fp_a = run_fingerprint(manifest_doc, "dev", {"k": 1}, machine="host-A", provider="p", threads=4)
    fp_b = run_fingerprint(manifest_doc, "dev", {"k": 1}, machine="host-A", provider="p", threads=4)
    assert fp_a == fp_b


def test_R_1122_fingerprint_defaults_are_used_when_not_supplied():
    """A caller that does not pass machine/provider/threads explicitly (the harness's own
    unadorned call shape) still gets a fingerprint that embeds this machine's own descriptor -
    it is not simply left out when the caller forgets to ask."""
    manifest_doc = {"sources": []}
    fp_default = run_fingerprint(manifest_doc, "dev", {})
    fp_explicit_same = run_fingerprint(
        manifest_doc, "dev", {},
        machine=machine_descriptor(), provider=execution_provider(), threads=thread_count(),
    )
    assert fp_default == fp_explicit_same


def test_R_1122_machine_descriptor_never_includes_the_operator_hostname():
    """Constitution VI's provenance requirement must not become a privacy leak: this file's
    output is meant to be publishable, and `platform.node()` routinely carries the operator's own
    account or machine name. `machine_descriptor()` must not be, or contain, the raw hostname."""
    hostname = platform.node()
    descriptor = machine_descriptor()
    assert descriptor != hostname
    if hostname:
        assert hostname not in descriptor


def test_R_1122_machine_descriptor_is_a_non_empty_stable_string():
    assert isinstance(machine_descriptor(), str)
    assert machine_descriptor()
    assert machine_descriptor() == machine_descriptor()


def test_R_1122_thread_count_is_a_positive_int():
    assert isinstance(thread_count(), int)
    assert thread_count() >= 1


def test_R_1122_execution_provider_is_a_non_empty_string():
    assert isinstance(execution_provider(), str)
    assert execution_provider()
