package org.ort.app

import android.app.Application

/**
 * Application shell. The Hilt graph, capture service wiring and onboarding arrive with
 * build-plan P8; for now this exists so the APK has a launchable entry point and the
 * minimum-API smoke test (AC-93 / NFR-5) has something real to start.
 */
class OrtApplication : Application()
