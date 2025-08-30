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

        private const val MAX_FALLBACK_RETRIES = 2
        private const val MAX_SUMMARY_RETRIES = 1
        private const val RAW_LOG_SNIPPET = 2048

        // For "need more files" loop to avoid infinite iterations
        private const val MAX_NEED_MORE_ROUNDS = 6

        // When the LLM planned set is incomplete after the main write, fallback batch size
        private const val FALLBACK_WRITE_BATCH_SIZE = 5

        // Code-dump logging helpers (for full file contents) - MOVED HERE TO FIX BUILD
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
        extraWritesAcceptedCount = 0

        lastAppNameForFallback = appName
        lastAppDescriptionForFallback = appDescription

        AiForegroundService.start(bridge.getContextBridge(), "Analyzing project for $appName")

        allProjectFiles = ProjectFileUtils.scanProjectFiles(projectDir)
        Log.d("AI_PIPELINE", "Project scan completed: files=${allProjectFiles.size} -> ${allProjectFiles.take(50)}${if (allProjectFiles.size > 50) " ... (total ${allProjectFiles.size})" else ""}")

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
        val prompt = sb.toString()
        Log.d("AI_PIPELINE", "requestFileSummaries: sending ${allProjectFiles.size} files, promptChars=${prompt.length}, modelOverride=${getFastModelForSummarization()}")
        logPromptToUi("Summarization prompt", prompt)
        conversation.addUserMessage(prompt)
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
        Log.d("AI_PIPELINE", "handleFileSummariesResponse: rawLen=${responseText.length}, snippet='${responseText.take(RAW_LOG_SNIPPET)}'")
        logPromptToUi("Summaries response (structural)", responseText)
        try {
            val root = JSONObject(responseText).unwrapDataIfPresent()

            val arr = root.optJSONArrayByKeys("file_summaries", "fileSummaries", "files") ?: run {
                throw JSONException("No value for file_summaries, fileSummaries, or files")
            }

            val summariesMap = mutableMapOf<String, String>()
            arr.forEachObject { item ->
                val path = item.optStringByKeys("file_path", "filePath", "file_name", "path") ?: ""
                val summary = item.optString("summary", "")
                if (path.isNotBlank()) summariesMap[path] = summary
            }

            if (summariesMap.isEmpty() && arr.length() > 0) {
                handleError("Parsed summaries array but the map is empty. Check JSON keys in response.", null)
                return
            }

            this.fileSummaries = summariesMap
            logViaBridge("✅ Summaries received for ${summariesMap.size} files.\n")
            Log.i("AI_PIPELINE", "Summaries parsed: count=${summariesMap.size}; sample=${summariesMap.entries.take(3)}")
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
        logPromptToUi("Selection-from-summaries prompt", prompt)
        conversation.addUserMessage(prompt)

        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response: JSONObject ->
                try {
                    val responseText = geminiHelper.extractTextFromApiResponse(response)
                    logPromptToUi("Selection-from-summaries response (structural)", responseText)
                    val jsonArray = JSONArray(geminiHelper.extractJsonArrayFromText(responseText))
                    val selected = List(jsonArray.length()) { jsonArray.getString(it) }.filter { it.isNotBlank() }

                    if (selected.isEmpty()) {
                        logViaBridge("AI did not select any files. Will try to generate from description.\n")
                        generateInitialFilesFromDescription(lastAppNameForFallback, lastAppDescriptionForFallback, 0)
                    } else {
                        logViaBridge("AI selected ${selected.size} files. Proceeding to show full contents (single-shot)...\n")
                        Log.i("AI_PIPELINE", "Selected files for full view: ${selected.joinToString()}")
                        selectedFilesForModification.clear()
                        selectedFilesForModification.addAll(selected)
                        loadSelectedFilesAndRunSingleShotFlow(lastAppNameForFallback, lastAppDescriptionForFallback)
                    }
                    conversation.addModelMessage(responseText)
                } catch (e: Exception) {
                    handleError("Failed to parse file selection from LLM: ${e.message}", e)
                }
            },
            modelIdentifierOverride = null, // Use main model
            responseMimeTypeOverride = "application/json"
        )
    }

    // --- Utility: load selected files and run single-shot flow ---
    private fun loadSelectedFilesAndRunSingleShotFlow(appName: String, appDescription: String) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            handleError("Project directory is null before single-shot generation.", null)
            return
        }
        bridge.updateStateBridge(AiWorkflowState.GENERATING_CODE)
        logViaBridge("Loading ${selectedFilesForModification.size} selected files (single-shot)...\n")
        Log.d("AI_PIPELINE", "loadSelectedFilesAndRunSingleShotFlow: selected=${selectedFilesForModification.size}")
        val fileContentsMap = mutableMapOf<String, String>()

        for (filePath in selectedFilesForModification) {
            val f = File(projectDir, filePath)
            if (!f.exists() || !f.isFile) {
                logViaBridge("Note: File '$filePath' not found. AI will be asked to create it if needed.\n")
                Log.w("AI_PIPELINE", "Selected file missing: '$filePath' -> will allow creation")
                fileContentsMap[filePath] = "// File: $filePath (This file is new or was not found. Please provide its complete content.)"
            } else {
                try {
                    fileContentsMap[filePath] = FileReader(f).use { it.readText() }
                    Log.d("AI_PIPELINE", "Read file: '$filePath', size=${fileContentsMap[filePath]?.length}")
                } catch (e: IOException) {
                    logViaBridge("⚠️ Error reading file $filePath: ${e.message}. AI will be asked to regenerate.\n")
                    Log.e("AI_PIPELINE", "Error reading file '$filePath': ${e.message}", e)
                    fileContentsMap[filePath] = "// File: $filePath (Error reading existing content. Please regenerate based on its intended role.)"
                }
            }
        }

        if (fileContentsMap.isEmpty()) {
            logViaBridge("No valid files were loaded. Attempting to generate from description...\n")
            generateInitialFilesFromDescription(appName, appDescription, 0)
            return
        }

        Log.i("AI_PIPELINE", "Single-shot flow starting with ${fileContentsMap.size} file(s).")
        runSingleShotPlanAndWriteFlow(appName, appDescription, fileContentsMap)
    }

    // --- Build a pretty block with all files ---
    private fun makeFilesContentBlock(filesMap: Map<String, String>): String {
        val sb = StringBuilder()
        filesMap.forEach { (path, content) ->
            sb.append("FILE: $path\n```\n")
            sb.append(content)
            sb.append("\n```\n\n")
        }
        val res = sb.toString()
        lastFileContextForFallback = res
        return res
    }

    // --- Class-level helper: parse FileModifications + requestMoreFiles safely ---
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

            val conclusion = root.optStringByKeys("conclusion", "summary", "conclusionText")?.takeIf { it.isNotBlank() }
            val requestMore = mutableListOf<String>()
            root.optJSONArrayByKeys("requestMoreFiles", "request_more_files", "request_more_files_paths")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (!s.isNullOrBlank()) requestMore.add(s)
                }
            }

            val fm = FileModifications(filesToWrite = filesMap, filesToDelete = filesToDelete, conclusion = conclusion)
            Log.d("AI_PIPELINE", "parseFileModificationsWithRequestMore: writes=${filesMap.size}, deletes=${filesToDelete.size}, requestMore=${requestMore.size}, hasConclusion=${!conclusion.isNullOrBlank()}")
            fm to if (requestMore.isNotEmpty()) requestMore else null
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing FileModifications JSON: '$jsonText'. Error: ${e.message}", e)
            null to null
        }
    }

    // --- Class-level helper: fallback ask for missing planned files in small chunks ---
    private fun requestMissingWritesInSmallBatches(
        filesContext: Map<String, String>,
        missing: List<String>,
        onComplete: (Map<String, String>) -> Unit
    ) {
        val collected = LinkedHashMap<String, String>()
        val chunks = missing.chunked(FALLBACK_WRITE_BATCH_SIZE)
        Log.i("AI_PIPELINE", "Fallback write: requesting ${missing.size} files in ${chunks.size} subset(s)")

        fun promptWriteForSubset(filesBlock: String, subset: List<String>): String = """
      Task (Fallback APPLY for missing files): Return FULL content for ONLY the following files:
      ${subset.joinToString(separator = "\n") { "- $it" }}

      Respond ONLY with JSON:
      {
        "filesToWrite": [
          { "filePath": "<one of the listed paths>", "fileContent": "<full file content>" }
        ]
      }

      Do not include other files. No prose.

      Context files you saw:
      $filesBlock
    """.trimIndent()

        fun askChunk(index: Int) {
            if (index >= chunks.size) {
                onComplete(collected)
                return
            }
            val subset = chunks[index]
            val filesBlock = makeFilesContentBlock(filesContext)
            val prompt = promptWriteForSubset(filesBlock, subset)
            conversation.addUserMessage(prompt)
            Log.i("AI_PIPELINE", "Fallback write chunk ${index + 1}/${chunks.size}: subsetSize=${subset.size}, promptChars=${prompt.length}")
            logPromptToUi("Fallback write prompt (subset ${index + 1}/${chunks.size})", prompt)

            geminiHelper.sendApiRequest(
                contents = conversation.getContentsForApi(),
                callback = { response: JSONObject ->
                    try {
                        val txt = geminiHelper.extractTextFromApiResponse(response)
                        Log.d("AI_PIPELINE", "fallback write response (chunk ${index + 1}): rawLen=${txt.length}, snippet='${txt.take(RAW_LOG_SNIPPET)}'")
                        logPromptToUi("Fallback write response (subset ${index + 1}/${chunks.size})", txt)
                        val fm = geminiHelper.parseAndConvertStructuredResponse(txt)
                        collected.putAll(fm.filesToWrite)
                    } catch (e: Exception) {
                        Log.e("AI_PIPELINE", "Fallback write parsing error: ${e.message}", e)
                    } finally {
                        askChunk(index + 1)
                    }
                },
                responseSchemaJson = geminiHelper.getMinimalFilesSchema(),
                responseMimeTypeOverride = "application/json",
                modelIdentifierOverride = null // Use main model for this critical step
            )
        }

        askChunk(0)
    }

    // --- NEW: Single-shot iterative flow (no batching for showing files) ---
    private fun runSingleShotPlanAndWriteFlow(
        appName: String,
        appDescription: String,
        initialFiles: Map<String, String>
    ) {
        val currentFilesMap = initialFiles.toMutableMap()
        var needMoreRounds = 0

        fun promptNeedMore(filesBlock: String): String = """
      You are updating the Android app "$appName".
      Goal: "$appDescription"

      Here are the files you can see (full content):
      $filesBlock

      Task (Step 1): Do you need to see additional files to complete the task?
      Respond ONLY with a JSON object:
      {
        "ready": <boolean>,              // true if you have enough context and can proceed to modify files now
        "requestMoreFiles": [ ... ]      // list of additional file paths you need, or [] if none
      }

      Important:
      - If a file listed above is missing content (placeholder comment indicating "new or not found"), and you need it, include it in requestMoreFiles OR declare ready=true and you will CREATE it in the next step.
      - Do not include any prose outside JSON.
    """.trimIndent()

        fun promptPlan(filesBlock: String): String = """
      You have enough context to begin modifications for "$appName".
      Goal reminder: "$appDescription"

      Files (full content):
      $filesBlock

      Task (Step 2 - PLAN): List ALL files you intend to modify or create to achieve the goal.
      Respond ONLY with a JSON object:
      {
        "plannedFilesToWrite": [ "<relative/path1>", "<relative/path2>", ... ]
      }

      Rules:
      - Include every file you will modify or create.
      - Only list paths (no content here). No prose outside JSON.
    """.trimIndent()

        fun promptWrite(filesBlock: String, expectedPaths: List<String>): String = """
      Task (Step 3 - APPLY): Return the FULL content for the files you planned.

      You MUST respond with a single JSON object following this schema:
      {
        "filesToWrite": [
          { "filePath": "<one of the planned paths>", "fileContent": "<full file content>" }
        ],
        "filesToDelete": [ "<optional file paths to delete>" ],
        "conclusion": "<optional final summary when the entire task is complete>"
      }

      Requirements:
      - You MUST include ALL of these paths exactly once in filesToWrite (no more, no less):
      ${expectedPaths.joinToString(separator = "\n") { "- $it" }}

      - Do NOT add extra files not in the above list.
      - No prose outside the JSON.
      
      Context files you saw:
      $filesBlock
    """.trimIndent()

        // Local lambdas with forward declarations to avoid unresolved references
        var stepNeedMore: () -> Unit = {}
        var stepPlan: () -> Unit = {}
        var stepWrite: (List<String>) -> Unit = {}

        stepWrite = { expected ->
            val filesBlock = makeFilesContentBlock(currentFilesMap)
            val prompt = if (expected.isEmpty()) {
                """
          Proceed to apply changes for "$appName" (Goal: "$appDescription").

          You MUST respond with a single JSON object following this schema:
          {
            "filesToWrite": [
              { "filePath": "<relative/path>", "fileContent": "<full file content>" }
            ],
            "filesToDelete": [ ],
            "conclusion": "<optional>"
          }

          Return only JSON. No prose.

          Context files:
          $filesBlock
        """.trimIndent()
            } else {
                promptWrite(filesBlock, expected)
            }

            conversation.addUserMessage(prompt)
            logViaBridge("Requesting full contents for ${if (expected.isEmpty()) "open set (no explicit plan)" else "${expected.size} planned file(s)"}...\n")
            if (expected.isNotEmpty()) logViaBridge("Expected list:\n${expected.joinToString("\n")}\n")
            Log.i("AI_PIPELINE", "Write step: expected=${expected.size}, filesShown=${currentFilesMap.size}, promptChars=${prompt.length}")
            logPromptToUi("Write prompt", prompt)

            geminiHelper.sendApiRequest(
                contents = conversation.getContentsForApi(),
                callback = { response: JSONObject ->
                    try {
                        val responseText = geminiHelper.extractTextFromApiResponse(response)
                        logViaBridge("LLM replied to write (len=${responseText.length}).\n")
                        Log.d("AI_PIPELINE", "write response: rawLen=${responseText.length}, snippet='${responseText.take(RAW_LOG_SNIPPET)}'")
                        logPromptToUi("Write response (structural)", responseText)

                        val fm = try {
                            geminiHelper.parseAndConvertStructuredResponse(responseText)
                        } catch (_: Exception) {
                            val (mods, _) = parseFileModificationsWithRequestMore(responseText)
                            mods ?: FileModifications(emptyMap(), emptyList(), null)
                        }

                        if (fm.filesToWrite.isNotEmpty()) {
                            logViaBridge("LLM provided ${fm.filesToWrite.size} file(s). See below:\n")
                            fm.filesToWrite.forEach { (path, content) ->
                                val uiSnippet = content.take(MAX_UI_SNIPPET)
                                directLogAppender(
                                    "----- BEGIN FILE: $path -----\n$uiSnippet" +
                                            if (content.length > uiSnippet.length) "\n... [truncated in UI log]" else "" +
                                                    "\n----- END FILE: $path -----\n\n"
                                )
                                logLarge(CODE_TAG, "===== BEGIN $path =====", content, "===== END $path =====")
                            }
                        } else {
                            logViaBridge("LLM provided no file contents in this write step.\n")
                        }

                        val filesWritten = fm.filesToWrite.keys.toMutableSet()
                        Log.i("AI_PIPELINE", "Write step parsed filesToWrite=${filesWritten.size}, filesToDelete=${fm.filesToDelete.size}")

                        if (expected.isNotEmpty()) {
                            val expectedSet = expected.toSet()
                            val missing = expectedSet.minus(filesWritten)
                            val extra = filesWritten.minus(expectedSet)

                            if (extra.isNotEmpty()) {
                                logViaBridge("Note: Write returned extra files not in plan:\n${extra.joinToString("\n")}\n")
                            }

                            if (missing.isNotEmpty()) {
                                logViaBridge("Write missing ${missing.size} planned file(s). Requesting the missing ones in small subsets...\n")
                                requestMissingWritesInSmallBatches(
                                    filesContext = currentFilesMap,
                                    missing = missing.toList()
                                ) { partial ->
                                    val combined = LinkedHashMap<String, String>(fm.filesToWrite)
                                    combined.putAll(partial)
                                    val final = FileModifications(combined, fm.filesToDelete, fm.conclusion)
                                    applyCodeChangesAndOrGetSummary(final)
                                }
                                return@sendApiRequest
                            }
                        }
                        applyCodeChangesAndOrGetSummary(fm)
                    } catch (e: Exception) {
                        Log.e("AI_PIPELINE", "Error in write step: ${e.message}", e)
                        handleError("Error during write step: ${e.message}", e)
                    }
                },
                responseSchemaJson = geminiHelper.getFileModificationsSchema(),
                responseMimeTypeOverride = "application/json",
                modelIdentifierOverride = null // Use main model
            )
        }

        stepPlan = {
            val filesBlock = makeFilesContentBlock(currentFilesMap)
            val prompt = promptPlan(filesBlock)
            conversation.addUserMessage(prompt)
            logViaBridge("Planning: asking LLM which files to modify/create... (${currentFilesMap.size} files shown)\n")
            Log.i("AI_PIPELINE", "Plan step: filesShown=${currentFilesMap.size}, promptChars=${prompt.length}")
            logPromptToUi("Plan prompt", prompt)

            geminiHelper.sendApiRequest(
                contents = conversation.getContentsForApi(),
                callback = { response: JSONObject ->
                    try {
                        val text = geminiHelper.extractTextFromApiResponse(response)
                        logViaBridge("LLM replied to plan (len=${text.length}).\n")
                        Log.d("AI_PIPELINE", "plan response: rawLen=${text.length}, snippet='${text.take(RAW_LOG_SNIPPET)}'")
                        logPromptToUi("Plan response (structural)", text)

                        val obj = JSONObject(text).unwrapDataIfPresent()
                        val arr = obj.optJSONArrayByKeys("plannedFilesToWrite", "planned_files_to_write")
                        if (arr == null || arr.length() == 0) {
                            logViaBridge("Plan returned no files; proceeding to write without explicit plan.\n")
                            stepWrite(emptyList())
                            return@sendApiRequest
                        }
                        val expected = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val s = arr.optString(i)
                            if (!s.isNullOrBlank()) expected.add(s)
                        }
                        logViaBridge("Plan expects ${expected.size} file(s):\n${expected.joinToString("\n")}\n")
                        stepWrite(expected)
                    } catch (e: Exception) {
                        Log.e("AI_PIPELINE", "Plan step parsing error: ${e.message}", e)
                        logViaBridge("⚠️ Plan parse error: ${e.message}. Proceeding to write without explicit plan.\n")
                        stepWrite(emptyList())
                    }
                },
                responseMimeTypeOverride = "application/json",
                modelIdentifierOverride = null // Use main model
            )
        }

        stepNeedMore = {
            if (needMoreRounds >= MAX_NEED_MORE_ROUNDS) {
                Log.w(
                    "AI_PIPELINE",
                    "Need-more-files rounds exceeded $MAX_NEED_MORE_ROUNDS; proceeding to plan with current set (${currentFilesMap.size} files)"
                )
                stepPlan()
            } else {
                val filesBlock = makeFilesContentBlock(currentFilesMap)
                val prompt = promptNeedMore(filesBlock)
                conversation.addUserMessage(prompt)
                logViaBridge("Asking LLM if it needs more files (round ${needMoreRounds + 1})... Showing ${currentFilesMap.size} file(s).\n")
                Log.i("AI_PIPELINE", "Need-more step round=${needMoreRounds + 1}, filesShown=${currentFilesMap.size}, promptChars=${prompt.length}")
                logPromptToUi("Need-more prompt", prompt)

                geminiHelper.sendApiRequest(
                    contents = conversation.getContentsForApi(),
                    callback = { response: JSONObject ->
                        try {
                            val text = geminiHelper.extractTextFromApiResponse(response)
                            logViaBridge("LLM replied to need-more (len=${text.length}).\n")
                            Log.d("AI_PIPELINE", "need-more response: rawLen=${text.length}, snippet='${text.take(RAW_LOG_SNIPPET)}'")
                            logPromptToUi("Need-more response (structural)", text)

                            val obj = JSONObject(text).unwrapDataIfPresent()
                            val ready = obj.optBoolean("ready", false)
                            val reqArr = obj.optJSONArrayByKeys("requestMoreFiles") ?: JSONArray()
                            val reqList = mutableListOf<String>()
                            for (i in 0 until reqArr.length()) {
                                val s = reqArr.optString(i)
                                if (!s.isNullOrBlank()) reqList.add(s)
                            }
                            if (ready || reqList.isEmpty()) {
                                Log.i("AI_PIPELINE", "Need-more step finished: ready=$ready, additionalRequested=${reqList.size}")
                                stepPlan()
                            } else {
                                Log.i("AI_PIPELINE", "Need-more requested ${reqList.size} additional files: $reqList")
                                val proj = bridge.currentProjectDirBridge
                                if (proj == null) {
                                    logViaBridge("⚠️ Project directory is null while loading requested additional files.\n")
                                    stepPlan()
                                    return@sendApiRequest
                                }
                                var added = 0
                                for (p in reqList) {
                                    if (currentFilesMap.containsKey(p)) continue
                                    val f = File(proj, p)
                                    currentFilesMap[p] = if (!f.exists() || !f.isFile) {
                                        Log.w("AI_PIPELINE", "Requested additional file not found: '$p' -> placeholder will be used")
                                        "// File: $p (This file is new or was not found. Please provide its complete content.)"
                                    } else {
                                        try {
                                            FileReader(f).use { it.readText() }
                                        } catch (e: IOException) {
                                            Log.e("AI_PIPELINE", "Error reading requested file '$p': ${e.message}", e)
                                            "// File: $p (Error reading existing content. Please regenerate.)"
                                        }
                                    }
                                    added++
                                }
                                needMoreRounds++
                                logViaBridge("Loaded $added requested files (visible=${currentFilesMap.size}).\n")
                                stepNeedMore()
                            }
                        } catch (e: Exception) {
                            Log.e("AI_PIPELINE", "Need-more step error: ${e.message}", e)
                            logViaBridge("⚠️ Need-more parse error: ${e.message}. Proceeding to planning.\n")
                            stepPlan()
                        }
                    },
                    responseMimeTypeOverride = "application/json",
                    modelIdentifierOverride = null // Use main model
                )
            }
        }

        // Start the single-shot loop
        stepNeedMore()
    }

    // --- Initial generation for empty projects ---
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
        logPromptToUi("Initial-generation prompt", prompt)

        geminiHelper.sendApiRequest(
            contents = conv.getContentsForApi(),
            callback = { response: JSONObject ->
                try {
                    val txt = geminiHelper.extractTextFromApiResponse(response)
                    logPromptToUi("Initial-generation response (structural)", txt)
                    val filesMap = geminiHelper.parseMinimalFilesResponse(txt)
                    if (!filesMap.isNullOrEmpty()) {
                        logViaBridge("AI proposed ${filesMap.size} initial file(s) from description.\n")
                        Log.i("AI_PIPELINE", "Initial generation parsed files: count=${filesMap.size}, sample=${filesMap.keys.take(5)}")
                        applyCodeChangesAndOrGetSummary(FileModifications(filesMap, emptyList(), null))
                    } else {
                        if (attempt < MAX_FALLBACK_RETRIES) {
                            logViaBridge("⚠️ AI returned no files for initial generation. Retrying...\n")
                            Log.w("AI_PIPELINE", "Initial generation returned no files; retrying attempt=${attempt + 1}")
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
            modelIdentifierOverride = null // Use main model
        )
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

        Log.i("AI_PIPELINE", "applyCodeChanges: writes=${modifications.filesToWrite.size}, deletes=${filteredFilesToDelete.size}, hasConclusion=${!modifications.conclusion.isNullOrBlank()}")

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

                    Log.i("AI_PIPELINE", "applyCodeChanges result: writeOk=$writeSuccessCount, writeErr=$writeErrorCount, delOk=$deleteSuccessCount, delErr=$deleteErrorCount, anyChangesApplied=$anyChangesApplied")

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

        logPromptToUi("Summary prompt", summaryPrompt)

        val summaryConversation = GeminiConversation().apply {
            addUserMessage(summaryPrompt)
            if (attempt > 0) addUserMessage("Retry ($attempt/$MAX_SUMMARY_RETRIES): Return ONLY a JSON object with a 'summary' string. No prose.")
        }

        val overrideModel = getFastModelForSummarization()

        geminiHelper.sendApiRequest(
            contents = summaryConversation.getContentsForApi(),
            callback = { response: JSONObject ->
                var finalSummaryToDisplay: String? = originalConclusion
                try {
                    val summaryResponseJsonText = geminiHelper.extractTextFromApiResponse(response)
                    logPromptToUi("Summary response (structural)", summaryResponseJsonText)
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
        Log.i("AI_PIPELINE", "finishAndMaybeBuild: hasSummary=${!finalSummary.isNullOrBlank()}, autoBuild=$autoBuildAfterApply, autoRun=$autoRunAfterBuild, encounteredError=$encounteredError, anyChangesApplied=$anyChangesApplied")
        bridge.displayAiConclusionBridge(finalSummary)
        bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)

        val projectDir = bridge.currentProjectDirBridge
        val okToAutoBuild = !encounteredError && anyChangesApplied && projectDir != null

        AiForegroundService.stop(bridge.getContextBridge())

        if (autoBuildAfterApply && okToAutoBuild && !hasTriggeredAutoBuild) {
            hasTriggeredAutoBuild = true
            Log.i("AI_PIPELINE", "Triggering auto-build (runAfterBuild=$autoRunAfterBuild) for '${projectDir!!.absolutePath}'")
            bridge.triggerBuildBridge(projectDir!!, runAfterBuild = autoRunAfterBuild)
        } else {
            if (!autoBuildAfterApply) logViaBridge("ℹ️ Auto-build disabled; waiting for user action.\n")
            if (encounteredError) logViaBridge("❌ Skipping auto-build due to an earlier error in the AI flow.\n")
            if (!anyChangesApplied) logViaBridge("ℹ️ Skipping auto-build because no code changes were applied.\n")
            if (projectDir == null) logViaBridge("❌ Skipping auto-build: projectDir is null.\n")
            Log.d("AI_PIPELINE", "Auto-build conditions: enabled=$autoBuildAfterApply, ok=$okToAutoBuild, triggered=$hasTriggeredAutoBuild, projectDirNull=${projectDir == null}")
        }
    }

    // --- Build & Fix loop ---
    fun handleBuildResult(success: Boolean, buildOutput: String) {
        if (success) {
            logViaBridge("✅ Build Successful! Workflow complete.\n")
            Log.i("AI_PIPELINE", "Build successful; workflow complete")
            bridge.updateStateBridge(AiWorkflowState.IDLE)
            return
        }

        Log.w("AI_PIPELINE", "Build failed; outputLen=${buildOutput.length}")
        logViaBridge("Build failed. Asking AI to analyze the error...\n")
        bridge.updateStateBridge(AiWorkflowState.ANALYZING_BUILD_ERROR)

        val prompt = """
      The build failed. Here is the build output:

      ```
      $buildOutput
      ```

      Given this error and the project file summaries (if available), which files do you need to see in full to fix the issue? Respond ONLY with a JSON array of file paths.
    """.trimIndent()
        logPromptToUi("Build-fix prompt", prompt)

        conversation.addUserMessage(prompt)
        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response: JSONObject ->
                try {
                    val responseText = geminiHelper.extractTextFromApiResponse(response)
                    logPromptToUi("Build-fix selection response (structural)", responseText)
                    val jsonArray = JSONArray(geminiHelper.extractJsonArrayFromText(responseText))
                    val selectedFiles = List(jsonArray.length()) { jsonArray.getString(it) }

                    if (selectedFiles.isEmpty()) {
                        finishAndMaybeBuild("AI analyzed the build error but did not suggest any file modifications.")
                    } else {
                        logViaBridge("AI selected ${selectedFiles.size} files to fix the build error.\n")
                        Log.i("AI_PIPELINE", "Build-fix selected files: ${selectedFiles.joinToString()}")
                        selectedFilesForModification.clear()
                        selectedFilesForModification.addAll(selectedFiles)
                        loadSelectedFilesAndRunSingleShotFlow(lastAppNameForFallback, "Fix the build error: $buildOutput")
                    }
                } catch (e: JSONException) {
                    handleError("Failed to parse file selection from LLM during fix attempt: ${e.message}", e)
                }
            },
            modelIdentifierOverride = null, // Use main model
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