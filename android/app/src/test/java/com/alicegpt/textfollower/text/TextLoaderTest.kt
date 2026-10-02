package com.alicegpt.textfollower.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextLoaderTest {

    private val cp1251 = Charset.forName("windows-1251")
    private val phrase = "Синяя лодка плывёт по реке"

    @Test
    fun utf8IsDetected() {
        assertEquals(phrase, TextLoader.load(phrase.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun utf8WithBomIsDetected() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + phrase.toByteArray(Charsets.UTF_8)
        assertEquals(phrase, TextLoader.load(bytes))
    }

    @Test
    fun windows1251FallsBack() {
        assertEquals(phrase, TextLoader.load(phrase.toByteArray(cp1251)))
    }

    @Test
    fun htmlTagsEntitiesAndScriptsAreStripped() {
        val html = """
            <html><head><title>Заголовок страницы</title><style>p{color:red}</style></head>
            <body><script>var x = "не текст";</script>
            <p>Первый&nbsp;абзац &mdash; тест &amp; проверка.</p><p>Второй<br>абзац &#1105;&#x436;ик</p><!-- комментарий --></body></html>
        """.trimIndent()
        val text = TextLoader.load(html.toByteArray(Charsets.UTF_8))
        assertEquals("Первый абзац — тест & проверка.\nВторой\nабзац ёжик", text)
    }

    @Test
    fun htmlCharsetComesFromMetaTag() {
        val html = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1251\"></head>" +
            "<body>$phrase</body></html>"
        assertEquals(phrase, TextLoader.load(html.toByteArray(cp1251)))
    }

    @Test
    fun preformattedTextKeepsLineBreaks() {
        val html = "<html><body><pre>\nпервая строка\n[5] вторая строка\n</pre></body></html>"
        assertEquals("первая строка\n[5] вторая строка", TextLoader.load(html.toByteArray()))
    }

    @Test
    fun libRuPageKeepsOnlyTheWork() {
        val html = "<html><body>меню сайта<!--------- Собственно произведение --------->" +
            "<pre>\nПЕСНЬ ПЕРВАЯ\nпервая строка\n<!-------- Блок описания произведения (слева внизу) -------->" +
            "<center>Оценка: шедевр</center></pre></body></html>"
        val text = TextLoader.load(html.toByteArray())
        assertEquals("ПЕСНЬ ПЕРВАЯ\nпервая строка", text)
    }

    @Test
    fun mhtWithBinaryPartAndMetaCharset() {
        val html = "<html><head><meta charset=\"windows-1251\"></head><body><p>$phrase</p><p>Вторая строка</p></body></html>"
        val head = "From: <Saved by Blink>\r\nSubject: test\r\nMIME-Version: 1.0\r\n" +
            "Content-Type: multipart/related;\r\n\ttype=\"text/html\";\r\n\tboundary=\"----Boundary--abc----\"\r\n\r\n\r\n"
        val partHead = "------Boundary--abc----\r\nContent-Type: text/html\r\nContent-Transfer-Encoding: binary\r\n\r\n"
        val tail = "\r\n------Boundary--abc----\r\nContent-Type: image/gif\r\nContent-Transfer-Encoding: binary\r\n\r\nGIF89a\r\n------Boundary--abc------\r\n"
        val bytes = head.toByteArray(Charsets.ISO_8859_1) + partHead.toByteArray(Charsets.ISO_8859_1) +
            html.toByteArray(cp1251) + tail.toByteArray(Charsets.ISO_8859_1)
        assertEquals("$phrase\nВторая строка", TextLoader.load(bytes))
    }

    @Test
    fun mhtWithQuotedPrintableUtf8() {
        val qp = phrase.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val v = b.toInt() and 0xFF
            if (v < 128 && v != '='.code) v.toChar().toString() else "=%02X".format(v)
        }
        val mht = "MIME-Version: 1.0\nContent-Type: multipart/related; boundary=\"B\"\n\n--B\n" +
            "Content-Type: text/html; charset=\"utf-8\"\nContent-Transfer-Encoding: quoted-printable\n\n" +
            "<html><body><p>$qp</p></body></html>\n--B--\n"
        assertEquals(phrase, TextLoader.load(mht.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun mhtWithBase64Part() {
        val html = "<html><body><p>$phrase</p></body></html>"
        val b64 = java.util.Base64.getMimeEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
        val mht = "MIME-Version: 1.0\nContent-Type: multipart/related; boundary=\"B\"\n\n--B\n" +
            "Content-Type: text/html; charset=utf-8\nContent-Transfer-Encoding: base64\n\n$b64\n--B--\n"
        assertEquals(phrase, TextLoader.load(mht.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun plainTextIsNotMistakenForMht() {
        assertFalse(TextLoader.looksLikeMht("Subject: тема\nпросто текст".toByteArray()))
        assertTrue(TextLoader.looksLikeHtml("<!DOCTYPE html><html>".toByteArray()))
    }

    @Test
    fun blankLinesAreCollapsed() {
        assertEquals("раз\n\nдва\nтри", TextLoader.normalizeText("раз\r\n\r\n\r\n\r\n  два  \nтри\n\n"))
    }
}
