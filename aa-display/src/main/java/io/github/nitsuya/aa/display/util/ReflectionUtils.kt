package io.github.nitsuya.aa.display.util

import java.lang.reflect.Field
import java.lang.reflect.Method

fun <T> tryOrNull(block: () -> T): T? = runCatching(block).getOrNull()

fun args(vararg values: Any?): Array<Any?> = arrayOf(*values)

fun argTypes(vararg types: Class<*>): Array<Class<*>> = arrayOf(*types)

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

fun Any.getObjectAs(name: String, type: Class<*>? = null): Any? {
    return javaClass.field(name, findSuper = true, type = type).get(this)
}

fun Class<*>.newInstance(
    args: Array<Any?> = emptyArray(),
    argTypes: Array<Class<*>> = emptyArray(),
): Any {
    return getDeclaredConstructor(*argTypes).apply {
        isAccessible = true
    }.newInstance(*args)
}

fun Any.invokeMethod(
    name: String,
    args: Array<Any?> = emptyArray(),
    argTypes: Array<Class<*>> = emptyArray(),
): Any? {
    val method = javaClass.findMethod(name, argTypes)
    return method.invoke(this, *args)
}

private fun Class<*>.findMethod(name: String, argTypes: Array<Class<*>>): Method {
    var current: Class<*>? = this
    while (current != null) {
        val method = current.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(argTypes)
        }
        if (method != null) {
            method.isAccessible = true
            return method
        }
        current = current.superclass
    }
    throw NoSuchMethodException("${this.name}#$name(${argTypes.joinToString { it.name }})")
}
