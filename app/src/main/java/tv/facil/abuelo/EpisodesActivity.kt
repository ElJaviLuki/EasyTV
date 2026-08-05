package tv.facil.abuelo

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import tv.facil.abuelo.databinding.ActivityChannelsBinding

class EpisodesActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_NAME = "series_name"
    }

    private lateinit var binding: ActivityChannelsBinding
    private lateinit var source: PlaylistSource
    private var allItems: List<CatalogItem> = emptyList()
    private var selectedCategory: String = "Todas"
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var catalogAdapter: CatalogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        source = PlaylistStore.byId(intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()) ?: run {
            finish()
            return
        }
        val seriesId = intent.getIntExtra(EXTRA_SERIES_ID, -1)
        val seriesName = intent.getStringExtra(EXTRA_SERIES_NAME).orEmpty()
        if (seriesId < 0) {
            finish()
            return
        }

        binding.title.text = seriesName
        binding.backButton.text = getString(R.string.back_series)
        binding.backButton.setOnClickListener { finish() }
        binding.categoryList.visibility = View.VISIBLE

        categoryAdapter = CategoryAdapter(emptyList(), selectedCategory) { category ->
            selectedCategory = category
            categoryAdapter.submit(categoryNames(), selectedCategory)
            renderList()
        }
        binding.categoryList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.categoryList.adapter = categoryAdapter

        catalogAdapter = CatalogAdapter(emptyList()) { item ->
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, item.url)
                    .putExtra(PlayerActivity.EXTRA_NAME, item.name)
                    .putExtra(PlayerActivity.EXTRA_GROUP, seriesName)
            )
        }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = catalogAdapter

        binding.loading.visibility = View.VISIBLE
        binding.status.text = getString(R.string.loading_episodes)

        lifecycleScope.launch {
            try {
                allItems = PlaylistRepository.loadEpisodes(source, seriesId)
                binding.loading.visibility = View.GONE
                binding.status.text = "${allItems.size} episodios"
                selectedCategory = "Todas"
                categoryAdapter.submit(categoryNames(), selectedCategory)
                renderList()
            } catch (e: Exception) {
                binding.loading.visibility = View.GONE
                binding.status.text = getString(R.string.load_error) + " (${e.message})"
                binding.backButton.requestFocus()
            }
        }
    }

    private fun categoryNames(): List<String> =
        listOf("Todas") + allItems.map { it.group }.distinct().sortedBy {
            it.substringAfter("Temporada ").toIntOrNull() ?: 0
        }

    private fun renderList() {
        val filtered = if (selectedCategory == "Todas") allItems
        else allItems.filter { it.group == selectedCategory }
        catalogAdapter.submit(filtered)
        if (filtered.isNotEmpty()) {
            binding.channelList.post {
                binding.channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }
}
