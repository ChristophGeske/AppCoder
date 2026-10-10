package com.itsaky.androidide.activities.editor

import android.content.Intent
import android.os.Bundle

class EditorActivityKt : EditorHandlerActivity() {

    companion object {
        const val EXTRA_AUTO_BUILD_PROJECT = "com.itsaky.androidide.AUTO_BUILD_PROJECT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // The parent class reads the flag and triggers the build here

        // IMMEDIATELY WIPE THE FLAG after the parent reads it.
        // This ensures that if Android restores or reopens this Activity from the background later,
        // the auto-build flag is completely gone.
        intent?.removeExtra(EXTRA_AUTO_BUILD_PROJECT)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        // Wipe it here as well in case the Activity was already running in the background
        intent?.removeExtra(EXTRA_AUTO_BUILD_PROJECT)
    }
}