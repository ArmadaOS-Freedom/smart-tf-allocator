package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MigrationInventoryPagingTest {

    @Test
    fun largeInventoryIsRecoveredInStableOrder() {
        val rows = ArrayList<String>(2048)
        for (index in 0 until 2048) {
            rows.add(row(index, 600))
        }
        assertTrue(rows.sumOf { it.length } > 1_000_000)

        val recovered = ArrayList<String>(rows.size)
        var offset = 0
        var pages = 0
        while (offset < rows.size) {
            val page = MigrationInventoryPaging.page(rows, offset, 64)
            assertTrue(page.rows.isNotEmpty())
            assertTrue(page.nextOffset > offset)
            assertTrue(page.rows.size <= MigrationInventoryPaging.MAX_ROWS_PER_PAGE)
            assertReplyFits(page.rows)
            recovered.addAll(page.rows)
            offset = page.nextOffset
            pages++
        }
        assertEquals(rows.size, offset)
        assertTrue(pages > 1)
        assertEquals(rows, recovered)
        assertEquals(rows.last(), recovered.last())
    }

    @Test
    fun unicodeRowsStayInsideTheReplyBudget() {
        val rows = ArrayList<String>(80)
        for (index in 0 until 80) {
            rows.add("{\"package_name\":\"应用.$index\",\"pad\":\"${"中".repeat(2_000)}\"}")
        }
        var offset = 0
        var lastPage = emptyList<String>()
        while (offset < rows.size) {
            val page = MigrationInventoryPaging.page(rows, offset, 64)
            assertReplyFits(page.rows)
            lastPage = page.rows
            offset = page.nextOffset
        }
        assertEquals(rows.size, offset)
        assertTrue(lastPage.size < 64)
        assertEquals(rows.last(), lastPage.last())
    }

    @Test
    fun emptyInventoryAndFinalOffsetAreValid() {
        val empty = MigrationInventoryPaging.page(emptyList(), 0, 64)
        assertEquals(0, empty.total)
        assertEquals(0, empty.nextOffset)
        assertTrue(empty.rows.isEmpty())

        val rows = listOf(row(0, 10), row(1, 10))
        val atEnd = MigrationInventoryPaging.page(rows, 2, 64)
        assertEquals(2, atEnd.nextOffset)
        assertTrue(atEnd.rows.isEmpty())
    }

    @Test
    fun invalidOffsetsAndLimitsAreRejected() {
        val rows = listOf(row(0, 10), row(1, 10))
        expectInvalid { MigrationInventoryPaging.page(rows, -1, 10) }
        expectInvalid { MigrationInventoryPaging.page(rows, 3, 10) }
        expectInvalid { MigrationInventoryPaging.page(rows, 0, 0) }
        expectInvalid { MigrationInventoryPaging.page(rows, 0, -3) }
    }

    @Test
    fun singleOversizedRowIsRefused() {
        val oversized = "x".repeat(80_000)
        try {
            MigrationInventoryPaging.page(listOf(oversized), 0, 64)
            fail("expected an oversized row refusal")
        } catch (expected: MigrationInventoryPaging.OversizedRowException) {
            assertTrue(expected.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun progressValidatorAcceptsOnlyStrictPages() {
        val first = MigrationInventoryPaging.validatePage(
            requestedOffset = 0,
            limit = 64,
            total = 130,
            pageOffset = 0,
            pageTotal = 130,
            nextOffset = 64,
            rowCount = 64,
        )
        assertEquals(64, first.nextOffset)
        assertFalse(first.complete)

        val last = MigrationInventoryPaging.validatePage(
            requestedOffset = 128,
            limit = 64,
            total = 130,
            pageOffset = 128,
            pageTotal = 130,
            nextOffset = 130,
            rowCount = 2,
        )
        assertEquals(130, last.nextOffset)
        assertTrue(last.complete)

        expectInvalid { validate(0, 0, 1) }
        expectInvalid { validate(0, 131, 130) }
        expectInvalid { validate(0, 10, 9) }
        expectInvalid { validate(0, 129, 64, pageTotal = 129) }
        expectInvalid { validate(0, 65, 65, limit = 64) }
        expectInvalid { validate(131, 132, 1) }
    }

    private fun validate(
        requestedOffset: Int,
        nextOffset: Int,
        rowCount: Int,
        pageTotal: Int = 130,
        limit: Int = 64,
    ) {
        MigrationInventoryPaging.validatePage(
            requestedOffset = requestedOffset,
            limit = limit,
            total = 130,
            pageOffset = requestedOffset,
            pageTotal = pageTotal,
            nextOffset = nextOffset,
            rowCount = rowCount,
        )
    }

    private fun assertReplyFits(rows: List<String>) {
        var bytes = MigrationInventoryPaging.REPLY_ENVELOPE_BYTES.toLong()
        for (row in rows) {
            bytes += MigrationInventoryPaging.parcelBytes(row.length) + 2L
        }
        assertTrue(bytes <= MigrationInventoryPaging.MAX_REPLY_BYTES)
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected an invalid page")
        } catch (expected: MigrationInventoryPaging.InvalidPageException) {
            assertTrue(expected.message?.isNotEmpty() == true)
        }
    }

    private fun row(index: Int, pad: Int): String =
        "{\"package_name\":\"app.$index\",\"pad\":\"${"x".repeat(pad)}\"}"
}
