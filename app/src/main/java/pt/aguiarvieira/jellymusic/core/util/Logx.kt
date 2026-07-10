package pt.aguiarvieira.jellymusic.core.util

import android.util.Log
import pt.aguiarvieira.jellymusic.BuildConfig

/**
 * Thin logging wrapper that only emits in debug builds. The [BuildConfig.DEBUG] guard is a
 * compile-time constant, so R8 removes these calls entirely from release builds.
 */
object Logx {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, message, throwable)
    }
}
