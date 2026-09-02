package de.m13t.wrunconfig

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

class WrunconfigVerifyPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("wrunconfigVerify", WrunconfigVerifyExtension::class.java)
        ext.wrunconfigDir.convention(project.layout.projectDirectory.dir("src/wrunconfig"))
        ext.applicationRoot.convention(project.layout.buildDirectory.dir("msix/application"))
        ext.failOnDropped.convention(false)
        ext.failOnDynamic.convention(false)
        ext.verifyCatalog.convention(false)

        val verify = project.tasks.register("verifyWrunconfig", WrunconfigVerifyTask::class.java) { task ->
            task.group = LifecycleBasePlugin.VERIFICATION_GROUP
            task.description = "Verifies classpath entries and the main class in .wrunconfig files."
            task.wrunconfigFiles.from(
                ext.wrunconfigDir.map { dir -> dir.asFileTree.matching { it.include("**/*.wrunconfig") } },
            )
            task.catalogFiles.from(
                ext.wrunconfigDir.map { dir -> dir.asFileTree.matching { it.include("**/*.cat") } },
            )
            task.applicationFiles.from(ext.applicationRoot.map { it.asFileTree })
            task.applicationRoot.set(ext.applicationRoot)
            task.failOnDropped.set(ext.failOnDropped)
            task.failOnDynamic.set(ext.failOnDynamic)
            task.verifyCatalog.set(ext.verifyCatalog)
            task.report.set(project.layout.buildDirectory.file("reports/wrunconfig/verify.txt"))
        }

        // Gate `check` when the lifecycle-base plugin (java, base, ...) is present.
        project.plugins.withType(LifecycleBasePlugin::class.java) {
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { it.dependsOn(verify) }
        }
    }
}
