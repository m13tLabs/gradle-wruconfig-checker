package de.m13t.wrunconfig

import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.util.regex.Pattern
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

enum class Status { OK, SKIP, FAIL }

data class ConfigResult(
    val file: File,
    val status: Status,
    val message: String,
    val mainClass: String? = null,
    val linkDetail: String? = null,
    val dropped: List<String> = emptyList(),
    val dynamic: List<String> = emptyList(),
    val envCount: Int = 0,
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
 */
class WrunconfigVerifier(
    applicationRoot: File,
    private val failOnDropped: Boolean = false,
    private val failOnDynamic: Boolean = false,
) {
    private val appRoot: File = applicationRoot.absoluteFile.normalize()

    fun verify(file: File): ConfigResult {
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
            (failOnDynamic && r.dynamic.isNotEmpty())

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
    }
}
