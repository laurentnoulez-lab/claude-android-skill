package com.example.slideshowstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.slideshowstudio.ui.SlideshowApp
import com.example.slideshowstudio.ui.theme.SlideshowStudioTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SlideshowApplication).container
        setContent {
            SlideshowStudioTheme {
                SlideshowApp(container = container)
            }
        }
    }
}
