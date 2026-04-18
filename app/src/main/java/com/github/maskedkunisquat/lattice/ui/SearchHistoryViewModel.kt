package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.PeopleRepository
import com.github.maskedkunisquat.lattice.core.logic.PlaceRepository
import com.github.maskedkunisquat.lattice.core.logic.SearchRepository
import com.github.maskedkunisquat.lattice.core.logic.TagRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchTab { ENTRIES, PEOPLE, PLACES, TAGS }

data class PlaceResult(val place: Place, val entryCount: Int)
data class TagResult(val tag: Tag, val entryCount: Int)

data class SearchUiState(
    val query: String = "",
    val expanded: Boolean = false,
    val activeTab: SearchTab = SearchTab.ENTRIES,
    val entryResults: List<JournalEntry> = emptyList(),
    val peopleResults: List<Person> = emptyList(),
    val placeResults: List<PlaceResult> = emptyList(),
    val tagResults: List<TagResult> = emptyList(),
    val isSemanticLoading: Boolean = false,
    val isLikeLoading: Boolean = false,
) {
    val isLoading: Boolean get() = isSemanticLoading || isLikeLoading
}

class SearchHistoryViewModel(
    private val searchRepository: SearchRepository,
    private val peopleRepository: PeopleRepository,
    private val placeRepository: PlaceRepository,
    private val tagRepository: TagRepository,
    private val journalRepository: JournalRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // Lightweight snapshot of entry refs (id/tagIds/placeIds only) for count-based UI operations.
    // Does not cache full entry content or embeddings.
    private val entryRefsState = journalRepository.getEntryRefs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var semanticJob: Job? = null
    private var likeJob: Job? = null

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        dispatchSearch(query)
    }

    fun onExpandedChange(expanded: Boolean) {
        _uiState.update { it.copy(expanded = expanded) }
        if (!expanded) resetSearch()
    }

    fun onTabChange(tab: SearchTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    /** Collapses and clears all search state. */
    fun collapse() {
        semanticJob?.cancel()
        likeJob?.cancel()
        _uiState.value = SearchUiState()
    }

    private fun resetSearch() {
        semanticJob?.cancel()
        likeJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                entryResults = emptyList(),
                peopleResults = emptyList(),
                placeResults = emptyList(),
                tagResults = emptyList(),
                isSemanticLoading = false,
                isLikeLoading = false,
            )
        }
    }

    private fun dispatchSearch(query: String) {
        semanticJob?.cancel()
        likeJob?.cancel()

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    entryResults = emptyList(),
                    peopleResults = emptyList(),
                    placeResults = emptyList(),
                    tagResults = emptyList(),
                    isSemanticLoading = false,
                    isLikeLoading = false,
                )
            }
            return
        }

        // Clear stale results from the previous query immediately so cancelled jobs
        // don't leave misleading results visible during the debounce window.
        _uiState.update {
            it.copy(
                entryResults = emptyList(),
                peopleResults = emptyList(),
                placeResults = emptyList(),
                tagResults = emptyList(),
                isSemanticLoading = true,
                isLikeLoading = true,
            )
        }

        // Semantic entry search — debounced 300 ms, re-launched (and previous cancelled) on each keystroke.
        semanticJob = viewModelScope.launch {
            val localQuery = query
            try {
                delay(300)
                _uiState.update { it.copy(isSemanticLoading = true) }
                val results = searchRepository.findSimilarEntries(localQuery, limit = 20)
                // Guard against a stale result landing after the query has changed.
                if (_uiState.value.query == localQuery) {
                    _uiState.update { it.copy(entryResults = results, isSemanticLoading = false) }
                } else {
                    _uiState.update { it.copy(isSemanticLoading = false) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isSemanticLoading = false) }
            }
        }

        // LIKE searches for people / places / tags — debounced 150 ms.
        likeJob = viewModelScope.launch {
            val localQuery = query
            try {
                delay(150)
                _uiState.update { it.copy(isLikeLoading = true) }
                val people = peopleRepository.searchByName(localQuery)
                val places = placeRepository.searchPlaces(localQuery)
                val tags = tagRepository.searchTags(localQuery)

                // Entry counts from lightweight ref snapshot; avoids loading content/embeddings.
                val entryRefs = entryRefsState.value
                val placeResults = places.map { place ->
                    PlaceResult(place, entryRefs.count { place.id in it.placeIds })
                }
                val tagResults = tags.map { tag ->
                    TagResult(tag, entryRefs.count { tag.id in it.tagIds })
                }
                // Guard against a stale result landing after the query has changed.
                if (_uiState.value.query == localQuery) {
                    _uiState.update {
                        it.copy(
                            peopleResults = people,
                            placeResults = placeResults,
                            tagResults = tagResults,
                            isLikeLoading = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLikeLoading = false) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isLikeLoading = false) }
            }
        }
    }

    companion object {
        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchHistoryViewModel(
                    searchRepository = app.searchRepository,
                    peopleRepository = app.peopleRepository,
                    placeRepository = app.placeRepository,
                    tagRepository = app.tagRepository,
                    journalRepository = app.journalRepository,
                ) as T
        }
    }
}
