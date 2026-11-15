package io.github.mydanyi.armadatf.storage

/**
 * Completes one `ParceledListSlice` read using its declared total.
 *
 * The platform's `ParceledListSlice` always writes the original row count
 * before the inline rows. A legacy platform can silently stop after a prefix
 * when a continuation call fails, so a raw reply is only complete when
 * [total] rows have actually been collected. Every continuation must advance
 * the offset and can never exceed the rows still missing; a failure from
 * [readNext] propagates and no partial prefix is ever returned.
 *
 * A [total] of 0 is a legitimate empty result, and there is no application
 * count cap: the declared total is the only bound.
 */
internal fun <T> collectCompleteSlice(
    total: Int,
    inline: List<T>,
    readNext: (Int) -> List<T>,
): List<T> {
    require(total >= 0) { "declared slice total was negative: $total" }
    require(inline.size <= total) {
        "inline slice rows ${inline.size} exceeded declared total $total"
    }
    val rows = ArrayList<T>()
    rows.addAll(inline)
    var offset = inline.size
    while (offset < total) {
        val next = readNext(offset)
        val remaining = total - offset
        if (next.isEmpty()) {
            throw IllegalStateException(
                "continuation at offset $offset returned no rows",
            )
        }
        if (next.size > remaining) {
            throw IllegalStateException(
                "continuation at offset $offset returned ${next.size} rows, $remaining remaining",
            )
        }
        rows.addAll(next)
        offset += next.size
    }
    return rows
}
