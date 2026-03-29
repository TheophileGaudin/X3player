package com.x3player.glasses.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object StoragePermissionHelper {
    fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        sdkInt >= Build.VERSION_CODES.M -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyArray()
    }

    fun hasRequiredPermissions(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return requiredPermissions(sdkInt).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
