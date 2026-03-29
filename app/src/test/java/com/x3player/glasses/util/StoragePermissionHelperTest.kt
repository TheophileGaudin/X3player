package com.x3player.glasses.util

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StoragePermissionHelperTest {
    @Test
    fun `uses media permission on android 13 and above`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO),
            StoragePermissionHelper.requiredPermissions(33),
        )
    }

    @Test
    fun `uses external storage permission below android 13`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            StoragePermissionHelper.requiredPermissions(32),
        )
    }
}
