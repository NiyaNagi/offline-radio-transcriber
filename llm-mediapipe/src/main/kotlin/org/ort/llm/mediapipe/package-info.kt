/*
 * :llm-mediapipe — the MediaPipe LLM Inference (`com.google.mediapipe:tasks-genai`)
 * implementation of the :llm-api contract. T3-only load (FR-AST-3a, AC-138): a T0/T1/T2 device
 * stores the model as part of the bundled asset set but never loads it into residency, so the
 * size cost is paid once at install and the memory/compute cost only where the tier already
 * affords it.
 *
 * Module :llm-mediapipe is scaffolded per technical design section 2: wired into the build
 * graph with its permitted dependencies (see build.gradle.kts) but not yet implemented.
 * The build-plan wave that owns it is in spec/build-plan.md (Wave F, P20).
 */
package org.ort.llm.mediapipe
