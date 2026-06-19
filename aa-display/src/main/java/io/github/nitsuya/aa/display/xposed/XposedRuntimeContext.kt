package io.github.nitsuya.aa.display.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

data class XposedRuntimeContext(
    val module: XposedModule,
    val classLoader: ClassLoader,
    val packageName: String,
    val processName: String,
    val applicationInfo: ApplicationInfo?,
    val isFirstPackage: Boolean,
    val isSystemServer: Boolean,
    val appContext: Context? = null,
) {
    fun withAppContext(context: Context): XposedRuntimeContext = copy(appContext = context)

    fun hookBefore(
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        block: (BeforeHookParam) -> Unit,
    ): ManagedHookHandle {
        return module.hook(executable)
            .setPriority(priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val param = BeforeHookParam(chain)
                block(param)
                if (param.returnEarly) {
                    param.result
                } else {
                    chain.proceed(param.args.toTypedArray())
                }
            }
            .let(::ManagedHookHandle)
    }

    fun hookAfter(
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        block: (AfterHookParam) -> Unit,
    ): ManagedHookHandle {
        return module.hook(executable)
            .setPriority(priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val param = AfterHookParam(chain, chain.proceed())
                block(param)
                param.result
            }
            .let(::ManagedHookHandle)
    }

    fun hookReplace(
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        block: (ReplaceHookParam) -> Any?,
    ): ManagedHookHandle {
        return module.hook(executable)
            .setPriority(priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                block(ReplaceHookParam(chain))
            }
            .let(::ManagedHookHandle)
    }

    fun loadClass(name: String): Class<*> = Class.forName(name, false, classLoader)

    fun findMethod(
        className: String,
        findSuper: Boolean = false,
        predicate: Method.() -> Boolean,
    ): Method = findMethod(loadClass(className), findSuper, predicate)

    fun findMethod(
        clazz: Class<*>,
        findSuper: Boolean = false,
        predicate: Method.() -> Boolean,
    ): Method = findAllMethods(clazz, findSuper, predicate).firstOrNull()
        ?: throw NoSuchMethodException("${clazz.name} method not found")

    fun findAllMethods(
        className: String,
        findSuper: Boolean = false,
        predicate: Method.() -> Boolean,
    ): List<Method> = findAllMethods(loadClass(className), findSuper, predicate)

    fun findAllMethods(
        clazz: Class<*>,
        findSuper: Boolean = false,
        predicate: Method.() -> Boolean,
    ): List<Method> {
        val methods = mutableListOf<Method>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods
                .filter { it.predicate() }
                .forEach {
                    it.isAccessible = true
                    methods += it
                }
            current = if (findSuper) current.superclass else null
        }
        return methods
    }

    fun findConstructor(
        className: String,
        predicate: Constructor<*>.() -> Boolean,
    ): Constructor<*> = findConstructor(loadClass(className), predicate)

    fun findConstructor(
        clazz: Class<*>,
        predicate: Constructor<*>.() -> Boolean,
    ): Constructor<*> = findAllConstructors(clazz, predicate).firstOrNull()
        ?: throw NoSuchMethodException("${clazz.name} constructor not found")

    fun findAllConstructors(
        className: String,
        predicate: Constructor<*>.() -> Boolean,
    ): List<Constructor<*>> = findAllConstructors(loadClass(className), predicate)

    fun findAllConstructors(
        clazz: Class<*>,
        predicate: Constructor<*>.() -> Boolean,
    ): List<Constructor<*>> {
        return clazz.declaredConstructors
            .filter { it.predicate() }
            .onEach { it.isAccessible = true }
    }

    fun log(message: String) = log("AAD_XposedRuntime", message)

    fun log(tag: String, message: String) = io.github.nitsuya.aa.display.xposed.log(tag, message)

    fun log(tag: String, message: String, throwable: Throwable?) =
        io.github.nitsuya.aa.display.xposed.log(tag, message, throwable)
}

class ManagedHookHandle(private val handle: XposedInterface.HookHandle) {
    fun unhook() = handle.unhook()
}

class BeforeHookParam internal constructor(private val chain: XposedInterface.Chain) {
    val args: MutableList<Any?> = chain.args.toMutableList()
    val thisObject: Any? get() = chain.thisObject
    val executable: Executable get() = chain.executable
    var result: Any? = null
        set(value) {
            field = value
            returnEarly = true
        }
    var returnEarly: Boolean = false
        private set

    fun returnEarly(result: Any? = null) {
        this.result = result
        this.returnEarly = true
    }
}

class AfterHookParam internal constructor(
    private val chain: XposedInterface.Chain,
    result: Any?,
) {
    val args: List<Any?> = chain.args
    val thisObject: Any? get() = chain.thisObject
    val executable: Executable get() = chain.executable
    var result: Any? = result
}

class ReplaceHookParam internal constructor(private val chain: XposedInterface.Chain) {
    val args: List<Any?> = chain.args
    val thisObject: Any? get() = chain.thisObject
    val executable: Executable get() = chain.executable
}
