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
    private fun newDateFormat() = SimpleDateFormat(DATE_PATTERN, Locale.US)

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
                // **회전이지 삭제가 아니다** (2026.08.22). 종전에는 `logFile.delete()`라
                // 상한에 닿는 순간 기록이 통째로 사라졌고, 목록이 *"총 137건"*에서 **1건**으로
                // 떨어졌다 — 간헐적 결함을 좇던 사용자가 쌓아 온 것이 예고도 흔적도 없이
                // 없어지는 자리다(형제인 휴지통은 넘치는 만큼만 덜어낸다).
                // 남길 것을 고르는 판정은 [LogRotation]이 든다(순수라 시험이 잡는다).
                if (logFile.exists() && logFile.length() > MAX_SIZE) {
                    val rotated = LogRotation.rotate(
                        logFile.readText(), MAX_SIZE / 2, ENTRY_SEPARATOR
                    )
                    logFile.writeText(rotated)
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
    /**
     * 로그의 시각 문자열 → epoch. **못 읽으면 0이 아니라 옛 로케일로 한 번 더 시도한다.**
     *
     * 이 저장소는 2026.08.21에 쓰기·읽기 서식을 `Locale.US`로 못박았다(R-70) — 종전에는
     * 기본 로케일이라 태국(불기)·일본(화력) 기기가 `2569-…`처럼 **다른 역법으로 적힌 파일**을
     * 이미 남겨 두었다. 못박기만 하고 끝내면 그 기기의 **기존 항목이 전부 1970년으로 밀려**
     * 목록 정렬과 "마지막 오류 시각"이 망가진다.
     *
     * 그래서 새 서식으로 먼저 읽고, 실패하면 **그 기기의 기본 로케일로** 한 번 더 읽는다.
     * 옛 파일은 그 로케일로 쓰였으므로 그때 읽힌다. 둘 다 실패해야 0이다.
     */
    private fun parseTimestamp(timeStr: String): Long {
        runCatching { newDateFormat().parse(timeStr)?.time }.getOrNull()?.let { return it }
        runCatching {
            // platform-parity-ok: **옛 파일을 읽으려면 옛 로케일이어야 한다** — 이 줄의 목적이
            // 기본 로케일로 쓰인 기존 로그를 되살리는 것이다. 쓰기는 위 `newDateFormat()`이
            // `Locale.US`로 하고, 이 폴백은 **읽기 전용**이라 새 값을 만들지 않는다.
            SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).parse(timeStr)?.time
        }.getOrNull()?.let { return it }
        return 0L
    }

    private fun parseErrorHeader(header: String, stackTrace: String): LogEntry? {
        val regex = Regex("""\[(.+?)]\[(.+?)]\[(.+?)] (.+)""")
        val match = regex.find(header) ?: return null
        val (timeStr, level, tag, message) = match.destructured
        val timestamp = parseTimestamp(timeStr)
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
