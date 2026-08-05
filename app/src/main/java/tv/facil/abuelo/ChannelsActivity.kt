package tv.facil.abuelo

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import tv.facil.abuelo.databinding.ActivityChannelsBinding

class ChannelsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
    }

    private lateinit var binding: ActivityChannelsBinding
    private lateinit var source: PlaylistSource
    private var allChannels: List<Channel> = emptyList()
    private var selectedCategory: String = "Todas"
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        source = SeededPlaylists.byId(intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()) ?: run {
            finish()
            return
        }

        binding.title.text = source.name
        binding.backButton.text = getString(R.string.back_servers)
        binding.backButton.setOnClickListener { finish() }

        categoryAdapter = CategoryAdapter(emptyList(), selectedCategory) { category ->
            selectedCategory = category
            categoryAdapter.submit(categoryNames(), selectedCategory)
            renderChannels()
        }
        binding.categoryList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.categoryList.adapter = categoryAdapter

        channelAdapter = ChannelAdapter(emptyList()) { channel ->
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, channel.url)
                    .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
                    .putExtra(PlayerActivity.EXTRA_GROUP, channel.group)
            )
        }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter

        load()
    }

    private fun categoryNames(): List<String> =
        listOf("Todas") + allChannels.map { it.group }.distinct().sorted()

    private fun load() {
        binding.loading.visibility = View.VISIBLE
        binding.status.text = getString(R.string.loading)
        lifecycleScope.launch {
            try {
                allChannels = PlaylistRepository.loadChannels(source)
                binding.loading.visibility = View.GONE
                binding.status.text = "${allChannels.size} canales · ${source.hint}"
                selectedCategory = "Todas"
                categoryAdapter.submit(categoryNames(), selectedCategory)
                renderChannels()
            } catch (e: Exception) {
                binding.loading.visibility = View.GONE
                binding.status.text = getString(R.string.load_error) + " (${e.message})"
                binding.backButton.requestFocus()
            }
        }
    }

    private fun renderChannels() {
        val filtered = if (selectedCategory == "Todas") allChannels
        else allChannels.filter { it.group == selectedCategory }
        channelAdapter.submit(filtered)
        if (filtered.isNotEmpty()) {
            binding.channelList.post {
                binding.channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }
}
