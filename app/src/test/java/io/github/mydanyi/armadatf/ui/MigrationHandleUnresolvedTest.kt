package io.github.mydanyi.armadatf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun handle(
        state: String,
        moveId: Int? = 7,
        bootId: String? = null,
        frameworkId: String? = null,
    ) = MigrationHandle(
        moveId = moveId,
        diskId = "disk-1",
        kind = "primary",
        targetUuid = "internal",
        packageName = null,
        sourceUuid = "internal",
        privateUuid = "1111-2222",
        state = state,
        bootId = bootId,
        frameworkId = frameworkId,
    )

    private companion object {
        const val BOOT_A = "3f8a1c2e-9b4d-4a61-8f2e-0c5d7b1a6e93"
        const val BOOT_B = "a71d5e0c-2c48-4f9b-b3a7-6d81c4f2b0e5"

        /** Same kernel boot, later framework start: every move was renumbered. */
        const val FW_A = "3f8a1c2e-9b4d-4a61-8f2e-0c5d7b1a6e93:1:5921"
        const val FW_B = "3f8a1c2e-9b4d-4a61-8f2e-0c5d7b1a6e93:2:1834"

        /** Another kernel boot that happens to report the same counters. */
        const val FW_C = "a71d5e0c-2c48-4f9b-b3a7-6d81c4f2b0e5:1:5921"
    }

    // --- framework-instance polling policy -----------------------------------

    @Test
    fun legacyRetainedIdWithoutFrameworkProofNeverPollsInReady() {
        // The saved handle names id 7 but no framework instance, so nothing
        // ties it to a native task: not even a READY transition may poll it.
        val legacy = handle("running", moveId = 7, bootId = BOOT_A)
        assertFalse(legacy.canPollInFramework(FW_A))

        // The production baseline stores the live boot and drops the unproven
        // id, so the request stays unresolved and unpollable.
        val baseline = legacy.withRecoveryBaseline(BOOT_A)
        assertNull(baseline.moveId)
        assertNull(baseline.frameworkId)
        assertEquals(BOOT_A, baseline.bootId)
        assertTrue(baseline.isUnresolved)
        assertFalse(baseline.canPollInFramework(FW_A))
    }

    @Test
    fun baselineDropsUnknownLegacyIdEvenWithoutALiveBoot() {
        val legacy = handle("starting", moveId = 7, bootId = null)
        val baseline = legacy.withRecoveryBaseline(null)
        assertNull(baseline.moveId)
        assertNull(baseline.bootId)
        assertTrue(baseline.isUnresolved)
        // Request and card identity are preserved for a later recovery.
        assertEquals(legacy.diskId, baseline.diskId)
        assertEquals(legacy.kind, baseline.kind)
        assertEquals(legacy.targetUuid, baseline.targetUuid)
        assertEquals(legacy.sourceUuid, baseline.sourceUuid)
        assertEquals(legacy.privateUuid, baseline.privateUuid)
        assertEquals(legacy.packageName, baseline.packageName)
        assertFalse(baseline.canPollInFramework(FW_A))
        assertFalse(baseline.hasRebooted(BOOT_A))
    }

    @Test
    fun malformedLiveBootStillDropsTheLegacyId() {
        val baseline = handle("unverified", moveId = 7, bootId = null)
            .withRecoveryBaseline("not-a-uuid")
        assertNull(baseline.moveId)
        assertNull(baseline.bootId)
        assertTrue(baseline.isUnresolved)
    }

    @Test
    fun recoveryBaselineNeverPollsTheOldResultAndLaterBootOnlyProvesTheReboot() {
        val baseline = handle("unverified", moveId = 7, bootId = BOOT_A, frameworkId = FW_B)
            .withRecoveryBaseline(BOOT_A)
        assertNull(baseline.frameworkId)
        assertFalse(baseline.canPollInFramework(FW_A))
        assertFalse(baseline.canPollInFramework(FW_B))
        // A later full reboot is real proof, but the dropped id stays dropped.
        assertTrue(baseline.hasRebooted(BOOT_B))
        assertFalse(baseline.canPollInFramework(FW_C))
    }

    @Test
    fun matchingFrameworkAcceptsTheOriginalZeroAndSeven() {
        assertTrue(handle("running", moveId = 0, frameworkId = FW_A).canPollInFramework(FW_A))
        assertTrue(handle("unverified", moveId = 7, frameworkId = FW_A).canPollInFramework(FW_A))
    }

    @Test
    fun anotherFrameworkInstanceCannotReleaseIdZero() {
        // A framework-only restart keeps the kernel boot, reports a new start
        // count and renumbers moves from 0, so the same number now names an
        // unrelated move: its failure must not release the saved id.
        val saved = handle("running", moveId = 0, bootId = BOOT_A, frameworkId = FW_A)
        assertFalse(saved.canPollInFramework(FW_B))
        assertFalse(saved.canPollInFramework(FW_C))
        assertFalse(saved.canPollInFramework(null))
        assertFalse(saved.canPollInFramework(""))
        assertFalse(saved.canPollInFramework("not-an-identity"))
        assertFalse(handle("running", moveId = 7, frameworkId = "boot").canPollInFramework("boot"))
        assertFalse(handle("running", moveId = 7, frameworkId = null).canPollInFramework(FW_A))
    }

    @Test
    fun frameworkOnlyRestartIsNeverAReboot() {
        // Same kernel boot, later start count: the old native task may still be
        // running inside the new instance, so this never looks like a reboot.
        val saved = handle("running", moveId = 0, bootId = BOOT_A, frameworkId = FW_B)
        assertFalse(saved.hasRebooted(BOOT_A))
        assertTrue(saved.hasRebooted(BOOT_B))
    }

    @Test
    fun frameworkIdentitySuppliesTheBootIdWhenTheFileReadFailed() {
        // The privileged tuple carries the same kernel boot UUID, so a failed
        // app-side boot_id read must not turn a framework restart into a reboot.
        val saved = handle("unverified", moveId = 0, bootId = null, frameworkId = FW_A)
        assertFalse(saved.hasRebooted(BOOT_A))
        assertTrue(saved.hasRebooted(BOOT_B))
    }

    @Test
    fun onlyAnUnownedLegacyHandleMayBaseline() {
        assertTrue(handle("running", moveId = 0).isUnprovenLegacy(processOwnedMoveId = false))
        // The exact id this process obtained is preserved even without a boot
        // or framework proof: the start reply already bound it here.
        assertFalse(handle("running", moveId = 0).isUnprovenLegacy(processOwnedMoveId = true))
        assertFalse(
            handle("running", moveId = 0, frameworkId = FW_A)
                .isUnprovenLegacy(processOwnedMoveId = false),
        )
        assertFalse(handle("completed", moveId = 0).isUnprovenLegacy(processOwnedMoveId = false))
    }

    @Test
    fun terminalUnknownStartingAndIdlessStatesAreNeverPollable() {
        assertFalse(handle("starting", moveId = 7, frameworkId = FW_A).canPollInFramework(FW_A))
        assertFalse(handle("completed", moveId = 7, frameworkId = FW_A).canPollInFramework(FW_A))
        assertFalse(handle("failed", moveId = 7, frameworkId = FW_A).canPollInFramework(FW_A))
        assertFalse(handle("unknown", moveId = 7, frameworkId = FW_A).canPollInFramework(FW_A))
        assertFalse(handle("running", moveId = null, frameworkId = FW_A).canPollInFramework(FW_A))
        assertFalse(handle("running", moveId = -1, frameworkId = FW_A).canPollInFramework(FW_A))
    }

    @Test
    fun zeroIsALegitimateNativeMoveId() {
        // AOSP numbers moves from 0, so only negatives are invalid.
        assertTrue(MigrationHandle.isValidNativeMoveId(0))
        assertTrue(MigrationHandle.isValidNativeMoveId(7))
        assertFalse(MigrationHandle.isValidNativeMoveId(null))
        assertFalse(MigrationHandle.isValidNativeMoveId(-1))
        assertFalse(MigrationHandle.isValidNativeMoveId(Int.MIN_VALUE))
        val zero = handle("running", moveId = 0, frameworkId = FW_A)
        assertTrue(zero.canPollInFramework(FW_A))
        assertTrue(zero.isUnresolved)
    }

    @Test
    fun baselineKeepsAnAlreadyKnownOriginalBootId() {
        // A stored boot id names the boot the request came from; a later live
        // value must not overwrite it and hide a real reboot.
        val baseline = handle("unverified", moveId = 7, bootId = BOOT_A)
            .withRecoveryBaseline(BOOT_B)
        assertNull(baseline.moveId)
        assertEquals(BOOT_A, baseline.bootId)
    }

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

    @Test
    fun sameKernelBootNeverRecoversEvenWhenTheDestinationCouldMatch() {
        // StorageManagerService may commit the primary target at COPY_FINISHED
        // before cleanup, so a fresh destination match inside one boot proves
        // nothing about an id-less request. A retained id also keeps polling.
        assertFalse(handle("starting", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_A))
        assertFalse(handle("running", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_A))
        assertFalse(handle("unverified", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_A))
        assertFalse(handle("unverified", moveId = 7, bootId = BOOT_A).hasRebooted(BOOT_A))
    }

    @Test
    fun differentValidBootRecoversIdlessAndKnownIdUnresolvedRequests() {
        // A full reboot ended any old native task; framework move ids restart
        // and may be reused after boot, so a retained id does not override it.
        assertTrue(handle("starting", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_B))
        assertTrue(handle("running", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_B))
        assertTrue(handle("unverified", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_B))
        assertTrue(handle("unverified", moveId = 7, bootId = BOOT_A).hasRebooted(BOOT_B))
    }

    @Test
    fun missingOrMalformedBootIdIsNeverRebootProof() {
        assertFalse(handle("unverified", moveId = null, bootId = null).hasRebooted(BOOT_B))
        assertFalse(handle("unverified", moveId = null, bootId = BOOT_A).hasRebooted(null))
        assertFalse(handle("unverified", moveId = null, bootId = BOOT_A).hasRebooted(""))
        assertFalse(handle("unverified", moveId = null, bootId = BOOT_A).hasRebooted("not-a-uuid"))
        assertFalse(handle("unverified", moveId = null, bootId = "boot-one").hasRebooted("boot-two"))
        assertFalse(
            handle("unverified", moveId = null, bootId = "3f8a1c2e9b4d4a618f2e0c5d7b1a6e93")
                .hasRebooted(BOOT_B),
        )
    }

    @Test
    fun legacyHandleWithoutBaselineCannotRecoverUntilOneIsStored() {
        // The production baseline is AppSettings.currentBootId(); only a valid
        // canonical id is stored, and that same boot never counts as a reboot.
        val legacy = handle("starting", moveId = null, bootId = null)
        assertFalse(legacy.hasRebooted(BOOT_A))

        val baseline = legacy.copy(
            state = "unverified",
            bootId = MigrationHandle.canonicalBootId(BOOT_A),
        )
        assertFalse(baseline.hasRebooted(BOOT_A))
        assertTrue(baseline.hasRebooted(BOOT_B))
    }

    @Test
    fun terminalAndUnknownStatesNeverRecoverAfterAReboot() {
        assertFalse(handle("failed", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_B))
        assertFalse(handle("completed", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_B))
        assertFalse(handle("unknown", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_B))
        assertFalse(handle("COMPLETED", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_B))
        assertFalse(handle("running ", moveId = null, bootId = BOOT_A).hasRebooted(BOOT_B))
    }

    @Test
    fun unreadableOrMissingLocationNeverVerifiesAnyDestination() {
        // verifySavedDestination passes null when the package cannot be read and
        // a blank primary UUID when no private volume is exposed. Neither may
        // fall back to internal: an unobserved location is UNKNOWN, not a match.
        val card = "1b6f0d3a-5a4e-4c2b-9d47-2f8e6a1c0b95"
        assertFalse(migrationLocationMatches(null, "internal"))
        assertFalse(migrationLocationMatches(null, card))
        assertFalse(migrationLocationMatches("", "internal"))
        assertFalse(migrationLocationMatches("", card))
        assertFalse(migrationLocationMatches("   ", "internal"))
        assertFalse(migrationLocationMatches("   ", card))
        assertFalse(migrationLocationMatches(card, "internal"))
    }

    @Test
    fun blankExpectedLocationIsUnknownNotInternal() {
        // An old handle may persist an empty target; the removed internal
        // shortcut treated it as "internal" and verified a missing observation.
        assertFalse(migrationLocationMatches("internal", ""))
        assertFalse(migrationLocationMatches("internal", "   "))
        assertFalse(migrationLocationMatches(null, ""))
    }

    @Test
    fun explicitInternalAliasesStillMatch() {
        // Native Overview and appStorageUuid spell a real internal location
        // with these markers, so the aliases keep matching each other.
        assertTrue(migrationLocationMatches("internal", "internal"))
        assertTrue(migrationLocationMatches("primary", "internal"))
        assertTrue(migrationLocationMatches("private", "primary"))
        assertTrue(migrationLocationMatches("INTERNAL", "internal"))
        assertTrue(migrationLocationMatches("internal", " PRIVATE "))
    }

    @Test
    fun externalUuidMatchesCaseInsensitivelyAndIgnoresHyphens() {
        val uuid = "1b6f0d3a-5a4e-4c2b-9d47-2f8e6a1c0b95"
        assertTrue(migrationLocationMatches(uuid, uuid))
        assertTrue(migrationLocationMatches(uuid.uppercase(), uuid))
        assertTrue(migrationLocationMatches(uuid.replace("-", ""), uuid))
        assertTrue(migrationLocationMatches("  $uuid  ", uuid))
        assertFalse(
            migrationLocationMatches("2c7e1f4b-6b5f-4d3c-8e58-3a9f7b2d1c06", uuid),
        )
        assertFalse(migrationLocationMatches("internal", uuid))
    }

    @Test
    fun bootIdNormalisationAcceptsOnlyCanonicalUuids() {
        assertEquals(BOOT_A, MigrationHandle.canonicalBootId("  $BOOT_A\n"))
        assertNull(MigrationHandle.canonicalBootId(null))
        assertNull(MigrationHandle.canonicalBootId(""))
        assertNull(MigrationHandle.canonicalBootId("   "))
        assertNull(MigrationHandle.canonicalBootId("3f8a1c2e9b4d4a618f2e0c5d7b1a6e93"))
    }

    // --- privileged boot fallback for reboot recovery -----------------------

    @Test
    fun privilegedFrameworkSuppliesTheBootProofWhenTheAppReadIsDenied() {
        // The app-side boot_id read is denied (null), but the privileged
        // framework identity names a different kernel boot, so a saved request
        // is proved rebooted from its own fallback boot UUID as well.
        val current = currentMigrationBootId(null, FW_C)
        assertEquals(BOOT_B, current)

        val savedFallback = handle("unverified", moveId = 0, bootId = null, frameworkId = FW_A)
        assertTrue(savedFallback.hasRebooted(current))

        val savedStoredBoot = handle("running", moveId = 7, bootId = BOOT_A)
        assertTrue(savedStoredBoot.hasRebooted(current))
    }

    @Test
    fun sameKernelWithRenumberedFrameworkCountersIsStillNotAReboot() {
        // A framework-only restart keeps the kernel boot UUID and only changes
        // the start count, so the privileged fallback proves no reboot.
        val current = currentMigrationBootId(null, FW_B)
        assertEquals(BOOT_A, current)

        val saved = handle("unverified", moveId = 0, bootId = null, frameworkId = FW_A)
        assertFalse(saved.hasRebooted(current))
    }

    @Test
    fun unknownOrMalformedCurrentBootMetadataStaysUnproven() {
        assertNull(currentMigrationBootId(null, null))
        assertNull(currentMigrationBootId("not-a-uuid", null))
        assertNull(currentMigrationBootId("3f8a1c2e9b4d4a618f2e0c5d7b1a6e93", null))

        val saved = handle("unverified", moveId = 0, bootId = BOOT_A)
        assertFalse(saved.hasRebooted(currentMigrationBootId(null, null)))
        // A saved handle without any proof is never recovered either.
        assertFalse(
            handle("unverified", moveId = 0, bootId = null)
                .hasRebooted(currentMigrationBootId(null, null)),
        )
    }

    @Test
    fun aReadableAppBootIdIsPreferredOverThePrivilegedFallback() {
        // The canonical app-side read wins; the framework fallback must not
        // override a valid value with the boot it happens to carry.
        assertEquals(BOOT_B, currentMigrationBootId(BOOT_B, FW_A))
        assertEquals(BOOT_A, currentMigrationBootId(BOOT_A, FW_C))

        val saved = handle("unverified", moveId = 0, bootId = BOOT_A)
        assertTrue(saved.hasRebooted(currentMigrationBootId(BOOT_B, FW_A)))
        assertFalse(saved.hasRebooted(currentMigrationBootId(BOOT_A, FW_C)))
    }
}
