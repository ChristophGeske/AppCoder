package com.itsaky.androidide.dialogs

enum class AiWorkflowState {
    IDLE,

    // --- PROJECT SETUP PHASE (From your original flow) ---
    CREATING_PROJECT_TEMPLATE,
    PREPARING_EXISTING_PROJECT,

    // --- AI INTERACTION PHASE (New + Original) ---
    // New states for the initial analysis
    SUMMARIZING_FILES,
    // Original state, now used after summarization
    SELECTING_FILES,
    // Original state for the main code generation
    GENERATING_CODE,
    // Original state for the final summary
    GENERATING_SUMMARY,

    // State indicating the AI is done and waiting for the user to build
    READY_FOR_ACTION,

    // --- BUILD & FIX LOOP PHASE ---
    AWAITING_BUILD_RESULT,
    ANALYZING_BUILD_ERROR,

    // --- FINAL STATES ---
    ERROR
}