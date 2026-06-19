package io.github.nitsuya.aa.display.xposed

import android.util.Log

fun log(tag: String, message: String) {
    XposedLogAdapter.log(Log.INFO, tag, message)
}

fun log(tag: String, message: String, t: Throwable?) {
    XposedLogAdapter.log(Log.ERROR, tag, message, t)
}
