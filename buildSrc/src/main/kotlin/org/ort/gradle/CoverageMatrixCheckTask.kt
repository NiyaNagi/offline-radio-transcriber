package org.ort.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * F-014: `results/coverage-matrix.md` is committed, but nothing in CI failed when it drifted
 * from what the spec/tests actually produce — it went a whole wave stale (101 -> 117 covered)
 * before an audit caught it. This task regenerates the matrix in memory and fails the build if
 * it differs from the committed file, so drift is a CI gate rather than something an auditor
 * has to notice by hand (constitution, Development Workflow: "the coverage-matrix delta").
 *
 * A trailing-newline or line-ending-only difference is not treated as drift — see
 * [CoverageMatrix.contentMatches].
 */
abstract class CoverageMatrixCheckTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testRoots: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val committed: RegularFileProperty

    @TaskAction
    fun check() {
        val coverage = CoverageMatrix.analyse(specDir.get().asFile, testRoots.files)
        val generated = CoverageMatrix.render(coverage)
        val committedFile = committed.get().asFile
        val committedText = if (committedFile.exists()) committedFile.readText() else ""

        if (!CoverageMatrix.contentMatches(generated, committedText)) {
            val relative = committedFile.relativeTo(project.rootDir)
            throw GradleException(
                "$relative is stale — run ./gradlew coverageMatrix and commit the result.",
            )
        }
        logger.lifecycle("coverageMatrixCheck: up to date (${coverage.covered.size} covered of ${coverage.requirements.size}).")
    }
}
