package com.x3player.glasses.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface VideoRepository {
    fun observeLibrary(filter: LibraryFilter, sort: LibrarySort): Flow<List<VideoItem>>
    suspend fun refreshLibrary()
    suspend fun getByUri(uri: Uri): VideoItem?
}
