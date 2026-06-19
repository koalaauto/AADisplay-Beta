package io.github.nitsuya.aa.display.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object XposedConfigSync : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val TAG = "AADisplay_ConfigSync"

    @Volatile
    private var localPreferences: SharedPreferences? = null

    @Volatile
    private var remotePreferences: SharedPreferences? = null

    @Volatile
    private var listenerRegistered = false

    fun init(context: Context) {
        val appContext = context.applicationContext
        val local = appContext.getSharedPreferences(AADisplayConfig.ConfigName, Context.MODE_PRIVATE)
        localPreferences = local
        if (!listenerRegistered) {
            local.registerOnSharedPreferenceChangeListener(this)
            listenerRegistered = true
        }

        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    bindService(service, local)
                }

                override fun onServiceDied(service: XposedService) {
                    remotePreferences = null
                    Log.w(TAG, "libxposed service died")
                }
            })
        }.onFailure { error ->
            Log.w(TAG, "libxposed service listener unavailable", error)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null) return
        val remote = remotePreferences ?: return
        runCatching {
            syncKey(sharedPreferences, remote, key)
        }.onFailure { error ->
            Log.w(TAG, "sync key failed: $key", error)
        }
    }

    private fun bindService(service: XposedService, local: SharedPreferences) {
        runCatching {
            val remote = service.getRemotePreferences(AADisplayConfig.ConfigName)
            remotePreferences = remote
            syncAll(local, remote)
            Log.i(TAG, "synced local config to libxposed remote preferences")
        }.onFailure { error ->
            remotePreferences = null
            Log.w(TAG, "libxposed remote preferences unavailable", error)
        }
    }

    private fun syncAll(local: SharedPreferences, remote: SharedPreferences) {
        val all = local.all
        if (all.isEmpty()) return
        val editor = remote.edit()
        all.forEach { (key, value) -> put(editor, key, value) }
        editor.apply()
    }

    private fun syncKey(local: SharedPreferences, remote: SharedPreferences, key: String) {
        val editor = remote.edit()
        if (!local.contains(key)) {
            editor.remove(key)
        } else {
            put(editor, key, local.all[key])
        }
        editor.apply()
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> {
                val strings = value.filterIsInstance<String>().toSet()
                editor.putStringSet(key, strings.toMutableSet())
            }
            null -> editor.remove(key)
            else -> editor.putString(key, value.toString())
        }
    }
}
