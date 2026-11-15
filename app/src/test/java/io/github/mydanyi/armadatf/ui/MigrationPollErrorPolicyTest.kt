package io.github.mydanyi.armadatf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationPollErrorPolicyTest {

    @Test
    fun destinationUnverifiedVerifiesTheSavedDestination() {
        assertEquals(
            MigrationPollErrorAction.VERIFY_DESTINATION,
            MigrationPollErrorPolicy.actionFor("destination_unverified"),
        )
    }

    @Test
    fun serviceNotReadyWaitsForConnection() {
        assertEquals(
            MigrationPollErrorAction.WAIT_FOR_CONNECTION,
            MigrationPollErrorPolicy.actionFor("service_not_ready"),
        )
    }

    @Test
    fun callFailuresKeepTheRequestUnverified() {
        val codes = listOf(
            "internal_error",
            "permission_denied",
            "native_storage_unavailable",
            "native_migration_unavailable",
            "card_changed",
            "allocation_failed",
            "verification_timeout",
            "removed_backend_error",
            "future_backend_error",
        )
        for (code in codes) {
            assertEquals(
                "error code $code",
                MigrationPollErrorAction.KEEP_UNVERIFIED,
                MigrationPollErrorPolicy.actionFor(code),
            )
        }
    }

    @Test
    fun specialCodesMustMatchExactly() {
        val codes = listOf(
            "destination_unverified_extra",
            "service_not_ready_extra",
            "Destination_Unverified",
        )
        for (code in codes) {
            assertEquals(
                "error code $code",
                MigrationPollErrorAction.KEEP_UNVERIFIED,
                MigrationPollErrorPolicy.actionFor(code),
            )
        }
    }

    @Test
    fun policyCannotFailOrReleaseTheRequest() {
        assertEquals(
            setOf(
                MigrationPollErrorAction.VERIFY_DESTINATION,
                MigrationPollErrorAction.WAIT_FOR_CONNECTION,
                MigrationPollErrorAction.KEEP_UNVERIFIED,
            ),
            MigrationPollErrorAction.values().toSet(),
        )
    }
}
