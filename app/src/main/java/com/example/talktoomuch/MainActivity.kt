package com.example.talktoomuch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.talktoomuch.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false
    private val restartHandler = Handler(Looper.getMainLooper())

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

        setupInteractions()
        observeViewModel()

        viewModel.initialize(
            speechAvailable = speechAvailable,
            hintText = getString(R.string.press_and_hold_hint),
            speechNotSupportedText = getString(R.string.speech_not_supported),
        )
        updateRecordButtonText()
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
            viewModel.onListeningStarted(getString(R.string.listening))
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
    }

    private fun startRecordingSession() {
        isRecording = true
        updateRecordButtonText()
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
        viewModel.onRecordReleased(getString(R.string.press_and_hold_hint))
        viewModel.requestGrammarResult(
            apiKey = BuildConfig.GEMINI_API_KEY,
            model = "gemini-3.6-flash",
            thinkingText = getString(R.string.ai_thinking),
            noTranscriptText = getString(R.string.ai_no_transcript),
            missingKeyText = getString(R.string.ai_missing_key),
            errorPrefixText = getString(R.string.ai_error_prefix),
        )
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
        viewModel.displayText.observe(this) { text ->
            binding.resultTextView.text = text
        }
        viewModel.grammarResult.observe(this) { result ->
            if (result != null) {
                binding.grammarResultTextView.text = formatGrammarResult(result)
            }
        }
        viewModel.isRecordButtonEnabled.observe(this) { isEnabled ->
            binding.recordButton.isEnabled = isEnabled
        }
    }

    private fun formatGrammarResult(result: GrammarResult): String =
        if (result.correctedSentence.isBlank() && result.questionOfAI.isBlank()) {
            result.explanation
        } else {
            buildString {
                append(
                    if (result.isCorrect) {
                        getString(R.string.grammar_status_correct)
                    } else {
                        getString(R.string.grammar_status_incorrect)
                    },
                )
                append('\n')
                if (result.correctedSentence.isNotBlank()) {
                    append(getString(R.string.grammar_corrected_sentence, result.correctedSentence))
                    append('\n')
                }
                append(getString(R.string.grammar_explanation, result.explanation))
                if (result.questionOfAI.isNotBlank()) {
                    append('\n')
                    append(getString(R.string.grammar_question, result.questionOfAI))
                }
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
                            viewModel.onRecognitionError(getString(R.string.press_and_hold_hint))
                        }

                        override fun onResults(results: Bundle?) {
                            val spokenText =
                                results
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                            viewModel.onFinalResult(
                                text = spokenText,
                                hintText = getString(R.string.press_and_hold_hint),
                            )
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

    override fun onDestroy() {
        restartHandler.removeCallbacks(restartListeningRunnable)
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}
