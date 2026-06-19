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
- [x] Do not depend on zygote injection.
- [x] Do not rely on `assets/xposed_init` for the migrated module path.
- [x] Do not keep any runtime dependency on `de.robv.android.xposed.*` inside the API 101 path.
- [x] Keep future API 102 migration possible by avoiding new legacy abstractions.

### Out of scope for the first migration

- [x] Do not enable API 102 hot reload in the first API 101 milestone.
- [x] Do not use API 102-only features such as `autoHotReload`, `detach()`, hook ids, or atomic hook replacement until the API 101 build is stable.
- [x] Do not redesign Android Auto business logic unless required by the hook model migration.

---

## 1. Baseline audit before changing code

- [x] Create a migration branch, for example `migration/libxposed-api-101`.
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

Notes:

- Created branch `migration/libxposed-api-101` from `main` at `9daf831`.
- Baseline build/archive is pending for a known-good test host. The Gradle wrapper has been restored (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties`) and points to Gradle 8.13 for AGP 8.11.x compatibility.
- 2026-06-18 local recheck: system Java is JDK `28-ea`, which Kotlin/Gradle 8.13 cannot parse (`IllegalArgumentException: 28-ea`). A temporary JDK 17 was used to verify `.\gradlew.bat --version` and `.\gradlew.bat :aa-display:tasks --quiet`; both succeed.
- 2026-06-18 local build limit: `.\gradlew.bat :aa-display:assembleDebug --stacktrace` reaches Android SDK resolution, then stops before source compilation because the temporary SDK has not accepted licenses for `platforms;android-36` and `build-tools;35.0.0`. No installed Android Studio SDK exists on this host. Use a JDK 17 + Android SDK host with accepted licenses for the final APK build.
- Device/ROM behavior and logcat capture require a known-good rooted test device, so those checklist items remain open.

---

## 2. Add modern libxposed packaging metadata

### 2.1 Stop excluding required `META-INF/xposed` files

Current problem: `aa-display/build.gradle.kts` excludes all `META-INF/**`, which would remove `META-INF/xposed/java_init.list`, `module.prop`, and `scope.list` from the APK.

- [x] In `aa-display/build.gradle.kts`, remove the broad exclusion:

```kotlin
resources.excludes.addAll(
    arrayOf(
        "META-INF/**",
        "kotlin/**"
    )
)
```

- [x] Replace it with a narrow exclude list that does not match `META-INF/xposed/**`.
- [ ] Verify the final APK contains:
  - [ ] `META-INF/xposed/java_init.list`
  - [ ] `META-INF/xposed/module.prop`
  - [ ] `META-INF/xposed/scope.list`
- [x] Add a CI or local verification command after assemble:

```bash
unzip -l aa-display/build/outputs/apk/debug/*.apk | grep 'META-INF/xposed'
```

Local Gradle task added:

```bash
./gradlew :aa-display:verifyDebugXposedMetadata
```

Note: APK verification is pending until a JDK 17 + Android SDK API 36 host with accepted licenses runs `assembleDebug`.

### 2.2 Add `module.prop`

- [x] Create `aa-display/src/main/resources/META-INF/xposed/module.prop`.
- [x] Initial API 101 content:

```properties
minApiVersion=101
targetApiVersion=101
staticScope=true
exceptionMode=protective
```

- [x] Do not add `autoHotReload` yet. It is API 102+ only.
- [x] Keep `exceptionMode=protective` for the first migration to reduce blast radius from hook exceptions.
- [ ] Revisit exception mode only after parity testing is complete.

### 2.3 Add `java_init.list`

- [x] Create `aa-display/src/main/resources/META-INF/xposed/java_init.list`.
- [x] Use exactly one Java/Kotlin entry class to keep future API 102 hot reload possible:

```text
io.github.nitsuya.aa.display.xposed.LibXposedInit
```

- [x] Do not add multiple Java entry classes unless hot reload is intentionally abandoned.

Note: `LibXposedInit` is implemented in section 4.

### 2.4 Add `scope.list`

- [x] Create `aa-display/src/main/resources/META-INF/xposed/scope.list`.
- [x] Initial content:

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

- [x] Keep `assets/xposed_init` and Manifest legacy metadata only during a temporary compatibility window if necessary.
- [x] Do not allow both legacy and libxposed entry paths to initialize hooks in the same process at the same time.
- [x] Add a runtime guard if both paths temporarily coexist:
  - [x] shared process-local singleton state such as `HookRuntime.isInitialized`.
  - [x] log a warning when the legacy path is used.
- [x] After API 101 path is stable, remove:
  - [x] `aa-display/src/main/assets/xposed_init`
  - [x] Manifest `xposedmodule`
  - [x] Manifest `xposeddescription`
  - [x] Manifest `xposedminversion`
  - [x] Manifest `xposedscope`
  - [x] `@array/xposed_scope` if no other code uses it.

Note: legacy metadata and `XposedInit.kt` have been removed from the main source path. `HookRuntime` remains as the process-local init/log adapter for the API 101 path.

---

## 3. Update Gradle dependencies

### 3.1 Add libxposed API dependency

- [x] Add official libxposed API as `compileOnly` in `aa-display/build.gradle.kts`.
- [x] Pin the API artifact version used by the chosen framework documentation.
- [x] Do not package libxposed API classes into the APK.
- [x] Keep this dependency isolated from app runtime code.

Example shape, confirm the exact artifact coordinate before committing:

```kotlin
compileOnly("<official-libxposed-api-coordinate>")
```

Chosen coordinate: `compileOnly("io.github.libxposed:api:101.0.1")`. Maven Central also has API 102, but API 101 is pinned here to avoid compiling against API 102-only surface during this milestone.

Verification note: inspected `api-101.0.1.aar` from Maven Central. API 101 includes the callbacks used here, `PROP_CAP_SYSTEM`, `PROP_CAP_REMOTE`, `PROP_RT_API_PROTECTION`, `ExceptionMode.PROTECTIVE`, `HookBuilder.setPriority(...)`, `HookBuilder.setExceptionMode(...)`, and `HookBuilder.intercept(...)`; it does not include API 102 `setId(...)` / hot reload callbacks, and this migration layer does not call them.

Config sync note: added `implementation("io.github.libxposed:service:101.0.0")` for the module app process only, so the settings UI can sync `aadisplay_config` into framework remote preferences. The hook API itself remains `compileOnly`. Its transitive Kotlin stdlib is excluded so the Kotlin 2.0 build chain is not silently upgraded by a Java-only service helper dependency.

### 3.2 Remove legacy Xposed API dependency from the migrated path

Current dependency:

```kotlin
compileOnly(files("./libs/de.robv.android.xposed_api_82.jar"))
```

- [x] Remove this dependency once every `de.robv.android.xposed.*` import is gone from the API 101 implementation.
- [x] Do not introduce any new code that imports:
  - `de.robv.android.xposed.XposedBridge`
  - `de.robv.android.xposed.XposedHelpers`
  - `de.robv.android.xposed.XC_MethodHook`
  - `de.robv.android.xposed.XSharedPreferences`
  - `de.robv.android.xposed.IXposedHookLoadPackage`
  - `de.robv.android.xposed.IXposedHookZygoteInit`
  - `de.robv.android.xposed.callbacks.XC_LoadPackage`

Note: `compileOnly(files("./libs/de.robv.android.xposed_api_82.jar"))` and `aa-display/libs/de.robv.android.xposed_api_82.jar` have been removed. `rg` now returns no legacy Xposed API imports in `aa-display/src/main/java`.

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

- [x] Audit EzXHelper usage by category:
  - [x] pure reflection helper usage.
  - [x] hook registration usage.
  - [x] context/classloader initialization usage.
  - [x] logging usage.
- [x] Replace all hook registration usage with libxposed `hook(Executable).intercept(...)`.
- [x] Replace `XC_MethodHook.Unhook` with libxposed `HookHandle`.
- [x] Replace `EzXHelperInit.initZygote(...)` by deleting the zygote initialization path.
- [x] Replace `EzXHelperInit.initHandleLoadPackage(...)` with an internal runtime context object.
- [x] Replace `EzXHelperInit.initAppContext()` with explicit context capture from hooked `Application`, `Instrumentation`, or system_server sources.
- [x] Decide whether pure reflection helpers can remain without importing or invoking legacy Xposed APIs.
- [x] For API 102 readiness, prefer removing EzXHelper entirely from the Xposed layer unless verified that it does not call `de.robv.android.xposed.*` internally.

Audit notes:

- Xposed-layer hook registration now goes through `XposedRuntimeContext`.
- Xposed-layer reflection helpers now live in `XposedRuntimeContext` and `XposedReflection.kt`.
- EzXHelper has been removed from the Gradle dependency list. Remaining convenience calls were replaced by project-owned helpers in `ReflectionUtils.kt`, and `CommonContextWrapper` now uses `ModuleResourceBridge` instead of `EzXHelperInit`.
- Logging now goes through `HookRuntime` / `XposedLogAdapter`, with Android `Log` fallback before module binding.

---

## 4. Introduce a new libxposed runtime layer

### 4.1 Add a single modern entry class

- [x] Add `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/LibXposedInit.kt`.
- [x] Make it extend `io.github.libxposed.api.XposedModule`.
- [x] Implement only API 101-compatible callbacks for the first milestone:
  - [x] `onModuleLoaded(...)`
  - [x] `onPackageLoaded(...)`
  - [x] `onPackageReady(...)`
  - [x] `onSystemServerStarting(...)`
- [x] Do not implement `onHotReloading(...)` or `onHotReloaded(...)` until the target is raised to API 102.
- [x] Log framework name/version/API version/properties during `onModuleLoaded(...)`.
- [x] Check framework properties:
  - [x] If system_server hooks are needed, verify `PROP_CAP_SYSTEM` exists before attempting system_server work.
  - [x] If remote preferences are used, verify `PROP_CAP_REMOTE` exists before depending on them.

### 4.2 Add a process-local runtime context

- [x] Add an internal context model, for example `XposedRuntimeContext`:

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

- [x] Include helper methods:
  - [x] `hookBefore(...)`
  - [x] `hookAfter(...)`
  - [x] `hookReplace(...)`
  - [x] `findMethod(...)`
  - [x] `findAllMethods(...)`
  - [x] `findConstructor(...)`
  - [x] `loadClass(...)`
  - [x] logging wrappers.
- [x] Keep these wrappers backed by libxposed only.
- [x] Do not hide legacy Xposed APIs behind wrappers.

### 4.3 Recreate `hookBefore`, `hookAfter`, and abort semantics

- [x] Implement `hookBefore` on top of libxposed `intercept`:
  - [x] Read immutable args through `chain.getArgs()`.
  - [x] Copy to a mutable array if arguments need changes.
  - [x] Call `chain.proceed(argsArray)` with modified args.
- [x] Implement `hookAfter` on top of libxposed `intercept`:
  - [x] Call `chain.proceed()` first.
  - [x] Return the original or modified result.
- [x] Implement replacement / abort helpers:
  - [x] For non-void methods: return the desired replacement value without calling `chain.proceed()`.
  - [x] For void methods and constructors: return `null` without calling `chain.proceed()`.
- [x] Replace current `abortMethod()` semantics carefully:
  - [x] Current `abortMethod()` sets `param.result = null`.
  - [x] In the new model, this means do not call `proceed()` and return `null`.
  - [ ] Verify the hooked method actually accepts `null`/void behavior.

### 4.4 Hook handle management

- [x] Add a project-owned handle type for migrated hooks:

```kotlin
class ManagedHookHandle(private val handle: XposedInterface.HookHandle) {
    fun unhook() = handle.unhook()
}
```

- [x] Do not use API 102-only hook ids in the API 101 implementation.
- [x] Store handles for hooks that are currently one-shot and manually unhooked:
  - [x] `ServiceManager.addService`
  - [x] `ActivityManagerService` constructor hook.
  - [x] `ActivityManagerService.systemReady`.
  - [x] `Instrumentation.callApplicationOnCreate` in Gearhead.
  - [x] `Instrumentation.callApplicationOnCreate` in other apps.
  - [x] power button hook.
  - [x] application bind/start process hooks.
- [x] Add cleanup helpers even though API 101 has no `detach()` lifecycle.

Note: dynamic hooks now keep `ManagedHookHandle` references and one-shot hooks unhook themselves after first successful use. API 102 id/atomic replacement support is intentionally deferred.

---

## 5. Migrate `XposedInit.kt`

Current file: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/XposedInit.kt`

- [x] Do not keep this file in the API 101 source path; no temporary legacy build flavor is kept here.
- [x] Do not let it run in the API 101 build variant.
- [x] Port logic into `LibXposedInit.kt`:

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

- [x] `onSystemServerStarting(...)`:
  - [x] initialize `AndroidHook` with system_server classloader.
- [x] `onPackageLoaded(...)` or `onPackageReady(...)` for Gearhead:
  - [x] if `packageName == "com.google.android.projection.gearhead"`, initialize `AndroidAuoHook`.
- [x] `onPackageLoaded(...)` or `onPackageReady(...)` for other scoped third-party packages:
  - [x] skip module package itself.
  - [x] skip system uid if equivalent information is available.
  - [x] initialize `OtherHook` only for intended scope packages.
- [x] Preserve process filtering by process name:
  - [x] `com.google.android.projection.gearhead`
  - [x] `com.google.android.projection.gearhead:projection`
  - [x] `com.google.android.projection.gearhead:car`

Important detail:

- [x] API 101 `PackageLoadedParam` provides package name, application info, first-package flag, and default classloader; it does not mirror every legacy `XC_LoadPackage.LoadPackageParam` field directly. Create your own context and pass only what each hook needs.

Notes:

- `XposedInit.kt` has been removed from the main source set; the API 101 build now has a single Java entry in `META-INF/xposed/java_init.list`.
- `LibXposedInit` routes `onSystemServerStarting(...)`, Gearhead package callbacks, and the intended static third-party scopes.

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

- [x] Remove imports of:
  - [x] `de.robv.android.xposed.XC_MethodHook`
  - [x] `de.robv.android.xposed.callbacks.XC_LoadPackage`
- [x] Replace `abortMethod()` extension with a libxposed-chain-compatible helper.
- [x] Make hook initialization idempotent per process.
- [x] Review `isInit`: current singleton objects are process-local, but the same singleton may receive multiple package callbacks in one process. Keep or replace with per-process keys if necessary.

### 6.2 Replace `AaHook`

Current file: `AndroidAuoHook.kt` defines `AaHook` using `XC_LoadPackage.LoadPackageParam`.

- [x] Move `AaHook` to its own file or keep it near `AndroidAuoHook`, but change signatures:

```kotlin
abstract class AaHook {
    abstract val tagName: String
    abstract fun isSupportProcess(processName: String): Boolean
    open fun loadDexClass(bridge: DexKitBridge, ctx: XposedRuntimeContext) {}
    abstract fun hook(config: SharedPreferences, ctx: XposedRuntimeContext)
}
```

- [x] Ensure every AA hook receives the same context type.
- [x] Ensure process name is provided reliably. If libxposed param does not expose process name directly, derive it once using `ApplicationInfo.processName`, `ActivityThread.currentProcessName()`, `/proc/self/cmdline`, or an equivalent safe helper.

Notes:

- `HookRuntime.markHookInitialized(...)` uses a process/package/system/tag key; `BaseHook.isInit` is still kept for compatibility/debug visibility.
- `AaHook` remains near `AndroidAuoHook` for now, but every AA child hook signature now receives `XposedRuntimeContext`.

---

## 7. Migrate system_server hook: `AndroidHook.kt`

Current file: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/AndroidHook.kt`

### 7.1 Entry point

- [x] Move initialization from package-load routing to `onSystemServerStarting(...)`.
- [x] Pass `SystemServerStartingParam.getClassLoader()` into the runtime context.
- [x] Ensure all system_server class lookups use that classloader.

### 7.2 `ServiceManager.addService`

Current behavior:

- Hook `android.os.ServiceManager.addService`.
- Wait for `param.args[0] == "package"`.
- Unhook once package service is obtained.
- Cast `param.args[1]` to `IPackageManager`.
- Call `BridgeService.register(pms)`.

Migration tasks:

- [x] Resolve `android.os.ServiceManager.addService` with system_server classloader.
- [x] Register a before-interceptor.
- [x] Copy args to an array only if needed; read arg 0/1 safely.
- [x] If arg 0 is `"package"`, unhook the handle.
- [x] Preserve exception containment: log but do not crash system_server.
- [x] Verify `BridgeService.register(pms)` still hooks `pms.onTransact` using the new hook wrapper.

### 7.3 `ActivityManagerService` constructor

Current behavior:

- Hook all constructors of `com.android.server.am.ActivityManagerService` whose first parameter is `Context`.
- After constructor, unhook all constructor hooks.
- Read `mUiContext` and set `CoreManagerService.systemContext`.

Migration tasks:

- [x] Reimplement constructor after-hook with libxposed.
- [x] Hook every matching constructor and store every handle.
- [x] After first successful call, unhook all handles.
- [x] Keep `getObjectAs("mUiContext")` functionality using reflection helper.
- [x] Preserve error logging if no matching constructor exists.

### 7.4 `ActivityManagerService.systemReady`

Current behavior:

- Hook `systemReady` after call.
- Unhook after first execution.
- Call `CoreManagerService.systemReady()`.

Migration tasks:

- [x] Reimplement as after-interceptor.
- [x] Ensure `chain.proceed()` runs before `CoreManagerService.systemReady()`.
- [x] Unhook after first successful call.
- [x] Keep failure isolated to avoid system_server crash.

### 7.5 `ActivityTaskSupervisor` / `ActivityStackSupervisor` display launch permission

Current behavior:

- For Android Q+:
  - Android S+: `com.android.server.wm.ActivityTaskSupervisor`
  - Android Q/R: `com.android.server.wm.ActivityStackSupervisor`
- Hook `isCallerAllowedToLaunchOnDisplay(...)` after call.
- If result is false and target display id equals AADisplay virtual display id, return true.

Migration tasks:

- [x] Reimplement as after-interceptor.
- [x] Preserve SDK-dependent class selection.
- [ ] Verify method signature on current supported Android versions.
- [x] Add defensive fallback logging if method not found.

### 7.6 Power button hook

Current behavior:

- Lazy resolve `PhoneWindowManager.powerPress(long, int, boolean)`.
- Hook/unhook dynamically through `AndroidHook.Power.hook()` / `unHook()`.
- Before original method, if `beganFromNonInteractive == false`, toggle display power and abort original method.

Migration tasks:

- [x] Replace `XC_MethodHook.Unhook?` with `HookHandle?`.
- [x] In replacement/before helper, return `null` without calling `proceed()` when aborting.
- [x] Preserve original behavior when `beganFromNonInteractive == true`.
- [ ] Verify no recursion through `CoreApi.toggleDisplayPower()`.

### 7.7 Application context density hook

Current behavior:

- Hook `ActivityTaskManagerService.startProcessAsync` before.
- Hook `IApplicationThread$Stub$Proxy.bindApplication` before.
- Track display id per package and mutate `Configuration.densityDpi`.

Migration tasks:

- [x] Reimplement both hooks as before-interceptors.
- [x] Use copied args when mutating arguments or mutable object fields.
- [x] Keep synchronized map behavior.
- [x] Preserve clear/unhook behavior.

Notes:

- `AndroidHook` is now initialized only from `LibXposedInit.onSystemServerStarting(...)`.
- `BridgeService.register(ctx, pms)` was migrated with the system_server path because package-service interception depends on it.
- Open validation: display permission method signatures and power-key recursion still need device/logcat verification.

---

## 8. Migrate package hooks: `AndroidAuoHook.kt`

Current file: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/AndroidAuoHook.kt`

### 8.1 Process routing

- [x] Preserve process constants:
  - `com.google.android.projection.gearhead`
  - `com.google.android.projection.gearhead:projection`
  - `com.google.android.projection.gearhead:car`
- [x] Only initialize child hooks matching the current process.
- [x] Do not run DexKit scans in unsupported processes.

### 8.2 Config loading

Current behavior uses:

```kotlin
XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
```

Migration tasks:

- [x] Replace with a `ConfigProvider` abstraction.
- [x] First implementation option: libxposed `getRemotePreferences(AADisplayConfig.ConfigName)`.
- [x] Validate that the app UI writes to the same preference group expected by the framework.
- [x] If remote preferences cannot be written directly by the app UI, implement explicit sync:
  - [x] app writes local config.
  - [x] app exports/syncs config into framework remote preferences or a remote file.
  - [x] hook process reads remote preferences/file.
- [x] Keep `reload()` equivalent if settings can change without process restart.
- [x] If reload is not available, document which changes require target process restart.
- [x] Avoid `MODE_WORLD_READABLE` or filesystem permission hacks.

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

- [x] Reimplement as before-interceptor.
- [x] Obtain `Application` from arg 0.
- [x] Use `application.applicationInfo.sourceDir` or context application info instead of legacy `lpparam.appInfo.sourceDir`.
- [x] Set the app context explicitly in the new runtime helper.
- [ ] Verify `System.loadLibrary("dexkit")` still works from the target process.
- [x] Preserve per-child-hook try/catch behavior:
  - [x] one child hook failing in `loadDexClass` must not block the others.
  - [x] one child hook failing in `hook` must not block the others.
- [x] Keep log timings for DexKit scan.

Notes:

- Hook processes now use `RemoteConfigProvider(ctx, AADisplayConfig.ConfigName, tag)`.
- The module app registers `XposedServiceHelper` in `Application.onCreate()` and syncs local `aadisplay_config` changes to libxposed remote preferences via `XposedConfigSync`.
- `SettingsActivity`, `AaMainFragment`, and `ShellManagerService` now use `MODE_PRIVATE`; no hook-side world-readable preference access remains.
- Settings that are read only when a target process registers hooks still require restarting that target process unless the hook itself reads preferences dynamically.

---

## 9. Migrate `OtherHook.kt`

Current behavior:

- Hook `Instrumentation.callApplicationOnCreate(Application)` before.
- Initialize app context.
- Find Android `status_bar_height` resource id.
- Hook `Resources.getDimension`, `getDimensionPixelSize`, `getDimensionPixelOffset` before.
- Return 0 for `status_bar_height`.

Tasks:

- [x] Reimplement `Instrumentation.callApplicationOnCreate` as before-interceptor.
- [x] Preserve one-shot unhook.
- [x] Replace `InitFields.appContext` with the explicit application context captured from arg 0.
- [x] Reimplement resource methods with replacement behavior:
  - [x] If arg 0 is not the target resource id, call `proceed()`.
  - [x] If method is `getDimension`, return `0F`.
  - [x] Else return `0`.
- [x] Scope this hook narrowly to intended apps; avoid broad injection into every user app unless explicitly desired.

Note: `OtherHook` is routed only for the static scoped third-party packages in `LibXposedInit`.

---

## 10. Migrate Android Auto child hooks

### 10.1 `AaBasicsHook.kt`

Current behavior:

- Android R+: hook `InstallSourceInfo.getInitiatingPackageName()` after and return `com.android.vending`.
- Older: hook `PackageManager.getInstallerPackageName()` after and return `com.android.vending`.

Tasks:

- [x] Reimplement both as after-interceptors.
- [x] Keep SDK branch.
- [x] Return original result on errors.
- [x] Verify no hidden dependency on `XC_LoadPackage.LoadPackageParam` remains.

### 10.2 `AaSignatureHook.kt`

Current behavior:

- DexKit finds Android Auto signature verifier method.
- Hook method after.
- If checked package equals module application id, return true.

Tasks:

- [x] Keep DexKit search logic.
- [x] Replace `XC_LoadPackage.LoadPackageParam` with runtime context.
- [x] Reimplement method hook as after-interceptor.
- [x] Preserve `BuildConfig.APPLICATION_ID` check.
- [x] Add log for ambiguous or missing DexKit result.

### 10.3 `AaDpiHook.kt`

Current behavior:

- DexKit finds `DisplayParams` class.
- Resolve AA 16.7 and 16.1 constructor shapes.
- Resolve `CarDisplay` constructor shapes.
- Hook constructors before and after.
- Mutate density DPI arguments.

Tasks:

- [x] Keep constructor resolution logic exactly unless tests require change.
- [x] Reimplement constructor after-log using `chain.proceed(args)` then log result/constructed object behavior according to libxposed constructor semantics.
- [x] Reimplement constructor before-mutation:
  - [x] Copy `chain.getArgs()` into a mutable array.
  - [x] Change `DISPLAY_PARAMS_DPI_ARG` or `CarDisplay` arg index 2.
  - [x] Call `chain.proceed(modifiedArgs)`.
- [x] Verify constructor interceptor return value expectations: constructors should return `null`; do not return the created object unless libxposed requires otherwise.
- [ ] Confirm the 16.7/16.1 constructor assumptions on current Android Auto version.

### 10.4 `AaBtnEventHook.kt`

Current behavior:

- Hook `ContextWrapper.registerReceiver` before.
- Detect media/projected key event receivers.
- Hook receiver `onReceive` before.
- Abort original receiver and send AADisplay broadcasts for click/long-click.

Tasks:

- [x] Reimplement `registerReceiver` before-interceptor.
- [x] Reimplement dynamic `onReceive` hook using libxposed.
- [x] Ensure each receiver class is hooked once.
- [x] Preserve synchronized sets/maps.
- [x] For abort behavior, return `null` without calling `proceed()`.
- [x] Preserve default voice assistant behavior when custom shell is blank.
- [ ] Verify long-press tracking still works with immutable chain args.

### 10.5 `AaUiHook.kt`

This is the highest-risk business hook. It contains layout rewriting, facet bar injection, click listener protection, auto-open, portrait bottom bar, landscape vertical rail, and corner-radius hooks.

Tasks:

- [x] Migrate in small commits by sub-feature:
  - [x] DexKit layout class discovery.
  - [x] `LayoutInfo` constructor hook.
  - [x] facet bar layout id resolution.
  - [x] `LayoutInflater.inflate` injection.
  - [x] base click/long-click listener hook.
  - [x] radius hook.
- [x] For `LayoutInfo` constructor:
  - [x] Preserve int layoutType path for AA 16.1 and older.
  - [x] Preserve enum layoutType path for AA 16.7+.
  - [x] Preserve `PORTRAIT_SHORT` bottom bar rewrite.
  - [x] Preserve vertical rail resource fallback order: legacy first, cielo fallback.
  - [x] Use `chain.proceed(modifiedArgs)` after mutating copied args.
- [x] For `LayoutInflater.inflate`:
  - [x] Call `chain.proceed()` first.
  - [x] If inflated resource id is not in `resLayoutFacetBarIds`, return original result.
  - [x] If matched, build custom `aa_facet_bar` and return it.
  - [x] On exception, return original result rather than breaking Android Auto UI.
- [x] For `View.setOnClickListener` and `View.setOnLongClickListener`:
  - [x] Recreate `XCallback.PRIORITY_LOWEST` behavior with libxposed priority constants.
  - [ ] Confirm priority ordering matches legacy behavior.
  - [x] Preserve `FinallyListener` logic.
  - [x] For abort behavior, return `null` without calling `proceed()`.
- [x] For auto-open:
  - [x] Confirm `startMethod?.invoke(null, Intent(...))` remains valid after context migration.
  - [x] Preserve delayed invocation and error logging.
- [x] For resources and view ids:
  - [x] Replace `InitFields.appContext` with explicit application context.
  - [x] Do not use module app context when target app context is required.
- [ ] Regression test portrait and landscape separately.

### 10.6 `AaPropsHook.kt`

Current behavior:

- DexKit finds phenotype props class/method.
- Hook method after and override selected Gearhead props.
- Hook `ContentResolver.query(Uri, Array<String>, String, Array<String>, String)` after for GMS car props.
- Merge custom `MatrixCursor` with existing cursor.

Tasks:

- [x] Keep DexKit class/method/field discovery logic.
- [x] Reimplement props method as after-interceptor.
- [x] Reimplement `ContentResolver.query` as after-interceptor.
- [x] Preserve cursor merge behavior.
- [x] Ensure returned cursors are not prematurely closed.
- [x] Preserve per-value type conversion and error logging.

Notes:

- All AA child hooks now accept `XposedRuntimeContext`.
- Static checks show no legacy `XC_LoadPackage`/`XC_MethodHook`/`XSharedPreferences` imports remain.
- Open validation: current AA constructor signatures, priority ordering, long-press behavior, and portrait/landscape UI parity require device/DHU testing.

---

## 11. Migrate `BridgeService.kt`

Current behavior:

- `BridgeService.register(pms)` hooks `pms.onTransact` before.
- It handles custom transaction code `AADD`.
- It writes `CoreManagerService.instance` binder into reply.

Tasks:

- [x] Replace `pms.javaClass.findMethod(true) { name == "onTransact" }.hookBefore` with the new hook wrapper.
- [x] Preserve `myTransact(code, data, reply)` logic exactly.
- [x] For handled transactions, return `true` without calling `proceed()`.
- [x] For unhandled transactions, call `proceed()`.
- [x] Preserve `data.setDataPosition(0)` and `reply?.setDataPosition(0)` behavior.
- [x] Keep Binder caller UID check.
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

- [x] Replace `XSharedPreferences` with `ConfigProvider`.
- [x] Make `CoreManagerService` independent from legacy Xposed classes.
- [x] Decide config ownership:
  - [x] Option A: hooks and service read libxposed remote preferences directly.
  - [x] Option B: app writes local preferences and explicitly syncs to remote preferences/file.
  - [ ] Option C: app process sends config through binder to system_server service.
- [x] Ensure `config.reload()` equivalent exists before `onCreateDisplay(...)` reads settings.
- [x] If live reload cannot be preserved, document required restart boundaries.
- [ ] Test all settings read by:
  - [ ] `AaVirtualDisplayAdapter`
  - [ ] `DisplayWindow`
  - [ ] `AndroidHook.Power`
  - [ ] AA hooks.

---

## 13. Replace logging

Current code imports and calls project-local `log(...)`, while some helpers may depend on legacy Xposed logging.

Tasks:

- [x] Implement a logging adapter backed by libxposed `log(priority, tag, msg)` and `log(priority, tag, msg, throwable)`.
- [x] Keep Android `Log` fallback for app-only code if needed.
- [x] Ensure logs still include existing tags for baseline comparison.
- [x] Do not call `XposedBridge.log` from API 101 code.

---

## 14. Remove every legacy API import from migrated code

Before declaring the API 101 migration complete, this command must return nothing for the API 101 source set:

```bash
grep -RInE 'de\.robv\.android\.xposed|IXposedHook|XC_MethodHook|XC_LoadPackage|XSharedPreferences|XposedBridge|XposedHelpers' aa-display/src/main/java aa-display/src/main/kotlin
```

Tasks:

- [x] Remove or isolate all matching imports.
- [x] If a temporary legacy flavor remains, keep it in a separate source set or branch, not mixed into the API 101 runtime path.
- [x] Remove `de.robv.android.xposed_api_82.jar` after no code depends on it.
- [ ] Confirm R8/ProGuard does not keep legacy code accidentally reachable.

Note: local `rg` checks for legacy Xposed imports, legacy metadata, and API 102-only calls currently return no matches.

---

## 15. API 102 readiness rules while implementing API 101

Do these during API 101 migration so API 102 is a small follow-up rather than a second rewrite.

- [x] Keep exactly one Java entry class in `java_init.list`.
- [x] Do not introduce any new `de.robv.android.xposed.*` usage.
- [x] Do not call Xposed APIs through reflection or dynamically loaded code.
- [x] Do not hide Xposed API calls inside DexKit-loaded code or plugin code.
- [x] Design hook registration around a small wrapper that can later expose API 102 hook ids.
- [ ] Store hook handles centrally so API 102 atomic replacement can be added later.
- [x] Keep process detachment as an optional abstraction:
  - [x] API 101: no-op / return early.
  - [ ] API 102: can call `detach()` for unsupported processes after migration.
- [x] Keep hot reload concerns isolated:
  - [x] no static references to obsolete target app classes that would survive generation changes.
  - [x] no global classloader cache without process/generation key.
  - [ ] all hook handles can be enumerated and unhooked if API 102 hot reload is enabled later.
- [x] Do not set `autoHotReload=true` until `targetApiVersion=102`.

---

## 16. Build and shrinker details

- [x] Keep `-keep class io.github.nitsuya.aa.display.** { *; }` until migration is stable.
- [x] Add explicit keep rule for the libxposed entry class if needed:

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

- [x] Every hook registration failure must log and continue unless the feature cannot safely proceed.
- [x] system_server hook failures must never throw out of interceptors.
- [x] Android Auto UI hook failures must return original UI results.
- [x] DexKit failures in one child hook must not block other child hooks.
- [x] Config read failures must disable dependent feature and log a clear message.
- [x] Binder transaction failures must reset parcel positions as current code does.
- [x] Constructor signature mismatches must log enough information to update the hook for new AA versions.

---

## 19. Suggested implementation order

1. [ ] Add docs and baseline logs.
2. [x] Add `META-INF/xposed` files and fix packaging excludes.
3. [x] Add libxposed dependency and `LibXposedInit` skeleton.
4. [x] Implement logging adapter.
5. [x] Implement runtime context and reflection helpers.
6. [x] Implement libxposed hook wrapper helpers.
7. [x] Migrate `BridgeService` hook helper dependency.
8. [x] Migrate `AndroidHook` system_server path.
9. [x] Migrate config provider away from `XSharedPreferences`.
10. [x] Migrate `AndroidAuoHook` entry and DexKit setup.
11. [x] Migrate `AaBasicsHook`.
12. [x] Migrate `AaSignatureHook`.
13. [x] Migrate `AaDpiHook`.
14. [x] Migrate `AaPropsHook`.
15. [x] Migrate `AaBtnEventHook`.
16. [x] Migrate `AaUiHook` in sub-features.
17. [x] Migrate `OtherHook`.
18. [x] Remove legacy imports and dependencies.
19. [x] Remove legacy metadata and `assets/xposed_init`.
20. [ ] Full regression test.
21. [ ] Prepare optional API 102 follow-up issue.

---

## 20. Optional API 102 follow-up after API 101 is stable

Only do this after the API 101 version passes regression testing.

- [ ] Raise `targetApiVersion=102` while keeping `minApiVersion=101` only if the code handles runtime API checks correctly. Otherwise raise both intentionally.
- [x] Confirm zero legacy Xposed API calls remain.
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
- [x] `module.prop` uses `minApiVersion=101` and `targetApiVersion=101`.
- [x] The module entry class extends `XposedModule`.
- [x] No runtime code imports `de.robv.android.xposed.*`.
- [x] No code uses `IXposedHookZygoteInit`.
- [x] No code uses `IXposedHookLoadPackage`.
- [x] No code uses `XC_MethodHook`.
- [x] No code uses `XC_LoadPackage.LoadPackageParam`.
- [x] No code uses `XSharedPreferences`.
- [x] `de.robv.android.xposed_api_82.jar` is removed from Gradle dependencies.
- [ ] system_server functionality works through `onSystemServerStarting(...)`.
- [ ] Gearhead hooks work in all required processes.
- [ ] Third-party scoped app hook still works.
- [ ] Every current user-facing feature matches baseline behavior.
- [x] Logs are sufficient to debug AA version drift.
- [x] API 102 follow-up can be done without another full rewrite.

Latest implementation note:

- API 101 code path has been migrated through the planned hook surface: system_server, Gearhead main/projection/car hooks, other scoped app hooks, remote config, metadata packaging, logging, and project-owned reflection helpers.
- 2026-06-18 static checks show no legacy Xposed API imports/calls, no EzXHelper dependency, no legacy `assets/xposed_init` / manifest xposed metadata, and no API 102-only `autoHotReload`, `setId`, or `detach` calls in the migrated source/config.
- 2026-06-18 local checks completed with JDK 17: `:aa-display:tasks --quiet`, `:aa-display:checkKotlinGradlePluginConfigurationErrors --stacktrace`, `git diff --check`, and the legacy/API102 `rg` scan.
- Remaining blockers are APK verification and real device/DHU regression testing. Local Gradle configuration works with JDK 17, but this host has no accepted Android SDK API 36 / Build Tools 35 licenses, so `assembleDebug` cannot proceed to compilation here.

---

## 22. Build and test flow for external test hosts

Use this checklist on a clean build host or CI runner after pulling this branch.

### 22.1 Build host preparation

- [ ] Install JDK 17 and ensure `java -version` reports 17.x. Do not build with JDK `28-ea`.
- [ ] Install Android SDK Platform 36.
- [ ] Install Android SDK Build Tools 35.0.0.
- [ ] Install Android SDK Platform Tools.
- [ ] Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to the SDK path.
- [ ] Run `sdkmanager --licenses` and accept the required SDK licenses on that host.
- [ ] Confirm Gradle wrapper files are present:
  - [ ] `gradlew`
  - [ ] `gradlew.bat`
  - [ ] `gradle/wrapper/gradle-wrapper.jar`
  - [ ] `gradle/wrapper/gradle-wrapper.properties`
- [ ] Optional release build only: set `KEY_ANDROID` and provide `key.jks` at the expected project-relative path.

### 22.2 Static checks before build

- [ ] Run `git status --short` and confirm only intended migration files are changed.
- [ ] Run `git diff --check` and confirm there are no whitespace errors.
- [ ] Run this legacy/API102 scan and confirm it prints no matches:

```bash
rg -n 'de\.robv\.android\.xposed|IXposedHook|XC_MethodHook|XC_LoadPackage|XSharedPreferences|XposedBridge|XposedHelpers|com\.github\.kyuubiran\.ezxhelper|EzXHelper|assets/xposed_init|android:name="xposed|xposedmodule|xposeddescription|xposedminversion|autoHotReload|\.setId\(|\.detach\(' aa-display build.gradle.kts settings.gradle.kts gradle.properties -S
```

- [ ] Confirm `aa-display/src/main/resources/META-INF/xposed/module.prop` contains `minApiVersion=101` and `targetApiVersion=101`.
- [ ] Confirm `aa-display/src/main/resources/META-INF/xposed/java_init.list` contains exactly `io.github.nitsuya.aa.display.xposed.LibXposedInit`.
- [ ] Confirm `aa-display/src/main/resources/META-INF/xposed/scope.list` contains `system`, `android`, Gearhead, AutoNavi Auto, and Square Home.

### 22.3 Build commands

- [ ] Windows: run `.\gradlew.bat --version`.
- [ ] Linux/macOS: run `./gradlew --version`.
- [ ] Run `.\gradlew.bat :aa-display:tasks --quiet` or `./gradlew :aa-display:tasks --quiet`.
- [ ] Run `.\gradlew.bat :aa-display:assembleDebug --stacktrace` or `./gradlew :aa-display:assembleDebug --stacktrace`.
- [ ] Run `.\gradlew.bat :aa-display:verifyDebugXposedMetadata` or `./gradlew :aa-display:verifyDebugXposedMetadata`.
- [ ] Optional: run `.\gradlew.bat :aa-display:assembleRelease --stacktrace` or `./gradlew :aa-display:assembleRelease --stacktrace`.
- [ ] Archive the generated APK from `aa-display/build/outputs/apk/debug/` or `aa-display/build/outputs/apk/release/`.

### 22.4 APK inspection

- [ ] Inspect the APK and confirm these entries exist:
  - [ ] `META-INF/xposed/java_init.list`
  - [ ] `META-INF/xposed/module.prop`
  - [ ] `META-INF/xposed/scope.list`
- [ ] Inspect the APK and confirm these entries do not exist:
  - [ ] `assets/xposed_init`
  - [ ] legacy manifest xposed metadata.
- [ ] Inspect class/package strings and confirm no `de.robv.android.xposed` classes are packaged from this app.
- [ ] Confirm native libraries required by DexKit are packaged.
- [ ] Confirm `aauto.aar` classes/resources are still packaged.

### 22.5 Install and activation

- [ ] Install the APK on a rooted test device with a libxposed-compatible framework that supports API 101.
- [ ] Confirm the framework recognizes the module from `META-INF/xposed/module.prop`.
- [ ] Enable or verify scope for:
  - [ ] `system`
  - [ ] `android`
  - [ ] `com.google.android.projection.gearhead`
  - [ ] `com.autonavi.amapauto`
  - [ ] `com.ss.squarehome2`
- [ ] Reboot, or restart the framework-required target processes after enabling the module.

### 22.6 Runtime log checks

- [ ] Capture logcat from boot/module load through Android Auto startup.
- [ ] Confirm `AADisplay_LibXposedInit` logs module load, framework name/version/API, and framework properties.
- [ ] Confirm framework properties include expected `PROP_CAP_SYSTEM` for system_server hooks.
- [ ] Confirm framework properties include expected `PROP_CAP_REMOTE` for remote preferences.
- [ ] Confirm `AADisplay_ConfigSync` logs remote preference sync from the app process.
- [ ] Confirm no hook exception escapes into a target process crash.

### 22.7 system_server regression tests

- [ ] Confirm `onSystemServerStarting(...)` runs.
- [ ] Confirm `ServiceManager.addService` hook catches the package service.
- [ ] Confirm `BridgeService.register(...)` succeeds.
- [ ] Confirm the app can retrieve the `CoreManagerService` binder.
- [ ] Confirm `ActivityManagerService` constructor hook captures `mUiContext`.
- [ ] Confirm `systemReady` calls `CoreManagerService.systemReady()` once.
- [ ] Confirm virtual display creation, surface attach, destroy, and re-create.
- [ ] Confirm display launch permission override works.
- [ ] Confirm power button toggle works when enabled.
- [ ] Confirm no boot loop or system_server crash.

### 22.8 Android Auto regression tests

- [ ] Confirm Gearhead main process loads `AndroidAuoHook`.
- [ ] Confirm Gearhead `:projection` process loads supported hooks.
- [ ] Confirm Gearhead `:car` process loads supported hooks.
- [ ] Confirm config reads match app settings after `XposedConfigSync`.
- [ ] Confirm DexKit loads and class discovery succeeds.
- [ ] Confirm signature bypass works for the module package.
- [ ] Confirm DPI override applies to DisplayParams and CarDisplay.
- [ ] Confirm props/phenotype overrides apply in main and car processes.
- [ ] Confirm media/steering wheel button interception works.
- [ ] Confirm custom voice assistant shell behavior works.
- [ ] Confirm default voice assistant behavior is preserved when custom shell is blank.
- [ ] Confirm landscape vertical rail layout matches baseline.
- [ ] Confirm portrait bottom bar layout matches baseline.
- [ ] Confirm home, recent, back, auto-open, and radius override behavior.

### 22.9 Other scoped app regression tests

- [ ] Confirm `OtherHook` runs only for intended scoped packages.
- [ ] Confirm status bar height suppression works for selected apps.
- [ ] Confirm apps outside the intended scope do not show behavior changes.

### 22.10 Failure and compatibility notes

- [ ] Test at least one Android 12+ ROM and record exact Android build.
- [ ] Record framework name/version/versionCode/API from `AADisplay_LibXposedInit`.
- [ ] Record Android Auto / Gearhead version.
- [ ] Record whether current AA constructor signatures choose strict 16.7, strict 16.1, or fallback paths.
- [ ] Keep representative logcat output for every hook tag listed in section 1.
- [ ] Only after all API 101 tests pass, open a separate API 102 follow-up for `targetApiVersion=102`, hot reload, hook ids, and `detach()`.
