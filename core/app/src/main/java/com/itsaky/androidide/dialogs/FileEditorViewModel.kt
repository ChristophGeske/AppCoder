package com.itsaky.androidide.dialogs

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.dialogs.GeminiHelper.Companion.DEFAULT_GEMINI_MODEL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileEditorViewModel(application: Application) : AndroidViewModel(application), ViewModelFileEditorBridge {

    companion object {
        private const val TAG = "FileEditorViewModel"
        private const val PREFS_NAME = "GeminiPrefs_AppCoder_VM"
        private const val KEY_API_KEY = "gemini_api_key_vm"
        private const val KEY_GEMINI_MODEL = "gemini_selected_model_vm"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _workflowState = MutableLiveData(AiWorkflowState.IDLE)
    val workflowState: LiveData<AiWorkflowState> = _workflowState

    private val _logOutput = MutableLiveData(StringBuilder())
    private val _logOutputString = MutableLiveData<String>()
    val logOutputText: LiveData<String> = _logOutputString

    private val _aiConclusion = MutableLiveData<String?>()
    val aiConclusion: LiveData<String?> = _aiConclusion

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isModifyingProject = MutableLiveData(false)
    val isModifyingProject: LiveData<Boolean> = _isModifyingProject

    private val _currentProjectDirVM = MutableLiveData<File?>()
    val currentProjectDirVM: LiveData<File?> = _currentProjectDirVM

    private val _statusText = MutableLiveData("Ready")
    val statusText: LiveData<String> = _statusText

    private val _actionButtonsVisible = MutableLiveData(false)
    val actionButtonsVisible: LiveData<Boolean> = _actionButtonsVisible

    private val _uiErrorEvent = MutableLiveData<String?>()
    val uiErrorEvent: LiveData<String?> = _uiErrorEvent

    private val _existingProjectNames = MutableLiveData<List<String>>(emptyList())
    val existingProjectNames: LiveData<List<String>> = _existingProjectNames

    private val _projectVersions = MutableLiveData<List<String>>()
    val projectVersions: LiveData<List<String>> = _projectVersions

    private val _versionsUiVisible = MutableLiveData(false)
    val versionsUiVisible: LiveData<Boolean> = _versionsUiVisible

    private var _storedApiKey: String = prefs.getString(KEY_API_KEY, "") ?: ""
    val storedApiKey: String get() = _storedApiKey

    private var _storedModelId: String = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
    val storedModelId: String get() = _storedModelId

    // to track the counter
    // to track the counter
    // to track the counter per model
    private val _dailyRequestCount = MutableLiveData<Pair<String, Int>>()
    val dailyRequestCount: LiveData<Pair<String, Int>> = _dailyRequestCount

    // ADD THESE LINES FOR THE DOWNGRADE PROMPT:
    private val _downgradePromptEvent = MutableLiveData<DowngradeData?>()
    val downgradePromptEvent: LiveData<DowngradeData?> = _downgradePromptEvent

    fun clearDowngradeEvent() {
        _downgradePromptEvent.value = null
    }

    fun setDowngradeEnabled(enabled: Boolean) {
        geminiHelper.allowDowngradePrompt = enabled
    }
    // END OF ADDED LINES

    var projectsBaseDir: File? = null
        set(value) {
            field = value
            if (value != null) {
                loadExistingProjectNames()
            }
        }

    // Bridge implementation (ViewModel acts as the bridge)
    override var currentProjectDirBridge: File?
        get() = _currentProjectDirVM.value
        set(value) { _currentProjectDirVM.postValue(value) }

    override val isModifyingExistingProjectBridge: Boolean
        get() = _isModifyingProject.value ?: false

    override fun updateStateBridge(newState: AiWorkflowState) {
        updateViewModelState(newState)
    }

    override fun appendToLogBridge(text: String) {
        appendToViewModelLog(text)
    }

    override fun displayAiConclusionBridge(conclusion: String?) {
        _aiConclusion.postValue(conclusion)
    }

    override fun handleErrorBridge(message: String, e: Exception?) {
        handleViewModelError(message, e)
    }

    override fun runOnUiThreadBridge(block: () -> Unit) {
        runOnMainThread(block)
    }

    override fun getContextBridge(): Context {
        return getApplication<Application>().applicationContext
    }

    override fun onTemplateProjectCreatedBridge(projectDir: File, appName: String, appDescription: String) {
        runOnMainThread {
            _currentProjectDirVM.value = projectDir
            appendToViewModelLog("✅ Base project template created (via bridge): ${projectDir.absolutePath}\n")
            if (currentApiKeyForWorkflow.isNotBlank()) {
                val enhancedPrompt = buildGenerationPrompt(appName, appDescription)
                geminiWorkflowCoordinator.startModificationFlow(
                    appName = appName,
                    appDescription = enhancedPrompt,
                    projectDir = projectDir,
                    autoBuild = true,
                    autoRun = true
                )
            } else {
                handleViewModelError("API Key not available when trying to start modification flow after template creation.", null)
            }
        }
    }

    override fun triggerBuildBridge(projectDir: File, runAfterBuild: Boolean) {
        runOnMainThread {
            try {
                // NO snapshot here anymore. A version (_vN) is stored only after this build was
                // installed successfully (AppVersionStore.onInstallSucceeded), so broken code is
                // never stored as a version. We only remember which workbench is being built.
                AppVersionStore.registerPendingBuild(projectDir)

                com.itsaky.androidide.projects.IProjectManager.getInstance().openProject(projectDir)

                val appCtx = getApplication<Application>().applicationContext
                val intent = android.content.Intent(
                    appCtx,
                    com.itsaky.androidide.activities.editor.EditorActivityKt::class.java
                ).apply {
                    putExtra(com.itsaky.androidide.activities.editor.EditorActivityKt.EXTRA_AUTO_BUILD_PROJECT, true)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                appCtx.startActivity(intent)
                appendToViewModelLog("🛠️ Auto-build started for '${projectDir.name}'. A version is saved only after it builds and installs successfully.\n")
            } catch (e: Exception) {
                handleViewModelError("Failed to trigger build: ${e.message}", e)
            }
        }
    }

    private val projectOperationsHandler: ProjectOperationsHandler by lazy {
        ProjectOperationsHandler(
            projectsBaseDir = projectsBaseDir ?: getApplication<Application>().filesDir,
            directLogAppender = { msg -> appendToLogBridge(msg) },
            directErrorHandler = { message, e -> handleErrorBridge(message, e) },
            bridge = this
        )
    }

    private val geminiHelper: GeminiHelper by lazy {
        GeminiHelper(
            apiKeyProvider = { _storedApiKey },
            errorHandlerCallback = { message, e -> handleViewModelError(message, e) },
            uiThreadExecutor = { block -> runOnMainThread(block) },
            onDowngradeSuggested = { data ->
                _downgradePromptEvent.postValue(data)
            },
            onRequestFired = { modelName ->
                incrementRequestCount(modelName)
            }
        )
    }

    // Final corrected initialization: Coordinator without serviceManager/fileScanner
    private val geminiWorkflowCoordinator: GeminiWorkflowCoordinator by lazy {
        GeminiWorkflowCoordinator(
            geminiHelper = geminiHelper,
            directLogAppender = { msg -> appendToLogBridge(msg) },
            bridge = this
        )
    }

    private var currentApiKeyForWorkflow: String = ""

    init {
        loadDailyRequestCount()
        geminiHelper.setModel(_storedModelId)
    }

    private fun loadDailyRequestCount(modelId: String = storedModelId) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        // Appending the date directly to the key prevents us from ever needing to "clear" preferences manually
        val count = prefs.getInt("req_${today}_$modelId", 0)
        _dailyRequestCount.postValue(Pair(modelId, count))
    }

    private fun incrementRequestCount(modelId: String) {
        runOnMainThread {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val key = "req_${today}_$modelId"
            var count = prefs.getInt(key, 0)
            count++
            prefs.edit().putInt(key, count).apply()
            _dailyRequestCount.value = Pair(modelId, count)
        }
    }

    private fun loadExistingProjectNames() {
        viewModelScope.launch(Dispatchers.IO) {
            val names = projectOperationsHandler.listExistingProjectNames()
            _existingProjectNames.postValue(names)
        }
    }


    fun onProjectNameSelected(appName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val versions = projectOperationsHandler.findProjectVersions(appName)
            withContext(Dispatchers.Main) {
                if (versions.isNotEmpty()) {
                    val displayList = mutableListOf<String>()
                    val latestVersion = versions.last()
                    // "Latest" continues the working copy. Only if it has unverified changes made in
                    // THIS session (IDE was just in the background) do they carry over; after a full
                    // restart they are discarded and "Latest" means the last working version.
                    val workbench = projectsBaseDir?.let { File(it, appName) }
                    val hasLiveChanges = workbench != null &&
                            AppVersionStore.workbenchState(workbench) == AppVersionStore.WorkbenchState.UNVERIFIED_THIS_SESSION
                    displayList.add(
                        if (hasLiveChanges) "Latest (_v$latestVersion + unverified changes)"
                        else "Latest (_v$latestVersion)"
                    )
                    displayList.addAll(versions.reversed().map { "_v$it" })
                    _projectVersions.value = displayList
                    _versionsUiVisible.value = true
                } else {
                    _versionsUiVisible.value = false
                    _projectVersions.value = emptyList()
                }
            }
        }
    }

    fun onProjectNameClearedOrNew() {
        _versionsUiVisible.postValue(false)
    }

    fun saveApiKey(apiKey: String) {
        val trimmedApiKey = apiKey.trim()
        prefs.edit().putString(KEY_API_KEY, trimmedApiKey).apply()
        this._storedApiKey = trimmedApiKey
    }

    fun saveSelectedModel(modelId: String) {
        prefs.edit().putString(KEY_GEMINI_MODEL, modelId).apply()
        this._storedModelId = modelId
        geminiHelper.setModel(modelId)
        loadDailyRequestCount(modelId) // Loads and displays the count for the newly selected model
    }

    fun updateIsModifyingProjectFlag(isModifying: Boolean) {
        if (_isModifyingProject.value != isModifying) {
            _isModifyingProject.postValue(isModifying)
        }
    }

    private fun buildGenerationPrompt(appName: String, userDescription: String): String {
        return """
        **AI GOAL: Full Android App Implementation**
        You are an expert Android developer... Your task is to generate all the necessary code for a new Android application based on the user's request.
        ... (rest of the detailed prompt)
        **App Name:** "$appName"
        **App Description:** "$userDescription"
    """.trimIndent()
    }

    fun initiateWorkflow(appName: String, appDescription: String, apiKey: String, selectedModelId: String, selectedVersion: String?) {
        if (projectsBaseDir == null) {
            handleViewModelError("Projects base directory not set in ViewModel.", null)
            return
        }
        currentApiKeyForWorkflow = apiKey
        saveApiKey(apiKey)
        saveSelectedModel(selectedModelId)

        _logOutput.postValue(StringBuilder())
        _logOutputString.postValue("")
        _aiConclusion.postValue(null)
        updateViewModelState(AiWorkflowState.IDLE)
        appendToViewModelLog("Workflow initiated for App: $appName, Model: $selectedModelId\n")
        Log.i(TAG, "WORKFLOW_START: App: '$appName', SelectedVersion: '$selectedVersion'")

        val isRetryPrompt = appDescription.contains("Please analyze the original app description", ignoreCase = true)

        viewModelScope.launch(Dispatchers.IO) {
            var shouldBranch = false
            var finalPrompt = appDescription

            val isModificationFromSpecificVersion = selectedVersion != null && !selectedVersion.startsWith("Latest")
            Log.d(TAG, "isModificationFromSpecificVersion: $isModificationFromSpecificVersion")

            if (isModificationFromSpecificVersion && !isRetryPrompt) {
                val numericSelectedVersion = selectedVersion!!.substringAfter("_v")
                Log.d(TAG, "User selected specific version: '$numericSelectedVersion'")

                val allVersions = projectOperationsHandler.findProjectVersions(appName)
                val latestVersion = allVersions.lastOrNull()
                Log.d(TAG, "All versions found: $allVersions. Latest version is: $latestVersion")

                if (latestVersion != null && numericSelectedVersion != latestVersion) {
                    shouldBranch = true
                    Log.i(TAG, "BRANCHING_DECISION: TRUE. Selected version ('$numericSelectedVersion') is not the latest ('$latestVersion').")
                } else {
                    shouldBranch = false
                    Log.i(TAG, "BRANCHING_DECISION: FALSE. Selected version is the latest or no other versions exist.")
                }

                updateViewModelState(AiWorkflowState.PREPARING_EXISTING_PROJECT)
                val workbenchMessage = if (shouldBranch) "for branching" else "to reset to latest"
                appendToViewModelLog("Preparing workbench from $selectedVersion $workbenchMessage\n")
                val versionProjectName = "${appName}${selectedVersion}"

                Log.i(TAG, "Calling overwriteProjectWithVersion. shouldBranch = $shouldBranch")
                val success = projectOperationsHandler.overwriteProjectWithVersion(appName, versionProjectName, shouldBranch)
                if (!success) {
                    Log.e(TAG, "overwriteProjectWithVersion failed. Aborting workflow.")
                    return@launch
                }

            } else if (isRetryPrompt) {
                appendToViewModelLog("Automated Retry: Skipping workbench overwrite.\n")
                Log.i(TAG, "This is an automated retry, skipping workbench overwrite.")
            } else {
                Log.i(TAG, "This is a new/linear modification from 'Latest'.")
                val workbench = File(projectsBaseDir!!, appName)
                if (workbench.isDirectory &&
                    AppVersionStore.workbenchState(workbench) == AppVersionStore.WorkbenchState.UNVERIFIED_PREVIOUS_SESSION
                ) {
                    // The IDE was closed completely since these changes were made and they were never
                    // built + installed successfully -> they are not a stored version. Start again
                    // from the last working version.
                    val latest = projectOperationsHandler.findProjectVersions(appName).lastOrNull()
                    if (latest != null) {
                        updateViewModelState(AiWorkflowState.PREPARING_EXISTING_PROJECT)
                        appendToViewModelLog("♻️ Discarding unverified changes from a previous session. Starting from the last working version _v$latest.\n")
                        val ok = projectOperationsHandler.overwriteProjectWithVersion(appName, "${appName}_v$latest", false)
                        if (!ok) return@launch
                    } else {
                        appendToViewModelLog("ℹ️ Found unverified changes from a previous session, but no working version exists yet. Continuing with the working copy.\n")
                    }
                }
            }

            if (!isRetryPrompt) {
                finalPrompt = if (shouldBranch) {
                    Log.i(TAG, "PROMPT_SELECTION: Branching workflow. Using simple prompt to protect bookmark.")
                    appDescription
                } else {
                    Log.i(TAG, "PROMPT_SELECTION: Linear workflow. Using enhanced generation prompt.")
                    buildGenerationPrompt(appName, appDescription)
                }
            } else {
                Log.i(TAG, "PROMPT_SELECTION: Retry workflow. Using provided description as-is.")
            }

            try {
                val projectDir = File(projectsBaseDir!!, appName)
                currentProjectDirBridge = projectDir
                val isExistingProject = projectOperationsHandler.projectExists(appName)

                if (isExistingProject) {
                    Log.d(TAG, "Project exists. Starting modification flow...")
                    geminiWorkflowCoordinator.startModificationFlow(
                        appName = appName,
                        appDescription = finalPrompt,
                        projectDir = projectDir,
                        autoBuild = true,
                        autoRun = true
                    )
                } else {
                    Log.d(TAG, "Project does not exist. Starting new project template creation...")
                    updateViewModelState(AiWorkflowState.CREATING_PROJECT_TEMPLATE)
                    projectOperationsHandler.createNewProjectFromTemplate(appName, finalPrompt)
                }
            } catch (e: Exception) {
                handleViewModelError("Error initiating workflow: ${e.message}", e)
            }
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    private fun appendToViewModelLog(text: String) {
        runOnMainThread {
            _logOutput.value?.append(text)
            _logOutputString.value = _logOutput.value.toString()
        }
    }

    private fun handleViewModelError(message: String, e: Exception?) {
        runOnMainThread {
            if (e != null) { Log.e(TAG, message, e) } else { Log.e(TAG, message) }
            _logOutput.value?.append("❌ ERROR: $message\n")
            _logOutputString.value = _logOutput.value.toString()
            updateViewModelState(AiWorkflowState.ERROR)
            _uiErrorEvent.value = message
        }
    }

    fun consumedUiErrorEvent() { _uiErrorEvent.value = null }

    private fun updateViewModelState(newState: AiWorkflowState) {
        runOnMainThread {
            if (_workflowState.value == newState && newState != AiWorkflowState.READY_FOR_ACTION) return@runOnMainThread
            _workflowState.value = newState
            Log.d(TAG, "State changed to (VM): $newState")

            val currentIsLoading = newState !in listOf(
                AiWorkflowState.IDLE,
                AiWorkflowState.READY_FOR_ACTION,
                AiWorkflowState.ERROR
            )
            if (_isLoading.value != currentIsLoading) _isLoading.value = currentIsLoading

            val currentActionButtonsVisible = newState == AiWorkflowState.READY_FOR_ACTION
            if (_actionButtonsVisible.value != currentActionButtonsVisible) _actionButtonsVisible.value = currentActionButtonsVisible

            val newStatusText = when (newState) {
                AiWorkflowState.IDLE -> "Ready"
                AiWorkflowState.CREATING_PROJECT_TEMPLATE -> "Creating base project..."
                AiWorkflowState.PREPARING_EXISTING_PROJECT -> "Preparing project from version..."
                AiWorkflowState.SUMMARIZING_FILES -> "Analyzing files..."
                AiWorkflowState.SELECTING_FILES -> "AI is selecting files..."
                AiWorkflowState.GENERATING_CODE -> "AI is generating code..."
                AiWorkflowState.GENERATING_SUMMARY -> "AI is generating summary..."
                AiWorkflowState.READY_FOR_ACTION -> if (_isModifyingProject.value == true) "Project modified. Ready." else "New project generated. Ready."
                AiWorkflowState.AWAITING_BUILD_RESULT -> "Building project..."
                AiWorkflowState.ANALYZING_BUILD_ERROR -> "Analyzing build error..."
                AiWorkflowState.ERROR -> "Error occurred (see log)"
            }
            if (_statusText.value != newStatusText) _statusText.value = newStatusText
        }
    }

    fun fetchModelsFromApi(provider: String, apiKey: String, callback: (List<String>) -> Unit) {
        geminiHelper.fetchAvailableModels(provider, apiKey, callback)
    }
}