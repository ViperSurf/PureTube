package com.puretube.app.ui.screens.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.puretube.app.data.remote.extractor.SearchHit
import com.puretube.app.data.remote.extractor.SearchTypeFilter

/**
 * App-scoped search state (v1.2). Search used to keep its query/results in
 * the composable's `remember`, so opening a video destroyed the search.
 * Held on the Application instead: query, type filter, results, pagination
 * and scroll position all survive opening a video and coming back.
 */
class SearchSession {
    var query by mutableStateOf("")
    var filter by mutableStateOf(SearchTypeFilter.ALL)
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
    var hasSearched by mutableStateOf(false)
    var hasMore by mutableStateOf(false)
    var status by mutableStateOf("Search YouTube without Shorts, tracking, or recommendations.")
    /** Full failure detail (class chain + top stack frames) for Copy Diagnostics. */
    var errorDetail by mutableStateOf("")
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var scrollIndex by mutableStateOf(0)
    var scrollOffset by mutableStateOf(0)
}
