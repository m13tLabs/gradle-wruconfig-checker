package de.m13t.wrunconfig

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class WrunconfigVerifyTask : DefaultTask() {

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wrunconfigFiles: ConfigurableFileCollection

    /** Sibling `*.cat` catalogs, tracked for up-to-date checks (used when [verifyCatalog]). */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogFiles: ConfigurableFileCollection

    /** Staged application tree, tracked for up-to-date checks. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val applicationFiles: ConfigurableFileCollection

    @get:Internal
    abstract val applicationRoot: DirectoryProperty

    @get:Input
    abstract val failOnDropped: Property<Boolean>

    @get:Input
    abstract val failOnDynamic: Property<Boolean>

    @get:Input
    abstract val verifyCatalog: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val appRoot = applicationRoot.get().asFile
        if (!appRoot.isDirectory) {
            throw GradleException(
                "applicationRoot not found: $appRoot\n" +
                    "Point wrunconfigVerify.applicationRoot at the staged app dir and make " +
                    "verifyWrunconfig run after the task that stages it (dependsOn/mustRunAfter).",
            )
        }

        val verifier = WrunconfigVerifier(
            appRoot,
            failOnDropped = failOnDropped.getOrElse(false),
            failOnDynamic = failOnDynamic.getOrElse(false),
            verifyCatalog = verifyCatalog.getOrElse(false),
        )

        val out = StringBuilder()
        var failures = 0
        val files = wrunconfigFiles.files.sortedBy { it.path }
        for (f in files) {
            val result = verifier.verify(f)
            val failed = verifier.isFailure(result)
            if (failed) failures++
            val strictOnly = failed && result.status == Status.OK &&
                (result.catalog == null || result.catalog.status == CatalogStatus.OK)
            render(result, failedByStrict = strictOnly, out)
        }
        val summary = "=== $failures failure(s) in ${files.size} config(s) ==="
        out.append('\n').append(summary).append('\n')

        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(out.toString())

        logger.lifecycle(out.toString().trim())
        if (failures > 0) throw GradleException("$summary  (see $reportFile)")
    }

    private fun render(r: ConfigResult, failedByStrict: Boolean, sb: StringBuilder) {
        val oneLine = r.status == Status.SKIP || r.linkDetail == null
        when {
            r.status == Status.SKIP -> sb.append("${r.file}  SKIP (${r.message})\n")
            r.linkDetail == null -> sb.append("${r.file}  FAIL (${r.message})\n")   // parse / workdir / missing entry
            else -> {
                sb.append('\n').append(r.file).append('\n')
                sb.append("  main    : ${r.mainClass}\n")
                sb.append("  link    : ${r.linkDetail}\n")
                if (r.dropped.isNotEmpty())
                    sb.append("  drop    : ${r.dropped.size} non-class cp entries (e.g. ${File(r.dropped.first()).name})\n")
                if (r.dynamic.isNotEmpty())
                    sb.append("  dynamic : ${r.dynamic.size} unresolved at build time, skipped (e.g. ${r.dynamic.first()})\n")
                if (r.envCount > 0)
                    sb.append("  env     : ${r.envCount} vars\n")
                if (failedByStrict)
                    sb.append("  strict  : failing (dropped/dynamic entries not allowed)\n")
            }
        }
        renderCatalog(r, oneLine, sb)
    }

    private fun renderCatalog(r: ConfigResult, oneLineParent: Boolean, sb: StringBuilder) {
        val c = r.catalog ?: return
        // give the catalog detail a header if the classpath section was a one-liner
        if (oneLineParent) sb.append("  (").append(r.file.name).append(")\n")
        sb.append("  catalog : ${c.status} - ${c.message}\n")
        if (c.uncovered.isNotEmpty()) {
            val show = c.uncovered.take(20)
            sb.append("  resign  : ${show.joinToString(", ")}")
            if (c.uncovered.size > show.size) sb.append(", ... (+${c.uncovered.size - show.size} more)")
            sb.append('\n')
        }
    }
}
