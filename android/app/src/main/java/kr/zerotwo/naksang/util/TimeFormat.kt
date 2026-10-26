package kr.zerotwo.naksang.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object IsoTime {
    fun now(): String = DateTimeFormatter.ISO_INSTANT.format(
        Instant.now().truncatedTo(ChronoUnit.SECONDS),
    )
}

object KoreanTime {
    fun watchingCaption(startedAtMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = Instant.ofEpochMilli(startedAtMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val day = when (start.toLocalDate()) {
            now.toLocalDate() -> "오늘"
            now.toLocalDate().minusDays(1) -> "어제"
            else -> "${start.monthValue}월 ${start.dayOfMonth}일"
        }
        val hour = start.hour
        val ampm = if (hour < 12) "오전" else "오후"
        val h12 = hour % 12
        val hourLabel = if (h12 == 0) 12 else h12
        val minute = if (start.minute == 0) "" else " ${start.minute}분"
        return "$day $ampm ${hourLabel}시${minute}부터 계속"
    }

    fun durationLabel(startedAtMs: Long, nowMs: Long): String {
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        val hours = elapsed / 3_600_000.0
        return if (hours >= 1.0) {
            String.format(Locale.US, "%.1fh", hours)
        } else {
            "${elapsed / 60_000}분"
        }
    }

    /** "오늘 오후 3:42" */
    fun sentCaption(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val whenAt = Instant.ofEpochMilli(epochMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val day = when (whenAt.toLocalDate()) {
            now.toLocalDate() -> "오늘"
            now.toLocalDate().minusDays(1) -> "어제"
            else -> "${whenAt.monthValue}월 ${whenAt.dayOfMonth}일"
        }
        val hour = whenAt.hour
        val ampm = if (hour < 12) "오전" else "오후"
        val h12 = hour % 12
        val hourLabel = if (h12 == 0) 12 else h12
        val minute = whenAt.minute.toString().padStart(2, '0')
        return "$day $ampm $hourLabel:$minute"
    }

    fun heartbeatCaption(lastAtMs: Long?, ok: Boolean?, nowMs: Long, demo: Boolean): String {
        if (demo) return "오프라인 데모"
        if (ok == null || lastAtMs == null) return "확인 중"
        if (!ok) return "다시 시도 중"
        val sec = ((nowMs - lastAtMs) / 1000).coerceAtLeast(0)
        return when {
            sec < 15 -> "방금 확인"
            sec < 60 -> "${sec}초 전"
            else -> "${sec / 60}분 전"
        }
    }
}

object ServerUrl {
    /** Origin only, no path and no trailing slash. Null when it cannot be parsed. */
    fun normalize(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "https://$text"
        }
        return try {
            val uri = java.net.URI(text)
            val host = uri.host ?: return null
            if (host.isBlank()) return null
            val scheme = uri.scheme ?: return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "$scheme://$host$port"
        } catch (_: Exception) {
            null
        }
    }
}
