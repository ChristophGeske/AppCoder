package com.itsaky.androidide.dialogs

import android.util.Log
import com.itsaky.androidide.services.AiForegroundService
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.max

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

        private const val MAX_FALLBACK_RETRIES = 2
        private const val MAX_SUMMARY_RETRIES = 1
        private const val RAW_LOG_SNIPPET = 2048

        // Batch sizing defaults
        private const val BUDGET_MINI_CHARS = 90_000
        private const val BUDGET_BIG_CHARS = 100_000

        private const val FILES_PER_BATCH_ALL = Int.MAX_VALUE
        private const val FILES_PER_BATCH_10 = 10
        private const val FILES_PER_BATCH_5 = 5
        private const val FILES_PER_BATCH_1 = 1

        private const val SELECTION_TOTAL_PREVIEW_BUDGET = 80_000
        private const val SELECTION_PER_FILE_PREVIEW = 800

        private const val MAX_EXTRA_WRITES_ACCEPTED = 24
        private const val MAX_CONSECUTIVE_ALL_FAILURES = 3
    }

    private enum class BatchMode { ALL, TEN, FIVE, ONE }

    private val conversation = GeminiConversation()

    // summarization state
    private var allProjectFiles = listOf<String>()
    private var fileSummaries = mapOf<String, String>()

    // selection / generation
    private val selectedFilesForModification = mutableListOf<String>()
    private var lastAppNameForFallback: String = ""
    private var lastAppDescriptionForFallback: String = ""
    private var lastFileContextForFallback: String = ""

    // flags
    private var autoBuildAfterApply = false
    private var autoRunAfterBuild = false
    private var hasTriggeredAutoBuild = false
    private var encounteredError = false
    private var anyChangesApplied = false
    private var extraWritesAcceptedCount = 0

    private fun logViaBridge(message: String) = bridge.appendToLogBridge(message)

    private fun getFastModelForSummarization(): String? {
        val current = geminiHelper.currentModelIdentifier
        return when {
            current.startsWith("gpt-5", true) -> "gpt-5-mini"
            current.startsWith("gemini-2.5-pro", true) -> "gemini-2.5-flash"
            else -> null
        }
    }
    private fun modelForStructuredSteps(): String? {
        val current = geminiHelper.currentModelIdentifier
        return when {
            current.equals("gpt-5", ignoreCase = true) -> "gpt-5-mini"
            current.startsWith("gemini-2.5-pro", ignoreCase = true) -> "gemini-2.5-flash"
            else -> null
        }
    }

    // Entry point
    fun startModificationFlow(
        appName: String,
        appDescription: String,
        projectDir: File,
        autoBuild: Boolean = false,
        autoRun: Boolean = false
    ) {
        val provider = if (geminiHelper.currentModelIdentifier.startsWith("gpt-", ignoreCase = true)) "OpenAI" else "Gemini"
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
        extraWritesAcceptedCount = 0

        lastAppNameForFallback = appName
        lastAppDescriptionForFallback = appDescription

        AiForegroundService.start(bridge.getContextBridge(), "Analyzing project for $appName")

        allProjectFiles = ProjectFileUtils.scanProjectFiles(projectDir)

        if (allProjectFiles.isEmpty()) {
            logViaBridge("Project is empty. Asking AI to generate initial files.\n")
            bridge.updateStateBridge(AiWorkflowState.CREATING_PROJECT_TEMPLATE)
            generateInitialFilesFromDescription(appName, appDescription, attempt = 0)
        } else {
            logViaBridge("Found ${allProjectFiles.size} files. Requesting summaries from a fast LLM...\n")
            bridge.updateStateBridge(AiWorkflowState.SUMMARIZING_FILES)
            requestFileSummaries()
        }
    }

    // --- STEP 1: Summarization ---
    private fun requestFileSummaries() {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            handleError("Project directory is null before summarization.", null)
            return
        }
        val sb = StringBuilder("Generate a concise, one-sentence summary for each file. Respond ONLY with a JSON object matching the provided schema.\n\n")
        for (path in allProjectFiles) {
            try {
                val content = FileReader(File(projectDir, path)).use { it.readText() }
                sb.append("--- FILE: $path ---\n```\n$content\n```\n\n")
            } catch (_: IOException) {
                logViaBridge("⚠️ Could not read file $path for summarization. Skipping.\n")
            }
        }
        conversation.addUserMessage(sb.toString())
        val overrideModel = getFastModelForSummarization()

        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = ::handleFileSummariesResponse,
            modelIdentifierOverride = overrideModel,
            responseSchemaJson = geminiHelper.getSummariesSchema()
        )
    }

    private fun handleFileSummariesResponse(response: JSONObject) {
        val responseText = geminiHelper.extractTextFromApiResponse(response)
        try {
            val root = JSONObject(responseText).unwrapDataIfPresent()
            val arr = root.getJSONArray("file_summaries")
            val summariesMap = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val path = item.optString("file_path", "").ifBlank { item.optString("filePath", "") }
                val summary = item.optString("summary", "")
                if (path.isNotBlank()) summariesMap[path] = summary
            }
            this.fileSummaries = summariesMap
            logViaBridge("✅ Summaries received for ${summariesMap.size} files.\n")
            conversation.addModelMessage(responseText)

            bridge.updateStateBridge(AiWorkflowState.SELECTING_FILES)
            requestFileSelectionFromSummaries()
        } catch (e: Exception) {
            handleError("Failed to parse file summaries from LLM: ${e.message}", e)
        }
    }

    // --- STEP 2: Selection based on summaries ---
    private fun requestFileSelectionFromSummaries() {
        val prompt = buildString {
            append("My goal is to implement the following feature in the '$lastAppNameForFallback' app:\n\"$lastAppDescriptionForFallback\"\n\n")
            append("Here is a list of project files and their concise summaries:\n")
            fileSummaries.forEach { (path, summary) -> append("- `$path`: $summary\n") }
            append("\nBased on my goal, which files do you need to see the full content of to begin? Respond ONLY with a JSON array of file paths.\n")
        }
        conversation.addUserMessage(prompt)

        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response ->
                try {
                    val responseText = geminiHelper.extractTextFromApiResponse(response)
                    val jsonArray = JSONArray(geminiHelper.extractJsonArrayFromText(responseText))
                    val selected = List(jsonArray.length()) { jsonArray.getString(it) }.filter { it.isNotBlank() }

                    if (selected.isEmpty()) {
                        logViaBridge("AI did not select any files. Will try to generate from description.\n")
                        generateInitialFilesFromDescription(lastAppNameForFallback, lastAppDescriptionForFallback, 0)
                    } else {
                        logViaBridge("AI selected ${selected.size} files. Proceeding to generate code...\n")
                        selectedFilesForModification.clear()
                        selectedFilesForModification.addAll(selected)
                        loadSelectedFilesAndGenerateInBatches(lastAppNameForFallback, lastAppDescriptionForFallback)
                    }
                    conversation.addModelMessage(responseText)
                } catch (e: Exception) {
                    handleError("Failed to parse file selection from LLM: ${e.message}", e)
                }
            },
            responseMimeTypeOverride = "application/json"
        )
    }

    // --- Loading selected files and batching ---
    private fun loadSelectedFilesAndGenerateInBatches(appName: String, appDescription: String) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            handleError("Project directory is null before batching generation.", null)
            return
        }
        bridge.updateStateBridge(AiWorkflowState.GENERATING_CODE)
        logViaBridge("Loading ${selectedFilesForModification.size} selected files for batched code generation...\n")
        val fileContentsMap = mutableMapOf<String, String>()

        for (filePath in selectedFilesForModification) {
            val f = File(projectDir, filePath)
            if (!f.exists() || !f.isFile) {
                logViaBridge("Note: File '$filePath' not found. AI will be asked to create it.\n")
                fileContentsMap[filePath] = "// File: $filePath (This file is new or was not found. Please provide its complete content.)"
            } else {
                try {
                    fileContentsMap[filePath] = FileReader(f).use { it.readText() }
                } catch (e: IOException) {
                    logViaBridge("⚠️ Error reading file $filePath: ${e.message}. AI will be asked to regenerate.\n")
                    fileContentsMap[filePath] = "// File: $filePath (Error reading existing content. Please regenerate based on its intended role.)"
                }
            }
        }

        if (fileContentsMap.isEmpty()) {
            logViaBridge("No valid files were loaded. Attempting to generate from description...\n")
            generateInitialFilesFromDescription(appName, appDescription, 0)
            return
        }

        val id = geminiHelper.currentModelIdentifier.lowercase()
        val batchCharBudget = if (id.contains("gpt-5-mini") || id.contains("gpt-5-nano")) BUDGET_MINI_CHARS else BUDGET_BIG_CHARS

        generateInBatches(appName, appDescription, fileContentsMap, batchCharBudget)
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
            - Keep this response to a practical subset (up to 8 files). Prioritize: Gradle/build files, AndroidManifest.xml, entry Activity/Compose file, layout(s), values/strings.xml.
            - Use Kotlin if source code is required.
        """.trimIndent()

        val conv = GeminiConversation().apply { addUserMessage(prompt) }
        val overrideModel = modelForStructuredSteps()

        geminiHelper.sendApiRequest(
            contents = conv.getContentsForApi(),
            callback = { response ->
                try {
                    val txt = geminiHelper.extractTextFromApiResponse(response)
                    Log.i(TAG, "Initial generation JSON (first 512 chars): ${txt.take(512)}")
                    val filesMap = geminiHelper.parseMinimalFilesResponse(txt)
                    if (!filesMap.isNullOrEmpty()) {
                        logViaBridge("AI proposed ${filesMap.size} initial file(s) from description.\n")
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
            modelIdentifierOverride = overrideModel
        )
    }

    private fun estimateCharsForFile(path: String, content: String): Int = path.length + content.length + 128

    private fun buildBatchesBySize(
        paths: List<String>,
        files: Map<String, String>,
        maxCharsPerBatch: Int,
        maxFilesPerBatch: Int
    ): ArrayDeque<List<String>> {
        val queue = ArrayDeque<List<String>>()
        var current = mutableListOf<String>()
        var size = 0
        for (p in paths) {
            val c = files[p] ?: ""
            val add = estimateCharsForFile(p, c)
            val wouldOverflow = (size + add > maxCharsPerBatch) || (current.size + 1 > maxFilesPerBatch)
            if (wouldOverflow && current.isNotEmpty()) {
                queue.addLast(current.toList())
                current = mutableListOf()
                size = 0
            }
            current.add(p)
            size += add
        }
        if (current.isNotEmpty()) queue.addLast(current.toList())
        return queue
    }

    private fun makePendingForMode(
        remaining: List<String>,
        mode: BatchMode,
        filesMap: Map<String, String>,
        maxCharsPerBatch: Int
    ): ArrayDeque<List<String>> {
        return when (mode) {
            BatchMode.ALL -> ArrayDeque<List<String>>().apply { if (remaining.isNotEmpty()) addLast(remaining.toList()) }
            BatchMode.TEN -> buildBatchesBySize(remaining, filesMap, maxCharsPerBatch, FILES_PER_BATCH_10)
            BatchMode.FIVE -> buildBatchesBySize(remaining, filesMap, maxCharsPerBatch, FILES_PER_BATCH_5)
            BatchMode.ONE -> buildBatchesBySize(remaining, filesMap, maxCharsPerBatch, FILES_PER_BATCH_1)
        }
    }

    // Parse modifications + optional requestMoreFiles
    private fun parseFileModificationsWithRequestMore(jsonText: String): Pair<FileModifications?, List<String>?> {
        try {
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

            val conclusion = root.optStringByKeys("conclusion", "summary", "conclusionText")?.takeIf { it.isNotBlank() }
            val requestMore = mutableListOf<String>()
            root.optJSONArrayByKeys("requestMoreFiles", "request_more_files", "request_more_files_paths")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (!s.isNullOrBlank()) requestMore.add(s)
                }
            }

            val fm = FileModifications(filesToWrite = filesMap, filesToDelete = filesToDelete, conclusion = conclusion)
            return fm to if (requestMore.isNotEmpty()) requestMore else null
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing FileModifications JSON: '$jsonText'. Error: ${e.message}", e)
            return null to null
        }
    }

    private fun generateInBatches(
        appName: String,
        appDescription: String,
        fileContentsMap: Map<String, String>,
        maxCharsPerBatch: Int
    ) {
        val overrideModel = modelForStructuredSteps()
        val aggregatedFiles = linkedMapOf<String, String>()
        val remainingPaths = fileContentsMap.keys.toMutableList()

        var currentMode = BatchMode.ALL
        var consecutiveAllFailures = 0
        var singlesAllowed = true
        var pendingBatches = makePendingForMode(remainingPaths, currentMode, fileContentsMap, maxCharsPerBatch)

        fun promptForBatch(paths: List<String>): String {
            val filesContentText = buildString {
                paths.forEach { path ->
                    val content = fileContentsMap[path] ?: ""
                    append("FILE: $path\n```\n$content\n```\n\n")
                }
            }
            lastFileContextForFallback = filesContentText
            return """
                You are updating an Android app called "$appName".
                Goal: "$appDescription"

                Primary scope for THIS batch is the files listed below:
                $filesContentText

                Respond with a single JSON object following the schema:
                {
                  "filesToWrite": [
                    { "filePath": "<one of the listed paths OR a small number of additional necessary files>", "fileContent": "<full content>" }
                  ],
                  "unchanged": [
                    "<every listed path you did NOT change>"
                  ],
                  "filesToDelete": [ "<optional file paths to delete>" ],
                  "requestMoreFiles": [ "<optional additional file paths you need to see to continue; set to [] or omit if done>" ],
                  "conclusion": "<optional final summary when the entire task is complete>"
                }

                Rules:
                - Every file from the listed batch MUST appear exactly once: either in filesToWrite or in unchanged.
                - You MAY include a small number of additional existing files in filesToWrite if they are necessary to make the change work.
                - Keep additional files minimal and relevant. Avoid unrelated or large refactors.
                - Do NOT include prose outside the JSON.
            """.trimIndent()
        }

        fun applyAndAccount(requested: List<String>, responseText: String): Pair<Boolean, Set<String>> {
            val (mods, _) = parseFileModificationsWithRequestMore(responseText)
            if (mods == null) return false to emptySet()

            // Accept in-scope writes
            val inScopeWrites = mods.filesToWrite.filterKeys { it in requested }
            if (inScopeWrites.isNotEmpty()) aggregatedFiles.putAll(inScopeWrites)

            // Accept limited out-of-scope writes
            val outOfScopeWrites = mods.filesToWrite.filterKeys { it !in requested }
            if (outOfScopeWrites.isNotEmpty()) {
                val remainingAllowance = MAX_EXTRA_WRITES_ACCEPTED - extraWritesAcceptedCount
                if (remainingAllowance > 0) {
                    val accepted = outOfScopeWrites.entries.take(remainingAllowance)
                    accepted.forEach { (k, v) -> aggregatedFiles[k] = v }
                    extraWritesAcceptedCount += accepted.size
                    val dropped = outOfScopeWrites.size - accepted.size
                    if (accepted.isNotEmpty()) {
                        logViaBridge("ℹ️ Accepted ${accepted.size} additional out-of-batch file(s) to complete the change.\n")
                    }
                    if (dropped > 0) {
                        logViaBridge("⚠️ Dropped $dropped extra out-of-batch file(s) due to allowance limit ($MAX_EXTRA_WRITES_ACCEPTED).\n")
                    }
                } else {
                    logViaBridge("⚠️ Skipped ${outOfScopeWrites.size} extra out-of-batch file(s) (allowance exhausted).\n")
                }
            }

            // Determine accounted set
            val accountedSet = mutableSetOf<String>()
            accountedSet.addAll(mods.filesToWrite.keys)
            accountedSet.addAll(mods.filesToDelete)

            // also include unchanged from responseText if present
            try {
                val root = JSONObject(responseText).unwrapDataIfPresent()
                root.optJSONArrayByKeys("unchanged")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (!s.isNullOrBlank()) accountedSet.add(s)
                    }
                }
            } catch (_: Exception) { /* ignore */ }

            val accountedInRequested = requested.filter { it in accountedSet }.toSet()
            val fullSuccess = accountedInRequested.size == requested.size
            return fullSuccess to accountedInRequested
        }

        fun rebuildPending() {
            pendingBatches = makePendingForMode(remainingPaths, currentMode, fileContentsMap, maxCharsPerBatch)
        }

        fun processNextBatch() {
            if (remainingPaths.isEmpty()) {
                val modifications = FileModifications(aggregatedFiles, emptyList(), null)
                if (aggregatedFiles.isEmpty()) logViaBridge("No files were generated/changed in batched flow.\n")
                else logViaBridge("Batched generation produced ${aggregatedFiles.size} file(s).\n")
                applyCodeChangesAndOrGetSummary(modifications)
                return
            }
            if (pendingBatches.isEmpty()) {
                rebuildPending()
                if (pendingBatches.isEmpty()) {
                    val modifications = FileModifications(aggregatedFiles, emptyList(), null)
                    applyCodeChangesAndOrGetSummary(modifications)
                    return
                }
            }

            val paths = pendingBatches.removeFirst()
            conversation.addUserMessage(promptForBatch(paths))
            logViaBridge("Generating batch (${paths.size} file(s)) [mode=$currentMode, remain=${remainingPaths.size}]...\n")

            geminiHelper.sendApiRequest(
                contents = conversation.getContentsForApi(),
                callback = { response ->
                    try {
                        val responseText = geminiHelper.extractTextFromApiResponse(response)
                        if (responseText.isBlank()) {
                            if (currentMode == BatchMode.ALL) {
                                consecutiveAllFailures++
                                if (consecutiveAllFailures >= MAX_CONSECUTIVE_ALL_FAILURES) singlesAllowed = false
                                currentMode = BatchMode.TEN
                            } else if (currentMode == BatchMode.TEN) currentMode = BatchMode.FIVE
                            else if (currentMode == BatchMode.FIVE) currentMode = if (singlesAllowed) BatchMode.ONE else BatchMode.ALL
                            else currentMode = BatchMode.ALL
                            rebuildPending()
                            processNextBatch()
                            return@sendApiRequest
                        }

                        val (fullSuccess, accounted) = applyAndAccount(paths, responseText)
                        if (accounted.isNotEmpty()) remainingPaths.removeAll(accounted)

                        // handle requestMoreFiles
                        val (_, requestMore) = parseFileModificationsWithRequestMore(responseText)
                        if (requestMore != null && requestMore.isNotEmpty()) {
                            logViaBridge("ℹ️ LLM requested additional ${requestMore.size} files to continue.\n")
                            val projectDir = bridge.currentProjectDirBridge
                            if (projectDir != null) {
                                val newFilesMap = mutableMapOf<String, String>()
                                for (p in requestMore) {
                                    val f = File(projectDir, p)
                                    if (!f.exists() || !f.isFile) {
                                        logViaBridge("Note: Requested additional file '$p' not found; will request LLM to create it if needed.\n")
                                        newFilesMap[p] = "// File: $p (This file is new or was not found. Please provide its complete content.)"
                                    } else {
                                        try {
                                            newFilesMap[p] = FileReader(f).use { it.readText() }
                                        } catch (e: IOException) {
                                            logViaBridge("⚠️ Error reading requested additional file $p: ${e.message}. Asking LLM to regenerate.\n")
                                            newFilesMap[p] = "// File: $p (Error reading existing content. Please regenerate.)"
                                        }
                                    }
                                }
                                // Merge for next run
                                val merged = linkedMapOf<String, String>()
                                merged.putAll(fileContentsMap)
                                merged.putAll(newFilesMap)
                                for (nf in newFilesMap.keys) if (!remainingPaths.contains(nf)) remainingPaths.add(nf)
                                generateInBatches(appName, appDescription, merged, maxCharsPerBatch)
                                return@sendApiRequest
                            } else {
                                logViaBridge("⚠️ Project dir null while attempting to load additional requested files.\n")
                            }
                        }

                        // adjust batch mode
                        if (fullSuccess) {
                            if (currentMode == BatchMode.ALL) consecutiveAllFailures = 0
                            else {
                                currentMode = BatchMode.ALL
                                rebuildPending()
                            }
                        } else {
                            if (currentMode == BatchMode.ALL) {
                                consecutiveAllFailures++
                                if (consecutiveAllFailures >= MAX_CONSECUTIVE_ALL_FAILURES) singlesAllowed = false
                                currentMode = BatchMode.TEN
                            } else if (currentMode == BatchMode.TEN) currentMode = BatchMode.FIVE
                            else if (currentMode == BatchMode.FIVE) currentMode = if (singlesAllowed) BatchMode.ONE else BatchMode.ALL
                            else currentMode = BatchMode.ALL
                            rebuildPending()
                        }
                        processNextBatch()
                    } catch (e: Exception) {
                        encounteredError = true
                        logViaBridge("⚠️ Error processing batch response: ${e.message}. Switching mode and continuing.\n")
                        if (currentMode == BatchMode.ALL) {
                            consecutiveAllFailures++
                            if (consecutiveAllFailures >= MAX_CONSECUTIVE_ALL_FAILURES) singlesAllowed = false
                            currentMode = BatchMode.TEN
                        } else if (currentMode == BatchMode.TEN) currentMode = BatchMode.FIVE
                        else if (currentMode == BatchMode.FIVE) currentMode = if (singlesAllowed) BatchMode.ONE else BatchMode.ALL
                        else currentMode = BatchMode.ALL
                        rebuildPending()
                        processNextBatch()
                    }
                },
                responseSchemaJson = geminiHelper.getFileModificationsSchema(),
                responseMimeTypeOverride = "application/json",
                modelIdentifierOverride = overrideModel
            )
        }

        processNextBatch()
    }

    // --- Apply changes and summary ---
    private fun applyCodeChangesAndOrGetSummary(modifications: FileModifications) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            handleError("Project directory is null before applying changes.", null)
            return
        }

        val filteredFilesToDelete = modifications.filesToDelete.filterNot { filePath -> File(filePath).name == PROTECTED_VERSION_FILE }
        if (modifications.filesToDelete.size != filteredFilesToDelete.size) {
            logViaBridge("Note: Protected system file '$PROTECTED_VERSION_FILE' was excluded from deletion.\n")
        }

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
                    logViaBridge(summary.ifBlank { "No specific file operations were logged as successful.\n" })

                    if (modifications.conclusion.isNullOrBlank()) {
                        if (changesApplied) {
                            logViaBridge("Initial conclusion missing. Requesting summary generation from AI...\n")
                            requestSummaryFromAI(modifications, attempt = 0)
                        } else {
                            finishAndMaybeBuild("No specific code changes were made, and no summary was provided by the AI.")
                        }
                    } else {
                        finishAndMaybeBuild(modifications.conclusion)
                    }
                }
            }
        } else {
            logViaBridge("AI did not provide any file changes or deletions.\n")
            if (modifications.conclusion.isNullOrBlank()) {
                logViaBridge("Attempting to generate a summary as no changes and no initial conclusion.\n")
                requestSummaryFromAI(modifications, attempt = 0)
            } else {
                finishAndMaybeBuild(modifications.conclusion)
            }
        }
    }

    private fun requestSummaryFromAI(currentModifications: FileModifications, attempt: Int) {
        val generatedFiles = currentModifications.filesToWrite
        val deletedFiles = currentModifications.filesToDelete
        val originalConclusion = currentModifications.conclusion

        if (generatedFiles.isEmpty() && deletedFiles.isEmpty() && originalConclusion.isNullOrBlank()) {
            logViaBridge("No code changes were made, providing a default summary for this specific case.\n")
            finishAndMaybeBuild("No specific code changes or deletions were performed by the AI.")
            return
        }
        if (!originalConclusion.isNullOrBlank()) {
            finishAndMaybeBuild(originalConclusion)
            return
        }

        if (attempt == 0) logViaBridge("Attempting to generate a summary for the applied changes via dedicated AI call...\n")
        else logViaBridge("Retrying summary generation (attempt $attempt)...\n")
        bridge.updateStateBridge(AiWorkflowState.GENERATING_SUMMARY)

        val changesDescription = buildString {
            if (generatedFiles.isNotEmpty()) {
                append("The following files were written or updated:\n")
                generatedFiles.keys.forEach { path -> append("- ${path.takeLast(50)}\n") }
            }
            if (deletedFiles.isNotEmpty()) {
                append("The following files were deleted:\n")
                deletedFiles.forEach { path -> append("- ${path.takeLast(50)}\n") }
            }
        }.ifEmpty { "No specific file content or deletion details to list." }

        val summaryPrompt = """
            Based on the following code modifications for the app "$lastAppNameForFallback" (Goal: "$lastAppDescriptionForFallback"), please provide a concise, user-friendly summary.

            Modifications Overview:
            $changesDescription

            Your response MUST be a single JSON object with one REQUIRED key: "summary" (string).
        """.trimIndent()

        val summaryConversation = GeminiConversation().apply {
            addUserMessage(summaryPrompt)
            if (attempt > 0) addUserMessage("Retry ($attempt/$MAX_SUMMARY_RETRIES): Return ONLY a JSON object with a 'summary' string. No prose.")
        }

        val overrideModel = modelForStructuredSteps()

        geminiHelper.sendApiRequest(
            contents = summaryConversation.getContentsForApi(),
            callback = { response ->
                var finalSummaryToDisplay: String? = originalConclusion
                try {
                    val summaryResponseJsonText = geminiHelper.extractTextFromApiResponse(response)
                    Log.i(TAG, "Raw summary generation response from AI: ${summaryResponseJsonText.take(512)}")
                    if (summaryResponseJsonText.isBlank()) {
                        val raw = response.toString()
                        logViaBridge("⚠️ Empty summary response. Raw snippet:\n${raw.take(RAW_LOG_SNIPPET)}\n\n")
                        if (attempt < MAX_SUMMARY_RETRIES) {
                            requestSummaryFromAI(currentModifications, attempt + 1); return@sendApiRequest
                        }
                        finalSummaryToDisplay = originalConclusion ?: "Summary generation attempt failed (empty response)."
                    } else {
                        val newSummary = geminiHelper.parseSummaryResponse(summaryResponseJsonText)
                        if (!newSummary.isNullOrBlank()) {
                            logViaBridge("✅ AI generated a summary successfully.\n")
                            finalSummaryToDisplay = newSummary
                        } else {
                            if (attempt < MAX_SUMMARY_RETRIES) {
                                logViaBridge("⚠️ AI failed to generate a valid summary string. Retrying...\n")
                                requestSummaryFromAI(currentModifications, attempt + 1); return@sendApiRequest
                            }
                            finalSummaryToDisplay = originalConclusion ?: "AI could not provide a summary for the changes."
                        }
                    }
                } catch (e: Exception) {
                    encounteredError = true
                    logViaBridge("⚠️ Error processing summary response: ${e.message}\n")
                    if (attempt < MAX_SUMMARY_RETRIES) {
                        requestSummaryFromAI(currentModifications, attempt + 1); return@sendApiRequest
                    }
                    bridge.handleErrorBridge("Failed during AI summary generation after retries: ${e.message}", e)
                    finalSummaryToDisplay = originalConclusion ?: "Error during summary generation."
                } finally {
                    finishAndMaybeBuild(finalSummaryToDisplay)
                }
            },
            responseSchemaJson = geminiHelper.getSummaryOnlySchema(),
            responseMimeTypeOverride = "application/json",
            modelIdentifierOverride = overrideModel
        )
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
            if (encounteredError) logViaBridge("✖️ Skipping auto-build due to an earlier error in the AI flow.\n")
            if (!anyChangesApplied) logViaBridge("ℹ️ Skipping auto-build because no code changes were applied.\n")
            if (projectDir == null) logViaBridge("✖️ Skipping auto-build: projectDir is null.\n")
        }
    }

    // --- Build & Fix loop ---
    fun handleBuildResult(success: Boolean, buildOutput: String) {
        if (success) {
            logViaBridge("🎉 Build Successful! Workflow complete.\n")
            bridge.updateStateBridge(AiWorkflowState.IDLE)
            return
        }

        logViaBridge("Build failed. Asking AI to analyze the error...\n")
        bridge.updateStateBridge(AiWorkflowState.ANALYZING_BUILD_ERROR)

        val prompt = """
            The build failed. Here is the build output:

            ```
            $buildOutput
            ```

            Given this error and the project file summaries (if available), which files do you need to see in full to fix the issue? Respond ONLY with a JSON array of file paths.
        """.trimIndent()

        conversation.addUserMessage(prompt)
        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response ->
                try {
                    val responseText = geminiHelper.extractTextFromApiResponse(response)
                    val jsonArray = JSONArray(geminiHelper.extractJsonArrayFromText(responseText))
                    val selectedFiles = List(jsonArray.length()) { jsonArray.getString(it) }

                    if (selectedFiles.isEmpty()) {
                        finishAndMaybeBuild("AI analyzed the build error but did not suggest any file modifications.")
                    } else {
                        logViaBridge("AI selected ${selectedFiles.size} files to fix the build error.\n")
                        selectedFilesForModification.clear()
                        selectedFilesForModification.addAll(selectedFiles)
                        loadSelectedFilesAndGenerateInBatches(lastAppNameForFallback, "Fix the build error: $buildOutput")
                    }
                } catch (e: JSONException) {
                    handleError("Failed to parse file selection from LLM during fix attempt: ${e.message}", e)
                }
            },
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun handleError(message: String, e: Exception?) {
        encounteredError = true
        bridge.handleErrorBridge(message, e)
        AiForegroundService.stop(bridge.getContextBridge())
    }
}
