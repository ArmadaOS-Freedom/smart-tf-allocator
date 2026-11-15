package io.github.mydanyi.armadatf.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-level regression for the retryExpected compare-and-publish transaction.
 *
 * [publishExpectedHandoff] is the production helper; these tests drive it
 * directly (no Android, no Context) and assert that a missing or superseded
 * pending payload is never published, and that a captured but stale
 * publication cannot interleave with its replacement.
 */
class ExpectedHandoffPublicationTest {

    private val lock = Any()
    private val a = "pending-a".toByteArray()
    private val b = "pending-b".toByteArray()

    @Test(timeout = 5_000)
    fun missingPendingDoesNotPublish() {
        var published: ByteArray? = null
        val result = publishExpectedHandoff(lock, a, { null }) { published = it }
        assertNull(result)
        assertNull(published)
    }

    @Test(timeout = 5_000)
    fun replacedPendingDoesNotPublish() {
        var published: ByteArray? = null
        val result = publishExpectedHandoff(lock, a, { b }) { published = it }
        assertNull(result)
        assertNull(published)
    }

    @Test(timeout = 5_000)
    fun exactPendingPublishesOnce() {
        var published: ByteArray? = null
        val result = publishExpectedHandoff(lock, a, { a.copyOf() }) { bytes ->
            published = bytes
            "published"
        }
        assertEquals("published", result)
        assertEquals("pending-a", String(published!!))
    }

    @Test(timeout = 5_000)
    fun publisherFailurePropagates() {
        try {
            publishExpectedHandoff<String>(lock, a, { a }) { throw IllegalStateException("io") }
            fail("publisher failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("io", expected.message)
        }
    }

    @Test(timeout = 5_000)
    fun readValidationFailurePropagates() {
        try {
            publishExpectedHandoff<String>(lock, a, { throw HandoffException("handoff_invalid") }) {
                throw AssertionError("a corrupt pending copy must never be published")
            }
            fail("read/validation failure must propagate")
        } catch (expected: HandoffException) {
            assertEquals("handoff_invalid", expected.code)
        }
    }

    /**
     * A is a real helper publication paused inside its publisher while holding
     * the lock; B models the newer write (replace pending, publish, clear) under
     * the same lock. B cannot finish before A is released and a stale A can
     * never land after B; the old compare/unlock/publish let B publish first.
     */
    @Test(timeout = 15_000)
    fun stalePublicationCannotInterleaveWithReplacement() {
        val pending = AtomicReference(a)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val published = Collections.synchronizedList(mutableListOf<String>())
        val failureA = AtomicReference<Throwable?>(null)
        val failureB = AtomicReference<Throwable?>(null)
        val aEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bAttempting = CountDownLatch(1)
        val bDone = CountDownLatch(1)

        val threadA = Thread {
            try {
                publishExpectedHandoff(lock, a, { pending.get() }) { bytes ->
                    aEntered.countDown()
                    releaseA.await(10, TimeUnit.SECONDS)
                    order.add("A") // recorded after release: A held the lock
                    published.add(String(bytes))
                    bytes.size
                }
            } catch (t: Throwable) {
                failureA.set(t)
            }
        }
        val threadB = Thread {
            try {
                bAttempting.countDown()
                synchronized(lock) {
                    pending.set(b)
                    order.add("B")
                    published.add(String(b))
                    pending.set(null)
                    bDone.countDown()
                }
            } catch (t: Throwable) {
                failureB.set(t)
            }
        }

        try {
            threadA.start()
            assertTrue(aEntered.await(10, TimeUnit.SECONDS))
            threadB.start()
            assertTrue(bAttempting.await(10, TimeUnit.SECONDS))
            assertFalse("B published while A held the lock",
                bDone.await(300, TimeUnit.MILLISECONDS))
            releaseA.countDown()
            assertTrue(bDone.await(10, TimeUnit.SECONDS))
            threadA.join(10_000)
            threadB.join(10_000)
            assertNull(failureA.get())
            assertNull(failureB.get())
            assertEquals(listOf("A", "B"), order)
            assertEquals(listOf("pending-a", "pending-b"), published)
            assertNull(pending.get())
        } finally {
            releaseA.countDown()
            threadA.join(5_000)
            threadB.join(5_000)
        }
    }
}
