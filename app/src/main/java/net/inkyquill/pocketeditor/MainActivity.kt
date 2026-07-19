package net.inkyquill.pocketeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.inkyquill.pocketeditor.ui.navigation.PocketEditorRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketEditorRoot()
        }
    }
}
