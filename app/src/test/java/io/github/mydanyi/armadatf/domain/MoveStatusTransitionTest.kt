package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoveStatusTransitionTest {

    @Test
    fun inProgressClaimsAFreeSlot() {
        assertEquals(7, MoveStatusDecoder.activeMoveIdAfter(null, 7, MoveStatus.InProgress(10)))
        assertEquals(7, MoveStatusDecoder.activeMoveIdAfter(7, 7, MoveStatus.InProgress(10)))
    }

    @Test
    fun runningToCompleteReleasesTheSameMove() {
        assertNull(MoveStatusDecoder.activeMoveIdAfter(7, 7, MoveStatus.Complete))
    }

    @Test
    fun runningToFailedReleasesTheSameMove() {
        assertNull(
            MoveStatusDecoder.activeMoveIdAfter(
                7,
                7,
                MoveStatus.Failed("insufficient_storage", -1),
            ),
        )
    }

    @Test
    fun terminalStatusKeepsAnUnrelatedActiveMove() {
        assertEquals(9, MoveStatusDecoder.activeMoveIdAfter(9, 7, MoveStatus.Complete))
        assertEquals(
            9,
            MoveStatusDecoder.activeMoveIdAfter(
                9,
                7,
                MoveStatus.Failed("internal_error", -6),
            ),
        )
    }

    @Test
    fun terminalStatusWithoutAnActiveMoveStaysEmpty() {
        assertNull(MoveStatusDecoder.activeMoveIdAfter(null, 7, MoveStatus.Complete))
        assertNull(
            MoveStatusDecoder.activeMoveIdAfter(
                null,
                7,
                MoveStatus.Failed("internal_error", -6),
            ),
        )
    }
}
