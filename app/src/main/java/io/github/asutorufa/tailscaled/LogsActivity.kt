package io.github.asutorufa.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import appctr.Appctr
import io.github.asutorufa.tailscaled.databinding.ActivityLogsBinding

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val adapter = LogsAdapter()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true 
        }
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadLogs() }
        loadLogs()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.logs_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_copy -> {
                copyLogs()
                true
            }
            R.id.action_share -> {
                shareLogs()
                true
            }
            R.id.action_clear -> {
                Appctr.clearLogs()
                loadLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadLogs() {
        binding.swipeRefresh.isRefreshing = true
        Thread {
            val logsString = try { Appctr.getLogs() } catch (e: Exception) { "" }
            val logsList = logsString.split("\n").filter { it.isNotEmpty() }
            
            runOnUiThread {
                adapter.submitList(logsList) {
                     if (logsList.isNotEmpty()) binding.recyclerView.scrollToPosition(logsList.size - 1)
                }
                binding.swipeRefresh.isRefreshing = false
            }
        }.start()
    }

    private fun copyLogs() {
        val logs = Appctr.getLogs()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Tailscale Logs", logs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val logs = Appctr.getLogs()
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, logs)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share logs via"))
    }
}