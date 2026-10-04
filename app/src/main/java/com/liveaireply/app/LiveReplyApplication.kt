package com.liveaireply.app

import android.app.Application
import com.liveaireply.app.engine.AssistantRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LiveReplyApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val container = AssistantRuntime.requireContainer(this)
        // Prime the cached settings so the accessibility service can gate events
        // immediately, before the first DataStore read completes.
        scope.launch {
            runCatching {
                val settings = container.settingsRepository.settings.first()
                container.currentSettings = settings
                container.eventLog.debugEnabled = settings.debugLogging
            }
        }
    }
}
