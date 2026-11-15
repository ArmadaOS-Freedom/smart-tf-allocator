package io.github.mydanyi.armadatf.storage

import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Parcel

/**
 * Reads one complete `IPackageManager.getInstalledApplications` reply.
 *
 * The platform's `ParceledListSlice` writes its declared total before the
 * inline rows, but a legacy `readFromParcel` silently returns a non-null prefix
 * when a continuation call fails. [read] therefore consumes the raw Binder
 * reply itself, checks every marker, reuses the retriever returned by the
 * initial reply for every continuation and delegates
 * the exact-count policy to [collectCompleteSlice]. Any truncation, corrupt
 * marker, rejected transaction, missing continuation or missing progress
 * throws; a collected prefix is never returned.
 *
 * This reader is deliberately narrow: only `android.content.pm.ApplicationInfo`
 * rows are accepted, so no arbitrary parcelable class name is loaded.
 */
object CompleteApplicationQuery {

    /**
     * Reads the reply to `getInstalledApplications` for [flags] and [userId].
     *
     * [transactionCode] is the platform transaction resolved by the caller,
     * [longFlags] selects the argument width of the installed method, and
     * [modernContinuation] selects the Android 14+ continuation reply header.
     */
    fun read(
        binder: IBinder,
        transactionCode: Int,
        flags: Long,
        longFlags: Boolean,
        userId: Int,
        modernContinuation: Boolean,
    ): List<ApplicationInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            if (longFlags) {
                data.writeLong(flags)
            } else {
                data.writeInt(flags.toInt())
            }
            data.writeInt(userId)
            if (!binder.transact(transactionCode, data, reply, 0)) {
                throw NativeMigrationException(
                    "IPackageManager.getInstalledApplications was rejected",
                )
            }
            requireAvailable(reply, INT_BYTES, "the reply header")
            reply.readException()
            requireAvailable(reply, INT_BYTES, "the slice marker")
            if (reply.readInt() != PARCELABLE_PRESENT) {
                throw NativeMigrationException(
                    "IPackageManager.getInstalledApplications returned no slice",
                )
            }
            requireAvailable(reply, INT_BYTES, "the declared count")
            val total = reply.readInt()
            if (total < 0) {
                throw NativeMigrationException(
                    "IPackageManager.getInstalledApplications declared $total applications",
                )
            }
            if (total == 0) return emptyList()
            requireAvailable(reply, INT_BYTES, "the row creator")
            val creator = reply.readString()
            if (creator != APPLICATION_CREATOR) {
                throw NativeMigrationException(
                    "IPackageManager.getInstalledApplications returned creator $creator",
                )
            }
            val inline = readChunk(reply, total)
            // The retriever binder is written once, as the initial reply's end
            // marker payload. Continuation replies carry rows only and reuse it.
            val retriever = if (inline.size < total) {
                requireAvailable(reply, INT_BYTES, "the continuation binder")
                reply.readStrongBinder()
                    ?: throw NativeMigrationException(
                        "IPackageManager.getInstalledApplications has no continuation",
                    )
            } else {
                null
            }
            return collectCompleteSlice(total, inline) { offset ->
                val continuation = retriever
                    ?: throw NativeMigrationException(
                        "IPackageManager.getInstalledApplications has no continuation at $offset",
                    )
                readContinuation(
                    continuation,
                    offset,
                    total - offset,
                    modernContinuation,
                )
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Reads rows until [limit] rows or the end marker, never past the end of data.
     *
     * A marker other than 1 or 0, a missing marker and a missing row payload
     * are all rejected. The end marker carries no binder: the retriever is
     * written once, in the initial reply, and the caller reads it there.
     */
    private fun readChunk(parcel: Parcel, limit: Int): List<ApplicationInfo> {
        val rows = ArrayList<ApplicationInfo>()
        while (rows.size < limit) {
            requireAvailable(parcel, INT_BYTES, "an inline marker")
            when (val marker = parcel.readInt()) {
                INLINE_ROW -> {
                    if (parcel.dataAvail() <= 0) {
                        throw NativeMigrationException(
                            "IPackageManager.getInstalledApplications was truncated",
                        )
                    }
                    rows.add(ApplicationInfo.CREATOR.createFromParcel(parcel))
                }
                INLINE_END -> return rows
                else -> throw NativeMigrationException(
                    "IPackageManager.getInstalledApplications returned marker $marker",
                )
            }
        }
        return rows
    }

    /** Reads one continuation chunk, whose reply header depends on [modern]. */
    private fun readContinuation(
        binder: IBinder,
        offset: Int,
        limit: Int,
        modern: Boolean,
    ): List<ApplicationInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInt(offset)
            if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) {
                throw NativeMigrationException(
                    "IPackageManager.getInstalledApplications continuation was rejected",
                )
            }
            if (modern) {
                requireAvailable(reply, INT_BYTES, "the continuation header")
                reply.readException()
            }
            return readChunk(reply, limit)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun requireAvailable(parcel: Parcel, bytes: Int, stage: String) {
        if (parcel.dataAvail() < bytes) {
            throw NativeMigrationException(
                "IPackageManager.getInstalledApplications reply ended before $stage",
            )
        }
    }

    private const val INTERFACE_TOKEN = "android.content.pm.IPackageManager"
    private const val APPLICATION_CREATOR = "android.content.pm.ApplicationInfo"
    private const val INT_BYTES = 4
    private const val PARCELABLE_PRESENT = 1
    private const val INLINE_ROW = 1
    private const val INLINE_END = 0
}
