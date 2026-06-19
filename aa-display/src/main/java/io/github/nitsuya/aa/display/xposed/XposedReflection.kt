package io.github.nitsuya.aa.display.xposed

import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Method

fun Class<*>.field(
    name: String,
    findSuper: Boolean = false,
    type: Class<*>? = null,
): Field {
    var current: Class<*>? = this
    while (current != null) {
        val field = current.declaredFields.firstOrNull {
            it.name == name && (type == null || it.type == type)
        }
        if (field != null) {
            field.isAccessible = true
            return field
        }
        current = if (findSuper) current.superclass else null
    }
    throw NoSuchFieldException("${this.name}#$name")
}

fun Any.getObject(name: String): Any? = javaClass.field(name, findSuper = true).get(this)

@Suppress("UNCHECKED_CAST")
fun <T> Any.getObjectAs(name: String): T = getObject(name) as T

fun Any.getObjectOrNull(name: String): Any? = runCatching { getObject(name) }.getOrNull()

fun Any.putObject(name: String, value: Any?) {
    javaClass.field(name, findSuper = true).set(this, value)
}

fun Any.invokeMethod(name: String, vararg args: Any?): Any? {
    val method = javaClass.findMethodByArgs(name, findSuper = true, args = args)
    return method.invoke(this, *args)
}

fun Class<*>.staticMethod(
    name: String,
    returnType: Class<*>? = null,
    vararg argTypes: Class<*>,
): Method {
    val method = declaredMethods.firstOrNull {
        it.name == name
            && (returnType == null || it.returnType == returnType)
            && it.parameterTypes.contentEquals(argTypes)
    } ?: throw NoSuchMethodException("${this.name}#$name")
    method.isAccessible = true
    return method
}

fun Context.getIdByName(name: String): Int = resources.getIdentifier(name, "id", packageName)

private fun Class<*>.findMethodByArgs(
    name: String,
    findSuper: Boolean,
    args: Array<out Any?>,
): Method {
    var current: Class<*>? = this
    while (current != null) {
        val method = current.declaredMethods.firstOrNull { method ->
            method.name == name
                && method.parameterTypes.size == args.size
                && method.parameterTypes.withIndex().all { (index, type) ->
                    args[index] == null || type.wrapPrimitive().isInstance(args[index])
                }
        }
        if (method != null) {
            method.isAccessible = true
            return method
        }
        current = if (findSuper) current.superclass else null
    }
    throw NoSuchMethodException("${this.name}#$name(${args.size})")
}

private fun Class<*>.wrapPrimitive(): Class<*> = when (this) {
    java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
    java.lang.Byte.TYPE -> java.lang.Byte::class.java
    java.lang.Character.TYPE -> java.lang.Character::class.java
    java.lang.Short.TYPE -> java.lang.Short::class.java
    java.lang.Integer.TYPE -> java.lang.Integer::class.java
    java.lang.Long.TYPE -> java.lang.Long::class.java
    java.lang.Float.TYPE -> java.lang.Float::class.java
    java.lang.Double.TYPE -> java.lang.Double::class.java
    java.lang.Void.TYPE -> java.lang.Void::class.java
    else -> this
}
