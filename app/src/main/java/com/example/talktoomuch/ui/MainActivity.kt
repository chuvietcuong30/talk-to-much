package com.example.talktoomuch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.talktoomuch.BuildConfig
import com.example.talktoomuch.R
import com.example.talktoomuch.databinding.ActivityMainBinding
import com.example.talktoomuch.databinding.LayoutSlideMenuBinding
import com.example.talktoomuch.repository.model.GrammarResult
import com.example.talktoomuch.viewmodel.MainViewModel
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var pendingSpeechText: String? = null
    private var lastGrammarResult: GrammarResult? = null
    private var isRecording = false
    private val restartHandler = Handler(Looper.getMainLooper())
    private lateinit var chatMessageAdapter: ChatMessageAdapter
    private lateinit var slideMenuAdapter: SlideMenuAdapter

    private val restartListeningRunnable =
        Runnable {
            if (isRecording && hasAudioPermission()) {
                startListening(showListeningText = false)
            }
        }

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                if (isRecording) {
                    startListening(showListeningText = true)
                }
            } else {
                isRecording = false
                updateRecordButtonText()
                updateSendButtonState()
                viewModel.onPermissionDenied(getString(R.string.permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val speechAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        if (speechAvailable) {
            setupSpeechRecognizer()
        }
        setupTextToSpeech()
        setupChatList()
        setupSlideMenu()
        setupInteractions()
        observeViewModel()

        viewModel.initialize(
            speechAvailable = speechAvailable,
            speechNotSupportedText = getString(R.string.speech_not_supported),
        )
        updateRecordButtonText()
        updateSendButtonState()
    }

    private fun startListening(showListeningText: Boolean) {
        val recognizerIntent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            }
        restartHandler.removeCallbacks(restartListeningRunnable)
        speechRecognizer?.startListening(recognizerIntent)
        if (showListeningText) {
            viewModel.onListeningStarted()
        }
    }

    private fun stopListening() {
        restartHandler.removeCallbacks(restartListeningRunnable)
        speechRecognizer?.stopListening()
    }

    private fun scheduleRestartListening() {
        restartHandler.removeCallbacks(restartListeningRunnable)
        restartHandler.postDelayed(restartListeningRunnable, 150L)
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun setupInteractions() {
        // Open slide menu via back arrow
        binding.navArrowButton.setOnClickListener {
            toggleSlideMenu(show = !isSlideMenuVisible())
        }

        binding.recordButton.setOnClickListener {
            if (!viewModel.canStartRecording()) {
                return@setOnClickListener
            }
            if (isRecording) {
                stopRecordingSession()
            } else {
                startRecordingSession()
            }
        }
        binding.sendButton.setOnClickListener {
            sendTypedMessage()
        }

        // Scrim closes the menu
        binding.menuScrim.setOnClickListener {
            toggleSlideMenu(show = false)
        }
    }

    // ============ SLIDE MENU ============

    private fun setupSlideMenu() {
        val menuBinding = LayoutSlideMenuBinding.inflate(layoutInflater, binding.slideMenuContainer, false)
        binding.slideMenuContainer.addView(menuBinding.root)

        slideMenuAdapter =
            SlideMenuAdapter { item ->
                onMenuItemSelected(item.id)
            }

        menuBinding.menuRecyclerView.layoutManager = LinearLayoutManager(this)
        menuBinding.menuRecyclerView.adapter = slideMenuAdapter
        slideMenuAdapter.submitList(buildMenuItems())

        // Close button at top of the panel
        menuBinding.menuCloseRow.setOnClickListener {
            toggleSlideMenu(show = false)
        }
    }

    private fun buildMenuItems(): List<SlideMenuItem> =
        listOf(
            SlideMenuItem(
                id = "home",
                iconRes = R.drawable.ic_home,
                labelRes = R.string.menu_home,
                isActive = true,
            ),
            SlideMenuItem(
                id = "history",
                iconRes = R.drawable.ic_history,
                labelRes = R.string.menu_history,
            ),
            SlideMenuItem(
                id = "profile",
                iconRes = R.drawable.ic_profile,
                labelRes = R.string.menu_profile,
            ),
            SlideMenuItem(
                id = "more",
                iconRes = R.drawable.ic_more,
                labelRes = R.string.menu_more,
            ),
        )

    private fun onMenuItemSelected(itemId: String) {
        when (itemId) {
            "history", "profile", "more" ->
                Toast.makeText(this, R.string.menu_coming_soon, Toast.LENGTH_SHORT).show()
            "home" -> {
                // Already on the home screen — just close the drawer
            }
        }
        toggleSlideMenu(show = false)
    }

    private fun isSlideMenuVisible(): Boolean =
        binding.slideMenuContainer.visibility == View.VISIBLE

    private fun toggleSlideMenu(show: Boolean) {
        if (show) {
            binding.menuScrim.visibility = View.VISIBLE
            binding.slideMenuContainer.visibility = View.VISIBLE
        } else {
            binding.menuScrim.visibility = View.GONE
            binding.slideMenuContainer.visibility = View.GONE
        }
    }

    private fun startRecordingSession() {
        isRecording = true
        updateRecordButtonText()
        updateSendButtonState()
        if (hasAudioPermission()) {
            startListening(showListeningText = true)
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun stopRecordingSession() {
        isRecording = false
        stopListening()
        updateRecordButtonText()
        updateSendButtonState()
        viewModel.onRecordReleased()
    }

    private fun updateRecordButtonText() {
        binding.recordButton.text =
            if (isRecording) {
                getString(R.string.stop_recording)
            } else {
                getString(R.string.hold_to_speak)
            }
    }

    private fun observeViewModel() {
        viewModel.chatMessages.observe(this) { messages ->
            chatMessageAdapter.submitList(messages) {
                scrollChatToBottom()
            }
        }
        viewModel.draftInputText.observe(this) { text ->
            if (binding.messageInputEditText.text.toString() != text) {
                binding.messageInputEditText.setText(text)
                binding.messageInputEditText.setSelection(text.length)
            }
        }
        viewModel.grammarResult.observe(this) { result ->
            if (result != null) {
                lastGrammarResult = result
                val shouldSpeak = result.correctedSentence.isNotBlank() || result.questionOfAI.isNotBlank()
                binding.slowReplayButton.isEnabled = shouldSpeak
                if (shouldSpeak) {
                    speakGrammarResult(result, speechRate = 1.0f)
                }
            }
        }
        viewModel.isRecordButtonEnabled.observe(this) { isEnabled ->
            binding.recordButton.isEnabled = isEnabled
        }
        binding.slowReplayButton.setOnClickListener {
            lastGrammarResult?.let { result ->
                speakGrammarResult(result, speechRate = 0.5f)
            }
        }
        binding.slowReplayButton.isEnabled = false
    }

    private fun setupTextToSpeech() {
        textToSpeech =
            TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.getDefault()
                    pendingSpeechText?.let { text ->
                        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "grammar_result")
                        pendingSpeechText = null
                    }
                }
            }
    }

    private fun speakGrammarResult(
        result: GrammarResult,
        speechRate: Float,
    ) {
        val speechText = buildSpeechText(result)
        if (speechText.isBlank()) {
            return
        }
        val tts = textToSpeech
        if (tts == null) {
            pendingSpeechText = speechText
            return
        }
        if (tts.isSpeaking) {
            tts.stop()
        }
        tts.setSpeechRate(speechRate)
        tts.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "grammar_result")
    }

    private fun buildSpeechText(result: GrammarResult): String =
        buildString {
            if (result.correctedSentence.isNotBlank()) {
                append(result.correctedSentence)
            }
            if (result.questionOfAI.isNotBlank()) {
                if (isNotBlank()) {
                    append(". ")
                }
                append(result.questionOfAI)
            }
        }

    private fun setupSpeechRecognizer() {
        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(
                    object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(
                            eventType: Int,
                            params: Bundle?,
                        ) = Unit

                        override fun onError(error: Int) {
                            if (isRecording && shouldRetryAfterError(error)) {
                                scheduleRestartListening()
                                return
                            }
                            viewModel.onRecognitionError()
                        }

                        override fun onResults(results: Bundle?) {
                            val spokenText =
                                results
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                            viewModel.onFinalResult(text = spokenText)
                            if (isRecording) {
                                scheduleRestartListening()
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val spokenText =
                                partialResults
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                            if (!spokenText.isNullOrBlank()) {
                                viewModel.onPartialResult(spokenText)
                            }
                        }
                    },
                )
            }
    }

    private fun shouldRetryAfterError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

    private fun setupChatList() {
        chatMessageAdapter = ChatMessageAdapter()
        binding.chatRecyclerView.layoutManager =
            LinearLayoutManager(this).apply {
                stackFromEnd = true
            }
        binding.chatRecyclerView.adapter = chatMessageAdapter
    }

    private fun scrollChatToBottom() {
        val lastPosition = chatMessageAdapter.itemCount - 1
        if (lastPosition < 0) {
            return
        }
        binding.chatRecyclerView.post {
            binding.chatRecyclerView.scrollToPosition(lastPosition)
            binding.chatRecyclerView.doOnNextLayout {
                binding.chatRecyclerView.scrollToPosition(lastPosition)
            }
        }
    }

    private fun updateSendButtonState() {
        val isEnabled = !isRecording
        binding.sendButton.isEnabled = isEnabled
        binding.sendButton.alpha = if (isEnabled) 1.0f else 0.45f
    }

    private fun sendTypedMessage() {
        viewModel.sendTypedPrompt(
            rawText = binding.messageInputEditText.text.toString(),
            apiKey = BuildConfig.GEMINI_API_KEY,
            model = "gemini-3.6-flash",
            thinkingText = getString(R.string.ai_thinking),
            noTranscriptText = getString(R.string.ai_no_transcript),
            missingKeyText = getString(R.string.ai_missing_key),
            errorPrefixText = getString(R.string.ai_error_prefix),
            statusCorrectText = getString(R.string.grammar_status_correct),
            statusIncorrectText = getString(R.string.grammar_status_incorrect),
            correctedLabel = getString(R.string.grammar_corrected_label),
            explanationLabel = getString(R.string.grammar_explanation_label),
            questionLabel = getString(R.string.grammar_question_label),
        )
    }

    override fun onDestroy() {
        restartHandler.removeCallbacks(restartListeningRunnable)
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}