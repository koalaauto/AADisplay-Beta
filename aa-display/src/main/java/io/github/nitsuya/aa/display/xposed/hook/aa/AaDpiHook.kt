package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.SharedPreferences
import android.content.pm.InstallSourceInfo
import android.graphics.Point
import android.graphics.Rect
import android.util.Size
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor

object AaDpiHook: AaHook() {
    override val tagName: String = "AAD_AaDpiHook"

    private var displayParamsConstructor: Constructor<*>? = null
    private var carDisplayConstructor: Constructor<*>? = null

    // DisplayParams 的 densityDpi 始终是第 9 个参数（index 8），跨 16.1/16.7 不变。
    private const val DISPLAY_PARAMS_DPI_ARG = 8

    override fun isSupportProcess(processName: String): Boolean {
        return processCar == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, ctx: XposedRuntimeContext) {
        val classes = bridge.findClass {
            searchPackages = listOf("")
            matcher {
                usingStrings {
                    add(
                        "DisplayParams(selectedIndex=",
                        StringMatchType.StartsWith,
                        false
                    )
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            // dexkit 字符串特征命中异常：只记录不抛——抛出会中断 AndroidAuoHook 的
            // hooks.forEach{loadDexClass}（无 try/catch），连累 :car 进程后续 hook 全不执行。
            log(tagName, "AaDpiHook: not found DisplayParams class：${classes.size}")
            return
        }
        displayParamsConstructor = runCatching {
            resolveDisplayParamsConstructor(ctx, classes[0].name)
        }.onFailure { e ->
            log(tagName, "AaDpiHook: resolveDisplayParamsConstructor failed for ${classes[0].name}", e)
        }.getOrNull()

        carDisplayConstructor = runCatching {
            resolveCarDisplayConstructor(ctx)
        }.onFailure { e ->
            log(tagName, "AaDpiHook: resolveCarDisplayConstructor failed", e)
        }.getOrNull()
    }

    // DisplayParams 构造器解析：
    //  - 16.1：18 参，... CarDisplayUiFeatures, int(bitmask)
    //  - 16.7：19 参，新增 CarDisplayBlendedUiConfig 插在 CarDisplayUiFeatures 之后、bitmask 之前
    // densityDpi 始终 index 8。strict 锁定 16.7/16.1 两套签名，fallback 只锁稳定前缀。
    private fun resolveDisplayParamsConstructor(ctx: XposedRuntimeContext, className: String): Constructor<*> {
        log(tagName, "AaDpiHook: resolveDisplayParamsConstructor for $className")

        // strict A — AA 16.7：19 参
        val strict167 = runCatching {
            ctx.findConstructor(className) {
                parameterCount == 19
                && (0..8).all { parameterTypes[it] == Int::class.javaPrimitiveType }    // 0..7 dims/fps + 8 densityDpi
                && parameterTypes[9]        == Float::class.javaPrimitiveType            // pixelAspectRatio
                && parameterTypes[10]       == Int::class.javaPrimitiveType              // depth
                && parameterTypes[11]       == Float::class.javaPrimitiveType            // scaledPixelAspectRatio
                && parameterTypes[12]       == Size::class.java                          // scaledDimensions
                && parameterTypes[13]       == Rect::class.java                          // stableInsets
                && parameterTypes[14]       == Rect::class.java                          // initialInsets
                && parameterTypes[15]       == List::class.java                          // cutouts
                && parameterTypes[16].name  == "com.google.android.gms.car.display.CarDisplayUiFeatures"
                && parameterTypes[17].name  == "com.google.android.gms.car.display.CarDisplayBlendedUiConfig" // 16.7 新增
                && parameterTypes[18]       == Int::class.javaPrimitiveType              // Kotlin default-args bitmask
            }
        }.getOrNull()
        if (strict167 != null) {
            log(tagName, "AaDpiHook: strict 16.7 DisplayParams ctor selected, paramCount=19")
            return strict167
        }

        // strict B — AA 16.1 及更早：18 参
        val strict161 = runCatching {
            ctx.findConstructor(className) {
                parameterCount == 18
                && (0..8).all { parameterTypes[it] == Int::class.javaPrimitiveType }
                && parameterTypes[9]        == Float::class.javaPrimitiveType
                && parameterTypes[10]       == Int::class.javaPrimitiveType
                && parameterTypes[11]       == Float::class.javaPrimitiveType
                && parameterTypes[12]       == Size::class.java
                && parameterTypes[13]       == Rect::class.java
                && parameterTypes[14]       == Rect::class.java
                && parameterTypes[15]       == List::class.java
                && parameterTypes[16].name  == "com.google.android.gms.car.display.CarDisplayUiFeatures"
                && parameterTypes[17]       == Int::class.javaPrimitiveType
            }
        }.getOrNull()
        if (strict161 != null) {
            log(tagName, "AaDpiHook: strict 16.1 DisplayParams ctor selected, paramCount=18")
            return strict161
        }

        // fallback — 只锁稳定前缀：前 9 个 int（含 index 8 densityDpi）+ Size 在某处出现，
        // 取参数最多的那个候选构造器。
        val clazz = ctx.loadClass(className)
        val fallback = clazz.declaredConstructors
            .filter { ctor ->
                val p = ctor.parameterTypes
                p.size >= 13
                    && (0..8).all { p[it] == Int::class.javaPrimitiveType }
                    && p.any { it == Size::class.java }
            }
            .maxByOrNull { it.parameterCount }
            ?: throw NoSuchMethodException("AaDpiHook: not found compatible DisplayParams constructor for $className")
        fallback.isAccessible = true
        log(tagName, "AaDpiHook: fallback DisplayParams ctor selected, paramCount=${fallback.parameterCount}")
        return fallback
    }

    // CarDisplay 构造器解析（类名是稳定的公开 GMS car 类，未混淆）：
    //  - 16.1：9 参，... int initialContentType, String configurationId
    //  - 16.7：10 参，末尾追加 CarDisplayBlendedUiConfig
    // carDisplayType=args[1]、displayDpi=args[2] 位置跨版本不变。
    private fun resolveCarDisplayConstructor(ctx: XposedRuntimeContext): Constructor<*> {
        val className = "com.google.android.gms.car.display.CarDisplay"
        log(tagName, "AaDpiHook: resolveCarDisplayConstructor for $className")

        // strict A — AA 16.7：10 参
        val strict167 = runCatching {
            ctx.findConstructor(className) {
                parameterCount == 10
                && parameterTypes[0].name == "com.google.android.gms.car.display.CarDisplayId"
                && parameterTypes[1] == Int::class.javaPrimitiveType    // carDisplayType
                && parameterTypes[2] == Int::class.javaPrimitiveType    // displayDpi
                && parameterTypes[3] == Point::class.java
                && parameterTypes[4] == Rect::class.java
                && parameterTypes[5] == Rect::class.java
                && parameterTypes[6] == List::class.java
                && parameterTypes[7] == Int::class.javaPrimitiveType    // initialContentType
                && parameterTypes[8] == String::class.java              // configurationId
                && parameterTypes[9].name == "com.google.android.gms.car.display.CarDisplayBlendedUiConfig" // 16.7 新增
            }
        }.getOrNull()
        if (strict167 != null) {
            log(tagName, "AaDpiHook: strict 16.7 CarDisplay ctor selected, paramCount=10")
            return strict167
        }

        // strict B — AA 16.1 及更早：9 参
        val strict161 = runCatching {
            ctx.findConstructor(className) {
                parameterCount == 9
                && parameterTypes[0].name == "com.google.android.gms.car.display.CarDisplayId"
                && parameterTypes[1] == Int::class.javaPrimitiveType
                && parameterTypes[2] == Int::class.javaPrimitiveType
                && parameterTypes[3] == Point::class.java
                && parameterTypes[4] == Rect::class.java
                && parameterTypes[5] == Rect::class.java
                && parameterTypes[6] == List::class.java
                && parameterTypes[7] == Int::class.javaPrimitiveType
                && parameterTypes[8] == String::class.java
            }
        }.getOrNull()
        if (strict161 != null) {
            log(tagName, "AaDpiHook: strict 16.1 CarDisplay ctor selected, paramCount=9")
            return strict161
        }

        // fallback — 只锁稳定前缀：CarDisplayId, int(carDisplayType), int(displayDpi), Point, ...
        val clazz = ctx.loadClass(className)
        val fallback = clazz.declaredConstructors
            .filter { ctor ->
                val p = ctor.parameterTypes
                p.size >= 9
                    && p[0].name == "com.google.android.gms.car.display.CarDisplayId"
                    && p[1] == Int::class.javaPrimitiveType
                    && p[2] == Int::class.javaPrimitiveType
                    && p[3] == Point::class.java
            }
            .maxByOrNull { it.parameterCount }
            ?: throw NoSuchMethodException("AaDpiHook: not found compatible CarDisplay constructor")
        fallback.isAccessible = true
        log(tagName, "AaDpiHook: fallback CarDisplay ctor selected, paramCount=${fallback.parameterCount}")
        return fallback
    }

    override fun hook(config: SharedPreferences, ctx: XposedRuntimeContext) {
        try {
            val androidAutoDpi = AADisplayConfig.AndroidAutoDpi.get(config)
            if (androidAutoDpi < 50) return

            val dpCtor = displayParamsConstructor
            if (dpCtor != null) {
                ctx.hookAfter(dpCtor) { param -> log(tagName, param.thisObject.toString()) }
                ctx.hookBefore(dpCtor) { param ->
                    try {
                        param.args[DISPLAY_PARAMS_DPI_ARG] = androidAutoDpi
                    } catch (e: Throwable) {
                        log(tagName, "AaDpiHook: DisplayParams hookBefore error", e)
                    }
                }
            } else {
                log(tagName, "AaDpiHook: displayParamsConstructor unresolved, skip DPI inject (DisplayParams)")
            }

            val cdCtor = carDisplayConstructor
            if (cdCtor != null) {
                ctx.hookAfter(cdCtor) { param -> log(tagName, param.thisObject.toString()) }
                ctx.hookBefore(cdCtor) { param ->
                    try {
                        if (param.args[1] == 0 && param.args[2] != androidAutoDpi) {
                            param.args[2] = androidAutoDpi
                        }
                    } catch (e: Throwable) {
                        log(tagName, "AaDpiHook: CarDisplay hookBefore error", e)
                    }
                }
            } else {
                log(tagName, "AaDpiHook: carDisplayConstructor unresolved, skip DPI inject (CarDisplay)")
            }
        } catch (e: Throwable) {
            log(tagName, "AaDpiHook: hook error", e)
        }
    }


}
