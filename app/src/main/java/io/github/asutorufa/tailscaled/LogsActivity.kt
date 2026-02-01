package io.github.asutorufa.tailscaled

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import appctr.Appctr
import io.github.asutorufa.tailscaled.databinding.ActivityLogsBinding
import java.util.concurrent.Executors

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val adapter = LogsAdapter()
    // Простой экзекутор для фона, чтобы не тянуть корутины если их нет, 
    // но в твоем проекте вроде Kotlin, так что можно и Thread/Handler
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // Прижимаем логи к низу
        }
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadLogs() }
        
        binding.fabClear.setOnClickListener {
            Appctr.clearLogs()
            loadLogs()
        }

        loadLogs()
    }

    private fun loadLogs() {
        binding.swipeRefresh.isRefreshing = true
        Thread {
            val logsString = try {
                Appctr.getLogs()
            } catch (e: Exception) {
                "Error reading logs: ${e.message}"
            }
            
            val logsList = logsString.split("\n").filter { it.isNotEmpty() }
            
            runOnUiThread {
                adapter.submitList(logsList) {
                     if (logsList.isNotEmpty()) {
                        binding.recyclerView.scrollToPosition(logsList.size - 1)
                     }
                }
                binding.swipeRefresh.isRefreshing = false
            }
        }.start()
    }
}