package com.example.talktoomuch.repository.model

data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
)
