package org.ort.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Regenerates `results/coverage-matrix.md` from the spec ids and the test sources. */
abstract class CoverageMatrixTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testRoots: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun generate() {
        val coverage = CoverageMatrix.analyse(specDir.get().asFile, testRoots.files)
        val md = CoverageMatrix.render(coverage)
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.writeText(md)
        logger.lifecycle(
            "coverageMatrix: ${coverage.requirements.size} requirements, " +
                "${coverage.covered.size} covered -> ${target.relativeTo(project.rootDir)}",
        )
        if (coverage.orphanTests.isNotEmpty()) {
            logger.warn("coverageMatrix: orphan tests naming unknown requirements: ${coverage.orphanTests.keys}")
        }
    }
}
