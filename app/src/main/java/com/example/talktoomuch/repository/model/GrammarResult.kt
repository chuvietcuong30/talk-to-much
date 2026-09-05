package com.example.talktoomuch.repository.model

data class GrammarResult(
    val isCorrect: Boolean,
    val correctedSentence: String,
    val explanation: String,
    val questionOfAI: String,
)
