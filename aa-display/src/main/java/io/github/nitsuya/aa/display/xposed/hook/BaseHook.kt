package io.github.nitsuya.aa.display.xposed.hook

import io.github.nitsuya.aa.display.xposed.BeforeHookParam
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext

abstract class BaseHook {
    var isInit: Boolean = false
    abstract val tagName: String
    abstract fun init(ctx: XposedRuntimeContext)
}

fun BeforeHookParam.abortMethod() {
    returnEarly(null)
}
