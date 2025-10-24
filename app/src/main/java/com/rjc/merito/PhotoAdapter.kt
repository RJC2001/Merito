package com.rjc.merito

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.rjc.merito.databinding.ItemPhotoBinding
import com.rjc.merito.model.Photo

class PhotoAdapter(
    private val items: List<Photo>,
    private val currentUserId: String?,
    private val onClick: (Photo) -> Unit,
    private val onDelete: (Photo) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    inner class VH(val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val p = items[pos]
                    onClick(p)
                }
            }
            binding.root.setOnLongClickListener { view ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                val photo = items[pos]
                if (photo.ownerUid != null && photo.ownerUid == currentUserId) {
                    androidx.appcompat.widget.PopupMenu(view.context, view).apply {
                        menu.add(0, 0, 0, view.context.getString(R.string.delete))
                        setOnMenuItemClickListener {
                            onDelete(photo)
                            true
                        }
                        show()
                    }
                } else {
                    androidx.appcompat.widget.PopupMenu(view.context, view).apply {
                        menu.add(0, 0, 0, view.context.getString(R.string.not_owner))
                        show()
                    }
                }
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.binding.tvTitle.text = p.title
        Glide.with(holder.itemView)
            .load(if (p.thumbUrl.isNotEmpty()) p.thumbUrl else p.fullUrl)
            .centerCrop()
            .placeholder(android.R.drawable.progress_indeterminate_horizontal)
            .error(android.R.drawable.stat_notify_error)
            .into(holder.binding.imgThumb)

        holder.binding.imgThumb.alpha = if (p.ownerUid != null && p.ownerUid == currentUserId) 1.0f else 1.0f
    }

    override fun getItemCount(): Int = items.size
}
