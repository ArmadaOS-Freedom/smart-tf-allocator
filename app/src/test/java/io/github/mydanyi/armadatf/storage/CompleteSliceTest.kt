package io.github.mydanyi.armadatf.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class CompleteSliceTest {

    @Test
    fun `inline rows complete without a continuation`() {
        var calls = 0
        val rows = collectCompleteSlice(3, listOf("a", "b", "c")) {
            calls++
            emptyList()
        }
        assertEquals(listOf("a", "b", "c"), rows)
        assertEquals(0, calls)
    }

    @Test
    fun `zero total is a legitimate empty slice`() {
        var calls = 0
        val rows = collectCompleteSlice(0, emptyList<String>()) {
            calls++
            emptyList()
        }
        assertEquals(emptyList<String>(), rows)
        assertEquals(0, calls)
    }

    @Test
    fun `multiple chunks keep order and reach the declared total`() {
        val rows = collectCompleteSlice(5, listOf(0, 1)) { offset ->
            when (offset) {
                2 -> listOf(2, 3)
                4 -> listOf(4)
                else -> throw AssertionError("unexpected offset $offset")
            }
        }
        assertEquals(listOf(0, 1, 2, 3, 4), rows)
    }

    @Test
    fun `failed continuation propagates instead of returning a prefix`() {
        val failure = IllegalStateException("continuation failed")
        try {
            collectCompleteSlice(4, listOf(0)) { throw failure }
            fail("truncated prefix was returned")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
    }

    @Test
    fun `empty continuation is rejected`() {
        try {
            collectCompleteSlice(2, listOf(0)) { emptyList() }
            fail("no-progress continuation was accepted")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `extra continuation rows are rejected`() {
        try {
            collectCompleteSlice(3, listOf(0)) { listOf(1, 2, 3) }
            fail("extra rows were accepted")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `negative total is rejected`() {
        try {
            collectCompleteSlice(-1, emptyList<Int>()) { emptyList() }
            fail("negative total was accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `inline rows beyond the total are rejected`() {
        try {
            collectCompleteSlice(1, listOf(0, 1)) { emptyList() }
            fail("inline overflow was accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `large slices are not truncated`() {
        val inline = (0 until 10).toList()
        val rows = collectCompleteSlice(301, inline) { offset ->
            val end = minOf(offset + 97, 301)
            (offset until end).toList()
        }
        assertEquals(301, rows.size)
        assertEquals(300, rows.last())
        assertEquals((0 until 301).toList(), rows)
    }
}
