package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import android.content.res.Resources
import io.github.nitsuya.aa.display.xposed.ManagedHookHandle
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext
import io.github.nitsuya.aa.display.xposed.log

object OtherHook : BaseHook() {
    override val tagName: String = "AAD_OtherHook"
    override fun init(ctx: XposedRuntimeContext) {
        try {
            log(tagName, "${ctx.packageName}, ${ctx.applicationInfo?.uid}, ${ctx.isFirstPackage}, ${ctx.processName}")
            var onCreateApplication: ManagedHookHandle? = null
            onCreateApplication = ctx.hookBefore(ctx.findMethod(Instrumentation::class.java) {
                name == "callApplicationOnCreate"
                    && parameterCount == 1
                    && parameterTypes[0] == Application::class.java
            }) { param ->
                onCreateApplication?.unhook()
                runCatching {
                    val application = param.args[0] as? Application
                    if (application == null) {
                        log(tagName, "callApplicationOnCreate arg0 is not Application")
                    } else {
                        val id = application.resources.getIdentifier("status_bar_height", "dimen", "android")
                        ctx.findAllMethods(Resources::class.java) {
                            (name == "getDimension"
                                || name == "getDimensionPixelSize"
                                || name == "getDimensionPixelOffset")
                                && parameterCount == 1
                                && parameterTypes[0] == Int::class.javaPrimitiveType
                        }.forEach { method ->
                            ctx.hookBefore(method) { resourceParam ->
                                if (resourceParam.args[0] != id) {
                                    return@hookBefore
                                }
                                resourceParam.returnEarly(if (method.name == "getDimension") 0F else 0)
                            }
                        }
                    }
                }.onFailure { e ->
                    log(tagName, "StatusBarHeight app create", e)
                }
            }
        } catch (e: Throwable) {
            log(tagName, "StatusBarHeight", e)
        }
    }
}
