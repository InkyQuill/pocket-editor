package net.inkyquill.pocketeditor.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChapterCollectorLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun changingChapterCancelsPreviousEagerCollectorInsteadOfAccumulating() {
        val chapter = mutableStateOf("a")
        val active = AtomicInteger()
        val completed = AtomicInteger()
        val sources = mutableMapOf<String, MutableSharedFlow<String>>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            rememberChapterState("book", chapter.value, scope) {
                sources.getOrPut(chapter.value) { MutableSharedFlow() }
                    .onStart { active.incrementAndGet() }
                    .onCompletion { active.decrementAndGet(); completed.incrementAndGet() }
            }
            Text(chapter.value)
        }

        compose.waitUntil(2_000) { active.get() == 1 }
        compose.runOnIdle { chapter.value = "b" }
        compose.waitUntil(2_000) { active.get() == 1 && completed.get() == 1 }
        compose.runOnIdle { chapter.value = "c" }
        compose.waitUntil(2_000) { active.get() == 1 && completed.get() == 2 }
        assertEquals(1, active.get())
    }
}
