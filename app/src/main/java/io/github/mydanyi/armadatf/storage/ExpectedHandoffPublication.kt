package io.github.mydanyi.armadatf.storage

/**
 * The compare-and-publish transaction behind [HandoffWriter.retryExpected].
 *
 * Every step runs inside [lock]: the pending bytes are read, compared
 * byte-for-byte with [expected] and only an exact match is handed to
 * [publish]. A missing or already replaced pending payload returns `null`
 * without publishing, so an older recovery can never overwrite a newer
 * handoff. A publisher failure propagates unchanged.
 */
internal fun <T> publishExpectedHandoff(
    lock: Any,
    expected: ByteArray,
    readPending: () -> ByteArray?,
    publish: (ByteArray) -> T,
): T? = synchronized(lock) {
    val pending = readPending() ?: return@synchronized null
    if (!pending.contentEquals(expected)) return@synchronized null
    publish(pending)
}
