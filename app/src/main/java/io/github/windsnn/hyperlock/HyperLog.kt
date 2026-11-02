package io.github.windsnn.hyperlock

import android.util.Log

object HyperLog {
    private const val TAG_PREFIX = "HyperLock"

    @Volatile
    var isVerbose: Boolean = false

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("$TAG_PREFIX-$tag", message, throwable)
        } else {
            Log.e("$TAG_PREFIX-$tag", message)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w("$TAG_PREFIX-$tag", message, throwable)
        } else {
            Log.w("$TAG_PREFIX-$tag", message)
        }
    }

    fun i(tag: String, message: String) {
        if (isVerbose) {
            Log.i("$TAG_PREFIX-$tag", message)
        }
    }

    fun d(tag: String, message: String) {
        if (isVerbose) {
            Log.d("$TAG_PREFIX-$tag", message)
        }
    }}
