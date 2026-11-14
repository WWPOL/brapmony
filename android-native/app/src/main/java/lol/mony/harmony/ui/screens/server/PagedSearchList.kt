package lol.mony.harmony.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.ServerManagementPage

/**
 * A list that loads its own pages as the reader reaches the end, and searches the whole
 * collection rather than the pages already in hand.
 *
 * Three behaviours are the point of it:
 *
 *  - **No page buttons.** The next page is requested when the last rows come into view, so a
 *    reader never navigates pages; a page still being fetched shows its own progress row.
 *  - **Search is the instance's.** The query is handed to [load], which filters the collection
 *    on the server. Filtering the loaded rows instead would silently miss everything not yet
 *    fetched, which is the bug this replaces.
 *  - **A page never loses a row.** Rows are merged by key, so a page that overlaps the previous
 *    one cannot produce a duplicate, and a row already loaded is never dropped by a late answer.
 *
 * The search is debounced so typing does not send a request per keystroke, and an in-flight
 * request for an older query cannot overwrite the answer for the current one.
 */
@Composable
internal fun <T> PagedSearchList(
    listKey: String,
    revision: Int,
    searchHint: String,
    emptyText: String,
    noMatchText: String,
    itemKey: (T) -> String,
    load: suspend (offset: Int, query: String) -> ServerManagementPage<T>,
    header: @Composable () -> Unit = {},
    /** Kept across pages and searches: the row the person picked, by key. */
    selectedKey: String? = null,
    row: @Composable (T, Boolean) -> Unit,
) {
    var query by remember(listKey) { mutableStateOf("") }
    var rows by remember(listKey, revision) { mutableStateOf<List<T>>(emptyList()) }
    var more by remember(listKey, revision) { mutableStateOf(false) }
    var loading by remember(listKey, revision) { mutableStateOf(true) }
    var loadingMore by remember(listKey, revision) { mutableStateOf(false) }
    var error by remember(listKey, revision) { mutableStateOf<String?>(null) }
    var exhausted by remember(listKey, revision) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentLoad by rememberUpdatedState(load)

    suspend fun fetch(reset: Boolean, forQuery: String) {
        if (reset) { loading = true; error = null; exhausted = false } else { loadingMore = true }
        try {
            val page = currentLoad(if (reset) 0 else rows.size, forQuery)
            // A late answer for a query the person has moved on from must not land.
            if (forQuery != query) return
            rows = if (reset) page.items.distinctBy(itemKey)
            else (rows + page.items).distinctBy(itemKey)
            more = page.hasMore
            exhausted = !page.hasMore
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This list could not be loaded." }
        finally { loading = false; loadingMore = false }
    }

    // A short pause after the last keystroke, so a search is one request rather than one per
    // character, and a cleared box returns to the unfiltered list.
    LaunchedEffect(listKey, revision, query) {
        delay(if (query.isBlank()) 0 else 300)
        listState.scrollToItem(0)
        fetch(reset = true, forQuery = query)
    }
    // The next page is requested when the end comes into view, which is what replaces a
    // "next page" control.
    LaunchedEffect(listState, more, loading, loadingMore, rows.size, query) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (more && !loading && !loadingMore && last >= rows.size - 2) {
                    fetch(reset = false, forQuery = query)
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text(searchHint) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
            }) else null,
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("paged-search"),
        )
        if (loading && rows.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("paged-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { header() }
            val failure = error
            if (failure != null) item(key = "error") {
                ServerManagementError(failure) { scope.launch { fetch(reset = true, forQuery = query) } }
            }
            if (rows.isEmpty() && loading) item(key = "first-load") {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            if (rows.isEmpty() && !loading && failure == null) item(key = "empty") {
                ServerManagementNote(if (query.isBlank()) emptyText else noMatchText)
            }
            items(rows, key = itemKey) { item ->
                row(item, selectedKey != null && itemKey(item) == selectedKey)
            }
            if (loadingMore) item(key = "more-progress") {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (exhausted && rows.isNotEmpty() && !loading) item(key = "end") {
                Text(if (query.isBlank()) "That is the whole list." else "That is every match.",
                    Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
