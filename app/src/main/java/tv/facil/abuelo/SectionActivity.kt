package tv.facil.abuelo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import tv.facil.abuelo.databinding.ActivityMainBinding

class SectionActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val source = PlaylistStore.byId(intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()) ?: run {
            finish()
            return
        }

        binding.brand.text = source.name
        binding.subtitle.text = getString(R.string.choose_section)
        binding.serverList.layoutManager = LinearLayoutManager(this)
        binding.serverList.adapter = SectionAdapter(
            listOf(ContentKind.LIVE, ContentKind.SERIES, ContentKind.MOVIES)
        ) { kind ->
            AppSettings.lastSourceId = source.id
            when (kind) {
                ContentKind.LIVE -> ModeNav.openTv(this, source.id)
                ContentKind.SERIES -> ModeNav.openCatalog(this, source.id, ContentKind.SERIES)
                ContentKind.MOVIES -> ModeNav.openCatalog(this, source.id, ContentKind.MOVIES)
            }
        }
    }
}
