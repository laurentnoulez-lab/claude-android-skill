package com.example.slideshowstudio

import android.app.Application

class SlideshowApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
