package com.echoreading.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.echoreading.R
import com.echoreading.MainActivity
import com.echoreading.voice.OfflineVoice
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class ReaderPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timeline = AudioTimeline()
    private var chunks = emptyList<ReadingChunk>()
    private var generation: Job? = null
    private var audioDir: File? = null
    private var completed = false
    private var wantsPlayback = false
    private var pendingPosition: Long? = null
    @Volatile private var generationId = 0

    override fun onCreate() {
        super.onCreate()
        ReaderState.restore(this)
        val audioParent = File(cacheDir, "reading-audio")
        if (audioParent.isDirectory) {
            audioParent.listFiles()?.forEach { dir ->
                if (dir.isDirectory && !ReaderAudioCache.isCachedDir(dir)) {
                    dir.deleteRecursively()
                }
            }
        }
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) updateStatus(ReadingStatus.PLAYING)
                    else if (ReaderState.snapshot.value.status == ReadingStatus.PLAYING) updateStatus(ReadingStatus.PAUSED)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (pendingPosition == null) wantsPlayback = playWhenReady
                    if (!playWhenReady && timeline.size > 0) {
                        updatePosition()
                        ReaderState.save(this@ReaderPlaybackService)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updatePosition()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        if (completed) stopReading(atEnd = true)
                        else updateStatus(ReadingStatus.PREPARING)
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "Player error encountered during playback", error)
                    failReading(generationId)
                }
            })
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.reading)
                .build(),
        )
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builtSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(Commands())
            .setMediaButtonPreferences(mediaButtons)
            .build()
        session = builtSession
        addSession(builtSession)
    }

    private val mediaButtons by lazy {
        listOf(
            button(CommandButton.ICON_SKIP_BACK_10, ACTION_BACK, getString(R.string.rewind_ten), CommandButton.SLOT_BACK),
            button(CommandButton.ICON_SKIP_FORWARD_10, ACTION_FORWARD, getString(R.string.forward_ten), CommandButton.SLOT_FORWARD),
            button(CommandButton.ICON_SKIP_BACK, ACTION_RESET, getString(R.string.reset_reading), CommandButton.SLOT_BACK_SECONDARY),
            button(CommandButton.ICON_STOP, ACTION_STOP, getString(R.string.stop_reading), CommandButton.SLOT_FORWARD_SECONDARY),
        )
    }

    private fun button(icon: Int, action: String, name: String, slot: Int) =
        CommandButton.Builder(icon)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .setDisplayName(name)
            .setSlots(slot)
            .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ((intent?.action == ACTION_READ || intent?.action == ACTION_PLAY) && player.mediaItemCount == 0) {
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { ReaderState.snapshot.value.text }
            startPreparingForeground(formatTitle(text, getString(R.string.app_name)))
        }
        when (intent?.action) {
            ACTION_READ -> startReading(
                intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                intent.getLongExtra(EXTRA_POSITION, 0),
                intent.getStringExtra(EXTRA_VOICE) ?: ReaderState.snapshot.value.voiceId,
                intent.getFloatExtra(EXTRA_SPEED, ReaderState.snapshot.value.speed),
            )
            ACTION_PLAY -> playReading()
            ACTION_PAUSE -> {
                wantsPlayback = false
                player.pause()
                updatePosition()
                updateStatus(ReadingStatus.PAUSED)
                ReaderState.save(this)
            }
            ACTION_BACK -> moveBy(-10_000)
            ACTION_FORWARD -> moveBy(10_000)
            ACTION_RESET -> resetReading()
            ACTION_STOP -> stopReading()
            ACTION_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, ReaderState.snapshot.value.speed)
                ReaderState.snapshot.value = ReaderState.snapshot.value.copy(speed = speed)
                ReaderState.save(this)
                val currentText = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { ReaderState.snapshot.value.text }
                if (currentText.isNotBlank() && (ReaderState.snapshot.value.status == ReadingStatus.PLAYING || ReaderState.snapshot.value.status == ReadingStatus.PREPARING)) {
                    val pos = intent.getLongExtra(EXTRA_POSITION, ReaderState.snapshot.value.positionMs)
                    startReading(currentText, pos, ReaderState.snapshot.value.voiceId, speed)
                }
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun startPreparingForeground(title: String = getString(R.string.app_name)) {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.reading), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speak)
            .setContentTitle(title)
            .setContentText(getString(R.string.preparing_audio))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (_: Exception) {
        }
    }

    private fun startReading(text: String, positionMs: Long, voiceId: String, speed: Float) {
        if (text.isBlank()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val markdown = MarkdownText.parse(text)
        if (markdown.spokenText.isBlank()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)?.cancel(STATUS_NOTIFICATION_ID)
        val saveHistory = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("save_history", true)
        if (saveHistory) {
            scope.launch { ReadingHistory.add(this@ReaderPlaybackService, text) }
        }
        val cached = ReaderAudioCache.get(text, voiceId, speed)
        if (cached != null) {
            playFromCache(cached, positionMs)
            return
        }
        ReaderAudioCache.clear()

        val previous = generation
        val previousDir = audioDir
        generationId++
        val id = generationId
        previous?.cancel()
        scope.launch(Dispatchers.IO) {
            previous?.join()
            if (previousDir != null && !ReaderAudioCache.isCachedDir(previousDir)) {
                previousDir.deleteRecursively()
            }
        }
        player.stop()
        player.clearMediaItems()
        timeline.clear()
        player.playlistMetadata = buildMediaMetadata(text, voiceId)
        chunks = markdown.readingChunks()
        completed = false
        wantsPlayback = true
        pendingPosition = positionMs.takeIf { it > 0 }
        audioDir = File(cacheDir, "reading-audio/$id").apply { mkdirs() }
        ReaderState.snapshot.value = ReaderSnapshot(
            text = text,
            status = ReadingStatus.PREPARING,
            positionMs = positionMs,
            durationMs = 0,
            voiceId = voiceId,
            speed = speed,
        )
        ReaderState.save(this)
        val destination = audioDir ?: return
        generation = scope.launch(Dispatchers.IO) {
            try {
                var targetVoice = OfflineVoice.option(this@ReaderPlaybackService, voiceId)
                if (!OfflineVoice.isInstalled(this@ReaderPlaybackService, targetVoice)) {
                    if (targetVoice.url != null) {
                        try {
                            OfflineVoice.install(this@ReaderPlaybackService, targetVoice) { _, _ -> }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to download voice ${targetVoice.id}, checking fallback", e)
                        }
                    }
                    if (!isActive || generationId != id) return@launch
                    if (!OfflineVoice.isInstalled(this@ReaderPlaybackService, targetVoice)) {
                        val installedVoices = OfflineVoice.allVoices(this@ReaderPlaybackService)
                            .filter { OfflineVoice.isInstalled(this@ReaderPlaybackService, it) }
                        val fallback = installedVoices.firstOrNull {
                            val targetLang = targetVoice.languageCode?.take(2) ?: targetVoice.id.take(2)
                            val itLang = it.languageCode?.take(2) ?: it.id.take(2)
                            targetLang.equals(itLang, ignoreCase = true)
                        } ?: installedVoices.firstOrNull()
                        if (fallback != null) {
                            targetVoice = fallback
                            withContext(Dispatchers.Main) {
                                ReaderState.snapshot.value = ReaderState.snapshot.value.copy(voiceId = fallback.id)
                                ReaderState.save(this@ReaderPlaybackService)
                            }
                        } else {
                            throw IllegalStateException(getString(R.string.download_failed))
                        }
                    }
                }

                chunks.forEachIndexed { index, chunk ->
                    if (!isActive || generationId != id) return@launch
                    // ponytail: native generation cannot stop mid-chunk; discard it after a stop.
                    val audio = OfflineVoice.synthesize(this@ReaderPlaybackService, chunk.text, targetVoice.id, speed)
                    if (!isActive || generationId != id) return@launch
                    val file = File(destination, "$index.wav")
                    val duration = WavFiles.write(file, audio)
                    withContext(Dispatchers.Main) { addAudio(id, index, file, duration, text, targetVoice.id) }
                }
                withContext(Dispatchers.Main) {
                    if (generationId != id) return@withContext
                    completed = true
                    if (pendingPosition != null) {
                        pendingPosition = null
                        seekGlobal(timeline.preparedMs)
                        if (wantsPlayback) stopReading(atEnd = true)
                    }
                    if (player.playbackState == Player.STATE_ENDED) stopReading(atEnd = true)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Throwable) {
                Log.e(TAG, "Audio synthesis failed for chunk in generation $id", e)
                withContext(Dispatchers.Main) { failReading(id, e.message) }
            }
        }
    }

    private fun playFromCache(cached: CachedReading, positionMs: Long) {
        val previous = generation
        val previousDir = audioDir
        generationId++
        val id = generationId
        previous?.cancel()
        generation = null
        if (previousDir != null && previousDir != cached.audioDir && !ReaderAudioCache.isCachedDir(previousDir)) {
            scope.launch(Dispatchers.IO) {
                previous?.join()
                previousDir.deleteRecursively()
            }
        }
        player.stop()
        player.clearMediaItems()
        timeline.clear()
        player.playlistMetadata = buildMediaMetadata(cached.text, cached.voiceId)
        chunks = cached.chunks
        audioDir = cached.audioDir
        completed = true
        wantsPlayback = true

        val mediaItems = cached.durations.mapIndexed { index, duration ->
            timeline.add(duration)
            val file = File(cached.audioDir, "$index.wav")
            MediaItem.Builder()
                .setMediaId(index.toString())
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(buildMediaMetadata(cached.text, cached.voiceId))
                .build()
        }
        player.addMediaItems(mediaItems)

        val targetPosition = positionMs.coerceIn(0, timeline.preparedMs)
        pendingPosition = null
        val (initialItem, initialLocal) = if (targetPosition > 0) {
            timeline.locate(targetPosition)
        } else {
            0 to 0L
        }
        player.seekTo(initialItem, initialLocal)

        ReaderState.snapshot.value = ReaderSnapshot(
            text = cached.text,
            status = ReadingStatus.PLAYING,
            positionMs = targetPosition,
            durationMs = timeline.preparedMs,
            characterOffset = chunks.getOrNull(initialItem)?.start ?: 0,
            voiceId = cached.voiceId,
            speed = cached.speed,
        )
        ReaderState.save(this)

        player.prepare()
        if (wantsPlayback) player.play()
    }

    private fun failReading(id: Int, errorMessage: String? = null) {
        if (generationId != id) return
        generationId++
        generation?.cancel()
        generation = null
        wantsPlayback = false
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            status = ReadingStatus.ERROR,
            error = errorMessage ?: getString(R.string.reading_error),
        )
        player.stop()
        player.clearMediaItems()
        ReaderState.save(this)
        showStatusNotification(
            getString(R.string.reading_error_title),
            errorMessage ?: getString(R.string.reading_error),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun addAudio(id: Int, index: Int, file: File, durationMs: Long, text: String, voiceId: String) {
        if (generationId != id) return
        timeline.add(durationMs)
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(durationMs = timeline.preparedMs)
        player.addMediaItem(
            MediaItem.Builder()
                .setMediaId(index.toString())
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(buildMediaMetadata(text, voiceId))
                .build(),
        )
        val requested = pendingPosition
        if (requested != null) {
            if (timeline.preparedMs < requested && !completed) return
            pendingPosition = null
            seekGlobal(requested.coerceAtMost(timeline.preparedMs))
        } else if (index == 0 || player.playbackState == Player.STATE_ENDED) {
            player.seekTo(index, 0)
        }
        player.prepare()
        if (wantsPlayback) player.play()
    }

    private fun playReading() {
        getSystemService(NotificationManager::class.java)?.cancel(STATUS_NOTIFICATION_ID)
        if (timeline.size == 0) {
            val saved = ReaderState.snapshot.value
            if (saved.text.isNotBlank() && generation == null) {
                startReading(saved.text, saved.positionMs, saved.voiceId, saved.speed)
            } else if (generation != null) wantsPlayback = true
            else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        wantsPlayback = true
        if (pendingPosition == null) player.play()
    }

    private fun moveBy(deltaMs: Long) {
        if (timeline.size == 0) {
            if (deltaMs > 0 && generation != null) pendingPosition = (pendingPosition ?: 0) + deltaMs
            return
        }
        val current = pendingPosition ?: currentPosition()
        val target = (current + deltaMs).coerceAtLeast(0)
        if (target > timeline.preparedMs && !completed) {
            pendingPosition = target
            wantsPlayback = player.isPlaying || wantsPlayback
            player.pause()
            updateStatus(ReadingStatus.PREPARING)
        } else {
            pendingPosition = null
            seekGlobal(target.coerceAtMost(timeline.preparedMs))
        }
    }

    private fun resetReading() {
        getSystemService(NotificationManager::class.java)?.cancel(STATUS_NOTIFICATION_ID)
        wantsPlayback = false
        pendingPosition = null
        player.pause()
        if (timeline.size > 0) seekGlobal(0)
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            status = ReadingStatus.PAUSED,
            positionMs = 0,
            characterOffset = 0,
        )
        ReaderState.save(this)
    }

    private fun seekGlobal(positionMs: Long) {
        if (timeline.size == 0) return
        val (item, local) = timeline.locate(positionMs)
        player.seekTo(item, local)
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            positionMs = positionMs,
            characterOffset = chunks.getOrNull(item)?.start ?: 0,
        )
    }

    private fun currentPosition(): Long =
        if (timeline.size == 0) ReaderState.snapshot.value.positionMs
        else timeline.globalPosition(player.currentMediaItemIndex, player.currentPosition)

    private fun updatePosition() {
        if (timeline.size == 0) return
        val index = player.currentMediaItemIndex
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            positionMs = currentPosition(),
            characterOffset = chunks.getOrNull(index)?.start ?: 0,
        )
    }

    private fun updateStatus(status: ReadingStatus) {
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(status = status, error = null)
    }

    private fun stopReading(atEnd: Boolean = false) {
        val position = if (atEnd) 0L else currentPosition()
        val currentSnapshot = ReaderState.snapshot.value
        val dir = audioDir
        if (atEnd && completed && dir != null && chunks.isNotEmpty() && timeline.size == chunks.size) {
            ReaderAudioCache.put(
                text = currentSnapshot.text,
                voiceId = currentSnapshot.voiceId,
                speed = currentSnapshot.speed,
                chunks = chunks,
                durations = timeline.durations(),
                audioDir = dir,
            )
        }
        if (atEnd) {
            showStatusNotification(
                getString(R.string.reading_complete_title),
                getString(R.string.reading_complete_body),
            )
        }
        generationId++
        generation?.cancel()
        generation = null
        player.stop()
        player.clearMediaItems()
        timeline.clear()
        wantsPlayback = false
        pendingPosition = null
        ReaderState.snapshot.value = currentSnapshot.copy(
            status = ReadingStatus.IDLE,
            positionMs = position,
            characterOffset = if (atEnd) 0 else currentSnapshot.characterOffset,
        )
        ReaderState.save(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ReaderState.save(this)
        generationId++
        scope.cancel()
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        player.release()
        super.onDestroy()
    }

    private inner class Commands : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_BACK, Bundle.EMPTY))
                .add(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(ACTION_STOP, Bundle.EMPTY))
                .add(SessionCommand(ACTION_RESET, Bundle.EMPTY))
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(mediaButtons)
                .build()
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            super.onPostConnect(session, controller)
            session.setCustomLayout(controller, mediaButtons)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_BACK -> moveBy(-10_000)
                ACTION_FORWARD -> moveBy(10_000)
                ACTION_STOP -> stopReading()
                ACTION_RESET -> resetReading()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun buildMediaMetadata(
        text: String = ReaderState.snapshot.value.text,
        voiceId: String = ReaderState.snapshot.value.voiceId,
    ): MediaMetadata {
        val title = formatTitle(text, getString(R.string.app_name))
        val voiceLabel = OfflineVoice.option(this, voiceId).label
        return MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(voiceLabel)
            .build()
    }

    private fun showStatusNotification(title: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        notifications.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL_ID,
                getString(R.string.reading_status_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speak)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            notifications.notify(STATUS_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val TAG = "ReaderPlaybackService"
        const val NOTIFICATION_ID = 1101
        const val STATUS_NOTIFICATION_ID = 1102
        const val CHANNEL_ID = "eco-reading-playback"
        const val STATUS_CHANNEL_ID = "eco-reading-status"
        const val EXTRA_TEXT = "text"
        const val EXTRA_POSITION = "position_ms"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SPEED = "speed"
        const val ACTION_READ = "com.echoreading.READ"
        const val ACTION_PLAY = "com.echoreading.PLAY"
        const val ACTION_PAUSE = "com.echoreading.PAUSE"
        const val ACTION_BACK = "com.echoreading.BACK_10"
        const val ACTION_FORWARD = "com.echoreading.FORWARD_10"
        const val ACTION_STOP = "com.echoreading.STOP"
        const val ACTION_RESET = "com.echoreading.RESET"
        const val ACTION_SPEED = "com.echoreading.SPEED"

        fun formatTitle(text: String, fallback: String): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            val preview = if (clean.length > 80) clean.take(80) + "…" else clean
            return preview.ifBlank { fallback }
        }
    }
}
