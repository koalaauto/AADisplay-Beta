package io.github.nitsuya.aa.display.xposed

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import java.util.Collections
import java.util.WeakHashMap

object ModuleResourceBridge {
    @Volatile
    private var moduleApkPath: String? = null

    private val patchedResources = Collections.synchronizedMap(WeakHashMap<Resources, String>())

    fun bind(applicationInfo: ApplicationInfo?) {
        moduleApkPath = applicationInfo?.sourceDir ?: applicationInfo?.publicSourceDir
    }

    @JvmStatic
    fun addModuleAssetPath(resources: Resources) {
        val path = moduleApkPath
        if (path.isNullOrBlank() || patchedResources[resources] == path) return

        runCatching {
            val addAssetPath = resources.assets.javaClass.getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPath.isAccessible = true
            val cookie = addAssetPath.invoke(resources.assets, path) as? Int ?: 0
            if (cookie != 0) {
                patchedResources[resources] = path
            } else {
                log("AADisplay_Resources", "addAssetPath returned 0 for $path")
            }
        }.onFailure { error ->
            log("AADisplay_Resources", "addModuleAssetPath failed for $path", error)
        }
    }
}
