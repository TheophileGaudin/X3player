package com.x3player.glasses

import android.app.Application

class X3PlayerApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}
