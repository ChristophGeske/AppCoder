# 📱 AppCoder: Turn Your App Ideas into Reality – Right On Your Phone!

**AppCoder** is a mobile Android app that empowers you to build your own Android apps directly on your phone — no programming experience required! You only need to describe your app and it will be build automatically.

---

## 🚀 Get Started with the Early Alpha Release!

**The First AppCoder Version is ready for you to test!**

1.  **⬇️ Download the APK:**
    *   Go to the **[latest release page](https://github.com/ChristophGeske/AppCoder/releases)**
    *   Download `app-arm64-v8a-release.apk` or another .apk according to your device type.

2.  **🔑 Get Your Free Gemini API Key:**
    *   AppCoder needs a free Gemini API key to generate code automatically.
    *   Visit Google AI Studio: [https://aistudio.google.com/apikey](https://aistudio.google.com/apikey)
    *   Sign in and click "Create API key". Copy this key ideally to your phones clipboard.

3.  **📲 Install & Use AppCoder:**
    *   See the **[latest release page](https://github.com/ChristophGeske/AppCoder/releases)** for instructions.

4. **🤓 Keep it Simple**
   * Stick to simple games and proof of concept apps, especially if it comes to long complex apps.

5. **📲 Check Back for Updates**
   * So many more improvements are possible to make this app better, so please check back later if you like this concept.
---

## 💡 What is AppCoder?

AppCoder transforms your plain English descriptions into working app code using advanced LLMs like **Google's Gemini 2.5 Pro** and **OpenAIs GPT-5 (ChatGPT)**. 

Simply describe your app vision, and AppCoder handles the code generation.

**Who is AppCoder for?**
*   **Non-programmers:** Create apps without technical barriers.
*   **Experienced Developers:** Rapidly prototype ideas. Export the generated code from your phone device manager to your desktop to continue development with more professional tools. 

**What is AppCoder able to do?**

* Currently, the limiting factor determining how complex the app can be is the LLM running in the background and the inability of the current AppCoder version to observe the running app and use the logs to fix issues. Both limitations are actively worked on.

* AppCoder uses Gemini 2.5 Pro with the thinking budget maximized as the default. This free model can generate simple apps like a to-do list, tic-tac-toe, or even a Tetris game. The 2.5 Pro model is completly free and one of the best choices for all kind of programming tasks. 

* A snake game is around the complexity that works well zero shot. More complex apps likely require you to build up the app step by step one feature at a time.

* You currently get a free Gemini API key with each Google account. It is possible to create multiple Google accounts to get multiple api keys each with generous token limits. Not sure how long Google can keep this up with all other providers charging around 10$ for 1 Million output tokens but right now there is no limit.

* You also have the option to choose GPT-5 in the dropdown menu. While it might allows for a slightly less buggy build, it is not free and requires a billing account.

* It takes Gemini 2.5 Pro about 3 minutes to generate a snake game code which is rather slow compared to about 20 seconds with 2.5 Flash. 

* GPT-5 seems to be the best model available currently but generating a single snake app costs around $0.30. I would not recommend it becuase this app is also not perfectly optimized yet to reduce expensive tokens. For example every file that makes up the app is touched and read again by a small model (flash or mini) when you modeify an app to improve it or fix errors.

* In my testing I saw the best performance using Gemini 2.5 Pro, GPT-5, GPT-5 Mini and Claude Sonnet 4. In the Anthropic app, you can itterate on your app idea quickly using HTML in the browser first. Once you're done itterating on your app idea, you can paste that code into AppCoder to make an android app from it. This might be the quickest way if you do the itterating work in HTML in the browser instead of waiting for the app to build and than itteration by building new apps.

* If the app is too complex, even the most advanced LLMs will generate unusable code, fail to build, crash on install, or contain major bugs. To give you an example. I tried the following Prompt with 2.5 Pro:
> *"Create an app that shows me a list of the latest news from reddit using the r/science subreddit. The app should also contain a filter to filter out topics one doesn't like. The filter stores a list of words and when they appear in the title those news are not displayed. The app checks new entries when opened but keeps already loaded links in cash. Pressing the title of an article in the app loads the reddit article."*

  Even with the 2.5 pro model and many itterations passing back the error the build failed all the time. I therefore switched to a simpler prompt:

> *"Create an app that shows me a list of the latest news from reddit using the r/science subreddit. Just list them on the main screen using the reddit api."*

  And now I only had to do 2 itterations passing build errors back to the LLM to end up with a working app showing me the news in a list. From this state you can slowly add features checking the reults in a test-build-cycle.

* The first build takes about 10 minutes. Follow-up builds typically take ~60 seconds on modern phones, from description to installation.

---

## ✨ Alternatives / Existing Tools

The space of programming helpers that use LLMs is relatively new and rapidly becoming crowded, as LLMs and programming are a natural match. The performance of LLMs on programming and math tasks is expected to improve significantly, with some experts predicting that all coding tasks will eventually be handled by LLMs. The main reason for that is that code can be automatically generated and checked which results in lots of synthetic data available for training these LLMs.

The most well-known tools are IDEs with advanced LLM and agent integrations, designed to help both professional and beginner programmers generate better code more efficiently. Cursor and Copilote might be the most prominent examples with many more being available. This project is similar to that idea with the difference of running on the phone, being focused on Android alone and being completly free and open source. 

In contrast programming helpers, no-code tools receive less attention, likely due to LLMs’ current limitations in common sense reasoning, which still requires human oversight. Currently, LLMs perform best at low-level tasks but are increasingly capable of implementing entire features. Non-programmers, who can manage higher-level design and testing, are becoming more effective collaborators for guiding these more capable LLMs. As LLMs continue to improve, a growing share of apps will be developed by non-programmers.

The focus of the AppCoder project is to targets these non-programmers. Coding directly on a phone is not ideal when direct in line code modification is required so professional developers will likely use a standard IDE instead. A desktop IDE is generally more powerful and flexible, but a phone-based IDE offers ease of use and combined with an LLM that handles the coding, quickly building apps on mobile devices becomes possible.

### 📱 Alternative App Creation Tools
*  **[Kiki.dev former Appacella](https://www.kiki.dev/)** a closed source website cor coding smartphone apps. It has 30 free itterations a month and 5 per day. Build time is fast and similar to this project. It uses an additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent)** to transfear the build app to your phone. Quality was not as good during my testing but they could easily upgrade to better models. 
*  **[Rork](https://rork.com)** Similar website also using the additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent)
*  **[Anything](https://www.createanything.com/)** Similar website also using the additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent). They allow you to choose the latest coding models like Claude Sonnet 4.5 which could be able to create good code. However in my testing the resulting tetris app I build for testing purposes was not working well despite very long generation times. They are also free of charge for the first few apps.

### 💻 Alternative Full IDEs or Software (mostly focused on programmers, some at casual users too) 
* [Cursor](https://www.cursor.com/), [OpenAl Codex](https://developers.openai.com/codex), [Devin by Cognition](https://app.devin.ai/), [Windsurf by Cognition](https://windsurf.com/editor), [Github Copiliot](https://github.com/features/copilot), [Gemini Code Assist](https://codeassist.google/), [Claude Code](https://www.anthropic.com/claude-code), [Cline](https://cline.bot/), [Trae](https://www.trae.ai/), [Augment Code](https://www.augmentcode.com/), [Roo Code](https://github.com/RooCodeInc/Roo-Code), [Void](https://voideditor.com/), [Zed](https://zed.dev/), [Aider](https://aider.chat/), [Lovable](https://lovable.dev/), [Bolt](https://bolt.new/), [Firebase Studio](https://firebase.studio/), [Manus](https://manus.im/guest), [Junie](https://jb.gg/try_junie​), [LocalSite-ai](https://github.com/weise25/LocalSite-ai), [Base44](https://base44.com/), [AugmentCode](https://www.augmentcode.com/), [Alibaba's Qoder](https://qoder.com/), [Vercel's v0](https://www.v0.app), [Replit](https://replit.com/ai), [Replit Agent 3](https://replit.com/agent3), [CreateAnything](https://www.createanything.com/), [Google's Jules](https://jules.google.com), [Z-AI’s GLM Coding](https://chat.z.ai/), [Factory AI](https://app.factory.ai/), [Blitzy](https://blitzy.com/), [Abacus.ai Deep Agent](https://deepagent-desktop.abacus.ai/), [Emergent](https://app.emergent.sh/landing), [Kilo Code](https://kilocode.ai/), [Warp Code](https://www.warp.dev), [Athas Code Editor](https://athas.dev/), [Zoer](https://zoer.ai/), [LingGuang](http://www.lingguang.com/), [Open Code](https://github.com/anomalyco/opencode), [MeDo](https://medo.dev/)
---

## 🛠️ For more serious Developers: Building AppCoder from Source

This section is for those who want to compile AppCoder from its source code. **If you just downloaded the APK, you can skip this.**

Unfortenatly a documentation is not available currently since to much is still changing. Here are some tips which I believe help in getting started if you realy need to look into the code.

This might be interesting for you if you want to implement your own LLM API calls or if you want to implement advanced LLM agent features to improve the code generation like automated testing and such features.

1.  **Clone this repository.**
2.  **Connect your Android smartphone** to your computer.
3.  **Build and install the AppCoder APK** onto your phone use **Android Studio Meerkat 2024.3.2** to match my setup and avoide version issues.
4.  **Development Setup Notes:**
    *   **Gradle JDK:** JDK 21.
5.  **Troubleshooting Builds from Source:**
    *   **Gradle build fails in Android Studio:** Try running the build process again.
    *   **Build of the *generated app* (by AppCoder) on the phone fails (during its terminal run):** Uninstall the partially built app and restart the build process within AppCoder.
6. You find the LLM related code under AppCoder\core\app\src\main\java\com\itsaky\androidide\dialogs. Also the package com.itsaky.androidide.activities.editor has modeficatons. The rest of the app is basically the original AndroidIDE code only modefied in a few areas for example getting it streamlined to better work for LLM based development.

---

## 🔮 Future Ideas & Development Roadmap

This section outlines a roadmap of potential improvements and features for AppCoder. These are ideas I've collected over time. I don't know if or when we'll get there, but they all seem possible. Some ideas excite me more than others, and if vibe coding keeps improving as I expect, all these features might be written completely autonomously by LLMs soon.

🧠 Core Intelligence & Context

**Persistent Conversation History:** Store generation history within project files so the AI remembers all previous changes, errors, and fixes, even after the app is reloaded.

**Prompt Caching:** Implement context caching to avoid reloading and re-analyzing unchanged code, significantly reducing costs and generation time.

**Session Recovery:** Automatically restore the full conversation context when a user returns to a project, ensuring a seamless workflow.

🤖 Advanced Generation Strategies

**Multi-Model Routing:** Employ a "small-model-first" strategy for initial drafts and simple tasks, intelligently routing complex problems or reviews to more powerful, larger models. (Partly realized already)

**Parallel Generation & A/B Testing:** Run multiple models (or the same model with different parameters) simultaneously to generate several versions of an app, allowing the user to choose the best one.

**Automated Model Selection:** The system intelligently chooses the best model or combination of models based on the app's complexity, category, and historical performance data.

**Model Performance Tracking:** Implement an internal ELO-style rating system that tracks which models and prompts succeed at which types of tasks to continuously optimize the selection process.

**Refactoring & Modernization:** Analyze the user's existing projects and suggest or automatically apply improvements to code structure, dependencies, and style. For example, if a new, better LLM is released or the app receives an update.

**Backward Code Generation:** Plan the user's entire app architecture and skeleton first, then generate individual components in parallel using multiple LLMs for a potentially faster end-to-end process.

🔧 Error Handling & Quality Assurance

**Live Logcat Integration:** Gain access to the running app's logs (Logcat) and crash reports to provide the AI with critical debugging information, moving beyond just build errors.

**Test-Driven Development (TDD):** Automatically generate and run unit tests for logic and UI tests for user interactions before each build to catch regressions and validate functionality.

**Security & Vulnerability Scanning:** Check for common security flaws like hardcoded credentials, insecure network requests, or data exposure, and suggest fixes.

**Performance Profiling:** Automatically measure the generated app's memory usage, battery drain, and startup time, allowing the AI to suggest optimizations.

**Unit Test Prototype App:** Build a test/prototype app first, allowing the user to try out different ideas like various slider versions or layouts, and use the feedback to iteratively improve the app.

💡 User Experience & Interface

**Interactive Chat & Brainstorming Mode:** Allow users to have a conversation with the AI to brainstorm and refine their app idea before committing to a full build.

**Visual Prototyping:** Integrate an image model to generate mockups or a logo based on the app description, letting the user approve the visual design before code generation begins, which could speed up development and give the AI a form of "paper prototype" to use when building the app. Draw-a-UI to allow users to sketch a simple wireframe of their app's interface, which a vision model then translates into a layout and functional components.

**Voice Input:** Enable speech-to-text for describing the app and dictating modifications for a hands-free experience.

**Adaptive UI:** Address UI issues like the code view jumping on input, and add features like collapsible code blocks, a full-screen mode, and smarter layout management.

**Localization & Accessibility:** Build apps with internationalization (multi-language support) from the start and automatically check for accessibility issues (contrast, touch targets, or even allow blind people to use voice to program apps for their special needs).

**Live Preview Environment:** Instead of waiting for a full build, generate a web-based preview of the app that updates in near real-time as the user modifies their description, shortening the iteration cycle.

🚀 Expanding Capabilities

**Device Integration & Sensor Showcase:** Provide app templates with examples of how to access phone sensors (GPS, camera, accelerometer) or how to add safe login functionalities, making the resulting apps easier to build for the AI and possibly safer because it uses tested code parts. The app could then receive badges like "guaranteed safe login" or "guaranteed safe payment module used."

**Hybrid Template System:** Combine the reliability of human-vetted code templates for common app structures (e.g., a to-do list backbone) with the flexibility of AI generation for custom features. This means the user searches for an app, possibly using a GitHub search for compatible apps behind the scenes, and can then adjust the apps to their liking. Or if it's an unfinished old prototype, continue developing it.

**Code-to-Design Translation:** Use vision models to analyze a screenshot of the generated app and compare it against the user's initial request for a feedback loop based on visual results.

**Multimodal Prompts:** Let users combine text, images (for style reference), and audio clips (for sound effects or instructions) into a single, rich prompt for the AI.

🌐 Community & Ecosystem

**Community App Marketplace:** Allow users to publish their successful apps to a shared space where others can use, remix, and build upon them. Could also simply be GitHub.

**Developer Marketplace:** Connect users who have a successful prototype with professional developers to take their app to the next level.

📊 Analytics & Learning

**Automated Prompt Optimization:** Anonymously analyze which app descriptions lead to successful builds and use that data to refine system prompts and guide users toward more effective requests.

**Feature Complexity Estimator:** Before generation, analyze the user's prompt to predict the probability of a successful build and manage user expectations.

**Cost & Time Calculator:** Provide a real-time estimate of the API costs and time required for the generation process based on the app's complexity. Also, during generation, display a cost progress bar so the user can decide to stop the run if API costs seem too high.

**User Data for Fine-Tuning:** With user consent, collect successful generation data to explore the feasibility of fine-tuning specialized models for Android development.

**Visual Dependency Graph:** Generate a simple visual map showing how the different files and components in the project connect to each other, helping users understand the app's architecture. Helps the user understand the structure of the generated code better. The current AppCoder version has an AI overview describing the steps the LLM took, but a visual graph might be more helpful and could be very relevant for finding bugs in the app, especially for programmers who know what might be causing a bug. The overview might be better than having to look at the bare code, which is likely hard to understand anyway when it's all auto-coded.

**AI-Powered Feature Board:** Convert a user's initial complex description into a Kanban or Trello-like board of features. The user can then prioritize, and the AI builds one feature at a time, ensuring a more stable, incremental development process. [Traycer](https://traycer.ai/) is offering a add on like this.

🔬 Research Directions

**Agent Architecture Benchmarking:** Empirically compare different agentic approaches (e.g., multi-agent debate, hierarchical planning) against monolithic generation for app creation tasks.

**Cost-Benefit Analysis:** Determine if multi-step, cheap-model-first strategies are ultimately more economical and faster than using a single, powerful model from the start.

**Autonomous Improvement Agent:** Design an agent that can autonomously scan GitHub, research papers, and app store trends to discover and implement better coding patterns and features into the AppCoder system itself. So many coding helper projects exist; automatically finding new features would be nice. Or simply offer the user different choices to personalize AppCoder for their needs. Personalized code is the future.


## 📜 License

```
AppCoder is based on AndroidIDE which is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

AndroidIDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with AndroidIDE. If not, see <https://www.gnu.org/licenses/>.
```
































