"""The two P6 decision-gate probes (functional-spec §14A.3, spec/build-plan.md S1.6-S1.7).

R4 — speaker separation on degraded narrowband voice (Fearless Steps), gating M6/D29.
R1 — LoRA fine-tune -> merge -> sherpa-onnx export -> load -> transcribe, gating D13.

Both probes are decision gates, not accuracy targets: a probe that cannot run for real in a
given environment must say so and record why, never invent a number (constitution VI).
"""
