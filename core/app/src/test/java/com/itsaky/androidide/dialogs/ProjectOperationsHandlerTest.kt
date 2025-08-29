package com.itsaky.androidide.dialogs

import android.util.Log // <-- Import the Android Log class
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.mockkStatic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectOperationsHandlerTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @get:Rule
    val tempFolder = TemporaryFolder()

    @MockK(relaxUnitFun = true)
    private lateinit var mockBridge: ViewModelFileEditorBridge

    private lateinit var handler: ProjectOperationsHandler
    private lateinit var projectsBaseDir: File

    @Before
    fun setUp() {
        // --- THIS IS THE CRITICAL FIX ---
        // Mock the static Android Log class to prevent the test from crashing.
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0 // Specify type for overloaded methods
        every { Log.e(any(), any(), any()) } returns 0

        projectsBaseDir = tempFolder.newFolder("TestProjects")
        handler = ProjectOperationsHandler(
            projectsBaseDir = projectsBaseDir,
            directLogAppender = { },
            directErrorHandler = { _, _ -> },
            bridge = mockBridge
        )
    }

    @Test
    fun `createVersionedCopy creates initial linear version correctly`() {
        // ARRANGE
        val sourceProject = File(projectsBaseDir, "MyTestApp").apply { mkdirs() }
        File(sourceProject, "MainActivity.kt").writeText("...")

        // ACT
        val newVersionDir = handler.createVersionedCopy(sourceProject, "MyTestApp")

        // ASSERT
        assertNotNull(newVersionDir)
        assertEquals("MyTestApp_v1", newVersionDir!!.name)
        assertTrue(File(newVersionDir, "MainActivity.kt").exists())
    }

    @Test
    fun `createVersionedCopy creates subsequent linear version correctly`() {
        // ARRANGE
        val sourceProject = File(projectsBaseDir, "MyTestApp").apply { mkdirs() }
        File(projectsBaseDir, "MyTestApp_v1").apply { mkdirs() }

        // ACT
        val newVersionDir = handler.createVersionedCopy(sourceProject, "MyTestApp")

        // ASSERT
        assertNotNull(newVersionDir)
        assertEquals("MyTestApp_v2", newVersionDir!!.name)
    }

    @Test
    fun `createVersionedCopy creates a new branch correctly`() {
        // ARRANGE
        val sourceProject = File(projectsBaseDir, "MyTestApp").apply { mkdirs() }
        File(projectsBaseDir, "MyTestApp_v1").apply { mkdirs() }
        File(projectsBaseDir, "MyTestApp_v2").apply { mkdirs() }

        val bookmarkFile = File(sourceProject, ".version_source")
        bookmarkFile.writeText("MyTestApp_v1")

        // ACT
        val newVersionDir = handler.createVersionedCopy(sourceProject, "MyTestApp")

        // ASSERT
        assertNotNull(newVersionDir)
        assertEquals("MyTestApp_v1.1", newVersionDir!!.name)
        assertFalse(File(newVersionDir, ".version_source").exists())
        assertFalse(bookmarkFile.exists())
    }

    @Test
    fun `createVersionedCopy creates a subsequent branch version correctly`() {
        // ARRANGE
        val sourceProject = File(projectsBaseDir, "MyTestApp").apply { mkdirs() }
        File(projectsBaseDir, "MyTestApp_v1").apply { mkdirs() }
        File(projectsBaseDir, "MyTestApp_v1.1").apply { mkdirs() }

        File(sourceProject, ".version_source").writeText("MyTestApp_v1")

        // ACT
        val newVersionDir = handler.createVersionedCopy(sourceProject, "MyTestApp")

        // ASSERT
        assertNotNull(newVersionDir)
        assertEquals("MyTestApp_v1.2", newVersionDir!!.name)
    }

    @Test
    fun `overwriteProjectWithVersion replaces content and creates bookmark for branching`() {
        // ARRANGE
        val baseProject = File(projectsBaseDir, "MyTestApp").apply { mkdirs() }
        File(baseProject, "old_file.txt").writeText("old")

        val versionProject = File(projectsBaseDir, "MyTestApp_v1").apply { mkdirs() }
        File(versionProject, "new_file.txt").writeText("new")
        
        // ACT
        val success = handler.overwriteProjectWithVersion("MyTestApp", "MyTestApp_v1", shouldBranch = true)

        // ASSERT
        assertTrue(success)
        assertFalse(File(baseProject, "old_file.txt").exists())
        assertTrue(File(baseProject, "new_file.txt").exists())
        
        val bookmark = File(baseProject, ".version_source")
        assertTrue(bookmark.exists())
        assertEquals("MyTestApp_v1", bookmark.readText())
    }

    @Test
    fun `overwriteProjectWithVersion does NOT create bookmark for reset`() {
        // ARRANGE
        val baseProject = File(projectsBaseDir, "MyTestApp").apply { mkdirs() }
        File(projectsBaseDir, "MyTestApp_v1").apply { mkdirs() }
        
        // ACT
        val success = handler.overwriteProjectWithVersion("MyTestApp", "MyTestApp_v1", shouldBranch = false)

        // ASSERT
        assertTrue(success)
        assertFalse(File(baseProject, ".version_source").exists())
    }
}