package com.x3player.glasses.data

class RoomPlaybackProgressStore(
    private val dao: PlaybackProgressDao,
) : PlaybackProgressStore {
    override suspend fun load(videoId: Long): PlaybackProgress? {
        return dao.get(videoId)?.toModel()
    }

    override suspend fun save(
        videoId: Long,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
        uri: String,
    ) {
        dao.upsert(
            PlaybackProgressEntity(
                videoId = videoId,
                uri = uri,
                resumePositionMs = positionMs,
                durationMs = durationMs,
                completed = completed,
                lastPlayedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun clear(videoId: Long) {
        dao.delete(videoId)
    }
}

private fun PlaybackProgressEntity.toModel(): PlaybackProgress = PlaybackProgress(
    videoId = videoId,
    uri = uri,
    resumePositionMs = resumePositionMs,
    durationMs = durationMs,
    completed = completed,
    lastPlayedAt = lastPlayedAt,
)
