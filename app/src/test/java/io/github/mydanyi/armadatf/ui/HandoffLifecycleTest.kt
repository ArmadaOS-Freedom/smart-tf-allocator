package io.github.mydanyi.armadatf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffLifecycleTest {

    @Test
    fun beginResetsCompletionAndCompletionIsPayloadBoundAgain() {
        val lifecycle = HandoffLifecycle()
        val first = lifecycle.currentGeneration()
        assertTrue(lifecycle.writeCompleted(first, "mixed-a", true))
        assertTrue(lifecycle.ready)

        lifecycle.begin()

        assertFalse(lifecycle.ready)
        assertNull(lifecycle.completedKey)
        assertEquals(HandoffLifecycle.PublishDecision.PUBLISH, lifecycle.decisionFor("mixed-a"))
    }

    @Test
    fun allAndroidThenNewLinuxPayloadWithoutObservedRunningPublishes() {
        val lifecycle = HandoffLifecycle()
        lifecycle.androidReady()
        assertTrue(lifecycle.ready)

        assertEquals(
            HandoffLifecycle.PublishDecision.PUBLISH,
            lifecycle.decisionFor("linux-b"),
        )
        assertTrue(lifecycle.writeCompleted(lifecycle.currentGeneration(), "linux-b", true))
        assertEquals(
            HandoffLifecycle.PublishDecision.ALREADY_COMPLETE,
            lifecycle.decisionFor("linux-b"),
        )
    }

    @Test
    fun previousMixedCompletionStillPublishesADistinctPayload() {
        val lifecycle = HandoffLifecycle()
        val generation = lifecycle.currentGeneration()
        assertTrue(lifecycle.writeCompleted(generation, "mixed-a", true))

        assertEquals(
            HandoffLifecycle.PublishDecision.PUBLISH,
            lifecycle.decisionFor("mixed-b"),
        )
    }

    @Test
    fun repeatedStatusForTheSameSuccessfulPayloadIsNotRewritten() {
        val lifecycle = HandoffLifecycle()
        val generation = lifecycle.currentGeneration()
        assertTrue(lifecycle.writeCompleted(generation, "mixed-a", true))

        assertEquals(
            HandoffLifecycle.PublishDecision.ALREADY_COMPLETE,
            lifecycle.decisionFor("mixed-a"),
        )
    }

    @Test
    fun failedWriteKeepsTheSamePayloadRetryable() {
        val lifecycle = HandoffLifecycle()
        val generation = lifecycle.currentGeneration()

        assertTrue(lifecycle.writeCompleted(generation, "linux-a", false))
        assertFalse(lifecycle.ready)
        assertEquals(
            HandoffLifecycle.PublishDecision.PUBLISH,
            lifecycle.decisionFor("linux-a"),
        )

        assertTrue(lifecycle.writeCompleted(generation, "linux-a", true))
        assertTrue(lifecycle.ready)
    }

    @Test
    fun pendingRecoveryInANewSessionPublishes() {
        val lifecycle = HandoffLifecycle()

        assertEquals(
            HandoffLifecycle.PublishDecision.PUBLISH,
            lifecycle.decisionFor("pending-a"),
        )
        assertTrue(
            lifecycle.writeCompleted(
                lifecycle.currentGeneration(),
                "pending-a",
                true,
            ),
        )
        assertTrue(lifecycle.ready)
        assertEquals("pending-a", lifecycle.completedKey)
    }

    @Test
    fun staleRecoveryOrWriteCallbackIsIgnored() {
        val lifecycle = HandoffLifecycle()
        val stale = lifecycle.currentGeneration()
        lifecycle.begin()

        assertFalse(lifecycle.writeCompleted(stale, "stale-a", true))
        assertFalse(lifecycle.ready)
        assertNull(lifecycle.completedKey)
    }

    @Test
    fun allAndroidInvalidatesAnOlderRecoveryCallback() {
        val lifecycle = HandoffLifecycle()
        val stale = lifecycle.currentGeneration()
        lifecycle.androidReady()

        assertFalse(lifecycle.writeCompleted(stale, "linux-a", true))
        assertTrue(lifecycle.ready)
        assertEquals(HandoffLifecycle.ANDROID_KEY, lifecycle.completedKey)
    }

    @Test
    fun aNewPayloadSupersedesAnInFlightOneAndOnlyTheLatestCompletes() {
        val lifecycle = HandoffLifecycle()
        assertTrue(lifecycle.selectPayload("linux-a"))
        val staleGeneration = lifecycle.currentGeneration()

        assertTrue(lifecycle.selectPayload("linux-b"))
        assertFalse(lifecycle.writeCompleted(staleGeneration, "linux-a", true))
        assertFalse(lifecycle.ready)
        assertNull(lifecycle.completedKey)
        assertEquals("linux-b", lifecycle.currentKey)
        assertEquals(
            HandoffLifecycle.PublishDecision.PUBLISH,
            lifecycle.decisionFor("linux-b"),
        )

        val current = lifecycle.currentGeneration()
        assertTrue(lifecycle.writeCompleted(current, "linux-b", true))
        assertTrue(lifecycle.ready)
        assertEquals(
            HandoffLifecycle.PublishDecision.ALREADY_COMPLETE,
            lifecycle.decisionFor("linux-b"),
        )
    }

    @Test
    fun repeatedSelectionOfTheSamePayloadKeepsInFlightWorkCurrent() {
        val lifecycle = HandoffLifecycle()
        assertTrue(lifecycle.selectPayload("linux-a"))
        val generation = lifecycle.currentGeneration()

        assertFalse(lifecycle.selectPayload("linux-a"))
        assertTrue(lifecycle.isCurrent(generation))
        assertTrue(lifecycle.writeCompleted(generation, "linux-a", true))
        assertTrue(lifecycle.ready)
    }

    @Test
    fun selectingTheAlreadyCompletedPayloadDoesNotInvalidateIt() {
        val lifecycle = HandoffLifecycle()
        val generation = lifecycle.currentGeneration()
        assertTrue(lifecycle.writeCompleted(generation, "linux-a", true))

        assertFalse(lifecycle.selectPayload("linux-a"))
        assertTrue(lifecycle.isCurrent(generation))
        assertEquals(
            HandoffLifecycle.PublishDecision.ALREADY_COMPLETE,
            lifecycle.decisionFor("linux-a"),
        )
    }

    @Test
    fun aCallbackForADifferentPayloadInTheSameGenerationIsRejected() {
        val lifecycle = HandoffLifecycle()
        lifecycle.selectPayload("linux-b")
        val generation = lifecycle.currentGeneration()

        assertFalse(lifecycle.writeCompleted(generation, "linux-a", true))
        assertFalse(lifecycle.ready)
        assertNull(lifecycle.completedKey)
    }

    @Test
    fun beginInvalidatesAnInFlightPayloadSelection() {
        val lifecycle = HandoffLifecycle()
        lifecycle.selectPayload("linux-a")
        val staleGeneration = lifecycle.currentGeneration()

        lifecycle.begin()

        assertNull(lifecycle.currentKey)
        assertFalse(lifecycle.currentNeedsPublication())
        assertFalse(lifecycle.writeCompleted(staleGeneration, "linux-a", true))
        assertFalse(lifecycle.ready)
    }

    @Test
    fun allAndroidInvalidatesAPreviouslySelectedPayload() {
        val lifecycle = HandoffLifecycle()
        lifecycle.selectPayload("linux-a")
        val staleGeneration = lifecycle.currentGeneration()

        lifecycle.androidReady()

        assertNull(lifecycle.currentKey)
        assertFalse(lifecycle.writeCompleted(staleGeneration, "linux-a", true))
        assertTrue(lifecycle.ready)
        assertEquals(HandoffLifecycle.ANDROID_KEY, lifecycle.completedKey)
    }

    @Test
    fun currentStableRecoveryApplies() {
        val lifecycle = HandoffLifecycle()

        assertEquals(
            HandoffLifecycle.RecoveryDisposition.APPLY,
            lifecycle.recoveryDisposition(lifecycle.currentGeneration(), superseded = false),
        )
    }

    @Test
    fun currentSupersededRecoveryReloads() {
        val lifecycle = HandoffLifecycle()

        assertEquals(
            HandoffLifecycle.RecoveryDisposition.RELOAD,
            lifecycle.recoveryDisposition(lifecycle.currentGeneration(), superseded = true),
        )
    }

    @Test
    fun staleRecoveryIsIgnored() {
        val lifecycle = HandoffLifecycle()
        val stale = lifecycle.currentGeneration()
        lifecycle.begin()

        assertEquals(
            HandoffLifecycle.RecoveryDisposition.IGNORE,
            lifecycle.recoveryDisposition(stale, superseded = false),
        )
        assertEquals(
            HandoffLifecycle.RecoveryDisposition.IGNORE,
            lifecycle.recoveryDisposition(stale, superseded = true),
        )
    }
}
