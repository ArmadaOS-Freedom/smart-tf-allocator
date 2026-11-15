package io.github.mydanyi.armadatf.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import io.github.mydanyi.armadatf.domain.FrameworkInstanceId
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Durable handle of one started migration: the retained move id plus the exact
 * request that produced it. [state] is one of `starting`, `running`,
 * `completed`, `failed` or `unverified`.
 *
 * The disk id and selected private UUID are stored next to the request so a
 * swapped or missing card can never inherit an old result.
 *
 * [frameworkId] is the canonical identity of the framework instance that
 * issued [moveId]. A numeric id is only meaningful inside that instance, so it
 * is the only admissible proof for polling or settling the id; a missing value
 * never proves one. Its boot UUID also stands in for an unreadable [bootId].
 *
 * [bootId] is the optional kernel boot id captured when the request was
 * persisted. It is the only admissible proof that a later launch runs on a
 * different kernel boot; a missing value never proves one.
 */
data class MigrationHandle(
    val moveId: Int?,
    val diskId: String,
    val kind: String,
    val targetUuid: String,
    val packageName: String?,
    val sourceUuid: String,
    val privateUuid: String?,
    val state: String,
    val errorCode: String? = null,
    val bootId: String? = null,
    /** Canonical framework instance that issued [moveId]; never a guess. */
    val frameworkId: String? = null,
) {

    /**
     * True while this saved request still has to be resolved before anything
     * destructive may start: the native move is still `starting`/`running`, or
     * its destination is `unverified`.
     *
     * Only a definitive native failure or an actually verified destination
     * releases the handle, so an unrecognised or transient persisted state stays
     * unresolved instead of unlocking formatting or a second migration.
     */
    val isUnresolved: Boolean
        get() = when (state) {
            STATE_STARTING, STATE_RUNNING, STATE_UNVERIFIED -> true
            STATE_COMPLETED, STATE_FAILED -> false
            else -> true
        }

    /**
     * True only when this handle is still one a restart may settle (`starting`,
     * `running` or `unverified`) and the kernel boot it was created in differs
     * from [currentBootId]. The saved side is the stored boot id or, when that
     * read failed, the boot UUID inside [frameworkId]. Both sides must be valid
     * canonical UUIDs, so a missing, unreadable or malformed value never proves
     * a reboot.
     *
     * A full reboot definitively ended any old native task, so a retained move
     * id does not override this: framework move ids restart and may be reused
     * after boot. A framework-only restart keeps the kernel boot id and is
     * therefore never a reboot. Terminal or unrecognised states never recover
     * this way.
     */
    fun hasRebooted(currentBootId: String?): Boolean {
        if (state !in RECOVERABLE_STATES) return false
        val saved = canonicalBootId(bootId)
            ?: FrameworkInstanceId.bootIdOf(frameworkId)
            ?: return false
        val current = canonicalBootId(currentBootId) ?: return false
        return saved != current
    }

    /**
     * True only when this persisted handle may be polled or resumed inside the
     * running framework instance: it must still be a live request
     * (`running`/`unverified`), carry a real native move id (framework ids start
     * at 0, so 0 is a legitimate id), and prove the same framework instance.
     *
     * The kernel boot id is not enough on its own: a framework-only restart
     * keeps it while every native move id is renumbered, so only [frameworkId]
     * ties the request to the namespace that issued the id. A missing,
     * unreadable or different instance yields false, so an unproven legacy id
     * is never polled.
     */
    fun canPollInFramework(currentFrameworkId: String?): Boolean {
        if (state != STATE_RUNNING && state != STATE_UNVERIFIED) return false
        if (!isValidNativeMoveId(moveId)) return false
        return FrameworkInstanceId.matches(frameworkId, currentFrameworkId)
    }

    /**
     * Recovery baseline for a legacy request whose saved boot proof is absent
     * or malformed and which no retained native record describes. The retained
     * id and its namespace were never proven, so both are dropped instead of
     * being stamped with the live instance; the request identity and the
     * unresolved state stay. An already known original boot id is preserved,
     * and [liveBootId] is stored only when it is a canonical UUID.
     *
     * The baseline can only support a later full-reboot recovery from the
     * actual source/destination; it can never make the old id pollable again.
     */
    fun withRecoveryBaseline(liveBootId: String?): MigrationHandle =
        copy(
            moveId = null,
            frameworkId = null,
            bootId = canonicalBootId(bootId) ?: canonicalBootId(liveBootId),
        )

    /**
     * True only for a request that may be reduced to a kernel recovery
     * baseline: still unresolved, without framework proof and with a numeric id
     * this process did not obtain ([processOwnedMoveId]). A process-owned id
     * stays, because the framework-only restart that hid its record did not end
     * that native task, and any framework proof keeps the handle pollable too.
     */
    fun isUnprovenLegacy(processOwnedMoveId: Boolean): Boolean =
        isUnresolved && !processOwnedMoveId && frameworkId == null

    companion object {
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_COMPLETED = "completed"
        const val STATE_FAILED = "failed"
        const val STATE_UNVERIFIED = "unverified"

        private val RECOVERABLE_STATES = setOf(
            STATE_STARTING,
            STATE_RUNNING,
            STATE_UNVERIFIED,
        )

        /**
         * True when [raw] is a native move id this build may use. AOSP numbers
         * moves from 0 (`mNextMoveId.getAndIncrement`) and the backend rejects
         * only negative values, so 0 is a real id while -1 is the stored
         * "absent" sentinel.
         */
        fun isValidNativeMoveId(raw: Int?): Boolean = raw != null && raw >= 0

        /**
         * Canonical lowercase form of [raw], or `null` unless it is a full UUID.
         * The round-trip check inside [FrameworkInstanceId.canonicalBootUuid]
         * rejects the shortened or padded forms a permissive parser may accept,
         * so arbitrary text never becomes reboot proof. The caller reads files.
         */
        fun canonicalBootId(raw: String?): String? =
            FrameworkInstanceId.canonicalBootUuid(raw)
    }
}

/**
 * Small persisted-settings helper for the two user preferences the screen owns:
 * the explicit language choice and the "Show logs" toggle.
 *
 * The language follows the system when the user has never chosen one, so a
 * device whose system language is Chinese opens in Chinese and an English
 * device opens in English. An explicit choice is persisted across launches.
 */
object AppSettings {

    private const val PREFS = "armada_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_SHOW_LOGS = "show_logs"
    private const val KEY_VERIFIED_DISK_ID = "verified_disk_id"
    private const val KEY_VERIFIED_DISK_BYTES = "verified_disk_bytes"
    private const val KEY_VERIFIED_CARD_CID = "verified_card_cid"
    private const val KEY_VERIFIED_PUBLIC_FS_UUID = "verified_public_fs_uuid"
    private const val KEY_VERIFIED_PUBLIC_PART_GUID = "verified_public_part_guid"
    private const val KEY_MIGRATION_HANDLE = "migration_handle"
    private const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"

    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    // --- language ------------------------------------------------------------

    /** The explicitly stored language, or `null` when following the system. */
    fun storedLanguage(context: Context): String? =
        prefs(context).getString(KEY_LANGUAGE, null)

    fun setLanguage(context: Context, language: String?) {
        val editor = prefs(context).edit()
        if (language == null) editor.remove(KEY_LANGUAGE) else editor.putString(KEY_LANGUAGE, language)
        editor.apply()
    }

    /** The effective language: the explicit choice, else the system language. */
    fun effectiveLanguage(context: Context): String {
        storedLanguage(context)?.let { return it }
        val system = systemLocale(context)
        return if (system.language == LANG_ZH) LANG_ZH else LANG_EN
    }

    /** Returns a context whose resources resolve the effective language. */
    fun wrap(base: Context): Context {
        val locale = Locale(effectiveLanguage(base))
        // Override locale/layout direction only, so orientation/size/density keep following the system.
        val configuration = Configuration()
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    private fun systemLocale(context: Context): Locale {
        val configuration = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
    }

    // --- logs ----------------------------------------------------------------

    fun showLogs(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_LOGS, false)

    fun setShowLogs(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_LOGS, show).apply()
    }

    // --- verified Linux completion -------------------------------------------

    /**
     * The durable, identity-bound proof of an app-verified all-Linux card.
     * Returns `null` when nothing usable was stored, so callers can never treat
     * a bare disk id as a completed allocation.
     */
    fun verifiedLinuxCard(context: Context): VerifiedLinuxCard? {
        val stored = prefs(context)
        val diskId = stored.getString(KEY_VERIFIED_DISK_ID, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val diskBytes = stored.getLong(KEY_VERIFIED_DISK_BYTES, 0L)
        if (diskBytes <= 0L) return null
        val cardCid = stored.getString(KEY_VERIFIED_CARD_CID, "").orEmpty()
        val publicFsUuid = stored.getString(KEY_VERIFIED_PUBLIC_FS_UUID, "").orEmpty()
        val publicPartGuid = stored.getString(KEY_VERIFIED_PUBLIC_PART_GUID, "").orEmpty()
        if (publicFsUuid.isBlank() && publicPartGuid.isBlank()) return null
        return VerifiedLinuxCard(
            diskId = diskId,
            diskBytes = diskBytes,
            cardCid = cardCid,
            publicFsUuid = publicFsUuid,
            publicPartGuid = publicPartGuid,
        )
    }

    /** Stores or clears the verified Linux completion. */
    fun setVerifiedLinuxCard(context: Context, card: VerifiedLinuxCard?) {
        val editor = prefs(context).edit()
        if (card == null) {
            editor
                .remove(KEY_VERIFIED_DISK_ID)
                .remove(KEY_VERIFIED_DISK_BYTES)
                .remove(KEY_VERIFIED_CARD_CID)
                .remove(KEY_VERIFIED_PUBLIC_FS_UUID)
                .remove(KEY_VERIFIED_PUBLIC_PART_GUID)
        } else {
            editor
                .putString(KEY_VERIFIED_DISK_ID, card.diskId)
                .putLong(KEY_VERIFIED_DISK_BYTES, card.diskBytes)
                .putString(KEY_VERIFIED_CARD_CID, card.cardCid)
                .putString(KEY_VERIFIED_PUBLIC_FS_UUID, card.publicFsUuid)
                .putString(KEY_VERIFIED_PUBLIC_PART_GUID, card.publicPartGuid)
        }
        editor.apply()
    }

    // --- kernel boot id ------------------------------------------------------

    /**
     * The kernel boot id of the running kernel, or `null` when this device does
     * not expose it. A denied, missing or non-canonical value yields `null`, so
     * no boot identity is ever invented.
     */
    fun currentBootId(): String? = try {
        MigrationHandle.canonicalBootId(File(BOOT_ID_PATH).readText())
    } catch (t: Exception) {
        null
    }

    // --- started migration handle --------------------------------------------

    /** The saved move handle, or `null` when no migration was ever started. */
    fun migrationHandle(context: Context): MigrationHandle? {
        val raw = prefs(context).getString(KEY_MIGRATION_HANDLE, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val diskId = json.optString("disk_id")
            val kind = json.optString("kind")
            val targetUuid = json.optString("target_uuid")
            val state = json.optString("state")
            if (diskId.isBlank() || kind.isBlank() || targetUuid.isBlank() || state.isBlank()) {
                return null
            }
            val rawMoveId = json.optInt("move_id", -1)
            MigrationHandle(
                moveId = rawMoveId.takeIf { MigrationHandle.isValidNativeMoveId(it) },
                diskId = diskId,
                kind = kind,
                targetUuid = targetUuid,
                packageName = json.optString("package_name").takeIf {
                    it.isNotEmpty() && it != "null"
                },
                sourceUuid = json.optString("source_uuid"),
                privateUuid = json.optString("private_uuid").takeIf {
                    it.isNotEmpty() && it != "null"
                },
                state = state,
                errorCode = json.optString("error_code").takeIf {
                    it.isNotEmpty() && it != "null"
                },
                bootId = MigrationHandle.canonicalBootId(json.optString("boot_id")),
                frameworkId = FrameworkInstanceId.canonical(json.optString("framework_id")),
            )
        } catch (t: Exception) {
            null
        }
    }

    fun setMigrationHandle(context: Context, handle: MigrationHandle?) {
        val editor = prefs(context).edit()
        if (handle == null) {
            editor.remove(KEY_MIGRATION_HANDLE)
        } else {
            val json = JSONObject()
                .put("move_id", handle.moveId ?: -1)
                .put("disk_id", handle.diskId)
                .put("kind", handle.kind)
                .put("target_uuid", handle.targetUuid)
                .put("source_uuid", handle.sourceUuid)
                .put("state", handle.state)
            handle.packageName?.let { json.put("package_name", it) }
            handle.privateUuid?.let { json.put("private_uuid", it) }
            handle.errorCode?.let { json.put("error_code", it) }
            MigrationHandle.canonicalBootId(handle.bootId)?.let { json.put("boot_id", it) }
            FrameworkInstanceId.canonical(handle.frameworkId)?.let { json.put("framework_id", it) }
            editor.putString(KEY_MIGRATION_HANDLE, json.toString())
        }
        editor.apply()
    }

    fun clearMigrationHandle(context: Context) {
        prefs(context).edit().remove(KEY_MIGRATION_HANDLE).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Kernel boot identity of the running system: the app-side [appBootId] read
 * when it is a canonical UUID, else the boot UUID inside the latest privileged
 * [frameworkId]. A framework-only restart keeps that UUID, so it is never
 * reboot proof. Returns `null` when neither side is valid, so a missing or
 * malformed value never invents a boot.
 */
internal fun currentMigrationBootId(appBootId: String?, frameworkId: String?): String? =
    MigrationHandle.canonicalBootId(appBootId) ?: FrameworkInstanceId.bootIdOf(frameworkId)

/**
 * Compares one observed storage location with the expected one for a saved
 * migration. Missing, blank or whitespace-only values are UNKNOWN on either
 * side, so an unreadable package or a missing volume can never verify an
 * internal or a card destination (the old `isInternalUuid(null)` shortcut
 * would have accepted a move that was never observed).
 *
 * `internal`, `primary` and `private` are the explicit markers the native
 * Overview and appStorageUuid use for real internal storage; external volume
 * UUIDs compare case-insensitively with hyphens ignored. Pure: no Android
 * dependency.
 */
internal fun migrationLocationMatches(actual: String?, expected: String): Boolean {
    val want = expected.trim()
    if (want.isEmpty()) return false
    val got = actual?.trim().orEmpty()
    if (got.isEmpty()) return false
    if (isInternalLocation(want)) return isInternalLocation(got)
    return locationKey(got) == locationKey(want)
}

private fun isInternalLocation(value: String): Boolean =
    value.equals("internal", ignoreCase = true) ||
        value.equals("primary", ignoreCase = true) ||
        value.equals("private", ignoreCase = true)

private fun locationKey(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace("-", "")
