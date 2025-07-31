package ink.enoch.glyphplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar
import ink.enoch.glyphplayer.databinding.ActivityMainBinding
import ink.enoch.glyphplayer.databinding.ActivityManualBinding


class MainActivity : AppCompatActivity() {
    private lateinit var binding:ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }


        binding.btnGoAudioVisualAi.setOnClickListener {
            Snackbar.make(window.decorView, "Go AudioVisualAi", Snackbar.LENGTH_LONG).show()
            startActivity(Intent().setClass(this@MainActivity, AudioVisualAiActivity::class.java))
        }
        binding.btnGoDebug.setOnClickListener {
            Snackbar.make(window.decorView, "Go Debug", Snackbar.LENGTH_LONG).show()
            startActivity(Intent().setClass(this@MainActivity, ManualActivity::class.java))
        }
    }
}