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
    // 竖屏底栏走 single-pane 的 PORTRAIT_SHORT（对应 sys_ui_layout_short_portrait）。
    // 不用 PORTRAIT_STANDARD：那是双拼布局（上半地图 widget + 下半 app），
    // 会让镜像内容只占下半屏、上半被 AA 默认 Google Maps 占（实机已证）。
    private const val ENUM_PORTRAIT_SHORT = "PORTRAIT_SHORT"

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

    // 16.7 的 cielo 竖排栏资源，仅作兜底：resolveVerticalRailResId 是 legacy 优先，
    // 只有 legacy 资源不存在（更老/未来版本）才回退到这两个 cielo 资源。
    // 注意：覆写成 cielo 会让 content 不让位被遮挡（实证），别反过来 cielo 优先。
    private var resLayoutLeftCieloResourceId: Int = 0
    private var resLayoutRightCieloResourceId: Int = 0

    // 竖屏车机：操作栏放底部。AA 自带 portrait 布局（sys_ui_layout_portrait /
    // sys_ui_layout_short_portrait）天然把 facet bar slot 放在底边满宽矮横条、
    // content 容器 Bottom_toTopOf 黑底栏 → content 向上让位、不被遮挡（实证 res/j5H.xml）。
    // 与竖排栏同样 legacy 优先（cielo portrait 仅作 legacy 不存在时的兜底；沿用 16.7
    // 已证根因原则：cielo content 不让位）。portrait 布局无 lhd/rhd 之分（单资源）。
    private var resLayoutPortraitResourceId: Int = 0
    private var resLayoutShortPortraitResourceId: Int = 0
    private var resLayoutPortraitCieloResourceId: Int = 0
    private var resLayoutShortPortraitCieloResourceId: Int = 0

    // hookLayout 决策出的"本次是否底栏模式"（竖屏 portrait=true / 横屏竖排栏=false）。
    // hookFacetBar 据此选横向(底栏)/竖向(左栏)排布。默认 false=维持现状竖排栏，
    // 万一 facet bar 先于任何 LayoutInfo 构造 inflate 也不会 regression。
    // 已评估的弱一致性取舍：写在 LayoutInfo 构造 hookBefore、读在 LayoutInflater.inflate
    // hookAfter，跨帧共享单例可变状态。@Volatile 只保证可见性，不保证 inflate 读到的是
    // "本轮布局那次"写入——横竖屏切换瞬间极端交错可能让 facet bar 方向错一帧，但下次
    // inflate 即自愈、全程在 try/catch 内不崩、车机横竖切换低频，故接受不另加锁/配对。
    @Volatile private var bottomBarMode = false

    // 16.1 旧竖排栏 inflate gh_coolwalk_vertical_facet_bar；16.7 cielo 竖排栏运行时
    // inflate 横版 gh_coolwalk_facet_bar(LHD)/gh_coolwalk_facet_bar_rhd(RHD)。三者任一
    // 被 inflate 都注入自定义 facet bar；旧版只命中 vertical 那个，自然兼容。
    private val resLayoutFacetBarIds = LinkedHashSet<Int>()
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

        resLayoutFacetBarIds.clear()
        for (fbn in arrayOf("gh_coolwalk_vertical_facet_bar", "gh_coolwalk_facet_bar", "gh_coolwalk_facet_bar_rhd")) {
            val fbid = InitFields.appContext.resources.getIdentifier(fbn, "layout", InitFields.appContext.packageName)
            if (fbid != 0) resLayoutFacetBarIds.add(fbid) else log(tagName, "AaUiHook: facet bar layout '$fbn' not found, skip")
        }
        log(tagName, "AaUiHook: facet bar layout ids resolved=$resLayoutFacetBarIds")
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

        // 竖屏底栏：AA 自带 portrait 布局（legacy 优先，cielo 兜底；无 lhd/rhd）
        resLayoutPortraitResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_layout_portrait", "layout", InitFields.appContext.packageName)
        resLayoutShortPortraitResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_layout_short_portrait", "layout", InitFields.appContext.packageName)
        resLayoutPortraitCieloResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_cielo_layout_portrait", "layout", InitFields.appContext.packageName)
        resLayoutShortPortraitCieloResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_cielo_layout_short_portrait", "layout", InitFields.appContext.packageName)
        log(tagName, "AaUiHook: portrait res legacy(std=$resLayoutPortraitResourceId,short=$resLayoutShortPortraitResourceId) cielo(std=$resLayoutPortraitCieloResourceId,short=$resLayoutShortPortraitCieloResourceId)")

        assert(resLayoutFacetBarIds.isNotEmpty()) { "no facet bar layout id resolved" }
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

    // 竖排栏资源解析：legacy 优先（与升级前 16.1 一致），cielo 仅作兜底。
    private fun resolveVerticalRailResId(isRightHandDrive: Boolean): Int {
        // 升级前(16.1)正常：覆写成旧 legacy 竖排栏布局，其 content 自动让位 + 窄黑 rail
        // (旧 coolwalk 行为)。16.7 该 legacy 资源仍保留，继续用它即与升级前一致。
        // cielo 布局 content 不让位(实证 contentInset_left begin=0 → 遮挡)，只当 legacy
        // 资源不存在(更老/未来版本)才回退 cielo。—— legacy 优先，纠正 R2 的 cielo 优先。
        val legacy = if (isRightHandDrive) resLayoutRightResourceId else resLayoutLeftResourceId
        val cielo = if (isRightHandDrive) resLayoutRightCieloResourceId else resLayoutLeftCieloResourceId
        val use = if (legacy != 0) legacy else cielo
        log(tagName, "AaUiHook: vertical rail resId -> ${if (use == legacy && legacy != 0) "legacy" else "cielo"}=$use (rhd=$isRightHandDrive, legacy=$legacy cielo=$cielo)")
        return use
    }

    // 竖屏底栏资源解析：**必须用 single-pane 的 short_portrait**。
    // sys_ui_layout_portrait 是双拼(content 容器内 50% guideline：上半地图 widget +
    // 下半 app)，会让镜像内容只占下半屏、上半被 AA 默认 Google Maps 占（实机已证）；
    // sys_ui_layout_short_portrait 无 50% 分屏，content 区直接 Bottom_toTopOf 底栏 →
    // 镜像内容全屏。优先级：legacy-short > cielo-short > legacy-std > cielo-std
    // （legacy 优先沿用 16.7 已证根因：cielo content 不让位；std 双拼仅极端兜底）。
    // 返回 0 表示无可用资源 → 调用方回退竖排栏（无 regression）。
    private fun resolvePortraitResId(): Int {
        val candidates = linkedMapOf(
            "legacy-short" to resLayoutShortPortraitResourceId,
            "cielo-short" to resLayoutShortPortraitCieloResourceId,
            "legacy-std" to resLayoutPortraitResourceId,
            "cielo-std" to resLayoutPortraitCieloResourceId,
        )
        val pick = candidates.entries.firstOrNull { it.value != 0 }
        log(tagName, "AaUiHook: portrait resId -> ${pick?.key ?: "none"}=${pick?.value ?: 0} (candidates=$candidates)")
        return pick?.value ?: 0
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
    @Volatile private var loggedPortraitDecision = false

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
                        bottomBarMode = false // int 路径=旧 AA(≤16.1)，竖排栏；竖屏底栏只走 16.7 enum 路径
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
                        // 竖屏车机：AA 自己判定为 PORTRAIT*（实测竖屏车机传 PORTRAIT_STANDARD，
                        // ordinal 10）。不改写成竖排栏，而是 enum+resId 一起改成 single-pane
                        // 的 PORTRAIT_SHORT + sys_ui_layout_short_portrait（content 向上让位 +
                        // 底部黑横条、镜像内容全屏）。**必须 enum 也改**：复刻竖排栏"enum 与
                        // resId 一起改"的成例——否则 AA 仍按双拼 PORTRAIT_STANDARD 分配出
                        // 第二个内容面塞默认 Google Maps（实机已证：只改 resId 时上半是 Maps）。
                        // portrait 资源不可用（更老 AA）才落到下面竖排栏逻辑（无 regression）。
                        // 横屏 AA 传 WIDESCREEN/CANONICAL 等，不进此分支 → 自动维持现状左竖
                        // 排栏（满足"横屏后期=左侧"）。
                        if (name.startsWith("PORTRAIT")) {
                            val portraitResId = resolvePortraitResId()
                            if (portraitResId != 0) {
                                param.args[0] = portraitResId
                                val shortEnum = findEnumConstantByName(layoutTypeArg.javaClass, ENUM_PORTRAIT_SHORT)
                                if (shortEnum != null) param.args[3] = shortEnum
                                bottomBarMode = true
                                if (!loggedPortraitDecision) {
                                    loggedPortraitDecision = true
                                    log(tagName, "AaUiHook: layout portrait(enum) $name -> ${if (shortEnum != null) ENUM_PORTRAIT_SHORT else "$name(keep,enum miss)"}, resId=$portraitResId (bottom bar, single-pane)")
                                }
                                return@hookBefore
                            }
                            log(tagName, "AaUiHook: portrait resId unavailable for $name, fallback to vertical rail")
                        }
                        val isRightHandDrive = param.args[4] as Boolean
                        val targetEnumName = if (isRightHandDrive) ENUM_VERTICAL_RAIL_RHD else ENUM_VERTICAL_RAIL_LHD
                        val targetEnum = findEnumConstantByName(layoutTypeArg.javaClass, targetEnumName)
                        if (targetEnum == null) {
                            log(tagName, "AaUiHook: enum $targetEnumName not found in ${layoutTypeArg.javaClass.name}, keep original (was $name)")
                            return@hookBefore
                        }
                        bottomBarMode = false // 横屏内容屏：竖排栏，facet bar 竖向排布
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
            if (param.args[0] as Int !in resLayoutFacetBarIds) {
                return@hookAfter
            }
            log(tagName, "AaUiHook: facet bar inflate hit id=${param.args[0]}, injecting aa_facet_bar")
            try {
            val resultViewGroup = param.result as ViewGroup? ?: return@hookAfter //androidx.constraintlayout.widget.ConstraintLayout
            val ctx = (param.thisObject as LayoutInflater).context
            val ctx2 = CommonContextWrapper.createAppCompatContext(ctx)
            val layoutInflater = LayoutInflater.from(ctx2)
            val resultViewGroupParent = (resultViewGroup.parent as ViewGroup?)?.apply {
                removeView(resultViewGroup)
            }
            if(closeLauncherDashboard){
                resultViewGroup.findViewById<View>(resIdLauncherAndDashboardIconId)?.apply {
                    setOnClickFinallyListener {
                        performLongClick()
                    }
                }
            }
            val aaFacetBar = layoutInflater.inflate(R.layout.aa_facet_bar, resultViewGroupParent, false) as ConstraintLayout
            // 不强设 aaFacetBar 宽度：让它 match_parent 填满 AA legacy facet slot（窄黑 rail），
            // 图标按 ConstraintSet 在窄 slot 内正常排布（与升级前 16.1 一致）。R4 曾用主屏
            // 密度算的 px 强设宽度，与投屏 display 密度不符，把图标布局撑坏，已移除。
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
                    // 统一缩放：所有图标在各自 44dp 盒内 FIT_CENTER，视觉尺寸一致。
                    // 不硬设 px（遵守 16.7 教训：投屏 display 密度≠主屏）；图标统一用
                    // 标准 Material Icons（24dp 网格、字重一致），盒同 + glyph 同 → 看齐。
                    scaleType = ImageView.ScaleType.FIT_CENTER
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
                createBtn(R.drawable.ic_sysbar_home){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_HOME)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
                createBtn(R.drawable.ic_sysbar_recent){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_APP_SWITCH)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
                createBtn(R.drawable.ic_sysbar_back){
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
            val movedTopIds = ArrayList<Int>()
            arrayListOf(resIdStatusBarId, resIdLauncherAndDashboardIconContainerId).forEach { vId ->
                val view = resultViewGroup.findViewById<View>(vId) ?: run {
                    log(tagName, "AaUiHook: container id=$vId not in this facet bar variant, skip")
                    return@forEach
                }
                (view.parent as ViewGroup?)?.apply {
                    removeView(view)
                }
                aaFacetBar.addView(view)
                movedTopIds.add(vId)
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
            if (bottomBarMode) {
                // 竖屏底栏：横向行排布。用户要求"最左=主页，最右=通知/时间"：
                // bottomIds(home/fullscreen/back) 锚**左端**、向右生长（index0=home 贴 parent
                // START → home 最左）；movedTopIds(status/launcher) 锚**右端**、向左生长
                // （index0=status 贴 parent END → 状态/时间最右）；TOP+BOTTOM 拉 parent 垂直居中。
                // 间距：单一值 spacePx 同时作为「贴边距离」和「图标间距」，左右对称、
                // 节奏一致（用户要求：最左图标距边 == 图标间距 == 右侧间距）。
                // 按**投屏 ctx 密度**算 px（非 appContext，不违反 16.7 教训——ctx 就是
                // 投屏 display 的 inflater context）。spacePx 可单点调。
                val density = ctx.resources.displayMetrics.density
                val spacePx = (20 * density).toInt()
                bottomIds.forEachIndexed { index, vId ->
                    set.connect(vId, ConstraintSet.START, if(index == 0) ConstraintSet.PARENT_ID else bottomIds[index-1], if(index == 0) ConstraintSet.START else ConstraintSet.END, spacePx)
                    set.connect(vId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
                    set.connect(vId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
                }
                movedTopIds.forEachIndexed { index, vId ->
                    set.connect(vId, ConstraintSet.END, if(index == 0) ConstraintSet.PARENT_ID else topIds[index-1], if(index == 0) ConstraintSet.END else ConstraintSet.START, spacePx)
                    set.connect(vId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
                    set.connect(vId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
                }
            } else {
                // 横屏左竖排栏（现状，与升级前 16.1 一致）
                bottomIds.forEachIndexed { index, vId ->
                    set.connect(vId, ConstraintSet.BOTTOM, if(index == 0) ConstraintSet.PARENT_ID else bottomIds[index-1], if(index == 0) ConstraintSet.BOTTOM else ConstraintSet.TOP, 0)
                    set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
                    set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
                }
                movedTopIds.forEachIndexed { index, vId ->
                    set.connect(vId, ConstraintSet.TOP, if(index == 0) ConstraintSet.PARENT_ID else topIds[index-1], if(index == 0) ConstraintSet.TOP else ConstraintSet.BOTTOM, 0)
                    set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
                    set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
                }
            }
//            set.connect(statusBarOverlayId, ConstraintSet.BOTTOM, resIdStatusBarId, ConstraintSet.BOTTOM, 0)
//            set.connect(statusBarOverlayId, ConstraintSet.TOP, resIdStatusBarId, ConstraintSet.TOP, 0)
//            set.connect(statusBarOverlayId, ConstraintSet.END, resIdStatusBarId, ConstraintSet.END, 0)
//            set.connect(statusBarOverlayId, ConstraintSet.START, resIdStatusBarId, ConstraintSet.START, 0)
            set.applyTo(aaFacetBar)
            resultViewGroup.visibility = View.GONE
            aaFacetBar.addView(resultViewGroup)
            param.result = aaFacetBar
            log(tagName, "AaUiHook: aa_facet_bar injected ok (mode=${if (bottomBarMode) "bottom" else "vertical"}, topIds=${movedTopIds.size})")
            } catch (e: Throwable) {
                log(tagName, "AaUiHook: aa_facet_bar inject failed for id=${param.args[0]}, keep original", e)
            }
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
