package com.smartphonex.core

import kotlinx.serialization.json.*

/** App versions like 0.7.0, 0.7.0-dev or v1.2: comparison, and reading what a release feed says is latest. */
object Versions {
    /** Numbers, then a pre-release tag. 0.7.0-dev comes before 0.7.0. Null if it isn't a version. */
    private fun parse(v: String): Pair<List<Int>, String?>? {
        val t = v.trim().removePrefix("v").removePrefix("V")
        val main = t.substringBefore('-')
        val pre = t.substringAfter('-', "").ifEmpty { null }
        val nums = main.split('.').map { it.toIntOrNull() ?: return null }
        if (nums.isEmpty() || nums.size > 4) return null
        return nums to pre
    }

    /** Negative if [a] is older than [b], 0 if the same, positive if newer. Null if either isn't a version. */
    fun compare(a: String, b: String): Int? {
        val (an, ap) = parse(a) ?: return null
        val (bn, bp) = parse(b) ?: return null
        for (i in 0 until maxOf(an.size, bn.size)) {
            val d = an.getOrElse(i) { 0 } - bn.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return when {
            ap == bp -> 0
            ap == null -> 1
            bp == null -> -1
            else -> ap.compareTo(bp)
        }
    }

    data class Release(val version: String, val page: String, val apk: String?)

    /**
     * Reads a release feed: GitHub's latest-release JSON (tag_name, html_url, assets), or a plain {version, url}.
     * Only https links are taken, so a feed can't point people at an unencrypted download.
     */
    fun parseFeed(text: String): Release? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        val version = (o["tag_name"] ?: o["version"])?.jsonPrimitive?.contentOrNull ?: return null
        if (parse(version) == null) return null
        if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        val page = (o["html_url"] ?: o["url"])?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") } ?: return null
        val apk = o["assets"]?.jsonArray?.mapNotNull { it.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull }
            ?.firstOrNull { it.startsWith("https://") && it.endsWith(".apk") }
        Release(version.removePrefix("v"), page, apk)
    }.getOrNull()
}
