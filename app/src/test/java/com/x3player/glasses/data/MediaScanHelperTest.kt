package com.x3player.glasses.data

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaScanHelperTest {
    @Test
    fun `collects supported video files from standard directories`() {
        val root = createTempDirectory().toFile()
        val movies = File(root, "Movies").apply { mkdirs() }
        File(movies, "clip.mp4").writeText("x")
        File(movies, "cover.jpg").writeText("x")

        val candidates = collectVideoCandidates(listOf(movies))

        assertEquals(listOf("clip.mp4"), candidates.map { it.name })
    }
}
