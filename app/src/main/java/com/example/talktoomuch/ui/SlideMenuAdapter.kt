package com.example.talktoomuch.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.talktoomuch.R
import com.example.talktoomuch.databinding.ItemMenuDrawerBinding

/**
 * Adapter for the slide-in drawer.
 * Renders a fixed list of menu items with an icon, label, optional chevron,
 * and a subtle highlight for the currently-selected entry.
 */
class SlideMenuAdapter(
    private val onItemClick: (SlideMenuItem) -> Unit,
) : ListAdapter<SlideMenuItem, SlideMenuAdapter.SlideMenuViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): SlideMenuViewHolder {
        val binding = ItemMenuDrawerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return SlideMenuViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(
        holder: SlideMenuViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    class SlideMenuViewHolder(
        private val binding: ItemMenuDrawerBinding,
        private val onItemClick: (SlideMenuItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SlideMenuItem) {
            val context = binding.root.context

            binding.menuItemIcon.setImageResource(item.iconRes)
            binding.menuItemLabel.text = context.getString(item.labelRes)

            val tintColor = if (item.isActive) {
                ContextCompat.getColor(context, R.color.menu_active_tint)
            } else {
                ContextCompat.getColor(context, R.color.menu_inactive_tint)
            }
            binding.menuItemIcon.setColorFilter(tintColor)
            binding.menuItemLabel.setTextColor(tintColor)
            binding.menuItemChevron.visibility = if (item.isActive) {
                android.view.View.INVISIBLE
            } else {
                android.view.View.VISIBLE
            }

            // Active row background
            binding.root.setBackgroundResource(
                if (item.isActive) R.drawable.bg_menu_item_active
                else android.R.color.transparent,
            )

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<SlideMenuItem>() {
                override fun areItemsTheSame(
                    oldItem: SlideMenuItem,
                    newItem: SlideMenuItem,
                ): Boolean = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: SlideMenuItem,
                    newItem: SlideMenuItem,
                ): Boolean = oldItem == newItem
            }
    }
}

/**
 * Immutable model representing one row in the slide drawer.
 */
data class SlideMenuItem(
    val id: String,
    val iconRes: Int,
    val labelRes: Int,
    val isActive: Boolean = false,
)