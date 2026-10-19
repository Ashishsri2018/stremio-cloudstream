package com.cs3addon.StreamioLinkDev

import com.lagradost.api.Log

/**
 * Verbose diagnostics for the dev plugin. Everything is logged under [TAG] so a single
 * `logcat -s StreamioLinkDev:V` capture contains the full story:
 *
 *  - which addons were queried and what their manifests advertise
 *  - raw stream counts, cache/back-off decisions and why a stream was dropped
 *  - every emitted link, with its type/quality/host
 *  - every subtitle URL shape tried, the HTTP result and the parsed language count
 *
 * Keep this file free of protocol logic so it cannot affect playback.
 */
internal object StremioDebug {
    const val TAG = "StreamioLinkDev"

    private const val MAX_PREVIEW = 500

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String) {
        Log.e(TAG, message)
    }

    /** Milliseconds elapsed since [startedAt] (a `System.currentTimeMillis()` value). */
    fun ms(startedAt: Long): Long = System.currentTimeMillis() - startedAt

    /** Host part of a URL, for compact log lines. */
    fun host(url: String?): String {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return "-"
        return value.substringAfter("://", value)
            .substringBefore('/')
            .substringBefore('?')
            .ifBlank { "-" }
    }

    /** Collapses whitespace and truncates to [max] characters. */
    fun clip(value: String?, max: Int = MAX_PREVIEW): String {
        if (value == null) return "null"
        val singleLine = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= max) singleLine
        else singleLine.take(max) + "...(${singleLine.length})"
    }

    /** Length plus a short prefix, enough to tell JSON from an HTML error page. */
    fun preview(body: String?): String {
        if (body == null) return "null"
        if (body.isBlank()) return "blank(${body.length})"
        return "len=${body.length} first=${clip(body, 180)}"
    }

    /** Compact rendering of a collection, capped at [max] entries. */
    fun join(values: Iterable<String>?, max: Int = 12): String {
        val list = values?.toList().orEmpty()
        if (list.isEmpty()) return "[]"
        val head = list.take(max).joinToString(",")
        return if (list.size > max) "[$head,+${list.size - max}]" else "[$head]"
    }
}
