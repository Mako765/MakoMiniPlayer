package com.mako.miniplayer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

@UnstableApi
class PlayerActivity : FragmentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var subtitleOverlay: OutlineTextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient()
    private val gson = Gson()

    private var subtitleTextSize = 18f
    private var subtitleBold = false
    private var subtitleColor = "#FFFFFF"
    private var subtitleBackground = false
    private var subtitleShadow = true
    private var subtitleShadowRadius = 6f
    private var subtitleOutlineWidth = 1.5f
    private var subtitleEncoding = "UTF-8"
    private var subtitleBottomMargin = 60
    private var subtitleOffsetMs = 0L

    private var osToken = ""
    private var osApiKey = ""
    private var osUsername = ""
    private var osPassword = ""
    private var subdlApiKey = ""

    private var isHlsStream = false
    private var currentSearchTitle = "subtitle"
    private var lastSearchTitle = ""
    private var videoUri: Uri? = null
    private var savedPosition = 0L
    private var wasPlaying = false

    private var currentSubtitleFile: File? = null
    private var currentSubtitleIsLocal = false
    private var localVideoFile: File? = null

    data class SubtitleEntry(val startMs: Long, val endMs: Long, val text: String)
    private var subtitleEntries = listOf<SubtitleEntry>()
    private var subtitleEnabled = false
    private val subtitleHandler = Handler(Looper.getMainLooper())
    private val subtitleRunnable = object : Runnable {
        override fun run() {
            updateSubtitleOverlay()
            subtitleHandler.postDelayed(this, 100)
        }
    }

    private val PREFS = "mako_prefs"

    private data class AspectRatio(val name: String, val width: Int, val height: Int)

    private val aspectRatios = listOf(
        AspectRatio("Default", 0, 0),
        AspectRatio("Fit", -1, -1),
        AspectRatio("Fill", -2, -2),
        AspectRatio("Zoom", -3, -3),
        AspectRatio("1:1", 1, 1),
        AspectRatio("4:3", 4, 3),
        AspectRatio("5:4", 5, 4),
        AspectRatio("16:9", 16, 9),
        AspectRatio("16:10", 16, 10),
        AspectRatio("18:9", 18, 9),
        AspectRatio("20:9", 20, 9),
        AspectRatio("21:9", 21, 9),
        AspectRatio("21:10", 21, 10),
        AspectRatio("64:27", 64, 27),
        AspectRatio("2.21:1", 221, 100),
        AspectRatio("2.35:1", 235, 100),
        AspectRatio("2.39:1", 239, 100),
        AspectRatio("2.55:1", 255, 100),
        AspectRatio("2.76:1", 276, 100)
    )
    private var currentAspectRatio = 0

    enum class SubSource { SUBDL, OPENSUBTITLES_COM }

    private val pickSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val currentUri = videoUri
        if (currentUri != null) {
            when (player.playbackState) {
                Player.STATE_IDLE, Player.STATE_ENDED -> reloadVideo(currentUri, savedPosition, wasPlaying)
                else -> {
                    try { player.seekTo(savedPosition) } catch (e: Exception) { }
                    if (wasPlaying) player.play()
                }
            }
        }
        uri?.let { loadSubtitleFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        subtitleOverlay = findViewById(R.id.subtitle_overlay)

        if (savedInstanceState != null && intent.data == null) {
            savedInstanceState.getString("video_uri")?.let {
                intent.data = Uri.parse(it)
            }
        }

        loadPrefs()
        initPlayer()
        loadVideo()
        applySubtitleStyle()
        applySubtitlePosition()

        playerView.findViewById<ImageButton>(R.id.btn_rewind)?.setOnClickListener { skipBackward() }
        playerView.findViewById<ImageButton>(R.id.btn_forward)?.setOnClickListener { skipForward() }
        playerView.findViewById<Button>(R.id.btn_speed)?.setOnClickListener { showSpeedDialog() }
        playerView.findViewById<Button>(R.id.btn_subtitles)?.setOnClickListener { showSubtitleMainDialog() }
        playerView.findViewById<Button>(R.id.btn_resize)?.setOnClickListener { cycleResizeMode() }
        playerView.findViewById<Button>(R.id.btn_video_info)?.setOnClickListener { showVideoInfo() }

        playerView.findViewById<Button>(R.id.btn_sub_plus)?.setOnClickListener {
            subtitleOffsetMs += 500
            playerView.findViewById<TextView>(R.id.tv_sub_offset)?.text = formatOffset(subtitleOffsetMs)
        }

        playerView.findViewById<Button>(R.id.btn_sub_minus)?.setOnClickListener {
            subtitleOffsetMs -= 500
            playerView.findViewById<TextView>(R.id.tv_sub_offset)?.text = formatOffset(subtitleOffsetMs)
        }

        playerView.post {
            try {
                val ffwdId = resources.getIdentifier("exo_ffwd_with_amount", "id", packageName)
                val rewId = resources.getIdentifier("exo_rew_with_amount", "id", packageName)
                if (ffwdId != 0) playerView.findViewById<android.view.View>(ffwdId)?.visibility = android.view.View.GONE
                if (rewId != 0) playerView.findViewById<android.view.View>(rewId)?.visibility = android.view.View.GONE
            } catch (e: Exception) { }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let { outState.putString("video_uri", it.toString()) }
        outState.putLong("saved_position", player.currentPosition)
        outState.putBoolean("was_playing", player.isPlaying)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadVideo()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        subdlApiKey = prefs.getString("subdl_api_key", "") ?: ""
        osApiKey = prefs.getString("os_api_key", "") ?: ""
        osUsername = prefs.getString("os_username", "") ?: ""
        osPassword = prefs.getString("os_password", "") ?: ""
        subtitleTextSize = prefs.getFloat("sub_size", 18f)
        subtitleBold = prefs.getBoolean("sub_bold", false)
        subtitleColor = prefs.getString("sub_color", "#FFFFFF") ?: "#FFFFFF"
        subtitleBackground = prefs.getBoolean("sub_background", false)
        subtitleShadow = prefs.getBoolean("sub_shadow", true)
        subtitleShadowRadius = prefs.getFloat("sub_shadow_radius", 6f)
        subtitleOutlineWidth = prefs.getFloat("sub_outline_width", 1.5f)
        subtitleEncoding = prefs.getString("sub_encoding", "UTF-8") ?: "UTF-8"
        subtitleBottomMargin = prefs.getInt("sub_margin", 60)
        currentAspectRatio = prefs.getInt("aspect_ratio", 0)
        lastSearchTitle = prefs.getString("last_search_title", "") ?: ""
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            putString("subdl_api_key", subdlApiKey)
            putString("os_api_key", osApiKey)
            putString("os_username", osUsername)
            putString("os_password", osPassword)
            putFloat("sub_size", subtitleTextSize)
            putBoolean("sub_bold", subtitleBold)
            putString("sub_color", subtitleColor)
            putBoolean("sub_background", subtitleBackground)
            putBoolean("sub_shadow", subtitleShadow)
            putFloat("sub_shadow_radius", subtitleShadowRadius)
            putFloat("sub_outline_width", subtitleOutlineWidth)
            putString("sub_encoding", subtitleEncoding)
            putInt("sub_margin", subtitleBottomMargin)
            putInt("aspect_ratio", currentAspectRatio)
            putString("last_search_title", lastSearchTitle)
            apply()
        }
    }

    private fun saveRecentFile(uri: Uri) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString("recent_files", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<String>>() {}.type
        val list: MutableList<String> = try { gson.fromJson(json, type) } catch (e: Exception) { mutableListOf() }
        val uriStr = uri.toString()
        list.remove(uriStr)
        list.add(0, uriStr)
        if (list.size > 10) list.subList(10, list.size).clear()
        prefs.edit().putString("recent_files", gson.toJson(list)).apply()
    }

    private fun getRecentFiles(): List<String> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString("recent_files", "[]") ?: "[]"
        val type = object : TypeToken<List<String>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { emptyList() }
    }

    private fun saveVideoPosition(uri: Uri, position: Long) {
        if (position < 5000) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString("saved_positions", "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, Long>>() {}.type
        val map: MutableMap<String, Long> = try { gson.fromJson(json, type) } catch (e: Exception) { mutableMapOf() }
        map[uri.toString()] = position
        if (map.size > 50) map.remove(map.keys.first())
        prefs.edit().putString("saved_positions", gson.toJson(map)).apply()
    }

    private fun getSavedPosition(uri: Uri): Long {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString("saved_positions", "{}") ?: "{}"
        val type = object : TypeToken<Map<String, Long>>() {}.type
        return try {
            val map: Map<String, Long> = gson.fromJson(json, type)
            map[uri.toString()] ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    private fun formatOffset(ms: Long): String {
        val sign = if (ms >= 0) "+" else "-"
        val abs = Math.abs(ms)
        return "$sign${"%.1f".format(abs / 1000.0)}s"
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.useController = true
        playerView.keepScreenOn = true

        playerView.setShowRewindButton(false)
        playerView.setShowFastForwardButton(false)
        playerView.setShowMultiWindowTimeBar(false)
        playerView.setShowShuffleButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setShowNextButton(false)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        playerView.controllerHideOnTouch = true
        playerView.controllerAutoShow = true

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        playerView.subtitleView?.visibility = android.view.View.GONE
        applyAspectRatio(showToast = false)
    }

    private fun getDataSourceFactory(uri: Uri) = when (uri.scheme) {
        "http", "https" -> DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        else -> DefaultDataSource.Factory(this)
    }

    private fun isHls(uri: Uri): Boolean {
        val str = uri.toString().lowercase()
        return str.contains(".m3u8") || str.contains(".m3u")
    }

    private fun isNetworkUri(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme in listOf("http", "https", "ftp", "rtsp")) return true
        if (scheme == "content") {
            val str = uri.toString().lowercase()
            return str.contains("smb") || str.contains("network") ||
                    str.contains("lan") || str.contains("ftp") ||
                    str.contains("webdav")
        }
        return false
    }

    private fun loadVideo() {
        val uri = intent.data ?: run {
            Toast.makeText(this, "No video file!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        videoUri = uri
        saveRecentFile(uri)

        val savedPos = getSavedPosition(uri)
        if (savedPos > 10000) {
            AlertDialog.Builder(this)
                .setTitle("Continue watching?")
                .setMessage("Resume from ${formatTime(savedPos)}?")
                .setPositiveButton("▶ Continue") { _, _ -> startPlayback(uri, savedPos) }
                .setNegativeButton("⏮ Start from beginning") { _, _ -> startPlayback(uri, 0) }
                .setCancelable(false)
                .show()
        } else {
            startPlayback(uri, 0)
        }
    }

    private fun startPlayback(uri: Uri, startPosition: Long) {
        autoLoadSubtitle(uri)

        if (!isNetworkUri(uri)) {
            val path = getRealPath(uri)
            if (path != null) localVideoFile = File(path)
        }

        try {
            player.stop()
            player.clearMediaItems()

            val dataSourceFactory = getDataSourceFactory(uri)
            isHlsStream = isHls(uri)
            val mediaItem = MediaItem.Builder().setUri(uri).build()
            val mediaSource = if (isHlsStream) {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            player.setMediaSource(mediaSource)
            player.prepare()
            if (startPosition > 0) player.seekTo(startPosition)
            player.playWhenReady = true

            showOsdInfo(uri)

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (!isHlsStream) {
                        isHlsStream = true
                        player.stop()
                        player.clearMediaItems()
                        val hlsSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                        player.setMediaSource(hlsSource)
                        player.prepare()
                        player.playWhenReady = true
                    } else {
                        Toast.makeText(this@PlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        videoUri?.let { saveVideoPosition(it, 0L) }
                        finish()
                    }
                }
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showOsdInfo(uri: Uri) {
        val name = uri.lastPathSegment?.substringBeforeLast(".") ?: uri.toString().substringAfterLast("/")
        Toast.makeText(this, "▶ $name", Toast.LENGTH_LONG).show()
    }

    private fun reloadVideo(uri: Uri, seekTo: Long, play: Boolean) {
        try {
            player.stop()
            player.clearMediaItems()
            val dataSourceFactory = getDataSourceFactory(uri)
            val mediaItem = MediaItem.Builder().setUri(uri).build()
            val mediaSource = if (isHls(uri)) {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }
            player.setMediaSource(mediaSource)
            player.prepare()
            player.seekTo(seekTo)
            player.playWhenReady = play
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish()
                }
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Reload error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getRealPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme == "content") {
            try {
                contentResolver.query(
                    uri, arrayOf(android.provider.MediaStore.Video.Media.DATA),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                        if (idx >= 0) {
                            val path = cursor.getString(idx)
                            if (!path.isNullOrEmpty()) return path
                        }
                    }
                }
            } catch (e: Exception) { }
            uri.path?.let { path ->
                val cleanPath = when {
                    path.contains("/storage/emulated") -> path
                    path.startsWith("/document/") -> "/storage/" + path.removePrefix("/document/").replace(":", "/")
                    path.startsWith("/tree/") -> "/storage/" + path.removePrefix("/tree/").replace(":", "/")
                    else -> path
                }
                val f = File(cleanPath)
                if (f.exists()) return cleanPath
            }
        }
        return null
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a
        val commonChars = shorter.count { c -> longer.contains(c) }
        return commonChars.toFloat() / longer.length.toFloat()
    }

    private fun findSubtitleInDir(dir: File, videoNameNoExt: String): File? {
        val allSrtFiles = dir.listFiles { f ->
            f.extension.lowercase() in listOf("srt", "sub", "ass")
        } ?: emptyArray()

        val found = allSrtFiles.firstOrNull { srt ->
            val srtName = srt.nameWithoutExtension.lowercase()
            srtName == videoNameNoExt ||
                    videoNameNoExt.contains(srtName) ||
                    srtName.contains(videoNameNoExt) ||
                    similarity(videoNameNoExt, srtName) >= 0.85f
        }
        if (found != null) return found

        val subFolderNames = listOf("Subs", "Subtitles", "Sub", "subs", "subtitles", "sub", "Subtitle", "subtitle")
        for (folderName in subFolderNames) {
            val subDir = File(dir, folderName)
            if (subDir.exists() && subDir.isDirectory) {
                val subFiles = subDir.listFiles { f ->
                    f.extension.lowercase() in listOf("srt", "sub", "ass")
                } ?: continue
                val subFound = subFiles.firstOrNull { srt ->
                    val srtName = srt.nameWithoutExtension.lowercase()
                    srtName == videoNameNoExt ||
                            videoNameNoExt.contains(srtName) ||
                            srtName.contains(videoNameNoExt) ||
                            similarity(videoNameNoExt, srtName) >= 0.85f
                }
                if (subFound != null) return subFound
            }
        }
        return null
    }

    private fun autoLoadSubtitle(videoUri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                if (isNetworkUri(videoUri)) {
                    autoLoadNetworkSubtitle(videoUri)
                    return@launch
                }

                val videoPath = getRealPath(videoUri) ?: return@launch
                val videoFile = File(videoPath)
                if (!videoFile.exists()) return@launch

                val videoNameNoExt = videoFile.nameWithoutExtension.lowercase()
                val videoDir = videoFile.parentFile ?: return@launch
                val subtitleFile = findSubtitleInDir(videoDir, videoNameNoExt)

                if (subtitleFile != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PlayerActivity, "Auto subtitle: ${subtitleFile.name}", Toast.LENGTH_SHORT).show()
                        currentSubtitleFile = subtitleFile
                        currentSubtitleIsLocal = true
                        startSubtitleOverlay(subtitleFile)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SRT_DEBUG", "Auto-load error: ${e.message}")
            }
        }
    }

    private fun autoLoadNetworkSubtitle(videoUri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val videoUrl = videoUri.toString()
                val videoNameNoExt = videoUrl.substringAfterLast("/").substringBeforeLast(".")
                val baseUrl = videoUrl.substringBeforeLast("/")
                val extensions = listOf(".srt", ".SRT", ".sub", ".SUB")
                for (ext in extensions) {
                    val srtUrl = "$baseUrl/$videoNameNoExt$ext"
                    try {
                        val response = client.newCall(Request.Builder().url(srtUrl).head().build()).execute()
                        if (response.isSuccessful) {
                            val dlBytes = client.newCall(Request.Builder().url(srtUrl).build()).execute().body?.bytes() ?: continue
                            val tempFile = File(cacheDir, "subtitle_network.srt")
                            FileOutputStream(tempFile).use { it.write(dlBytes) }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@PlayerActivity, "Auto subtitle: $videoNameNoExt$ext", Toast.LENGTH_SHORT).show()
                                currentSubtitleFile = tempFile
                                currentSubtitleIsLocal = false
                                startSubtitleOverlay(tempFile)
                            }
                            return@launch
                        }
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) {
                android.util.Log.e("SRT_DEBUG", "Network auto-load error: ${e.message}")
            }
        }
    }

    private fun parseSrt(file: File): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        try {
            val bytes = file.readBytes()
            val text = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, Charsets.UTF_16LE).replace("\uFEFF", "")
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, Charsets.UTF_16BE).replace("\uFEFF", "")
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    String(bytes.drop(3).toByteArray(), Charsets.UTF_8)
                else -> {
                    val hasUtf8Balkan = (0 until bytes.size - 1).any { i ->
                        val b0 = bytes[i].toInt() and 0xFF
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        (b0 == 0xC5 && b1 in listOf(0xA0, 0xA1, 0xBD, 0xBE)) ||
                                (b0 == 0xC4 && b1 in listOf(0x8C, 0x8D, 0x86, 0x87, 0x90, 0x91))
                    }
                    val hasWin1250 = bytes.any { b ->
                        val u = b.toInt() and 0xFF
                        u in listOf(0x8A, 0x9A, 0x8E, 0x9E, 0xC8, 0xE8, 0xC6, 0xE6, 0xD0, 0xF0)
                    }
                    when {
                        hasUtf8Balkan -> String(bytes, Charsets.UTF_8)
                        hasWin1250 -> String(bytes, Charset.forName("Windows-1250"))
                        else -> {
                            val utf8 = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { null }
                            if (utf8 != null && !utf8.contains('\uFFFD')) utf8
                            else String(bytes, Charset.forName("Windows-1250"))
                        }
                    }
                }
            }.replace("\r\n", "\n").replace("\r", "\n")

            val blocks = text.trim().split(Regex("\n\n+"))
            for (block in blocks) {
                val lines = block.trim().split("\n")
                if (lines.size < 2) continue
                val timeLine = lines.firstOrNull { it.contains("-->") } ?: continue
                val parts = timeLine.split("-->")
                if (parts.size < 2) continue
                val startMs = parseTimestamp(parts[0].trim())
                val endMs = parseTimestamp(parts[1].trim().split(" ")[0])
                if (startMs < 0 || endMs < 0 || endMs <= startMs) continue
                val timeIndex = lines.indexOf(timeLine)
                val subtitleText = lines.drop(timeIndex + 1)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .replace(Regex("<[^>]*>"), "")
                    .replace(Regex("\\{[^}]*\\}"), "")
                    .trim()
                if (subtitleText.isNotEmpty()) {
                    entries.add(SubtitleEntry(startMs, endMs, subtitleText))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SRT_DEBUG", "Parse error: ${e.message}")
        }
        return entries.sortedBy { it.startMs }
    }

    private fun parseTimestamp(ts: String): Long {
        return try {
            val clean = ts.trim().replace(",", ".")
            val parts = clean.split(":")
            if (parts.size != 3) return -1
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val secMs = parts[2].split(".")
            val seconds = secMs[0].toLong()
            val ms = if (secMs.size > 1) secMs[1].padEnd(3, '0').take(3).toLong() else 0L
            (hours * 3600 + minutes * 60 + seconds) * 1000 + ms
        } catch (e: Exception) { -1L }
    }

    private fun updateSubtitleOverlay() {
        if (!subtitleEnabled || subtitleEntries.isEmpty()) {
            subtitleOverlay.visibility = android.view.View.GONE
            return
        }
        val currentMs = player.currentPosition + subtitleOffsetMs
        val entry = subtitleEntries.firstOrNull { currentMs >= it.startMs && currentMs <= it.endMs }
        if (entry != null) {
            subtitleOverlay.text = entry.text
            subtitleOverlay.visibility = android.view.View.VISIBLE
        } else {
            subtitleOverlay.visibility = android.view.View.GONE
        }
    }

    private fun startSubtitleOverlay(file: File) {
        scope.launch(Dispatchers.IO) {
            val entries = parseSrt(file)
            withContext(Dispatchers.Main) {
                if (entries.isEmpty()) {
                    Toast.makeText(this@PlayerActivity, "No subtitles! File: ${file.name} (${file.length()} bytes)", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                subtitleEntries = entries
                subtitleEnabled = true
                subtitleOffsetMs = 0L
                playerView.findViewById<TextView>(R.id.tv_sub_offset)?.text = "+0.0s"
                subtitleHandler.removeCallbacks(subtitleRunnable)
                subtitleHandler.post(subtitleRunnable)
                applySubtitleStyle()
                applySubtitlePosition()
                Toast.makeText(this@PlayerActivity, "Subtitle loaded! (${entries.size} lines)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopSubtitleOverlay() {
        subtitleEnabled = false
        subtitleEntries = listOf()
        subtitleOffsetMs = 0L
        playerView.findViewById<TextView>(R.id.tv_sub_offset)?.text = "+0.0s"
        subtitleHandler.removeCallbacks(subtitleRunnable)
        subtitleOverlay.visibility = android.view.View.GONE
        currentSubtitleFile = null
    }

    private fun applySubtitlePosition() {
        val density = resources.displayMetrics.density
        val marginPx = (subtitleBottomMargin * density).toInt()
        val params = subtitleOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
        params.bottomMargin = marginPx
        subtitleOverlay.layoutParams = params
    }

    private fun saveSubtitleToMovieFolder() {
        val subFile = currentSubtitleFile ?: run {
            Toast.makeText(this, "No subtitle loaded!", Toast.LENGTH_SHORT).show()
            return
        }
        val videoFile = localVideoFile ?: run {
            Toast.makeText(this, "Only available for local files!", Toast.LENGTH_SHORT).show()
            return
        }

        val suggestedName = videoFile.nameWithoutExtension
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        layout.addView(TextView(this).apply {
            text = "Current subtitle:\n${subFile.name}"
            textSize = 12f
            setPadding(0, 0, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = "Movie name:\n${videoFile.name}"
            textSize = 12f
            setPadding(0, 0, 0, 8)
        })

        val etName = EditText(this).apply {
            setText(suggestedName)
            hint = "Save as (without extension)"
        }
        layout.addView(TextView(this).apply { text = "Save as:"; setPadding(0, 8, 0, 4) })
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("💾 Save subtitle to movie folder")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val targetFile = File(videoFile.parentFile, "$newName.srt")
                scope.launch(Dispatchers.IO) {
                    try {
                        subFile.copyTo(targetFile, overwrite = true)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PlayerActivity, "Saved! Auto-load ready ✅", Toast.LENGTH_SHORT).show()
                            currentSubtitleFile = targetFile
                            currentSubtitleIsLocal = true
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PlayerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameSubtitleFile() {
        val subFile = currentSubtitleFile ?: run {
            Toast.makeText(this, "No subtitle loaded!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!subFile.exists()) {
            Toast.makeText(this, "Subtitle file not found!", Toast.LENGTH_SHORT).show()
            return
        }

        val suggestedName = localVideoFile?.nameWithoutExtension ?: subFile.nameWithoutExtension
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        layout.addView(TextView(this).apply {
            text = "Current: ${subFile.name}"
            textSize = 12f
            setPadding(0, 0, 0, 4)
        })
        localVideoFile?.let {
            layout.addView(TextView(this).apply {
                text = "Movie: ${it.name}"
                textSize = 12f
                setPadding(0, 0, 0, 8)
            })
        }

        val etName = EditText(this).apply {
            setText(suggestedName)
            hint = "New name (without extension)"
        }
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("✏️ Rename subtitle")
            .setView(layout)
            .setPositiveButton("Rename") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val newFile = File(subFile.parentFile, "$newName.srt")
                scope.launch(Dispatchers.IO) {
                    try {
                        subFile.renameTo(newFile)
                        withContext(Dispatchers.Main) {
                            currentSubtitleFile = newFile
                            Toast.makeText(this@PlayerActivity, "Renamed to: ${newFile.name} ✅", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PlayerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoInfo() {
        val uri = videoUri ?: return
        val sb = StringBuilder()
        sb.appendLine("📁 ${uri.lastPathSegment ?: uri.toString().substringAfterLast("/")}")
        sb.appendLine()

        for (group in player.currentTracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            val fmt = group.getTrackFormat(i)
                            sb.appendLine("🎬 Video: ${fmt.width}x${fmt.height}")
                            if (fmt.frameRate > 0) sb.appendLine("   FPS: ${"%.2f".format(fmt.frameRate)}")
                            if (fmt.codecs != null) sb.appendLine("   Codec: ${fmt.codecs}")
                            if (fmt.bitrate > 0) sb.appendLine("   Bitrate: ${fmt.bitrate / 1000} kbps")
                        }
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            val fmt = group.getTrackFormat(i)
                            sb.appendLine("🔊 Audio: ${fmt.language ?: "unknown"}")
                            if (fmt.channelCount > 0) sb.appendLine("   Channels: ${fmt.channelCount}")
                            if (fmt.sampleRate > 0) sb.appendLine("   Sample rate: ${fmt.sampleRate} Hz")
                            if (fmt.codecs != null) sb.appendLine("   Codec: ${fmt.codecs}")
                        }
                    }
                }
                else -> {}
            }
        }

        sb.appendLine()
        sb.appendLine("⏱ Duration: ${formatTime(player.duration)}")
        if (subtitleEnabled) sb.appendLine("📝 Sub offset: ${formatOffset(subtitleOffsetMs)}")

        AlertDialog.Builder(this)
            .setTitle("Video Info")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun cycleResizeMode() {
        currentAspectRatio = (currentAspectRatio + 1) % aspectRatios.size
        applyAspectRatio(showToast = true)
        savePrefs()
    }

    private fun applyAspectRatio(showToast: Boolean = true) {
        val ratio = aspectRatios[currentAspectRatio]
        playerView.findViewById<Button>(R.id.btn_resize)?.text = ratio.name
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val params = playerView.layoutParams as android.widget.FrameLayout.LayoutParams
        params.gravity = android.view.Gravity.CENTER

        when {
            ratio.width == 0 && ratio.height == 0 -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            ratio.width == -1 -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            ratio.width == -2 -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            ratio.width == -3 -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            else -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                val targetRatio = ratio.width.toFloat() / ratio.height.toFloat()
                val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
                if (targetRatio > screenRatio) {
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT
                    params.height = (screenWidth / targetRatio).toInt()
                } else {
                    params.width = (screenHeight * targetRatio).toInt()
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        }
        playerView.layoutParams = params
        playerView.requestLayout()
        if (showToast) Toast.makeText(this, ratio.name, Toast.LENGTH_SHORT).show()
    }

    private fun skipForward() { player.seekTo(player.currentPosition + 15000) }
    private fun skipBackward() { player.seekTo((player.currentPosition - 15000).coerceAtLeast(0)) }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val current = player.playbackParameters.speed
        val selected = values.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 2

        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(speeds, selected) { dialog, index ->
                player.playbackParameters = PlaybackParameters(values[index])
                playerView.findViewById<Button>(R.id.btn_speed)?.text = "Speed: ${speeds[index]}"
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleMainDialog() {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        for (group in player.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language ?: "unknown"
                    val label = format.label ?: lang
                    val forced = if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) " [forced]" else ""
                    val displayName = "📺 $label [$lang]$forced"
                    val trackGroup = group.mediaTrackGroup
                    val trackIndex = i
                    options.add(displayName)
                    actions.add {
                        stopSubtitleOverlay()
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setOverrideForType(TrackSelectionOverride(trackGroup, trackIndex))
                            .build()
                        playerView.subtitleView?.visibility = android.view.View.VISIBLE
                        Toast.makeText(this, "Subtitle: $displayName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val audioGroups = mutableListOf<Pair<String, () -> Unit>>()
        for (group in player.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language ?: "track ${i + 1}"
                    val label = format.label ?: lang
                    val selected = if (group.isTrackSelected(i)) " ✓" else ""
                    val trackGroup = group.mediaTrackGroup
                    val trackIndex = i
                    audioGroups.add("🔊 Audio: $label [$lang]$selected" to {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setOverrideForType(TrackSelectionOverride(trackGroup, trackIndex))
                            .build()
                        Toast.makeText(this, "Audio: $label", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
        if (audioGroups.size > 1) {
            audioGroups.forEach { (name, action) ->
                options.add(name)
                actions.add(action)
            }
        }

        options.add("🔑 OpenSubtitles.com")
        actions.add { checkOSCredentials() }

        options.add("🆓 SubDL (free registration)")
        actions.add { checkSubDLCredentials() }

        options.add("📁 Load from device")
        actions.add {
            savedPosition = player.currentPosition
            wasPlaying = player.isPlaying
            try {
                player.pause()
                pickSubtitle.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open file picker: ${e.message}", Toast.LENGTH_LONG).show()
                if (wasPlaying) player.play()
            }
        }

        if (currentSubtitleFile != null && localVideoFile != null) {
            options.add("💾 Save subtitle to movie folder")
            actions.add { saveSubtitleToMovieFolder() }
        }

        if (currentSubtitleFile != null && currentSubtitleFile!!.exists()) {
            options.add("✏️ Rename subtitle file")
            actions.add { renameSubtitleFile() }
        }

        options.add("🎨 Customize subtitles")
        actions.add { showSubtitleStyleDialog() }

        options.add("🚫 Disable subtitles")
        actions.add {
            stopSubtitleOverlay()
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            playerView.subtitleView?.visibility = android.view.View.GONE
            Toast.makeText(this, "Subtitles disabled", Toast.LENGTH_SHORT).show()
        }

        options.add("💙 Support developer")
        actions.add {
            AlertDialog.Builder(this)
                .setTitle("MakoMiniPlayer")
                .setMessage("This is my first build :D\n\nFree Android TV player with subtitle support.\n\nIf you find this app useful, consider supporting the developer ☕")
                .setPositiveButton("💙 Donate via PayPal") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/makominiplayer")))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Cannot open browser", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle("Subtitles & Audio")
            .setItems(options.toTypedArray()) { _, index -> actions[index].invoke() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleSearchDialog(source: SubSource) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }

        val etQuery = EditText(this).apply {
            hint = "Movie or series name"
            val suggested = if (lastSearchTitle.isNotEmpty()) lastSearchTitle else getQueryFromIntent()
            setText(suggested)
        }

        val etSeason = EditText(this).apply {
            hint = "Season (optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val etEpisode = EditText(this).apply {
            hint = "Episode (optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val languages = listOf(
            "All languages" to "", "Bosnian" to "bs", "Croatian" to "hr",
            "Serbian" to "sr", "Slovenian" to "sl", "English" to "en",
            "German" to "de", "French" to "fr", "Spanish" to "es",
            "Italian" to "it", "Portuguese" to "pt", "Russian" to "ru",
            "Polish" to "pl", "Czech" to "cs", "Slovak" to "sk",
            "Hungarian" to "hu", "Romanian" to "ro", "Bulgarian" to "bg",
            "Greek" to "el", "Turkish" to "tr", "Arabic" to "ar",
            "Dutch" to "nl", "Swedish" to "sv", "Norwegian" to "no",
            "Finnish" to "fi", "Danish" to "da", "Japanese" to "ja",
            "Chinese" to "zh", "Korean" to "ko"
        )

        val selectedLangs = BooleanArray(languages.size) { false }
        selectedLangs[1] = true; selectedLangs[2] = true; selectedLangs[3] = true

        val tvLangSelected = TextView(this).apply {
            text = "Languages: Bosnian, Croatian, Serbian"
            textSize = 12f
            setPadding(0, 8, 0, 4)
        }

        val btnSelectLang = Button(this).apply {
            text = "Select Languages"
            setOnClickListener {
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Select Languages")
                    .setMultiChoiceItems(
                        languages.map { it.first }.toTypedArray(), selectedLangs
                    ) { _, which, isChecked ->
                        selectedLangs[which] = isChecked
                        val selected = languages.filterIndexed { i, _ -> selectedLangs[i] }.map { it.first }
                        tvLangSelected.text = "Languages: ${if (selected.isEmpty()) "All" else selected.joinToString(", ")}"
                    }
                    .setPositiveButton("OK", null).show()
            }
        }

        layout.addView(TextView(this).apply {
            text = if (source == SubSource.OPENSUBTITLES_COM) "OpenSubtitles.com" else "SubDL"
            textSize = 16f; setPadding(0, 0, 0, 10)
        })
        layout.addView(etQuery)
        layout.addView(TextView(this).apply { text = "Season"; setPadding(0, 16, 0, 4) })
        layout.addView(etSeason)
        layout.addView(TextView(this).apply { text = "Episode"; setPadding(0, 16, 0, 4) })
        layout.addView(etEpisode)
        layout.addView(btnSelectLang)
        layout.addView(tvLangSelected)

        AlertDialog.Builder(this)
            .setTitle("Search Subtitles")
            .setView(layout)
            .setPositiveButton("Search") { _, _ ->
                val query = etQuery.text.toString().trim()
                val season = etSeason.text.toString().trim().toIntOrNull()
                val episode = etEpisode.text.toString().trim().toIntOrNull()
                val langCode = languages.filterIndexed { i, _ -> selectedLangs[i] }
                    .mapNotNull { it.second.ifEmpty { null } }.joinToString(",")
                if (query.isNotEmpty()) {
                    currentSearchTitle = query
                    lastSearchTitle = query
                    savePrefs()
                    when (source) {
                        SubSource.OPENSUBTITLES_COM -> loginAndSearch(query, season, episode, langCode)
                        SubSource.SUBDL -> searchSubDL(query, season, episode, langCode)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Settings") { _, _ ->
                when (source) {
                    SubSource.OPENSUBTITLES_COM -> showOSCredentialsDialog { showSubtitleSearchDialog(source) }
                    SubSource.SUBDL -> showSubDLCredentialsDialog { showSubtitleSearchDialog(source) }
                }
            }
            .show()
    }

    private fun getQueryFromIntent(): String {
        val data = intent.data?.toString() ?: return ""
        val file = data.substringAfterLast("/").substringBeforeLast(".")
        return if (file.length > 3 && !file.startsWith("http")) file else ""
    }

    private fun checkOSCredentials() {
        if (osApiKey.isEmpty() || osUsername.isEmpty() || osPassword.isEmpty())
            showOSCredentialsDialog { showSubtitleSearchDialog(SubSource.OPENSUBTITLES_COM) }
        else showSubtitleSearchDialog(SubSource.OPENSUBTITLES_COM)
    }

    private fun showOSCredentialsDialog(onDone: (() -> Unit)? = null) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }
        val etApiKey = EditText(this).apply { hint = "API Key"; setText(osApiKey) }
        val etUser = EditText(this).apply { hint = "Username"; setText(osUsername) }
        val etPass = EditText(this).apply {
            hint = "Password"; setText(osPassword)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(TextView(this).apply { text = "Register free at opensubtitles.com"; textSize = 12f; setPadding(0, 0, 0, 16) })
        layout.addView(etApiKey); layout.addView(etUser); layout.addView(etPass)

        AlertDialog.Builder(this).setTitle("OpenSubtitles.com Settings").setView(layout)
            .setPositiveButton("Save") { _, _ ->
                osApiKey = etApiKey.text.toString().trim()
                osUsername = etUser.text.toString().trim()
                osPassword = etPass.text.toString().trim()
                osToken = ""; savePrefs(); onDone?.invoke()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun loginAndSearch(query: String, season: Int?, episode: Int?, lang: String) {
        Toast.makeText(this, "Logging in...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val body = gson.toJson(mapOf("username" to osUsername, "password" to osPassword))
                val request = Request.Builder()
                    .url("https://api.opensubtitles.com/api/v1/login")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Api-Key", osApiKey).addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "MakoMiniPlayer v1.0").build()

                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: ""
                val map = gson.fromJson<Map<String, Any>>(json, object : TypeToken<Map<String, Any>>() {}.type)
                osToken = map["token"] as? String ?: ""

                withContext(Dispatchers.Main) {
                    if (osToken.isNotEmpty()) searchSubtitlesApi(query, season, episode, lang)
                    else {
                        Toast.makeText(this@PlayerActivity, "Login failed: ${map["message"]}", Toast.LENGTH_LONG).show()
                        showOSCredentialsDialog { loginAndSearch(query, season, episode, lang) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun searchSubtitlesApi(query: String, season: Int?, episode: Int?, lang: String) {
        Toast.makeText(this, "Searching...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                var url = "https://api.opensubtitles.com/api/v1/subtitles?query=${Uri.encode(query)}"
                if (season != null) url += "&season_number=$season"
                if (episode != null) url += "&episode_number=$episode"
                if (lang.isNotEmpty()) url += "&languages=$lang"
                url += "&per_page=20"

                val request = Request.Builder().url(url).get()
                    .addHeader("Api-Key", osApiKey).addHeader("Authorization", "Bearer $osToken")
                    .addHeader("User-Agent", "MakoMiniPlayer v1.0").build()

                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: ""
                val map = gson.fromJson<Map<String, Any>>(json, object : TypeToken<Map<String, Any>>() {}.type)
                val data = map["data"] as? List<Map<String, Any>> ?: emptyList()

                withContext(Dispatchers.Main) {
                    if (data.isEmpty()) Toast.makeText(this@PlayerActivity, "No subtitles found.", Toast.LENGTH_LONG).show()
                    else showApiSubtitleResults(data)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Search error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showApiSubtitleResults(results: List<Map<String, Any>>) {
        val labels = results.map { item ->
            val attrs = item["attributes"] as? Map<*, *> ?: return@map "Unknown"
            val name = attrs["release"] as? String
                ?: (attrs["feature_details"] as? Map<*, *>)?.get("movie_name") as? String ?: "Unknown"
            val lang = attrs["language"] as? String ?: ""
            val downloads = (attrs["download_count"] as? Double)?.toInt() ?: 0
            "$name [$lang] ↓$downloads"
        }

        AlertDialog.Builder(this).setTitle("Select Subtitle")
            .setItems(labels.toTypedArray()) { _, index ->
                val item = results[index]
                val fileId = ((item["attributes"] as? Map<*, *>)?.get("files") as? List<*>)
                    ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("file_id") }
                    ?.let { (it as? Double)?.toInt() }
                if (fileId != null) downloadSubtitleById(fileId)
                else Toast.makeText(this, "Cannot get file.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun downloadSubtitleById(fileId: Int) {
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val body = gson.toJson(mapOf("file_id" to fileId))
                val request = Request.Builder()
                    .url("https://api.opensubtitles.com/api/v1/download")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Api-Key", osApiKey)
                    .addHeader("Authorization", "Bearer $osToken")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", "MakoMiniPlayer v1.0").build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!responseBody.trimStart().startsWith("{")) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PlayerActivity, "Bad response: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val map = gson.fromJson<Map<String, Any>>(responseBody, object : TypeToken<Map<String, Any>>() {}.type)
                val downloadUrl = map["link"] as? String
                val remaining = map["remaining"] as? Double

                if (downloadUrl == null) {
                    withContext(Dispatchers.Main) {
                        val msg = if (remaining != null && remaining <= 0) "Daily download limit reached!"
                        else "No download link in response."
                        Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val dlResponse = client.newCall(
                    Request.Builder().url(downloadUrl)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .addHeader("Accept", "text/plain, application/x-subrip, */*")
                        .build()
                ).execute()
                val dlBytes = dlResponse.body?.bytes() ?: return@launch
                val file = subtitleDownloadFile(currentSearchTitle)

                if (isZip(dlBytes)) {
                    val zip = ZipInputStream(dlBytes.inputStream())
                    var entry = zip.nextEntry
                    var found = false
                    while (entry != null) {
                        if (entry.name.endsWith(".srt", ignoreCase = true) || entry.name.endsWith(".ass", ignoreCase = true)) {
                            FileOutputStream(file).use { out -> zip.copyTo(out) }
                            found = true; break
                        }
                        zip.closeEntry(); entry = zip.nextEntry
                    }
                    zip.close()
                    if (!found) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@PlayerActivity, "No SRT in ZIP.", Toast.LENGTH_LONG).show() }
                        return@launch
                    }
                } else {
                    FileOutputStream(file).use { it.write(dlBytes) }
                }

                withContext(Dispatchers.Main) {
                    if (file.exists() && file.length() > 100) {
                        currentSubtitleFile = file
                        currentSubtitleIsLocal = localVideoFile != null
                        Toast.makeText(this@PlayerActivity, "Downloaded! (${file.length()} bytes)", Toast.LENGTH_SHORT).show()
                        startSubtitleOverlay(file)
                    } else {
                        Toast.makeText(this@PlayerActivity, "File too small: ${file.length()} bytes", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkSubDLCredentials() {
        if (subdlApiKey.isEmpty()) showSubDLCredentialsDialog { showSubtitleSearchDialog(SubSource.SUBDL) }
        else showSubtitleSearchDialog(SubSource.SUBDL)
    }

    private fun showSubDLCredentialsDialog(onDone: (() -> Unit)? = null) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }
        val etApiKey = EditText(this).apply { hint = "SubDL API Key"; setText(subdlApiKey) }
        layout.addView(TextView(this).apply {
            text = "Register free at subdl.com → Profile → API Key"; textSize = 12f; setPadding(0, 0, 0, 16)
        })
        layout.addView(etApiKey)

        AlertDialog.Builder(this).setTitle("SubDL Settings").setView(layout)
            .setPositiveButton("Save") { _, _ ->
                subdlApiKey = etApiKey.text.toString().trim(); savePrefs(); onDone?.invoke()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun searchSubDL(query: String, season: Int?, episode: Int?, lang: String) {
        Toast.makeText(this, "Searching SubDL...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                var url = "https://api.subdl.com/api/v1/subtitles?api_key=$subdlApiKey&film_name=${Uri.encode(query)}"
                if (season != null && episode != null) url += "&type=tv&season_number=$season&episode_number=$episode"
                else url += "&type=movie"
                if (lang.isNotEmpty()) url += "&languages=${lang.uppercase()}"

                val request = Request.Builder().url(url).get()
                    .addHeader("User-Agent", "MakoMiniPlayer/1.0").build()

                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: "{}"
                val map = gson.fromJson<Map<String, Any>>(json, object : TypeToken<Map<String, Any>>() {}.type)
                val results = map["subtitles"] as? List<Map<String, Any>> ?: emptyList()

                withContext(Dispatchers.Main) {
                    if (results.isEmpty()) Toast.makeText(this@PlayerActivity, "No subtitles found.", Toast.LENGTH_LONG).show()
                    else showSubDLResults(results)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Search error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSubDLResults(results: List<Map<String, Any>>) {
        val labels = results.take(30).map { item ->
            val name = item["release_name"] as? String ?: item["name"] as? String ?: "Unknown"
            val lang = item["language"] as? String ?: ""
            "$name [$lang]"
        }

        AlertDialog.Builder(this).setTitle("Select Subtitle")
            .setItems(labels.toTypedArray()) { _, index ->
                val item = results[index]
                val urlPath = item["url"] as? String
                if (urlPath != null) {
                    val fullUrl = if (urlPath.startsWith("http")) urlPath else "https://dl.subdl.com$urlPath"
                    downloadSubtitleFromUrl(fullUrl, false, currentSearchTitle)
                } else Toast.makeText(this, "Cannot get file.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun subtitleDownloadFile(title: String = "subtitle"): File {
        val safeName = title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val makoDir = File(downloadsDir, "MakoMiniPlayer")
        makoDir.mkdirs()
        return File(makoDir, "${safeName}_hr.srt")
    }

    private fun downloadSubtitleFromUrl(url: String, compressed: Boolean, title: String = "subtitle") {
        scope.launch(Dispatchers.IO) {
            try {
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                val bytes = response.body?.bytes() ?: return@launch
                val file = subtitleDownloadFile(title)

                if (isZip(bytes)) {
                    val zip = ZipInputStream(bytes.inputStream())
                    var entry = zip.nextEntry
                    var found = false
                    while (entry != null) {
                        if (entry.name.endsWith(".srt", ignoreCase = true) ||
                            entry.name.endsWith(".ass", ignoreCase = true) ||
                            entry.name.endsWith(".sub", ignoreCase = true)) {
                            FileOutputStream(file).use { out -> zip.copyTo(out) }
                            found = true; break
                        }
                        zip.closeEntry(); entry = zip.nextEntry
                    }
                    zip.close()
                    if (!found) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@PlayerActivity, "No SRT in ZIP.", Toast.LENGTH_LONG).show() }
                        return@launch
                    }
                } else if (compressed) {
                    GZIPInputStream(bytes.inputStream()).use { gzip ->
                        FileOutputStream(file).use { out -> gzip.copyTo(out) }
                    }
                } else {
                    FileOutputStream(file).use { it.write(bytes) }
                }

                withContext(Dispatchers.Main) {
                    if (file.exists() && file.length() > 100) {
                        currentSubtitleFile = file
                        currentSubtitleIsLocal = localVideoFile != null
                        Toast.makeText(this@PlayerActivity, "Downloaded! (${file.length()} bytes)", Toast.LENGTH_SHORT).show()
                        startSubtitleOverlay(file)
                    } else {
                        Toast.makeText(this@PlayerActivity, "File empty!", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun loadSubtitleFromUri(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val directFile = when (uri.scheme) {
                    "file" -> File(uri.path ?: "").takeIf { it.exists() }
                    "content" -> {
                        var result: String? = null
                        try {
                            contentResolver.query(uri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null)
                                ?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val idx = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                                        if (idx >= 0) result = cursor.getString(idx)
                                    }
                                }
                        } catch (e: Exception) { }
                        result?.let { File(it) }?.takeIf { it.exists() }
                    }
                    else -> null
                }

                if (directFile != null) {
                    withContext(Dispatchers.Main) {
                        currentSubtitleFile = directFile
                        currentSubtitleIsLocal = true
                        startSubtitleOverlay(directFile)
                    }
                    return@launch
                }

                val tempFile = File(cacheDir, "subtitle_temp.srt")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    currentSubtitleFile = tempFile
                    currentSubtitleIsLocal = false
                    startSubtitleOverlay(tempFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applySubtitleStyle() {
        try {
            val color = Color.parseColor(subtitleColor)
            subtitleOverlay.setTextColor(color)
            subtitleOverlay.textSize = subtitleTextSize
            subtitleOverlay.setTypeface(null, if (subtitleBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            subtitleOverlay.setBackgroundColor(if (subtitleBackground) Color.argb(180, 0, 0, 0) else Color.TRANSPARENT)
            if (subtitleShadow) subtitleOverlay.setShadowLayer(subtitleShadowRadius, 2f, 2f, Color.BLACK)
            else subtitleOverlay.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            subtitleOverlay.outlineWidth = if (subtitleShadow) subtitleOutlineWidth else 0f
            subtitleOverlay.outlineColor = Color.BLACK
            subtitleOverlay.invalidate()
        } catch (e: Exception) { }
    }

    private fun showSubtitleStyleDialog() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }
        scroll.addView(layout)

        val tvSize = TextView(this).apply { text = "Size: ${subtitleTextSize.toInt()}sp" }
        val seekSize = SeekBar(this).apply {
            max = 30; progress = subtitleTextSize.toInt() - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { subtitleTextSize = (p + 10).toFloat(); tvSize.text = "Size: ${subtitleTextSize.toInt()}sp" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        val tvPosition = TextView(this).apply { text = "Position: ${subtitleBottomMargin}dp from bottom" }
        val seekPosition = SeekBar(this).apply {
            max = 300; progress = subtitleBottomMargin
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { subtitleBottomMargin = p; tvPosition.text = "Position: ${p}dp from bottom"; applySubtitlePosition() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        val chkBold = CheckBox(this).apply { text = "Bold"; isChecked = subtitleBold }
        val chkBackground = CheckBox(this).apply { text = "Dark background"; isChecked = subtitleBackground }
        val chkShadow = CheckBox(this).apply { text = "Shadow / outline"; isChecked = subtitleShadow }

        val tvShadowRadius = TextView(this).apply { text = "Shadow size: ${subtitleShadowRadius.toInt()}" }
        val seekShadowRadius = SeekBar(this).apply {
            max = 20; progress = subtitleShadowRadius.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { subtitleShadowRadius = p.toFloat().coerceAtLeast(1f); tvShadowRadius.text = "Shadow size: $p" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        val tvOutline = TextView(this).apply { text = "Outline width: ${"%.1f".format(subtitleOutlineWidth)}" }
        val seekOutline = SeekBar(this).apply {
            max = 20; progress = (subtitleOutlineWidth * 2).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { subtitleOutlineWidth = p / 2f; tvOutline.text = "Outline width: ${"%.1f".format(subtitleOutlineWidth)}" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        val colors = listOf("White" to "#FFFFFF", "Yellow" to "#FFFF00", "Green" to "#00FF00", "Cyan" to "#00FFFF", "Orange" to "#FFA500")
        val colorSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PlayerActivity, android.R.layout.simple_spinner_dropdown_item, colors.map { it.first })
            setSelection(colors.indexOfFirst { it.second == subtitleColor }.takeIf { it >= 0 } ?: 0)
        }

        val encodings = listOf("UTF-8", "Windows-1250", "Windows-1252", "ISO-8859-1", "ISO-8859-2", "UTF-16")
        val encodingSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PlayerActivity, android.R.layout.simple_spinner_dropdown_item, encodings)
            setSelection(encodings.indexOfFirst { it == subtitleEncoding }.takeIf { it >= 0 } ?: 0)
        }

        layout.addView(tvSize); layout.addView(seekSize)
        layout.addView(TextView(this).apply { text = "Position (black bar support)"; setPadding(0, 16, 0, 4) })
        layout.addView(tvPosition); layout.addView(seekPosition)
        layout.addView(TextView(this).apply { text = "Style"; setPadding(0, 16, 0, 4) })
        layout.addView(chkBold); layout.addView(chkBackground); layout.addView(chkShadow)
        layout.addView(tvShadowRadius); layout.addView(seekShadowRadius)
        layout.addView(tvOutline); layout.addView(seekOutline)
        layout.addView(TextView(this).apply { text = "Color"; setPadding(0, 16, 0, 4) })
        layout.addView(colorSpinner)
        layout.addView(TextView(this).apply { text = "Text encoding"; setPadding(0, 16, 0, 4) })
        layout.addView(encodingSpinner)

        AlertDialog.Builder(this).setTitle("Subtitle Style").setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                subtitleBold = chkBold.isChecked
                subtitleBackground = chkBackground.isChecked
                subtitleShadow = chkShadow.isChecked
                subtitleColor = colors[colorSpinner.selectedItemPosition].second
                subtitleEncoding = encodings[encodingSpinner.selectedItemPosition]
                applySubtitleStyle(); applySubtitlePosition(); savePrefs()
                Toast.makeText(this, "Style applied!", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> { showSubtitleMainDialog(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { skipForward(); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { skipBackward(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_BUTTON_A -> {
                if (player.isPlaying) player.pause() else player.play(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        videoUri?.let { saveVideoPosition(it, player.currentPosition) }
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        subtitleHandler.removeCallbacks(subtitleRunnable)
        player.release()
        scope.cancel()
    }
}