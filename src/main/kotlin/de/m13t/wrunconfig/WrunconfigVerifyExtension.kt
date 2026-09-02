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
 *     verifyCatalog.set(false)   // also check the sibling .cat covers the staged app tree
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

    /**
     * Also verify each config against its sibling Authenticode catalog
     * (`<name>.wrunconfig` -> `<name>.cat`): every file staged under
     * [applicationRoot] must have its hash listed in the catalog. An uncovered
     * file means the payload changed since signing and the package must be
     * re-signed. The build fails on a missing/stale/unreadable catalog.
     */
    abstract val verifyCatalog: Property<Boolean>
}
