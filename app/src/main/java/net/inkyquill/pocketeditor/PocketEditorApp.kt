package net.inkyquill.pocketeditor

import android.app.Application
import android.content.Context

class PocketEditorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.create(this)
    }
}

class AppContainer private constructor(
    val applicationContext: Context,
) {
    companion object {
        fun create(context: Context) = AppContainer(context.applicationContext)
    }
}
