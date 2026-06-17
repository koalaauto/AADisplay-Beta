# AADisplay libxposed API 101 Migration TODO

> Goal: migrate the current legacy Xposed/LSPosed module implementation to modern libxposed API 101 while preserving every existing feature. Keep the design API-102-ready, but do not require API 102-only features in the first migration milestone.

## 0. Scope and success criteria

### Target for the first migration

- [ ] Build and run as a modern libxposed module with:
  - `minApiVersion=101`
  - `targetApiVersion=101`
- [ ] Preserve all current behavior:
  - [ ] system_server bridge service injection.
  - [ ] virtual display creation and control.
  - [ ] Android Auto / Gearhead signature bypass.
  - [ ] Android Auto DPI override.
  - [ ] Android Auto facet bar / layout rewrite.
  - [ ] Android Auto button event hook.
  - [ ] Android Auto phenotype / props hook.
  - [ ] status bar height suppression for selected third-party apps.
  - [ ] shell/root control flow through the existing app UI and services.
- [ ] Do not depend on zygote injection.
- [ ] Do not rely on `assets/xposed_init` for the migrated module path.
- [ ] Do not keep any runtime dependency on `de.robv.android.xposed.*` inside the API 101 path.
- [ ] Keep future API 102 migration possible by avoiding new legacy abstractions.

### Out of scope for the first migration

- [ ] Do not enable API 102 hot reload in the first API 101 milestone.
- [ ] Do not use API 102-only features such as `autoHotReload`, `detach()`, hook ids, or atomic hook replacement until the API 101 build is stable.
- [ ] Do not redesign Android Auto business logic unless required by the hook model migration.

---

## 1. Baseline audit before changing code

- [ ] Create a migration branch, for example `migration/libxposed-api-101`.
- [ ] Build the current `main` branch once and archive the APK.
- [ ] Record current working behavior on at least one known-good test device / ROM:
  - [ ] Android version.
  - [ ] LSPosed / framework version.
  - [ ] Android Auto / Gearhead version.
  - [ ] Whether system_server hook loads.
  - [ ] Whether Android Auto hooks load in `com.google.android.projection.gearhead`.
  - [ ] Whether hooks load in `:projection`.
  - [ ] Whether hooks load in `:car`.
  - [ ] Whether virtual display can be created, surfaced, destroyed, and re-created.
  - [ ] Whether home / recent / back / power / steering wheel controls work.
  - [ ] Whether custom AA props still apply.
  - [ ] Whether DPI override still applies.
  - [ ] Whether portrait bottom bar and landscape vertical rail behavior match the current implementation.
- [ ] Save representative logcat output from the current implementation:
  - [ ] `AADisplay_XposedInit`
  - [ ] `AAD_AndroidHook`
  - [ ] `AAD_AndroidAuoHook`
  - [ ] `AAD_AaBasicsHook`
  - [ ] `AAD_AaSignatureHook`
  - [ ] `AAD_AaDpiHook`
  - [ ] `AAD_AaBtnEventHook`
  - [ ] `AAD_AaUiHook`
  - [ ] `AAD_AaPropsHook`
  - [ ] `AAD_OtherHook`
  - [ ] `CoreManagerService`
- [ ] Keep this baseline log as the reference for feature parity.

---

## 2. Add modern libxposed packaging metadata

### 2.1 Stop excluding required `META-INF/xposed` files

Current problem: `aa-display/build.gradle.kts` excludes all `META-INF/**`, which would remove `META-INF/xposed/java_init.list`, `module.prop`, and `scope.list` from the APK.

- [ ] In `aa-display/build.gradle.kts`, remove the broad exclusion:

```kotlin
resources.excludes.addAll(
    arrayOf(
        "META-INF/**",
        "kotlin/**"
    )
)
```

- [ ] Replace it with a narrow exclude list that does not match `META-INF/xposed/**`.
- [ ] Verify the final APK contains:
  - [ ] `META-INF/xposed/java_init.list`
  - [ ] `META-INF/xposed/module.prop`
  - [ ] `META-INF/xposed/scope.list`
- [ ] Add a CI or local verification command after assemble:

```bash
unzip -l aa-display/build/outputs/apk/debug/*.apk | grep 'META-INF/xposed'
```

### 2.2 Add `module.prop`

- [ ] Create `aa-display/src/main/resources/META-INF/xposed/module.prop`.
- [ ] Initial API 101 content:

```properties
minApiVersion=101
targetApiVersion=101
staticScope=true
exceptionMode=protective
```

- [ ] Do not add `autoHotReload` yet. It is API 102+ only.
- [ ] Keep `exceptionMode=protective` for the first migration to reduce blast radius from hook exceptions.
- [ ] Revisit exception mode only after parity testing is complete.

### 2.3 Add `java_init.list`

- [ ] Create `aa-display/src/main/resources/META-INF/xposed/java_init.list`.
- [ ] Use exactly one Java/Kotlin entry class to keep future API 102 hot reload possible:

```text
io.github.nitsuya.aa.display.xposed.LibXposedInit
```

- [ ] Do not add multiple Java entry classes unless hot reload is intentionally abandoned.

### 2.4 Add `scope.list`

- [ ] Create `aa-display/src/main/resources/META-INF/xposed/scope.list`.
- [ ] Initial content:

```text
system
android
com.google.android.projection.gearhead
com.autonavi.amapauto
com.ss.squarehome2
```

Notes:

- `system` is required for system_server hooks.
- Keep `android` only if it is still needed for non-system-server `android` package callbacks. Do not use `android` as a substitute for system_server.
- Keep `com.autonavi.amapauto` and `com.ss.squarehome2` because they are currently listed in `xposed_scope`.
- If third-party app status bar suppression should apply beyond those packages, document and expand the static scope intentionally. Do not rely on accidental callbacks.

### 2.5 Legacy metadata transition plan

- [ ] Keep `assets/xposed_init` and Manifest legacy metadata only during a temporary compatibility window if necessary.
- [ ] Do not allow both legacy and libxposed entry paths to initialize hooks in the same process at the same time.
- [ ] Add a runtime guard if both paths temporarily coexist:
  - [ ] shared process-local singleton state such as `HookRuntime.isInitialized`.
  - [ ] log a warning when the legacy path is used.
- [ ] After API 101 path is stable, remove:
  - [ ] `aa-display/src/main/assets/xposed_init`
  - [ ] Manifest `xposedmodule`
  - [ ] Manifest `xposeddescription`
  - [ ] Manifest `xposedminversion`
  - [ ] Manifest `xposedscope`
  - [ ] `@array/xposed_scope` if no other code uses it.

---

## 3. Update Gradle dependencies

### 3.1 Add libxposed API dependency

- [ ] Add official libxposed API as `compileOnly` in `aa-display/build.gradle.kts`.
- [ ] Pin the API artifact version used by the chosen framework documentation.
- [ ] Do not package libxposed API classes into the APK.
- [ ] Keep this dependency isolated from app runtime code.

Example shape, confirm the exact artifact coordinate before committing:

```kotlin
compileOnly("<official-libxposed-api-coordinate>")
```

### 3.2 Remove legacy Xposed API dependency from the migrated path

Current dependency:

```kotlin
compileOnly(files("./libs/de.robv.android.xposed_api_82.jar"))
```

- [ ] Remove this dependency once every `de.robv.android.xposed.*` import is gone from the API 101 implementation.
- [ ] Do not introduce any new code that imports:
  - `de.robv.android.xposed.XposedBridge`
  - `de.robv.android.xposed.XposedHelpers`
  - `de.robv.android.xposed.XC_MethodHook`
  - `de.robv.android.xposed.XSharedPreferences`
  - `de.robv.android.xposed.IXposedHookLoadPackage`
  - `de.robv.android.xposed.IXposedHookZygoteInit`
  - `de.robv.android.xposed.callbacks.XC_LoadPackage`

### 3.3 Replace EzXHelper hook usage

Current code heavily depends on EzXHelper for:

- class lookup convenience.
- reflection helpers.
- `hookBefore` / `hookAfter`.
- `XC_MethodHook.Unhook` handles.
- `EzXHelperInit.initZygote(...)`.
- `EzXHelperInit.initHandleLoadPackage(...)`.
- `EzXHelperInit.initAppContext()`.

Tasks:

- [ ] Audit EzXHelper usage by category:
  - [ ] pure reflection helper usage.
  - [ ] hook registration usage.
  - [ ] context/classloader initialization usage.
  - [ ] logging usage.
- [ ] Replace all hook registration usage with libxposed `hook(Executable).intercept(...)`.
- [ ] Replace `XC_MethodHook.Unhook` with libxposed `HookHandle`.
- [ ] Replace `EzXHelperInit.initZygote(...)` by deleting the zygote initialization path.
- [ ] Replace `EzXHelperInit.initHandleLoadPackage(...)` with an internal runtime context object.
- [ ] Replace `EzXHelperInit.initAppContext()` with explicit context capture from hooked `Application`, `Instrumentation`, or system_server sources.
- [ ] Decide whether pure reflection helpers can remain without importing or invoking legacy Xposed APIs.
- [ ] For API 102 readiness, prefer removing EzXHelper entirely from the Xposed layer unless verified that it does not call `de.robv.android.xposed.*` internally.

---

## 4. Introduce a new libxposed runtime layer

### 4.1 Add a single modern entry class

- [ ] Add `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/LibXposedInit.kt`.
- [ ] Make it extend `io.github.libxposed.api.XposedModule`.
- [ ] Implement only API 101-compatible callbacks for the first milestone:
  - [ ] `onModuleLoaded(...)`
  - [ ] `onPackageLoaded(...)`
  - [ ] `onPackageReady(...)`
  - [ ] `onSystemServerStarting(...)`
- [ ] Do not implement `onHotReloading(...)` or `onHotReloaded(...)` until the target is raised to API 102.
- [ ] Log framework name/version/API version/properties during `onModuleLoaded(...)`.
- [ ] Check framework properties:
  - [ ] If system_server hooks are needed, verify `PROP_CAP_SYSTEM` exists before attempting system_server work.
  - [ ] If remote preferences are used, verify `PROP_CAP_REMOTE` exists before depending on them.

### 4.2 Add a process-local runtime context

- [ ] Add an internal context model, for example `XposedRuntimeContext`:

```kotlin
data class XposedRuntimeContext(
    val module: XposedModule,
    val classLoader: ClassLoader,
    val packageName: String,
    val processName: String,
    val applicationInfo: ApplicationInfo?,
    val isFirstPackage: Boolean,
    val isSystemServer: Boolean,
)
```

- [ ] Include helper methods:
  - [ ] `hookBefore(...)`
  - [ ] `hookAfter(...)`
  - [ ] `hookReplace(...)`
  - [ ] `findMethod(...)`
  - [ ] `findAllMethods(...)`
  - [ ] `findConstructor(...)`
  - [ ] `loadClass(...)`
  - [ ] logging wrappers.
- [ ] Keep these wrappers backed by libxposed only.
- [ ] Do not hide legacy Xposed APIs behind wrappers.

### 4.3 Recreate `hookBefore`, `hookAfter`, and abort semantics

- [ ] Implement `hookBefore` on top of libxposed `intercept`:
  - [ ] Read immutable args through `chain.getArgs()`.
  - [ ] Copy to a mutable array if arguments need changes.
  - [ ] Call `chain.proceed(argsArray)` with modified args.
- [ ] Implement `hookAfter` on top of libxposed `intercept`:
  - [ ] Call `chain.proceed()` first.
  - [ ] Return the original or modified result.
- [ ] Implement replacement / abort helpers:
  - [ ] For non-void methods: return the desired replacement value without calling `chain.proceed()`.
  - [ ] For void methods and constructors: return `null` without calling `chain.proceed()`.
- [ ] Replace current `abortMethod()` semantics carefully:
  - [ ] Current `abortMethod()` sets `param.result = null`.
  - [ ] In the new model, this means do not call `proceed()` and return `null`.
  - [ ] Verify the hooked method actually accepts `null`/void behavior.

### 4.4 Hook handle management

- [ ] Replace all `XC_MethodHook.Unhook?` with a project-owned handle type:

```kotlin
class ManagedHookHandle(private val handle: XposedInterface.HookHandle) {
    fun unhook() = handle.unhook()
}
```

- [ ] Do not use API 102-only hook ids in the API 101 implementation.
- [ ] Store handles for hooks that are currently one-shot and manually unhooked:
  - [ ] `ServiceManager.addService`
  - [ ] `ActivityManagerService` constructor hook.
  - [ ] `ActivityManagerService.systemReady`.
  - [ ] `Instrumentation.callApplicationOnCreate` in Gearhead.
  - [ ] `Instrumentation.callApplicationOnCreate` in other apps.
  - [ ] power button hook.
  - [ ] application bind/start process hooks.
- [ ] Add cleanup helpers even though API 101 has no `detach()` lifecycle.

---

## 5. Migrate `XposedInit.kt`

Current file: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/XposedInit.kt`

- [ ] Keep this file unchanged initially if a temporary legacy build flavor is needed.
- [ ] Do not let it run in the API 101 build variant.
- [ ] Port logic into `LibXposedInit.kt`:

Current legacy routing:

```kotlin
when {
    packageName == "android" && lpparam.appInfo == null -> arrayOf(AndroidHook)
    packageName == "com.google.android.projection.gearhead" -> arrayOf(AndroidAuoHook)
    packageName == BuildConfig.APPLICATION_ID || lpparam.appInfo == null || lpparam.appInfo.uid == 1000 -> null
    else -> arrayOf(OtherHook)
}
```

Target routing:

- [ ] `onSystemServerStarting(...)`:
  - [ ] initialize `AndroidHook` with system_server classloader.
- [ ] `onPackageLoaded(...)` or `onPackageReady(...)` for Gearhead:
  - [ ] if `packageName == "com.google.android.projection.gearhead"`, initialize `AndroidAuoHook`.
- [ ] `onPackageLoaded(...)` or `onPackageReady(...)` for other scoped third-party packages:
  - [ ] skip module package itself.
  - [ ] skip system uid if equivalent information is available.
  - [ ] initialize `OtherHook` only for intended scope packages.
- [ ] Preserve process filtering by process name:
  - [ ] `com.google.android.projection.gearhead`
  - [ ] `com.google.android.projection.gearhead:projection`
  - [ ] `com.google.android.projection.gearhead:car`

Important detail:

- [ ] API 101 `PackageLoadedParam` provides package name, application info, first-package flag, and default classloader; it does not mirror every legacy `XC_LoadPackage.LoadPackageParam` field directly. Create your own context and pass only what each hook needs.

---

## 6. Migrate hook base abstractions

Current file: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/BaseHook.kt`

### 6.1 Replace legacy interface

Current:

```kotlin
abstract class BaseHook {
    var isInit: Boolean = false
    abstract val tagName: String
    abstract fun init(lpparam: XC_LoadPackage.LoadPackageParam)
}
```

Target:

```kotlin
abstract class BaseHook {
    var isInit: Boolean = false
    abstract val tagName: String
    abstract fun init(ctx: XposedRuntimeContext)
}
```

- [ ] Remove imports of:
  - `de.robv.android.xposed.XC_MethodHook`
  - `de.robv.android.xposed.callbacks.XC_LoadPackage`
- [ ] Replace `abortMethod()` extension with a libxposed-chain-compatible helper.
- [ ] Make hook initialization idempotent per process.
- [ ] Review `isInit`: current singleton objects are process-local, but the same singleton may receive multiple package callbacks in one process. Keep or replace with per-process keys if necessary.

### 6.2 Replace `AaHook`

Current file: `AndroidAuoHook.kt` defines `AaHook` using `XC_LoadPackage.LoadPackageParam`.

- [ ] Move `AaHook` to its own file or keep it near `AndroidAuoHook`, but change signatures:

```kotlin
abstract class AaHook {
    abstract val tagName: String
    abstract fun isSupportProcess(processName: String): Boolean
    open fun loadDexClass(bridge: DexKitBridge, ctx: XposedRuntimeContext) {}
    abstract fun hook(config: SharedPreferences, ctx: XposedRuntimeContext)
}
```

- [ ] Ensure every AA hook receives the same context type.
- [ ] Ensure process name is provided reliably. If libxposed param does not expose process name directly, derive it once using `ApplicationInfo.processName`, `ActivityThread.currentProcessName()`, `/proc/self/cmdline`, or an equivalent safe helper.

---

## 7. Migrate system_server hook: `AndroidHook.kt`

Current file: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/AndroidHook.kt`

### 7.1 Entry point

- [ ] Move initialization from package-load routing to `onSystemServerStarting(...)`.
- [ ] Pass `SystemServerStartingParam.getClassLoader()` into the runtime context.
- [ ] Ensure all system_server class lookups use that classloader.

### 7.2 `ServiceManager.addService`

Current behavior:

- Hook `android.os.ServiceManager.addService`.
- Wait for `param.args[0] == "package"`.
- Unhook once package service is obtained.
- Cast `param.args[1]` to `IPackageManager`.
- Call `BridgeService.register(pms)`.

Migration tasks:

- [ ] Resolve `android.os.ServiceManager.addService` with system_server classloader.
- [ ] Register a before-interceptor.
- [ ] Copy args to an array only if needed; read arg 0/1 safely.
- [ ] If arg 0 is `"package"`, unhook the handle.
- [ ] Preserve exception containment: log but do not crash system_server.
- [ ] Verify `BridgeService.register(pms)` still hooks `pms.onTransact` using the new hook wrapper.

### 7.3 `ActivityManagerService` constructor

Current behavior:

- Hook all constructors of `com.android.server.am.ActivityManagerService` whose first parameter is `Context`.
- After constructor, unhook all constructor hooks.
- Read `mUiContext` and set `CoreManagerService.systemContext`.

Migration tasks:

- [ ] Reimplement constructor after-hook with libxposed.
- [ ] Hook every matching constructor and store every handle.
- [ ] After first successful call, unhook all handles.
- [ ] Keep `getObjectAs("mUiContext")` functionality using reflection helper.
- [ ] Preserve error logging if no matching constructor exists.

### 7.4 `ActivityManagerService.systemReady`

Current behavior:

- Hook `systemReady` after call.
- Unhook after first execution.
- Call `CoreManagerService.systemReady()`.

Migration tasks:

- [ ] Reimplement as after-interceptor.
- [ ] Ensure `chain.proceed()` runs before `CoreManagerService.systemReady()`.
- [ ] Unhook after first successful call.
- [ ] Keep failure isolated to avoid system_server crash.

### 7.5 `ActivityTaskSupervisor` / `ActivityStackSupervisor` display launch permission

Current behavior:

- For Android Q+:
  - Android S+: `com.android.server.wm.ActivityTaskSupervisor`
  - Android Q/R: `com.android.server.wm.ActivityStackSupervisor`
- Hook `isCallerAllowedToLaunchOnDisplay(...)` after call.
- If result is false and target display id equals AADisplay virtual display id, return true.

Migration tasks:

- [ ] Reimplement as after-interceptor.
- [ ] Preserve SDK-dependent class selection.
- [ ] Verify method signature on current supported Android versions.
- [ ] Add defensive fallback logging if method not found.

### 7.6 Power button hook

Current behavior:

- Lazy resolve `PhoneWindowManager.powerPress(long, int, boolean)`.
- Hook/unhook dynamically through `AndroidHook.Power.hook()` / `unHook()`.
- Before original method, if `beganFromNonInteractive == false`, toggle display power and abort original method.

Migration tasks:

- [ ] Replace `XC_MethodHook.Unhook?` with `HookHandle?`.
- [ ] In replacement/before helper, return `null` without calling `proceed()` when aborting.
- [ ] Preserve original behavior when `beganFromNonInteractive == true`.
- [ ] Verify no recursion through `CoreApi.toggleDisplayPower()`.

### 7.7 Application context density hook

Current behavior:

- Hook `ActivityTaskManagerService.startProcessAsync` before.
- Hook `IApplicationThread$Stub$Proxy.bindApplication` before.
- Track display id per package and mutate `Configuration.densityDpi`.

Migration tasks:

- [ ] Reimplement both hooks as before-interceptors.
- [ ] Use copied args when mutating arguments or mutable object fields.
- [ ] Keep synchronized map behavior.
- [ ] Preserve clear/unhook behavior.

---

## 8. Migrate package hooks: `AndroidAuoHook.kt`

Current file: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/AndroidAuoHook.kt`

### 8.1 Process routing

- [ ] Preserve process constants:
  - `com.google.android.projection.gearhead`
  - `com.google.android.projection.gearhead:projection`
  - `com.google.android.projection.gearhead:car`
- [ ] Only initialize child hooks matching the current process.
- [ ] Do not run DexKit scans in unsupported processes.

### 8.2 Config loading

Current behavior uses:

```kotlin
XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
```

Migration tasks:

- [ ] Replace with a `ConfigProvider` abstraction.
- [ ] First implementation option: libxposed `getRemotePreferences(AADisplayConfig.ConfigName)`.
- [ ] Validate that the app UI writes to the same preference group expected by the framework.
- [ ] If remote preferences cannot be written directly by the app UI, implement explicit sync:
  - [ ] app writes local config.
  - [ ] app exports/syncs config into framework remote preferences or a remote file.
  - [ ] hook process reads remote preferences/file.
- [ ] Keep `reload()` equivalent if settings can change without process restart.
- [ ] If reload is not available, document which changes require target process restart.
- [ ] Avoid `MODE_WORLD_READABLE` or filesystem permission hacks.

### 8.3 `Instrumentation.callApplicationOnCreate`

Current behavior:

- Hook `Instrumentation.callApplicationOnCreate(Application)` before.
- Unhook after first app creation.
- Initialize app context.
- Load `dexkit` native library.
- Use DexKit against `lpparam.appInfo.sourceDir`.
- Run `loadDexClass` for child hooks.
- Run `hook` for child hooks.

Migration tasks:

- [ ] Reimplement as before-interceptor.
- [ ] Obtain `Application` from arg 0.
- [ ] Use `application.applicationInfo.sourceDir` or context application info instead of legacy `lpparam.appInfo.sourceDir`.
- [ ] Set the app context explicitly in the new runtime helper.
- [ ] Verify `System.loadLibrary("dexkit")` still works from the target process.
- [ ] Preserve per-child-hook try/catch behavior:
  - [ ] one child hook failing in `loadDexClass` must not block the others.
  - [ ] one child hook failing in `hook` must not block the others.
- [ ] Keep log timings for DexKit scan.

---

## 9. Migrate `OtherHook.kt`

Current behavior:

- Hook `Instrumentation.callApplicationOnCreate(Application)` before.
- Initialize app context.
- Find Android `status_bar_height` resource id.
- Hook `Resources.getDimension`, `getDimensionPixelSize`, `getDimensionPixelOffset` before.
- Return 0 for `status_bar_height`.

Tasks:

- [ ] Reimplement `Instrumentation.callApplicationOnCreate` as before-interceptor.
- [ ] Preserve one-shot unhook.
- [ ] Replace `InitFields.appContext` with the explicit application context captured from arg 0.
- [ ] Reimplement resource methods with replacement behavior:
  - [ ] If arg 0 is not the target resource id, call `proceed()`.
  - [ ] If method is `getDimension`, return `0F`.
  - [ ] Else return `0`.
- [ ] Scope this hook narrowly to intended apps; avoid broad injection into every user app unless explicitly desired.

---

## 10. Migrate Android Auto child hooks

### 10.1 `AaBasicsHook.kt`

Current behavior:

- Android R+: hook `InstallSourceInfo.getInitiatingPackageName()` after and return `com.android.vending`.
- Older: hook `PackageManager.getInstallerPackageName()` after and return `com.android.vending`.

Tasks:

- [ ] Reimplement both as after-interceptors.
- [ ] Keep SDK branch.
- [ ] Return original result on errors.
- [ ] Verify no hidden dependency on `XC_LoadPackage.LoadPackageParam` remains.

### 10.2 `AaSignatureHook.kt`

Current behavior:

- DexKit finds Android Auto signature verifier method.
- Hook method after.
- If checked package equals module application id, return true.

Tasks:

- [ ] Keep DexKit search logic.
- [ ] Replace `XC_LoadPackage.LoadPackageParam` with runtime context.
- [ ] Reimplement method hook as after-interceptor.
- [ ] Preserve `BuildConfig.APPLICATION_ID` check.
- [ ] Add log for ambiguous or missing DexKit result.

### 10.3 `AaDpiHook.kt`

Current behavior:

- DexKit finds `DisplayParams` class.
- Resolve AA 16.7 and 16.1 constructor shapes.
- Resolve `CarDisplay` constructor shapes.
- Hook constructors before and after.
- Mutate density DPI arguments.

Tasks:

- [ ] Keep constructor resolution logic exactly unless tests require change.
- [ ] Reimplement constructor after-log using `chain.proceed(args)` then log result/constructed object behavior according to libxposed constructor semantics.
- [ ] Reimplement constructor before-mutation:
  - [ ] Copy `chain.getArgs()` into a mutable array.
  - [ ] Change `DISPLAY_PARAMS_DPI_ARG` or `CarDisplay` arg index 2.
  - [ ] Call `chain.proceed(modifiedArgs)`.
- [ ] Verify constructor interceptor return value expectations: constructors should return `null`; do not return the created object unless libxposed requires otherwise.
- [ ] Confirm the 16.7/16.1 constructor assumptions on current Android Auto version.

### 10.4 `AaBtnEventHook.kt`

Current behavior:

- Hook `ContextWrapper.registerReceiver` before.
- Detect media/projected key event receivers.
- Hook receiver `onReceive` before.
- Abort original receiver and send AADisplay broadcasts for click/long-click.

Tasks:

- [ ] Reimplement `registerReceiver` before-interceptor.
- [ ] Reimplement dynamic `onReceive` hook using libxposed.
- [ ] Ensure each receiver class is hooked once.
- [ ] Preserve synchronized sets/maps.
- [ ] For abort behavior, return `null` without calling `proceed()`.
- [ ] Preserve default voice assistant behavior when custom shell is blank.
- [ ] Verify long-press tracking still works with immutable chain args.

### 10.5 `AaUiHook.kt`

This is the highest-risk business hook. It contains layout rewriting, facet bar injection, click listener protection, auto-open, portrait bottom bar, landscape vertical rail, and corner-radius hooks.

Tasks:

- [ ] Migrate in small commits by sub-feature:
  - [ ] DexKit layout class discovery.
  - [ ] `LayoutInfo` constructor hook.
  - [ ] facet bar layout id resolution.
  - [ ] `LayoutInflater.inflate` injection.
  - [ ] base click/long-click listener hook.
  - [ ] radius hook.
- [ ] For `LayoutInfo` constructor:
  - [ ] Preserve int layoutType path for AA 16.1 and older.
  - [ ] Preserve enum layoutType path for AA 16.7+.
  - [ ] Preserve `PORTRAIT_SHORT` bottom bar rewrite.
  - [ ] Preserve vertical rail resource fallback order: legacy first, cielo fallback.
  - [ ] Use `chain.proceed(modifiedArgs)` after mutating copied args.
- [ ] For `LayoutInflater.inflate`:
  - [ ] Call `chain.proceed()` first.
  - [ ] If inflated resource id is not in `resLayoutFacetBarIds`, return original result.
  - [ ] If matched, build custom `aa_facet_bar` and return it.
  - [ ] On exception, return original result rather than breaking Android Auto UI.
- [ ] For `View.setOnClickListener` and `View.setOnLongClickListener`:
  - [ ] Recreate `XCallback.PRIORITY_LOWEST` behavior with libxposed priority constants.
  - [ ] Confirm priority ordering matches legacy behavior.
  - [ ] Preserve `FinallyListener` logic.
  - [ ] For abort behavior, return `null` without calling `proceed()`.
- [ ] For auto-open:
  - [ ] Confirm `startMethod?.invoke(null, Intent(...))` remains valid after context migration.
  - [ ] Preserve delayed invocation and error logging.
- [ ] For resources and view ids:
  - [ ] Replace `InitFields.appContext` with explicit application context.
  - [ ] Do not use module app context when target app context is required.
- [ ] Regression test portrait and landscape separately.

### 10.6 `AaPropsHook.kt`

Current behavior:

- DexKit finds phenotype props class/method.
- Hook method after and override selected Gearhead props.
- Hook `ContentResolver.query(Uri, Array<String>, String, Array<String>, String)` after for GMS car props.
- Merge custom `MatrixCursor` with existing cursor.

Tasks:

- [ ] Keep DexKit class/method/field discovery logic.
- [ ] Reimplement props method as after-interceptor.
- [ ] Reimplement `ContentResolver.query` as after-interceptor.
- [ ] Preserve cursor merge behavior.
- [ ] Ensure returned cursors are not prematurely closed.
- [ ] Preserve per-value type conversion and error logging.

---

## 11. Migrate `BridgeService.kt`

Current behavior:

- `BridgeService.register(pms)` hooks `pms.onTransact` before.
- It handles custom transaction code `AADD`.
- It writes `CoreManagerService.instance` binder into reply.

Tasks:

- [ ] Replace `pms.javaClass.findMethod(true) { name == "onTransact" }.hookBefore` with the new hook wrapper.
- [ ] Preserve `myTransact(code, data, reply)` logic exactly.
- [ ] For handled transactions, return `true` without calling `proceed()`.
- [ ] For unhandled transactions, call `proceed()`.
- [ ] Preserve `data.setDataPosition(0)` and `reply?.setDataPosition(0)` behavior.
- [ ] Keep Binder caller UID check.
- [ ] Confirm binder service works from app process after migration.

---

## 12. Migrate `CoreManagerService.kt` config access

Current behavior:

```kotlin
val config: XSharedPreferences? by lazy {
    XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName).let { config ->
        if (!config.file.canRead()) null else config
    }
}
```

Tasks:

- [ ] Replace `XSharedPreferences` with `ConfigProvider`.
- [ ] Make `CoreManagerService` independent from legacy Xposed classes.
- [ ] Decide config ownership:
  - [ ] Option A: hooks and service read libxposed remote preferences directly.
  - [ ] Option B: app writes local preferences and explicitly syncs to remote preferences/file.
  - [ ] Option C: app process sends config through binder to system_server service.
- [ ] Ensure `config.reload()` equivalent exists before `onCreateDisplay(...)` reads settings.
- [ ] If live reload cannot be preserved, document required restart boundaries.
- [ ] Test all settings read by:
  - [ ] `AaVirtualDisplayAdapter`
  - [ ] `DisplayWindow`
  - [ ] `AndroidHook.Power`
  - [ ] AA hooks.

---

## 13. Replace logging

Current code imports and calls project-local `log(...)`, while some helpers may depend on legacy Xposed logging.

Tasks:

- [ ] Implement a logging adapter backed by libxposed `log(priority, tag, msg)` and `log(priority, tag, msg, throwable)`.
- [ ] Keep Android `Log` fallback for app-only code if needed.
- [ ] Ensure logs still include existing tags for baseline comparison.
- [ ] Do not call `XposedBridge.log` from API 101 code.

---

## 14. Remove every legacy API import from migrated code

Before declaring the API 101 migration complete, this command must return nothing for the API 101 source set:

```bash
grep -RInE 'de\.robv\.android\.xposed|IXposedHook|XC_MethodHook|XC_LoadPackage|XSharedPreferences|XposedBridge|XposedHelpers' aa-display/src/main/java aa-display/src/main/kotlin
```

Tasks:

- [ ] Remove or isolate all matching imports.
- [ ] If a temporary legacy flavor remains, keep it in a separate source set or branch, not mixed into the API 101 runtime path.
- [ ] Remove `de.robv.android.xposed_api_82.jar` after no code depends on it.
- [ ] Confirm R8/ProGuard does not keep legacy code accidentally reachable.

---

## 15. API 102 readiness rules while implementing API 101

Do these during API 101 migration so API 102 is a small follow-up rather than a second rewrite.

- [ ] Keep exactly one Java entry class in `java_init.list`.
- [ ] Do not introduce any new `de.robv.android.xposed.*` usage.
- [ ] Do not call Xposed APIs through reflection or dynamically loaded code.
- [ ] Do not hide Xposed API calls inside DexKit-loaded code or plugin code.
- [ ] Design hook registration around a small wrapper that can later expose API 102 hook ids.
- [ ] Store hook handles centrally so API 102 atomic replacement can be added later.
- [ ] Keep process detachment as an optional abstraction:
  - [ ] API 101: no-op / return early.
  - [ ] API 102: can call `detach()` for unsupported processes after migration.
- [ ] Keep hot reload concerns isolated:
  - [ ] no static references to obsolete target app classes that would survive generation changes.
  - [ ] no global classloader cache without process/generation key.
  - [ ] all hook handles can be enumerated and unhooked if API 102 hot reload is enabled later.
- [ ] Do not set `autoHotReload=true` until `targetApiVersion=102`.

---

## 16. Build and shrinker details

- [ ] Keep `-keep class io.github.nitsuya.aa.display.** { *; }` until migration is stable.
- [ ] Add explicit keep rule for the libxposed entry class if needed:

```proguard
-keep class io.github.nitsuya.aa.display.xposed.LibXposedInit { *; }
```

- [ ] Verify R8 does not remove `META-INF/xposed` resources.
- [ ] Verify R8 does not obfuscate entry class name unless `java_init.list` is updated accordingly. Prefer not obfuscating entry class.
- [ ] Verify native libraries required by DexKit are still packaged and loadable.
- [ ] Verify `aauto.aar` packaging remains unaffected by metadata changes.

---

## 17. Test matrix

### 17.1 Install and activation

- [ ] APK installs successfully.
- [ ] LSPosed/framework recognizes module metadata from `module.prop`.
- [ ] Scope list is displayed or applied as expected.
- [ ] Module can be enabled for:
  - [ ] `system`
  - [ ] `com.google.android.projection.gearhead`
  - [ ] `com.autonavi.amapauto`
  - [ ] `com.ss.squarehome2`
- [ ] Reboot or restart required processes as documented.

### 17.2 system_server tests

- [ ] `onSystemServerStarting(...)` runs.
- [ ] `ServiceManager.addService` hook gets package service.
- [ ] `BridgeService.register(...)` succeeds.
- [ ] App can retrieve `CoreManagerService` binder.
- [ ] `ActivityManagerService` constructor hook captures `mUiContext`.
- [ ] `systemReady` hook calls `CoreManagerService.systemReady()` once.
- [ ] Display launch permission override works.
- [ ] Power button toggle still works when enabled.
- [ ] No system_server crash or boot loop.

### 17.3 Android Auto main process

- [ ] Gearhead main process loads module.
- [ ] Config provider can read settings.
- [ ] DexKit library loads.
- [ ] `AaBasicsHook` applies installer/source spoofing.
- [ ] `AaPropsHook` applies main-process props where applicable.

### 17.4 Android Auto `:projection`

- [ ] `AaBtnEventHook` loads only in supported process.
- [ ] Media button interception works.
- [ ] Projected key event interception works.
- [ ] Custom voice assistant shell behavior preserved.
- [ ] Default voice assistant behavior preserved when custom shell is blank.
- [ ] `AaUiHook` loads required classes.
- [ ] Facet bar injection works.
- [ ] Landscape vertical rail layout matches baseline.
- [ ] Portrait bottom bar layout matches baseline.
- [ ] Home / recent / back buttons work.
- [ ] Auto-open still works.
- [ ] Radius override still works when enabled.

### 17.5 Android Auto `:car`

- [ ] `AaSignatureHook` bypasses module package signature check.
- [ ] `AaDpiHook` applies DisplayParams DPI override.
- [ ] `AaDpiHook` applies CarDisplay DPI override.
- [ ] `AaPropsHook` applies car-process props.
- [ ] Constructor signatures still match current AA version.

### 17.6 Other scoped apps

- [ ] `OtherHook` runs only where intended.
- [ ] Status bar height suppression works.
- [ ] No broad side effects in apps outside intended scope.

---

## 18. Failure handling requirements

- [ ] Every hook registration failure must log and continue unless the feature cannot safely proceed.
- [ ] system_server hook failures must never throw out of interceptors.
- [ ] Android Auto UI hook failures must return original UI results.
- [ ] DexKit failures in one child hook must not block other child hooks.
- [ ] Config read failures must disable dependent feature and log a clear message.
- [ ] Binder transaction failures must reset parcel positions as current code does.
- [ ] Constructor signature mismatches must log enough information to update the hook for new AA versions.

---

## 19. Suggested implementation order

1. [ ] Add docs and baseline logs.
2. [ ] Add `META-INF/xposed` files and fix packaging excludes.
3. [ ] Add libxposed dependency and `LibXposedInit` skeleton.
4. [ ] Implement logging adapter.
5. [ ] Implement runtime context and reflection helpers.
6. [ ] Implement libxposed hook wrapper helpers.
7. [ ] Migrate `BridgeService` hook helper dependency.
8. [ ] Migrate `AndroidHook` system_server path.
9. [ ] Migrate config provider away from `XSharedPreferences`.
10. [ ] Migrate `AndroidAuoHook` entry and DexKit setup.
11. [ ] Migrate `AaBasicsHook`.
12. [ ] Migrate `AaSignatureHook`.
13. [ ] Migrate `AaDpiHook`.
14. [ ] Migrate `AaPropsHook`.
15. [ ] Migrate `AaBtnEventHook`.
16. [ ] Migrate `AaUiHook` in sub-features.
17. [ ] Migrate `OtherHook`.
18. [ ] Remove legacy imports and dependencies.
19. [ ] Remove legacy metadata and `assets/xposed_init`.
20. [ ] Full regression test.
21. [ ] Prepare optional API 102 follow-up issue.

---

## 20. Optional API 102 follow-up after API 101 is stable

Only do this after the API 101 version passes regression testing.

- [ ] Raise `targetApiVersion=102` while keeping `minApiVersion=101` only if the code handles runtime API checks correctly. Otherwise raise both intentionally.
- [ ] Confirm zero legacy Xposed API calls remain.
- [ ] Add optional `autoHotReload=true` only after testing hot reload behavior.
- [ ] Implement `onHotReloading(...)`:
  - [ ] unhook all managed hook handles.
  - [ ] clear process/generation-local class caches.
  - [ ] return true only when safe.
- [ ] Implement `onHotReloaded(...)`:
  - [ ] reinitialize hooks as needed.
  - [ ] reload config provider.
- [ ] Use API 102 hook ids only after API 101 behavior is stable.
- [ ] Consider `detach()` for unsupported packages/processes to reduce callback overhead.
- [ ] Add tests for module update without target process restart.

---

## 21. Completion checklist

The migration is complete only when all of these are true:

- [ ] APK contains `META-INF/xposed/java_init.list`, `module.prop`, and `scope.list`.
- [ ] `module.prop` uses `minApiVersion=101` and `targetApiVersion=101`.
- [ ] The module entry class extends `XposedModule`.
- [ ] No runtime code imports `de.robv.android.xposed.*`.
- [ ] No code uses `IXposedHookZygoteInit`.
- [ ] No code uses `IXposedHookLoadPackage`.
- [ ] No code uses `XC_MethodHook`.
- [ ] No code uses `XC_LoadPackage.LoadPackageParam`.
- [ ] No code uses `XSharedPreferences`.
- [ ] `de.robv.android.xposed_api_82.jar` is removed from Gradle dependencies.
- [ ] system_server functionality works through `onSystemServerStarting(...)`.
- [ ] Gearhead hooks work in all required processes.
- [ ] Third-party scoped app hook still works.
- [ ] Every current user-facing feature matches baseline behavior.
- [ ] Logs are sufficient to debug AA version drift.
- [ ] API 102 follow-up can be done without another full rewrite.
