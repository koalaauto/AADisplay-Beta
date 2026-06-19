package io.github.nitsuya.aa.display.xposed

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

interface ConfigProvider {
    fun getPreferences(): SharedPreferences
    fun reload()
}

class RemoteConfigProvider(
    private val ctx: XposedRuntimeContext,
    private val group: String,
    private val tag: String,
) : ConfigProvider {
    @Volatile
    private var preferences: SharedPreferences? = null

    override fun getPreferences(): SharedPreferences {
        preferences?.let { return it }
        val loaded = loadPreferences()
        preferences = loaded
        return loaded
    }

    override fun reload() {
        preferences = null
    }

    private fun loadPreferences(): SharedPreferences {
        if (!ctx.module.getFrameworkProperties().hasXposedProperty(XposedInterface.PROP_CAP_REMOTE)) {
            log(tag, "framework has no remote preference capability; using default hook config")
            return EmptySharedPreferences
        }
        return runCatching {
            ctx.module.getRemotePreferences(group).also {
                log(tag, "using libxposed remote preferences group=$group")
            }
        }.getOrElse { error ->
            log(tag, "failed to load libxposed remote preferences group=$group; using defaults", error)
            EmptySharedPreferences
        }
    }
}

class EmptyConfigProvider(private val tag: String) : ConfigProvider {
    private val preferences = EmptySharedPreferences

    override fun getPreferences(): SharedPreferences {
        log(tag, "using empty hook config")
        return preferences
    }

    override fun reload() = Unit
}

object EmptySharedPreferences : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = false
    override fun edit(): SharedPreferences.Editor = EmptyEditor
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}

private object EmptyEditor : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
    override fun remove(key: String?): SharedPreferences.Editor = this
    override fun clear(): SharedPreferences.Editor = this
    override fun commit(): Boolean = true
    override fun apply() = Unit
}
