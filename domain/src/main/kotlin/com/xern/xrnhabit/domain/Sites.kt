package com.xern.xrnhabit.domain

/** Website targets (I18): normalizing what a user types or a browser shows, and matching. */
object Sites {

    /**
     * "https://www.YouTube.com/watch?v=x" -> "youtube.com". Returns null for anything that isn't
     * a plausible host — a search query typed into the address bar, a bare word, an empty bar.
     */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        s = s.substringAfter("://", s)
        s = s.takeWhile { it != '/' && it != '?' && it != '#' }
        s = s.substringAfterLast('@').substringBefore(':').trimEnd('.')
        s = s.removePrefix("www.")
        if (s.length > 253) return null
        val labels = s.split('.')
        if (labels.size < 2) return null
        if (labels.any { !validLabel(it) }) return null
        val tld = labels.last()
        val isIpv4 = labels.size == 4 && labels.all { l -> l.all(Char::isDigit) && l.toInt() <= 255 }
        val tldOk = (tld.length >= 2 && tld.all { it in 'a'..'z' }) || tld.startsWith("xn--")
        return if (isIpv4 || tldOk) s else null
    }

    /** `youtube.com` covers `youtube.com` and `m.youtube.com`, but not `notyoutube.com`. */
    fun matches(host: String, target: String): Boolean =
        host == target || host.endsWith(".$target")

    private fun validLabel(label: String): Boolean =
        label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}
