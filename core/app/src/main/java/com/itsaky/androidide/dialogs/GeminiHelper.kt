// File: com/itsaky/androidide/dialogs/GeminiHelper.kt
package com.itsaky.androidide.dialogs

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class AiFileInstruction(val filePath: String, val fileContent: String)
data class AiStructuredResponse(
    val filesToWrite: List<AiFileInstruction>? = null,
    val filesToDelete: List<String>? = null,
    val conclusion: String? = null
)
data class AiMinimalFilesResponse(val filesToWrite: List<AiFileInstruction>? = null)
data class AiSummaryResponse(val summary: String?)
data class FileModifications(
    val filesToWrite: Map<String, String>,
    val filesToDelete: List<String>,
    val conclusion: String?
)

data class MinimalBatchResult(
    val filesToWrite: Map<String, String>,
    val unchanged: List<String>
)

class GeminiHelper(
    private val apiKeyProvider: () -> String,
    private val errorHandlerCallback: (String, Exception?) -> Unit,
    private val uiThreadExecutor: (block: () -> Unit) -> Unit
) {
    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-1.5-flash"
        private const val RAW_LOG_TAG = "GemHelper_RAW"
        private const val PIPE = "AI_PIPELINE"

        private const val GEMINI_25_PRO_THINK_MAX = 32768
        private const val GEMINI_25_FLASH_MAX = 24576

        // Retry/fallback policy for transient overloads
        private val RETRYABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
        private const val MAX_HTTP_RETRIES = 3
        private fun backoffDelayMs(attempt: Int): Long {
            val base = (1000L shl attempt).coerceAtMost(8000L) // 1s, 2s, 4s, cap 8s
            val jitter = kotlin.random.Random.Default.nextLong(0L, 400L)
            return base + jitter
        }
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    var currentModelIdentifier: String = DEFAULT_GEMINI_MODEL
        private set

    fun setModel(modelId: String) {
        currentModelIdentifier = if (modelId.isNotBlank()) modelId else DEFAULT_GEMINI_MODEL
        Log.i("GeminiHelper", "API model set to: $currentModelIdentifier")
        Log.i(PIPE, "Model set to: $currentModelIdentifier")
    }

    internal fun JSONObject.unwrapDataIfPresent(): JSONObject = this.optJSONObject("data") ?: this
    internal fun JSONObject.optJSONArrayByKeys(vararg keys: String): JSONArray? {
        for (k in keys) {
            val arr = this.optJSONArray(k)
            if (arr != null) return arr
        }
        return null
    }
    internal fun JSONObject.optStringByKeys(vararg keys: String): String? {
        for (k in keys) {
            val v = this.optString(k, null)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    // --- Schemas used by the Coordinator ---
    internal fun getSummariesSchema(): String = JSONObject().apply {
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
                    put("additionalProperties", false)
                })
            })
        })
        put("required", JSONArray().put("file_summaries"))
        put("additionalProperties", false)
    }.toString()

    internal fun getFileModificationsSchema(): String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filesToWrite", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("description", "An array of files to write or overwrite with new content.")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("filePath", JSONObject().apply { put("type", "string") })
                        put("fileContent", JSONObject().apply { put("type", "string") })
                    })
                    put("required", JSONArray().put("filePath").put("fileContent"))
                    put("additionalProperties", false)
                })
            })
            put("filesToDelete", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("description", "An array of file paths to delete.")
                put("items", JSONObject().apply { put("type", "string") })
            })
            put("requestMoreFiles", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("description", "An array of additional file paths the AI needs to see to continue.")
                put("items", JSONObject().apply { put("type", "string") })
            })
            put("conclusion", JSONObject().apply {
                put("type", "string"); put("nullable", true)
                put("description", "A final summary of the changes once the entire task is complete.")
            })
        })
        // FIX: FÜGE DIESE ZEILE HINZU, UM ALLE PROPERTIES ALS VERPFLICHTEND ZU DEKLARIEREN
        put("required", JSONArray().put("filesToWrite").put("filesToDelete").put("requestMoreFiles").put("conclusion"))
        put("additionalProperties", false)
    }.toString()

    internal fun getMinimalFilesSchema(): String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filesToWrite", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("filePath", JSONObject().apply { put("type", "string") })
                        put("fileContent", JSONObject().apply { put("type", "string") })
                    })
                    put("required", JSONArray().put("filePath").put("fileContent"))
                    put("additionalProperties", false)
                })
            })
        })
        put("required", JSONArray().put("filesToWrite"))
        put("additionalProperties", false)
    }.toString()

    internal fun getSummaryOnlySchema(): String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("summary", JSONObject().apply {
                put("type", "string")
                put("description", "A concise summary of the provided code changes.")
            })
        })
        put("required", JSONArray().put("summary"))
        put("additionalProperties", false)
    }.toString()

    fun sendApiRequest(
        contents: List<JSONObject>,
        callback: (JSONObject) -> Unit,
        modelIdentifierOverride: String? = null,
        responseSchemaJson: String? = null,
        responseMimeTypeOverride: String? = "application/json"
    ) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) { errorHandlerCallback("API Key is not set.", null); return }

        val initialModelId = modelIdentifierOverride ?: currentModelIdentifier

        fun maskApiKeyInUrl(u: String): String = u.replace(Regex("(key=)([^&]+)"), "$1****")

        fun fallbackModelFor(modelId: String): String? {
            val id = modelId.lowercase()
            return when {
                id.startsWith("gemini-2.5-flash") -> "gemini-2.5-pro"
                id == "gpt-5-mini" -> "gpt-5"
                else -> null
            }
        }

        fun buildRequestForModel(modelId: String): Pair<Request, OkHttpClient>? {
            val isGptModel = modelId.startsWith("gpt-", ignoreCase = true)
            Log.i(PIPE, "sendApiRequest: model='$modelId', isGpt=$isGptModel, schemaProvided=${!responseSchemaJson.isNullOrBlank()}, responseMimeType=$responseMimeTypeOverride, contentsParts=${contents.size}")

            val requestJson = if (isGptModel) {
                buildOpenAiRequest(
                    modelId = modelId,
                    geminiContents = contents,
                    responseSchemaJson = responseSchemaJson
                )
            } else {
                buildGeminiRequest(contents, responseSchemaJson, responseMimeTypeOverride, modelId)
            } ?: return null

            val url = if (isGptModel)
                "https://api.openai.com/v1/chat/completions"
            else
                "https://generativelanguage.googleapis.com/v1beta/models/${modelId}:generateContent?key=$apiKey"

            val req = Request.Builder()
                .url(url)
                .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { if (isGptModel) addHeader("Authorization", "Bearer $apiKey") }
                .build()

            val http = clientForModel(modelId)
            Log.d(PIPE, "HTTP POST -> ${maskApiKeyInUrl(req.url.toString())}")
            return req to http
        }

        fun enqueueWithRetry(modelId: String, attempt: Int, fallbackUsed: Boolean) {
            val pair = buildRequestForModel(modelId) ?: return
            val (req, http) = pair

            http.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val retryableNet = (e is SocketTimeoutException) || (e is UnknownHostException)
                    if (retryableNet && attempt < MAX_HTTP_RETRIES) {
                        val delay = backoffDelayMs(attempt)
                        Log.w(PIPE, "HTTP failure (retryableNet=$retryableNet) on model=$modelId: ${e.javaClass.simpleName}; delay=${delay}ms; attempt=${attempt + 1}")
                        Thread {
                            try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                            enqueueWithRetry(modelId, attempt + 1, fallbackUsed)
                        }.start()
                        return
                    }
                    errorHandlerCallback("API Network Error ($modelId): ${e.message}", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    try {
                        if (!response.isSuccessful || responseBody == null) {
                            var detail = responseBody ?: "No error body"
                            val code = response.code
                            try {
                                val j = JSONObject(responseBody ?: "")
                                detail = j.optJSONObject("error")?.optString("message", detail) ?: j.optString("message", detail)
                            } catch (_: Exception) {}

                            val retryableHttp = RETRYABLE_HTTP_CODES.contains(code)
                            if (retryableHttp && attempt < MAX_HTTP_RETRIES) {
                                val delay = backoffDelayMs(attempt)
                                Log.w(PIPE, "HTTP <- code=$code (retryable). Retrying in ${delay}ms; attempt=${attempt + 1}; detail='${detail.take(256)}'")
                                Thread {
                                    try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                                    enqueueWithRetry(modelId, attempt + 1, fallbackUsed)
                                }.start()
                                return
                            }

                            if (retryableHttp && !fallbackUsed) {
                                val alt = fallbackModelFor(modelId)
                                if (!alt.isNullOrBlank()) {
                                    Log.w(PIPE, "HTTP <- code=$code (retryable) exhausted retries; falling back model: '$modelId' -> '$alt'")
                                    enqueueWithRetry(alt, 0, true)
                                    return
                                }
                            }

                            Log.e(PIPE, "HTTP <- error code=$code, detail=${detail.take(512)}")
                            errorHandlerCallback("API Error ($modelId - Code: $code): $detail", null)
                            return
                        }

                        Log.d(PIPE, "HTTP <- success code=${response.code}, bytes=${responseBody.length}")
                        val jsonResponse = JSONObject(responseBody)
                        uiThreadExecutor { callback(jsonResponse) }
                    } catch (e: Exception) {
                        errorHandlerCallback("Error processing API response ($modelId): ${e.message}", e)
                        Log.e(PIPE, "Error processing API response: ${e.message}")
                    } finally { response.body?.close() }
                }
            })
        }

        enqueueWithRetry(initialModelId, attempt = 0, fallbackUsed = false)
    }

    private fun clientForModel(modelId: String): OkHttpClient {
        val id = modelId.lowercase()
        val b = baseClient.newBuilder()
        if (id.startsWith("gpt-5")) {
            b.readTimeout(300, TimeUnit.SECONDS)
            b.writeTimeout(300, TimeUnit.SECONDS)
            b.connectTimeout(60, TimeUnit.SECONDS)
            b.pingInterval(30, TimeUnit.SECONDS)
            Log.d(PIPE, "clientForModel: tuned timeouts for $modelId (OpenAI gpt-5*)")
        }
        if (id.startsWith("gemini-2.5") || id.startsWith("gemini-1.5")) {
            b.readTimeout(300, TimeUnit.SECONDS)
            b.writeTimeout(300, TimeUnit.SECONDS)
            b.connectTimeout(60, TimeUnit.SECONDS)
            b.pingInterval(30, TimeUnit.SECONDS)
            Log.d(PIPE, "clientForModel: tuned timeouts for $modelId (Gemini 1.5/2.5)")
        }
        return b.build()
    }

    private fun buildOpenAiRequest(
        modelId: String,
        geminiContents: List<JSONObject>,
        responseSchemaJson: String?
    ): JSONObject {
        val userContent = StringBuilder().apply {
            geminiContents.forEach { content ->
                content.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text", "")
                        if (!t.isNullOrBlank()) append(t).append("\n")
                    }
                }
            }
        }.toString()

        val messages = JSONArray()
        if (!responseSchemaJson.isNullOrBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "You are an expert Android developer. You must respond using the provided JSON schema.")
            })
        }
        messages.put(JSONObject().apply { put("role", "user"); put("content", userContent) })

        val body = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
        }

        val supportsJsonSchemaFormat = run {
            val idLower = modelId.lowercase()
            // FIX: Allow 'gpt-5-mini' to use the strict schema enforcement
            idLower.startsWith("gpt-5") && !idLower.contains("nano")
        }
        if (!responseSchemaJson.isNullOrBlank()) {
            if (supportsJsonSchemaFormat) {
                Log.d(PIPE, "OpenAI response_format=json_schema for model=$modelId")
                body.put("response_format", JSONObject().apply {
                    put("type", "json_schema")
                    put("json_schema", JSONObject().apply {
                        put("name", "android_ide_schema")
                        put("strict", true)
                        put("schema", JSONObject(responseSchemaJson))
                    })
                })
            } else {
                Log.d(PIPE, "OpenAI response_format=json_object for model=$modelId (no schema format support)")
                body.put("response_format", JSONObject().put("type", "json_object"))
            }
        }
        return body
    }

    private fun removeUnsupportedKeys(json: Any): Any {
        when (json) {
            is JSONObject -> {
                val keysToRemove = mutableListOf<String>()
                val iterator = json.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    if (key == "additionalProperties") {
                        keysToRemove.add(key)
                    } else {
                        removeUnsupportedKeys(json.get(key))
                    }
                }
                for (key in keysToRemove) {
                    json.remove(key)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    removeUnsupportedKeys(json.get(i))
                }
            }
        }
        return json
    }

    private fun buildGeminiRequest(
        contents: List<JSONObject>,
        responseSchemaJson: String?,
        responseMimeTypeOverride: String?,
        effectiveModelIdentifier: String
    ): JSONObject? {
        val idLower = effectiveModelIdentifier.lowercase()
        val isGemini25 = idLower.startsWith("gemini-2.5")
        val isPro = idLower.startsWith("gemini-2.5-pro")
        val isFlash = idLower.startsWith("gemini-2.5-flash")

        val generationConfig = JSONObject().apply {
            put("temperature", 1)
            put("maxOutputTokens", 200000)
            put("topP", 0.95)
            put("topK", 40)

            if (!responseSchemaJson.isNullOrBlank()) {
                put("response_mime_type", responseMimeTypeOverride ?: "application/json")
                try {
                    val originalSchema = JSONObject(responseSchemaJson)
                    val cleanedSchema = removeUnsupportedKeys(originalSchema) as JSONObject
                    put("response_schema", cleanedSchema)
                    Log.d(PIPE, "Gemini generationConfig: response_schema included, mime=${responseMimeTypeOverride ?: "application/json"}")
                } catch (e: JSONException) {
                    errorHandlerCallback("Invalid response_schema JSON: ${e.message}", e)
                    return null
                }
            }

            if (isGemini25) {
                val budget = when {
                    isPro -> GEMINI_25_PRO_THINK_MAX
                    isFlash -> GEMINI_25_FLASH_MAX
                    else -> GEMINI_25_FLASH_MAX
                }
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", budget)
                    put("includeThoughts", true)
                })
                put("mediaResolution", "MEDIA_RESOLUTION_MEDIUM")
                Log.d(PIPE, "Gemini generationConfig: thinkingBudget=$budget, includeThoughts=true, mediaResolution=MEDIUM")
            }
        }

        return JSONObject().apply {
            put("contents", JSONArray(contents))
            put("generationConfig", generationConfig)
            put("safetySettings", JSONArray().apply {
                put(JSONObject().apply { put("category", "HARM_CATEGORY_HARASSMENT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_HATE_SPEECH"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_DANGEROUS_CONTENT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
            })
        }
    }

    fun extractTextFromApiResponse(response: JSONObject): String {
        try {
            if (response.has("candidates")) {
                val parts = response.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")

                if (parts == null || parts.length() == 0) {
                    Log.w("GeminiHelper", "API response has 'candidates' but no valid 'parts' array.")
                    return ""
                }

                var firstNonThought: String? = null
                var jsonLike: String? = null

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.has("thought")) {
                        continue
                    }

                    val raw = part.optString("text", "")
                    if (raw.isBlank()) continue

                    val text = unwrapCodeFences(raw).trim()
                    if (firstNonThought == null) firstNonThought = text

                    if ((text.startsWith("{") && text.endsWith("}")) ||
                        (text.startsWith("[") && text.endsWith("]"))
                    ) {
                        jsonLike = text
                        break
                    }
                }
                val out = jsonLike ?: firstNonThought ?: ""
                Log.d(PIPE, "extractText: source=Gemini, outLen=${out.length}, jsonLike=${jsonLike != null}")
                return out
            } else if (response.has("choices")) {
                val firstChoice = response.optJSONArray("choices")?.optJSONObject(0) ?: return ""
                val message = firstChoice.optJSONObject("message") ?: return ""
                val content = message.optString("content", "")
                if (content.isNotBlank()) {
                    Log.d(PIPE, "extractText: source=OpenAI, outLen=${content.length}")
                    return content
                }

                val args = message.optJSONArray("tool_calls")
                    ?.optJSONObject(0)
                    ?.optJSONObject("function")
                    ?.optString("arguments", "") ?: ""
                if (args.isNotBlank()) {
                    Log.d(PIPE, "extractText: source=OpenAI(tool), outLen=${args.length}")
                    return args
                }
                return ""
            } else {
                Log.w("GeminiHelper", "Could not find 'candidates' or 'choices' in API response.")
                return ""
            }
        } catch (e: Exception) {
            Log.e("GeminiHelper", "Error extracting content string from response", e)
            return ""
        }
    }

    private fun unwrapCodeFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            val newlineIdx = t.indexOf('\n')
            if (newlineIdx != -1) {
                // remove language hint like "json"
                t = t.substring(newlineIdx + 1)
            }
            if (t.endsWith("```")) t = t.removeSuffix("```")
        }
        return t.trim()
    }

    fun parseMinimalFilesResponse(jsonText: String): Map<String, String>? {
        val filesMap = mutableMapOf<String, String>()
        try {
            val root = JSONObject(jsonText).unwrapDataIfPresent()
            val arr = root.optJSONArrayByKeys("filesToWrite", "files_to_write")
            arr?.forEachObject { obj ->
                val path = obj.optStringByKeys("filePath", "file_path") ?: ""
                val content = obj.optStringByKeys("fileContent", "file_content") ?: ""
                if (path.isNotBlank()) filesMap[path] = content
            }
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiMinimalFilesResponse JSON: '$jsonText'. Error: ${e.message}", e)
            return null
        }
        return filesMap.takeIf { it.isNotEmpty() }
    }

    fun parseSummaryResponse(jsonText: String): String? {
        return try {
            val root = JSONObject(jsonText).unwrapDataIfPresent()
            val summary = root.optStringByKeys("summary", "conclusion")
            Log.d(PIPE, "parseSummaryResponse: hasSummary=${!summary.isNullOrBlank()}, len=${summary?.length ?: 0}")
            summary
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiSummaryResponse JSON: '$jsonText'. Error: ${e.message}", e)
            null
        }
    }

    private fun parseAiStructuredResponse(jsonText: String): AiStructuredResponse {
        val filesToWrite = mutableListOf<AiFileInstruction>()
        val filesToDelete = mutableListOf<String>()
        var conclusion: String? = null
        try {
            val root = JSONObject(jsonText).unwrapDataIfPresent()
            val writeArray = root.optJSONArrayByKeys("filesToWrite", "files_to_write")
            writeArray?.forEachObject { obj ->
                val path = obj.optStringByKeys("filePath", "file_path") ?: ""
                val content = obj.optStringByKeys("fileContent", "file_content") ?: ""
                if (path.isNotBlank()) filesToWrite.add(AiFileInstruction(path, content))
            }
            val deleteArray = root.optJSONArrayByKeys("filesToDelete", "files_to_delete")
            deleteArray?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (!s.isNullOrBlank()) filesToDelete.add(s)
                }
            }
            conclusion = root.optStringByKeys("conclusion", "summary")
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiStructuredResponse JSON: '$jsonText'. Error: ${e.message}", e)
        }
        Log.d(PIPE, "parseAiStructuredResponse: writes=${filesToWrite.size}, deletes=${filesToDelete.size}, hasConclusion=${!conclusion.isNullOrBlank()}")
        return AiStructuredResponse(
            filesToWrite = filesToWrite.takeIf { it.isNotEmpty() },
            filesToDelete = filesToDelete.takeIf { it.isNotEmpty() },
            conclusion = conclusion
        )
    }

    fun convertAiResponseToFileModifications(aiResponse: AiStructuredResponse): FileModifications {
        val filesToWriteMap = aiResponse.filesToWrite?.associate { it.filePath to it.fileContent } ?: emptyMap()
        Log.d(PIPE, "convertAiResponseToFileModifications: writes=${filesToWriteMap.size}, deletes=${aiResponse.filesToDelete?.size ?: 0}, hasConclusion=${!aiResponse.conclusion.isNullOrBlank()}")
        return FileModifications(filesToWriteMap, aiResponse.filesToDelete ?: emptyList(), aiResponse.conclusion)
    }

    fun parseAndConvertStructuredResponse(jsonText: String): FileModifications {
        val aiResponse = parseAiStructuredResponse(jsonText)
        return convertAiResponseToFileModifications(aiResponse)
    }

    fun extractJsonArrayFromText(text: String): String {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            val arr = text.substring(startIndex, endIndex + 1)
            Log.d(PIPE, "extractJsonArrayFromText: sliced array len=${arr.length}")
            return arr
        }
        val cleanedText = text.replace("```json", "").replace("```", "").trim()
        Log.d(PIPE, "extractJsonArrayFromText: cleaned text startsWith=[${cleanedText.startsWith("[")}], endsWith=]${cleanedText.endsWith("]")}")
        return if (cleanedText.startsWith("[") && cleanedText.endsWith("]")) cleanedText else "[]"
    }

    private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) {
            val obj = optJSONObject(i)
            if (obj != null) action(obj)
        }
    }
}