package net.inkyquill.pocketeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import net.inkyquill.pocketeditor.ui.navigation.PocketEditorRoot
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0x00000000, 0x00000000),
            navigationBarStyle = SystemBarStyle.auto(0x00000000, 0x00000000),
        )
        setContent {
            PocketEditorTheme {
                PocketEditorRoot()
            }
        }
    }
}
