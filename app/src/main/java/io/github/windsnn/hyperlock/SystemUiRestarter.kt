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
                showToast(context, "请至少选择一个作用域应用")
                return@thread
            }

            val rootCheck = runSu("id")
            if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
                HyperLog.e("Restarter", "Root permission denied or unavailable: exitCode=${rootCheck.exitCode}")
                showToast(context, "未获得 root 权限，重启失败")
                return@thread
            }

            val failed = buildList {
                targets.forEach { target ->
                    HyperLog.d("Restarter", "Killing process: ${target.packageName}")
                    // 锚定整行 cmdline：pkill -f 的模式是正则，包名里的点号会变成通配符，
                    // 未锚定时可能命中无关进程（AGENTS.md 只允许这两个目标）。
                    val pattern = "^" + target.packageName.replace(".", "\\.") + "($| )"
                    if (runSu("pkill -f '$pattern'").exitCode != 0) {
                        add(target.title)
                    }
                }
            }
            if (failed.isEmpty()) {
                HyperLog.i("Restarter", "Successfully restarted scopes: ${targets.map { it.title }}")
                showToast(context, "已重启选中的作用域应用")
            } else {
                HyperLog.w("Restarter", "Failed to restart scopes: ${failed.joinToString("、")}")
                showToast(context, "重启失败：${failed.joinToString("、")}")
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

enum class ScopeApplication(val title: String, val packageName: String) {
    SYSTEM_UI("系统界面", "com.android.systemui"),
    AOD("息屏与锁屏编辑", "com.miui.aod"),
}
