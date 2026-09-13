package com.example.talktoomuch.repository.model

/**
 * One chat message rendered in the main list.
 *
 * The model is intentionally rich so the UI layer can switch styles via
 * view visibility (no RecyclerView view-types needed):
 *
 *  • [fromUser]          — true → user bubble (right), false → AI bubble (left)
 *  • [isDateLabel]       — true → render as a centered "Hôm nay" pill instead
 *  • [statusCorrect]     — non-null indicates the AI is showing a grammar status badge
 *  • [correctedSentence] — populated to render the light-blue correction card
 *  • [explanation]       — main AI message text (or fallback)
 *  • [questionOfAI]      — populated to render the light-green question card
 *  • [showMetaReplay]    — render the small "Đã phát lại · waveform" meta row
 *  • [timeLabel]         — small timestamp shown next to user bubble / AI header
 */
data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val isDateLabel: Boolean = false,
    val statusCorrect: Boolean? = null,
    val correctedSentence: String? = null,
    val explanation: String? = null,
    val questionOfAI: String? = null,
    val showMetaReplay: Boolean = false,
    val timeLabel: String = "",
)
