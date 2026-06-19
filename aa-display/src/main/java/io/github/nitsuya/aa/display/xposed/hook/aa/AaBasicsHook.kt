package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.SharedPreferences
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.log

object AaBasicsHook: AaHook() {
    override val tagName: String = "AAD_AaBasicsHook"

    override fun isSupportProcess(processName: String): Boolean {
        return true
    }

    override fun hook(config: SharedPreferences, ctx: XposedRuntimeContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ctx.hookAfter(ctx.findMethod(InstallSourceInfo::class.java) {
                    name == "getInitiatingPackageName"
                }) { param ->
                    param.result = "com.android.vending"
                }
            } catch (e: Throwable) {
                log(tagName, "InstallSourceInfo.getInitiatingPackageName", e)
            }
        } else {
            try {
                ctx.hookAfter(ctx.findMethod(PackageManager::class.java) {
                    name == "getInstallerPackageName"
                }) { param ->
                    param.result = "com.android.vending"
                }
            } catch (e: Throwable) {
                log(tagName, "PackageManager.getInstallerPackageName", e)
            }
        }
    }
}
