package io.github.mydanyi.armadatf.ui

/**
 * Decision for a failed migration-status poll on an unresolved request.
 *
 * A failed IPC call cannot prove that the native move ended: a real failure
 * arrives as a parsed migration status with state failed. Only the two explicit
 * protocol answers are special; every other error keeps the saved handle
 * unresolved.
 */
internal enum class MigrationPollErrorAction {
    VERIFY_DESTINATION,
    WAIT_FOR_CONNECTION,
    KEEP_UNVERIFIED,
}

internal object MigrationPollErrorPolicy {
    fun actionFor(errorCode: String): MigrationPollErrorAction = when (errorCode) {
        "destination_unverified" -> MigrationPollErrorAction.VERIFY_DESTINATION
        "service_not_ready" -> MigrationPollErrorAction.WAIT_FOR_CONNECTION
        else -> MigrationPollErrorAction.KEEP_UNVERIFIED
    }
}
