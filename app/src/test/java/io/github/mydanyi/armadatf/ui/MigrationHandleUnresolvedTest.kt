package io.github.mydanyi.armadatf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side checks of the one shared predicate both Activities use to decide
 * whether a saved migration request still blocks destructive work.
 *
 * Only pure [MigrationHandle] objects are used: no Android, JSON or IPC call is
 * involved, and the real production property is exercised directly.
 */
class MigrationHandleUnresolvedTest {

    private fun handle(state: String, moveId: Int? = 7) = MigrationHandle(
        moveId = moveId,
        diskId = "disk-1",
        kind = "primary",
        targetUuid = "internal",
        packageName = null,
        sourceUuid = "internal",
        privateUuid = "1111-2222",
        state = state,
    )

    @Test
    fun unverifiedHandleRestoredInANewProcessStillBlocks() {
        // AppSettings.migrationHandle() rebuilds exactly these fields on a fresh
        // process, so only the object identity is new here.
        val persisted = handle("unverified", moveId = 42)
        val restored = persisted.copy()

        assertTrue(restored.isUnresolved)
        assertEquals(42, restored.moveId ?: -1)
    }

    @Test
    fun startingAndRunningHandlesBlock() {
        assertTrue(handle("starting", moveId = null).isUnresolved)
        assertTrue(handle("running").isUnresolved)
    }

    @Test
    fun definitiveNativeOutcomesDoNotBlock() {
        assertFalse(handle("failed").isUnresolved)
        assertFalse(handle("completed").isUnresolved)
    }

    @Test
    fun unknownOrTransientStateCannotReleaseTheHandle() {
        // Only the two definitive states above may release the handle; a value
        // this build does not recognise must keep destructive work blocked.
        assertTrue(handle("unknown").isUnresolved)
        assertTrue(handle("pending").isUnresolved)
        assertTrue(handle("COMPLETED").isUnresolved)
        assertTrue(handle("running ").isUnresolved)
    }

    @Test
    fun unverifiedHandleKeepsTheOriginalMoveIdForResume() {
        // resumeSavedPolling reuses this id; the predicate must neither need a
        // new id nor treat the missing verification as resolved.
        val resumed = handle("unverified", moveId = 3)

        assertTrue(resumed.isUnresolved)
        assertEquals(3, resumed.moveId ?: -1)
    }
}
