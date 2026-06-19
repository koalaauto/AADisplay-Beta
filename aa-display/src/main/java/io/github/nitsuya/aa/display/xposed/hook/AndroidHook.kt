package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.IsSystemEnv
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.BridgeService
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.ManagedHookHandle
import io.github.nitsuya.aa.display.xposed.RemoteConfigProvider
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext
import io.github.nitsuya.aa.display.xposed.getObject
import io.github.nitsuya.aa.display.xposed.getObjectAs
import io.github.nitsuya.aa.display.xposed.invokeMethod
import io.github.nitsuya.aa.display.xposed.log
import io.github.qauxv.util.Initiator
import java.util.Collections

object AndroidHook : BaseHook() {
    override val tagName: String = "AAD_AndroidHook"

    private var runtimeContext: XposedRuntimeContext? = null

    override fun init(ctx: XposedRuntimeContext) {
        runtimeContext = ctx
        Initiator.init(ctx.classLoader)
        CoreManagerService.setConfigProvider(RemoteConfigProvider(ctx, AADisplayConfig.ConfigName, CoreManagerService.TAG))
        log(tagName, "xposed init")

        hookPackageService(ctx)
        hookActivityManagerService(ctx)
        hookDisplayLaunchPermission(ctx)
    }

    private fun hookPackageService(ctx: XposedRuntimeContext) {
        runCatching {
            var serviceManagerHooks: List<ManagedHookHandle> = emptyList()
            val addServiceMethods = ctx.findAllMethods("android.os.ServiceManager") {
                name == "addService"
                    && parameterCount >= 2
                    && parameterTypes[0] == String::class.java
                    && IBinder::class.java.isAssignableFrom(parameterTypes[1])
            }
            serviceManagerHooks = addServiceMethods.map { method ->
                ctx.hookBefore(method) { param ->
                    if (param.args.getOrNull(0) == "package") {
                        serviceManagerHooks.forEach { it.unhook() }
                        val binder = param.args.getOrNull(1) as? IBinder ?: return@hookBefore
                        val pms = (binder as? IPackageManager)
                            ?: IPackageManager.Stub.asInterface(binder)
                            ?: return@hookBefore
                        log(tagName, "Got pms: $pms")
                        runCatching {
                            BridgeService.register(ctx, pms)
                            log(tagName, "Bridge service injected")
                        }.onFailure {
                            log(tagName, "System service crashed", it)
                        }
                    }
                }
            }
            if (serviceManagerHooks.isEmpty()) {
                log(tagName, "no ServiceManager.addService overload found")
            }
        }.onFailure {
            log(tagName, "ServiceManager.addService", it)
        }
    }

    private fun hookActivityManagerService(ctx: XposedRuntimeContext) {
        runCatching {
            var constructorHooks: List<ManagedHookHandle> = emptyList()
            val constructors = ctx.findAllConstructors("com.android.server.am.ActivityManagerService") {
                parameterTypes.isNotEmpty() && parameterTypes[0] == Context::class.java
            }
            constructorHooks = constructors.map { constructor ->
                ctx.hookAfter(constructor) { param ->
                    constructorHooks.forEach { it.unhook() }
                    CoreManagerService.systemContext = param.thisObject!!.getObjectAs("mUiContext")
                    log(tagName, "get systemUiContext")
                }
            }
            if (constructorHooks.isEmpty()) {
                log(tagName, "no constructor with parameterTypes[0] == Context found")
            }
        }.onFailure {
            log(tagName, "ActivityManagerService constructor", it)
        }

        runCatching {
            var systemReadyHooks: List<ManagedHookHandle> = emptyList()
            val methods = ctx.findAllMethods("com.android.server.am.ActivityManagerService") {
                name == "systemReady"
            }
            systemReadyHooks = methods.map { method ->
                ctx.hookAfter(method) {
                    systemReadyHooks.forEach { it.unhook() }
                    runCatching {
                        CoreManagerService.systemReady()
                        log(tagName, "system ready")
                    }.onFailure { error ->
                        log(tagName, "systemReady callback", error)
                    }
                }
            }
            if (systemReadyHooks.isEmpty()) {
                log(tagName, "no ActivityManagerService.systemReady overload found")
            }
        }.onFailure {
            log(tagName, "ActivityManagerService.systemReady", it)
        }
    }

    private fun hookDisplayLaunchPermission(ctx: XposedRuntimeContext) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val className = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "com.android.server.wm.ActivityTaskSupervisor"
        } else {
            "com.android.server.wm.ActivityStackSupervisor"
        }
        runCatching {
            ctx.hookAfter(ctx.findMethod(className) {
                name == "isCallerAllowedToLaunchOnDisplay"
                    && parameterCount == 4
                    && parameterTypes[0] == Int::class.javaPrimitiveType
                    && parameterTypes[1] == Int::class.javaPrimitiveType
                    && parameterTypes[2] == Int::class.javaPrimitiveType
                    && parameterTypes[3] == ActivityInfo::class.java
            }) { param ->
                if (param.result == false && param.args.getOrNull(2) == CoreManagerService.getDisplayId()) {
                    param.result = true
                    log(tagName, "hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        }.onFailure {
            log(tagName, "$className.isCallerAllowedToLaunchOnDisplay", it)
        }
    }

    object Power {
        private var hookPower: ManagedHookHandle? = null

        fun hook() {
            unHook()
            val ctx = runtimeContext
            if (!IsSystemEnv || ctx == null) return
            hookPower = runCatching {
                ctx.hookBefore(ctx.findMethod("com.android.server.policy.PhoneWindowManager") {
                    name == "powerPress"
                        && parameterCount == 3
                        && parameterTypes[0] == Long::class.javaPrimitiveType
                        && parameterTypes[1] == Int::class.javaPrimitiveType
                        && parameterTypes[2] == Boolean::class.javaPrimitiveType
                }) { param ->
                    if (!(param.args[2] as Boolean)) {
                        CoreApi.toggleDisplayPower()
                        param.returnEarly(null)
                    } else {
                        CoreApi.displayPower(true)
                    }
                }
            }.onFailure {
                log(tagName, "Power PhoneWindowManager.powerPress", it)
            }.getOrNull()
        }

        fun unHook() {
            hookPower?.unhook()
            hookPower = null
        }
    }

    object FuckAppUseApplicationContext {
        private val appInitUseDisplay = Collections.synchronizedMap(HashMap<String, Int>())
        private var activityTaskManagerServiceStartProcessAsyncHook: ManagedHookHandle? = null
        private var applicationThreadBindApplicationHook: ManagedHookHandle? = null

        fun hook() {
            unHook()
            val ctx = runtimeContext
            if (!IsSystemEnv || ctx == null) return

            activityTaskManagerServiceStartProcessAsyncHook = runCatching {
                ctx.hookBefore(ctx.findMethod("com.android.server.wm.ActivityTaskManagerService") {
                    name == "startProcessAsync"
                }) { param ->
                    try {
                        val activityRecord = param.args[0] ?: return@hookBefore
                        val displayId = activityRecord.invokeMethod("getDisplayId") as Int
                        val packageName = activityRecord.getObject("packageName") as String
                        if (displayId == 0) {
                            appInitUseDisplay.remove(packageName)
                            return@hookBefore
                        }
                        appInitUseDisplay[packageName] = displayId
                    } catch (e: Exception) {
                        log(tagName, "activityTaskManagerService_startProcessAsync Hook Exception", e)
                    }
                }
            }.onFailure {
                log(tagName, "FuckAppUseAppContext ActivityTaskManagerService.startProcessAsync method", it)
            }.getOrNull()

            applicationThreadBindApplicationHook = runCatching {
                ctx.hookBefore(ctx.findMethod("android.app.IApplicationThread\$Stub\$Proxy") {
                    name == "bindApplication"
                }) { param ->
                    try {
                        val configuration = param.args.getOrNull(15)
                        if (configuration !is Configuration) {
                            return@hookBefore
                        }
                        val packageName = (param.args[0] as String).substringBeforeLast(":")
                        if (appInitUseDisplay.containsKey(packageName)) {
                            val densityDpi = CoreManagerService.getDensityDpi()
                            if (densityDpi != 0) {
                                configuration.densityDpi = densityDpi
                            }
                        }
                    } catch (e: Exception) {
                        log(tagName, "applicationThread_bindApplication Hook Exception", e)
                    }
                }
            }.onFailure {
                log(tagName, "FuckAppUseAppContext IApplicationThread.bindApplication method", it)
            }.getOrNull()
        }

        fun unHook() {
            appInitUseDisplay.clear()
            activityTaskManagerServiceStartProcessAsyncHook?.unhook()
            activityTaskManagerServiceStartProcessAsyncHook = null

            applicationThreadBindApplicationHook?.unhook()
            applicationThreadBindApplicationHook = null
        }
    }
}
