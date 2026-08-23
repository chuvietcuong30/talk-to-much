package com.example.talktoomuch

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainViewModel : ViewModel() {
    private val _displayText = MutableLiveData("")
    val displayText: LiveData<String> = _displayText

    private val _isRecordButtonEnabled = MutableLiveData(true)
    val isRecordButtonEnabled: LiveData<Boolean> = _isRecordButtonEnabled

    private val _aiAnswerText = MutableLiveData<String?>(null)
    val aiAnswerText: LiveData<String?> = _aiAnswerText

    private var speechAvailable = false
    private var isListening = false
    private var sessionTranscript = ""
    private var currentPartial = ""

    fun initialize(
        speechAvailable: Boolean,
        hintText: String,
        speechNotSupportedText: String,
    ) {
        this.speechAvailable = speechAvailable
        isListening = false
        if (speechAvailable) {
            _isRecordButtonEnabled.value = true
            _displayText.value = hintText
        } else {
            _isRecordButtonEnabled.value = false
            _displayText.value = speechNotSupportedText
        }
    }

    fun canStartRecording(): Boolean = speechAvailable

    fun onListeningStarted(listeningText: String) {
        if (!isListening) {
            sessionTranscript = ""
            currentPartial = ""
        }
        isListening = true
        _displayText.value = listeningText
    }

    fun onRecordReleased(hintText: String) {
        if (sessionTranscript.isNotBlank() || currentPartial.isNotBlank()) {
            _displayText.value = mergeTranscriptAndPartial()
        } else if (isListening) {
            _displayText.value = hintText
        }
        isListening = false
    }

    fun onPermissionDenied(permissionDeniedText: String) {
        isListening = false
        _displayText.value = permissionDeniedText
    }

    fun requestAiAnswer(
        apiKey: String,
        model: String,
        thinkingText: String,
        noTranscriptText: String,
        missingKeyText: String,
        errorPrefixText: String,
    ) {
        val prompt = mergeTranscriptAndPartial().trim()
        if (prompt.isBlank()) {
            _aiAnswerText.value = noTranscriptText
            return
        }
        if (apiKey.isBlank()) {
            _aiAnswerText.value = missingKeyText
            return
        }

        _aiAnswerText.value = thinkingText
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                runCatching {
                    callGeminiGenerateContent(
                        apiKey = apiKey,
                        model = model,
                        userPrompt = prompt,
                    )
                }
            result
                .onSuccess { answer ->
                    _aiAnswerText.postValue(answer)
                }.onFailure { error ->
                    _aiAnswerText.postValue("$errorPrefixText ${error.message ?: "unknown"}")
                }
        }
    }

    fun onPartialResult(text: String) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return
        }
        isListening = true
        currentPartial = normalizedText
        _displayText.value = mergeTranscriptAndPartial()
    }

    fun onFinalResult(
        text: String?,
        hintText: String,
    ) {
        val resultText = text?.trim().orEmpty()
        if (resultText.isNotBlank()) {
            appendToSessionTranscript(resultText)
        }
        currentPartial = ""
        _displayText.value =
            if (sessionTranscript.isBlank()) {
                hintText
            } else {
                sessionTranscript
            }
        isListening = false
    }

    fun onRecognitionError(hintText: String) {
        if (sessionTranscript.isNotBlank() || currentPartial.isNotBlank()) {
            _displayText.value = mergeTranscriptAndPartial()
        } else if (isListening) {
            _displayText.value = hintText
        }
        isListening = false
    }

    private fun appendToSessionTranscript(text: String) {
        sessionTranscript =
            if (sessionTranscript.isBlank()) {
                text
            } else {
                "$sessionTranscript $text"
            }
    }

    private fun mergeTranscriptAndPartial(): String =
        when {
            sessionTranscript.isBlank() -> currentPartial
            currentPartial.isBlank() -> sessionTranscript
            else -> "$sessionTranscript $currentPartial"
        }

    private fun callGeminiGenerateContent(
        apiKey: String,
        model: String,
        userPrompt: String,
    ): String {
        val connection =
            (
                URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .openConnection() as HttpURLConnection
            ).apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

        val payload =
            JSONObject()
                .apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put("text", userPrompt),
                                ),
                            ),
                        ),
                    )
                }.toString()

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload)
        }

        val responseCode = connection.responseCode
        val responseBody =
            readBody(
                if (responseCode in 200..299) connection.inputStream else connection.errorStream,
            )
        connection.disconnect()

        if (responseCode !in 200..299) {
            throw IllegalStateException("HTTP $responseCode: $responseBody")
        }

        return extractTextFromCandidates(JSONObject(responseBody).optJSONArray("candidates"))
            ?: throw IllegalStateException("No text in AI response")
    }

    private fun readBody(stream: java.io.InputStream?): String {
        if (stream == null) {
            return ""
        }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    private fun extractTextFromCandidates(candidates: JSONArray?): String? {
        if (candidates == null) {
            return null
        }
        val builder = StringBuilder()
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val contentArray = content.optJSONArray("parts") ?: continue
            for (j in 0 until contentArray.length()) {
                val contentItem = contentArray.optJSONObject(j) ?: continue
                val text = contentItem.optString("text").trim()
                if (text.isNotEmpty()) {
                    if (builder.isNotEmpty()) {
                        builder.append('\n')
                    }
                    builder.append(text)
                }
            }
        }
        return builder.toString().ifBlank { null }
    }
}
