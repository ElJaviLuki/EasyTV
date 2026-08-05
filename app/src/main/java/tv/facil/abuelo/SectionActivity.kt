package tv.facil.abuelo

import android.content.Intent
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

        val source = SeededPlaylists.byId(intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()) ?: run {
            finish()
            return
        }

        binding.brand.text = source.name
        binding.subtitle.text = getString(R.string.choose_section)
        binding.serverList.layoutManager = LinearLayoutManager(this)
        binding.serverList.adapter = SectionAdapter(
            listOf(ContentKind.LIVE, ContentKind.SERIES, ContentKind.MOVIES)
        ) { kind ->
            startActivity(
                Intent(this, CatalogActivity::class.java)
                    .putExtra(CatalogActivity.EXTRA_SOURCE_ID, source.id)
                    .putExtra(CatalogActivity.EXTRA_KIND, kind.name)
            )
        }
    }
}
