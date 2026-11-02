package io.github.windsnn.hyperlock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.concurrent.thread

object SystemUiRestarter {
    fun restart(context: Context, targets: Set<ScopeApplication>) {
        thread(name = "scope-app-restart", isDaemon = true) {
            if (targets.isEmpty()) {
                showToast(context, context.getString(R.string.toast_restart_select_one))
                return@thread
            }

            val rootCheck = runSu("id")
            if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
                HyperLog.e("Restarter", "Root permission denied or unavailable: exitCode=${rootCheck.exitCode}")
                showToast(context, context.getString(R.string.toast_restart_no_root))
                return@thread
            }

            val failed = buildList {
                targets.forEach { target ->
                    HyperLog.d("Restarter", "Killing process: ${target.packageName}")
                    // 严格转义点号并锚定完整进程名，避免正则匹配误杀其他前缀或包含该包名的无关进程
                    val pattern = "^" + target.packageName.replace(".", "\\.") + "($| )"
                    if (runSu("pkill -f '$pattern'").exitCode != 0) {
                        add(context.getString(target.titleRes))
                    }
                }
            }
            if (failed.isEmpty()) {
                HyperLog.i("Restarter", "Successfully restarted scopes: ${targets.map { context.getString(it.titleRes) }}")
                showToast(context, context.getString(R.string.toast_restart_success))
            } else {
                HyperLog.w("Restarter", "Failed to restart scopes: ${failed.joinToString(", ")}")
                showToast(context, context.getString(R.string.toast_restart_failed, failed.joinToString(", ")))
            }
        }
    }

    private fun runSu(command: String): CommandResult = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        CommandResult(exitCode, output)
    }.getOrElse { error ->
        HyperLog.e("Restarter", "Error running su command: $command", error)
        CommandResult(-1, "")
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)
}

enum class ScopeApplication(val titleRes: Int, val packageName: String) {
    SYSTEM_UI(R.string.scope_system_ui, "com.android.systemui"),
    AOD(R.string.scope_aod, "com.miui.aod"),
}
