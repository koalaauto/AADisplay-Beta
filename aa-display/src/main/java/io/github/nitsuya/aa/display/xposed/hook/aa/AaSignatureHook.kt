package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.SharedPreferences
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object AaSignatureHook: AaHook() {
    override val tagName: String = "AAD_AaSignatureHook"

    private lateinit var method: Method

    override fun isSupportProcess(processName: String): Boolean {
        return processCar == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, ctx: XposedRuntimeContext) {
        val methodMatcher = MethodMatcher().apply{
            modifiers = Modifier.PUBLIC or Modifier.FINAL
            returnType = "boolean"
            paramTypes("java.lang.String")
        }
        val classes = bridge.findClass {
            searchPackages = listOf("")
            matcher {
                usingStrings {
                    add(
                        "Package has more than one signature.",
                        StringMatchType.Equals,
                        false
                    )
                }
                methods {
                    add(methodMatcher)
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaSignatureHook: not found SignatureVerifierUtil class：${classes.size}")
        }

        val methodDatas = classes[0].getMethods().findMethod(FindMethod().matcher(methodMatcher))
        if (methodDatas.isEmpty() || methodDatas.size > 1) {
            throw NoSuchMethodException("AaSignatureHook: not found Check method：${classes.size}")
        }
        val methodData = methodDatas[0]
        method = ctx.findMethod(methodData.className) {
            name == methodData.methodName
            && parameterCount == 1
            && parameterTypes[0] == String::class.java
        }
    }
    override fun hook(config: SharedPreferences, ctx: XposedRuntimeContext) {
        ctx.hookAfter(method) { param ->
            if((param.args[0] as String) == BuildConfig.APPLICATION_ID){
                param.result = true
            }
        }
    }
}
