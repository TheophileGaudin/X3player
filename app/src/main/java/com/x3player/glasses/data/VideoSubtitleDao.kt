package com.x3player.glasses.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoSubtitleDao {
    @Query(
        """
        SELECT * FROM uploaded_subtitles
        WHERE videoId = :videoId
        ORDER BY createdAtEpochMs DESC, id DESC
        """
    )
    fun observeSubtitles(videoId: Long): Flow<List<UploadedSubtitleEntity>>

    @Query(
        """
        SELECT selectedSubtitleId FROM subtitle_selection
        WHERE videoId = :videoId
        LIMIT 1
        """
    )
    fun observeSelectedSubtitleId(videoId: Long): Flow<Long?>

    @Query(
        """
        SELECT * FROM uploaded_subtitles
        WHERE videoId = :videoId AND subtitleUri = :subtitleUri
        LIMIT 1
        """
    )
    suspend fun findByVideoIdAndUri(videoId: Long, subtitleUri: String): UploadedSubtitleEntity?

    @Query(
        """
        SELECT * FROM uploaded_subtitles
        WHERE id = :subtitleId
        LIMIT 1
        """
    )
    suspend fun getById(subtitleId: Long): UploadedSubtitleEntity?

    @Insert
    suspend fun insertSubtitle(entity: UploadedSubtitleEntity): Long

    @Update
    suspend fun updateSubtitle(entity: UploadedSubtitleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSelection(entity: SubtitleSelectionEntity)

    @Query("DELETE FROM subtitle_selection WHERE videoId = :videoId")
    suspend fun clearSelection(videoId: Long)
}
