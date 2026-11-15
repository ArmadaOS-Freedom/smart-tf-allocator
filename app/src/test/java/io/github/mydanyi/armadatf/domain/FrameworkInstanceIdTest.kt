package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side checks of the real production identity policy: a framework-only
 * restart keeps the kernel boot id but renumbers every native move, so the
 * tuple of boot UUID, system-server start count and start time is what proves
 * a numeric id namespace.
 */
class FrameworkInstanceIdTest {

    private companion object {
        const val BOOT_A = "3f8a1c2e-9b4d-4a61-8f2e-0c5d7b1a6e93"
        const val BOOT_B = "a71d5e0c-2c48-4f9b-b3a7-6d81c4f2b0e5"
    }

    @Test
    fun canonicalTupleUsesTheFullBootIdCountAndElapsed() {
        assertEquals(
            "$BOOT_A:1:5921",
            FrameworkInstanceId.create(BOOT_A, "1", "5921"),
        )
        assertEquals(
            "$BOOT_A:7:0",
            FrameworkInstanceId.create("$BOOT_A\n", " 7 ", "0"),
        )
    }

    @Test
    fun sameKernelBootWithAnotherStartCountIsAnotherInstance() {
        val first = FrameworkInstanceId.create(BOOT_A, "1", "5921")
        val restarted = FrameworkInstanceId.create(BOOT_A, "2", "5921")
        val laterElapsed = FrameworkInstanceId.create(BOOT_A, "1", "5922")
        assertNotEquals(first, restarted)
        assertNotEquals(first, laterElapsed)
        assertFalse(FrameworkInstanceId.matches(first, restarted))
        assertFalse(FrameworkInstanceId.matches(first, laterElapsed))
    }

    @Test
    fun differentKernelBootDiffersEvenWithTheSameNumbers() {
        val a = FrameworkInstanceId.create(BOOT_A, "1", "5921")
        val b = FrameworkInstanceId.create(BOOT_B, "1", "5921")
        assertNotEquals(a, b)
        assertFalse(FrameworkInstanceId.matches(a, b))
    }

    @Test
    fun theSameInstanceMatchesItselfAfterACanonicalRoundTrip() {
        val created = FrameworkInstanceId.create(BOOT_A, "1", "5921")
        assertEquals(created, FrameworkInstanceId.canonical("$BOOT_A:0001:5921\n"))
        assertTrue(FrameworkInstanceId.matches(created, "$BOOT_A:1:5921"))
        assertTrue(FrameworkInstanceId.matches(created, created))
    }

    @Test
    fun missingOrMalformedFieldsAreNeverAnIdentity() {
        assertNull(FrameworkInstanceId.create(null, "1", "5921"))
        assertNull(FrameworkInstanceId.create(BOOT_A, null, "5921"))
        assertNull(FrameworkInstanceId.create(BOOT_A, "1", null))
        // The start count is incremented from 1 before PackageManager exists.
        assertNull(FrameworkInstanceId.create(BOOT_A, "0", "5921"))
        assertNull(FrameworkInstanceId.create(BOOT_A, "-1", "5921"))
        assertNull(FrameworkInstanceId.create(BOOT_A, "1", "-1"))
        assertNull(FrameworkInstanceId.create(BOOT_A, "1.5", "5921"))
        assertNull(FrameworkInstanceId.create(BOOT_A, "", "5921"))
        assertNull(FrameworkInstanceId.create(BOOT_A, "1", "  "))
        assertNull(FrameworkInstanceId.create("boot", "1", "5921"))
        // A value that would overflow a Long is refused instead of wrapping.
        assertNull(FrameworkInstanceId.create(BOOT_A, "99999999999999999999", "5921"))
        assertNull(FrameworkInstanceId.create(BOOT_A, "1", "99999999999999999999"))
    }

    @Test
    fun canonicalRequiresExactlyThreeFields() {
        assertNull(FrameworkInstanceId.canonical(null))
        assertNull(FrameworkInstanceId.canonical(""))
        assertNull(FrameworkInstanceId.canonical(BOOT_A))
        assertNull(FrameworkInstanceId.canonical("$BOOT_A:1"))
        assertNull(FrameworkInstanceId.canonical("$BOOT_A:1:5921:2"))
        assertNull(FrameworkInstanceId.canonical("$BOOT_A::5921"))
        assertNull(FrameworkInstanceId.canonical("$BOOT_A:1:"))
        assertNull(FrameworkInstanceId.canonical("boot:1:5921"))
    }

    @Test
    fun nullNeverMatchesAnythingIncludingAnotherNull() {
        assertFalse(FrameworkInstanceId.matches(null, null))
        assertFalse(FrameworkInstanceId.matches(null, "$BOOT_A:1:5921"))
        assertFalse(FrameworkInstanceId.matches("$BOOT_A:1:5921", null))
        assertFalse(FrameworkInstanceId.matches(null, ""))
        assertFalse(FrameworkInstanceId.matches("", ""))
    }

    @Test
    fun bootIdOfReturnsOnlyTheCanonicalKernelUuid() {
        assertEquals(BOOT_A, FrameworkInstanceId.bootIdOf("$BOOT_A:1:5921"))
        assertNull(FrameworkInstanceId.bootIdOf(null))
        assertNull(FrameworkInstanceId.bootIdOf(""))
        assertNull(FrameworkInstanceId.bootIdOf("$BOOT_A:1"))
        assertNull(FrameworkInstanceId.bootIdOf("not-an-identity"))
        // The reused UUID validator rejects shortened and padded spellings.
        assertEquals(BOOT_A, FrameworkInstanceId.canonicalBootUuid(" $BOOT_A "))
        assertNull(FrameworkInstanceId.canonicalBootUuid("3f8a1c2e9b4d4a618f2e0c5d7b1a6e93"))
        assertNull(FrameworkInstanceId.canonicalBootUuid("boot"))
    }
}
