package de.m13t.wrunconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WrunconfigVerifierTest {

    @TempDir lateinit var tmp: File
    private lateinit var appRoot: File
    private lateinit var wd: File

    @BeforeEach
    fun setUp() {
        appRoot = File(tmp, "application")
        wd = File(appRoot, "client").apply { mkdirs() }

        // dir-based classpath entry containing a .class
        File(wd, "plugin/com/example").mkdirs()
        File(wd, "plugin/com/example/Helper.class").writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
        // valid jar with the main class
        jar(File(wd, "lib.jar"), "com/example/Main.class")
        // registry-default jar (no main)
        jar(File(wd, "extra.jar"), "com/other/Thing.class")
        // dropped, corrupt
        File(wd, "notes.pom").writeText("<project/>")
        File(wd, "broken.jar").writeText("not a zip")
    }

    private fun jar(target: File, vararg entries: String) {
        ZipOutputStream(target.outputStream()).use { z ->
            for (e in entries) {
                z.putNextEntry(ZipEntry(e))
                z.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
                z.closeEntry()
            }
        }
    }

    private fun cfg(name: String, xml: String): File =
        File(tmp, name).apply { writeText(xml) }

    private fun verifier() = WrunconfigVerifier(appRoot)

    @Test fun `resolves registry default, drops pom, links main`() {
        val f = cfg(
            "App.wrunconfig",
            """
            <Configuration><Process>
              <WorkingDirectory>[APPDIR]\client</WorkingDirectory>
              <Arguments>-cp "lib.jar;plugin;[RETRIVEFROMREGISTRY|HKLM|Software\Foo|Path|extra.jar];notes.pom" com.example.Main [ARGS]</Arguments>
            </Process>
            <EnvironmentVariable><Name>JAVA_TOOL_OPTIONS</Name><Value>-Xmx256m</Value></EnvironmentVariable>
            </Configuration>
            """.trimIndent(),
        )
        val r = verifier().verify(f)
        assertEquals(Status.OK, r.status)
        assertEquals("com.example.Main", r.mainClass)
        assertEquals(1, r.dropped.size)        // notes.pom
        assertEquals(1, r.envCount)
    }

    @Test fun `missing classpath entry fails`() {
        val f = cfg(
            "Bad.wrunconfig",
            """
            <Configuration><Process>
              <WorkingDirectory>[APPDIR]\client</WorkingDirectory>
              <Arguments>-cp missing.jar;lib.jar com.example.Main</Arguments>
            </Process></Configuration>
            """.trimIndent(),
        )
        val r = verifier().verify(f)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.message.contains("missing"))
    }

    @Test fun `no classpath or main is skipped`() {
        val f = cfg("Skip.wrunconfig", "<Configuration><Process><Arguments>-query [ARGS]</Arguments></Process></Configuration>")
        assertEquals(Status.SKIP, verifier().verify(f).status)
    }

    @Test fun `corrupt jar fails linkage`() {
        val f = cfg(
            "Corrupt.wrunconfig",
            """
            <Configuration><Process>
              <WorkingDirectory>[APPDIR]\client</WorkingDirectory>
              <Arguments>-cp broken.jar com.example.Main</Arguments>
            </Process></Configuration>
            """.trimIndent(),
        )
        val r = verifier().verify(f)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.linkDetail!!.contains("corrupt"))
    }

    @Test fun `unresolvable entries are skipped as dynamic, not failed`() {
        val f = cfg(
            "Dynamic.wrunconfig",
            """
            <Configuration><Process>
              <WorkingDirectory>[APPDIR]\client</WorkingDirectory>
              <Arguments>-cp lib.jar;[EXENAME]_plugins;[SOME_RUNTIME_DIR] com.example.Main</Arguments>
            </Process></Configuration>
            """.trimIndent(),
        )
        val r = verifier().verify(f)
        assertEquals(Status.OK, r.status)
        assertEquals(2, r.dynamic.size)
    }

    // --- catalog (.cat) <-> app-tree sync -----------------------------------

    private fun appXml() =
        """
        <Configuration><Process>
          <WorkingDirectory>[APPDIR]\client</WorkingDirectory>
          <Arguments>-cp "lib.jar" com.example.Main [ARGS]</Arguments>
        </Process></Configuration>
        """.trimIndent()

    private fun stagedFiles() = appRoot.walkTopDown().filter { it.isFile }.toList()

    private fun sha256(f: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(f.readBytes())

    private fun derLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte()) + derLen(body.size) + body
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    // 2.16.840.1.101.3.4.2.1
    private val sha256Oid =
        byteArrayOf(0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)

    private fun digestInfo(hash: ByteArray) = seq(seq(sha256Oid, byteArrayOf(0x05, 0x00)), tlv(0x04, hash))

    /** A minimal CTL-ish DER: SEQUENCE of member SEQUENCEs, each wrapping a DigestInfo. */
    private fun catalog(hashes: List<ByteArray>) =
        seq(*hashes.map { seq(digestInfo(it)) }.toTypedArray())

    private fun catVerifier() = WrunconfigVerifier(appRoot, verifyCatalog = true)

    @Test fun `catalog covering the whole app tree passes`() {
        val f = cfg("App.exe.wrunconfig", appXml())
        File(tmp, "App.exe.cat").writeBytes(catalog(stagedFiles().map { sha256(it) }))
        val r = catVerifier().verify(f)
        assertEquals(CatalogStatus.OK, r.catalog!!.status)
        assertEquals("SHA-256", r.catalog!!.algorithm)
        assertTrue(r.catalog!!.checked >= 5)
        assertTrue(!catVerifier().isFailure(r))
    }

    @Test fun `catalog missing a staged file reports drift and fails`() {
        val f = cfg("App.exe.wrunconfig", appXml())
        val hashes = stagedFiles().filter { it.name != "extra.jar" }.map { sha256(it) }
        File(tmp, "App.exe.cat").writeBytes(catalog(hashes))
        val r = catVerifier().verify(f)
        assertEquals(CatalogStatus.DRIFT, r.catalog!!.status)
        assertEquals(listOf("client/extra.jar"), r.catalog!!.uncovered)
        assertTrue(catVerifier().isFailure(r))
    }

    @Test fun `missing sibling catalog fails when enabled`() {
        val f = cfg("App.exe.wrunconfig", appXml())
        val r = catVerifier().verify(f)
        assertEquals(CatalogStatus.MISSING, r.catalog!!.status)
        assertTrue(catVerifier().isFailure(r))
    }

    @Test fun `unreadable catalog fails`() {
        val f = cfg("App.exe.wrunconfig", appXml())
        File(tmp, "App.exe.cat").writeText("not a catalog at all")
        val r = catVerifier().verify(f)
        assertEquals(CatalogStatus.ERROR, r.catalog!!.status)
    }

    @Test fun `catalog check is off by default`() {
        val f = cfg("App.exe.wrunconfig", appXml())
        assertNull(verifier().verify(f).catalog)
    }

    @Test fun `digests inside a pkcs7-wrapped octet string are still found`() {
        val f = cfg("App.exe.wrunconfig", appXml())
        val ctl = catalog(stagedFiles().map { sha256(it) })
        // SEQUENCE { OID signedData, [0] { OCTET STRING { <ctl> } } }
        val signedDataOid =
            byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)
        File(tmp, "App.exe.cat").writeBytes(seq(signedDataOid, tlv(0xA0, tlv(0x04, ctl))))
        val r = catVerifier().verify(f)
        assertEquals(CatalogStatus.OK, r.catalog!!.status)
    }
}
