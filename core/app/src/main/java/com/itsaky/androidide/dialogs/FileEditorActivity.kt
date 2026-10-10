package com.itsaky.androidide.dialogs

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.dialogs.GeminiHelper.Companion.DEFAULT_GEMINI_MODEL
import java.io.File

class FileEditorActivity : AppCompatActivity() {

    companion object {
        private const val DEBUG_TAG = "AutoFixDebug"
        const val TAG_ACTIVITY = "FileEditorActivity"
        private const val SECTION_SETUP = 0
        private const val SECTION_LOG = 1
        private const val SECTION_RESULT = 2
        private const val STATE_SECTION = "state_section"
        const val EXTRA_PROJECTS_BASE_DIR = "projects_base_dir"
        const val RESULT_EXTRA_PROJECT_PATH = "project_path_result"
        const val EXTRA_APP_NAME = "app_name_result"
        const val EXTRA_PREFILL_APP_NAME = "prefill_app_name"
        const val EXTRA_PREFILL_APP_DESCRIPTION = "prefill_app_description"
        const val EXTRA_IS_AUTO_RETRY_ATTEMPT = "com.itsaky.androidide.IS_AUTO_RETRY_ATTEMPT"
        const val EXTRA_ENABLE_AUTO_FIX = "com.itsaky.androidide.ENABLE_AUTO_FIX"
        const val EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX = "com.itsaky.androidide.INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX"

        fun newIntent(context: Context, projectsBaseDir: String): Intent {
            return Intent(context, FileEditorActivity::class.java).apply {
                putExtra(EXTRA_PROJECTS_BASE_DIR, projectsBaseDir)
            }
        }
    }

    private lateinit var appNameAutocomplete: AutoCompleteTextView
    private lateinit var appDescriptionInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var providerSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var customModelInputLayout: TextInputLayout
    private lateinit var customModelInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var statusText: TextView
    private lateinit var logOutput: TextView
    private lateinit var aiConclusionOutput: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var actionButtonsLayout: LinearLayout
    private lateinit var continueButton: Button
    private lateinit var modifyFurtherButton: Button
    private lateinit var autoFixCheckbox: CheckBox
    private lateinit var autoDowngradeCheckbox: CheckBox
    private lateinit var requestCounterText: TextView
    private lateinit var appVersionLayout: LinearLayout
    private lateinit var appVersionSpinner: Spinner
    private lateinit var freeModelsOnlyCheckbox: CheckBox
    private lateinit var sectionTabs: TabLayout
    private lateinit var setupScroll: NestedScrollView
    private lateinit var logScroll: NestedScrollView
    private lateinit var resultScroll: NestedScrollView
    private lateinit var railTopButton: View
    private lateinit var railEndButton: View
    private lateinit var bottomBar: View
    private lateinit var bottomBarHandle: View
    private lateinit var setupSection: ScrollSection
    private lateinit var logSection: ScrollSection
    private lateinit var resultSection: ScrollSection
    private var currentSection: ScrollSection? = null
    private var barShown = true
    private var wasProcessing = false
    private var suppressAutoSwitch = false
    private var lastShownConclusion: String? = null

    private lateinit var viewModel: FileEditorViewModel
    private var thisInstanceIsOperatingInFullAuto: Boolean = false
    private var autoProceedAfterVmWorkIsPending: Boolean = false
    private var fetchedModelList: List<String> = emptyList()

    private val predefinedModels by lazy {
        listOf(
            "gemini-3.8-flash" to "Gemini 3.8 Flash",
            "gemini-3.7-flash" to "Gemini 3.7 Flash",
            "gemini-3.5-flash" to "Gemini 3.5 Flash",
            "gemini-2.5-pro" to "Gemini 2.5 Pro",
            "gpt-6-astra" to "GPT-6 Astra ($10/$50)",
            "gpt-6.1-sol" to "GPT-6.1 Sol ($2/$10)",
            "gpt-6-luna" to "GPT-6 Luna ($0.1/$0.5)"
        ).distinctBy { it.first }
    }

    private val customModelOptionText = "Enter Custom Model ID..."

    private fun isModelForProvider(modelId: String, provider: String): Boolean {
        val id = modelId.lowercase()
        return when (provider) {
            "Gemini" -> id.contains("gemini")
            "OpenAI" -> id.contains("gpt") || id.contains("o1") || id.contains("o3")
            else -> true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_editor)
        Log.d(DEBUG_TAG, "$TAG_ACTIVITY: onCreate START")

        supportActionBar?.title = "AI App Generator / Modifier"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel = ViewModelProvider(this)[FileEditorViewModel::class.java]
        viewModel.projectsBaseDir = File(intent.getStringExtra(EXTRA_PROJECTS_BASE_DIR) ?: filesDir.absolutePath)

        bindViews()
        setupSections()
        setupInitialUIValues()
        // After a rotation / process restore, don't yank the user to another tab.
        suppressAutoSwitch = savedInstanceState != null

        val prefillAppName = intent.getStringExtra(EXTRA_PREFILL_APP_NAME)
        val prefillAppDescription = intent.getStringExtra(EXTRA_PREFILL_APP_DESCRIPTION)
        val isThisAnIncomingAutoRetry = intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

        if (prefillAppName != null) appNameAutocomplete.setText(prefillAppName)
        if (prefillAppDescription != null) appDescriptionInput.setText(prefillAppDescription)

        if (isThisAnIncomingAutoRetry) {
            autoFixCheckbox.isChecked = true
            autoFixCheckbox.isEnabled = false
            thisInstanceIsOperatingInFullAuto = true
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) performGenerateOrFinalizeAction()
            }, 750)
        } else {
            autoFixCheckbox.isChecked = AutoFixStateManager.isAutoFixModeGloballyActive
            autoFixCheckbox.isEnabled = true
            thisInstanceIsOperatingInFullAuto = false
        }

        val incomingAutoFixDesc = intent.getStringExtra(EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX)
        if (!incomingAutoFixDesc.isNullOrBlank()) {
            appDescriptionInput.setText(incomingAutoFixDesc)
        } else if (prefillAppDescription != null) {
            appDescriptionInput.setText(prefillAppDescription)
        }

        setupListeners()
        observeViewModel()

        savedInstanceState?.getInt(STATE_SECTION, SECTION_SETUP)
            ?.takeIf { it != SECTION_SETUP }
            ?.let { selectSection(it) }
        window.decorView.post { suppressAutoSwitch = false }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.isLoading.value == true) {
                    Toast.makeText(this@FileEditorActivity, "Processing... Please wait.", Toast.LENGTH_SHORT).show()
                } else {
                    val isCurrentlyAnAutoRetry = intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)
                    if (!isCurrentlyAnAutoRetry && AutoFixStateManager.isAutoFixModeGloballyActive) {
                        AutoFixStateManager.disableAutoFixMode()
                    }
                    thisInstanceIsOperatingInFullAuto = false
                    setResult(Activity.RESULT_CANCELED)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        Log.d(DEBUG_TAG, "$TAG_ACTIVITY: onCreate END")
    }

    private fun bindViews() {
        appNameAutocomplete = findViewById(R.id.app_name_autocomplete)
        appDescriptionInput = findViewById(R.id.app_description_input)
        apiKeyInput = findViewById(R.id.api_key_input)
        providerSpinner = findViewById(R.id.provider_spinner)
        modelSpinner = findViewById(R.id.model_spinner)
        customModelInputLayout = findViewById(R.id.custom_model_input_layout)
        customModelInput = findViewById(R.id.custom_model_input)
        generateButton = findViewById(R.id.generate_app_button)
        statusText = findViewById(R.id.status_text)
        logOutput = findViewById(R.id.log_output)
        aiConclusionOutput = findViewById(R.id.ai_conclusion_output)
        loadingIndicator = findViewById(R.id.loading_indicator)
        actionButtonsLayout = findViewById(R.id.action_buttons_layout)
        continueButton = findViewById(R.id.continue_button)
        modifyFurtherButton = findViewById(R.id.modify_further_button)
        autoFixCheckbox = findViewById(R.id.auto_fix_checkbox)
        autoDowngradeCheckbox = findViewById(R.id.auto_downgrade_checkbox)
        requestCounterText = findViewById(R.id.request_counter_text)
        appVersionLayout = findViewById(R.id.app_version_layout)
        appVersionSpinner = findViewById(R.id.app_version_spinner)
        freeModelsOnlyCheckbox = findViewById(R.id.free_models_only_checkbox)

        sectionTabs = findViewById(R.id.section_tabs)
        setupScroll = findViewById(R.id.setup_scroll)
        logScroll = findViewById(R.id.log_scroll)
        resultScroll = findViewById(R.id.result_scroll)
        railTopButton = findViewById(R.id.rail_top_button)
        railEndButton = findViewById(R.id.rail_end_button)
        bottomBar = findViewById(R.id.bottom_bar)
        bottomBarHandle = findViewById(R.id.bottom_bar_handle)
        // NOTE: log and conclusion are plain selectable TextViews inside ONE page scroller each.
        // No fixed heights, no ScrollingMovementMethod, no key listeners needed.
    }

    private fun setupInitialUIValues() {
        apiKeyInput.setText(viewModel.storedApiKey)

        val providers = listOf("Gemini", "OpenAI")
        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providers).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        setupModelSpinnerInternal(viewModel.storedModelId)
        val initialModelId = getSelectedModelIdFromUi()
        updateModelBrandingForSelection(initialModelId, selectedDisplayName = null)
    }

    private fun setupModelSpinnerInternal(initialModelId: String) {
        val provider = providerSpinner.selectedItem?.toString() ?: "Gemini"

        val baseList = if (fetchedModelList.isNotEmpty()) {
            fetchedModelList.filter { isModelForProvider(it, provider) }
        } else {
            predefinedModels.map { it.first }.filter { isModelForProvider(it, provider) }
        }

        val filteredList = if (freeModelsOnlyCheckbox.isChecked && provider == "Gemini") {
            baseList.filter { it.contains("flash", ignoreCase = true) && !it.contains("pro", ignoreCase = true) }
        } else {
            baseList
        }

        val displayNames = filteredList.toMutableList().also { it.add(customModelOptionText) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner.adapter = adapter

        val effectiveInitialModelId = if (isModelForProvider(initialModelId, provider)) initialModelId else displayNames.firstOrNull() ?: DEFAULT_GEMINI_MODEL
        val predefinedIndex = displayNames.indexOf(effectiveInitialModelId)

        if (predefinedIndex != -1) {
            modelSpinner.setSelection(predefinedIndex, false)
            customModelInputLayout.visibility = View.GONE
            customModelInput.text = null
        } else {
            val customIndex = displayNames.indexOf(customModelOptionText)
            modelSpinner.setSelection(if (customIndex != -1) customIndex else 0, false)
            customModelInputLayout.visibility = View.VISIBLE
            customModelInput.setText(effectiveInitialModelId)
        }
    }

    private fun updateModelBrandingForSelection(modelId: String, selectedDisplayName: String?) {
        val provider = providerSpinner.selectedItem?.toString() ?: "Gemini"

        findViewById<TextInputLayout>(R.id.api_key_layout)?.hint = "$provider API Key"
        findViewById<TextView?>(R.id.model_label)?.text = "$provider Model"
        customModelInputLayout.hint = "Custom $provider Model Code (e.g. gemini-3.8-flash)"
    }

    private fun performGenerateOrFinalizeAction(isTriggeredForImmediateReturn: Boolean = false) {
        var appName = appNameAutocomplete.text.toString().trim()
        if (appName.isBlank()) {
            appName = "TetrisGame"
            appNameAutocomplete.setText(appName)
        }

        var currentDescription = appDescriptionInput.text.toString().trim()
        if (currentDescription.isBlank()) {
            currentDescription = "A complete, simple black and white Tetris game in Kotlin for Android using standard Canvas drawing and a basic game loop. No external engines."
            appDescriptionInput.setText(currentDescription)
        }

        val apiKey = apiKeyInput.text.toString().trim()
        val selectedModelId = getSelectedModelIdFromUi()
        val isThisAnIncomingAutoRetryIntent = intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

        if (!isTriggeredForImmediateReturn) {
            var isValid = true

            if (apiKey.isBlank()) { apiKeyInput.error = "API Key required"; isValid = false }
            else { apiKeyInput.error = null }

            if (!isValid) {
                Toast.makeText(this, "Please fill the API Key.", Toast.LENGTH_SHORT).show()
                if (autoFixCheckbox.isChecked) AutoFixStateManager.disableAutoFixMode()
                thisInstanceIsOperatingInFullAuto = false
                return
            }

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(generateButton.windowToken, 0)
        }

        viewModel.saveApiKey(apiKey)
        viewModel.saveSelectedModel(selectedModelId)

        if (!isTriggeredForImmediateReturn) {
            if (!isThisAnIncomingAutoRetryIntent) {
                if (autoFixCheckbox.isChecked) {
                    AutoFixStateManager.enableAutoFixMode(currentDescription)
                    thisInstanceIsOperatingInFullAuto = true
                } else {
                    AutoFixStateManager.disableAutoFixMode()
                    thisInstanceIsOperatingInFullAuto = false
                }
            }
            val selectedVersion = if (appVersionLayout.visibility == View.VISIBLE && appVersionSpinner.selectedItem != null) {
                val selectedItemStr = appVersionSpinner.selectedItem.toString()
                if (selectedItemStr.startsWith("Latest")) null else selectedItemStr
            } else null

            viewModel.initiateWorkflow(appName, currentDescription, apiKey, selectedModelId, selectedVersion)

        } else {
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_APP_NAME, appName)
            viewModel.currentProjectDirVM.value?.let {
                resultIntent.putExtra(RESULT_EXTRA_PROJECT_PATH, it.absolutePath)
            }
            if (isThisAnIncomingAutoRetryIntent) {
                resultIntent.putExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, true)
            }
            resultIntent.putExtra(EXTRA_ENABLE_AUTO_FIX, AutoFixStateManager.isAutoFixModeGloballyActive)
            AutoFixStateManager.initialAppDescriptionForGlobalAutoFix?.let { desc ->
                if (AutoFixStateManager.isAutoFixModeGloballyActive) {
                    resultIntent.putExtra(EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX, desc)
                }
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun setupListeners() {
        generateButton.setOnClickListener {
            performGenerateOrFinalizeAction()
        }

        autoFixCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (autoFixCheckbox.isEnabled) {
                val currentDesc = appDescriptionInput.text.toString().trim()
                if (isChecked) {
                    AutoFixStateManager.enableAutoFixMode(if (currentDesc.isNotBlank()) currentDesc else "Tetris game")
                } else {
                    AutoFixStateManager.disableAutoFixMode()
                }
            }
        }

        autoDowngradeCheckbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDowngradeEnabled(isChecked)
        }

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = providerSpinner.selectedItem?.toString() ?: "Gemini"
                if (provider == "Gemini") {
                    freeModelsOnlyCheckbox.visibility = View.VISIBLE
                } else {
                    freeModelsOnlyCheckbox.visibility = View.GONE
                    freeModelsOnlyCheckbox.isChecked = false
                }

                fetchedModelList = emptyList()
                setupModelSpinnerInternal(getSelectedModelIdFromUi())

                val selectedModelIdNow = getSelectedModelIdFromUi()
                updateModelBrandingForSelection(selectedModelIdNow, null)
                // NEW: Update ViewModel immediately on provider change
                viewModel.saveSelectedModel(selectedModelIdNow)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        freeModelsOnlyCheckbox.setOnCheckedChangeListener { _, _ ->
            setupModelSpinnerInternal(getSelectedModelIdFromUi())
            // NEW: Update ViewModel immediately
            viewModel.saveSelectedModel(getSelectedModelIdFromUi())
        }

        apiKeyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && apiKeyInput.text.toString().isNotBlank()) {
                val provider = providerSpinner.selectedItem.toString()

                if (provider != "Gemini") freeModelsOnlyCheckbox.isChecked = false

                Toast.makeText(this, "Fetching available models...", Toast.LENGTH_SHORT).show()

                viewModel.fetchModelsFromApi(provider, apiKeyInput.text.toString()) { models ->
                    if (models.isNotEmpty()) {
                        fetchedModelList = models
                        setupModelSpinnerInternal(getSelectedModelIdFromUi())
                        Toast.makeText(this, "Loaded ${models.size} models.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Could not load dynamic models. Check API Key.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDisplayName = modelSpinner.selectedItem?.toString() ?: ""
                val selectedModelIdNow = getSelectedModelIdFromUi()
                updateModelBrandingForSelection(selectedModelIdNow, selectedDisplayName)

                if (selectedDisplayName == customModelOptionText) {
                    customModelInputLayout.visibility = View.VISIBLE
                    customModelInputLayout.post { customModelInputLayout.requestFocus() }
                } else {
                    customModelInputLayout.visibility = View.GONE
                    customModelInput.text = null
                }
                updateGenerateButtonTextAndRole()

                // NEW: Update ViewModel immediately on model change
                viewModel.saveSelectedModel(selectedModelIdNow)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // NEW: Live counter update when typing a custom model ID
        customModelInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (modelSpinner.selectedItem?.toString() == customModelOptionText) {
                    viewModel.saveSelectedModel(getSelectedModelIdFromUi())
                }
            }
        })

        continueButton.setOnClickListener {
            if (AutoFixStateManager.isAutoFixModeGloballyActive) {
                AutoFixStateManager.disableAutoFixMode()
            }
            thisInstanceIsOperatingInFullAuto = false
            if (viewModel.isLoading.value == false && viewModel.actionButtonsVisible.value == true && viewModel.currentProjectDirVM.value != null) {
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_APP_NAME, appNameAutocomplete.text.toString().trim())
                    putExtra(RESULT_EXTRA_PROJECT_PATH, viewModel.currentProjectDirVM.value!!.absolutePath)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this, "Not ready to continue or project path missing.", Toast.LENGTH_SHORT).show()
            }
        }

        modifyFurtherButton.setOnClickListener {
            if (AutoFixStateManager.isAutoFixModeGloballyActive) {
                AutoFixStateManager.disableAutoFixMode()
            }
            thisInstanceIsOperatingInFullAuto = false

            viewModel.updateStateBridge(AiWorkflowState.IDLE)
            appDescriptionInput.text?.clear()
            selectSection(SECTION_SETUP)
            appDescriptionInput.requestFocus()
            afterNextLayout(appDescriptionInput) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(appDescriptionInput, InputMethodManager.SHOW_IMPLICIT)
            }

            Toast.makeText(this, "Ready for further modifications.", Toast.LENGTH_SHORT).show()
        }

        appNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val enteredName = s.toString().trim()
                val isExisting = enteredName.isNotEmpty() && (viewModel.existingProjectNames.value?.contains(enteredName) == true)
                viewModel.updateIsModifyingProjectFlag(isExisting)
                if (!isExisting) viewModel.onProjectNameClearedOrNew()
            }
        })

        appNameAutocomplete.setOnItemClickListener { _, _, _, _ ->
            val selectedAppName = appNameAutocomplete.text.toString()
            viewModel.updateIsModifyingProjectFlag(true)
            viewModel.onProjectNameSelected(selectedAppName)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(appNameAutocomplete.windowToken, 0)
            appNameAutocomplete.clearFocus()
        }

        // The description grows up to 14 lines and only then scrolls by itself. Claim the touch
        // from the page scroller ONLY while the field can really scroll; otherwise the page scrolls.
        appDescriptionInput.setOnTouchListener { v, event ->
            val field = v as TextView
            val canScroll = field.canScrollVertically(1) || field.canScrollVertically(-1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (canScroll) v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun updateGenerateButtonTextAndRole() {
        val isModifying = viewModel.isModifyingProject.value ?: false
        val isLoading = viewModel.isLoading.value ?: false
        val vmState = viewModel.workflowState.value ?: AiWorkflowState.IDLE

        if (isLoading) {
            generateButton.text = "Processing..."
            return
        }
        if (actionButtonsLayout.visibility == View.VISIBLE) {
            generateButton.text = if (isModifying) getString(R.string.button_modify_again) else getString(R.string.button_generate_new_app)
        } else {
            when (vmState) {
                AiWorkflowState.IDLE, AiWorkflowState.ERROR -> {
                    generateButton.text = if (isModifying) getString(R.string.button_modify_app_with_ai) else getString(R.string.button_generate_app_with_ai)
                }
                AiWorkflowState.READY_FOR_ACTION -> {
                    generateButton.text = if (isModifying) getString(R.string.button_apply_modifications_and_build) else getString(R.string.button_build_generated_app)
                }
                else -> generateButton.text = "Processing..."
            }
        }
    }

    private fun getSelectedModelIdFromUi(): String {
        val selectedSpinnerPosition = modelSpinner.selectedItemPosition
        val provider = providerSpinner.selectedItem?.toString() ?: "Gemini"

        val baseList = if (fetchedModelList.isNotEmpty()) {
            fetchedModelList.filter { isModelForProvider(it, provider) }
        } else {
            predefinedModels.map { it.first }.filter { isModelForProvider(it, provider) }
        }

        val filteredList = if (freeModelsOnlyCheckbox.isChecked && provider == "Gemini") {
            baseList.filter { it.contains("flash", ignoreCase = true) && !it.contains("pro", ignoreCase = true) }
        } else {
            baseList
        }

        val displayNames = filteredList.toMutableList().also { it.add(customModelOptionText) }
        val selectedDisplayName = displayNames.getOrElse(selectedSpinnerPosition) { "" }

        return if (selectedDisplayName == customModelOptionText) {
            customModelInput.text.toString().trim().ifBlank { DEFAULT_GEMINI_MODEL }
        } else {
            selectedDisplayName
        }
    }

    private fun showDowngradeDialog(data: DowngradeData) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Model Unavailable")
            .setMessage("The model '${data.failedModel}' is unavailable (${data.reason}).\n\nDo you want to continue automatically with the next available model: '${data.fallbackModel}'?")
            .setPositiveButton("Continue with ${data.fallbackModel}") { _, _ ->
                setupModelSpinnerInternal(data.fallbackModel)
                updateModelBrandingForSelection(data.fallbackModel, null)
                data.resumeAction.invoke()
                Toast.makeText(this, "Resuming with ${data.fallbackModel}...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                viewModel.handleErrorBridge("Generation aborted. Model unavailable: ${data.reason}")
            }
            .setCancelable(false)
            .show()
    }

    private fun observeViewModel() {
        viewModel.versionsUiVisible.observe(this) { isVisible ->
            appVersionLayout.visibility = if (isVisible) View.VISIBLE else View.GONE
            val isLoading = viewModel.isLoading.value ?: false
            appVersionSpinner.isEnabled = !isLoading && isVisible
        }

        viewModel.projectVersions.observe(this) { versions ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, versions ?: emptyList())
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            appVersionSpinner.adapter = adapter
        }

        viewModel.workflowState.observe(this) { state ->
            updateUiBasedOnState(state)
            val isReady = state == AiWorkflowState.READY_FOR_ACTION
            val notLoading = viewModel.isLoading.value == false
            if (isReady && notLoading && thisInstanceIsOperatingInFullAuto && AutoFixStateManager.isAutoFixModeGloballyActive && !autoProceedAfterVmWorkIsPending) {
                autoProceedAfterVmWorkIsPending = true
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed && thisInstanceIsOperatingInFullAuto && AutoFixStateManager.isAutoFixModeGloballyActive) {
                        performGenerateOrFinalizeAction(isTriggeredForImmediateReturn = true)
                    }
                    autoProceedAfterVmWorkIsPending = false
                }, 300)
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            val isProcessing = isLoading ?: false

            // Generation just started -> show the log so the user sees progress.
            if (isProcessing && !wasProcessing && !suppressAutoSwitch) selectSection(SECTION_LOG)
            wasProcessing = isProcessing

            loadingIndicator.visibility = if (isProcessing) View.VISIBLE else View.GONE
            generateButton.isEnabled = !isProcessing

            appDescriptionInput.isFocusable = !isProcessing
            appDescriptionInput.isFocusableInTouchMode = !isProcessing
            appDescriptionInput.isCursorVisible = !isProcessing

            appNameAutocomplete.isEnabled = !isProcessing
            apiKeyInput.isEnabled = !isProcessing
            providerSpinner.isEnabled = !isProcessing
            modelSpinner.isEnabled = !isProcessing

            customModelInputLayout.isEnabled = !isProcessing
            customModelInput.isEnabled = !isProcessing
            autoFixCheckbox.isEnabled = !isProcessing && !intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

            val vmState = viewModel.workflowState.value
            val isReadyForActionFromVm = vmState == AiWorkflowState.READY_FOR_ACTION
            val showActionButtons = isReadyForActionFromVm && !isProcessing
            actionButtonsLayout.visibility = if (showActionButtons) View.VISIBLE else View.GONE
            continueButton.isEnabled = showActionButtons
            modifyFurtherButton.isEnabled = showActionButtons

            if (isProcessing) {
                generateButton.text = "Processing..."
            } else {
                updateGenerateButtonTextAndRole()
            }
        }

        viewModel.logOutputText.observe(this) { text ->
            logOutput.text = text
            // Follow the tail only while the user is at the end; never yank them away from
            // something they scrolled up to read.
            if (logSection.followTail) logSection.scrollToEndAfterLayout()
            else afterNextLayout(logScroll) { refreshRail() }
        }
        viewModel.aiConclusion.observe(this) { conclusion ->
            val hasConclusion = !conclusion.isNullOrBlank()
            aiConclusionOutput.text = if (hasConclusion) "AI Conclusion:\n$conclusion\n" else ""
            sectionTabs.getTabAt(SECTION_RESULT)?.text = if (hasConclusion) "Result •" else "Result"
            if (hasConclusion && conclusion != lastShownConclusion) {
                lastShownConclusion = conclusion
                resultSection.scrollToTop(animated = false)
                if (!suppressAutoSwitch && !thisInstanceIsOperatingInFullAuto) selectSection(SECTION_RESULT)
            } else if (!hasConclusion) {
                lastShownConclusion = null
            }
        }
        viewModel.statusText.observe(this) { text -> statusText.text = text }
        viewModel.actionButtonsVisible.observe(this) { visible ->
            val isLoadingVal = viewModel.isLoading.value ?: false
            val effectiveVisibility = if (visible && !isLoadingVal) View.VISIBLE else View.GONE
            if (actionButtonsLayout.visibility != effectiveVisibility) {
                actionButtonsLayout.visibility = effectiveVisibility
            }
            continueButton.isEnabled = visible && !isLoadingVal
            modifyFurtherButton.isEnabled = visible && !isLoadingVal
            updateGenerateButtonTextAndRole()
        }
        viewModel.isModifyingProject.observe(this) { updateGenerateButtonTextAndRole() }
        viewModel.existingProjectNames.observe(this) { names ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names ?: emptyList())
            appNameAutocomplete.setAdapter(adapter)
        }
        viewModel.uiErrorEvent.observe(this) { errorMessage ->
            errorMessage?.let {
                androidx.appcompat.app.AlertDialog.Builder(this@FileEditorActivity)
                    .setTitle("API Error (400 / Bad Request)")
                    .setMessage(it)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .show()

                thisInstanceIsOperatingInFullAuto = false
                AutoFixStateManager.disableAutoFixMode()
                viewModel.consumedUiErrorEvent()
            }
        }

        viewModel.downgradePromptEvent.observe(this) { downgradeData ->
            if (downgradeData != null) {
                showDowngradeDialog(downgradeData)
                viewModel.clearDowngradeEvent()
            }
        }

        viewModel.dailyRequestCount.observe(this) { (modelName, count) ->
            requestCounterText.text = "API Requests Today ($modelName): $count"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sections (Setup / Log / Result), sticky action bar and jump rail
    // ---------------------------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Runs [block] once after the next layout pass of [view] (forces one if none is pending). */
    private fun afterNextLayout(view: View, block: () -> Unit) {
        val observer = view.viewTreeObserver
        if (!observer.isAlive) {
            view.post { block() }
            return
        }
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val vto = view.viewTreeObserver
                if (vto.isAlive) vto.removeOnGlobalLayoutListener(this)
                block()
            }
        })
        view.requestLayout()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(sectionTabs.windowToken, 0)
    }

    private fun setupSections() {
        setupSection = ScrollSection(setupScroll, followsTail = false)
        logSection = ScrollSection(logScroll, followsTail = true)
        resultSection = ScrollSection(resultScroll, followsTail = false)
        currentSection = setupSection

        sectionTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let { applySection(it.position) }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            // Tapping the active tab again jumps to the top of its content.
            override fun onTabReselected(tab: TabLayout.Tab?) {
                currentSection?.scrollToTop(animated = true)
            }
        })

        railTopButton.setOnClickListener { currentSection?.scrollToTop(animated = true) }
        railEndButton.setOnClickListener { currentSection?.scrollToEnd(animated = true) }
        bottomBarHandle.setOnClickListener { showBottomBar() }

        // Keep content clear of the overlaying action bar, and keep the bar glued to the bottom
        // when its height changes (progress bar appears, Continue/Modify row appears, ...).
        bottomBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            val oldHeight = oldBottom - oldTop
            if (height != oldHeight) {
                bottomBar.post { syncContentInsets(height) }
                if (height > oldHeight && oldHeight > 0) {
                    showBottomBar() // new content in the bar (e.g. action buttons) -> make sure it is seen
                } else if (!barShown) {
                    bottomBar.translationY = (height + dp(12)).toFloat()
                }
            }
        }
    }

    private fun syncContentInsets(barHeight: Int) {
        val bottomPadding = barHeight + dp(8)
        for (section in listOf(setupSection, logSection, resultSection)) {
            val v = section.scroll
            if (v.paddingBottom != bottomPadding) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomPadding)
            }
        }
        if (logSection.followTail) logSection.scrollToEndAfterLayout()
        afterNextLayout(logScroll) { refreshRail() }
    }

    private fun selectSection(index: Int) {
        sectionTabs.getTabAt(index)?.select()
    }

    private fun applySection(index: Int) {
        setupScroll.visibility = if (index == SECTION_SETUP) View.VISIBLE else View.GONE
        logScroll.visibility = if (index == SECTION_LOG) View.VISIBLE else View.GONE
        resultScroll.visibility = if (index == SECTION_RESULT) View.VISIBLE else View.GONE
        currentSection = when (index) {
            SECTION_LOG -> logSection
            SECTION_RESULT -> resultSection
            else -> setupSection
        }
        if (index != SECTION_SETUP) hideKeyboard()
        showBottomBar()
        currentSection?.let { section ->
            if (section.followsTail && section.followTail) section.scrollToEndAfterLayout()
            afterNextLayout(section.scroll) { refreshRail() }
        }
    }

    private fun showBottomBar() {
        if (barShown) return
        barShown = true
        bottomBarHandle.visibility = View.GONE
        bottomBar.animate().cancel()
        bottomBar.animate().translationY(0f).setDuration(160).start()
    }

    private fun hideBottomBar() {
        if (!barShown) return
        barShown = false
        bottomBarHandle.visibility = View.VISIBLE
        bottomBar.animate().cancel()
        bottomBar.animate().translationY((bottomBar.height + dp(12)).toFloat()).setDuration(160).start()
    }

    /** Shows the top / end jump buttons only when there is something to jump to. */
    private fun refreshRail() {
        val section = currentSection ?: return
        val y = section.scroll.scrollY
        val remaining = section.maxScroll() - y
        railTopButton.visibility = if (y > dp(64)) View.VISIBLE else View.GONE
        railEndButton.visibility = if (remaining > dp(64)) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SECTION, sectionTabs.selectedTabPosition)
    }

    /**
     * Wraps one NestedScrollView: tail-following (log), hide/show of the action bar while the
     * user scrolls, and top/end jumping.
     */
    private inner class ScrollSection(
        val scroll: NestedScrollView,
        val followsTail: Boolean
    ) {
        var followTail: Boolean = followsTail
            private set
        private var programmatic = false
        private var accumulatedDy = 0

        init {
            scroll.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    if (!scroll.isShown) return@OnScrollChangeListener
                    if (!programmatic) {
                        if (followsTail) followTail = isAtEnd()
                        reactToUserScroll(scrollY, scrollY - oldScrollY)
                    }
                    if (currentSection === this) refreshRail()
                }
            )
        }

        fun maxScroll(): Int {
            val child = scroll.getChildAt(0) ?: return 0
            val viewport = scroll.height - scroll.paddingTop - scroll.paddingBottom
            return maxOf(0, child.height - viewport)
        }

        fun isAtEnd(): Boolean = scroll.scrollY >= maxScroll() - dp(32)

        fun scrollToTop(animated: Boolean) {
            if (animated) scroll.smoothScrollTo(0, 0) else jumpTo(0)
        }

        fun scrollToEnd(animated: Boolean) {
            val target = maxScroll()
            if (animated) scroll.smoothScrollTo(0, target) else jumpTo(target)
        }

        fun scrollToEndAfterLayout() {
            afterNextLayout(scroll) {
                if (followTail) scrollToEnd(animated = false)
                if (currentSection === this) refreshRail()
            }
        }

        private fun jumpTo(y: Int) {
            programmatic = true
            scroll.scrollTo(0, y)
            programmatic = false
        }

        // Scroll down -> slide the action bar away; scroll up, top or end -> bring it back.
        private fun reactToUserScroll(scrollY: Int, dy: Int) {
            if (dy == 0) return
            if (scrollY <= 0 || isAtEnd()) {
                accumulatedDy = 0
                showBottomBar()
                return
            }
            if ((dy > 0) != (accumulatedDy > 0)) accumulatedDy = 0
            accumulatedDy += dy
            if (accumulatedDy > dp(24)) hideBottomBar()
            else if (accumulatedDy < -dp(12)) showBottomBar()
        }
    }

    private fun updateUiBasedOnState(state: AiWorkflowState) {
        val isLoadingVal = viewModel.isLoading.value ?: false
        if (!isLoadingVal) updateGenerateButtonTextAndRole()
        val showActionButtons = state == AiWorkflowState.READY_FOR_ACTION && !isLoadingVal
        if (actionButtonsLayout.visibility != if (showActionButtons) View.VISIBLE else View.GONE) {
            actionButtonsLayout.visibility = if (showActionButtons) View.VISIBLE else View.GONE
        }
        continueButton.isEnabled = showActionButtons
        modifyFurtherButton.isEnabled = showActionButtons
    }
}