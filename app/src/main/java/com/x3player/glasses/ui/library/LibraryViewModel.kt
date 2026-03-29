package com.x3player.glasses.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.x3player.glasses.data.LibraryFilter
import com.x3player.glasses.data.LibrarySort
import com.x3player.glasses.data.SettingsRepository
import com.x3player.glasses.data.VideoItem
import com.x3player.glasses.data.VideoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val items: List<VideoItem> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.DATE_DESC,
    val autoResume: Boolean = true,
    val reopenLastVideoOnLaunch: Boolean = true,
    val isRefreshing: Boolean = true,
    val emptyMessage: String = "Loading local videos...",
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val filterOverride = MutableStateFlow<LibraryFilter?>(null)
    private val sortOverride = MutableStateFlow<LibrarySort?>(null)
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.settings,
                filterOverride,
                sortOverride,
            ) { settings, localFilter, localSort ->
                Triple(localFilter ?: settings.lastFilter, localSort ?: settings.lastSort, settings)
            }.flatMapLatest { (filter, sort, _) ->
                videoRepository.observeLibrary(filter, sort).combine(settingsRepository.settings) { items, latestSettings ->
                    LibraryUiState(
                        items = items,
                        filter = filter,
                        sort = sort,
                        autoResume = latestSettings.autoResume,
                        reopenLastVideoOnLaunch = latestSettings.reopenLastVideoOnLaunch,
                        isRefreshing = false,
                        emptyMessage = if (items.isEmpty()) {
                            "No videos found in Movies or Downloads.\nCopy files to the glasses and tap Refresh."
                        } else {
                            ""
                        },
                    )
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setFilter(filter: LibraryFilter) {
        filterOverride.value = filter
        viewModelScope.launch {
            settingsRepository.setLastFilter(filter)
        }
    }

    fun cycleSort() {
        val next = when (uiState.value.sort) {
            LibrarySort.DATE_DESC -> LibrarySort.NAME_ASC
            LibrarySort.NAME_ASC -> LibrarySort.DURATION_DESC
            LibrarySort.DURATION_DESC -> LibrarySort.DATE_DESC
        }
        sortOverride.value = next
        viewModelScope.launch {
            settingsRepository.setLastSort(next)
        }
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, emptyMessage = "Refreshing local library...") }
        viewModelScope.launch {
            videoRepository.refreshLibrary()
        }
    }

    fun setAutoResume(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoResume(enabled)
        }
    }

    fun setReopenLastVideoOnLaunch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setReopenLastVideoOnLaunch(enabled)
        }
    }

    class Factory(
        private val videoRepository: VideoRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(videoRepository, settingsRepository) as T
        }
    }
}
