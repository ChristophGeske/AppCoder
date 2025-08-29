package com.itsaky.androidide.dialogs

// The @RunWith annotation has been REMOVED.
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The @RunWith(AndroidJUnit4::class) line that was here is the cause of the error and has been deleted.
class LiveGeminiSummarizationTest {

    private val model = "gemini-1.5-flash"

    private val files = mapOf(
        "app/src/main/AndroidManifest.xml" to """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.basicapp">
                <application android:label="Basic App">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent(),

        "app/src/main/java/com/example/basicapp/MainActivity.kt" to """
            package com.example.basicapp
            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.material3.Text

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        Text("Hello, World!")
                    }
                }
            }
        """.trimIndent(),
        
        "app/src/main/java/com/example/basicapp/MainViewModel.kt" to """
            package com.example.basicapp
            import androidx.lifecycle.ViewModel
            
            class MainViewModel : ViewModel() {
                val greeting = "Hello from ViewModel"
            }
        """.trimIndent(),

        "app/build.gradle.kts" to """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android {
                namespace = "com.example.basicapp"
                compileSdk = 34
                defaultConfig {
                    minSdk = 24
                }
                buildFeatures {
                    compose = true
                }
            }
        """.trimIndent()
    )

    private fun getSummariesSchema(): String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("file_summaries", JSONObject().apply {
                put("type", "array")
                put("description", "An array of file paths and their concise one-sentence summaries.")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("file_path", JSONObject().apply { put("type", "string") })
                        put("summary", JSONObject().apply { put("type", "string") })
                    })
                    put("required", JSONArray().put("file_path").put("summary"))
                })
            })
        })
        put("required", JSONArray().put("file_summaries"))
    }.toString()

    @Test
    fun testLiveSummarizationApiCall() {
        println("--- LIVE API INTEGRATION TEST ---")
        println(">>> Please enter your Gemini API Key and press Enter:")
        val apiKey = readlnOrNull()

        if (apiKey.isNullOrBlank()) {
            println("API Key was not provided. Test skipped.")
            return
        }

        val appDescription = "A simple Tetris game"
        val contentBuilder = StringBuilder()
        contentBuilder.append("Goal: \"$appDescription\"\n\n")
        contentBuilder.append("Analyze the following files from a basic Android project and provide a one-sentence summary for each.\n\n")
        files.forEach { (path, content) ->
            contentBuilder.append("FILE: $path\n```\n$content\n```\n\n")
        }
        val promptText = contentBuilder.toString()

        val generationConfig = JSONObject().apply {
            put("temperature", 0.5)
            put("maxOutputTokens", 8192)
            put("response_mime_type", "application/json")
            put("response_schema", JSONObject(getSummariesSchema()))
        }
        
        val contents = JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", promptText))))
        
        val requestJson = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", generationConfig)
        }

        val client = OkHttpClient.Builder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val latch = CountDownLatch(1)
        var responseBody: String? = null
        var error: String? = null

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                error = "Network call failed: ${e.message}"
                latch.countDown()
            }
            override fun onResponse(call: Call, response: Response) {
                responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    error = "API Error (Code: ${response.code}): $responseBody"
                }
                latch.countDown()
            }
        })

        latch.await(120, TimeUnit.SECONDS)

        println("\n--- TEST RESULT ---")
        if (error != null) {
            println("ERROR: $error")
            assert(false) { "Test failed with an error: $error" }
        } else {
            println("SUCCESS: Raw LLM Response:")
            try {
                val prettyJson = JSONObject(responseBody).toString(4)
                println(prettyJson)
            } catch (e: Exception) {
                println(responseBody)
            }
            assert(!responseBody.isNullOrBlank()) { "Response body should not be empty" }
        }
    }
}