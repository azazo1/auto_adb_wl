package com.azazo1.auto_adb_wl

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.azazo1.auto_adb_wl.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnPair.setOnClickListener { pair() }
    }

    fun pair() {
        val addr = binding.etServerAddr.toString()
        val addrPair = addr.split(":")
        lifecycleScope.launch {
            val c = CommunicatorPlain()
            c.connect(InetSocketAddress(addrPair[0], addrPair[1].toInt()))
            c.send("nihao")
            Log.i("ma", c.receive().orEmpty())
        }
    }

    fun connect() {
        lifecycleScope.launch {
            if (MyAccessibilityService.instance != null) {
                val s = MyAccessibilityService.instance!!.openWirelessDebugAndGetAddr()
                Toast.makeText(this@MainActivity, s, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    R.string.no_accessibility_permission,
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }
}