package de.m13t.wrunconfig

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises the real task wiring (extension -> task -> verifier) through Gradle. */
class WrunconfigVerifyPluginFunctionalTest {

    @TempDir lateinit var projectDir: File

    private lateinit var wrunconfigDir: File
    private lateinit var appClient: File

    @BeforeEach
    fun setUp() {
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n")

        wrunconfigDir = File(projectDir, "src/wrunconfig").apply { mkdirs() }
        File(wrunconfigDir, "App.exe.wrunconfig").writeText(
            """
            <Configuration><Process>
              <WorkingDirectory>[APPDIR]\client</WorkingDirectory>
              <Arguments>-cp "lib.jar" com.example.Main [ARGS]</Arguments>
            </Process></Configuration>
            """.trimIndent(),
        )

        appClient = File(projectDir, "staged/client").apply { mkdirs() }
        ZipOutputStream(File(appClient, "lib.jar").outputStream()).use { z ->
            z.putNextEntry(ZipEntry("com/example/Main.class"))
            z.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
            z.closeEntry()
        }
    }

    private fun buildFile(extra: String) = File(projectDir, "build.gradle.kts").writeText(
        """
        plugins {
            base
            id("de.m13t.wrunconfig-verify")
        }
        wrunconfigVerify {
            applicationRoot.set(layout.projectDirectory.dir("staged"))
            $extra
        }
        """.trimIndent(),
    )

    private fun run(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")

    private fun catFor(config: String) {
        val members = File(projectDir, "staged").walkTopDown().filter { it.isFile }
            .map { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }.toList()
        fun der(tag: Int, body: ByteArray): ByteArray {
            val len = if (body.size < 0x80) byteArrayOf(body.size.toByte())
            else byteArrayOf(0x81.toByte(), body.size.toByte())
            return byteArrayOf(tag.toByte()) + len + body
        }
        fun seq(vararg p: ByteArray) = der(0x30, p.fold(ByteArray(0)) { a, b -> a + b })
        val oid = byteArrayOf(0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)
        val ctl = seq(*members.map { seq(seq(seq(oid, byteArrayOf(0x05, 0x00)), der(0x04, it))) }.toTypedArray())
        File(wrunconfigDir, config).writeBytes(ctl)
    }

    @Test fun `classpath check passes and is wired into check`() {
        buildFile("")
        val result = run("check").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyWrunconfig")?.outcome)
        assertTrue(File(projectDir, "build/reports/wrunconfig/verify.txt").exists())
    }

    @Test fun `verifyCatalog passes with a catalog covering the staged tree`() {
        buildFile("verifyCatalog.set(true)")
        catFor("App.exe.cat")
        val result = run("verifyWrunconfig").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyWrunconfig")?.outcome)
    }

    @Test fun `verifyCatalog fails the build when the catalog is missing`() {
        buildFile("verifyCatalog.set(true)")
        val result = run("verifyWrunconfig").buildAndFail()
        assertTrue(result.output.contains("MISSING"))
    }

    @Test fun `verifyCatalog fails the build on drift`() {
        buildFile("verifyCatalog.set(true)")
        catFor("App.exe.cat")
        // change a staged file after the catalog was written -> drift
        File(appClient, "extra.txt").writeText("added after signing")
        val result = run("verifyWrunconfig").buildAndFail()
        assertTrue(result.output.contains("re-sign required"))
        assertTrue(result.output.contains("client/extra.txt"))
    }
}
