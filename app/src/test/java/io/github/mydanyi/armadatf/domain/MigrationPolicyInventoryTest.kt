package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationPolicyInventoryTest {

    private companion object {
        const val CARD = "abcdef0123456789abcdef0123456789"
    }

    @Test
    fun inventoryKeepsEveryVisibleAppWithoutTruncation() {
        val apps = (0 until 320).map {
            InstalledApp(packageName = "com.example.app%03d".format(it))
        } + InstalledApp(packageName = "com.example.zzz.oncard", sourceUuid = CARD)

        val inventory = MigrationPolicy.inventoryApps(apps, CARD)

        assertEquals(321, inventory.size)
        assertTrue(inventory.any { it.packageName == "com.example.zzz.oncard" })
        assertEquals(
            inventory.map { it.packageName }.sorted(),
            inventory.map { it.packageName },
        )
    }

    @Test
    fun hiddenSystemAndPreinstalledAppsStayExcludedEvenLateInTheAlphabet() {
        val apps = listOf(
            InstalledApp(packageName = "com.example.user"),
            InstalledApp(
                packageName = "com.example.zzz.system",
                isSystem = true,
                sourceUuid = CARD,
            ),
            InstalledApp(
                packageName = "com.example.zzz.updated",
                isUpdatedSystem = true,
                sourceUuid = CARD,
            ),
            InstalledApp(
                packageName = "com.example.zzz.oem",
                isPreinstalled = true,
                sourceUuid = CARD,
            ),
            InstalledApp(packageName = MigrationPolicy.ALLOCATOR_PACKAGE),
        )

        assertEquals(
            listOf("com.example.user"),
            MigrationPolicy.inventoryApps(apps, CARD).map { it.packageName },
        )
    }

    @Test
    fun lateOnCardAppStaysReachableAndMovableBack() {
        val late = InstalledApp(packageName = "com.example.zzz.card", sourceUuid = CARD)
        val apps = (0 until 305).map {
            InstalledApp(packageName = "com.example.a%03d".format(it))
        } + late

        val inventory = MigrationPolicy.inventoryApps(apps, CARD)
        val found = inventory.first { it.packageName == late.packageName }

        assertEquals(306, inventory.size)
        assertTrue(MigrationPolicy.isOnSelectedCard(found, CARD))
        assertTrue(MigrationPolicy.eligibleForInternal(found, CARD))
    }
}
