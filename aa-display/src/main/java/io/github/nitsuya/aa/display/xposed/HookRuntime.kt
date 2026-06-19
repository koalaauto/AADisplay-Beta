package io.github.nitsuya.aa.display.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.Collections

object HookRuntime {
    private val initializedHooks = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    var isLibXposedEntryLoaded: Boolean = false
        private set

    @Volatile
    var processName: String = "unknown"
        private set

    @Volatile
    var isSystemServer: Boolean = false
        private set

    fun bindModule(module: XposedModule, processName: String, isSystemServer: Boolean) {
        isLibXposedEntryLoaded = true
        this.processName = processName
        this.isSystemServer = isSystemServer
        ModuleResourceBridge.bind(module.moduleApplicationInfo)
        XposedLogAdapter.bind(module)
    }

    fun markHookInitialized(ctx: XposedRuntimeContext, tagName: String): Boolean {
        val key = listOf(ctx.processName, ctx.packageName, ctx.isSystemServer, tagName).joinToString("|")
        return initializedHooks.add(key)
    }

    fun noteLegacyEntry(tag: String, callback: String) {
        val message = if (isLibXposedEntryLoaded) {
            "Legacy Xposed entry $callback ignored because libxposed entry is already loaded"
        } else {
            "Legacy Xposed entry $callback ignored; API 101 migration path owns hook initialization"
        }
        log(tag, message)
    }
}

object XposedLogAdapter {
    @Volatile
    private var module: XposedModule? = null

    fun bind(module: XposedModule) {
        this.module = module
    }

    fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        val boundModule = module
        if (boundModule != null) {
            if (throwable == null) {
                boundModule.log(priority, tag, message)
            } else {
                boundModule.log(priority, tag, message, throwable)
            }
        } else {
            if (throwable == null) {
                Log.println(priority, tag, message)
            } else {
                Log.println(priority, tag, "$message\n${Log.getStackTraceString(throwable)}")
            }
        }
    }
}

fun Long.hasXposedProperty(property: Long): Boolean = (this and property) == property

fun XposedInterface.frameworkPropertiesSummary(): String {
    val properties = getFrameworkProperties()
    val names = mutableListOf<String>()
    if (properties.hasXposedProperty(XposedInterface.PROP_CAP_SYSTEM)) names += "PROP_CAP_SYSTEM"
    if (properties.hasXposedProperty(XposedInterface.PROP_CAP_REMOTE)) names += "PROP_CAP_REMOTE"
    if (properties.hasXposedProperty(XposedInterface.PROP_RT_API_PROTECTION)) names += "PROP_RT_API_PROTECTION"
    return "0x${properties.toString(16)}" + if (names.isEmpty()) "" else " (${names.joinToString()})"
}
