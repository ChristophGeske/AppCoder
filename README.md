# 📱 AppCoder: Turn Your App Ideas into Reality – Right On Your Phone!

**AppCoder** is a mobile Android app that empowers you to build your own Android apps directly on your phone — no programming experience required! You only need to describe your app and it will be built automatically.

---

## 🚀 Get Started with the Early Alpha Release!

**The First AppCoder Version is ready for you to test!**

1.  **⬇️ Download the APK:**
    *   Go to the **[latest release page](https://github.com/ChristophGeske/AppCoder/releases)**
    *   Download `app-arm64-v8a-release.apk` or another .apk according to your device type.

2.  **🔑 Get Your Free Gemini API Key:**
    *   AppCoder needs a free Gemini API key to generate code automatically.
    *   Visit Google AI Studio: [https://aistudio.google.com/apikey](https://aistudio.google.com/apikey)
    *   Sign in and click "Create API key". Copy this key (ideally to your phone's clipboard).

3.  **📲 Install & Use AppCoder:**
    *   See the **[latest release page](https://github.com/ChristophGeske/AppCoder/releases)** for instructions.

4. **🤓 Keep it Simple:**
   * Stick to simple games and proof-of-concept apps, especially when you are just starting out.

5. **📲 Check Back for Updates:**
   * So many more improvements are possible to make this app better, so please check back later if you like this concept!

---

## 💡 What is AppCoder?

AppCoder transforms your plain English descriptions into working app code using advanced LLMs like **Google's Gemini 3.8 Flash** and **OpenAI's GPT-6 lineup**. 

Simply describe your app vision, and AppCoder handles the code generation.

**Who is AppCoder for?**
*   **Non-programmers:** Create apps without technical barriers.
*   **Experienced Developers:** Rapidly prototype ideas. Export the generated code from your phone's device manager to your desktop to continue development with more professional tools. 

**What is AppCoder able to do?**

- Currently, the limiting factor determining how complex the app can be is the LLM running in the background and the inability of the current AppCoder version to observe the running app and use the logs to fix issues. Both limitations are being actively worked on.

- AppCoder uses **Gemini 3.8 Flash** as the recommended default. This model is incredibly fast, completely free on the Google AI Studio tier, and one of the best choices for simple programming tasks. It can easily generate apps like a to-do list, tic-tac-toe, or even a Tetris game. 

- **Custom Model Input:** If you want to use a model that isn't in the dropdown list—like the more powerful **gemini-3.1-pro**, or even future models like **gemini-4** when they drop—you can simply select "Enter Custom Model ID..." and type the model name directly into the app!

- A snake game is around the complexity that works well "zero-shot" (on the first try). More complex apps likely require you to build up the app step-by-step, one feature at a time.

- You currently get a free Gemini API key with each Google account. I'm not sure how long Google can keep this up with other providers charging high prices for output tokens, but right now there is an incredibly generous free limit. **Keep an eye on the in-app Request Counter to see if you are hitting your free tier limits!**

- You also have the option to choose **OpenAI models** (like GPT-6 Luna, Sol, and Astra) in the dropdown menu. While they might allow for highly capable reasoning, they are not free and require an active API billing account.

- Generating a simple app like Snake takes **Gemini 3.8 Flash** only about 20 seconds.

- In my testing, I saw the best performance using the Gemini Flash/Pro models, the GPT-6 lineup, and Claude 3.5 Sonnet. *Tip: You can iterate on your app idea quickly using Claude/HTML in your browser first. Once you're done iterating on your app idea, you can paste that code into AppCoder's prompt to automatically convert it into an Android app.*

- If the app is too complex, even the most advanced LLMs will generate unusable code, fail to build, crash on install, or contain major bugs. To give you an example, I tried the following prompt:

> *"Create an app that shows me a list of the latest news from reddit using the r/science subreddit. The app should also contain a filter to filter out topics one doesn't like. The filter stores a list of words and when they appear in the title those news are not displayed. The app checks new entries when opened but keeps already loaded links in cache. Pressing the title of an article in the app loads the reddit article."*

  Even with advanced models and many iterations passing back the error, the build failed. I therefore switched to a simpler prompt:

> *"Create an app that shows me a list of the latest news from reddit using the r/science subreddit. Just list them on the main screen using the reddit api."*

  With this, I only had to do 2 automatic iterations (where AppCoder passes build errors back to the LLM) to end up with a working app showing me the news in a list! From this state, you can slowly add features checking the results in a test-build-cycle.

- The very first build takes about 10 minutes to set up the Android environment. Follow-up builds typically take ~60 seconds on modern phones, from description to installation.

---

## ✨ Alternatives / Existing Tools

The space of programming helpers that use LLMs is relatively new and rapidly becoming crowded, as LLMs and programming are a natural match. The performance of LLMs on programming and math tasks is expected to improve significantly, with some experts predicting that all coding tasks will eventually be handled by LLMs. The main reason for this is that code can be automatically generated and checked, which results in lots of synthetic data available for training these LLMs.

The most well-known tools are IDEs with advanced LLM and agent integrations, designed to help both professional and beginner programmers generate better code more efficiently. Cursor and Copilot might be the most prominent examples, with many more being available. This project is similar to that idea, with the difference of running on the phone, being focused on Android alone, and being completely free and open source. 

In contrast to programming helpers, no-code tools receive less attention, likely due to LLMs’ current limitations in common sense reasoning, which still requires human oversight. Currently, LLMs perform best at low-level tasks but are increasingly capable of implementing entire features. Non-programmers, who can manage higher-level design and testing, are becoming more effective collaborators for guiding these more capable LLMs. As LLMs continue to improve, a growing share of apps will be developed by non-programmers.

The focus of the AppCoder project is to target these non-programmers. Coding directly on a phone is not ideal when direct inline code modification is required, so professional developers will likely use a standard IDE instead. A desktop IDE is generally more powerful and flexible, but a phone-based IDE offers ease of use—and combined with an LLM that handles the coding, quickly building apps on mobile devices becomes possible.

### 📱 Alternative App Creation Tools
*  **[Kiki.dev (formerly Appacella)](https://www.kiki.dev/)** A closed-source website for coding smartphone apps. It has 30 free iterations a month and 5 per day. Build time is fast and similar to this project. It uses an additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent) to transfer the built app to your phone. Quality was not as good during my testing, but they could easily upgrade to better models. 
*  **[Rork](https://rork.com)** Similar website also using the additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent).
*  **[Anything](https://www.createanything.com/)** Similar website also using the Expo Go App. They allow you to choose the latest coding models like Claude 3.5 Sonnet which should be able to create good code. However, in my testing, the resulting Tetris app I built for testing purposes was not working well despite very long generation times. They are also free of charge for the first few apps.
*  **[Essential Apps Beta (Nothing Phone)](https://playground.nothing.tech/apps)** Is as of today (Feb. 22, 2026) still behind a waiting list and seems to be a Nothing phone exclusive application. Some more info can be found [here](https://nothing.community/en/d/52739-essential-apps-enters-beta).

### 💻 Alternative Full IDEs or Software (mostly focused on programmers, some at casual users too) 
- [Cursor](https://www.cursor.com/), [OpenAl Codex](https://developers.openai.com/codex), [Devin by Cognition](https://app.devin.ai/), [Windsurf by Cognition](https://windsurf.com/editor), [Github Copilot](https://github.com/features/copilot), [Gemini Code Assist](https://codeassist.google/), [Claude Code](https://www.anthropic.com/claude-code), [Cline](https://cline.bot/), [Trae](https://www.trae.ai/), [Augment Code](https://www.augmentcode.com/), [Roo Code](https://github.com/RooCodeInc/Roo-Code), [Void](https://voideditor.com/), [Zed](https://zed.dev/), [Aider](https://aider.chat/), [Lovable](https://lovable.dev/), [Bolt](https://bolt.new/), [Firebase Studio](https://firebase.studio/), [Manus](https://manus.im/guest), [Junie](https://jb.gg/try_junie​), [LocalSite-ai](https://github.com/weise25/LocalSite-ai), [Base44](https://base44.com/), [Alibaba's Qoder](https://qoder.com/), [Vercel's v0](https://www.v0.app), [Replit](https://replit.com/ai), [Replit Agent 3](https://replit.com/agent3), [CreateAnything](https://www.createanything.com/), [Google's Jules](https://jules.google.com), [Z-AI’s GLM Coding](https://chat.z.ai/), [Factory AI](https://app.factory.ai/), [Blitzy](https://blitzy.com/), [Abacus.ai Deep Agent](https://deepagent-desktop.abacus.ai/), [Emergent](https://app.emergent.sh/landing), [Kilo Code](https://kilocode.ai/), [Warp Code](https://www.warp.dev), [Athas Code Editor](https://athas.dev/), [Zoer](https://zoer.ai/), [LingGuang](http://www.lingguang.com/), [Open Code](https://github.com/anomalyco/opencode), [MeDo](https://medo.dev/)

---

## 🛠️ For more serious Developers: Building AppCoder from Source

This section is for those who want to compile AppCoder from its source code. **If you just want to use and download the APK, you can skip this.**

Unfortunately, documentation is not available currently since too much is still changing. Here are some tips which I believe help in getting started if you really need to look into the code.

This might be interesting for you if you want to implement your own LLM API calls or if you want to implement advanced LLM agent features to improve the code generation like automated testing.

1.  **Clone this repository.**
2.  **Connect your Android smartphone** to your computer.
3.  **Build and install the AppCoder APK** onto your phone. Use **Android Studio Meerkat 2024.3.2** to match my setup and avoid version issues.
4.  **Development Setup Notes:**
    *   **Gradle JDK:** JDK 21.
5.  **Troubleshooting Builds from Source:**
    *   **Gradle build fails in Android Studio:** Try running the build process again.
    *   **Build of the *generated app* (by AppCoder) on the phone fails (during its terminal run):** Uninstall the partially built app and restart the build process within AppCoder.

## ⚙️ How AppCoder Works (The AI Pipeline)

AppCoder uses a "Single-Shot" autonomous pipeline. Single-shot saves on API request calls, but possibly there are more token-efficient ways than dumping all the code into the LLM (for the first call it is fine since the base code is tiny anyway and easily fits in the context window of most models). Here is exactly what happens when you press **Generate**:

### 1. Context Gathering 📂
- **New Apps:** AppCoder instantly creates a standard Android/Gradle skeleton project (SDK 21-34) on your phone.
- **Existing Apps:** AppCoder scans your project directory, skipping heavy build folders and caching only your actual code files (`.kt`, `.xml`, `.gradle`).

### 2. The AI Request 🧠
Your prompt and all your project files are bundled into a single, massive context window and sent to the LLM.
- **Dynamic Fallbacks:** If the selected model is overloaded (HTTP 503) or out of quota (HTTP 429), AppCoder automatically prompts you to downgrade to the next available model in the chain so you don't lose your progress.
- **Strict Adherence:** The AI is strictly locked into a JSON Schema. It is not allowed to return conversational text—it must return exact file paths and complete code blocks.

### 3. Code Application 💾
AppCoder intercepts the JSON response, validates it, and writes the new/modified files directly to your phone's local storage.

### 4. Auto-Build & The Auto-Fix Loop 🛠️
Once the files are saved, AppCoder hands the project over to the local Gradle daemon to compile the APK.
- **✅ If the build succeeds:** The app is installed on your phone.
- **❌ If the build fails:** AppCoder enters the **Auto-Fix Loop**. It silently intercepts the raw Gradle compiler errors, sends them *back* to the LLM alongside your buggy code, and asks the AI to patch its own mistakes. (AppCoder will attempt this up to 3 times automatically).

```text
[ User Prompt ] + [ Project Files ] 
        │
        ▼
 🌐 LLM Processing (Gemini / OpenAI / Claude)
   (Auto-Downgrades on server non available - asks user to confirm)
        │
        ▼
[ JSON Code Output ] 
        │
        ▼
💾 Files Written to Phone Storage
        │
        ▼
⚙️ Gradle Auto-Build
        ├──❌ Build Fails ──▶ (Sends Compiler Errors to LLM) ──┐
        │                                                      │
        ▼                                                  [ Auto-Fix ]
✅ Build Succeeds                                              │
        │                                                      │
        ▼                                                      ▼
 📱 App Installed! ◀───────────────────────────────────────────┘
```

### 🗺️ Where to find things in the code

All AppCoder-specific code lives in `core/app/src/main/java/com/itsaky/androidide/dialogs/`. The rest of the app is mostly original AndroidIDE code.

| I want to change... | Look at |
|---|---|
| The main AI screen (inputs, model dropdown, buttons) | `dialogs/FileEditorActivity.kt` and `res/layout/activity_file_editor.xml` |
| Screen state and what happens on "Generate" | `FileEditorViewModel.kt`, `ViewModelFileEditorBridge.kt` |
| The step-by-step AI flow (summarize, select, plan, write, summary, build-fix) and all prompts | `GeminiWorkflowCoordinator.kt` |
| API calls, models, thinking settings, retries, JSON schemas, response parsing | `GeminiHelper.kt` |
| Chat history sent to the model | `GeminiConversation.kt` |
| Which project files are scanned, and how AI output is written to disk | `ProjectFileUtils.kt` |
| Project creation from a template, version snapshots and branching | `ProjectOperationsHandler.kt` |
| Auto-fix loop (retry after failed builds, attempt limit) | `AutoFixStateManager.kt` |
| Workflow states shown in the UI | `AiWorkflowState.kt` |
| Foreground service while the AI works | `services/AiForegroundService`, `AiServiceManager.kt` |
| Entry from the home screen to the AI editor | `activities/MainActivity.kt` (`buttonLaunchAiEditor`) |
| UI texts and button labels | `res/values/strings.xml` |
| What happens after a build succeeds or fails (snapshots, auto-fix trigger) | `handlers/EditorBuildEventListener.kt`, then `activities/editor/EditorHandlerActivity.kt` |
| The contract between the AI workflow and the UI | `dialogs/ViewModelFileEditorBridge.kt` |
| Where the API key and selected model are saved | `FileEditorViewModel.kt` (`SharedPreferences "GeminiPrefs_AppCoder_VM"`) |
| HTTP/JSON libraries | `core/app/build.gradle.kts` (OkHttp 4.10.0; JSON is Android’s `org.json`) |

*Troubleshooting: if Gradle fails with `Unsupported class file major version 69`, Gradle is running on JDK 25. Set the Gradle JDK to 21 in Android Studio (Settings → Build Tools → Gradle) and check `JAVA_HOME`.*

---

## 🔮 Future Ideas & Development Roadmap

This section outlines a roadmap of potential improvements and features for AppCoder. These are ideas I've collected over time. I don't know if or when we'll get there, but they all seem possible. Some ideas excite me more than others, and if vibe coding keeps improving as I expect, all these features might be written completely autonomously by LLMs soon.

**🧠 Core Intelligence & Context**
- **Persistent Conversation History:** Store generation history within project files so the AI remembers all previous changes, errors, and fixes, even after the app is reloaded.
- **Prompt Caching:** Implement context caching to avoid reloading and re-analyzing unchanged code, significantly reducing costs and generation time.
- **Session Recovery:** Automatically restore the full conversation context when a user returns to a project, ensuring a seamless workflow.

**🤖 Advanced Generation Strategies**
- **Multi-Model Routing:** Employ a "small-model-first" strategy for initial drafts and simple tasks, intelligently routing complex problems or reviews to more powerful, larger models. (Partly realized already)
- **Parallel Generation & A/B Testing:** Run multiple models (or the same model with different parameters) simultaneously to generate several versions of an app, allowing the user to choose the best one.
- **Automated Model Selection:** The system intelligently chooses the best model or combination of models based on the app's complexity, category, and historical performance data.
- **Model Performance Tracking:** Implement an internal ELO-style rating system that tracks which models and prompts succeed at which types of tasks to continuously optimize the selection process.
- **Refactoring & Modernization:** Analyze the user's existing projects and suggest or automatically apply improvements to code structure, dependencies, and style.
- **Backward Code Generation:** Plan the user's entire app architecture and skeleton first, then generate individual components in parallel using multiple LLMs for a potentially faster end-to-end process.

**🔧 Error Handling & Quality Assurance**
- **Live Logcat Integration:** Gain access to the running app's logs (Logcat) and crash reports to provide the AI with critical debugging information, moving beyond just build errors.
- **Test-Driven Development (TDD):** Automatically generate and run unit tests for logic and UI tests for user interactions before each build to catch regressions and validate functionality.
- **Security & Vulnerability Scanning:** Check for common security flaws like hardcoded credentials, insecure network requests, or data exposure, and suggest fixes.
- **Performance Profiling:** Automatically measure the generated app's memory usage, battery drain, and startup time, allowing the AI to suggest optimizations.
- **Unit Test Prototype App:** Build a test/prototype app first, allowing the user to try out different ideas like various slider versions or layouts, and use the feedback to iteratively improve the main app.

**💡 User Experience & Interface**
- **Interactive Chat & Brainstorming Mode:** Allow users to have a conversation with the AI to brainstorm and refine their app idea before committing to a full build.
- **Visual Prototyping:** Integrate an image model to generate mockups or a logo based on the app description, letting the user approve the visual design before code generation begins. Draw-a-UI allows users to sketch a simple wireframe of their app's interface, which a vision model then translates into a layout and functional components.
- **Voice Input:** Enable speech-to-text for describing the app and dictating modifications for a hands-free experience.
- **Adaptive UI:** Address UI issues like the code view jumping on input, and add features like collapsible code blocks, a full-screen mode, and smarter layout management.
- **Localization & Accessibility:** Build apps with internationalization (multi-language support) from the start and automatically check for accessibility issues.
- **Live Preview Environment:** Instead of waiting for a full build, generate a web-based preview of the app that updates in near real-time as the user modifies their description.

**🚀 Expanding Capabilities**
- **Device Integration & Sensor Showcase:** Provide app templates with examples of how to access phone sensors (GPS, camera, accelerometer) or how to add safe login functionalities, making the resulting apps easier to build for the AI and safer because it uses tested code parts.
- **Hybrid Template System:** Combine the reliability of human-vetted code templates for common app structures with the flexibility of AI generation for custom features.
- **Code-to-Design Translation:** Use vision models to analyze a screenshot of the generated app and compare it against the user's initial request for a visual feedback loop.
- **Multimodal Prompts:** Let users combine text, images (for style reference), and audio clips (for sound effects or instructions) into a single, rich prompt for the AI.

**🌐 Community & Ecosystem**
- **Community App Marketplace:** Allow users to publish their successful apps to a shared space where others can use, remix, and build upon them.
- **Developer Marketplace:** Connect users who have a successful prototype with professional developers to take their app to the next level.

**📊 Analytics & Learning**
- **Automated Prompt Optimization:** Anonymously analyze which app descriptions lead to successful builds and use that data to refine system prompts.
- **Feature Complexity Estimator:** Before generation, analyze the user's prompt to predict the probability of a successful build and manage user expectations.
- **Cost & Time Calculator:** Provide a real-time estimate of the API costs and time required for the generation process based on the app's complexity.
- **Visual Dependency Graph:** Generate a simple visual map showing how the different files and components in the project connect to each other, helping users understand the app's architecture.
- **AI-Powered Feature Board:** Convert a user's initial complex description into a Kanban or Trello-like board of features. The user can then prioritize, and the AI builds one feature at a time, ensuring a more stable, incremental development process.

**🔬 Research Directions**
- **Agent Architecture Benchmarking:** Empirically compare different agentic approaches against monolithic generation for app creation tasks.
- **Cost-Benefit Analysis:** Determine if multi-step, cheap-model-first strategies are ultimately more economical and faster than using a single, powerful model from the start.
- **Autonomous Improvement Agent:** Design an agent that can autonomously scan GitHub, research papers, and app store trends to discover and implement better coding patterns and features into the AppCoder system itself.
- 
## 📜 License

```text
AppCoder is based on AndroidIDE which is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

AndroidIDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with AndroidIDE. If not, see <https://www.gnu.org/licenses/>.
