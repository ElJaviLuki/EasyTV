package tv.facil.abuelo

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import tv.facil.abuelo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subtitle.text = getString(R.string.choose_server)
        binding.serverList.layoutManager = LinearLayoutManager(this)
        binding.serverList.adapter = ServerAdapter(SeededPlaylists.sources) { source ->
            startActivity(
                Intent(this, ChannelsActivity::class.java)
                    .putExtra(ChannelsActivity.EXTRA_SOURCE_ID, source.id)
            )
        }
    }
}
