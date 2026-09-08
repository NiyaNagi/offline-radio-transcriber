package org.ort.app.ui.screens

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * R-055, `Detail-Revisions.dc.html`: the word-level diff a version card's transcript renders
 * against the current version. Pure logic, tested independent of Compose.
 */
class WordDiffTest {

    @Test
    fun `identical text is all Equal ops`() {
        val ops = WordDiff.diff(listOf("this", "is", "a", "test"), listOf("this", "is", "a", "test"))

        assert(ops.all { it is WordDiff.Op.Equal })
        assertEquals(listOf("this", "is", "a", "test"), ops.map { (it as WordDiff.Op.Equal).word })
    }

    @Test
    fun `a single substituted word diffs as one deletion and one insertion`() {
        val ops = WordDiff.diff(
            old = "november pop a charlie".split(" "),
            new = "november papa charlie".split(" "),
        )

        assertEquals(
            listOf(
                WordDiff.Op.Equal("november"),
                WordDiff.Op.Deleted("pop"),
                WordDiff.Op.Deleted("a"),
                WordDiff.Op.Inserted("papa"),
                WordDiff.Op.Equal("charlie"),
            ),
            ops,
        )
    }

    @Test
    fun `an appended word at the end is a trailing insertion`() {
        val ops = WordDiff.diff(old = listOf("roger", "that"), new = listOf("roger", "that", "clear"))

        assertEquals(
            listOf(WordDiff.Op.Equal("roger"), WordDiff.Op.Equal("that"), WordDiff.Op.Inserted("clear")),
            ops,
        )
    }

    @Test
    fun `an empty old text is entirely insertions`() {
        val ops = WordDiff.diff(old = emptyList(), new = listOf("hello", "world"))

        assertEquals(listOf(WordDiff.Op.Inserted("hello"), WordDiff.Op.Inserted("world")), ops)
    }
}
