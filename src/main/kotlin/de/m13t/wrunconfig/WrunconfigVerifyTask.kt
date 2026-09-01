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
        )

        val out = StringBuilder()
        var failures = 0
        val files = wrunconfigFiles.files.sortedBy { it.path }
        for (f in files) {
            val result = verifier.verify(f)
            val failed = verifier.isFailure(result)
            if (failed) failures++
            render(result, failedByStrict = failed && result.status == Status.OK, out)
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
    }
}
