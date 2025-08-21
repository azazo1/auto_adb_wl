package com.azazo1.auto_adb_wl

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.azazo1.auto_adb_wl.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

fun String.toMessageConnect(): String {
    return "c-$this"
}

fun String.toMessageDisconnect(): String {
    return "d-$this"
}


class MainActivity : AppCompatActivity() {
    companion object {
        const val WIDGET_PREF = "widget_pref"
        const val INPUT_ADDR = "input_addr"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sharedPreferences = getSharedPreferences(WIDGET_PREF, MODE_PRIVATE)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        loadWidgetStates()
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnPair.setOnClickListener { pair() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.etServerAddr.addTextChangedListener {
            it?.let {
                sharedPreferences.edit {
                    putString(INPUT_ADDR, it.toString())
                }
            }
        }
    }

    fun loadWidgetStates() {
        binding.etServerAddr.setText(
            sharedPreferences.getString(INPUT_ADDR, "localhost:15555")
        )
    }

    fun pair() {
    }

    fun getInputAddr(): InetSocketAddress {
        val addr = binding.etServerAddr.text.toString()
        val addrPair = addr.split(":")
        return InetSocketAddress(addrPair[0], addrPair[1].toInt())
    }

    fun connect(disconnect: Boolean = false) {
        val inputAddr = try {
            getInputAddr()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.please_fill_input_addr, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            if (MyAccessibilityService.instance != null) {
                val addr = MyAccessibilityService.instance!!.openWirelessDebugAndGetAddr()
                if (addr == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.failed_to_get_wireless_debug_address,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val comm = CommunicatorPlain()
                comm.connect(inputAddr)
                comm.send(
                    if (disconnect) {
                        addr.toMessageDisconnect()
                    } else {
                        addr.toMessageConnect()
                    }
                )
                Toast.makeText(this@MainActivity, addr, Toast.LENGTH_SHORT).show()
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

    fun disconnect() {
        connect(true)
    }
}