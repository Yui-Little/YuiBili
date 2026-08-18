package com.yuilittle.bili

/**
 * Pure paging rules shared by the home feeds and executable host-side tests.
 * The memory limit is a sliding window, never an end-of-pagination signal.
 */
object FeedPagingPolicy {
    const val PAGE_SIZE = 12
    const val MAX_IN_MEMORY_ITEMS = 480
    const val RETAINED_ITEMS = 360
    const val MAX_REFRESH_SCAN_PAGES = 4
    const val MAX_APPEND_SKIP_PAGES = 2

    fun nextRefreshPage(lastSuccessfulPage: Int): Int =
        if (lastSuccessfulPage <= 0) 1 else lastSuccessfulPage + 1

    fun isTerminalPage(resultSize: Int): Boolean = resultSize == 0

    fun trimFromStart(currentSize: Int, additionsSize: Int): Int {
        val total = currentSize.coerceAtLeast(0) + additionsSize.coerceAtLeast(0)
        return if (total > MAX_IN_MEMORY_ITEMS) total - RETAINED_ITEMS else 0
    }
}
