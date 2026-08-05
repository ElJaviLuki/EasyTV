package tv.facil.abuelo

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import tv.facil.abuelo.databinding.ItemCategoryBinding
import tv.facil.abuelo.databinding.ItemChannelBinding
import tv.facil.abuelo.databinding.ItemServerBinding

class ServerAdapter(
    private val items: List<PlaylistSource>,
    private val onClick: (PlaylistSource) -> Unit
) : RecyclerView.Adapter<ServerAdapter.Holder>() {

    inner class Holder(val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.serverName.text = item.name
        holder.binding.serverHint.text = item.hint
        holder.binding.root.setOnClickListener { onClick(item) }
        if (position == 0) holder.binding.root.requestFocus()
    }
}

class CategoryAdapter(
    private var items: List<String>,
    private var selected: String,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

    inner class Holder(val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(items: List<String>, selected: String) {
        this.items = items
        this.selected = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val name = items[position]
        val isSelected = name == selected
        holder.binding.chipText.text = name
        holder.binding.chipText.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (isSelected) R.color.bg else R.color.text_primary
            )
        )
        holder.binding.chipText.isSelected = isSelected
        holder.binding.chipText.setOnClickListener { onClick(name) }
    }
}

class ChannelAdapter(
    private var items: List<Channel>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    inner class Holder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(items: List<Channel>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.channelNumber.text = item.number.toString()
        holder.binding.channelName.text = item.name
        holder.binding.channelGroup.text = item.group
        holder.binding.root.setOnClickListener { onClick(item) }
    }
}
