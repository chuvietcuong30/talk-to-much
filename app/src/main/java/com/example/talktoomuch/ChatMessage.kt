package com.example.talktoomuch

data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
)
