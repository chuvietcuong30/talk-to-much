package com.example.talktoomuch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
                updateMicState()
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
            // Only apply horizontal + bottom padding; status bar inset is applied to topBar
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        // Make the top bar fill the status bar area so the status bar matches the white top bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, v.paddingBottom)
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
            todayLabel = getString(R.string.date_today),
            greetingText = getString(R.string.ai_greeting),
        )
        updateMicState()
    }

    // ===================== Speech =====================

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

    // ===================== Setup =====================

    private fun setupInteractions() {
        // Open slide menu via hamburger
        binding.navArrowButton.setOnClickListener {
            toggleSlideMenu(show = !isSlideMenuVisible())
        }

        // Settings button → toast for now
        binding.settingsButton.setOnClickListener {
            Toast.makeText(this, R.string.cd_settings, Toast.LENGTH_SHORT).show()
        }

        // Big green mic button
        binding.micButton.setOnClickListener {
            if (!viewModel.canStartRecording()) {
                Toast.makeText(this, R.string.speech_not_supported, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isRecording) {
                stopRecordingSession()
            } else {
                startRecordingSession()
            }
        }

        // Hidden recordButton (kept for compatibility)
        binding.recordButton.setOnClickListener {
            if (!viewModel.canStartRecording()) return@setOnClickListener
            if (isRecording) {
                stopRecordingSession()
            } else {
                startRecordingSession()
            }
        }

        // Blue send button
        binding.sendButton.setOnClickListener {
            sendTypedMessage()
        }

        // Input text watcher → toggle mic ↔ send
        binding.messageInputEditText.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    updateMicSendVisibility(s?.toString().orEmpty())
                }
            },
        )

        // Scrim closes the menu
        binding.menuScrim.setOnClickListener {
            toggleSlideMenu(show = false)
        }

        // Suggestion chips → insert topic as a starter prompt into input
        val chipMap =
            mapOf(
                binding.chipTravel to "Travel",
                binding.chipWork to "Work",
                binding.chipFood to "Food",
                binding.chipHobby to "Hobby",
            )
        chipMap.forEach { (chip, topic) ->
            chip.setOnClickListener {
                val template =
                    when (topic) {
                        "Travel" -> "Tell me about your favorite travel experience."
                        "Work" -> "Describe a typical day at your workplace."
                        "Food" -> "What's the best meal you've had recently?"
                        "Hobby" -> "What hobby do you enjoy the most and why?"
                        else -> "Let's talk about $topic."
                    }
                binding.messageInputEditText.setText(template)
                binding.messageInputEditText.setSelection(template.length)
                binding.messageInputEditText.requestFocus()
            }
        }

        updateMicSendVisibility(binding.messageInputEditText.text.toString())
    }

    // ===================== Slide Menu =====================

    private fun setupSlideMenu() {
        val menuBinding =
            LayoutSlideMenuBinding.inflate(
                layoutInflater,
                binding.slideMenuContainer,
                false,
            )
        binding.slideMenuContainer.addView(menuBinding.root)

        slideMenuAdapter =
            SlideMenuAdapter { item ->
                onMenuItemSelected(item.id)
            }

        menuBinding.menuRecyclerView.layoutManager = LinearLayoutManager(this)
        menuBinding.menuRecyclerView.adapter = slideMenuAdapter
        slideMenuAdapter.submitList(buildMenuItems())

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
            "history", "profile", "more" -> {
                Toast.makeText(this, R.string.menu_coming_soon, Toast.LENGTH_SHORT).show()
            }

            "home" -> {
                // Already on the home screen — just close the drawer
            }
        }
        toggleSlideMenu(show = false)
    }

    private fun isSlideMenuVisible(): Boolean = binding.slideMenuContainer.visibility == View.VISIBLE

    private fun toggleSlideMenu(show: Boolean) {
        if (show) {
            binding.menuScrim.visibility = View.VISIBLE
            binding.slideMenuContainer.visibility = View.VISIBLE
        } else {
            binding.menuScrim.visibility = View.GONE
            binding.slideMenuContainer.visibility = View.GONE
        }
    }

    // ===================== Recording =====================

    private fun startRecordingSession() {
        isRecording = true
        updateMicState()
        if (hasAudioPermission()) {
            startListening(showListeningText = true)
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun stopRecordingSession() {
        isRecording = false
        stopListening()
        updateMicState()
        viewModel.onRecordReleased()
        // Do NOT auto-send after recording. The user should be able to review
        // and edit the dictated text before pressing send. The send button will
        // light up as soon as recording ends (handled by updateMicState).
    }

    private fun updateMicState() {
        // Show stop icon when recording (visual cue on mic button)
        if (isRecording) {
            binding.micButton.setImageResource(R.drawable.ic_stop_small)
            binding.micButton.contentDescription = getString(R.string.cd_stop_mic)
        } else {
            binding.micButton.setImageResource(R.drawable.ic_mic)
            binding.micButton.contentDescription = getString(R.string.cd_mic)
        }
        updateMicSendVisibility(binding.messageInputEditText.text.toString())
    }

    private fun updateMicSendVisibility(currentText: String) {
        // UX:
        //  - Mic is always visible.
        //  - Send button becomes visible as soon as the user starts typing.
        //  - While recording, send is visible but disabled (dimmed), so the user
        //    can still see it but cannot send until recording finishes. This lets
        //    the user edit what they just dictated before sending.
        val hasText = currentText.trim().isNotEmpty()
        binding.micButton.visibility = View.VISIBLE
        binding.sendButton.visibility = if (hasText) View.VISIBLE else View.GONE
        binding.sendButton.isEnabled = hasText && !isRecording
        binding.sendButton.alpha = if (binding.sendButton.isEnabled) 1.0f else 0.4f
    }

    // ===================== VM observer =====================

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
                val shouldSpeak = result.correctedSentence.isNotBlank()
                binding.slowReplayButton.isEnabled = shouldSpeak
                if (shouldSpeak) {
                    speakGrammarResult(result, speechRate = 1.0f)
                }
            }
        }
        viewModel.isRecordButtonEnabled.observe(this) { isEnabled ->
            binding.recordButton.isEnabled = isEnabled
            binding.micButton.isEnabled = isEnabled
            binding.micButton.alpha = if (isEnabled) 1.0f else 0.45f
        }
        binding.slowReplayButton.setOnClickListener {
            lastGrammarResult?.let { result ->
                speakGrammarResult(result, speechRate = 0.5f)
            }
        }
        binding.slowReplayButton.isEnabled = false
    }

    // ===================== TTS =====================

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
        val corrected = result.correctedSentence.trim()
        if (corrected.isBlank()) {
            return
        }
        // Auto-play when AI first responds: read the corrected sentence AND the
        // follow-up question (joined by ". ") so the user hears the full reply,
        // matching what the "Đọc chậm" button plays.
        val question = result.questionOfAI.trim()
        val fullReply =
            if (question.isNotEmpty()) {
                "$corrected. $question"
            } else {
                corrected
            }
        speakCorrectedSentence(fullReply, speechRate)
    }

    private fun speakCorrectedSentence(
        correctedSentence: String,
        speechRate: Float,
    ) {
        speakText(correctedSentence, speechRate)
    }

    /**
     * Replay text composed of the corrected sentence followed by the AI's
     * follow-up question (already joined by the adapter with ". "). Used by
     * the "Đọc chậm" button inside the correction card.
     */
    private fun speakReplayText(
        replayText: String,
        speechRate: Float,
    ) {
        speakText(replayText, speechRate)
    }

    private fun speakText(
        text: String,
        speechRate: Float,
    ) {
        val clean = text.trim()
        if (clean.isBlank()) {
            return
        }
        val tts = textToSpeech
        if (tts == null) {
            pendingSpeechText = clean
            return
        }
        if (tts.isSpeaking) {
            tts.stop()
        }
        tts.setSpeechRate(speechRate)
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "grammar_result")
    }

    private fun buildSpeechText(result: GrammarResult): String {
        // Speak both the corrected sentence and the follow-up question (auto-play
        // path uses the same join logic as the "Đọc chậm" button so the user
        // hears the full reply at normal speed).
        val corrected = result.correctedSentence.trim()
        val question = result.questionOfAI.trim()
        return if (corrected.isBlank()) {
            ""
        } else if (question.isEmpty()) {
            corrected
        } else {
            "$corrected. $question"
        }
    }

    // ===================== Speech recognizer =====================

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
                            isRecording = false
                            updateMicState()
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

    // ===================== List & send =====================

    private fun setupChatList() {
        chatMessageAdapter =
            ChatMessageAdapter(
                onReplayTextClicked = { replayText ->
                    // "Đọc chậm" inside correction card — speak BOTH the corrected
                    // sentence and the follow-up question at slow speed (0.5x).
                    speakReplayText(replayText, speechRate = 0.5f)
                },
            )
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
        }
    }

    private fun sendTypedMessage() {
        val raw = binding.messageInputEditText.text.toString()
        if (raw.trim().isEmpty()) return
        viewModel.sendTypedPrompt(
            rawText = raw,
            apiKey = BuildConfig.GEMINI_API_KEY,
            model = "gemini-3.5-flash",
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
        binding.messageInputEditText.setText("")
    }

    override fun onDestroy() {
        restartHandler.removeCallbacks(restartListeningRunnable)
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    companion object {
        // Suppress unused import warning when running Lint
        @Suppress("unused")
        fun newIntent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)
    }
}
