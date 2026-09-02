package de.m13t.wrunconfig

import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

enum class Status { OK, SKIP, FAIL }

/** Outcome of the optional `.cat` <-> app-tree sync check (see [WrunconfigVerifier.checkCatalog]). */
enum class CatalogStatus { OK, MISSING, DRIFT, ERROR }

data class CatalogResult(
    val catalog: File?,
    val status: CatalogStatus,
    val message: String,
    val algorithm: String? = null,
    val members: Int = 0,
    val checked: Int = 0,
    /** Staged files (relative to the app root) whose hash is not in the catalog. */
    val uncovered: List<String> = emptyList(),
)

data class ConfigResult(
    val file: File,
    val status: Status,
    val message: String,
    val mainClass: String? = null,
    val linkDetail: String? = null,
    val dropped: List<String> = emptyList(),
    val dynamic: List<String> = emptyList(),
    val envCount: Int = 0,
    /** Non-null only when the catalog check is enabled. */
    val catalog: CatalogResult? = null,
)

/**
 * Pure (Gradle-free) verifier for MSIX-Power-Wrapper `.wrunconfig` files.
 *
 * It reads the `<Process><Arguments>` / `<WorkingDirectory>` block, resolves the
 * placeholders that can be resolved at build time (see [resolveToken]), extracts
 * the `-cp` / `-classpath` value and the main class, and checks that:
 *   - the working directory exists under [applicationRoot],
 *   - every *static* classpath entry exists, and
 *   - the main class is present on the classpath.
 *
 * Entries that still contain unresolved placeholders after resolution (registry
 * lookups without a default, `[EXENAME]`, environment folders, ...) cannot be
 * verified at build time; they are reported as `dynamic` and skipped rather than
 * failed, unless [failOnDynamic] is set.
 *
 * When [verifyCatalog] is set, each config is also checked against its sibling
 * Authenticode security catalog (`<name>.wrunconfig` -> `<name>.cat`): every file
 * staged under [applicationRoot] must have its hash listed in the catalog. A file
 * that is not covered means the payload changed since the catalog was signed and
 * the package must be re-signed. See [checkCatalog].
 */
class WrunconfigVerifier(
    applicationRoot: File,
    private val failOnDropped: Boolean = false,
    private val failOnDynamic: Boolean = false,
    private val verifyCatalog: Boolean = false,
) {
    private val appRoot: File = applicationRoot.absoluteFile.normalize()

    fun verify(file: File): ConfigResult {
        val base = verifyClasspath(file)
        if (!verifyCatalog) return base
        return base.copy(catalog = checkCatalog(file))
    }

    private fun verifyClasspath(file: File): ConfigResult {
        val doc: Document = try {
            val f = DocumentBuilderFactory.newInstance()
            runCatching {
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            f.newDocumentBuilder().parse(file)
        } catch (e: Exception) {
            return ConfigResult(file, Status.FAIL, "ParseError: ${e.message}")
        }

        val (cp, main) = cpAndMain(tokenize(resolve(text(doc, "Process", "Arguments"))))
        if (cp == null || main == null) return ConfigResult(file, Status.SKIP, "no classpath/main")

        // Working directory mirrors the reference validator: appRoot + basename of
        // the (raw) Windows WorkingDirectory path.
        val wd = File(appRoot, winBaseName(text(doc, "Process", "WorkingDirectory")))
        if (!wd.isDirectory) return ConfigResult(file, Status.FAIL, "workdir missing: $wd")

        val keep = ArrayList<String>()
        val dropped = ArrayList<String>()
        val dynamic = ArrayList<String>()
        for (raw in unquote(cp).split(";")) {
            if (raw.isEmpty()) continue
            if (raw.contains('[') || raw.contains(']')) { dynamic.add(raw); continue }
            val e = raw.replace('\\', File.separatorChar)
            if (e == "." || entryFile(wd, e).isDirectory || suffix(e) in CP_OK) keep.add(e) else dropped.add(e)
        }

        val missing = keep.filter { !entryFile(wd, it).exists() }
        if (missing.isNotEmpty()) {
            return ConfigResult(
                file, Status.FAIL,
                "${missing.size} cp entries missing, first: ${missing.first()}",
                mainClass = main, dropped = dropped, dynamic = dynamic,
            )
        }

        val (linked, detail) = linkage(keep, main, wd)
        return ConfigResult(
            file,
            if (linked) Status.OK else Status.FAIL,
            detail,
            mainClass = main,
            linkDetail = detail,
            dropped = dropped,
            dynamic = dynamic,
            envCount = countChildren(doc, "EnvironmentVariable"),
        )
    }

    /** Whether a result should fail the build, honouring the strictness flags. */
    fun isFailure(r: ConfigResult): Boolean =
        r.status == Status.FAIL ||
            (failOnDropped && r.dropped.isNotEmpty()) ||
            (failOnDynamic && r.dynamic.isNotEmpty()) ||
            (r.catalog != null && r.catalog.status != CatalogStatus.OK)

    // --- catalog (.cat) <-> app-tree sync check --------------------------------

    /**
     * Verifies that the sibling security catalog covers the whole staged app tree.
     *
     * The catalog is `<name>.cat` next to `<name>.wrunconfig` (so
     * `App.exe.wrunconfig` -> `App.exe.cat`). Every regular file under
     * [applicationRoot] (excluding `.cat` / `.wrunconfig` files themselves) must
     * have its hash present among the catalog's member digests; anything else is
     * `DRIFT` and needs a re-sign.
     *
     * Catalog parsing is dependency-free: the DER tree is walked and every member
     * `DigestInfo` (`SEQUENCE { AlgorithmIdentifier, OCTET STRING }` with a known
     * hash OID) plus every raw 20/32-byte subject identifier is collected. This
     * covers `signtool` / `makeappx` catalogs (the MSIX case). Catalogs whose
     * members carry only a hex reference tag are handled as a fallback.
     */
    fun checkCatalog(wrunconfig: File): CatalogResult {
        val base = wrunconfig.name.removeSuffix(".wrunconfig").ifEmpty { wrunconfig.name }
        val cat = File(wrunconfig.parentFile, "$base.cat")
        if (!cat.isFile) {
            return CatalogResult(cat, CatalogStatus.MISSING, "no sibling catalog: ${cat.name}")
        }
        if (!appRoot.isDirectory) {
            return CatalogResult(cat, CatalogStatus.ERROR, "app tree not found: $appRoot")
        }

        val digests: Map<String, Set<String>> = try {
            parseCatalogDigests(cat.readBytes())
        } catch (e: Exception) {
            return CatalogResult(cat, CatalogStatus.ERROR, "unreadable catalog: ${e.message}")
        }
        if (digests.isEmpty()) {
            return CatalogResult(cat, CatalogStatus.ERROR, "no member hashes found in ${cat.name}")
        }
        val algos = digests.keys.sorted()
        val members = digests.values.sumOf { it.size }

        val uncovered = ArrayList<String>()
        var checked = 0
        appRoot.walkTopDown().filter { it.isFile }.forEach { f ->
            val n = f.name.lowercase()
            if (n.endsWith(".cat") || n.endsWith(".wrunconfig")) return@forEach
            checked++
            val covered = algos.any { algo ->
                val h = runCatching { fileHash(f, algo) }.getOrNull()
                h != null && digests.getValue(algo).contains(h)
            }
            if (!covered) uncovered.add(f.relativeTo(appRoot).path.replace(File.separatorChar, '/'))
        }

        val algoTag = algos.joinToString("+")
        return if (uncovered.isEmpty()) {
            CatalogResult(
                cat, CatalogStatus.OK,
                "$checked staged file(s) covered ($algoTag, $members member(s))",
                algoTag, members, checked,
            )
        } else {
            CatalogResult(
                cat, CatalogStatus.DRIFT,
                "${uncovered.size}/$checked staged file(s) not in ${cat.name} - re-sign required",
                algoTag, members, checked, uncovered.sorted(),
            )
        }
    }

    private fun fileHash(f: File, jca: String): String {
        val md = MessageDigest.getInstance(jca)
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    // --- catalog DER parsing (minimal, dependency-free) ------------------------

    /** tag / content span into the backing DER array; children parsed lazily. */
    private inner class Tlv(val tag: Int, val cStart: Int, val cEnd: Int, val der: ByteArray) {
        val constructed get() = (tag and 0x20) != 0
        fun content(): ByteArray = der.copyOfRange(cStart, cEnd)
        val children: List<Tlv> by lazy {
            when {
                constructed -> runCatching { parseTlvs(der, cStart, cEnd) }.getOrDefault(emptyList())
                // OCTET STRING / BIT STRING may itself wrap DER (PKCS#7 eContent).
                tag == 0x04 && looksLikeDer(cStart) ->
                    runCatching { parseTlvs(der, cStart, cEnd) }.getOrDefault(emptyList())
                else -> emptyList()
            }
        }

        private fun looksLikeDer(at: Int): Boolean {
            if (cEnd - at < 2) return false
            val t = der[at].toInt() and 0xFF
            return t == 0x30 || t == 0x31
        }
    }

    private fun parseTlvs(b: ByteArray, from: Int, to: Int): List<Tlv> {
        val out = ArrayList<Tlv>()
        var i = from
        while (i < to) {
            if (i + 1 >= to) break
            val tag = b[i].toInt() and 0xFF
            var j = i + 1
            var len = b[j].toInt() and 0xFF
            j++
            if (len == 0x80) throw IllegalArgumentException("indefinite length")
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                if (n > 4 || j + n > to) throw IllegalArgumentException("bad length")
                len = 0
                repeat(n) { len = (len shl 8) or (b[j++].toInt() and 0xFF) }
            }
            val cStart = j
            val cEnd = j + len
            if (cEnd > to || cEnd < cStart) throw IllegalArgumentException("length overrun")
            out.add(Tlv(tag, cStart, cEnd, b))
            i = cEnd
        }
        return out
    }

    private fun parseCatalogDigests(der: ByteArray): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        collectDigests(parseTlvs(der, 0, der.size), out, 0)
        if (out.isEmpty()) collectHexTags(parseTlvs(der, 0, der.size), out, 0)
        return out
    }

    private fun collectDigests(nodes: List<Tlv>, out: MutableMap<String, MutableSet<String>>, depth: Int) {
        if (depth > 48) return
        for (n in nodes) {
            if (n.tag == 0x30) {
                val ch = n.children
                // DigestInfo ::= SEQUENCE { AlgorithmIdentifier, OCTET STRING }
                if (ch.size == 2 && ch[0].tag == 0x30 && ch[1].tag == 0x04) {
                    val oid = ch[0].children.firstOrNull()
                        ?.takeIf { it.tag == 0x06 }
                        ?.let { oidString(it.content()) }
                    val meta = oid?.let { HASH_OIDS[it] }
                    if (meta != null && ch[1].content().size == meta.second) {
                        out.getOrPut(meta.first) { HashSet() }.add(ch[1].content().toHex())
                    }
                }
                // TrustedSubject ::= SEQUENCE { subjectIdentifier OCTET STRING, attributes SET }
                if (ch.size >= 2 && ch[0].tag == 0x04 && ch[1].tag == 0x31) {
                    when (ch[0].content().size) {
                        20 -> out.getOrPut("SHA-1") { HashSet() }.add(ch[0].content().toHex())
                        32 -> out.getOrPut("SHA-256") { HashSet() }.add(ch[0].content().toHex())
                    }
                }
            }
            if (n.children.isNotEmpty()) collectDigests(n.children, out, depth + 1)
        }
    }

    private fun collectHexTags(nodes: List<Tlv>, out: MutableMap<String, MutableSet<String>>, depth: Int) {
        if (depth > 48) return
        for (n in nodes) {
            if (n.tag == 0x04) decodeHexTag(n.content())?.let { (algo, hex) ->
                out.getOrPut(algo) { HashSet() }.add(hex)
            }
            if (n.children.isNotEmpty()) collectHexTags(n.children, out, depth + 1)
        }
    }

    private fun decodeHexTag(content: ByteArray): Pair<String, String>? {
        fun asHash(s: String): Pair<String, String>? {
            val h = s.trim().lowercase()
            if (!h.matches(HEX)) return null
            return when (h.length) {
                40 -> "SHA-1" to h
                64 -> "SHA-256" to h
                else -> null
            }
        }
        asHash(String(content, Charsets.US_ASCII))?.let { return it }
        if (content.size % 2 == 0 && content.size >= 80) {
            asHash(String(content, Charsets.UTF_16LE))?.let { return it }
        }
        return null
    }

    private fun oidString(b: ByteArray): String {
        if (b.isEmpty()) return ""
        val sb = StringBuilder()
        val first = b[0].toInt() and 0xFF
        sb.append(first / 40).append('.').append(first % 40)
        var v = 0L
        for (k in 1 until b.size) {
            val x = b[k].toInt() and 0xFF
            v = (v shl 7) or (x and 0x7F).toLong()
            if (x and 0x80 == 0) { sb.append('.').append(v); v = 0 }
        }
        return sb.toString()
    }

    // --- placeholder resolution (build-time subset of the wrapper's grammar) ---

    private fun resolve(input: String?): String {
        if (input == null) return ""
        var s: String = input
        var guard = 0
        while (guard++ < 25) {
            val next = resolveOnce(s)
            if (next == s) break
            s = next
        }
        return s.replace('\\', File.separatorChar)
    }

    private fun resolveOnce(input: String): String {
        // The longer "[APPDIR]\.." is handled before the bare "[APPDIR]".
        var s = input.replace("[APPDIR]\\..", appRoot.toString())
        val out = StringBuilder()
        var i = 0
        var changed = false
        while (i < s.length) {
            val c = s[i]
            if (c == '[') {
                val end = matchBracket(s, i)
                if (end < 0) { out.append(c); i++; continue }
                val repl = resolveToken(s.substring(i + 1, end))
                if (repl != null) { out.append(repl); changed = true } else out.append(s, i, end + 1)
                i = end + 1
            } else { out.append(c); i++ }
        }
        return if (changed) out.toString() else s
    }

    private fun matchBracket(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private fun splitPipes(s: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '[' -> depth++
                ']' -> depth--
                '|' -> if (depth == 0) { parts.add(s.substring(start, i)); start = i + 1 }
            }
        }
        parts.add(s.substring(start))
        return parts
    }

    /** Returns the resolved value for a `[...]` token, or null to leave it as a dynamic entry. */
    private fun resolveToken(inner: String): String? {
        val p = splitPipes(inner)
        return when (p[0]) {
            "APPDIR" -> appRoot.toString()
            "ARGS", "RESOLVED_ARGS", "ARGSSELECTOR" -> " "          // no runtime args at build time
            "QUOTE" -> if (p.size > 1) p[1] else ""
            "CHANGEEXTENSION" -> if (p.size > 2) changeExt(p[1], p[2]) else null
            "RETRIVEFROMREGISTRY" -> if (p.size >= 5) p[4] else ""  // fall back to the registry default
            "ENV" -> if (p.size > 1) System.getenv(p[1]) else null
            else -> System.getenv(p[0])                            // EXENAME/SPECIALFOLDER/... stay dynamic when unset
        }
    }

    private fun changeExt(path: String, ext: String): String {
        val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        val dot = path.lastIndexOf('.')
        val e = if (ext.startsWith(".")) ext else ".$ext"
        return if (dot <= slash) path + e else path.substring(0, dot) + e
    }

    // --- argument parsing ---

    private fun tokenize(s: String): List<String> {
        val t = ArrayList<String>()
        val m = TOKEN.matcher(s)
        while (m.find()) t.add(m.group())
        return t
    }

    private fun cpAndMain(toks: List<String>): Pair<String?, String?> {
        var cp: String? = null
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            when {
                t in CP_OPTS -> { cp = if (i + 1 < toks.size) toks[i + 1] else null; i++ }
                t.startsWith("-") -> { /* skip other flags */ }
                else -> return cp to t
            }
            i++
        }
        return cp to null
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

    // --- linkage: is the main class present on the classpath? ---

    private fun linkage(entries: List<String>, main: String, wd: File): Pair<Boolean, String> {
        val rel = main.replace('.', '/') + ".class"
        var found = false
        val bad = ArrayList<String>()
        for (e in entries) {
            val p = entryFile(wd, e)
            if (e == "." || p.isDirectory) {
                if (File(p, rel).exists()) found = true
            } else if (suffix(e) in CP_OK && p.exists()) {
                try {
                    ZipFile(p).use { z -> if (z.getEntry(rel) != null) found = true }
                } catch (ex: ZipException) {
                    bad.add(e)
                } catch (ex: Exception) {
                    bad.add(e)
                }
            }
        }
        var tag = if (found) "OK (main class present)" else "FAIL: main class not on classpath"
        if (bad.isNotEmpty()) tag += "  [${bad.size} corrupt jar(s): ${bad.first()}]"
        return (found && bad.isEmpty()) to tag
    }

    // --- small helpers ---

    private fun entryFile(wd: File, e: String): File {
        val f = File(e)
        return if (f.isAbsolute) f else File(wd, e)
    }

    private fun suffix(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot < 0) "" else name.substring(dot).lowercase()
    }

    private fun winBaseName(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val n = s.replace('/', '\\')
        val i = n.lastIndexOf('\\')
        return if (i < 0) n else n.substring(i + 1)
    }

    private fun text(doc: Document, parent: String, child: String): String {
        val ps = doc.getElementsByTagName(parent)
        for (i in 0 until ps.length) {
            var c = ps.item(i).firstChild
            while (c != null) {
                if (c.nodeType == Node.ELEMENT_NODE && c.nodeName == child) return c.textContent.trim()
                c = c.nextSibling
            }
        }
        return ""
    }

    private fun countChildren(doc: Document, tag: String): Int = doc.getElementsByTagName(tag).length

    companion object {
        private val CP_OPTS = setOf("-cp", "-classpath", "--class-path")
        private val CP_OK = setOf(".jar", ".zip")
        private val TOKEN = Pattern.compile("(?:\"[^\"]*\"|\\S)+")
        private val HEX = Regex("[0-9a-f]+")

        /** hash OID -> (JCA name, digest length in bytes) */
        private val HASH_OIDS = mapOf(
            "1.3.14.3.2.26" to ("SHA-1" to 20),
            "2.16.840.1.101.3.4.2.1" to ("SHA-256" to 32),
            "2.16.840.1.101.3.4.2.2" to ("SHA-384" to 48),
            "2.16.840.1.101.3.4.2.3" to ("SHA-512" to 64),
        )

        private fun ByteArray.toHex(): String {
            val d = "0123456789abcdef"
            val sb = StringBuilder(size * 2)
            for (byte in this) {
                val v = byte.toInt() and 0xFF
                sb.append(d[v ushr 4]).append(d[v and 0xF])
            }
            return sb.toString()
        }
    }
}
