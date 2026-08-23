package com.example.talktoomuch

data class GrammarResult(
    val isCorrect: Boolean,
    val correctedSentence: String,
    val explanation: String,
    val questionOfAI: String,
)
