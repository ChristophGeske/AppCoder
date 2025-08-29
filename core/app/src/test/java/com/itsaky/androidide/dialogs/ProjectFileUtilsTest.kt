package com.itsaky.androidide.dialogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectFileUtilsTest {

    // This rule creates a new temporary folder before each test and deletes it after.
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `scanProjectFiles correctly finds code files and ignores build directories`() {
        // ARRANGE
        val projectRoot = tempFolder.root

        // Create a valid source file
        val srcMain = File(projectRoot, "app/src/main/java").apply { mkdirs() }
        File(srcMain, "MainActivity.kt").writeText("package com.example")

        // Create a file in a build directory that should be ignored
        val buildDir = File(projectRoot, "app/build/generated").apply { mkdirs() }
        File(buildDir, "Generated.kt").writeText("package com.generated")
        
        // Create a non-code file that should be ignored
        File(srcMain, "image.png").createNewFile()

        // ACT
        val scannedFiles = ProjectFileUtils.scanProjectFiles(projectRoot)

        // ASSERT
        assertEquals(1, scannedFiles.size)
        assertTrue(scannedFiles.contains("app/src/main/java/MainActivity.kt"))
        assertFalse(scannedFiles.contains("app/build/generated/Generated.kt"))
    }

    @Test
    fun `processFileChangesAndDeletions correctly writes and deletes files`() {
        // ARRANGE
        val projectRoot = tempFolder.root
        val fileToWrite = "app/src/main/res/values/strings.xml"
        val fileToDelete = "app/src/main/java/OldActivity.kt"
        
        // Create the file that will be deleted
        val oldFile = File(projectRoot, fileToDelete).apply { parentFile.mkdirs(); createNewFile() }
        assertTrue(oldFile.exists()) // Pre-condition check

        val filesToWrite = mapOf(fileToWrite to "<resources></resources>")
        val filesToDelete = listOf(fileToDelete)
        
        var successWrites = 0
        var successDeletes = 0

        // ACT
        ProjectFileUtils.processFileChangesAndDeletions(
            projectDir = projectRoot,
            filesToWrite = filesToWrite,
            filesToDelete = filesToDelete,
            logAppender = {},
            onComplete = { writeSuccessCount, _, deleteSuccessCount, _ ->
                successWrites = writeSuccessCount
                successDeletes = deleteSuccessCount
            }
        )

        // ASSERT
        val newFile = File(projectRoot, fileToWrite)
        assertTrue(newFile.exists())
        assertEquals("<resources></resources>", newFile.readText())
        assertFalse(oldFile.exists())
        
        assertEquals(1, successWrites)
        assertEquals(1, successDeletes)
    }
}