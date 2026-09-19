package com.screenshare

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 无 adb 环境下的「显化报错」工具。
 *
 * - 所有日志（含未捕获异常堆栈）既留在内存，也追加写入应用私有文件 files/app.log；
 * - [installCrashHandler] 捕获未处理异常，先把堆栈落盘，再交回系统默认处理器（进程行为不变）；
 * - MainActivity 启动时读取展示，可一键复制到剪贴板，直接粘贴给别人排查。
 *
 * 所有方法都不会抛异常：日志本身绝不能再导致崩溃。
 */
object AppLog {
    private const val FILE_NAME = "app.log"
    private const val MAX_CHARS = 64_000

    private val lock = Any()
    private val buffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    /** 已记录的未捕获异常次数（用于启动时提示"上次运行崩溃"） */
    @Volatile
    var crashCount: Int = 0
        private set

    /** 上次进程退出是否为异常退出（原生崩溃/ANR/被杀等），由 [logLastExitReason] 判定 */
    @Volatile
    var lastExitAbnormal: Boolean = false
        private set

    fun init(context: Context) {
        val dir = context.applicationContext.filesDir
        synchronized(lock) {
            if (logFile == null) {
                logFile = File(dir, FILE_NAME)
            }
            try {
                val f = logFile
                if (buffer.isEmpty() && f != null && f.exists()) {
                    buffer.append(f.readText())
                }
                crashCount = countCrash(buffer)
            } catch (e: Throwable) {
                Log.w("AppLog", "init failed", e)
            }
        }
    }

    fun log(
        tag: String,
        message: String,
        t: Throwable? = null,
    ) {
        val line =
            buildString {
                append(timeFormat.format(Date()))
                append(' ')
                append(tag)
                append(": ")
                append(message)
                if (t != null) {
                    append('\n')
                    append(Log.getStackTraceString(t))
                }
                append('\n')
            }
        synchronized(lock) {
            try {
                buffer.append(line)
                if (buffer.length > MAX_CHARS) buffer.delete(0, buffer.length - MAX_CHARS)
                logFile?.appendText(line)
            } catch (e: Throwable) {
                Log.w("AppLog", "write failed", e)
            }
        }
        if (t != null) Log.e(tag, message, t) else Log.i(tag, message)
    }

    fun dump(): String = synchronized(lock) { buffer.toString().ifBlank { "（暂无日志）" } }

    fun clear() {
        synchronized(lock) {
            try {
                buffer.setLength(0)
                crashCount = 0
                logFile?.writeText("")
            } catch (e: Throwable) {
                Log.w("AppLog", "clear failed", e)
            }
        }
    }

    /** 安装未捕获异常处理器：记录堆栈后仍交回原处理器，进程照样退出，但错误被留存下来 */
    fun installCrashHandler(context: Context) {
        init(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            log("CRASH", "未捕获异常（线程 ${thread.name}）", e)
            synchronized(lock) { crashCount++ }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * 把「上次进程是怎么死的」写进日志（API 30+）。
     *
     * 原生崩溃（SIGSEGV/abort）不会经过 [installCrashHandler]：进程被信号直接杀掉，
     * 没有任何 Java 堆栈，日志里表现为"某个打点之后再无输出"。这类静默死亡只能靠系统侧的
     * ApplicationExitInfo 还原：reason/description 能区分是 native crash、ANR、低内存还是
     * 被系统策略杀掉；API 31+ 还能直接读到 tombstone 文本（等价 adb 的 /data/tombstones）。
     */
    fun logLastExitReason(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val info =
                am
                    .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                    .firstOrNull()
            if (info == null) {
                log("EXIT", "未查询到上次进程退出记录")
                return
            }
            lastExitAbnormal = isAbnormalExit(info.reason)
            val sb = StringBuilder()
            sb
                .append("上次进程退出：pid=")
                .append(info.pid)
                .append(" reason=")
                .append(reasonName(info.reason))
                .append("(")
                .append(info.reason)
                .append(") 状态=")
                .append(if (lastExitAbnormal) "异常" else "正常")
                .append(" 时间=")
                .append(timeFormat.format(Date(info.timestamp)))
            info.description?.let { sb.append("\n描述：").append(it) }
            log("EXIT", sb.toString())
            if (lastExitAbnormal) dumpTrace(info)
        } catch (e: Throwable) {
            log("EXIT", "读取进程退出原因失败", e)
        }
    }

    /**
     * 读系统的 tombstone / ANR trace。
     *
     * Android 12+ 的 tombstone 是 protobuf 二进制（不是纯文本），直接 readText() 只能得到
     * 一堆乱码——所以这里先做一次通用 protobuf 遍历，把可读字段按 "字段路径=值" 展开
     * （能看到线程名、崩溃信号、backtrace 里的 function_name / 库名），
     * 万一展开不出东西，再退化成 strings 式的可打印串提取。
     */
    private fun dumpTrace(info: ApplicationExitInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val stream = info.traceInputStream
            if (stream == null) {
                log("EXIT", "系统未保留 tombstone/ANR trace")
                return
            }
            val bytes = stream.use { it.readBytes() }
            val sb = StringBuilder()
            walkProto(bytes, sb, "", 0)
            val text =
                if (sb.length < 64) {
                    "（protobuf 未解析出字段，退化为可打印串）\n" + extractPrintableStrings(bytes)
                } else {
                    sb.toString()
                }
            val max = 20_000
            val clipped =
                if (text.length > max) {
                    text.substring(0, max) + "\n…（已截断，原文 ${text.length} 字符）"
                } else {
                    text
                }
            log("EXIT", "系统 trace（tombstone/ANR，${bytes.size} 字节）：\n$clipped")
        } catch (e: Throwable) {
            log("EXIT", "读取 trace 失败", e)
        }
    }

    /** 通用 protobuf 遍历：长度分隔字段若基本可打印就当字符串输出，否则当作子消息递归 */
    private fun walkProto(
        data: ByteArray,
        out: StringBuilder,
        prefix: String,
        depth: Int,
    ) {
        if (depth > 6 || out.length > 24_000) return
        var i = 0
        while (i < data.size) {
            val key = readVarint(data, i) ?: return
            i = key.second
            val field = (key.first shr 3).toInt()
            when ((key.first.toInt()) and 7) {
                0 -> {
                    val v = readVarint(data, i) ?: return
                    i = v.second
                    out.append(prefix).append(field).append('=').append(v.first).append('\n')
                }

                2 -> {
                    val len = readVarint(data, i) ?: return
                    i = len.second
                    val size = len.first.toInt()
                    if (size < 0 || i + size > data.size) return
                    val chunk = data.copyOfRange(i, i + size)
                    i += size
                    if (isPrintableChunk(chunk)) {
                        out
                            .append(prefix)
                            .append(field)
                            .append("=\"")
                            .append(sanitize(chunk))
                            .append("\"\n")
                    } else {
                        walkProto(chunk, out, "$prefix$field.", depth + 1)
                    }
                }

                5 -> i += 4
                1 -> i += 8
                else -> return
            }
        }
    }

    private fun readVarint(
        data: ByteArray,
        from: Int,
    ): Pair<Long, Int>? {
        var shift = 0
        var result = 0L
        var i = from
        while (i < data.size && shift < 64) {
            val b = data[i].toInt() and 0xFF
            i++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result to i
            shift += 7
        }
        return null
    }

    private fun isPrintableChunk(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        var ok = 0
        for (c in b) {
            val v = c.toInt() and 0xFF
            if (v == 0x0A || v == 0x09 || v in 0x20..0x7E) ok++
        }
        return ok * 10 >= b.size * 9
    }

    private fun sanitize(b: ByteArray): String {
        val sb = StringBuilder(b.size)
        for (c in b) {
            val v = c.toInt() and 0xFF
            sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
        }
        return sb.toString()
    }

    /** strings 式兜底：按出现顺序提取长度 >= 5 的可打印串并去重 */
    private fun extractPrintableStrings(data: ByteArray): String {
        val out = StringBuilder()
        val seen = HashSet<String>()
        var cur = StringBuilder()
        var count = 0
        fun flush() {
            if (cur.length >= 5 && seen.add(cur.toString())) {
                out.append(cur).append('\n')
                if (++count >= 150) return
            }
            cur.setLength(0)
        }
        for (c in data) {
            val v = c.toInt() and 0xFF
            if (v in 0x20..0x7E) {
                cur.append(v.toChar())
            } else {
                flush()
                if (count >= 150) break
            }
        }
        flush()
        return out.toString()
    }

    /** 记录一次设备环境：定位"只在特定镜像/ABI/页大小上复现"的问题时很关键 */
    fun logDeviceEnv() {
        val pageSize =
            try {
                android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            } catch (e: Throwable) {
                -1L
            }
        log(
            "ENV",
            "设备=${Build.DEVICE}/${Build.MODEL} SDK=${Build.VERSION.SDK_INT} " +
                "ABI=${Build.SUPPORTED_ABIS.joinToString(",")} 页大小=$pageSize",
        )
    }

    private fun isAbnormalExit(reason: Int): Boolean =
        when (reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            -> true
            else -> false
        }

    private fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH(Java 异常)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE(原生崩溃)"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    when (reason) {
                        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
                        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
                        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
                        else -> "?"
                    }
                } else {
                    "?"
                }
        }

    private fun countCrash(text: CharSequence): Int =
        Regex("(?m)^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d CRASH:").findAll(text).count()
}
