package com.example.talktoomuch

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.talktoomuch.databinding.ItemChatMessageBinding

class ChatMessageAdapter : ListAdapter<ChatMessage, ChatMessageAdapter.ChatMessageViewHolder>(DIFF_CALLBACK) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ChatMessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemChatMessageBinding.inflate(inflater, parent, false)
        return ChatMessageViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: ChatMessageViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    class ChatMessageViewHolder(
        private val binding: ItemChatMessageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.messageTextView.text = message.text
            val context = binding.root.context
            val params = binding.messageTextView.layoutParams as FrameLayout.LayoutParams
            if (message.fromUser) {
                params.gravity = Gravity.END
                binding.messageTextView.setBackgroundResource(R.drawable.bg_chat_user)
                binding.messageTextView.setTextColor(
                    ContextCompat.getColor(context, android.R.color.white),
                )
            } else {
                params.gravity = Gravity.START
                binding.messageTextView.setBackgroundResource(R.drawable.bg_chat_ai)
                binding.messageTextView.setTextColor(
                    ContextCompat.getColor(context, android.R.color.black),
                )
            }
            binding.messageTextView.layoutParams = params
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
