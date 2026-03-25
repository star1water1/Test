package com.novelcharacter.app.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 전역 오류 로거.
 * 런타임 오류를 error_log.txt에 기록하고,
 * 기존 crash_log.txt와 통합하여 설정 화면에서 조회할 수 있게 한다.
 */
object AppLogger {

    private lateinit var logFile: File
    private lateinit var crashFile: File
    private const val MAX_SIZE = 512 * 1024L // 512KB
    private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /** SimpleDateFormat은 스레드 안전하지 않으므로 호출 시마다 생성 */
    private fun newDateFormat() = SimpleDateFormat(DATE_PATTERN, Locale.getDefault())

    // 엔트리 구분자 (파싱용)
    private const val ENTRY_SEPARATOR = "──────────"

    fun init(filesDir: File) {
        logFile = File(filesDir, "error_log.txt")
        crashFile = File(filesDir, "crash_log.txt")
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        writeEntry("ERROR", tag, message, throwable)
    }

    fun warn(tag: String, message: String) {
        writeEntry("WARN", tag, message, null)
    }

    private fun writeEntry(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!::logFile.isInitialized) return
        try {
            synchronized(this) {
                if (logFile.exists() && logFile.length() > MAX_SIZE) {
                    logFile.delete()
                }
                logFile.appendText(buildString {
                    appendLine(ENTRY_SEPARATOR)
                    appendLine("[${newDateFormat().format(Date())}][$level][$tag] $message")
                    if (throwable != null) {
                        appendLine(throwable.stackTraceToString())
                    }
                })
            }
        } catch (_: Exception) {
            // 로그 저장 실패 시 무시
        }
    }

    /**
     * error_log.txt + crash_log.txt를 병합하여 시간역순 반환.
     * 최대 [limit]건까지 반환.
     */
    fun readAllLogs(limit: Int = 200): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        if (::logFile.isInitialized && logFile.exists()) {
            entries.addAll(parseErrorLog(logFile))
        }
        if (::crashFile.isInitialized && crashFile.exists()) {
            entries.addAll(parseCrashLog(crashFile))
        }
        return entries.sortedByDescending { it.timestamp }.take(limit)
    }

    fun getErrorCount(): Int {
        var count = 0
        if (::logFile.isInitialized && logFile.exists()) {
            count += logFile.useLines { lines -> lines.count { it.startsWith(ENTRY_SEPARATOR) } }
        }
        if (::crashFile.isInitialized && crashFile.exists()) {
            count += crashFile.useLines { lines -> lines.count { it.startsWith("=== Crash at") } }
        }
        return count
    }

    fun getLastErrorTime(): Long? {
        val entries = readAllLogs(1)
        return entries.firstOrNull()?.timestamp
    }

    fun clearErrorLog() {
        if (::logFile.isInitialized && logFile.exists()) {
            logFile.delete()
        }
    }

    fun clearAllLogs() {
        clearErrorLog()
        if (::crashFile.isInitialized && crashFile.exists()) {
            crashFile.delete()
        }
    }

    fun getLogFiles(): List<File> {
        val files = mutableListOf<File>()
        if (::logFile.isInitialized && logFile.exists() && logFile.length() > 0) {
            files.add(logFile)
        }
        if (::crashFile.isInitialized && crashFile.exists() && crashFile.length() > 0) {
            files.add(crashFile)
        }
        return files
    }

    // ── error_log.txt 파싱 ──

    private fun parseErrorLog(file: File): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        try {
            val content = file.readText()
            val blocks = content.split(ENTRY_SEPARATOR).filter { it.isNotBlank() }
            for (block in blocks) {
                val lines = block.trim().lines()
                if (lines.isEmpty()) continue
                val headerLine = lines[0]
                val entry = parseErrorHeader(headerLine, lines.drop(1).joinToString("\n").trim())
                if (entry != null) entries.add(entry)
            }
        } catch (_: Exception) {
            // 파싱 실패 시 무시
        }
        return entries
    }

    /**
     * 헤더 형식: [2026-03-25 14:30:00][ERROR][Tag] message
     */
    private fun parseErrorHeader(header: String, stackTrace: String): LogEntry? {
        val regex = Regex("""\[(.+?)]\[(.+?)]\[(.+?)] (.+)""")
        val match = regex.find(header) ?: return null
        val (timeStr, level, tag, message) = match.destructured
        val timestamp = try {
            newDateFormat().parse(timeStr)?.time ?: 0L
        } catch (_: Exception) { 0L }
        return LogEntry(
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message,
            stackTrace = stackTrace.ifBlank { null }
        )
    }

    // ── crash_log.txt 파싱 ──
    // 기존 형식: === Crash at 2026-03-25 14:30:00 ===\nThread: main\nstacktrace...

    private fun parseCrashLog(file: File): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        try {
            val content = file.readText()
            val blocks = content.split("=== Crash at ").filter { it.isNotBlank() }
            for (block in blocks) {
                val lines = block.trim().lines()
                if (lines.isEmpty()) continue
                // 첫 줄: "2026-03-25 14:30:00 ==="
                val timeStr = lines[0].removeSuffix("===").trim()
                val timestamp = try {
                    newDateFormat().parse(timeStr)?.time ?: 0L
                } catch (_: Exception) { 0L }
                val body = lines.drop(1).joinToString("\n").trim()
                val threadName = if (body.startsWith("Thread: ")) {
                    body.lines().first().removePrefix("Thread: ")
                } else ""
                entries.add(LogEntry(
                    timestamp = timestamp,
                    level = "CRASH",
                    tag = "Thread: $threadName",
                    message = "앱 비정상 종료",
                    stackTrace = body
                ))
            }
        } catch (_: Exception) {
            // 파싱 실패 시 무시
        }
        return entries
    }
}

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val stackTrace: String?
)
