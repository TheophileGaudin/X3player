package com.x3player.glasses.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress WHERE videoId = :videoId LIMIT 1")
    suspend fun get(videoId: Long): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE videoId = :videoId")
    suspend fun delete(videoId: Long)
}
