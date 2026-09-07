package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * `:lexicon` is pure JVM (constitution VII; technical design rule 3) — the highest-uncertainty
 * accuracy work runs on a desktop with fast tests. The `dependencyRules` task enforces the
 * module graph; this asserts the stronger property that the Android platform is not even
 * reachable on the classpath.
 */
class NoAndroidDependencyTest {

    @Test
    fun `no android platform class is on the lexicon classpath`() {
        for (fqcn in listOf("android.content.Context", "android.os.SystemClock", "androidx.room.RoomDatabase")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(fqcn) }
        }
    }
}
