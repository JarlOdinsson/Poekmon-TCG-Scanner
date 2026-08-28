package com.pokemontcgscanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pokemontcgscanner.app.ui.CardDexApp
import com.pokemontcgscanner.app.ui.CardDexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CardDexTheme { CardDexApp() } }
    }
}
