package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepartitionPolicyTest {

    private companion object {
        const val CARD_COMPACT = "abcdef0123456789abcdef0123456789"
        const val CARD_DASHED = "ABCDEF01-2345-6789-ABCD-EF0123456789"
        const val OTHER_COMPACT = "11111111222233334444555566667777"
    }

    @Test
    fun noPrivateVolumesIsSafe() {
        assertNull(RepartitionPolicy.privateIdentityRefusal(emptyList()))
        assertNull(RepartitionPolicy.occupancyRefusal(emptySet(), null, emptyList()))
    }

    @Test
    fun validPrivateIdentityIsAccepted() {
        assertNull(RepartitionPolicy.privateIdentityRefusal(listOf(CARD_COMPACT)))
        assertNull(RepartitionPolicy.privateIdentityRefusal(listOf(CARD_DASHED)))
    }

    @Test
    fun unknownPrivateIdentityIsRefused() {
        for (raw in listOf(null, "", "   ", "not-a-uuid")) {
            assertEquals(
                MigrationErrors.APP_QUERY_UNAVAILABLE,
                RepartitionPolicy.privateIdentityRefusal(listOf(raw)),
            )
        }
    }

    @Test
    fun knownAndUnknownPrivatePairIsRefused() {
        assertEquals(
            MigrationErrors.APP_QUERY_UNAVAILABLE,
            RepartitionPolicy.privateIdentityRefusal(listOf(CARD_COMPACT, null)),
        )
    }

    @Test
    fun safeEmptyKnownCardIsAllowed() {
        val uuids = RepartitionPolicy.privateUuids(listOf(CARD_COMPACT))

        assertNull(RepartitionPolicy.occupancyRefusal(uuids, null, emptyList()))
        assertNull(RepartitionPolicy.occupancyRefusal(uuids, "internal", listOf(null)))
    }

    @Test
    fun primaryOnCardBlocksWithNormalizedComparison() {
        val uuids = RepartitionPolicy.privateUuids(listOf(CARD_COMPACT))

        assertEquals(
            MigrationErrors.MOVE_BACK_REQUIRED,
            RepartitionPolicy.occupancyRefusal(uuids, CARD_DASHED, emptyList()),
        )
    }

    @Test
    fun appOnCardBlocksAcrossProfilesAndSystemApps() {
        val uuids = RepartitionPolicy.privateUuids(listOf(CARD_DASHED))

        // Profile 0 has nothing on the card; profile 10 holds a system app there.
        assertEquals(
            MigrationErrors.MOVE_BACK_REQUIRED,
            RepartitionPolicy.occupancyRefusal(
                uuids,
                null,
                listOf(null, CARD_COMPACT, OTHER_COMPACT),
            ),
        )
    }

    @Test
    fun unknownPrivateUuidInOccupancySetIsUnverifiable() {
        assertEquals(
            MigrationErrors.APP_QUERY_UNAVAILABLE,
            RepartitionPolicy.occupancyRefusal(setOf(CARD_COMPACT, "garbage"), null, emptyList()),
        )
    }
}
