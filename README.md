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

* GPT-5 is the best model available currently but generating a single snake app costs around $0.30. I would not recommend it becuase this app is also not perfectly optimized yet to reduce expensive tokens. For example even tasks that could be done with a cheaper model will be done by the expensive GPT-5 model if you choose it from the dropdown menu.

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
*  **[Kiki.dev former Appacella](https://www.kiki.dev/)** is a commercial non-open source app for coding apps. It has 30 free itterations a month and 5 per day. Build time is fast and similar to this project. It uses an additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent)** to transfear the build app to your phone. Quality was nat as good during my testing but they could easily upgrade to GPT-5. But they very likely host their own models to save cost.
*  **[Rork](https://rork.com)** is a commercial non-open source app for coding apps. It has 7 free itterations per day and you will likely need all of them to build one simple tetris game. Build time is fast and similar to this project. It uses an additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent)** to transfear the build app to your phone.

### 💻 Alternative Full IDEs or Software (mostly focused on programmers, some at casual users too) 
* [Cursor](https://www.cursor.com/), [Windsurf](https://windsurf.com/editor), [Github Copiliot inside VS](https://github.com/features/copilot) (free for students), [Cline](https://cline.bot/), [Trae](https://www.trae.ai/), [Claude Code](https://www.anthropic.com/claude-code), [Augment Code](https://www.augmentcode.com/), [Roocode](https://github.com/RooCodeInc/Roo-Code), [Void](https://voideditor.com/), [Zed AI](https://zed.dev/ai), [Aider](https://aider.chat/), [Lovable](https://lovable.dev/), [bolt](https://bolt.new/), [Firebase Studio](https://firebase.studio/), [Manus](https://manus.im/guest), [Junie](https://jb.gg/try_junie​), [LocalSite-ai](https://github.com/weise25/LocalSite-ai), [base44](https://base44.com/), [AugmentCode](https://www.augmentcode.com/), [Alibaba's Qoder](https://qoder.com/), [Vercel's v0](https://www.v0.app), [Replit](https://replit.com/ai), [CreateAnything](https://www.createanything.com/)

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

## 📜 License

```
AppCoder is based on AndroidIDE which is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

AndroidIDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with AndroidIDE. If not, see <https://www.gnu.org/licenses/>.
```














