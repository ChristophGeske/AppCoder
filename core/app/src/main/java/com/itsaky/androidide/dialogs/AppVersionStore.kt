package com.itsaky.androidide.dialogs

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Single source of truth for app versions (<AppName>_v1, _v2, _v2.1 ...).
 *
 * RULES
 *  1. The working copy ("workbench", folder <AppName>) is where the AI changes code. It may be broken.
 *  2. A version folder is created ONLY after the workbench built AND installed successfully
 *     (possibly after several automatic fix attempts). Broken code is never stored as a version.
 *  3. Every AI change marks the workbench as "unverified". The marker holds a token that is unique
 *     per process:
 *       - IDE only moved to the background (same process): keep working on the workbench.
 *       - IDE fully closed and started again (new process): the unverified changes are discarded and
 *         the workbench is reset to the latest stored version when the project is modified again.
 */
object AppVersionStore {

    private const val TAG = "AppVersionStore"

    const val VERSION_SOURCE_FILE = ".version_source"
    const val UNVERIFIED_FILE = ".unverified"

    // Directories that are never copied into a version snapshot (they are rebuilt anyway)
    private val SNAPSHOT_SKIP = setOf("build", ".gradle", ".git", ".idea")

    /** Changes every time the app process is created, i.e. after the IDE was closed completely. */
    private val sessionToken: String = UUID.randomUUID().toString()

    /** The workbench whose build was started most recently in this process. */
    @Volatile
    private var pendingBuildDir: File? = null

    enum class WorkbenchState {
        /** No unverified changes: the workbench equals a verified state. */
        CLEAN,

        /** Unverified changes made in this process (e.g. IDE was only in the background). */
        UNVERIFIED_THIS_SESSION,

        /** Unverified changes left over from a process that no longer exists. */
        UNVERIFIED_PREVIOUS_SESSION
    }

    // ------------------------------------------------------------------------------------------
    // Workbench state
    // ------------------------------------------------------------------------------------------

    fun markUnverified(workbench: File) {
        try {
            File(workbench, UNVERIFIED_FILE).writeText(sessionToken)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write unverified marker in ${workbench.absolutePath}", e)
        }
    }

    fun workbenchState(workbench: File): WorkbenchState {
        val marker = File(workbench, UNVERIFIED_FILE)
        if (!marker.isFile) return WorkbenchState.CLEAN
        val token = try {
            marker.readText().trim()
        } catch (e: Exception) {
            ""
        }
        return if (token == sessionToken) WorkbenchState.UNVERIFIED_THIS_SESSION
        else WorkbenchState.UNVERIFIED_PREVIOUS_SESSION
    }

    // ------------------------------------------------------------------------------------------
    // Build -> install -> commit
    // ------------------------------------------------------------------------------------------

    /** Call when a build of [workbench] is started. */
    fun registerPendingBuild(workbench: File) {
        pendingBuildDir = workbench
    }

    /**
     * Call when the IDE reports that an APK was INSTALLED successfully.
     * If it belongs to the workbench that is currently being built and that workbench has unverified
     * changes, they are stored as a new version. Returns the new version folder, or null if nothing
     * was committed.
     *
     * [installedPackage] is the package name from the installer result (may be null if unknown).
     */
    @Synchronized
    fun onInstallSucceeded(installedPackage: String?): File? {
        val workbench = pendingBuildDir ?: return null
        if (!workbench.isDirectory) return null
        if (!File(workbench, UNVERIFIED_FILE).isFile) return null // nothing new to store

        val appId = readApplicationId(workbench)
        if (installedPackage != null && appId != null && installedPackage != appId) {
            Log.i(TAG, "Install of '$installedPackage' ignored; pending build is '$appId'.")
            return null
        }

        return try {
            val versionDir = commitVerifiedVersion(workbench)
            pendingBuildDir = null
            Log.i(TAG, "Verified build installed. Stored version '${versionDir.name}'.")
            versionDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store verified version for '${workbench.name}'", e)
            null
        }
    }

    /** Stores the workbench as a new version and clears its unverified marker. */
    fun commitVerifiedVersion(workbench: File): File {
        val versionDir = createSnapshot(workbench)
        File(workbench, UNVERIFIED_FILE).delete()
        return versionDir
    }

    // ------------------------------------------------------------------------------------------
    // Versions on disk
    // ------------------------------------------------------------------------------------------

    private val versionComparator = Comparator<String> { v1, v2 ->
        val parts1 = v1.split('.').mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split('.').mapNotNull { it.toIntOrNull() }
        val maxParts = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxParts) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return@Comparator p1.compareTo(p2)
        }
        0
    }

    fun findVersions(projectsBaseDir: File, baseAppName: String): List<String> {
        val regex = "^${Regex.escape(baseAppName)}_v(\\d+(?:\\.\\d+)*)$".toRegex()
        val versions = projectsBaseDir.listFiles { dir -> dir.isDirectory && regex.matches(dir.name) }
            ?.mapNotNull { regex.find(it.name)?.groupValues?.get(1) }
            ?: emptyList()
        return versions.sortedWith(versionComparator)
    }

    /**
     * Copies [workbench] to the next free version folder (linear: _v3, or branch: _v2.1 when the
     * workbench was started from an older version). Throws on failure.
     */
    fun createSnapshot(workbench: File): File {
        if (!workbench.isDirectory) throw IOException("Source project directory does not exist for versioning.")
        val projectsBaseDir = workbench.parentFile ?: throw IOException("Project has no parent directory.")
        val baseName = workbench.name

        val bookmark = File(workbench, VERSION_SOURCE_FILE)
        val sourceVersionProjectName = if (bookmark.exists()) bookmark.readText().trim() else null

        val newVersionName = if (sourceVersionProjectName != null) {
            // BRANCH: workbench was started from an older version
            val sourceVersionNumber = sourceVersionProjectName.substringAfterLast("_v")
            val childVersions = findVersions(projectsBaseDir, baseName).filter { v ->
                v.startsWith("$sourceVersionNumber.") &&
                        v.split('.').size == sourceVersionNumber.split('.').size + 1
            }
            val newNumber = if (childVersions.isEmpty()) {
                "$sourceVersionNumber.1"
            } else {
                val parts = childVersions.sortedWith(versionComparator).last().split('.').toMutableList()
                parts[parts.size - 1] = ((parts.last().toIntOrNull() ?: 0) + 1).toString()
                parts.joinToString(".")
            }
            "${baseName}_v$newNumber"
        } else {
            // LINEAR: next major number
            val latestMajor = findVersions(projectsBaseDir, baseName)
                .filter { !it.contains('.') }
                .mapNotNull { it.toIntOrNull() }
                .maxOrNull() ?: 0
            "${baseName}_v${latestMajor + 1}"
        }

        val newVersionDir = File(projectsBaseDir, newVersionName)
        if (newVersionDir.exists()) throw IOException("Version directory '$newVersionName' already exists.")

        copySkippingBuildDirs(workbench, newVersionDir)

        // A snapshot is a clean, verified state: no bookmark, no unverified marker.
        File(newVersionDir, VERSION_SOURCE_FILE).delete()
        File(newVersionDir, UNVERIFIED_FILE).delete()
        // The workbench continues linearly from here.
        bookmark.delete()

        return newVersionDir
    }

    private fun copySkippingBuildDirs(src: File, dest: File) {
        dest.mkdirs()
        src.walkTopDown()
            .onEnter { it == src || it.name !in SNAPSHOT_SKIP }
            .forEach { f ->
                val target = File(dest, f.relativeTo(src).path)
                if (f.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    f.copyTo(target, overwrite = true)
                }
            }
    }

    // ------------------------------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------------------------------

    fun readApplicationId(workbench: File): String? {
        val regex = Regex("applicationId\\s*(?:=\\s*)?[\"']([^\"']+)[\"']")
        for (rel in listOf("app/build.gradle.kts", "app/build.gradle")) {
            val f = File(workbench, rel)
            if (!f.isFile) continue
            try {
                regex.find(f.readText())?.let { return it.groupValues[1] }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read $rel", e)
            }
        }
        return null
    }
}