// File: com/itsaky/androidide/dialogs/GeminiWorkflowCoordinator.kt
package com.itsaky.androidide.dialogs

import android.util.Log
import com.itsaky.androidide.services.AiForegroundService
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.IOException

// ---- Local JSON helper extensions (avoid unresolved references) ----
private fun JSONObject.unwrapDataIfPresent(): JSONObject = this.optJSONObject("data") ?: this
private fun JSONObject.optJSONArrayByKeys(vararg keys: String): JSONArray? {
    for (k in keys) {
        val arr = this.optJSONArray(k)
        if (arr != null) return arr
    }
    return null
}
private fun JSONObject.optStringByKeys(vararg keys: String): String? {
    for (k in keys) {
        val v = this.optString(k, null)
        if (!v.isNullOrBlank()) return v
    }
    return null
}
private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (i in 0 until this.length()) {
        val obj = this.optJSONObject(i)
        if (obj != null) action(obj)
    }
}

class GeminiWorkflowCoordinator(
    private val geminiHelper: GeminiHelper,
    private val directLogAppender: (String) -> Unit,
    private val bridge: ViewModelFileEditorBridge
) {
    companion object {
        private const val TAG = "AiWorkflow_Merged"
        private const val PROTECTED_VERSION_FILE = ".version_source"
        private val PROTECTED_FILES = setOf(PROTECTED_VERSION_FILE, AppVersionStore.UNVERIFIED_FILE)

        private const val MAX_FALLBACK_RETRIES = 2
        private const val MAX_NEED_MORE_ROUNDS = 6

        // Code-dump logging helpers
        private const val CODE_TAG = "AI_PIPELINE_CODE"
        private const val MAX_UI_SNIPPET = 8000
    }

    private fun logLarge(tag: String, header: String, body: String, footer: String = "") {
        if (header.isNotEmpty()) Log.v(tag, header)
        val chunk = 3000
        var i = 0
        val len = body.length
        while (i < len) {
            val end = kotlin.math.min(i + chunk, len)
            Log.v(tag, body.substring(i, end))
            i = end
        }
        if (footer.isNotEmpty()) Log.v(tag, footer)
    }

    private fun logPromptToUi(title: String, text: String) {
        val head = ">>> $title (chars=${text.length})\n"
        val body = text.take(MAX_UI_SNIPPET)
        val tail = if (text.length > MAX_UI_SNIPPET) "\n... [truncated in UI log]\n" else "\n"
        directLogAppender(head + body + tail)
    }

    private val conversation = GeminiConversation()

    private var allProjectFiles = listOf<String>()
    private val selectedFilesForModification = mutableListOf<String>()
    private var lastAppNameForFallback: String = ""
    private var lastAppDescriptionForFallback: String = ""

    // flags
    private var autoBuildAfterApply = false
    private var autoRunAfterBuild = false
    private var hasTriggeredAutoBuild = false
    private var encounteredError = false
    private var anyChangesApplied = false

    private fun logViaBridge(message: String) = bridge.appendToLogBridge(message)

    // Entry point
    fun startModificationFlow(
        appName: String,
        appDescription: String,
        projectDir: File,
        autoBuild: Boolean = false,
        autoRun: Boolean = false
    ) {
        val provider = if (geminiHelper.currentModelIdentifier.startsWith("gpt-", ignoreCase = true)) "OpenAI" else "Gemini"
        Log.i("AI_PIPELINE", "startModificationFlow: app='$appName', model='${geminiHelper.currentModelIdentifier}', provider=$provider, autoBuild=$autoBuild, autoRun=$autoRun, projectDir='${projectDir.absolutePath}'")
        logViaBridge("AI Workflow ($provider): Starting for project '$appName'\n")

        conversation.clear()
        selectedFilesForModification.clear()
        bridge.currentProjectDirBridge = projectDir
        bridge.displayAiConclusionBridge(null)

        autoBuildAfterApply = autoBuild
        autoRunAfterBuild = autoRun
        hasTriggeredAutoBuild = false
        encounteredError = false
        anyChangesApplied = false

        lastAppNameForFallback = appName
        lastAppDescriptionForFallback = appDescription

        AiForegroundService.start(bridge.getContextBridge(), "Analyzing project for $appName")

        allProjectFiles = ProjectFileUtils.scanProjectFiles(projectDir)
        Log.d("AI_PIPELINE", "Project scan completed: files=${allProjectFiles.size} -> ${allProjectFiles.take(50)}")

        if (allProjectFiles.isEmpty()) {
            logViaBridge("Project is empty. Asking AI to generate initial files.\n")
            bridge.updateStateBridge(AiWorkflowState.CREATING_PROJECT_TEMPLATE)
            generateInitialFilesFromDescription(appName, appDescription, attempt = 0)
        } else {
            logViaBridge("Project has ${allProjectFiles.size} files. Sending directly to AI for modification...\n")
            selectedFilesForModification.clear()
            selectedFilesForModification.addAll(allProjectFiles)
            loadSelectedFilesAndRunSingleShotFlow(appName, appDescription)
        }
    }

    private fun loadSelectedFilesAndRunSingleShotFlow(appName: String, appDescription: String) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            handleError("Project directory is null before single-shot generation.", null)
            return
        }
        bridge.updateStateBridge(AiWorkflowState.GENERATING_CODE)
        Log.d("AI_PIPELINE", "loadSelectedFilesAndRunSingleShotFlow: selected=${selectedFilesForModification.size}")
        val fileContentsMap = mutableMapOf<String, String>()

        for (filePath in selectedFilesForModification) {
            val f = File(projectDir, filePath)
            if (!f.exists() || !f.isFile) {
                fileContentsMap[filePath] = "// File: $filePath (This file is new or was not found. Please provide its complete content.)"
            } else {
                try {
                    fileContentsMap[filePath] = FileReader(f).use { it.readText() }
                } catch (e: IOException) {
                    fileContentsMap[filePath] = "// File: $filePath (Error reading existing content. Please regenerate based on its intended role.)"
                }
            }
        }

        if (fileContentsMap.isEmpty()) {
            logViaBridge("No valid files were loaded. Attempting to generate from description...\n")
            generateInitialFilesFromDescription(appName, appDescription, 0)
            return
        }

        runSingleShotPlanAndWriteFlow(appName, appDescription, fileContentsMap)
    }

    private fun makeFilesContentBlock(filesMap: Map<String, String>): String {
        val sb = StringBuilder()
        filesMap.forEach { (path, content) ->
            sb.append("FILE: $path\n```\n")
            sb.append(content)
            sb.append("\n```\n\n")
        }
        return sb.toString()
    }

    private fun parseFileModificationsWithRequestMore(jsonText: String): Pair<FileModifications?, List<String>?> {
        return try {
            val root = JSONObject(jsonText).unwrapDataIfPresent()
            val filesMap = mutableMapOf<String, String>()
            root.optJSONArrayByKeys("filesToWrite", "files_to_write")?.forEachObject { obj ->
                val path = obj.optStringByKeys("filePath", "file_path") ?: ""
                val content = obj.optStringByKeys("fileContent", "file_content") ?: ""
                if (path.isNotBlank()) filesMap[path] = content
            }

            val filesToDelete = mutableListOf<String>()
            root.optJSONArrayByKeys("filesToDelete", "files_to_delete")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (!s.isNullOrBlank()) filesToDelete.add(s)
                }
            }

            val requestMore = mutableListOf<String>()
            root.optJSONArrayByKeys("requestMoreFiles", "request_more_files", "request_more_files_paths")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (!s.isNullOrBlank()) requestMore.add(s)
                }
            }

            val conclusionText = root.optStringByKeys("conclusion", "summary")?.trim()
            val fm = FileModifications(filesToWrite = filesMap, filesToDelete = filesToDelete, conclusion = conclusionText)
            fm to if (requestMore.isNotEmpty()) requestMore else null
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing FileModifications JSON: '$jsonText'. Error: ${e.message}", e)
            null to null
        }
    }

    private fun runSingleShotPlanAndWriteFlow(
        appName: String,
        appDescription: String,
        initialFiles: Map<String, String>
    ) {
        val currentFilesMap = initialFiles.toMutableMap()
        var needMoreRounds = 0

        fun executeAllInOneStep() {
            val filesBlock = makeFilesContentBlock(currentFilesMap)
            val prompt = """
            You are updating the Android app "$appName".
            Goal: "$appDescription"

            Here are the files you can currently see (full content):
            $filesBlock

            Task: Analyze the goal and provide the necessary code modifications. 
            You MUST respond with a single JSON object matching the required schema:
            {
              "filesToWrite": [ { "filePath": "...", "fileContent": "..." } ],
              "filesToDelete": [ "..." ],
              "requestMoreFiles": [ "..." ],
              "conclusion": "..."
            }

            Important Rules:
1. Put ALL your modified or new files in "filesToWrite".

            2. If you absolutely need to see the content of other existing files first, list their paths in "requestMoreFiles".
            3. Do NOT include prose outside the JSON.
            4. In "conclusion" write a short plain-text summary for the user (3-6 sentences): what you
               changed, in which files, and anything worth testing. Do not leave it empty when you
               changed files. Use an empty string only while you are still requesting more files.
            """.trimIndent()

            conversation.addUserMessage(prompt)
            logPromptToUi("Generation Prompt", prompt) // <-- ADDED LOGGING HERE
            logViaBridge("Sending files to AI for processing (Round ${needMoreRounds + 1})...\n")

            geminiHelper.sendApiRequest(
                contents = conversation.getContentsForApi(),
                callback = { response: JSONObject ->
                    try {
                        val responseText = geminiHelper.extractTextFromApiResponse(response)
                        conversation.addModelMessage(responseText)

                        val (mods, requestedMore) = parseFileModificationsWithRequestMore(responseText)
                        val fm = mods ?: FileModifications(emptyMap(), emptyList(), null)

                        if (!requestedMore.isNullOrEmpty() && needMoreRounds < MAX_NEED_MORE_ROUNDS) {
                            val proj = bridge.currentProjectDirBridge
                            if (proj != null) {
                                var added = 0
                                for (p in requestedMore) {
                                    if (!currentFilesMap.containsKey(p)) {
                                        val f = File(proj, p)
                                        currentFilesMap[p] = if (f.exists() && f.isFile) {
                                            try { FileReader(f).use { it.readText() } }
                                            catch (e: IOException) { "// File read error" }
                                        } else {
                                            "// File not found. Create it if needed."
                                        }
                                        added++
                                    }
                                }
                                needMoreRounds++
                                logViaBridge("Loaded $added additional requested files. Re-prompting AI...\n")
                                executeAllInOneStep()
                                return@sendApiRequest
                            }
                        }

                        // Clean structured logging for UI
                        if (fm.filesToWrite.isNotEmpty()) {
                            logViaBridge("🤖 AI generated/modified ${fm.filesToWrite.size} file(s):\n" +
                                    fm.filesToWrite.keys.joinToString("\n") { "  📄 $it" } + "\n\n")
                        }
                        if (fm.filesToDelete.isNotEmpty()) {
                            logViaBridge("🗑️ AI deleted ${fm.filesToDelete.size} file(s):\n" +
                                    fm.filesToDelete.joinToString("\n") { "  ❌ $it" } + "\n\n")
                        }

                        applyCodeChangesAndOrGetSummary(fm)

                    } catch (e: Exception) {
                        handleError("Error during generation step: ${e.message}", e)
                    }
                },
                responseSchemaJson = geminiHelper.getFileModificationsSchema(),
                responseMimeTypeOverride = "application/json",
                modelIdentifierOverride = null
            )
        }

        executeAllInOneStep()
    }

    private fun generateInitialFilesFromDescription(appName: String, appDescription: String, attempt: Int) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            handleError("Project directory is null before initial generation.", null)
            return
        }
        val existingFiles = ProjectFileUtils.scanProjectFiles(projectDir)
        val existingListText = if (existingFiles.isNotEmpty()) existingFiles.joinToString("\n") { "- $it" } else "(no existing files)"

        val prompt = """
      You are creating/updating an Android application named "$appName".
      Goal: "$appDescription"

      The project may be minimal or empty. Here is the current file list (if any):
      $existingListText

      Produce the essential set of files to implement the goal. Update existing files when appropriate and create missing ones.
      Respond ONLY as JSON with key "filesToWrite": an array of objects, each having:
- "filePath": relative path under project root (e.g., "app/src/main/AndroidManifest.xml")

- "fileContent": the full content of that file


      Constraints:
- Return at least 1 file.

- Use Kotlin if source code is required.

- Do not include prose.

    """.trimIndent()

        conversation.addUserMessage(prompt)
        logPromptToUi("Initial-generation prompt", prompt) // <-- ADDED LOGGING HERE

        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response: JSONObject ->
                try {
                    val txt = geminiHelper.extractTextFromApiResponse(response)
                    conversation.addModelMessage(txt)

                    val filesMap = geminiHelper.parseMinimalFilesResponse(txt)
                    if (!filesMap.isNullOrEmpty()) {
                        logViaBridge("🤖 AI proposed ${filesMap.size} initial file(s):\n" +
                                filesMap.keys.joinToString("\n") { "  📄 $it" } + "\n\n")

                        applyCodeChangesAndOrGetSummary(FileModifications(filesMap, emptyList(), null))
                    } else {
                        if (attempt < MAX_FALLBACK_RETRIES) {
                            logViaBridge("⚠️ AI returned no files for initial generation. Retrying...\n")
                            generateInitialFilesFromDescription(appName, appDescription, attempt + 1)
                        } else {
                            handleError("AI did not produce any files to write after retries.", null)
                        }
                    }
                } catch (e: Exception) {
                    handleError("Error during initial file generation: ${e.message}", e)
                }
            },
            responseSchemaJson = geminiHelper.getMinimalFilesSchema(),
            responseMimeTypeOverride = "application/json",
            modelIdentifierOverride = null
        )
    }

    private fun applyCodeChangesAndOrGetSummary(modifications: FileModifications) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            handleError("Project directory is null before applying changes.", null)
            return
        }

        val filteredFilesToDelete = modifications.filesToDelete.filterNot { filePath -> File(filePath).name in PROTECTED_FILES }

        if (modifications.filesToWrite.isNotEmpty() || filteredFilesToDelete.isNotEmpty()) {
            logViaBridge("AI Workflow: Applying code changes and deletions...\n")
            ProjectFileUtils.processFileChangesAndDeletions(
                projectDir, modifications.filesToWrite, filteredFilesToDelete, directLogAppender
            ) { writeSuccessCount, writeErrorCount, deleteSuccessCount, deleteErrorCount ->
                bridge.runOnUiThreadBridge {
                    var summary = ""
                    var changesApplied = false
                    if (writeErrorCount > 0 || deleteErrorCount > 0) {
                        summary += "⚠️ Some file operations failed. Writes (Success: $writeSuccessCount, Error: $writeErrorCount), Deletes (Success: $deleteSuccessCount, Error: $deleteErrorCount).\n"
                    }
                    if (writeSuccessCount > 0) { summary += "✅ Successfully applied $writeSuccessCount file content changes.\n"; changesApplied = true }
                    if (deleteSuccessCount > 0) { summary += "✅ Successfully deleted $deleteSuccessCount files.\n"; changesApplied = true }
                    anyChangesApplied = anyChangesApplied || changesApplied

                    // These changes are not a stored version until they build AND install.
                    if (changesApplied) AppVersionStore.markUnverified(projectDir)

                    if (summary.isNotBlank()) logViaBridge(summary)

                    val warning = if (writeErrorCount > 0 || deleteErrorCount > 0) {
                        "\n\n⚠️ Some file operations failed (see the Log tab)."
                    } else ""
                    finishAndMaybeBuild(conclusionFor(modifications) + warning)
                }
            }
        } else {
            logViaBridge("AI did not provide any file changes or deletions.\n")
            finishAndMaybeBuild(conclusionFor(modifications))
        }
    }

    /** The AI's own conclusion if it gave one, otherwise a plain list of what was changed. */
    private fun conclusionFor(mods: FileModifications): String {
        val fromAi = mods.conclusion?.trim()
        if (!fromAi.isNullOrBlank()) return fromAi

        val sb = StringBuilder()
        if (mods.filesToWrite.isNotEmpty()) {
            sb.append("Created or modified ${mods.filesToWrite.size} file(s):\n")
            mods.filesToWrite.keys.forEach { sb.append("• ").append(it).append('\n') }
        }
        if (mods.filesToDelete.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Deleted ${mods.filesToDelete.size} file(s):\n")
            mods.filesToDelete.forEach { sb.append("• ").append(it).append('\n') }
        }
        if (sb.isEmpty()) sb.append("The AI did not propose any changes.")
        return sb.toString().trim()
    }

    private fun finishAndMaybeBuild(finalSummary: String?) {
        bridge.displayAiConclusionBridge(finalSummary)
        bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)

        val projectDir = bridge.currentProjectDirBridge
        val okToAutoBuild = !encounteredError && anyChangesApplied && projectDir != null

        AiForegroundService.stop(bridge.getContextBridge())

        if (autoBuildAfterApply && okToAutoBuild && !hasTriggeredAutoBuild) {
            hasTriggeredAutoBuild = true
            bridge.triggerBuildBridge(projectDir!!, runAfterBuild = autoRunAfterBuild)
        } else {
            if (!autoBuildAfterApply) logViaBridge("ℹ️ Auto-build disabled; waiting for user action.\n")
            if (encounteredError) logViaBridge("❌ Skipping auto-build due to an earlier error in the AI flow.\n")
            if (!anyChangesApplied) logViaBridge("ℹ️ Skipping auto-build because no code changes were applied.\n")
        }
    }

    fun handleBuildResult(success: Boolean, rawBuildOutput: String) {
        if (success) {
            logViaBridge("✅ Build Successful! Workflow complete.\n")
            bridge.updateStateBridge(AiWorkflowState.IDLE)
            return
        }

        val cleanError = ProjectFileUtils.extractRelevantCompilerErrors(rawBuildOutput)

        logViaBridge("Build failed. Filtered error report:\n```\n$cleanError\n```\nAsking AI to fix the issue...\n")
        bridge.updateStateBridge(AiWorkflowState.ANALYZING_BUILD_ERROR)

        val prompt = """
        The previous code generated a build error. Here are the specific compiler errors:
        ```
        $cleanError
        ```
        
        Task: Fix these specific compiler errors based on the code you just generated.
        You MUST respond with a single JSON object matching the required schema.
        Put the corrected files in the "filesToWrite" array. Do not include prose outside the JSON.
        """.trimIndent()

        conversation.addUserMessage(prompt)
        logPromptToUi("Build-fix prompt", prompt) // <-- ADDED LOGGING HERE

        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response: JSONObject ->
                try {
                    val responseText = geminiHelper.extractTextFromApiResponse(response)
                    conversation.addModelMessage(responseText)

                    val (mods, _) = parseFileModificationsWithRequestMore(responseText)
                    val fm = mods ?: FileModifications(emptyMap(), emptyList(), null)

                    if (fm.filesToWrite.isEmpty()) {
                        finishAndMaybeBuild("AI analyzed the build error but did not suggest any file modifications.")
                    } else {
                        logViaBridge("🔧 AI applied bug fixes to ${fm.filesToWrite.size} file(s):\n" +
                                fm.filesToWrite.keys.joinToString("\n") { "  📄 $it" } + "\n\n")

                        applyCodeChangesAndOrGetSummary(fm)
                    }
                } catch (e: JSONException) {
                    handleError("Failed to parse fix attempt: ${e.message}", e)
                }
            },
            modelIdentifierOverride = null,
            responseSchemaJson = geminiHelper.getFileModificationsSchema(),
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun handleError(message: String, e: Exception?) {
        encounteredError = true
        Log.e("AI_PIPELINE", "handleError: $message", e)
        bridge.handleErrorBridge(message, e)
        AiForegroundService.stop(bridge.getContextBridge())
    }
}