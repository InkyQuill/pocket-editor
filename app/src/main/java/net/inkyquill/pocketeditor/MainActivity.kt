package net.inkyquill.pocketeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketEditorTheme {
                PocketEditorRoot()
            }
        }
    }
}

@Composable
fun PocketEditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
fun PocketEditorRoot() = Unit
