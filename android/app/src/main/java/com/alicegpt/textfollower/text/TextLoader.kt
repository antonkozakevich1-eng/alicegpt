package com.alicegpt.textfollower.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Превращает содержимое файла (.txt, .html, .mht) в обычный текст.
 * Кодировка определяется автоматически: BOM, объявление в HTML, иначе UTF-8 с откатом на windows-1251.
 */
object TextLoader {

    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    private val LATIN1: Charset = Charsets.ISO_8859_1
    private val BOM = Char(0xFEFF).toString()

    fun load(bytes: ByteArray): String {
        val text = when {
            looksLikeMht(bytes) -> mhtToText(bytes)
            looksLikeHtml(bytes) -> htmlToText(decodeHtml(bytes, null))
            else -> decode(bytes, null)
        }
        return normalizeText(text)
    }

    // ---------- кодировки ----------

    /** BOM → [declared] → строгий UTF-8 → windows-1251. */
    fun decode(bytes: ByteArray, declared: String?): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val cs = declared?.let { charsetOrNull(it) }
        if (cs != null) return String(bytes, cs)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, WINDOWS_1251)
        }
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name.trim().trim('"', '\''))
    } catch (e: Exception) {
        null
    }

    private fun decodeHtml(bytes: ByteArray, headerCharset: String?): String {
        val declared = headerCharset ?: sniffMetaCharset(bytes)
        return decode(bytes, declared)
    }

    private val META_CHARSET = Regex("charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-:.]+)", RegexOption.IGNORE_CASE)

    private fun sniffMetaCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), LATIN1)
        return META_CHARSET.find(head)?.groupValues?.get(1)
    }

    // ---------- определение формата ----------

    private fun headAsText(bytes: ByteArray, n: Int = 2048) = String(bytes, 0, minOf(bytes.size, n), LATIN1)

    fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = headAsText(bytes).lowercase()
        return head.contains("<html") || head.contains("<!doctype html") || head.contains("<body") || head.contains("<head")
    }

    fun looksLikeMht(bytes: ByteArray): Boolean {
        val head = headAsText(bytes, 4096).trimStart()
        val mimeStart = listOf("From:", "MIME-Version:", "Subject:", "Snapshot-Content-Location:", "Content-Type: multipart")
            .any { head.startsWith(it, ignoreCase = true) }
        return mimeStart && head.contains("Content-Type:", ignoreCase = true)
    }

    // ---------- MHT ----------

    private class MimePart(val headers: Map<String, String>, val body: ByteArray)

    private fun mhtToText(bytes: ByteArray): String {
        val part = findHtmlPart(parseMime(String(bytes, LATIN1)))
            ?: return decode(bytes, null)
        val contentType = part.headers["content-type"].orEmpty()
        val charset = META_CHARSET.find(contentType)?.groupValues?.get(1)
        val html = decodeHtml(part.body, charset)
        return if (contentType.contains("html", ignoreCase = true) || looksLikeHtml(part.body)) htmlToText(html) else html
    }

    /** Первая часть text/html (иначе text/plain), с учётом вложенных multipart. */
    private fun findHtmlPart(root: List<MimePart>): MimePart? {
        fun walk(parts: List<MimePart>, wanted: String): MimePart? {
            for (p in parts) {
                val type = p.headers["content-type"].orEmpty().lowercase()
                if (type.startsWith("multipart/")) {
                    val inner = parseMime(String(p.body, LATIN1))
                    walk(inner, wanted)?.let { return it }
                } else if (type.startsWith(wanted)) {
                    return p
                }
            }
            return null
        }
        return walk(root, "text/html") ?: walk(root, "text/plain")
    }

    /** Разбирает сообщение MIME в список листовых частей (для multipart) или одну часть. */
    private fun parseMime(message: String): List<MimePart> {
        val (headers, bodyStart) = parseHeaders(message, 0)
        val type = headers["content-type"].orEmpty()
        val boundary = Regex("boundary\\s*=\\s*\"?([^\";\\s]+)\"?", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
        if (!type.lowercase().startsWith("multipart/") || boundary == null) {
            return listOf(MimePart(headers, decodeTransfer(message.substring(bodyStart), headers["content-transfer-encoding"])))
        }
        val delimiter = "--$boundary"
        val parts = ArrayList<MimePart>()
        var pos = message.indexOf(delimiter, bodyStart)
        while (pos >= 0) {
            val afterDelimiter = pos + delimiter.length
            if (message.startsWith("--", afterDelimiter)) break // закрывающая граница
            val lineEnd = message.indexOf('\n', afterDelimiter)
            if (lineEnd < 0) break
            val partStart = lineEnd + 1
            val next = message.indexOf(delimiter, partStart)
            var partEnd = if (next < 0) message.length else next
            // перевод строки перед границей принадлежит границе
            if (partEnd > partStart && message[partEnd - 1] == '\n') partEnd--
            if (partEnd > partStart && message[partEnd - 1] == '\r') partEnd--
            val raw = message.substring(partStart, maxOf(partStart, partEnd))
            val (partHeaders, bStart) = parseHeaders(raw, 0)
            val encoding = partHeaders["content-transfer-encoding"]
            val ctype = partHeaders["content-type"].orEmpty().lowercase()
            parts.add(
                MimePart(
                    partHeaders,
                    if (ctype.startsWith("multipart/")) raw.substring(0).toByteArray(LATIN1)
                    else decodeTransfer(raw.substring(bStart), encoding),
                ),
            )
            pos = next
        }
        return parts
    }

    /** Заголовки (с учётом переносов строк) и смещение начала тела. */
    private fun parseHeaders(s: String, from: Int): Pair<Map<String, String>, Int> {
        val map = LinkedHashMap<String, String>()
        var i = from
        var lastKey: String? = null
        while (i < s.length) {
            var eol = s.indexOf('\n', i)
            if (eol < 0) eol = s.length
            val line = s.substring(i, eol).trimEnd('\r')
            i = minOf(eol + 1, s.length)
            if (line.isEmpty()) return map to i
            if ((line[0] == ' ' || line[0] == '\t') && lastKey != null) {
                map[lastKey] = map[lastKey] + " " + line.trim()
            } else {
                val colon = line.indexOf(':')
                if (colon <= 0) return map to from // это не заголовки
                lastKey = line.substring(0, colon).trim().lowercase()
                map[lastKey] = line.substring(colon + 1).trim()
            }
        }
        return map to s.length
    }

    private fun decodeTransfer(body: String, encoding: String?): ByteArray = when (encoding?.trim()?.lowercase()) {
        "quoted-printable" -> decodeQuotedPrintable(body)
        "base64" -> decodeBase64(body)
        else -> body.toByteArray(LATIN1)
    }

    private fun decodeQuotedPrintable(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=') {
                if (i + 1 < s.length && s[i + 1] == '\n') { i += 2; continue }
                if (i + 2 < s.length && s[i + 1] == '\r' && s[i + 2] == '\n') { i += 3; continue }
                val h = if (i + 2 < s.length) hex(s[i + 1]) shl 4 or hex(s[i + 2]) else -1
                if (h >= 0 && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0) {
                    out.write(h)
                    i += 3
                    continue
                }
            }
            out.write(c.code and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    /** Свой декодер base64: java.util.Base64 появился только в API 26, а minSdk здесь 24. */
    private fun decodeBase64(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> continue
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    // ---------- HTML → текст ----------

    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "ul", "ol", "tr", "table", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
        "pre", "blockquote", "dd", "dt", "dl", "section", "article", "header", "footer", "center", "form",
    )

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "hellip" to "…",
        "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "copy" to "©", "shy" to "", "middot" to "·", "sect" to "§", "deg" to "°",
    )

    /** Для страниц lib.ru берём только само произведение, без меню, оценок и счётчиков. */
    private fun mainRegion(html: String): String {
        val startMarker = Regex("<!--[^>]*Собственно произведение[^>]*-->").find(html)
        var from = startMarker?.range?.last?.plus(1) ?: run {
            val body = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(html)
            body?.range?.last?.plus(1) ?: 0
        }
        if (from < 0) from = 0
        var to = html.length
        val endMarker = Regex("<!--[^>]*Блок описания произведения \\(слева внизу\\)[^>]*-->").find(html, from)
        if (endMarker != null) {
            to = endMarker.range.first
        } else {
            val bodyEnd = html.lastIndexOf("</body", ignoreCase = true)
            if (bodyEnd > from) to = bodyEnd
        }
        return html.substring(from, to)
    }

    fun htmlToText(html: String): String {
        val src = mainRegion(html)
        val out = StringBuilder(src.length)
        var inPre = false
        var i = 0
        val n = src.length

        fun newline() {
            while (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
            if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append('\n')
        }

        while (i < n) {
            val c = src[i]
            when {
                c == '<' && src.startsWith("<!--", i) -> {
                    val end = src.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                c == '<' && i + 1 < n && (src[i + 1].isLetter() || src[i + 1] == '/' || src[i + 1] == '!') -> {
                    var end = src.indexOf('>', i + 1)
                    if (end < 0) end = n - 1
                    val tag = src.substring(i + 1, end)
                    val closing = tag.startsWith("/")
                    val name = tag.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase()
                    i = end + 1
                    when {
                        !closing && (name == "script" || name == "style" || name == "head") -> {
                            val close = Regex("</$name\\s*>", RegexOption.IGNORE_CASE).find(src, i)
                            i = close?.range?.last?.plus(1) ?: n
                        }
                        name == "pre" -> {
                            inPre = !closing
                            newline()
                        }
                        name in BLOCK_TAGS -> newline()
                        name == "td" || name == "th" -> if (out.isNotEmpty() && out.last() != '\n' && out.last() != ' ') out.append(' ')
                    }
                }
                c == '&' -> {
                    val semi = src.indexOf(';', i + 1)
                    val decoded = if (semi in (i + 2)..(i + 10)) decodeEntity(src.substring(i + 1, semi)) else null
                    if (decoded != null) {
                        appendText(out, decoded, inPre)
                        i = semi + 1
                    } else {
                        out.append('&')
                        i++
                    }
                }
                else -> {
                    appendText(out, c.toString(), inPre)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun appendText(out: StringBuilder, s: String, preformatted: Boolean) {
        for (ch in s) {
            if (preformatted) {
                out.append(if (ch == '\r') '\n' else ch)
            } else if (ch == '\n' || ch == '\r' || ch == '\t' || ch == ' ' || ch == '\u00A0') {
                if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
            } else {
                out.append(ch)
            }
        }
    }

    private fun decodeEntity(name: String): String? {
        if (name.startsWith("#")) {
            val code = try {
                if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) name.substring(2).toInt(16) else name.substring(1).toInt()
            } catch (e: NumberFormatException) {
                return null
            }
            return if (code in 1..0x10FFFF) String(Character.toChars(code)) else null
        }
        return NAMED_ENTITIES[name.lowercase()]
    }

    // ---------- общая чистка ----------

    /** Единые переводы строк, без лишних пробелов и без более чем одной пустой строки подряд. */
    fun normalizeText(text: String): String {
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ').split('\n')
        val sb = StringBuilder(text.length)
        var blank = 0
        for (raw in lines) {
            val line = raw.trim().replace(Regex("[ \\t]{2,}"), " ")
            if (line.isEmpty()) {
                blank++
                continue
            }
            if (sb.isNotEmpty()) sb.append("\n")
            if (blank > 0 && sb.isNotEmpty()) sb.append("\n")
            blank = 0
            sb.append(line)
        }
        return sb.toString()
    }
}
