package io.github.nitsuya.aa.display.xposed

import android.os.Process
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.hook.AndroidAuoHook
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.hook.BaseHook
import io.github.nitsuya.aa.display.xposed.hook.OtherHook

class LibXposedInit : XposedModule() {
    companion object {
        const val TAG = "AADisplay_LibXposedInit"
        private const val PACKAGE_ANDROID = "android"
        private const val PACKAGE_GEARHEAD = "com.google.android.projection.gearhead"
        private val OTHER_SCOPED_PACKAGES = setOf(
            "com.autonavi.amapauto",
            "com.ss.squarehome2",
        )
    }

    private var loadedProcessName: String = "unknown"
    private var loadedAsSystemServer: Boolean = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        loadedProcessName = param.processName
        loadedAsSystemServer = param.isSystemServer
        HookRuntime.bindModule(this, param.processName, param.isSystemServer)

        val properties = getFrameworkProperties()
        log(
            TAG,
            "module loaded process=${param.processName}, system=${param.isSystemServer}, " +
                "framework=${getFrameworkName()} ${getFrameworkVersion()}(${getFrameworkVersionCode()}), " +
                "api=${getApiVersion()}, properties=${frameworkPropertiesSummary()}"
        )
        if (!properties.hasXposedProperty(XposedInterface.PROP_CAP_SYSTEM)) {
            log(TAG, "framework does not advertise PROP_CAP_SYSTEM; system_server hooks will be skipped")
        }
        if (!properties.hasXposedProperty(XposedInterface.PROP_CAP_REMOTE)) {
            log(TAG, "framework does not advertise PROP_CAP_REMOTE; remote preferences must not be assumed")
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        log(
            TAG,
            "package loaded package=${param.packageName}, process=$loadedProcessName, first=${param.isFirstPackage}"
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        when {
            packageName == PACKAGE_GEARHEAD -> {
                initHooks(createPackageContext(param), AndroidAuoHook)
            }

            packageName == BuildConfig.APPLICATION_ID -> {
                log(TAG, "skip module package in process=$loadedProcessName")
            }

            param.applicationInfo.uid == Process.SYSTEM_UID -> {
                log(TAG, "skip system uid package=$packageName process=$loadedProcessName")
            }

            packageName in OTHER_SCOPED_PACKAGES -> {
                initHooks(createPackageContext(param), OtherHook)
            }

            else -> {
                log(TAG, "skip non-target scoped package=$packageName process=$loadedProcessName")
            }
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val properties = getFrameworkProperties()
        if (!properties.hasXposedProperty(XposedInterface.PROP_CAP_SYSTEM)) {
            log(TAG, "skip system_server initialization because PROP_CAP_SYSTEM is missing")
            return
        }

        initHooks(
            XposedRuntimeContext(
                module = this,
                classLoader = param.classLoader,
                packageName = PACKAGE_ANDROID,
                processName = if (loadedProcessName == "unknown") "system_server" else loadedProcessName,
                applicationInfo = null,
                isFirstPackage = true,
                isSystemServer = true,
            ),
            AndroidHook,
        )
    }

    private fun createPackageContext(param: XposedModuleInterface.PackageReadyParam): XposedRuntimeContext {
        return XposedRuntimeContext(
            module = this,
            classLoader = param.classLoader,
            packageName = param.packageName,
            processName = resolveProcessName(param),
            applicationInfo = param.applicationInfo,
            isFirstPackage = param.isFirstPackage,
            isSystemServer = loadedAsSystemServer,
        )
    }

    private fun resolveProcessName(param: XposedModuleInterface.PackageLoadedParam): String {
        return loadedProcessName
            .takeIf { it.isNotBlank() && it != "unknown" }
            ?: currentProcessName()
            ?: param.applicationInfo.processName
            ?: param.packageName
    }

    private fun initHooks(ctx: XposedRuntimeContext, vararg hooks: BaseHook) {
        hooks.forEach { hook ->
            if (!HookRuntime.markHookInitialized(ctx, hook.tagName)) {
                log(TAG, "skip duplicate hook=${hook.tagName} package=${ctx.packageName} process=${ctx.processName}")
                return@forEach
            }
            runCatching {
                hook.init(ctx)
                hook.isInit = true
                log(TAG, "inited hook=${hook.tagName} package=${ctx.packageName} process=${ctx.processName}")
            }.onFailure { error ->
                log(TAG, "failed init hook=${hook.tagName} package=${ctx.packageName} process=${ctx.processName}", error)
            }
        }
    }

    private fun currentProcessName(): String? {
        return runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentProcessName")
                .invoke(null) as? String
        }.getOrNull()
    }
}
