package de.m13t.wrunconfig

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the `verifyWrunconfig` task.
 *
 * ```
 * wrunconfigVerify {
 *     wrunconfigDir.set(layout.projectDirectory.dir("src/wrunconfig"))
 *     applicationRoot.set(layout.buildDirectory.dir("msix/application"))
 *     failOnDropped.set(false)   // fail if a cp entry is neither a dir nor a .jar/.zip
 *     failOnDynamic.set(false)   // fail if a cp entry cannot be resolved at build time
 * }
 * ```
 */
abstract class WrunconfigVerifyExtension {
    /** Directory scanned recursively for `*.wrunconfig` files. */
    abstract val wrunconfigDir: DirectoryProperty

    /** Staged application root the classpath entries resolve against (`[APPDIR]`). */
    abstract val applicationRoot: DirectoryProperty

    /** Fail the build if a classpath entry is dropped as non-class (pom, txt, ...). */
    abstract val failOnDropped: Property<Boolean>

    /** Fail the build if a classpath entry still contains unresolved placeholders. */
    abstract val failOnDynamic: Property<Boolean>
}
