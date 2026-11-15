package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side checks of the binder-witnessed framework identity used on releases
 * that do not publish the system-server start tuple (Android 8, `API 26`).
 */
class BinderFrameworkIdentityTest {

    private companion object {
        const val BOOT_A = "3f8a1c2e-9b4d-4a61-8f2e-0c5d7b1a6e93"
        const val BOOT_B = "a71d5e0c-2c48-4f9b-b3a7-6d81c4f2b0e5"
        const val WITNESS_1 = "11111111-1111-4111-8111-111111111111"
        const val WITNESS_2 = "22222222-2222-4222-8222-222222222222"
    }

    private fun witnessFactory(vararg tokens: String): () -> String {
        val remaining = tokens.toMutableList()
        return { remaining.removeAt(0) }
    }

    @Test
    fun sameLiveBinderAndBootKeepOneStableIdentity() {
        val identity = BinderFrameworkIdentity(witnessFactory(WITNESS_1))
        val binder = Any()
        val first = identity.observe(BOOT_A, binder, alive = true)
        val second = identity.observe(BOOT_A, binder, alive = true)
        assertEquals(first, second)
        assertEquals("$BOOT_A:binder:$WITNESS_1", first)
        assertTrue(FrameworkInstanceId.matches(first, second))
    }

    @Test
    fun anotherBinderOnTheSameBootRotatesTheWitness() {
        val identity = BinderFrameworkIdentity(witnessFactory(WITNESS_1, WITNESS_2))
        val first = identity.observe(BOOT_A, Any(), alive = true)
        val second = identity.observe(BOOT_A, Any(), alive = true)
        assertNotEquals(first, second)
        assertFalse(FrameworkInstanceId.matches(first, second))
        assertEquals("$BOOT_A:binder:$WITNESS_1", first)
        assertEquals("$BOOT_A:binder:$WITNESS_2", second)
    }

    @Test
    fun aRecreatedReaderNeverMatchesTheOldObservation() {
        val first = BinderFrameworkIdentity(witnessFactory(WITNESS_1))
        val second = BinderFrameworkIdentity(witnessFactory(WITNESS_2))
        val binder = Any()
        val old = first.observe(BOOT_A, binder, alive = true)
        val recreated = second.observe(BOOT_A, binder, alive = true)
        assertNotEquals(old, recreated)
        assertFalse(FrameworkInstanceId.matches(old, recreated))
    }

    @Test
    fun anotherBootRotatesTheWitnessButKeepsTheNewKernelBoot() {
        val identity = BinderFrameworkIdentity(witnessFactory(WITNESS_1, WITNESS_2))
        val binder = Any()
        assertEquals("$BOOT_A:binder:$WITNESS_1", identity.observe(BOOT_A, binder, alive = true))
        val rebooted = identity.observe(BOOT_B, binder, alive = true)
        assertEquals("$BOOT_B:binder:$WITNESS_2", rebooted)
        assertEquals(BOOT_B, FrameworkInstanceId.bootIdOf(rebooted))
    }

    @Test
    fun aDeadOrMissingBinderIsNeverAnIdentity() {
        val identity = BinderFrameworkIdentity(witnessFactory(WITNESS_1, WITNESS_2))
        val binder = Any()
        assertEquals("$BOOT_A:binder:$WITNESS_1", identity.observe(BOOT_A, binder, alive = true))
        assertNull(identity.observe(BOOT_A, binder, alive = false))
        assertNull(identity.observe(BOOT_A, null, alive = true))
        // Clearing the observation also drops the old witness.
        assertEquals("$BOOT_A:binder:$WITNESS_2", identity.observe(BOOT_A, binder, alive = true))
    }

    @Test
    fun anInvalidBootIsNeverAnIdentity() {
        val identity = BinderFrameworkIdentity(witnessFactory(WITNESS_1))
        assertNull(identity.observe(null, Any(), alive = true))
        assertNull(identity.observe("boot", Any(), alive = true))
        assertNull(identity.observe("", Any(), alive = true))
    }

    @Test
    fun aNumericNamespaceNeverMatchesTheBinderWitness() {
        val binderId = BinderFrameworkIdentity(witnessFactory(WITNESS_1))
            .observe(BOOT_A, Any(), alive = true)
        val numeric = FrameworkInstanceId.create(BOOT_A, "1", "5921")
        assertNotEquals(numeric, binderId)
        assertFalse(FrameworkInstanceId.matches(numeric, binderId))
        assertFalse(FrameworkInstanceId.matches(binderId, numeric))
    }
}
