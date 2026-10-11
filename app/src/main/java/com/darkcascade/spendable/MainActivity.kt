package com.darkcascade.spendable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.darkcascade.spendable.ui.SpendableApp
import com.darkcascade.spendable.ui.theme.SpendableTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpendableTheme {
                SpendableApp()
            }
        }
    }
}
