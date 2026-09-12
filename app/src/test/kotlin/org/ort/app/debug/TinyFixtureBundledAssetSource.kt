package org.ort.app.debug

import org.ort.app.assets.BundledAssetSource
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelId
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * R-807/CI regression (`OutOfMemoryError` at `org.robolectric.res.android.Asset$_CompressedAsset
 * .getBuffer`, Release workflow run 34664670909, commit `5e07273f`): a fixture-sized
 * [BundledAssetSource] for any test whose own purpose is scenario *loading* or *navigation*, not
 * asset-installation fidelity — [ScenariosTest]'s own `R_110 loading every scenario back to back
 * five times...`/`R_110 every declared scenario name loads without throwing`, and
 * `org.ort.app.debug.tour.TourStepsTest`'s own `R_TOUR_STEPS...`. A genuine `HF_TOKEN` build's real
 * bundle (all five entries, one of them ~555MB) cannot be copied — or, worse, read back by
 * Robolectric's own asset reader, which inflates a compressed asset's entire uncompressed content
 * into the test JVM's heap to serve it — repeatedly inside any of these tests' own loops without
 * exhausting either a 1-minute test bound or the heap itself (the exact `OutOfMemoryError` this
 * fixture exists to prevent). `internal`, not `private`, and its own file rather than nested in one
 * test class: every test above needs the identical fixture, in a different package in
 * `TourStepsTest`'s own case — Kotlin's `internal` is module-scoped, not package-scoped, so this
 * stays visible across both `debug`/`test` source sets and every package under them the same way
 * [DebugBundledAssetSourceOverride] itself already is.
 *
 * Every real [ModelId]'s own real destination is used (the exact path
 * [org.ort.app.assets.BundledAssetInstaller.installOne] writes to and [ModelsController] reads
 * back from), with a handful of bytes and a `sha256` this fake source's own bytes genuinely hash to
 * — so [org.ort.app.assets.BundledAssetInstaller]'s real copy-then-verify code path runs
 * unmodified, only the byte count differs. `missing = false` for every entry, including the gated
 * LLM: this fake source exists to prove loading/navigation safety, not to simulate the escape
 * hatch (that is `WpiScenariosTest`'s own `R_841_assets-bundled...` concern).
 */
internal class TinyFixtureBundledAssetSource(filesDir: File) : BundledAssetSource {
    private val tinyBytes = "R-807 tiny fixture asset, never the real bundle".toByteArray()
    private val tinySha256 = MessageDigest.getInstance("SHA-256").digest(tinyBytes)
        .joinToString("") { "%02x".format(it) }
    private val manifestJson = run {
        val assetsJson = ModelId.entries.joinToString(",\n") { id ->
            val destination = ModelCatalog.entry(id).destination(filesDir)
                .relativeTo(filesDir).invariantSeparatorsPath
            """{"id":"${id.name}","destination":"$destination","sha256":"$tinySha256",""" +
                """"sizeBytes":${tinyBytes.size},"missing":false}"""
        }
        """{"assets":[$assetsJson]}"""
    }

    override fun open(assetPath: String): InputStream = if (assetPath == "bundled/manifest.json") {
        manifestJson.toByteArray().inputStream()
    } else {
        tinyBytes.inputStream()
    }
}
