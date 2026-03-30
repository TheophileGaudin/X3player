package com.x3player.glasses.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.x3player.glasses.R
import com.x3player.glasses.data.VideoItem
import com.x3player.glasses.databinding.RowVideoItemBinding
import com.x3player.glasses.util.formatDate
import com.x3player.glasses.util.formatDuration

class VideoListAdapter(
    private val onVideoClicked: (VideoItem, Int) -> Unit,
) : ListAdapter<VideoItem, VideoListAdapter.VideoViewHolder>(DiffCallback) {

    private var selectedIndex: Int = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = RowVideoItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    fun setSelectedIndex(index: Int) {
        if (selectedIndex == index) return
        val previous = selectedIndex
        selectedIndex = index
        if (previous >= 0) notifyItemChanged(previous)
        if (selectedIndex >= 0) notifyItemChanged(selectedIndex)
    }

    inner class VideoViewHolder(
        private val binding: RowVideoItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VideoItem, position: Int) {
            val selected = position == selectedIndex
            binding.videoTitle.text = item.displayName
            binding.videoMeta.text = "${item.bucketName}  -  ${formatDuration(item.durationMs)}  -  ${formatDate(item.dateModified)}"
            binding.root.setOnClickListener { onVideoClicked(item, position) }
            binding.root.isActivated = selected
            binding.root.isSelected = selected
            binding.videoTitle.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) R.color.accent_cyan else R.color.text_primary,
                )
            )
            binding.videoMeta.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) R.color.text_primary else R.color.text_secondary,
                )
            )
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean = oldItem == newItem
    }
}
