package io.github.mydanyi.armadatf.domain

import java.util.UUID

/**
 * Stable identity of one running Android framework instance.
 *
 * The kernel boot id alone does not identify a `PackageManagerService`: a
 * framework-only restart keeps the kernel boot but resets every native move id,
 * so a numeric id restored from disk can name an unrelated failed move of a
 * newer instance (including id 0). `SystemServer` publishes
 * `sys.system_server.start_count` (incremented on every framework restart) and
 * `sys.system_server.start_elapsed` before the package manager is bootstrapped,
 * so the tuple below witnesses that a numeric id namespace is still the same
 * one that issued the id.
 *
 * Two canonical forms name one instance. The
 * `<boot-uuid>:<start-count>:<start-elapsed-ms>` form is the full lowercase
 * kernel boot UUID, the positive system-server start count and the nonnegative
 * elapsed milliseconds since framework start, joined by a fixed `:`. The
 * `<boot-uuid>:binder:<witness-uuid>` form witnesses one live package-service
 * binder on releases that do not publish that tuple (Android 8, `API 26`); the
 * witness UUID is rotated for every new binder object or kernel boot.
 * Anything missing, unparsable, out of range or differently shaped is not an
 * identity, so [canonical] returns `null` and [matches] is `false`.
 *
 * This is a correctness witness only: the identity names the framework instance
 * that is running, never that an old native task ended. Only a real kernel boot
 * change proves that (see `MigrationHandle.hasRebooted`).
 */
object FrameworkInstanceId {

    /** Fixed field separator; a canonical UUID and decimal digits never contain it. */
    const val DELIMITER = ":"

    /** Second-field marker of the binder-witnessed identity form. */
    private const val BINDER_MARKER = "binder"

    private const val FIELD_COUNT = 3

    /**
     * Builds the canonical identity from the raw kernel boot id and the two raw
     * system properties, or `null` when any field is missing or malformed.
     *
     * The boot id must be a full canonical UUID. The start count must be a
     * positive decimal integer and the elapsed value a nonnegative one; both are
     * parsed with overflow-safe `Long` arithmetic, so an oversized or negative
     * value is rejected instead of wrapping.
     */
    fun create(
        kernelBootId: String?,
        startCountRaw: String?,
        startElapsedRaw: String?,
    ): String? {
        val bootId = canonicalBootUuid(kernelBootId) ?: return null
        val count = decimal(startCountRaw, minimum = 1L) ?: return null
        val elapsed = decimal(startElapsedRaw, minimum = 0L) ?: return null
        return bootId + DELIMITER + count + DELIMITER + elapsed
    }

    /**
     * Builds the canonical binder-witnessed identity from the kernel boot id
     * and a witness UUID, or `null` when either is missing or malformed.
     *
     * The witness UUID is generated for one live package-service binder on one
     * kernel boot, so this form names that framework instance without relying
     * on the system-server tuple. It never proves that an old native task
     * ended; only a real kernel boot change does that.
     */
    fun createBinder(
        kernelBootId: String?,
        witnessId: String?,
    ): String? {
        val bootId = canonicalBootUuid(kernelBootId) ?: return null
        val witness = canonicalBootUuid(witnessId) ?: return null
        return bootId + DELIMITER + BINDER_MARKER + DELIMITER + witness
    }

    /**
     * Canonical form of [raw], or `null` unless every field is present, well
     * formed and in range. Re-emitting the parsed numbers drops leading zeros
     * and padding, so two spellings of one tuple collapse to one identity while
     * arbitrary text never becomes one.
     */
    fun canonical(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty()) return null
        val parts = value.split(DELIMITER)
        if (parts.size != FIELD_COUNT) return null
        if (parts[1] == BINDER_MARKER) return createBinder(parts[0], parts[2])
        return create(parts[0], parts[1], parts[2])
    }

    /**
     * True only when both sides are canonical identities of the same framework
     * instance. A missing or malformed side never matches, so `null`/`null` is
     * not proof of anything.
     */
    fun matches(expected: String?, actual: String?): Boolean {
        if (expected == null || actual == null) return false
        val left = canonical(expected) ?: return false
        val right = canonical(actual) ?: return false
        return left == right
    }

    /**
     * The canonical kernel boot UUID carried by [raw], or `null` when [raw] is
     * not a canonical framework identity. This is the same value the kernel
     * exposes, so it may stand in for an unreadable `boot_id` file.
     */
    fun bootIdOf(raw: String?): String? = canonical(raw)?.substringBefore(DELIMITER)

    /** Canonical lowercase full UUID, or `null` for anything else. */
    fun canonicalBootUuid(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty()) return null
        val parsed = try {
            UUID.fromString(value)
        } catch (t: IllegalArgumentException) {
            return null
        }
        return parsed.toString().takeIf { it == value.lowercase() }
    }

    /**
     * Parses an unsigned decimal integer of at least [minimum].
     *
     * Only ASCII digits are accepted; a sign, an empty string or any other
     * character is rejected. `toLongOrNull` refuses a value that would overflow
     * a `Long`, so no arithmetic wraps around.
     */
    private fun decimal(raw: String?, minimum: Long): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!value.all { it in '0'..'9' }) return null
        val parsed = value.toLongOrNull() ?: return null
        return parsed.takeIf { it >= minimum }
    }
}
