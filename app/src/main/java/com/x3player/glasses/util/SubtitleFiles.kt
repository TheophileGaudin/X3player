package com.x3player.glasses.util

import androidx.media3.common.MimeTypes
import java.util.Locale

fun resolveSupportedSubtitleMimeType(rawMimeType: String?, displayName: String?): String? {
    val normalizedMimeType = rawMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)

    when (normalizedMimeType) {
        "application/x-subrip",
        "application/srt",
        "text/srt" -> return MimeTypes.APPLICATION_SUBRIP

        MimeTypes.TEXT_VTT -> return MimeTypes.TEXT_VTT

        "text/x-ssa",
        "text/x-ass",
        "application/x-ass" -> return MimeTypes.TEXT_SSA

        "application/ttml+xml" -> return MimeTypes.APPLICATION_TTML
    }

    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)

    return when (extension) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }
}

fun isSupportedSubtitleFileName(displayName: String?): Boolean {
    return resolveSupportedSubtitleMimeType(null, displayName) != null
}
