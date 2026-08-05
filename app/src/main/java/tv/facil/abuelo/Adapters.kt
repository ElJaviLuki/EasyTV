package tv.facil.abuelo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

class SectionAdapter(
    private val items: List<ContentKind>,
    private val onClick: (ContentKind) -> Unit
) : RecyclerView.Adapter<SectionAdapter.Holder>() {

    private val hints = mapOf(
        ContentKind.LIVE to "TV en directo",
        ContentKind.SERIES to "Capítulos por temporadas",
        ContentKind.MOVIES to "Cine a la carta"
    )

    inner class Holder(val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.serverName.text = item.title
        holder.binding.serverHint.text = hints[item].orEmpty()
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

class CatalogAdapter(
    private val scope: CoroutineScope,
    private var items: List<CatalogItem>,
    private val epgSource: PlaylistSource?,
    private val showLiveEpg: Boolean,
    private val onClick: (CatalogItem) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.Holder>() {

    inner class Holder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        var epgJob: Job? = null
    }

    fun submit(items: List<CatalogItem>) {
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
        holder.epgJob?.cancel()
        holder.binding.channelNumber.text = item.number.toString()
        holder.binding.channelName.text = item.name
        holder.binding.channelGroup.text = item.group
        holder.binding.channelGroup.visibility = if (showLiveEpg) View.GONE else View.VISIBLE
        holder.binding.channelEpg.visibility = if (showLiveEpg) View.VISIBLE else View.GONE
        holder.binding.channelEpg.text = if (showLiveEpg) "Cargando guía…" else item.group

        holder.binding.channelLogo.load(item.logo) {
            crossfade(true)
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }

        if (showLiveEpg && epgSource != null) {
            val streamId = item.streamId
            if (streamId == null) {
                holder.binding.channelEpg.text = item.group
            } else {
                val bindId = streamId
                holder.epgJob = scope.launch {
                    val now = EpgRepository.nowPlaying(epgSource, bindId)
                    if (holder.bindingAdapterPosition == position) {
                        holder.binding.channelEpg.text = now?.scheduleLine() ?: item.group
                    }
                }
            }
        }

        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.epgJob?.cancel()
        holder.epgJob = null
        super.onViewRecycled(holder)
    }
}
