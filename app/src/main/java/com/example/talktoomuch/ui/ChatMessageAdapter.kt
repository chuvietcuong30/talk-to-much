package com.example.talktoomuch.ui

import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.talktoomuch.R
import com.example.talktoomuch.databinding.ItemChatMessageBinding
import com.example.talktoomuch.repository.model.ChatMessage

/**
 * Adapter that renders any [ChatMessage] by toggling visibility of pre-built
 * sub-views inside [item_chat_message]. This keeps the layout flat (no nested
 * RecyclerView types) while supporting AI text, correction card, question card,
 * status badge, date pill, and user bubble in a single item view.
 *
 * The [onReplayTextClicked] callback is invoked when the user taps the "Đọc
 * chậm" button inside an AI correction card. The adapter passes the full text
 * to be replayed (corrected sentence + follow-up question, joined by ". ");
 * playback itself is handled by the Activity.
 */
class ChatMessageAdapter(
    private val onReplayTextClicked: (replayText: String) -> Unit = {},
) :
    ListAdapter<ChatMessage, ChatMessageAdapter.ChatMessageViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(
        parent: android.view.ViewGroup,
        viewType: Int,
    ): ChatMessageViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            android.view.LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ChatMessageViewHolder(binding, onReplayTextClicked)
    }

    override fun onBindViewHolder(
        holder: ChatMessageViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    class ChatMessageViewHolder(
        private val binding: ItemChatMessageBinding,
        private val onReplayTextClicked: (replayText: String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val ctx = binding.root.context

            // Reset visible states
            binding.datePill.visibility = View.GONE
            binding.aiBubbleColumn.visibility = View.GONE
            binding.userBubbleColumn.visibility = View.GONE

            if (message.isDateLabel) {
                binding.datePill.visibility = View.VISIBLE
                binding.datePill.text = message.text
                return
            }

            if (message.fromUser) {
                binding.userBubbleColumn.visibility = View.VISIBLE
                binding.userTextView.text = message.text
                binding.userTimeLabel.text = message.timeLabel
                binding.userTimeLabel.visibility =
                    if (message.timeLabel.isBlank()) View.GONE else View.VISIBLE
            } else {
                bindAi(message, ctx)
            }
        }

        private fun bindAi(message: ChatMessage, ctx: android.content.Context) {
            binding.aiBubbleColumn.visibility = View.VISIBLE

            // Header time
            binding.aiTimeLabel.text = message.timeLabel
            binding.aiTimeLabel.visibility =
                if (message.timeLabel.isBlank()) View.GONE else View.VISIBLE

            // Status badge (correct / incorrect)
            val status = message.statusCorrect
            if (status != null) {
                binding.aiStatusBadge.visibility = View.VISIBLE
                binding.aiStatusText.text = ctx.getString(
                    if (status) R.string.ai_status_correct
                    else R.string.ai_status_incorrect,
                )
                val tint = ContextCompat.getColor(
                    ctx,
                    if (status) R.color.brand_secondary else R.color.brand_primary,
                )
                binding.aiStatusText.setTextColor(tint)
                binding.aiStatusIcon.imageTintList =
                    android.content.res.ColorStateList.valueOf(tint)
            } else {
                binding.aiStatusBadge.visibility = View.GONE
            }

            // Main text (fallback to message.text)
            binding.aiTextView.text =
                message.explanation?.takeIf { it.isNotBlank() } ?: message.text

            // Correction card
            val corrected = message.correctedSentence?.trim().orEmpty()
            val question = message.questionOfAI?.trim().orEmpty()
            if (corrected.isNotEmpty()) {
                binding.correctionCard.visibility = View.VISIBLE
                binding.correctedSentenceText.text = "“$corrected”"
                // "Đọc chậm" button inside the correction card: play BOTH the
                // corrected sentence AND the follow-up question at slow speed
                // (0.5x), as requested.
                binding.correctedSentencePlayButton.setOnClickListener {
                    val toSpeak = buildString {
                        if (corrected.isNotEmpty()) append(corrected)
                        if (question.isNotEmpty()) {
                            if (isNotEmpty()) append(". ")
                            append(question)
                        }
                    }
                    onReplayTextClicked(toSpeak)
                }
            } else {
                binding.correctionCard.visibility = View.GONE
                binding.correctedSentencePlayButton.setOnClickListener(null)
            }

            // Question card
            if (question.isNotEmpty()) {
                binding.questionCard.visibility = View.VISIBLE
                binding.questionText.text = question
            } else {
                binding.questionCard.visibility = View.GONE
            }

            // Meta replay row
            binding.metaRow.visibility =
                if (message.showMetaReplay) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<ChatMessage>() {
                override fun areItemsTheSame(
                    oldItem: ChatMessage,
                    newItem: ChatMessage,
                ): Boolean = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: ChatMessage,
                    newItem: ChatMessage,
                ): Boolean = oldItem == newItem
            }
    }
}
