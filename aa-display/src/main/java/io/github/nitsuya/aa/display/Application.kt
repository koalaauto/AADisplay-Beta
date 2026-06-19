package io.github.nitsuya.aa.display

import android.content.Context
import android.os.Process
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import io.github.nitsuya.aa.display.util.XposedConfigSync
import io.github.nitsuya.aa.display.util.tryOrNull
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.ModuleResourceBridge


val IsSystemEnv by lazy {
    Process.myUid() == 1000
}
val CoreApi by lazy {
    if(!IsSystemEnv) CoreManager
    else CoreManagerService.instance!!
}
lateinit var App : Application
class Application: android.app.Application() {
    init {
        App = this
        tryOrNull {
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ModuleResourceBridge.bind(applicationInfo)
        DynamicColors.applyToActivitiesIfAvailable(this)
        XposedConfigSync.init(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }
}
