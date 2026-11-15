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
        val queriedFlags = mutableListOf<Long>()
        val cardApp = snapshot("com.example.card", sourceUuid = "card-uuid")

        val occupancy = NativeMigration().allUsersPackages(
            users = listOf(OWNER, WORK_PROFILE),
            callerUserId = OWNER,
        ) { userId, flags ->
            queried += userId
            queriedFlags += flags
            if (userId == WORK_PROFILE && flags == 0x2000L) listOf(cardApp) else emptyList()
        }

        assertEquals(listOf(OWNER, WORK_PROFILE), queried)
        assertEquals(listOf(0x2000L, 0x2000L), queriedFlags)
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
            ) { userId, _ ->
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
        fails {
            NativeMigration().allUsersPackages(emptyList(), OWNER) { _, _ -> emptyList() }
        }
        fails {
            NativeMigration().allUsersPackages(listOf(WORK_PROFILE), OWNER) { _, _ ->
                listOf(snapshot("com.example.other"))
            }
        }
    }

    @Test
    fun validEmptyListPerUserIsAllowed() {
        val occupancy = NativeMigration().allUsersPackages(listOf(OWNER), OWNER) { _, _ ->
            emptyList()
        }

        assertEquals(mapOf(OWNER to emptyList<PackageSnapshot>()), occupancy)
    }

    @Test
    fun occupancyKeepsOnlyInstalledEntries() {
        val installed = FakeApplicationInfo().apply {
            packageName = "com.example.hidden"
            flags = FLAG_INSTALLED
        }
        val uninstalled = FakeApplicationInfo().apply {
            packageName = "com.example.retained"
            flags = 0
        }

        val kept = NativeMigration().occupancyApplicationEntries(
            listOf(installed, uninstalled),
        )

        assertEquals(listOf<Any>(installed), kept)
    }

    @Test
    fun occupancyKeepsInstalledSystemAndFactoryApps() {
        val systemApp = FakeApplicationInfo().apply {
            packageName = "com.example.system"
            flags = FLAG_INSTALLED or FLAG_SYSTEM
        }
        val factoryApp = FakeApplicationInfo().apply {
            packageName = "com.example.factory"
            flags = FLAG_INSTALLED
            sourceDir = "/system/priv-app/Factory/Factory.apk"
        }

        val kept = NativeMigration().occupancyApplicationEntries(
            listOf(systemApp, factoryApp),
        )

        assertEquals(listOf<Any>(systemApp, factoryApp), kept)
    }

    @Test
    fun occupancyRefusesNullListAndNullEntries() {
        fails { NativeMigration().occupancyApplicationEntries(null) }
        fails { NativeMigration().occupancyApplicationEntries(listOf(null)) }
        fails {
            NativeMigration().occupancyApplicationEntries(
                listOf(FakeApplicationInfo().apply { flags = FLAG_INSTALLED }, null),
            )
        }
    }

    @Test
    fun occupancyRefusesMissingOrMalformedFlags() {
        fails { NativeMigration().occupancyApplicationEntries(listOf(MissingFlagsInfo())) }
        fails { NativeMigration().occupancyApplicationEntries(listOf(MalformedFlagsInfo())) }
        fails {
            NativeMigration().occupancyApplicationEntries(
                listOf(
                    FakeApplicationInfo().apply { flags = FLAG_INSTALLED },
                    MissingFlagsInfo(),
                ),
            )
        }
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

    class MissingFlagsInfo {
        @JvmField
        val packageName = "com.example.missing"
    }

    class MalformedFlagsInfo {
        @JvmField
        val packageName = "com.example.malformed"

        @JvmField
        val flags = "not-a-number"
    }

    private companion object {
        const val OWNER = 0
        const val WORK_PROFILE = 10
        const val FLAG_INSTALLED = 1 shl 23
        const val FLAG_SYSTEM = 1
    }
}
