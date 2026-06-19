package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import io.github.nitsuya.aa.display.xposed.ManagedHookHandle
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.RemoteConfigProvider
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBasicsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBtnEventHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaDpiHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaPropsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaSignatureHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaUiHook
import io.github.nitsuya.aa.display.xposed.log
import org.luckypray.dexkit.DexKitBridge
import kotlin.system.measureTimeMillis


abstract class AaHook {
    companion object {
        const val processMain =       "com.google.android.projection.gearhead"
        const val processProjection = "com.google.android.projection.gearhead:projection"
        const val processCar =        "com.google.android.projection.gearhead:car"
    }
    abstract val tagName: String
    abstract fun isSupportProcess(processName: String) : Boolean
    open fun loadDexClass(bridge: DexKitBridge, ctx: XposedRuntimeContext) {}
    abstract fun hook(config: SharedPreferences, ctx: XposedRuntimeContext)
}

object AndroidAuoHook : BaseHook() {
    override val tagName: String = "AAD_AndroidAuoHook"
    override fun init(ctx: XposedRuntimeContext) {
        val processName = ctx.processName
        val hooks = listOf(AaBasicsHook, AaSignatureHook, AaDpiHook, AaBtnEventHook, AaUiHook, AaPropsHook).filter { i -> i.isSupportProcess(processName) }
        if(hooks.isEmpty()) return

        val configProvider = RemoteConfigProvider(ctx, AADisplayConfig.ConfigName, tagName)
        var onCreateApplication: ManagedHookHandle? = null
        onCreateApplication = ctx.hookBefore(ctx.findMethod(Instrumentation::class.java) {
            name == "callApplicationOnCreate"
                && parameterCount == 1
                && parameterTypes[0] == Application::class.java
        }) { param ->
            onCreateApplication?.unhook()
            val application = param.args[0] as? Application
            if (application == null) {
                log(tagName, "callApplicationOnCreate arg0 is not Application")
                return@hookBefore
            }
            runCatching {
                val appCtx = ctx.withAppContext(application)

                System.loadLibrary("dexkit")
                val bridge = DexKitBridge.create(application.applicationInfo.sourceDir)
                if (bridge == null) {
                    log(tagName, "DexKitBridge.create() failed")
                } else {
                    bridge.use {
                        val measureTimeMillis = measureTimeMillis {
                            hooks.forEach { h ->
                                runCatching { h.loadDexClass(it, appCtx) }
                                    .onFailure { e -> log(tagName, "${h.tagName} loadDexClass failed", e) }
                            }
                        }
                        log(tagName, "$processName load class measure ${measureTimeMillis}ms")
                    }
                    configProvider.reload()
                    val configPreferences = configProvider.getPreferences()
                    hooks.forEach { h ->
                        runCatching { h.hook(configPreferences, appCtx) }
                            .onFailure { e -> log(tagName, "${h.tagName} hook failed", e) }
                    }
                }
            }.onFailure { e ->
                log(tagName, "callApplicationOnCreate hook failed", e)
            }
        }
    }
}


