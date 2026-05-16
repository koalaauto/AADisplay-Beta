package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.findConstructor
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getIdByName
import com.github.kyuubiran.ezxhelper.utils.getObjectOrNull
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.staticMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XCallback
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.service.AaActivityService
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.abortMethod
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.template.bases.runMain
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.delay
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object AaUiHook: AaHook() {
    override val tagName: String = "AAD_AaUiHook"

    private var layoutInfoConstructor: Constructor<*>? = null
    private var startMethod: Method? = null

    private var resLayoutLeftResourceId: Int = 0
    private var resLayoutRightResourceId: Int = 0

    // AA 16.7 引入 cielo 布局体系：竖排栏运行时改走 sys_ui_cielo_layout_canonical_vertical_rail_*，
    // 旧 sys_ui_layout_canonical_vertical_rail_* 资源保留但不再被运行时选用。
    // 同时 LayoutInfo 构造器 layoutType 参数由 int 变成混淆枚举（16.7=Lyoi;）。
    // 该枚举 toString() 被重写为返回 ordinal 数字串（所以 logcat 里 "layoutType=10" 是数字，
    // 真实身份是 ordinal/q==10 的常量 PORTRAIT_STANDARD），但 Enum.name() 反射仍返回
    // 可读常量名（构造时 Enum.<init>(String,int) 传的就是 "PORTRAIT_STANDARD" 等）。
    // 因此按 Enum.name() 反查比按 ordinal/混淆类名稳。
    private const val ENUM_VERTICAL_RAIL_LHD = "STANDARD_VERTICAL_RAIL"
    private const val ENUM_VERTICAL_RAIL_RHD = "STANDARD_VERTICAL_RAIL_RTL"

    // 不改写的 layoutType（按 Enum.name() 前缀/全名判断，跨版本稳定）：
    // CLUSTER_* = 仪表盘独立物理屏，AUXILIARY_* = 副屏，UNKNOWN_LAYOUT = 未定，
    // 这些不是我们要投的主内容屏，强行塞竖排栏会错位。其余（STANDARD* / WIDESCREEN* /
    // PORTRAIT_* / SEMI_WIDESCREEN* / SHORT_CANONICAL* 等内容屏）一律改成竖排栏。
    // 这复刻了 16.1 旧 int 逻辑"除 cluster/auxiliary 外都转竖排栏"的设计意图。
    private fun shouldKeepLayoutTypeByName(name: String): Boolean {
        return name == "UNKNOWN_LAYOUT" ||
            name.startsWith("CLUSTER") ||
            name.startsWith("AUXILIARY")
    }

    // 16.7 真正生效的 cielo 竖排栏资源；resolveVerticalRailResId 优先取这两个，
    // getIdentifier==0（更老版本无 cielo）再回退到 16.1 的旧资源名。
    private var resLayoutLeftCieloResourceId: Int = 0
    private var resLayoutRightCieloResourceId: Int = 0

    private var resLayoutGhFacetBarId: Int = 0
    private var resIdStatusBarId: Int = 0
    private var resIdAssistantIconContainerId: Int = 0
    private var resIdAssistantIconId: Int = 0
    private var resIdLauncherAndDashboardIconContainerId: Int = 0
    private var resIdLauncherAndDashboardIconId: Int = 0

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        val classes = bridge.findClass {
            searchPackages = listOf("")
            matcher {
                usingStrings {
                    add(
                        "LayoutInfo{layoutResourceId=",
                        StringMatchType.StartsWith,
                        false
                    )
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            // dexkit 字符串特征命中异常：只记录，不抛——抛出会中断 AndroidAuoHook 的
            // hooks.forEach{loadDexClass}，连带 hook() 全不执行（竖排栏/自动打开全失效）。
            log(tagName, "AaUiHook: not found LayoutInfo class：${classes.size}")
            return
        }
        // 构造器签名解析失败也只记录不抛，理由同上；后续 hookLayout 会因 ctor==null 自动跳过。
        layoutInfoConstructor = runCatching {
            resolveLayoutInfoConstructor(classes[0].name)
        }.onFailure { e ->
            log(tagName, "AaUiHook: resolveLayoutInfoConstructor failed for ${classes[0].name}", e)
        }.getOrNull()

        try{
            startMethod = loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService").staticMethod("a", null, argTypes(Intent::class.java))
        } catch (e: Throwable){
            log(tagName,  "AaUiHook: not found CarSystemUiControllerService.a static method", e)
        }

        resLayoutGhFacetBarId = InitFields.appContext.resources.getIdentifier("gh_coolwalk_vertical_facet_bar", "layout", InitFields.appContext.packageName)
        resIdStatusBarId = getIdByName("status_bar")//android.support.p001v4.app.FragmentContainerView
        resIdAssistantIconContainerId = getIdByName("assistant_icon_container")//com.google.android.apps.auto.components.coolwalk.focusring.FocusInterceptor
        resIdAssistantIconId = getIdByName("assistant_icon")//com.google.android.apps.auto.components.coolwalk.button.CoolwalkButton
        resIdLauncherAndDashboardIconContainerId = getIdByName("launcher_and_dashboard_icon_container")//com.google.android.apps.auto.components.coolwalk.focusring.FocusInterceptor
        resIdLauncherAndDashboardIconId = getIdByName("launcher_and_dashboard_icon")//com.google.android.apps.auto.components.coolwalk.button.CoolwalkButton

        resLayoutLeftResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_layout_canonical_vertical_rail_lhd", "layout", InitFields.appContext.packageName)
        resLayoutRightResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_layout_canonical_vertical_rail_rhd", "layout", InitFields.appContext.packageName)

        // AA 16.7+ cielo 竖排栏资源（旧版本 getIdentifier 返回 0，自然回退到旧资源）
        resLayoutLeftCieloResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_cielo_layout_canonical_vertical_rail_lhd", "layout", InitFields.appContext.packageName)
        resLayoutRightCieloResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_cielo_layout_canonical_vertical_rail_rhd", "layout", InitFields.appContext.packageName)

        assert(resLayoutGhFacetBarId != 0) { "resLayoutGhFacetBarId not fund" }
        assert(resIdStatusBarId != 0) { "resIdStatusBarId not fund" }
        assert(resIdAssistantIconContainerId != 0) { "resIdAssistantIconContainerId not fund" }
        assert(resIdAssistantIconId != 0) { "resIdAssistantIconId not fund" }
        assert(resIdLauncherAndDashboardIconContainerId != 0) { "resIdLauncherAndDashboardIconContainerId not fund" }
        assert(resIdLauncherAndDashboardIconId != 0) { "resIdLauncherAndDashboardIconId not fund" }

        // 至少要有一套竖排栏资源可用（cielo 新版 或 legacy 旧版），全 0 才是真异常
        assert(resLayoutLeftResourceId != 0 || resLayoutLeftCieloResourceId != 0) { "vertical rail (lhd) resource not fund" }
        assert(resLayoutRightResourceId != 0 || resLayoutRightCieloResourceId != 0) { "vertical rail (rhd) resource not fund" }
        log(tagName, "AaUiHook: rail res legacy(l=$resLayoutLeftResourceId,r=$resLayoutRightResourceId) cielo(l=$resLayoutLeftCieloResourceId,r=$resLayoutRightCieloResourceId)")


    }

    override fun hook(config: SharedPreferences, lpparam: XC_LoadPackage.LoadPackageParam) {
        log(tagName,  "AaUiHook: ~~~~~~~~~~~~~~~~~~~~~~~~~~~")
        hookBaseClick()
        hookLayout()
        hookFacetBar(config)
        hookRadius(config)
    }

    private fun printBundle(extras: Bundle, index: Int): String{
        val keys = extras.keySet()
        return keys.joinToString { it } + " \r\n " + keys.mapNotNull { key ->
            val value = extras.get(key)
            if (value == null) "$key -> null"
            if (value is Bundle) {
                "$key -> Type:Bundle, ${printBundle(value, index + 1)}"
            } else {
                "$key -> ${value.toString()}, Type:${value!!::class.java.name}"
            }
        }.joinToString(separator = "\r\n", prefix = "    ".repeat(index)) { it }
    }

    // 竖排栏资源解析：优先 16.7 cielo，回退 16.1 legacy。
    private fun resolveVerticalRailResId(isRightHandDrive: Boolean): Int {
        return if (isRightHandDrive) {
            if (resLayoutRightCieloResourceId != 0) resLayoutRightCieloResourceId else resLayoutRightResourceId
        } else {
            if (resLayoutLeftCieloResourceId != 0) resLayoutLeftCieloResourceId else resLayoutLeftResourceId
        }
    }

    // 按 Enum.name() 在该枚举类里反查目标常量（AA 16.7 layoutType 是混淆枚举，
    // 类名不稳但常量名 STANDARD_VERTICAL_RAIL[_RTL] 跨版本稳定）。
    private fun findEnumConstantByName(enumClass: Class<*>, targetName: String): Any? {
        return runCatching {
            enumClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == targetName }
        }.getOrNull()
    }

    // 一次性诊断打点：hookBefore 每帧都会调用，不能每次都 log（会刷屏），
    // 但首次命中各分支时各打一条，便于实机 grep 确认改写真的发生。
    @Volatile private var loggedIntDecision = false
    @Volatile private var loggedEnumDecision = false
    @Volatile private var loggedSkipDecision = false

    private fun hookLayout() {
        val ctor = layoutInfoConstructor
        if (ctor == null) {
            log(tagName, "AaUiHook: layoutInfoConstructor unresolved, skip hookLayout")
            return
        }
        ctor.hookAfter { param -> log(tagName, param.thisObject.toString()) }
        ctor.hookBefore { param ->
            try {
                if (param.args.size < 5) return@hookBefore
                val layoutTypeArg = param.args[3]

                when (layoutTypeArg) {
                    // 16.1 及更早：layoutType 是 int，3=左舵竖排栏 4=右舵竖排栏，8/9/10=cluster/auxiliary 跳过
                    is Int -> {
                        when (layoutTypeArg) {
                            8, 9, 10 -> return@hookBefore
                        }
                        val isRightHandDrive = param.args[4] as Boolean
                        param.args[0] = resolveVerticalRailResId(isRightHandDrive)
                        param.args[3] = if (isRightHandDrive) 4 else 3 // layoutType left:3 right:4
                        if (param.args.size > 5 && param.args[5] is Boolean) {
                            param.args[5] = true // hasVerticalRail
                        }
                        if (!loggedIntDecision) {
                            loggedIntDecision = true
                            log(tagName, "AaUiHook: layout rewrite(int) -> verticalRail rhd=$isRightHandDrive resId=${param.args[0]}")
                        }
                    }
                    // 16.7 cielo：layoutType 是混淆枚举，按 Enum.name() 换成 STANDARD_VERTICAL_RAIL[_RTL]。
                    // 之前误用 name.startsWith("STANDARD") 把 PORTRAIT_STANDARD(=ordinal 10) 这种
                    // 内容屏排除掉了，导致竖屏 DHU 拿不到竖排栏 —— 改为按 keep 名单反向判断。
                    is Enum<*> -> {
                        val name = layoutTypeArg.name
                        if (shouldKeepLayoutTypeByName(name)) {
                            if (!loggedSkipDecision) {
                                loggedSkipDecision = true
                                log(tagName, "AaUiHook: layout keep(enum) name=$name (cluster/auxiliary/unknown)")
                            }
                            return@hookBefore
                        }
                        val isRightHandDrive = param.args[4] as Boolean
                        val targetEnumName = if (isRightHandDrive) ENUM_VERTICAL_RAIL_RHD else ENUM_VERTICAL_RAIL_LHD
                        val targetEnum = findEnumConstantByName(layoutTypeArg.javaClass, targetEnumName)
                        if (targetEnum == null) {
                            log(tagName, "AaUiHook: enum $targetEnumName not found in ${layoutTypeArg.javaClass.name}, keep original (was $name)")
                            return@hookBefore
                        }
                        param.args[0] = resolveVerticalRailResId(isRightHandDrive)
                        param.args[3] = targetEnum
                        if (param.args.size > 5 && param.args[5] is Boolean) {
                            param.args[5] = true // hasVerticalRail
                        }
                        if (!loggedEnumDecision) {
                            loggedEnumDecision = true
                            log(tagName, "AaUiHook: layout rewrite(enum) $name -> $targetEnumName rhd=$isRightHandDrive resId=${param.args[0]}")
                        }
                    }
                    else -> {
                        log(tagName, "AaUiHook: unexpected layoutType arg type=${layoutTypeArg?.javaClass?.name}, skip")
                        return@hookBefore
                    }
                }
            } catch (e: Throwable) {
                log(tagName, "AaUiHook: hookLayout error", e)
            }
        }
    }

    private fun resolveLayoutInfoConstructor(className: String): Constructor<*> {
        // New AA versions frequently change obfuscated ctor tails; keep only stable prefix checks.
        log(tagName, "AaUiHook: resolveLayoutInfoConstructor for $className")

        // strict A — AA 16.7 cielo：(int,int,int, <layoutType enum 非基本类型>, bool, bool,
        //            <carDisplayUiInfo 非基本类型>, bool, bool, bool) 共 10 参
        val strictMatch167 = runCatching {
            findConstructor(className) {
                parameterCount == 10
                    && parameterTypes[0] == Int::class.javaPrimitiveType
                    && parameterTypes[1] == Int::class.javaPrimitiveType
                    && parameterTypes[2] == Int::class.javaPrimitiveType
                    && !parameterTypes[3].isPrimitive            // layoutType: enum
                    && parameterTypes[4] == Boolean::class.javaPrimitiveType
                    && parameterTypes[5] == Boolean::class.javaPrimitiveType
                    && !parameterTypes[6].isPrimitive            // carDisplayUiInfo
                    && parameterTypes[7] == Boolean::class.javaPrimitiveType
                    && parameterTypes[8] == Boolean::class.javaPrimitiveType
                    && parameterTypes[9] == Boolean::class.javaPrimitiveType
            }
        }.getOrNull()
        if (strictMatch167 != null) {
            log(tagName, "AaUiHook: strict 16.7 ctor selected, paramCount=10")
            return strictMatch167
        }

        // strict B — AA 16.1 及更早：(int,int,int,int, bool, bool, ?, bool) 共 8 参
        val strictMatch161 = runCatching {
            findConstructor(className) {
                parameterCount == 8
                    && parameterTypes[0] == Int::class.javaPrimitiveType
                    && parameterTypes[1] == Int::class.javaPrimitiveType
                    && parameterTypes[2] == Int::class.javaPrimitiveType
                    && parameterTypes[3] == Int::class.javaPrimitiveType
                    && parameterTypes[4] == Boolean::class.javaPrimitiveType
                    && parameterTypes[5] == Boolean::class.javaPrimitiveType
                    && parameterTypes[7] == Boolean::class.javaPrimitiveType
            }
        }.getOrNull()
        if (strictMatch161 != null) {
            log(tagName, "AaUiHook: strict 16.1 ctor selected, paramCount=8")
            return strictMatch161
        }

        // fallback — 仅锁定最稳定的前缀 (int,int,int, layoutType, isRightHandDrive:bool)，
        // 不再要求 layoutType 是 int（16.7 已变 enum），挑参数最多的那个有参构造器。
        val clazz = loadClass(className)
        val fallback = clazz.declaredConstructors
            .filter { ctor ->
                val p = ctor.parameterTypes
                p.size >= 5
                    && p[0] == Int::class.javaPrimitiveType
                    && p[1] == Int::class.javaPrimitiveType
                    && p[2] == Int::class.javaPrimitiveType
                    && p[4] == Boolean::class.javaPrimitiveType
            }
            .maxByOrNull { it.parameterCount }
            ?: throw NoSuchMethodException("AaUiHook: not found compatible LayoutInfo constructor for $className")

        fallback.isAccessible = true
        log(tagName, "AaUiHook: fallback constructor selected, paramCount=${fallback.parameterCount}, arg3=${fallback.parameterTypes[3].name}")
        return fallback
    }

    private fun hookFacetBar(config: SharedPreferences) {
        val enableDefVoiceAssist = AADisplayConfig.VoiceAssistShell.get(config).isNullOrBlank()
        val closeLauncherDashboard = AADisplayConfig.CloseLauncherDashboard.get(config)
        val autoOpen = AADisplayConfig.AutoOpen.get(config)
        findMethod(LayoutInflater::class.java) {
            name == "inflate"
            && parameterCount == 3
            && parameterTypes[0] == Int::class.javaPrimitiveType // resource
            && parameterTypes[1] == ViewGroup::class.java // root
            && parameterTypes[2] == Boolean::class.javaPrimitiveType // attachToRoot
        }.hookAfter { param ->
            if (param.args[0] as Int != resLayoutGhFacetBarId) {
                return@hookAfter
            }
            val resultViewGroup = param.result as ViewGroup? ?: return@hookAfter //androidx.constraintlayout.widget.ConstraintLayout
            val ctx = (param.thisObject as LayoutInflater).context
            val ctx2 = CommonContextWrapper.createAppCompatContext(ctx)
            val layoutInflater = LayoutInflater.from(ctx2)
            val resultViewGroupParent = (resultViewGroup.parent as ViewGroup?)?.apply {
                removeView(resultViewGroup)
            }
            if(closeLauncherDashboard){
                resultViewGroup.findViewById<View>(resIdLauncherAndDashboardIconId).apply {
                    setOnClickFinallyListener {
                        performLongClick()
                    }
                }
            }
            val aaFacetBar = layoutInflater.inflate(R.layout.aa_facet_bar, resultViewGroupParent, false) as ConstraintLayout
            if(autoOpen){
                aaFacetBar.post {
                    runMain {
                        delay(1000)
                        try{
                            startMethod?.invoke(null, Intent().apply {
                                component = ComponentName(BuildConfig.APPLICATION_ID, AaActivityService::class.java.name)
                                putExtra("android.intent.extra.PACKAGE_NAME", BuildConfig.APPLICATION_ID)
                            })
                        } catch (e: Throwable) {
                            log(tagName, "CarSystemUiControllerService.a start app error", e)
                        }
                    }
                }
            }
            val createBtn: (resId: Int, block: View.() -> Unit) -> Int = { resId, block ->
                val btn = ImageView(ctx).apply {
                    id = View.generateViewId()
                    layoutParams = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setImageResource(resId)
                }
                block(btn)
                aaFacetBar.addView(btn)
                btn.id
            }
            val topIds = arrayListOf(
                resIdStatusBarId,
                resIdLauncherAndDashboardIconContainerId
            )
            val bottomIds = arrayListOf(
                createBtn(R.drawable.ic_aa_home_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_HOME)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
                createBtn(R.drawable.ic_aa_fullscreen_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_APP_SWITCH)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
                createBtn(R.drawable.ic_aa_arrow_back_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_BACK)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
            )
//                createBtn(R.drawable.ic_aa_filter_none_44){
//                    val intentClick = Intent().apply {
//                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
//                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_DEMO_APP_1)
//                    }
//                    setOnClickListener {
//                        ctx.sendBroadcast(intentClick)
//                    }
//                    setPadding(0, 5, 0, 2)
//                },
//                createBtn(R.drawable.ic_aa_phone_44){
//                    val intentClick = Intent().apply {
//                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
//                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_FEATURED_APP_1)
//                    }
//                    setOnClickListener {
//                        ctx.sendBroadcast(intentClick)
//                    }
//                    setPadding(0, 5, 0, 10)
//                },
//                resultViewGroup.findViewById<View>(resIdAssistantIconId).run {
//                    if(!enableDefVoiceAssist){
//                        val intentClick = Intent().apply {
//                            action = AABroadcastConst.ACTION_SCREEN_CONTROL
//                            putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_SEARCH)
//                        }
//                        setOnClickFinallyListener {
//                            ctx.sendBroadcast(intentClick)
//                        }
//                    }
//                    resIdAssistantIconContainerId
//                },
//          arrayListOf(resIdStatusBarId, resIdLauncherAndDashboardIconContainerId, resIdAssistantIconContainerId).forEach { vId ->
            arrayListOf(resIdStatusBarId, resIdLauncherAndDashboardIconContainerId).forEach { vId ->
                val view = resultViewGroup.findViewById<View>(vId)
                (view.parent as ViewGroup?)?.apply {
                    removeView(view)
                }
                aaFacetBar.addView(view)
            }
//            val statusBarOverlayId = View(ctx).run {
//                id = View.generateViewId()
//                layoutParams = ConstraintLayout.LayoutParams(0, 0)
//                val intentClick = Intent().apply {
//                    action = AABroadcastConst.ACTION_SCREEN_CONTROL
//                    putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_POWER)
//                }
//                setOnClickListener {
//                    ctx.sendBroadcast(intentClick)
//                }
//                val statusBar = aaFacetBar.findViewById<ViewGroup>(resIdStatusBarId)
//                setOnLongClickListener {
//                    if(statusBar.childCount > 0){
//                        statusBar.getChildAt(0).performClick()
//                    }
//                    true
//                }
//                aaFacetBar.addView(this)
//                id
//            }
            val set = ConstraintSet()
            set.clone(aaFacetBar)
            bottomIds.forEachIndexed { index, vId ->
                set.connect(vId, ConstraintSet.BOTTOM, if(index == 0) ConstraintSet.PARENT_ID else bottomIds[index-1], if(index == 0) ConstraintSet.BOTTOM else ConstraintSet.TOP, 0)
                set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
                set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            }
            topIds.forEachIndexed { index, vId ->
                set.connect(vId, ConstraintSet.TOP, if(index == 0) ConstraintSet.PARENT_ID else topIds[index-1], if(index == 0) ConstraintSet.TOP else ConstraintSet.BOTTOM, 0)
                set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
                set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            }
//            set.connect(statusBarOverlayId, ConstraintSet.BOTTOM, resIdStatusBarId, ConstraintSet.BOTTOM, 0)
//            set.connect(statusBarOverlayId, ConstraintSet.TOP, resIdStatusBarId, ConstraintSet.TOP, 0)
//            set.connect(statusBarOverlayId, ConstraintSet.END, resIdStatusBarId, ConstraintSet.END, 0)
//            set.connect(statusBarOverlayId, ConstraintSet.START, resIdStatusBarId, ConstraintSet.START, 0)
            set.applyTo(aaFacetBar)
            resultViewGroup.visibility = View.GONE
            aaFacetBar.addView(resultViewGroup)
            param.result = aaFacetBar
        }
    }

    private fun hookBaseClick() {
        try {
            findMethod(View::class.java) {
                name == "setOnLongClickListener"
                && parameterCount == 1
                && parameterTypes[0] == View.OnLongClickListener::class.java
            }.hookBefore(XCallback.PRIORITY_LOWEST) {
                if (it.args[0] is FinallyListener) return@hookBefore
                val view = it.thisObject as View
                if (!view.hasOnLongClickListeners() || (view.getObjectOrNull("mListenerInfo")?.getObjectOrNull("mOnLongClickListener") is FinallyListener).not()) {
                    return@hookBefore
                }
                view.setOnOriLongClickListener(it.args[0] as View.OnLongClickListener)
                it.abortMethod()
            }
        } catch (e: Throwable) {
            log(tagName, "hook View.setOnLongClickListener", e)
        }
        try {
            findMethod(View::class.java) {
                name == "setOnClickListener"
                && parameterCount == 1
                && parameterTypes[0] == View.OnClickListener::class.java
            }.hookBefore(XCallback.PRIORITY_LOWEST) {
                if (it.args[0] is FinallyListener) return@hookBefore
                val view = it.thisObject as View
                if (!view.hasOnClickListeners() || (view.getObjectOrNull("mListenerInfo")?.getObjectOrNull("mOnClickListener") is FinallyListener).not()) {
                    return@hookBefore
                }
                view.setOnOriClickListener(it.args[0] as View.OnClickListener)
                it.abortMethod()
            }
        } catch (e: Throwable) {
            log(tagName, "hook View.setOnClickListener", e)
        }
    }

    private fun hookRadius(config: SharedPreferences) {
        if(!AADisplayConfig.ForceRightAngle.get(config)){
            return
        }
        try{
            findConstructor("com.google.android.gms.car.ProjectionWindowDecorationParams"){
                parameterCount == 9
                && parameterTypes[0] == Int::class.javaPrimitiveType //outlineLeft
                && parameterTypes[1] == Int::class.javaPrimitiveType //outlineTop
                && parameterTypes[2] == Int::class.javaPrimitiveType //outlineRight
                && parameterTypes[3] == Int::class.javaPrimitiveType //outlineBottom
                && parameterTypes[4] == Int::class.javaPrimitiveType //corners
                && parameterTypes[5] == Int::class.javaPrimitiveType //cornerRadius
                && parameterTypes[6] == Int::class.javaPrimitiveType //antiAliasingType
                && parameterTypes[7] == Boolean::class.javaPrimitiveType //showOutlinesOnlyWhenInset
                && parameterTypes[8] == Boolean::class.javaPrimitiveType //showRoundedCornersOnlyWhenInset
            }.hookBefore { param ->
                param.args[5] = 0
            }
        } catch (e: Throwable) {
            log(tagName, "ProjectionWindowDecorationParams", e)
        }
    }

    interface FinallyListener
    private fun interface OnClickFinallyListener: View.OnClickListener, FinallyListener
    private fun interface OnLongClickFinallyListener: View.OnLongClickListener, FinallyListener
    private fun View.setOnClickFinallyListener(l: OnClickFinallyListener) = this.setOnClickListener(l)
    private fun View.setOnLongClickFinallyListener(l: OnLongClickFinallyListener) = this.setOnLongClickListener(l)
    private fun View.setOnOriClickListener(l: View.OnClickListener) = this.setTag(R.id.ori_click_listener, l)
    private fun View.setOnOriLongClickListener(l: View.OnLongClickListener) = this.setTag(R.id.ori_long_click_listener, l)
    private fun View.performOriClick() {
        val clickListener = this.getTag(R.id.ori_click_listener) as View.OnClickListener? ?: return
        clickListener.onClick(this)
    }
    private fun View.performOriLongClick(): Boolean {
        val clickListener = this.getTag(R.id.ori_long_click_listener) as View.OnLongClickListener? ?: return false
        return clickListener.onLongClick(this)
    }
}
