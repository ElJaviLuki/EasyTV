package tv.facil.abuelo

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    lateinit var currentKind: ContentKind
        private set
    private var fromTv: Boolean = false
    private var allItems: List<CatalogItem> = emptyList()
    private var selectedCategory: String = "Todas"
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var catalogAdapter: CatalogAdapter
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!bindFromIntent(intent)) {
            finish()
            return
        }

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
            showLiveEpg = currentKind == ContentKind.LIVE
        ) { item -> onItemClick(item) }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = catalogAdapter

        showCacheThenRefresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!bindFromIntent(intent)) {
            finish()
            return
        }
        catalogAdapter = CatalogAdapter(
            scope = lifecycleScope,
            items = emptyList(),
            epgSource = source,
            showLiveEpg = currentKind == ContentKind.LIVE
        ) { item -> onItemClick(item) }
        binding.channelList.adapter = catalogAdapter
        showCacheThenRefresh()
    }

    private fun bindFromIntent(intent: Intent): Boolean {
        source = PlaylistStore.byId(intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()) ?: return false
        currentKind = ContentKind.fromExtra(intent.getStringExtra(EXTRA_KIND))
        fromTv = intent.getBooleanExtra(ModeNav.EXTRA_FROM_TV, currentKind == ContentKind.LIVE)

        AppSettings.lastSourceId = source.id
        AppSettings.lastScreen = when (currentKind) {
            ContentKind.LIVE -> AppScreen.CHANNELS
            ContentKind.SERIES -> AppScreen.SERIES
            ContentKind.MOVIES -> AppScreen.MOVIES
        }

        binding.title.text = "${source.name} · ${currentKind.title}"
        binding.backButton.text = when {
            fromTv && currentKind == ContentKind.LIVE -> getString(R.string.back_to_tv)
            else -> getString(R.string.back_section)
        }
        binding.backButton.setOnClickListener { navigateBack() }
        return true
    }

    private fun onItemClick(item: CatalogItem) {
        when {
            currentKind == ContentKind.SERIES && item.seriesId != null -> {
                startActivity(
                    Intent(this, EpisodesActivity::class.java)
                        .putExtra(EpisodesActivity.EXTRA_SOURCE_ID, source.id)
                        .putExtra(EpisodesActivity.EXTRA_SERIES_ID, item.seriesId)
                        .putExtra(EpisodesActivity.EXTRA_SERIES_NAME, item.name)
                )
            }
            item.url.isNotBlank() -> {
                if (currentKind == ContentKind.LIVE) {
                    EpisodeQueue.clear()
                    ZapPlaylist.set(
                        PlaylistRepository.memoryCached(source.id, ContentKind.LIVE)
                            .ifEmpty { allItems }
                            .ifEmpty { visibleItems() }
                    )
                    AppSettings.saveLastLive(item)
                    AppSettings.lastScreen = AppScreen.TV
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URL, item.url)
                            .putExtra(PlayerActivity.EXTRA_NAME, item.name)
                            .putExtra(PlayerActivity.EXTRA_GROUP, item.group)
                            .putExtra(PlayerActivity.EXTRA_NUMBER, item.number)
                            .putExtra(PlayerActivity.EXTRA_LOGO, item.logo)
                            .putExtra(PlayerActivity.EXTRA_STREAM_ID, item.streamId ?: -1)
                            .putExtra(PlayerActivity.EXTRA_SOURCE_ID, source.id)
                            .putExtra(PlayerActivity.EXTRA_ZAP_ENABLED, true)
                            .putExtra(PlayerActivity.EXTRA_SEEK_ENABLED, false)
                    )
                    finish()
                } else {
                    ModeNav.openVod(
                        this,
                        source.id,
                        VodPlayback(
                            url = item.url,
                            name = item.name,
                            group = item.group,
                            logo = item.logo,
                            number = item.number,
                            seriesId = null,
                            seriesName = null,
                            positionMs = 0L
                        )
                    )
                }
            }
        }
    }

    private fun navigateBack() {
        if (fromTv && currentKind == ContentKind.LIVE) {
            ModeNav.openTv(this, source.id)
        } else {
            finish()
        }
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
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                PlaylistRepository.memoryCached(source.id, currentKind)
                    .ifEmpty { PlaylistRepository.diskCached(this@CatalogActivity, source.id, currentKind) }
            }
            if (cached.isNotEmpty()) {
                applyItems(cached, "${cached.size} ${currentKind.loadingLabel} (caché) · actualizando…")
            } else {
                allItems = emptyList()
                catalogAdapter.submit(emptyList())
                binding.loading.visibility = View.VISIBLE
                binding.status.text = getString(R.string.loading_kind, currentKind.loadingLabel)
            }

            try {
                val fresh = PlaylistRepository.loadCatalog(
                    this@CatalogActivity, source, currentKind, force = cached.isNotEmpty()
                )
                applyItems(fresh, "${fresh.size} ${currentKind.loadingLabel} · ${source.hint}")
            } catch (e: Exception) {
                binding.loading.visibility = View.GONE
                if (cached.isEmpty()) {
                    binding.status.text = getString(R.string.load_error) + " (${e.message})"
                    binding.backButton.requestFocus()
                } else {
                    binding.status.text =
                        "${cached.size} ${currentKind.loadingLabel} (caché) · sin red"
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            navigateBack()
            return true
        }
        if (ModeNav.handleColorKey(this, keyCode, source.id)) return true
        return super.onKeyDown(keyCode, event)
    }
}
