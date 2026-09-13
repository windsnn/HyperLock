package io.github.windsnn.hyperlock

import android.util.Log

object HyperLog {
    private const val TAG_PREFIX = "HyperLock"

    @Volatile
    var isVerbose: Boolean = false

    /**
     * 严重错误与异常捕获（默认始终记录）
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("$TAG_PREFIX-$tag", message, throwable)
        } else {
            Log.e("$TAG_PREFIX-$tag", message)
        }
    }

    /**
     * 兼容性告警或权限受阻（默认始终记录）
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w("$TAG_PREFIX-$tag", message, throwable)
        } else {
            Log.w("$TAG_PREFIX-$tag", message)
        }
    }

    /**
     * 正常业务流转（仅详细日志开启时输出）
     */
    fun i(tag: String, message: String) {
        if (isVerbose) {
            Log.i("$TAG_PREFIX-$tag", message)
        }
    }

    /**
     * 详细跟踪（仅详细日志开启时输出）
     */
    fun d(tag: String, message: String) {
        if (isVerbose) {
            Log.d("$TAG_PREFIX-$tag", message)
        }
    }}
