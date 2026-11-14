package io.github.mydanyi.armadatf.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake stand-in for the platform `UserInfo`; the mapper reads its public `id`. */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class FakeUserInfo {
    @JvmField var id: Int = 0
}

/** Fake user object without an `id`, so the mapper must reject it. */
@Suppress("unused")
class FakeUserWithoutId {
    @JvmField var name: String = "user"
}

/**
 * Host-side tests for the privileged user enumeration seam in [NativeMigration].
 * No binder call happens: only the pure mapping and argument shaping are used.
 */
class NativeMigrationUserMappingTest {

    @Test
    fun ownerAndProfileIdsAreMapped() {
        val users = listOf(
            FakeUserInfo().apply { id = 0 },
            FakeUserInfo().apply { id = 10 },
        )

        assertEquals(listOf(0, 10), NativeMigration().userIdsFromList(users))
    }

    @Test
    fun duplicateIdsAreFolded() {
        val users = listOf(
            FakeUserInfo().apply { id = 0 },
            FakeUserInfo().apply { id = 0 },
        )

        assertEquals(listOf(0), NativeMigration().userIdsFromList(users))
    }

    @Test
    fun emptyOrMissingUserListIsRejected() {
        fails { NativeMigration().userIdsFromList(null) }
        fails { NativeMigration().userIdsFromList(emptyList<Any?>()) }
    }

    @Test
    fun nullEntryIsRejected() {
        fails { NativeMigration().userIdsFromList(listOf(null)) }
    }

    @Test
    fun missingOrNegativeIdIsRejected() {
        fails { NativeMigration().userIdsFromList(listOf(FakeUserWithoutId())) }
        fails { NativeMigration().userIdsFromList(listOf(FakeUserInfo().apply { id = -1 })) }
    }

    @Test
    fun callerMustBeReported() {
        fails { NativeMigration().requireUserList(listOf(0), callerUserId = 10) }
        assertEquals(listOf(0, 10), NativeMigration().requireUserList(listOf(0, 10), 10))
    }

    @Test
    fun supportedGetUsersAritiesSelectAllUsers() {
        for (arity in 1..3) {
            val args = NativeMigration().userQueryArgs(arity)
            assertEquals(arity, args.size)
            assertTrue(args.all { it == false })
        }
        fails { NativeMigration().userQueryArgs(0) }
        fails { NativeMigration().userQueryArgs(4) }
    }

    private fun fails(block: () -> Unit) {
        try {
            block()
        } catch (e: NativeMigrationException) {
            return
        }
        throw AssertionError("expected NativeMigrationException")
    }
}
