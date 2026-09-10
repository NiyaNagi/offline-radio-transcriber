package org.ort.pipeline.digest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LlmModelLocatorTest {

    @Test
    fun `locate is null when the model has not been installed`() {
        val filesDir = createTempDirectory("llm-model-locator-test").toFile()

        assertNull(LlmModelLocator.locate(filesDir))
    }

    @Test
    fun `locate finds the model once it exists at the documented path`() {
        val filesDir = createTempDirectory("llm-model-locator-test").toFile()
        val dir = LlmModelLocator.modelsDir(filesDir)
        dir.mkdirs()
        val modelFile = File(dir, "${LlmModelLocator.MODEL_ID}.task")
        modelFile.writeText("not a real model, just proving the path")

        val located = LlmModelLocator.locate(filesDir)

        assertEquals(modelFile.absolutePath, located?.absolutePath)
    }
}
