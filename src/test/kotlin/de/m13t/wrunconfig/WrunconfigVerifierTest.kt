package de.m13t.wrunconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
}
