package com.x3player.glasses.data

data class AppSettings(
    val lastSort: LibrarySort = LibrarySort.DATE_DESC,
    val lastFilter: LibraryFilter = LibraryFilter.ALL,
    val autoResume: Boolean = true,
    val reopenLastVideoOnLaunch: Boolean = true,
    val lastVideoUri: String? = null,
)
