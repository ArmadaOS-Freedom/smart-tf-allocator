package io.github.mydanyi.armadatf.storage

import io.github.mydanyi.armadatf.storage.NativeMigration.PackageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests for the installed-application list validation and the
 * all-user occupancy sweep in [NativeMigration].
 *
 * The per-user read is injected as a plain lambda and the list seam takes plain
 * lists, so no binder, `IUserManager`/`IPackageManager` call or Android stub is
 * involved.
 */
class NativeMigrationOccupancyQueryTest {

    @Test
    fun missingOrNullApplicationListIsRejected() {
        fails { NativeMigration().applicationEntries(null) }
        fails { NativeMigration().applicationEntries(listOf(null)) }
    }

    @Test
    fun emptyApplicationListIsStillValid() {
        assertEquals(emptyList<Any>(), NativeMigration().applicationEntries(emptyList<Any?>()))
    }

    @Test
    fun readableApplicationEntriesAreKeptInOrder() {
        val entries = NativeMigration().applicationEntries(listOf("first", "second"))

        assertEquals(listOf("first", "second"), entries)
    }

    @Test
    fun ownerEmptyAndWorkProfileAppIsQueriedAndRetained() {
        val queried = mutableListOf<Int>()
        val cardApp = snapshot("com.example.card", sourceUuid = "card-uuid")

        val occupancy = NativeMigration().allUsersPackages(
            users = listOf(OWNER, WORK_PROFILE),
            callerUserId = OWNER,
        ) { userId ->
            queried += userId
            if (userId == WORK_PROFILE) listOf(cardApp) else emptyList()
        }

        assertEquals(listOf(OWNER, WORK_PROFILE), queried)
        assertEquals(setOf(OWNER, WORK_PROFILE), occupancy.keys)
        assertEquals(emptyList<PackageSnapshot>(), occupancy[OWNER])
        assertEquals(listOf(cardApp), occupancy[WORK_PROFILE])
    }

    @Test
    fun secondaryUserQueryFailureAbortsTheSweep() {
        val queried = mutableListOf<Int>()
        val failure = try {
            NativeMigration().allUsersPackages(
                users = listOf(OWNER, WORK_PROFILE),
                callerUserId = OWNER,
            ) { userId ->
                queried += userId
                if (userId == WORK_PROFILE) throw SecurityException("profile denied")
                listOf(snapshot("com.example.owner"))
            }
            null
        } catch (e: SecurityException) {
            e
        }

        assertEquals(listOf(OWNER, WORK_PROFILE), queried)
        assertTrue("query failure must abort the sweep", failure != null)
    }

    @Test
    fun missingCallerOrEmptyUserListIsRefused() {
        fails { NativeMigration().allUsersPackages(emptyList(), OWNER) { emptyList() } }
        fails {
            NativeMigration().allUsersPackages(listOf(WORK_PROFILE), OWNER) {
                listOf(snapshot("com.example.other"))
            }
        }
    }

    @Test
    fun validEmptyListPerUserIsAllowed() {
        val occupancy = NativeMigration().allUsersPackages(listOf(OWNER), OWNER) {
            emptyList()
        }

        assertEquals(mapOf(OWNER to emptyList<PackageSnapshot>()), occupancy)
    }

    private fun snapshot(
        packageName: String,
        sourceUuid: String? = null,
    ): PackageSnapshot = PackageSnapshot(
        packageName = packageName,
        isSystem = false,
        isUpdatedSystem = false,
        installLocation = -1,
        sourceUuid = sourceUuid,
        isPreinstalled = false,
    )

    private fun fails(block: () -> Unit) {
        try {
            block()
        } catch (e: NativeMigrationException) {
            return
        }
        throw AssertionError("expected NativeMigrationException")
    }

    private companion object {
        const val OWNER = 0
        const val WORK_PROFILE = 10
    }
}
