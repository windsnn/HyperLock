package io.github.windsnn.hyperlock

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HookApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        HyperLog.isVerbose = HookSettingsStore(this).settings.verboseLog
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        HyperLog.i("App", "LSPosed remote service bound successfully")
        serviceState.value = service
    }

    override fun onServiceDied(service: XposedService) {
        HyperLog.w("App", "LSPosed remote service died unexpectedly")
        if (serviceState.value === service) serviceState.value = null
    }

    companion object {
        private val serviceState = MutableStateFlow<XposedService?>(null)
        val service = serviceState.asStateFlow()
    }
}
