package com.x3player.glasses

enum class TempleDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

interface TempleNavigationHandler {
    fun onTempleNavigate(direction: TempleDirection): Boolean
    fun onTempleTap(): Boolean
    fun onTempleEnsureFocus() {}
}
