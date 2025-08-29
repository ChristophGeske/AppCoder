package com.itsaky.androidide.dialogs

import android.content.Context
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
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
        coordinator = GeminiWorkflowCoordinator(
            geminiHelper = mockGeminiHelper,
            directLogAppender = { },
            bridge = mockBridge,
            serviceManager = mockServiceManager,
            fileScanner = mockFileScanner
        )

        // Standard-Setup
        every { mockBridge.getContextBridge() } returns mockContext
        every { mockFileScanner.scanProjectFiles(any()) } returns listOf("src/main/App.kt")
        every { mockBridge.isModifyingExistingProjectBridge } returns true
        every { mockGeminiHelper.currentModelIdentifier } returns "gemini-pro"
        // WICHTIG: Der Coordinator setzt dieses Property. Wir müssen das mocken.
        every { mockBridge.currentProjectDirBridge = any() } returns Unit
        // Wenn der Getter aufgerufen wird, geben wir ein Dummy-File zurück.
        every { mockBridge.currentProjectDirBridge } returns File("/fake/path")

        // Führe Code auf dem UI-Thread sofort aus
        val uiBlock = slot<() -> Unit>()
        every { mockBridge.runOnUiThreadBridge(capture(uiBlock)) } answers {
            uiBlock.captured.invoke()
        }
        
        // Erlaube den Aufruf von sendApiRequest
        justRun { mockGeminiHelper.sendApiRequest(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `startModificationFlow should execute initial steps and call api`() {
        // ARRANGE
        val appName = "TestApp"
        val appDescription = "Eine einfache App"
        val projectDir = File("/fake/path")

        // ACT
        coordinator.startModificationFlow(appName, appDescription, projectDir)

        // ASSERT
        // Wir überprüfen einfach, ob die vier wichtigsten Aktionen stattgefunden haben.
        // Die Reihenfolge ist hierbei nicht entscheidend (unordered).
        verify(ordering = io.mockk.Ordering.UNORDERED) {
            mockServiceManager.startService(mockContext, "Generating code for $appName")
            mockBridge.updateStateBridge(AiWorkflowState.SELECTING_FILES)
            mockFileScanner.scanProjectFiles(projectDir)
            mockGeminiHelper.sendApiRequest(
                contents = any(),
                callback = any(),
                responseMimeTypeOverride = "application/json"
            )
        }
    }
}