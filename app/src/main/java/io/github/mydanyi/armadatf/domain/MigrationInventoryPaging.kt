package io.github.mydanyi.armadatf.domain

/**
 * Pure policy for transferring the read-only migration inventory over Binder
 * in bounded pages.
 *
 * The service keeps the complete inventory in memory and freezes every row as
 * an immutable JSON string; only this policy decides which rows fit into one
 * reply. Sizes are measured in UTF-16 bytes because an AIDL String parcel
 * charges `4 + (length + 1) * 2` bytes, not the UTF-8 length of the text.
 */
object MigrationInventoryPaging {
    /** Hard row cap for one reply. */
    const val MAX_ROWS_PER_PAGE = 64

    /** Conservative AIDL transaction budget for one page reply. */
    const val MAX_REPLY_BYTES = 64 * 1024

    /** Reserved for the token, offsets, array brackets and ok(data) wrapper. */
    const val REPLY_ENVELOPE_BYTES = 2 * 1024

    private const val ARRAY_SEPARATOR_BYTES = 2L

    /** Approximate parcel cost of one AIDL String in UTF-16 bytes. */
    fun parcelBytes(length: Int): Long {
        val raw = 4L + (length.toLong() + 1L) * 2L
        return (raw + 3L) and -4L
    }

    /** True when an encoded reply fits the per-page Binder budget. */
    fun fitsReply(reply: String): Boolean =
        parcelBytes(reply.length) <= MAX_REPLY_BYTES.toLong()

    data class Page(
        val offset: Int,
        val total: Int,
        val nextOffset: Int,
        val rows: List<String>,
    )

    data class Progress(
        val nextOffset: Int,
        val complete: Boolean,
    )

    class InvalidPageException(message: String) : IllegalArgumentException(message)

    class OversizedRowException(message: String) : IllegalArgumentException(message)

    /**
     * Selects the rows starting at [offset] that fit both [limit] rows and the
     * reply byte budget. [Page.nextOffset] is always greater than [offset]
     * unless [offset] is already [rows].size.
     */
    fun page(rows: List<String>, offset: Int, limit: Int): Page {
        if (offset < 0) throw InvalidPageException("offset must be >= 0")
        if (offset > rows.size) throw InvalidPageException("offset exceeds the inventory")
        if (limit <= 0) throw InvalidPageException("limit must be > 0")
        if (offset == rows.size) return Page(offset, rows.size, offset, emptyList())

        val cap = minOf(limit, MAX_ROWS_PER_PAGE)
        val budget = MAX_REPLY_BYTES.toLong() - REPLY_ENVELOPE_BYTES.toLong()
        val selected = ArrayList<String>(cap)
        var used = 0L
        var index = offset
        while (index < rows.size && selected.size < cap) {
            val row = rows[index]
            val cost = parcelBytes(row.length) + ARRAY_SEPARATOR_BYTES
            if (used + cost > budget) break
            selected.add(row)
            used += cost
            index++
        }
        if (selected.isEmpty()) {
            throw OversizedRowException(
                "a single inventory row does not fit the binder reply budget",
            )
        }
        return Page(offset, rows.size, offset + selected.size, selected)
    }

    /**
     * Validates one client-observed page against the request and the stable
     * inventory total. It refuses a page that does not advance, advances too
     * far, changes the total or reports a row count that cannot match the
     * offset movement.
     */
    fun validatePage(
        requestedOffset: Int,
        limit: Int,
        total: Int,
        pageOffset: Int,
        pageTotal: Int,
        nextOffset: Int,
        rowCount: Int,
    ): Progress {
        if (total < 0) throw InvalidPageException("total must be >= 0")
        if (requestedOffset < 0 || requestedOffset > total) {
            throw InvalidPageException("requested offset is outside the inventory")
        }
        if (limit <= 0) throw InvalidPageException("limit must be > 0")
        if (pageOffset != requestedOffset) {
            throw InvalidPageException("page offset does not match the request")
        }
        if (pageTotal != total) {
            throw InvalidPageException("inventory total changed between pages")
        }
        if (nextOffset <= requestedOffset) {
            throw InvalidPageException("page made no progress")
        }
        if (nextOffset > total) {
            throw InvalidPageException("page advanced past the inventory")
        }
        if (rowCount != nextOffset - requestedOffset) {
            throw InvalidPageException("row count does not match the offset advance")
        }
        if (rowCount > limit || rowCount > MAX_ROWS_PER_PAGE) {
            throw InvalidPageException("page returned more rows than requested")
        }
        return Progress(nextOffset, nextOffset == total)
    }
}
