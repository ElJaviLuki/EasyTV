package tv.facil.abuelo

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.facil.abuelo.databinding.ActivityChannelsBinding

class CatalogActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_KIND = "kind"
    }

    private lateinit var binding: ActivityChannelsBinding
    private lateinit var source: PlaylistSource
    private lateinit var kind: ContentKind
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
        kind = ContentKind.fromExtra(intent.getStringExtra(EXTRA_KIND))

        binding.title.text = "${source.name} · ${kind.title}"
        binding.backButton.text = getString(R.string.back_section)
        binding.backButton.setOnClickListener { finish() }

        categoryAdapter = CategoryAdapter(emptyList(), selectedCategory) { category ->
            selectedCategory = category
            categoryAdapter.submit(categoryNames(), selectedCategory)
            renderList()
        }
        binding.categoryList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.categoryList.adapter = categoryAdapter

        catalogAdapter = CatalogAdapter(
            scope = lifecycleScope,
            items = emptyList(),
            epgSource = source,
            showLiveEpg = kind == ContentKind.LIVE
        ) { item ->
            when {
                kind == ContentKind.SERIES && item.seriesId != null -> {
                    startActivity(
                        Intent(this, EpisodesActivity::class.java)
                            .putExtra(EpisodesActivity.EXTRA_SOURCE_ID, source.id)
                            .putExtra(EpisodesActivity.EXTRA_SERIES_ID, item.seriesId)
                            .putExtra(EpisodesActivity.EXTRA_SERIES_NAME, item.name)
                    )
                }
                item.url.isNotBlank() -> {
                    if (kind == ContentKind.LIVE) {
                        ZapPlaylist.set(visibleItems())
                    } else {
                        ZapPlaylist.clear()
                    }
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URL, item.url)
                            .putExtra(PlayerActivity.EXTRA_NAME, item.name)
                            .putExtra(PlayerActivity.EXTRA_GROUP, item.group)
                            .putExtra(PlayerActivity.EXTRA_ZAP_ENABLED, kind == ContentKind.LIVE)
                    )
                }
            }
        }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = catalogAdapter

        showCacheThenRefresh()
    }

    private fun categoryNames(): List<String> =
        listOf("Todas") + allItems.map { it.group }.distinct().sorted()

    private fun applyItems(items: List<CatalogItem>, status: String) {
        allItems = items
        binding.loading.visibility = View.GONE
        binding.status.text = status
        selectedCategory = "Todas"
        categoryAdapter.submit(categoryNames(), selectedCategory)
        renderList()
    }

    private fun showCacheThenRefresh() {
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                PlaylistRepository.memoryCached(source.id, kind)
                    .ifEmpty { PlaylistRepository.diskCached(this@CatalogActivity, source.id, kind) }
            }
            if (cached.isNotEmpty()) {
                applyItems(cached, "${cached.size} ${kind.loadingLabel} (caché) · actualizando…")
            } else {
                binding.loading.visibility = View.VISIBLE
                binding.status.text = getString(R.string.loading_kind, kind.loadingLabel)
            }

            try {
                val fresh = PlaylistRepository.loadCatalog(
                    this@CatalogActivity, source, kind, force = cached.isNotEmpty()
                )
                applyItems(fresh, "${fresh.size} ${kind.loadingLabel} · ${source.hint}")
            } catch (e: Exception) {
                binding.loading.visibility = View.GONE
                if (cached.isEmpty()) {
                    binding.status.text = getString(R.string.load_error) + " (${e.message})"
                    binding.backButton.requestFocus()
                } else {
                    binding.status.text = "${cached.size} ${kind.loadingLabel} (caché) · sin red"
                }
            }
        }
    }

    private fun visibleItems(): List<CatalogItem> =
        if (selectedCategory == "Todas") allItems
        else allItems.filter { it.group == selectedCategory }

    private fun renderList() {
        val filtered = visibleItems()
        catalogAdapter.submit(filtered)
        if (filtered.isNotEmpty()) {
            binding.channelList.post {
                binding.channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }
}
