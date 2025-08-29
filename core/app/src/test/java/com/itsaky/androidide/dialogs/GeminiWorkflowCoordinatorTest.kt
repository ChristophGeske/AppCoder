package com.itsaky.androidide.dialogs

import android.content.Context
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class GeminiWorkflowCoordinatorTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var mockGeminiHelper: GeminiHelper
    @MockK(relaxUnitFun = true)
    private lateinit var mockBridge: ViewModelFileEditorBridge
    @MockK(relaxUnitFun = true)
    private lateinit var mockServiceManager: AiServiceManager
    @MockK
    private lateinit var mockFileScanner: ProjectFileScanner
    @MockK
    private lateinit var mockContext: Context

    private lateinit var coordinator: GeminiWorkflowCoordinator

    @Before
    fun setUp() {
        // --- FIX: The constructor now matches the new signature ---
        coordinator = GeminiWorkflowCoordinator(
            geminiHelper = mockGeminiHelper,
            directLogAppender = { },
            bridge = mockBridge,
            serviceManager = mockServiceManager,
            fileScanner = mockFileScanner
        )
        // --- END FIX ---

        // Standard mock setup
        every { mockBridge.getContextBridge() } returns mockContext
        every { mockFileScanner.scanProjectFiles(any()) } returns listOf("src/main/App.kt")
        every { mockBridge.currentProjectDirBridge = any() } returns Unit
        every { mockBridge.currentProjectDirBridge } returns File("/fake/path")

        // Immediately execute code on the UI thread
        val uiBlock = slot<() -> Unit>()
        every { mockBridge.runOnUiThreadBridge(capture(uiBlock)) } answers {
            uiBlock.captured.invoke()
        }
        
        // Allow API requests to be called
        justRun { mockGeminiHelper.sendApiRequest(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `startModificationFlow should scan files and request summaries`() {
        // ARRANGE
        val appName = "TestApp"
        val appDescription = "A simple app"
        val projectDir = File("/fake/path")
        val summariesSchema = "{ \"type\": \"object\" }" // Dummy schema
        every { mockGeminiHelper.getSummariesSchema() } returns summariesSchema

        // ACT
        coordinator.startModificationFlow(appName, appDescription, projectDir)

        // ASSERT
        // Verify the initial steps of the NEW pipeline
        verify {
            // 1. Starts the service
            mockServiceManager.startService(mockContext, "Analyzing project structure...")
            // 2. Scans the files
            mockFileScanner.scanProjectFiles(projectDir)
            // 3. Updates the UI state to SUMMARIZING
            mockBridge.updateStateBridge(AiWorkflowState.SUMMARIZING_FILES)
            // 4. Sends the first API request to get summaries
            mockGeminiHelper.sendApiRequest(
                contents = any(),
                callback = any(),
                modelIdentifierOverride = any(),
                responseSchemaJson = summariesSchema
            )
        }
    }
}