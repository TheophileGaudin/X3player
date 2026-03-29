package com.x3player.glasses.binocular

interface BinocularRenderer {
    fun setMirrorMode(isComplexContent: Boolean)
    fun notifyFrameChanged()
    fun release()
}
