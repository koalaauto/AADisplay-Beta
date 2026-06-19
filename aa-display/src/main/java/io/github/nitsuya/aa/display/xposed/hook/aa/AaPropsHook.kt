package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ContentResolver
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.database.MergeCursor
import android.net.Uri
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext
import io.github.nitsuya.aa.display.xposed.field
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.putObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.HashMap

object AaPropsHook: AaHook() {
    override val tagName: String = "AAD_AaPropsHook"

    private lateinit var method: Method
    private lateinit var groupField: Field
    private lateinit var keyField: Field
//    private lateinit var defValueField: Field

    override fun isSupportProcess(processName: String): Boolean {
        //return processMain == processName || processProjection == processName || processCar == processName
        return true
    }

    override fun loadDexClass(bridge: DexKitBridge, ctx: XposedRuntimeContext) {
        val methodMatcher = MethodMatcher().usingStrings {
            add(
                "Must call PhenotypeContext.setContext() first",
                StringMatchType.Equals,
                false
            )
        }
//        val fieldName = arrayOf(/*"b", "c",*/ "e")
        val fieldName = arrayOf("a", "b")
        val fieldsInfo = linkedMapOf(
            fieldName[0] to "java.lang.String", //groupField
            fieldName[1] to "java.lang.String", //keyField
//            fieldName[2] to "java.lang.Object"  //defValueField
        )
        val classes = bridge.findClass {
            searchPackages = listOf("")
            matcher {
                fields {
                    fieldsInfo.forEach { (name, typeName) ->
                        add {
                            modifiers(Modifier.PUBLIC)
                            type(typeName)
                            name(name)
                        }
                    }
                }
                methods {
                    add(methodMatcher)
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaPropsHook: not found props class：${classes.size}")
        }
        val methodDatas = classes[0].getMethods().findMethod(FindMethod().matcher(methodMatcher))
        if (methodDatas.isEmpty() || methodDatas.size > 1) {
            throw NoSuchMethodException("AaPropsHook: not found props method：${classes.size}")
        }
        val methodData = methodDatas[0]
        val clazz = ctx.loadClass(methodData.className)
        groupField = clazz.field(fieldName[0]) //com.google.android.projection.gearhead
        keyField = clazz.field(fieldName[1]) //Coolwalk__enabled
//        defValueField = clazz.field(fieldName[2]) //true
        log(tagName, "$clazz#${methodData.methodName}#${fieldName.joinToString()}")
        method = ctx.findMethod(clazz) {
            name == methodData.methodName
//            && parameterCount == 1
        }
    }

    override fun hook(config: SharedPreferences, ctx: XposedRuntimeContext) {
        hookComGoogleAndroidProjectionGearheadProps(ctx, config)
        hookComGoogleAndroidGmsCarProps(ctx, config)
    }

    private fun hookComGoogleAndroidProjectionGearheadProps(ctx: XposedRuntimeContext, config: SharedPreferences?) {
        val props = AADisplayConfig.ComGoogleAndroidProjectionGearheadProps.get(config) ?: return
        if (props.isEmpty) {
            return
        }
        val keyValue = HashMap<String, Any?>(props.size, 1f)
        ctx.hookAfter(method) { param ->
            val thisObject = param.thisObject
            val group = groupField.get(thisObject)
            if (group != "com.google.android.projection.gearhead") {
                return@hookAfter
            }
            val key = keyField.get(thisObject) as String
            if (!props.containsKey(key)){
                return@hookAfter
            }
            val value = keyValue.computeIfAbsent(key) {
//                val defValue = defValueField.get(thisObject) ?: return@computeIfAbsent null
                val value = props[key] as String
                //log(tagName, "$key,$value,${defValue}")
                log(tagName, "$key,$value")
                try {
                    when (param.result?.javaClass ?: return@computeIfAbsent null) {
                        String::class.java -> value
                        java.lang.Boolean::class.java, Boolean::class.java -> value.toBoolean()
                        java.lang.Long::class.java, Long::class.java -> value.toLong()
                        Integer::class.java, Int::class.java -> value.toInt()
                        else -> {
                            val result = param.result
                            value.split(",").forEach { item ->
                                val (key, type, value) = item.split("@", limit = 3)
                                result.putObject(
                                    key,
                                    when(type){
                                        "String" -> value
                                        "Int" -> value.toInt()
                                        "Boolean" -> value.toBoolean()
                                        "Long" -> value.toLong()
                                        else -> return@forEach
                                    }
                                )
                            }
                            null
                        }
                    }
                } catch (e: Throwable) {
                    log(tagName,"Android Auto[com.google.android.projection.gearhead] config, $key=$value convert exception", e)
                    null
                }
            }
            if (value != null) {
                param.result = value
            }
        }
    }

    private fun hookComGoogleAndroidGmsCarProps(ctx: XposedRuntimeContext, config: SharedPreferences?) {
        val props = AADisplayConfig.ComGoogleAndroidGmsCarProps.get(config) ?: return
        if (props.isEmpty) {
            return
        }
        try {
            fun createPropsCursor() = MatrixCursor(arrayOf("key", "value"), props.size).apply {
                props.forEach { prop ->
                    addRow(arrayOf(prop.key, prop.value))
                }
            }
            ctx.hookAfter(ctx.findMethod(ContentResolver::class.java) {
                name == "query"
                && parameterCount == 5
                && parameterTypes[0] == Uri::class.java             // uri
                && parameterTypes[1] == Array<String>::class.java   // projection
                && parameterTypes[2] == String::class.java          // selection
                && parameterTypes[3] == Array<String>::class.java   // selectionArgs
                && parameterTypes[4] == String::class.java          // sortOrder
            }) { param ->
                val uri = param.args[0] as Uri
                if (uri.authority != "com.google.android.gms.phenotype") return@hookAfter
                //log(tagName, "ContentProvider.query: uri: $uri")
                if (uri.path != "/com.google.android.gms.car") return@hookAfter
                //log(AaUiHook.tagName, "GmsCarProps-----${lpparam.processName}------")
                param.result = if (param.result == null) createPropsCursor() else MergeCursor(
                    arrayOf(
                        param.result as Cursor,
                        createPropsCursor()
                    )
                )
            }
        } catch (e: Throwable) {
            log(tagName, "[com.google.android.gms.car] config", e)
        }
    }

}
