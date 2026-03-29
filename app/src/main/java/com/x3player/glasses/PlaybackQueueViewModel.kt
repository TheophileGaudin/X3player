package com.x3player.glasses

import androidx.lifecycle.ViewModel
import com.x3player.glasses.data.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PlaybackQueueViewModel : ViewModel() {
    private val queue = MutableStateFlow<List<VideoItem>>(emptyList())
    private val currentIndex = MutableStateFlow(-1)

    val currentQueue: StateFlow<List<VideoItem>> = queue.asStateFlow()
    val selectedIndex: StateFlow<Int> = currentIndex.asStateFlow()

    val currentItem: VideoItem?
        get() = queue.value.getOrNull(currentIndex.value)

    fun openQueue(items: List<VideoItem>, index: Int) {
        queue.value = items
        currentIndex.value = index.coerceIn(0, items.lastIndex)
    }

    fun openSingle(item: VideoItem) {
        queue.value = listOf(item)
        currentIndex.value = 0
    }

    fun selectNext(): VideoItem? {
        val nextIndex = currentIndex.value + 1
        if (nextIndex !in queue.value.indices) return null
        currentIndex.value = nextIndex
        return queue.value[nextIndex]
    }

    fun selectPrevious(): VideoItem? {
        val previousIndex = currentIndex.value - 1
        if (previousIndex !in queue.value.indices) return null
        currentIndex.value = previousIndex
        return queue.value[previousIndex]
    }

    fun canMoveNext(): Boolean = currentIndex.value + 1 in queue.value.indices

    fun canMovePrevious(): Boolean = currentIndex.value - 1 in queue.value.indices

    fun selectIndex(index: Int): VideoItem? {
        if (index !in queue.value.indices) return null
        currentIndex.value = index
        return queue.value[index]
    }

    fun clear() {
        queue.update { emptyList() }
        currentIndex.value = -1
    }
}
