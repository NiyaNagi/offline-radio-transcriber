package org.ort.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CorpusManifestTest {

    private val manifest = """
        # id	fold	source	path	synthetic
        val-hour-01	train	own-recording	audio/val/01.flac	false
        val-hour-02	dev	own-recording	audio/val/02.flac	false
        noise-dev	dev	own-recording	audio/noise-dev.flac	false
        noise-eval	eval	own-recording	audio/noise-eval.flac	false
    """.trimIndent()

    @Test
    fun `AC_100 the eval fold is not readable without an explicit opt-in`() {
        val m = CorpusManifest.parse(manifest)
        assertThrows(IllegalStateException::class.java) { m.entries(Fold.EVAL) }
        assertEquals(1, m.entries(Fold.EVAL, allowEval = true).size)
        assertEquals(3, m.all().size, "all() hides eval by default")
    }

    @Test
    fun `FR_TST_9 a synthetic entry in the eval fold is rejected outright`() {
        assertThrows(IllegalArgumentException::class.java) {
            CorpusManifest.parse("gen-01\teval\tsynthetic\taudio/gen/01.flac\ttrue")
        }
    }

    @Test
    fun `a duplicate id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CorpusManifest.parse("x\ttrain\ts\tp\nx\tdev\ts\tp")
        }
    }
}
