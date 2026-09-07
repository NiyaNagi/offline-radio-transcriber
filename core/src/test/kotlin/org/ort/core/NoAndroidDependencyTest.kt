package org.ort.core

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * `:core` has no Android dependency at all (technical design rule 3; constitution VII). The
 * `dependencyRules` task enforces the module graph; this asserts the stronger property that
 * the Android platform is not even reachable on `:core`'s classpath.
 */
class NoAndroidDependencyTest {

    @Test
    fun `no android platform class is on the core classpath`() {
        for (fqcn in listOf("android.content.Context", "android.os.SystemClock", "androidx.room.RoomDatabase")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(fqcn) }
        }
    }
}
