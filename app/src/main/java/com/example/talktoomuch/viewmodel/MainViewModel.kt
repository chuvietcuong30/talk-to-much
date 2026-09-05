package com.example.talktoomuch.viewmodel

import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.talktoomuch.repository.model.ChatMessage
import com.example.talktoomuch.repository.model.GrammarResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.collections.plus

class MainViewModel : ViewModel() {
    private val _chatMessages = MutableLiveData<List<ChatMessage>>(emptyList())
    val chatMessages: LiveData<List<ChatMessage>> = _chatMessages

    private val _draftInputText = MutableLiveData("")
    val draftInputText: LiveData<String> = _draftInputText

    private val _isRecordButtonEnabled = MutableLiveData(true)
    val isRecordButtonEnabled: LiveData<Boolean> = _isRecordButtonEnabled

    private val _grammarResult = MutableLiveData<GrammarResult?>(null)
    val grammarResult: LiveData<GrammarResult?> = _grammarResult

    private var speechAvailable = false
    private var isListening = false
    private var sessionTranscript = ""
    private var currentPartial = ""
    private var nextMessageId = 0L

    fun initialize(
        speechAvailable: Boolean,
        speechNotSupportedText: String,
    ) {
        this.speechAvailable = speechAvailable
        isListening = false
        _draftInputText.value = ""
        if (speechAvailable) {
            _isRecordButtonEnabled.value = true
        } else {
            _isRecordButtonEnabled.value = false
            appendMessage(speechNotSupportedText, fromUser = false)
        }
    }

    fun canStartRecording(): Boolean = speechAvailable

    fun onListeningStarted() {
        if (!isListening) {
            sessionTranscript = ""
            currentPartial = ""
        }
        isListening = true
    }

    fun onRecordReleased() {
        _draftInputText.value = mergeTranscriptAndPartial()
        isListening = false
    }

    fun onPermissionDenied(permissionDeniedText: String) {
        isListening = false
        appendMessage(permissionDeniedText, fromUser = false)
    }

    fun sendTypedPrompt(
        rawText: String,
        apiKey: String,
        model: String,
        thinkingText: String,
        noTranscriptText: String,
        missingKeyText: String,
        errorPrefixText: String,
        statusCorrectText: String,
        statusIncorrectText: String,
        correctedLabel: String,
        explanationLabel: String,
        questionLabel: String,
    ) {
        val prompt = rawText.trim()
        _draftInputText.value = ""
        requestGrammarResult(
            prompt = prompt,
            apiKey = apiKey,
            model = model,
            thinkingText = thinkingText,
            noTranscriptText = noTranscriptText,
            missingKeyText = missingKeyText,
            errorPrefixText = errorPrefixText,
            statusCorrectText = statusCorrectText,
            statusIncorrectText = statusIncorrectText,
            correctedLabel = correctedLabel,
            explanationLabel = explanationLabel,
            questionLabel = questionLabel,
        )
    }

    fun sendRecordedPrompt(
        apiKey: String,
        model: String,
        thinkingText: String,
        noTranscriptText: String,
        missingKeyText: String,
        errorPrefixText: String,
        statusCorrectText: String,
        statusIncorrectText: String,
        correctedLabel: String,
        explanationLabel: String,
        questionLabel: String,
    ) {
        val prompt = mergeTranscriptAndPartial().trim()
        sessionTranscript = ""
        currentPartial = ""
        _draftInputText.value = ""
        requestGrammarResult(
            prompt = prompt,
            apiKey = apiKey,
            model = model,
            thinkingText = thinkingText,
            noTranscriptText = noTranscriptText,
            missingKeyText = missingKeyText,
            errorPrefixText = errorPrefixText,
            statusCorrectText = statusCorrectText,
            statusIncorrectText = statusIncorrectText,
            correctedLabel = correctedLabel,
            explanationLabel = explanationLabel,
            questionLabel = questionLabel,
        )
    }

    private fun requestGrammarResult(
        prompt: String,
        apiKey: String,
        model: String,
        thinkingText: String,
        noTranscriptText: String,
        missingKeyText: String,
        errorPrefixText: String,
        statusCorrectText: String,
        statusIncorrectText: String,
        correctedLabel: String,
        explanationLabel: String,
        questionLabel: String,
    ) {
        if (prompt.isBlank()) {
            appendMessage(noTranscriptText, fromUser = false)
            _grammarResult.value =
                GrammarResult(
                    isCorrect = false,
                    correctedSentence = "",
                    explanation = noTranscriptText,
                    questionOfAI = "",
                )
            return
        }

        appendMessage(prompt, fromUser = true)

        if (apiKey.isBlank()) {
            appendMessage(missingKeyText, fromUser = false)
            _grammarResult.value =
                GrammarResult(
                    isCorrect = false,
                    correctedSentence = "",
                    explanation = missingKeyText,
                    questionOfAI = "",
                )
            return
        }

        val thinkingMessageId = appendMessage(thinkingText, fromUser = false)
        _grammarResult.value =
            GrammarResult(
                isCorrect = false,
                correctedSentence = "",
                explanation = thinkingText,
                questionOfAI = "",
            )
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                runCatching {
                    callGeminiGenerateGrammarCheck(
                        apiKey = apiKey,
                        model = model,
                        userPrompt = prompt,
                    )
                }
            result
                .onSuccess { answer ->
                    val aiText =
                        formatGrammarResultForChat(
                            result = answer,
                            statusCorrectText = statusCorrectText,
                            statusIncorrectText = statusIncorrectText,
                            correctedLabel = correctedLabel,
                            explanationLabel = explanationLabel,
                            questionLabel = questionLabel,
                        )
                    updateMessage(thinkingMessageId, aiText)
                    _grammarResult.postValue(answer)
                }.onFailure { error ->
                    updateMessage(
                        thinkingMessageId,
                        "$errorPrefixText ${error.message ?: "unknown"}",
                    )
                    _grammarResult.postValue(
                        GrammarResult(
                            isCorrect = false,
                            correctedSentence = "",
                            explanation = "$errorPrefixText ${error.message ?: "unknown"}",
                            questionOfAI = "",
                        ),
                    )
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
        _draftInputText.value = mergeTranscriptAndPartial()
    }

    fun onFinalResult(text: String?) {
        val resultText = text?.trim().orEmpty()
        if (resultText.isNotBlank()) {
            appendToSessionTranscript(resultText)
        }
        currentPartial = ""
        _draftInputText.value = sessionTranscript
        isListening = false
    }

    fun onRecognitionError() {
        _draftInputText.value = mergeTranscriptAndPartial()
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

    private fun appendMessage(
        text: String,
        fromUser: Boolean,
    ): Long {
        val messageId = nextMessageId++
        val currentMessages = _chatMessages.value.orEmpty()
        val updatedMessages =
            currentMessages +
                ChatMessage(
                    id = messageId,
                    text = text,
                    fromUser = fromUser,
                )
        setOrPostChatMessages(updatedMessages)
        return messageId
    }

    private fun updateMessage(
        messageId: Long,
        newText: String,
    ) {
        val updatedMessages =
            _chatMessages.value
                .orEmpty()
                .map { message ->
                    if (message.id == messageId) {
                        message.copy(text = newText)
                    } else {
                        message
                    }
                }
        setOrPostChatMessages(updatedMessages)
    }

    private fun setOrPostChatMessages(messages: List<ChatMessage>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _chatMessages.value = messages
        } else {
            _chatMessages.postValue(messages)
        }
    }

    private fun formatGrammarResultForChat(
        result: GrammarResult,
        statusCorrectText: String,
        statusIncorrectText: String,
        correctedLabel: String,
        explanationLabel: String,
        questionLabel: String,
    ): String {
        if (result.correctedSentence.isBlank() && result.questionOfAI.isBlank()) {
            return result.explanation
        }
        return buildString {
            append(if (result.isCorrect) statusCorrectText else statusIncorrectText)
            if (result.correctedSentence.isNotBlank()) {
                append('\n')
                append(correctedLabel)
                append(": ")
                append(result.correctedSentence)
            }
            append('\n')
            append(explanationLabel)
            append(": ")
            append(result.explanation)
            if (result.questionOfAI.isNotBlank()) {
                append('\n')
                append(questionLabel)
                append(": ")
                append(result.questionOfAI)
            }
        }
    }

    private fun callGeminiGenerateGrammarCheck(
        apiKey: String,
        model: String,
        userPrompt: String,
    ): GrammarResult {
        val grammarPrompt =
            """
            You are a grammar checker.
            Analyze the user's sentence and return only valid JSON with these keys:
            - isCorrect: boolean
            - correctedSentence: string
            - explanation: string
            - questionOfAI: string

            If the sentence is already correct, keep correctedSentence the same as the original sentence.
            Use questionOfAI for a short follow-up question or leave it empty if none is needed.

            User sentence: $userPrompt
            """.trimIndent()

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
                                    JSONObject().put("text", grammarPrompt),
                                ),
                            ),
                        ),
                    )
                    put(
                        "generationConfig",
                        JSONObject().put("responseMimeType", "application/json"),
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

        val responseText =
            extractTextFromCandidates(JSONObject(responseBody).optJSONArray("candidates"))
                ?: throw IllegalStateException("No text in AI response")
        return parseGrammarResult(responseText)
    }

    private fun parseGrammarResult(responseText: String): GrammarResult {
        val jsonText = extractJsonObjectText(responseText)
        val json = JSONObject(jsonText)
        val correctedSentence = json.optString("correctedSentence").trim()
        val explanation = json.optString("explanation").trim()
        val questionOfAI = json.optString("questionOfAI").trim()

        if (correctedSentence.isBlank() || explanation.isBlank()) {
            throw IllegalStateException("AI response is missing required fields")
        }

        return GrammarResult(
            isCorrect = json.optBoolean("isCorrect"),
            correctedSentence = correctedSentence,
            explanation = explanation,
            questionOfAI = questionOfAI,
        )
    }

    private fun extractJsonObjectText(text: String): String {
        val startIndex = text.indexOf('{')
        val endIndex = text.lastIndexOf('}')
        if (startIndex < 0 || endIndex <= startIndex) {
            throw IllegalStateException("AI response is not valid JSON")
        }
        return text.substring(startIndex, endIndex + 1)
    }

    private fun readBody(stream: InputStream?): String {
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
