package com.mako.miniplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MainActivity : FragmentActivity() {

    private val PREFS = "mako_prefs"
    private val gson = Gson()

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val intent = Intent(this, PlayerActivity::class.java).apply {
                data = it
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickVideo.launch(arrayOf("video/*"))
        } else {
            Toast.makeText(this, "Storage permission needed.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.data != null) {
            val playerIntent = Intent(this, PlayerActivity::class.java).apply {
                data = intent.data
                action = Intent.ACTION_VIEW
            }
            startActivity(playerIntent)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_open_video)?.setOnClickListener {
            openVideoPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecentFiles()
    }

    private fun openVideoPicker() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_MEDIA_VIDEO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    pickVideo.launch(arrayOf("video/*"))
                } else {
                    requestPermission.launch(Manifest.permission.READ_MEDIA_VIDEO)
                }
            }
            else -> {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    pickVideo.launch(arrayOf("video/*"))
                } else {
                    requestPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun getRecentFiles(): List<String> {
        val json = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("recent_files", "[]") ?: "[]"
        val type = object : TypeToken<List<String>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { emptyList() }
    }

    private fun loadRecentFiles() {
        val container = findViewById<LinearLayout>(R.id.recent_files_container) ?: return
        val label = findViewById<TextView>(R.id.tv_recent_label) ?: return
        container.removeAllViews()

        val recentFiles = getRecentFiles()
        if (recentFiles.isEmpty()) {
            label.visibility = View.GONE
            return
        }

        label.visibility = View.VISIBLE

        recentFiles.take(8).forEach { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                val name = uri.lastPathSegment
                    ?.substringAfterLast("/")
                    ?.substringBeforeLast(".")
                    ?: uriStr.substringAfterLast("/").substringBeforeLast(".")

                val btn = Button(this).apply {
                    text = "▶  $name"
                    textSize = 12f
                    setTextColor(android.graphics.Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#2A0A4E")
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 8)
                        width = 520
                    }
                    isFocusable = true
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(24, 0, 24, 0)

                    setOnClickListener {
                        try {
                            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                                data = uri
                                action = Intent.ACTION_VIEW
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    setOnLongClickListener {
                        removeRecentFile(uriStr)
                        loadRecentFiles()
                        true
                    }
                }
                container.addView(btn)
            } catch (e: Exception) { }
        }

        // Hint za long press
        if (recentFiles.isNotEmpty()) {
            val hint = TextView(this).apply {
                text = "long press to remove"
                textSize = 10f
                setTextColor(android.graphics.Color.parseColor("#444444"))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 0) }
            }
            container.addView(hint)
        }
    }

    private fun removeRecentFile(uriStr: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString("recent_files", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<String>>() {}.type
        val list: MutableList<String> = try { gson.fromJson(json, type) } catch (e: Exception) { mutableListOf() }
        list.remove(uriStr)
        prefs.edit().putString("recent_files", gson.toJson(list)).apply()
    }
}