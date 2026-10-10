package com.itsaky.androidide.dialogs // Your current package for this interface

import android.util.Log
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.Locale // Added for lowercase

object ProjectFileUtils {

    private const val TAG = "ProjectFileUtils"

    fun scanProjectFiles(projectRoot: File): List<String> {
        val result = mutableListOf<String>()
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            Log.w(TAG, "scanProjectFiles: Project root directory does not exist or is not a directory: ${projectRoot.absolutePath}")
            return result
        }
        collectFilePaths(projectRoot, projectRoot, result)
        return result
    }

    private fun collectFilePaths(baseDir: File, currentDir: File, result: MutableList<String>) {
        val files = currentDir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                // Added "gradle" to skip the heavy wrapper jar
                if (file.name in arrayOf("build", ".gradle", ".git", ".idea", "generated", "libs", "obj", "bin", "gradle")) {
                    continue
                }
                collectFilePaths(baseDir, file, result)
            } else {
                // Pass the actual File object so we can check its size
                if (isCodeFile(file)) {
                    val relativePath = file.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    result.add(relativePath)
                }
            }
        }
    }

    private fun isCodeFile(file: File): Boolean {
        val filename = file.name.lowercase(Locale.ROOT)

        // 1. Explicitly ignore binaries and local env files
        if (filename.endsWith(".jar") || filename.endsWith(".aar") || filename.endsWith(".so") || filename.endsWith(".apk")) return false
        if (filename == "local.properties" || filename == "gradlew" || filename == "gradlew.bat") return false

        // 2. Only allow specific text extensions
        val codeExtensions = arrayOf(".kt", ".java", ".xml", ".gradle", ".kts", ".properties", ".json", ".md", ".toml")
        val hasValidExtension = codeExtensions.any { filename.endsWith(it) }

        if (!hasValidExtension) return false

        // 3. SIZE LIMIT: Ignore files larger than 100 KB (102,400 bytes)
        // This is the crucial fix to prevent 503 Overloaded errors!
        if (file.length() > 100 * 1024) {
            Log.w(TAG, "Skipping large file to save AI tokens: ${file.name} (${file.length()} bytes)")
            return false
        }

        return true
    }


    fun processFileChangesAndDeletions(
        projectDir: File,
        filesToWrite: Map<String, String>,
        filesToDelete: List<String>,
        logAppender: (String) -> Unit,
        onComplete: (writeSuccessCount: Int, writeErrorCount: Int, deleteSuccessCount: Int, deleteErrorCount: Int) -> Unit
    ) {
        var writeSuccess = 0
        var writeError = 0
        var deleteSuccess = 0
        var deleteError = 0

        logAppender("--- Starting File System Operations ---\n")

        if (filesToDelete.isNotEmpty()) {
            logAppender("Deletion Phase: Attempting to delete ${filesToDelete.size} file(s).\n")
            filesToDelete.forEach { relativePath ->
                if (relativePath.isBlank()) {
                    logAppender("⚠️ Attempted to delete a file with a blank path. Skipping.\n")
                    return@forEach
                }
                val file = File(projectDir, relativePath)
                if (file.exists()) {
                    try {
                        if (file.isFile) {
                            if (file.delete()) {
                                logAppender("✅ Deleted file: $relativePath\n")
                                deleteSuccess++
                            } else {
                                logAppender("⚠️ Failed to delete file (unknown reason): $relativePath\n")
                                deleteError++
                            }
                        } else if (file.isDirectory) { // Simple directory deletion (non-recursive)
                            if (file.listFiles()?.isEmpty() == true && file.delete()) {
                                logAppender("✅ Deleted empty directory: $relativePath\n")
                                deleteSuccess++
                            } else {
                                logAppender("⚠️ Path for deletion is a non-empty directory or failed to delete: $relativePath. Manual deletion might be required.\n")
                                deleteError++
                            }
                        }
                    } catch (e: SecurityException) {
                        logAppender("⚠️ Security error deleting $relativePath: ${e.message}\n")
                        deleteError++
                    } catch (e: Exception) {
                        logAppender("⚠️ Unexpected error deleting $relativePath: ${e.message}\n")
                        deleteError++
                    }
                } else {
                    logAppender("ℹ️ File for deletion not found: $relativePath\n")
                }
            }
            logAppender("Deletion Phase complete. Success: $deleteSuccess, Errors: $deleteError.\n")
        } else {
            logAppender("Deletion Phase: No files specified for deletion.\n")
        }

        if (filesToWrite.isNotEmpty()) {
            logAppender("Writing Phase: Attempting to write/update ${filesToWrite.size} file(s).\n")
            filesToWrite.forEach { (relativePath, content) ->
                if (relativePath.isBlank()) {
                    logAppender("⚠️ Attempted to write a file with a blank path. Skipping.\n")
                    return@forEach
                }
                val file = File(projectDir, relativePath)
                try {
                    file.parentFile?.mkdirs()
                    FileWriter(file).use { it.write(content) }
                    logAppender("✅ Updated/Created file: $relativePath\n")
                    writeSuccess++
                } catch (e: IOException) {
                    logAppender("❌ IO Failed to write $relativePath: ${e.message}\n")
                    writeError++
                } catch (e: SecurityException) {
                    logAppender("❌ Security exception writing $relativePath: ${e.message}\n")
                    writeError++
                } catch (e: Exception) {
                    logAppender("❌ Failed to write $relativePath (Unexpected Error): ${e.message}\n")
                    writeError++
                }
            }
            logAppender("Writing Phase complete. Success: $writeSuccess, Errors: $writeError.\n")
        } else {
            logAppender("Writing Phase: No files specified for writing/updating.\n")
        }

        logAppender("--- File System Operations Complete ---\n")
        onComplete(writeSuccess, writeError, deleteSuccess, deleteError)
    }

    // Helper to find a common package name
    fun findCommonPackageName(projectDir: File?, appName: String, codeFileContents: Collection<String>): String {
        // 1. Try from AndroidManifest.xml
        projectDir?.let {
            val manifestFile = File(it, "app/src/main/AndroidManifest.xml")
            if (manifestFile.exists()) {
                try {
                    val manifestContent = FileReader(manifestFile).use { reader -> reader.readText() }
                    Regex("""package="([^"]+)"""").find(manifestContent)?.groupValues?.get(1)?.let { packageName ->
                        if (packageName.isNotBlank()) return packageName
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Could not read AndroidManifest.xml to find package name", e)
                }
            }
        }

        // 2. Try from existing code files
        for (content in codeFileContents) {
            Regex("""^\s*package\s+([a-zA-Z0-9_.]+)""").find(content)?.groupValues?.get(1)?.let { packageName ->
                if (packageName.isNotBlank() && !packageName.startsWith("java.") && !packageName.startsWith("javax.") && !packageName.startsWith("android.")) {
                    return packageName
                }
            }
        }

        // 3. Generate a default based on appName
        val sanitizedAppName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault()).ifEmpty { "myapp" }
        return "com.example.$sanitizedAppName"
    }

    /**
     * Extracts only actionable compiler errors (Kotlin, Java, AAPT/XML, Gradle dependency errors)
     * and strips daemon logs, stack traces, and boilerplate to keep LLM context minimal.
     */
    fun extractRelevantCompilerErrors(rawOutput: String, maxChars: Int = 2500): String {
        if (rawOutput.isBlank()) return "Unknown build failure (no log output)."

        val lines = rawOutput.lines()
        val errorLines = mutableListOf<String>()

        val errorPatterns = listOf(
            Regex("""^e:\s+.*"""),                                      // Kotlin compiler errors (e: /path/File.kt:12:34: ...)
            Regex(""".*error:\s+.*"""),                                 // javac errors
            Regex(""".*AAPT:\s+error:.*""", RegexOption.IGNORE_CASE),   // AAPT2 XML resource errors
            Regex(""".*Execution failed for task.*"""),                 // Failed task name
            Regex(""".*Unresolved reference.*"""),                      // Common Kotlin compiler issues
            Regex(""".*Type mismatch.*"""),
            Regex(""".*Manifest merger failed.*""", RegexOption.IGNORE_CASE),
            Regex("""FAILURE:\s+Build failed with an exception\..*""")
        )

        var captureNextCount = 0

        for (line in lines) {
            val trimmed = line.trim()

            // Skip internal Gradle stack traces and daemon metadata
            if (trimmed.startsWith("at org.gradle.") ||
                trimmed.startsWith("at java.") ||
                trimmed.startsWith("at com.android.tools.") ||
                trimmed.startsWith("Caused by: org.gradle.") ||
                trimmed.contains("WorkerExecutor") ||
                trimmed.contains("Problems report is available") ||
                trimmed.contains("Deprecated Gradle features were used") ||
                trimmed.contains("BUILD FAILED in")
            ) {
                continue
            }

            val matchesError = errorPatterns.any { it.containsMatchIn(trimmed) }
            if (matchesError) {
                errorLines.add(trimmed)
                captureNextCount = 2 // Capture the next 2 lines often containing code pointers (e.g. ^^^)
            } else if (captureNextCount > 0) {
                errorLines.add(trimmed)
                captureNextCount--
            }
        }

        // If no compiler-pattern lines matched, grab the lines immediately surrounding "FAILURE:"
        val result = if (errorLines.isNotEmpty()) {
            errorLines.distinct().joinToString("\n")
        } else {
            lines.filter { it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true) }
                .take(15)
                .joinToString("\n")
        }

        return result.take(maxChars).trim()
    }
}