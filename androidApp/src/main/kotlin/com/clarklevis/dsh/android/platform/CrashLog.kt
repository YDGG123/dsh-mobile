package com.clarklevis.dsh.android.platform

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常落盘（**取证设施**，2026-11-02 用户报「挂着不动会闪退」后加）：
 * 没有它，用户侧闪退永远只能靠电脑抓 logcat——而 adb 常常不可用。
 *
 * 落盘内容（AndroidTester 2026-11-02 要求的五项 + 版本）：
 *  时间 ｜ 线程名 ｜ 前台/后台 ｜ 版本名 + versionCode ｜ 完整堆栈
 * 路径：`noBackupFilesDir/last-crash.txt`（可重建、不进备份，不被 auto-backup 带出敏感信息）。
 *
 * 三条纪律：
 * - **只写最近一次**（覆盖），上限 64KB，避免无限增长；
 * - 记完**必须转交原 handler**，behavior 与系统默认一致（不能吞掉崩溃）；
 * - 自身任何异常都静默（取证设施绝不能变成新的崩溃源）。
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 64 * 1024

    @Volatile private var installed = false

    fun install(context: Context, isForeground: () -> Boolean) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable, isForeground()) }
                .onFailure { Log.w(TAG, "crash log write failed") }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /** 最近一次崩溃文本；没有则 null。 */
    fun read(context: Context): String? = runCatching {
        file(context).takeIf { it.exists() }?.readText()?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable, foreground: Boolean) {
        val (versionName, versionCode) = version(context)
        val stack = StringWriter().also { writer -> PrintWriter(writer).use { throwable.printStackTrace(it) } }.toString()
        val header = buildString {
            append("时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())).append('\n')
            append("线程: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
            append("前后台: ").append(if (foreground) "前台" else "后台").append('\n')
            append("版本: ").append(versionName).append(" (").append(versionCode).append(")\n")
            append("异常: ").append(throwable.javaClass.name).append(": ").append(throwable.message ?: "").append('\n')
            append("—".repeat(24)).append('\n')
        }
        val text = (header + stack).take(MAX_CHARS)
        file(context).writeText(text)
    }

    private fun version(context: Context): Pair<String, Long> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        info.versionName.orEmpty() to code
    }.getOrElse { "?" to 0L }
}
