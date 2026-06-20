package com.x3player.glasses.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.google.android.material.button.MaterialButton
import com.x3player.glasses.BuildConfig
import com.x3player.glasses.data.LocalSubtitleCandidate
import com.x3player.glasses.data.LocalSubtitleImportScanner
import com.x3player.glasses.MainActivity
import com.x3player.glasses.PlaybackQueueViewModel
import com.x3player.glasses.R
import com.x3player.glasses.TempleDirection
import com.x3player.glasses.TempleNavigationHandler
import com.x3player.glasses.X3PlayerApplication
import com.x3player.glasses.data.PlaybackProgressStore
import com.x3player.glasses.data.SettingsRepository
import com.x3player.glasses.data.SubtitleRepository
import com.x3player.glasses.data.SubtitleSelectionMode
import com.x3player.glasses.data.VideoItem
import com.x3player.glasses.data.VideoSubtitleState
import com.x3player.glasses.databinding.FragmentPlayerBinding
import com.x3player.glasses.util.formatDuration
import com.x3player.glasses.util.resolveResumePosition
import com.x3player.glasses.util.shouldMarkCompleted
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlayerFragment : Fragment(), TempleNavigationHandler {

    interface Callbacks {
        fun onExitPlayerRequested()
    }

    private enum class MainControl {
        BACK,
        PLAY_PAUSE,
        RESTART,
        STOP,
        PROGRESS,
        CAPTIONS,
        OPTIONS,
    }

    private enum class AdvancedControl {
        BACK,
        SEEK_BACK,
        SEEK_FORWARD,
        PREVIOUS,
        NEXT,
    }

    private enum class SubtitleTrackKind {
        EMBEDDED,
        EXTERNAL,
    }

    private data class SubtitleTrackOption(
        val key: String,
        val group: Tracks.Group,
        val trackIndex: Int,
        val kind: SubtitleTrackKind,
        val externalSubtitleId: Long?,
        val label: String,
        val supported: Boolean,
        val selected: Boolean,
    )

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val playbackQueueViewModel: PlaybackQueueViewModel by activityViewModels()
    private lateinit var playbackProgressStore: PlaybackProgressStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var subtitleRepository: SubtitleRepository
    private lateinit var audioManager: AudioManager

    private var callbacks: Callbacks? = null
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var currentItem: VideoItem? = null
    private var mainSelection: MainControl = MainControl.PLAY_PAUSE
    private var advancedSelection: AdvancedControl = AdvancedControl.BACK
    private var subtitleState: VideoSubtitleState = VideoSubtitleState()
    private var subtitleMenuSelectionIndex = SUBTITLE_NONE_INDEX
    private var pendingSubtitleMenuSelectionKey: String? = null
    private var subtitleTrackOptions: List<SubtitleTrackOption> = emptyList()
    private var subtitleImportCandidates: List<LocalSubtitleCandidate> = emptyList()
    private var subtitleImportSelectionIndex = 0
    private var appliedSubtitleVideoId: Long? = null
    private var appliedExternalSubtitleIds: Set<Long> = emptySet()
    private var hasFatalPlaybackError = false
    private var lastProgressTapAt = 0L
    private var lastSubtitleMenuTapAt = 0L
    private var pendingSubtitleMenuActionIndex: Int? = null

    private var subtitleObserverJob: Job? = null
    private var playbackPreparationJob: Job? = null
    private var subtitleImportLoadJob: Job? = null

    private val subtitleTrackButtons = linkedMapOf<String, MaterialButton>()
    private val subtitleImportButtons = mutableListOf<MaterialButton>()
    private lateinit var subtitleImportScanner: LocalSubtitleImportScanner

    private val uiHandler = Handler(Looper.getMainLooper())
    private val controlsHideRunnable = Runnable { setControlsVisible(false) }
    private val progressForwardRunnable = Runnable { applyDeferredForwardSeek() }
    private val subtitleMenuActionRunnable = Runnable { performPendingSubtitleMenuAction() }
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            updatePositionViews()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            hasFatalPlaybackError = true
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.text = "This file could not be played.\n${error.errorCodeName}"
            setControlsVisible(true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    hasFatalPlaybackError = false
                    binding.errorText.visibility = View.GONE
                    updatePositionViews()
                    updateAudioTrackStatus(player?.currentTracks ?: Tracks.EMPTY)
                }
                Player.STATE_ENDED -> {
                    savePlaybackProgress(forceCompleted = true)
                    setControlsVisible(true)
                }
            }
            updatePlayPauseLabel()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseLabel()
            setControlsVisible(true)
        }

        override fun onTracksChanged(tracks: Tracks) {
            rebuildSubtitleTrackOptions(tracks)
            applyStoredSubtitleSelection()
            updateAudioTrackStatus(tracks)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val appContainer = (requireActivity().application as X3PlayerApplication).appContainer
        playbackProgressStore = appContainer.playbackProgressStore
        settingsRepository = appContainer.settingsRepository
        subtitleRepository = appContainer.subtitleRepository
        subtitleImportScanner = LocalSubtitleImportScanner(context.applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (activity as? MainActivity)?.binocularRenderer?.setMirrorMode(false)

        initializePlayer()
        setupControls()
        observeQueue()
        uiHandler.post(positionUpdateRunnable)
    }

    override fun onResume() {
        super.onResume()
        binding.root.requestFocus()
        (activity as? MainActivity)?.binocularRenderer?.setMirrorMode(false)
        onTempleEnsureFocus()
    }

    override fun onPause() {
        savePlaybackProgress()
        super.onPause()
    }

    override fun onDestroyView() {
        uiHandler.removeCallbacks(positionUpdateRunnable)
        uiHandler.removeCallbacks(controlsHideRunnable)
        uiHandler.removeCallbacks(progressForwardRunnable)
        uiHandler.removeCallbacks(subtitleMenuActionRunnable)
        subtitleObserverJob?.cancel()
        subtitleObserverJob = null
        playbackPreparationJob?.cancel()
        playbackPreparationJob = null
        subtitleImportLoadJob?.cancel()
        subtitleImportLoadJob = null
        savePlaybackProgress()
        releasePlayer()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (activity as? MainActivity)?.binocularRenderer?.setMirrorMode(false)
        subtitleTrackButtons.clear()
        subtitleImportButtons.clear()
        _binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }

    override fun onTempleNavigate(direction: TempleDirection): Boolean {
        if (binding.controlsOverlay.visibility != View.VISIBLE) {
            setControlsVisible(true)
        }

        if (isSubtitleImportVisible()) {
            resetSubtitleMenuTapGesture()
            subtitleImportSelectionIndex = navigateSubtitleImportMenu(subtitleImportSelectionIndex, direction)
        } else if (isSubtitleMenuVisible()) {
            resetSubtitleMenuTapGesture()
            subtitleMenuSelectionIndex = navigateSubtitleMenu(subtitleMenuSelectionIndex, direction)
        } else if (isAdvancedOptionsVisible()) {
            advancedSelection = navigateAdvanced(advancedSelection, direction)
        } else {
            val previousSelection = mainSelection
            mainSelection = navigateMain(mainSelection, direction)
            if (previousSelection != MainControl.PROGRESS || mainSelection != MainControl.PROGRESS) {
                resetProgressTapGesture()
            }
        }
        setControlsVisible(true)
        applySelection()
        return true
    }

    override fun onTempleTap(): Boolean {
        if (binding.controlsOverlay.visibility != View.VISIBLE) {
            setControlsVisible(true)
            return true
        }

        if (isSubtitleImportVisible()) {
            handleSubtitleImportTap()
            return true
        }

        if (isSubtitleMenuVisible()) {
            handleSubtitleMenuTap()
            return true
        }

        if (isAdvancedOptionsVisible()) {
            selectionView(advancedSelection)?.performClick()
        } else {
            if (mainSelection != MainControl.PROGRESS) {
                resetProgressTapGesture()
            }
            selectionView(mainSelection)?.performClick()
        }
        applySelection()
        return true
    }

    override fun onTempleEnsureFocus() {
        if (binding.controlsOverlay.visibility == View.VISIBLE) {
            applySelection()
        }
    }

    override fun onTempleInteractionFinished() {
        if (_binding != null) {
            scheduleControlsAutoHide()
        }
    }

    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(requireContext())
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val exoPlayer = ExoPlayer.Builder(requireContext(), renderersFactory)
            .setSeekBackIncrementMs(PROGRESS_SEEK_MS)
            .setSeekForwardIncrementMs(PROGRESS_SEEK_MS)
            .build()
            .also { it.addListener(playerListener) }
        player = exoPlayer
        mediaSession = MediaSession.Builder(requireContext(), exoPlayer).build()
        binding.playerView.player = exoPlayer
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
    }

    private fun setupControls() {
        binding.root.apply {
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                handleKeyEvent(keyCode, event)
            }
            setOnClickListener {
                when {
                    isSubtitleImportVisible() -> closeSubtitleImportOverlay()
                    isSubtitleMenuVisible() -> closeSubtitleMenu()
                    isAdvancedOptionsVisible() -> closeAdvancedOptions()
                    else -> setControlsVisible(!binding.controlsOverlay.isShown)
                }
            }
        }

        binding.playerView.setOnClickListener {
            when {
                isSubtitleImportVisible() -> closeSubtitleImportOverlay()
                isSubtitleMenuVisible() -> closeSubtitleMenu()
                isAdvancedOptionsVisible() -> closeAdvancedOptions()
                else -> setControlsVisible(!binding.controlsOverlay.isShown)
            }
        }

        binding.backButton.setOnClickListener { callbacks?.onExitPlayerRequested() }
        binding.playPauseButton.setOnClickListener { togglePlayback() }
        binding.restartButton.setOnClickListener { restartCurrentVideo() }
        binding.stopButton.setOnClickListener { stopPlayback() }
        binding.progressContainer.setOnClickListener { handleProgressTap() }
        binding.captionsButton.setOnClickListener { openSubtitleMenu() }
        binding.optionsButton.setOnClickListener { openAdvancedOptions() }
        binding.advancedBackButton.setOnClickListener { closeAdvancedOptions() }
        binding.seekBackButton.setOnClickListener {
            seekBy(-PROGRESS_SEEK_MS)
            setControlsVisible(true)
        }
        binding.seekForwardButton.setOnClickListener {
            seekBy(PROGRESS_SEEK_MS)
            setControlsVisible(true)
        }
        binding.previousButton.setOnClickListener { moveToPrevious() }
        binding.nextButton.setOnClickListener { moveToNext() }
        binding.subtitleUploadButton.setOnClickListener { openSubtitleImportOverlay() }
        binding.subtitleNoneButton.setOnClickListener { selectNoSubtitles() }

        updatePlayPauseLabel()
        updateNavigationButtons()
        renderSubtitleRows()
        renderSubtitleImportRows()
    }

    private fun observeQueue() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    playbackQueueViewModel.currentQueue,
                    playbackQueueViewModel.selectedIndex,
                ) { queue, index -> queue.getOrNull(index) }.collect { item ->
                    if (item == null) {
                        binding.errorText.visibility = View.VISIBLE
                        binding.errorText.text = "No video selected."
                        return@collect
                    }
                    updateNavigationButtons()
                    if (item.id != currentItem?.id) {
                        bindVideo(item)
                    }
                }
            }
        }
    }

    private suspend fun computeStartPosition(item: VideoItem): Long {
        val settings = settingsRepository.settings.first()
        val progress = playbackProgressStore.load(item.id)
        return resolveResumePosition(progress, settings.autoResume)
    }

    private fun bindVideo(item: VideoItem) {
        currentItem = item
        mainSelection = MainControl.PLAY_PAUSE
        resetProgressTapGesture()
        closeAdvancedOptions(notifyFrame = false)
        closeSubtitleImportOverlay(notifyFrame = false)
        closeSubtitleMenu(notifyFrame = false)
        binding.titleText.text = item.displayName
        binding.errorText.visibility = View.GONE

        subtitleState = VideoSubtitleState()
        subtitleTrackOptions = emptyList()
        subtitleImportCandidates = emptyList()
        subtitleImportSelectionIndex = 0
        appliedSubtitleVideoId = null
        appliedExternalSubtitleIds = emptySet()
        pendingSubtitleMenuSelectionKey = null
        hasFatalPlaybackError = false
        binding.playbackMessageText.visibility = View.GONE
        renderSubtitleRows()
        renderSubtitleImportRows()

        subtitleObserverJob?.cancel()
        subtitleObserverJob = viewLifecycleOwner.lifecycleScope.launch {
            subtitleRepository.observeVideoSubtitles(item.id).collect { state ->
                handleSubtitleState(item, state)
            }
        }
    }

    private fun handleSubtitleState(item: VideoItem, state: VideoSubtitleState) {
        if (currentItem?.id != item.id) return

        val currentMenuTrackKey = subtitleTrackForMenuIndex(subtitleMenuSelectionIndex)?.key
        subtitleState = state
        val externalIds = state.subtitles.mapTo(linkedSetOf()) { it.id }

        if (appliedSubtitleVideoId != item.id) {
            preparePlayback(item, state, initialPlayback = true)
        } else if (appliedExternalSubtitleIds != externalIds) {
            preparePlayback(item, state, initialPlayback = false)
        } else {
            applyStoredSubtitleSelection()
            renderSubtitleRows(currentMenuTrackKey)
            (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
        }
    }

    private fun preparePlayback(
        item: VideoItem,
        state: VideoSubtitleState,
        initialPlayback: Boolean,
    ) {
        playbackPreparationJob?.cancel()
        playbackPreparationJob = viewLifecycleOwner.lifecycleScope.launch {
            if (currentItem?.id != item.id) return@launch

            val exoPlayer = player ?: return@launch
            val startPosition = if (initialPlayback) {
                computeStartPosition(item)
            } else {
                exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            val playWhenReady = if (initialPlayback) {
                true
            } else {
                exoPlayer.playWhenReady
            }

            settingsRepository.setLastVideoUri(item.contentUri.toString())
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            exoPlayer.setMediaItem(buildMediaItem(item, state), startPosition)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = playWhenReady

            appliedSubtitleVideoId = item.id
            appliedExternalSubtitleIds = state.subtitles.mapTo(linkedSetOf()) { it.id }
            updatePositionViews()
            setControlsVisible(true)
        }
    }

    private fun buildMediaItem(item: VideoItem, state: VideoSubtitleState): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(item.contentUri)

        if (state.subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                state.subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(subtitle.contentUri)
                        .setMimeType(subtitle.mimeType)
                        .setLabel(subtitle.displayName)
                        .setId(externalSubtitleTrackId(subtitle.id))
                        .build()
                }
            )
        }

        return builder.build()
    }

    private fun moveToPrevious() {
        savePlaybackProgress()
        playbackQueueViewModel.selectPrevious()
        updateNavigationButtons()
    }

    private fun moveToNext() {
        savePlaybackProgress()
        playbackQueueViewModel.selectNext()
        updateNavigationButtons()
    }

    private fun togglePlayback() {
        val exoPlayer = player ?: return
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            val duration = exoPlayer.duration.takeIf { it > 0L } ?: 0L
            val nearEnd = duration > 0L &&
                exoPlayer.currentPosition >= (duration - PLAYBACK_RESTART_THRESHOLD_MS).coerceAtLeast(0L)
            if (exoPlayer.playbackState == Player.STATE_ENDED || nearEnd) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.play()
        }
        setControlsVisible(true)
    }

    private fun restartCurrentVideo() {
        resetProgressTapGesture()
        player?.seekTo(0L)
        player?.playWhenReady = true
        setControlsVisible(true)
    }

    private fun stopPlayback() {
        resetProgressTapGesture()
        player?.pause()
        player?.seekTo(0L)
        updatePositionViews()
        setControlsVisible(true)
    }

    private fun handleProgressTap() {
        val now = SystemClock.elapsedRealtime()
        if (lastProgressTapAt > 0L && now - lastProgressTapAt <= PROGRESS_DOUBLE_TAP_WINDOW_MS) {
            uiHandler.removeCallbacks(progressForwardRunnable)
            seekBy(-PROGRESS_SEEK_MS)
            resetProgressTapGesture()
            setControlsVisible(true)
            return
        }

        lastProgressTapAt = now
        uiHandler.removeCallbacks(progressForwardRunnable)
        uiHandler.postDelayed(progressForwardRunnable, PROGRESS_DOUBLE_TAP_WINDOW_MS)
        setControlsVisible(true)
    }

    private fun handleSubtitleMenuTap() {
        val now = SystemClock.elapsedRealtime()
        if (pendingSubtitleMenuActionIndex != null &&
            now - lastSubtitleMenuTapAt <= SUBTITLE_MENU_DOUBLE_TAP_WINDOW_MS
        ) {
            resetSubtitleMenuTapGesture()
            if (isSubtitleImportVisible()) {
                closeSubtitleImportOverlay()
            } else {
                closeSubtitleMenu()
            }
            return
        }

        pendingSubtitleMenuActionIndex = if (isSubtitleImportVisible()) {
            subtitleImportSelectionIndex
        } else {
            subtitleMenuSelectionIndex
        }
        lastSubtitleMenuTapAt = now
        uiHandler.removeCallbacks(subtitleMenuActionRunnable)
        uiHandler.postDelayed(subtitleMenuActionRunnable, SUBTITLE_MENU_DOUBLE_TAP_WINDOW_MS)
        setControlsVisible(true)
    }

    private fun handleSubtitleImportTap() {
        handleSubtitleMenuTap()
    }

    private fun performPendingSubtitleMenuAction() {
        val actionIndex = pendingSubtitleMenuActionIndex ?: return
        resetSubtitleMenuTapGesture()
        if (isSubtitleImportVisible()) {
            importSubtitleCandidate(actionIndex)
            return
        }

        when (actionIndex) {
            SUBTITLE_UPLOAD_INDEX -> openSubtitleImportOverlay()
            SUBTITLE_NONE_INDEX -> selectNoSubtitles()
            else -> subtitleTrackForMenuIndex(actionIndex)?.let(::selectSubtitleTrack)
        }
    }

    private fun openSubtitleImportOverlay() {
        val item = currentItem ?: return
        clearSubtitleMessage()
        subtitleImportSelectionIndex = 0
        subtitleImportCandidates = emptyList()
        renderSubtitleImportRows()
        binding.subtitleImportMessageText.text = getString(R.string.subtitle_import_subtitle)
        binding.subtitleImportOverlay.visibility = View.VISIBLE
        setControlsVisible(true)

        subtitleImportLoadJob?.cancel()
        subtitleImportLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val candidates = subtitleImportScanner.findCandidatesFor(item)
            subtitleImportCandidates = candidates
            renderSubtitleImportRows()
            binding.subtitleImportMessageText.text = if (candidates.isEmpty()) {
                getString(R.string.subtitle_import_empty)
            } else {
                getString(R.string.subtitle_import_subtitle)
            }
            applySelection()
        }
    }

    private fun importSubtitleCandidate(index: Int) {
        val item = currentItem ?: return
        val candidate = subtitleImportCandidates.getOrNull(index) ?: run {
            setControlsVisible(true)
            return
        }

        lifecycleScope.launch {
            try {
                val uploaded = subtitleRepository.addSubtitle(item.id, candidate.contentUri)
                pendingSubtitleMenuSelectionKey = externalSubtitleTrackId(uploaded.id)
                clearSubtitleMessage()
                closeSubtitleImportOverlay(notifyFrame = false)
            } catch (_: IllegalArgumentException) {
                showSubtitleMessage(getString(R.string.subtitle_upload_unsupported))
            } catch (_: Exception) {
                showSubtitleMessage(getString(R.string.subtitle_upload_failed))
            } finally {
                setControlsVisible(true)
            }
        }
    }

    private fun selectNoSubtitles() {
        val item = currentItem ?: return
        clearSubtitleMessage()
        lifecycleScope.launch {
            subtitleRepository.selectNone(item.id)
        }
        setControlsVisible(true)
    }

    private fun selectSubtitleTrack(option: SubtitleTrackOption) {
        if (!option.supported) return
        val item = currentItem ?: return
        clearSubtitleMessage()
        lifecycleScope.launch {
            when (option.kind) {
                SubtitleTrackKind.EMBEDDED -> subtitleRepository.selectEmbedded(item.id, option.key)
                SubtitleTrackKind.EXTERNAL -> {
                    val externalId = option.externalSubtitleId ?: return@launch
                    subtitleRepository.selectExternal(item.id, externalId)
                }
            }
        }
        setControlsVisible(true)
    }

    private fun applyDeferredForwardSeek() {
        seekBy(PROGRESS_SEEK_MS)
    }

    private fun seekBy(deltaMs: Long): Long {
        val exoPlayer = player ?: return 0L
        val start = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration.takeIf { it > 0L }
        val target = resolveSeekTarget(start, duration, deltaMs)
        exoPlayer.seekTo(target)
        updatePositionViews()
        return target - start
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        setControlsVisible(true)
    }

    private fun updateNavigationButtons() {
        binding.previousButton.isEnabled = playbackQueueViewModel.canMovePrevious()
        binding.nextButton.isEnabled = playbackQueueViewModel.canMoveNext()
    }

    private fun updatePlayPauseLabel() {
        val isPlaying = player?.isPlaying == true
        if (isPlaying) {
            binding.playPauseButton.text = ""
            binding.playPauseButton.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
            binding.playPauseButton.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            binding.playPauseButton.iconPadding = 0
        } else {
            binding.playPauseButton.icon = null
            binding.playPauseButton.text = PLAY_SYMBOL
        }
    }

    private fun updatePositionViews() {
        val exoPlayer = player ?: return
        val currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration.takeIf { it > 0L } ?: 0L
        binding.positionText.text = "${formatDuration(currentPosition)} / ${formatDuration(duration)}"
        binding.playbackProgress.progress = if (duration > 0L) {
            ((currentPosition * PROGRESS_MAX) / duration).toInt()
        } else {
            0
        }
        (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
    }

    private fun savePlaybackProgress(forceCompleted: Boolean = false) {
        val item = currentItem ?: return
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it > 0L } ?: 0L
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val completed = forceCompleted || shouldMarkCompleted(position, duration) || exoPlayer.playbackState == Player.STATE_ENDED
        lifecycleScope.launch {
            playbackProgressStore.save(
                videoId = item.id,
                positionMs = if (completed) duration else position,
                durationMs = duration,
                completed = completed,
                uri = item.contentUri.toString(),
            )
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        if (!visible) {
            closeAdvancedOptions(notifyFrame = false)
            closeSubtitleImportOverlay(notifyFrame = false)
            closeSubtitleMenu(notifyFrame = false)
            resetProgressTapGesture()
        }
        binding.controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        scheduleControlsAutoHide()
        if (visible) {
            applySelection()
        } else {
            binding.root.requestFocus()
        }
        (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
    }

    private fun scheduleControlsAutoHide() {
        uiHandler.removeCallbacks(controlsHideRunnable)
        val shouldAutoHide = shouldAutoHidePlayerControls(
            controlsVisible = binding.controlsOverlay.visibility == View.VISIBLE,
            isPlaying = player?.isPlaying == true,
            hasOpenOverlay = isAdvancedOptionsVisible() ||
                isSubtitleImportVisible() ||
                isSubtitleMenuVisible(),
            hasFatalError = hasFatalPlaybackError,
        )
        if (shouldAutoHide) {
            uiHandler.postDelayed(controlsHideRunnable, CONTROL_HIDE_DELAY_MS)
        }
    }

    private fun openAdvancedOptions() {
        closeSubtitleImportOverlay(notifyFrame = false)
        closeSubtitleMenu(notifyFrame = false)
        advancedSelection = AdvancedControl.BACK
        binding.advancedOptionsOverlay.visibility = View.VISIBLE
        resetProgressTapGesture()
        setControlsVisible(true)
    }

    private fun closeAdvancedOptions(notifyFrame: Boolean = true) {
        if (binding.advancedOptionsOverlay.visibility != View.VISIBLE) return
        binding.advancedOptionsOverlay.visibility = View.GONE
        advancedSelection = AdvancedControl.BACK
        if (binding.controlsOverlay.visibility == View.VISIBLE) {
            applySelection()
            scheduleControlsAutoHide()
        }
        if (notifyFrame) {
            (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
        }
    }

    private fun openSubtitleMenu() {
        closeSubtitleImportOverlay(notifyFrame = false)
        closeAdvancedOptions(notifyFrame = false)
        subtitleMenuSelectionIndex = SUBTITLE_NONE_INDEX
        pendingSubtitleMenuSelectionKey = null
        clearSubtitleMessage()
        binding.subtitleOverlay.visibility = View.VISIBLE
        resetProgressTapGesture()
        resetSubtitleMenuTapGesture()
        setControlsVisible(true)
    }

    private fun closeSubtitleMenu(notifyFrame: Boolean = true) {
        if (binding.subtitleOverlay.visibility != View.VISIBLE) return
        binding.subtitleOverlay.visibility = View.GONE
        subtitleMenuSelectionIndex = SUBTITLE_NONE_INDEX
        pendingSubtitleMenuSelectionKey = null
        clearSubtitleMessage()
        resetSubtitleMenuTapGesture()
        if (binding.controlsOverlay.visibility == View.VISIBLE) {
            applySelection()
            scheduleControlsAutoHide()
        }
        if (notifyFrame) {
            (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
        }
    }

    private fun closeSubtitleImportOverlay(notifyFrame: Boolean = true) {
        if (binding.subtitleImportOverlay.visibility != View.VISIBLE) return
        binding.subtitleImportOverlay.visibility = View.GONE
        subtitleImportLoadJob?.cancel()
        subtitleImportLoadJob = null
        subtitleImportSelectionIndex = 0
        subtitleImportCandidates = emptyList()
        renderSubtitleImportRows()
        resetSubtitleMenuTapGesture()
        if (binding.controlsOverlay.visibility == View.VISIBLE) {
            applySelection()
            scheduleControlsAutoHide()
        }
        if (notifyFrame) {
            (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
        }
    }

    private fun navigateMain(current: MainControl, direction: TempleDirection): MainControl {
        return when (current) {
            MainControl.BACK -> when (direction) {
                TempleDirection.RIGHT -> MainControl.PLAY_PAUSE
                else -> current
            }
            MainControl.PLAY_PAUSE -> when (direction) {
                TempleDirection.LEFT -> MainControl.BACK
                TempleDirection.RIGHT -> MainControl.RESTART
                else -> current
            }
            MainControl.RESTART -> when (direction) {
                TempleDirection.LEFT -> MainControl.PLAY_PAUSE
                TempleDirection.RIGHT -> MainControl.STOP
                else -> current
            }
            MainControl.STOP -> when (direction) {
                TempleDirection.LEFT -> MainControl.RESTART
                TempleDirection.RIGHT -> MainControl.PROGRESS
                else -> current
            }
            MainControl.PROGRESS -> when (direction) {
                TempleDirection.LEFT -> MainControl.STOP
                TempleDirection.RIGHT -> MainControl.CAPTIONS
                else -> current
            }
            MainControl.CAPTIONS -> when (direction) {
                TempleDirection.LEFT -> MainControl.PROGRESS
                TempleDirection.RIGHT -> MainControl.OPTIONS
                else -> current
            }
            MainControl.OPTIONS -> when (direction) {
                TempleDirection.LEFT -> MainControl.CAPTIONS
                else -> current
            }
        }
    }

    private fun navigateAdvanced(current: AdvancedControl, direction: TempleDirection): AdvancedControl {
        return when (current) {
            AdvancedControl.BACK -> when (direction) {
                TempleDirection.DOWN -> AdvancedControl.SEEK_BACK
                else -> current
            }
            AdvancedControl.SEEK_BACK -> when (direction) {
                TempleDirection.UP -> AdvancedControl.BACK
                TempleDirection.DOWN -> AdvancedControl.SEEK_FORWARD
                else -> current
            }
            AdvancedControl.SEEK_FORWARD -> when (direction) {
                TempleDirection.UP -> AdvancedControl.SEEK_BACK
                TempleDirection.DOWN -> AdvancedControl.PREVIOUS
                else -> current
            }
            AdvancedControl.PREVIOUS -> when (direction) {
                TempleDirection.UP -> AdvancedControl.SEEK_FORWARD
                TempleDirection.DOWN -> AdvancedControl.NEXT
                else -> current
            }
            AdvancedControl.NEXT -> when (direction) {
                TempleDirection.UP -> AdvancedControl.PREVIOUS
                else -> current
            }
        }
    }

    private fun navigateSubtitleMenu(currentIndex: Int, direction: TempleDirection): Int {
        val lastIndex = (subtitleMenuItemCount() - 1).coerceAtLeast(SUBTITLE_NONE_INDEX)
        return when (direction) {
            TempleDirection.UP -> (currentIndex - 1).coerceAtLeast(SUBTITLE_UPLOAD_INDEX)
            TempleDirection.DOWN -> (currentIndex + 1).coerceAtMost(lastIndex)
            TempleDirection.LEFT,
            TempleDirection.RIGHT -> currentIndex.coerceIn(SUBTITLE_UPLOAD_INDEX, lastIndex)
        }
    }

    private fun navigateSubtitleImportMenu(currentIndex: Int, direction: TempleDirection): Int {
        val lastIndex = (subtitleImportCandidates.lastIndex).coerceAtLeast(0)
        return when (direction) {
            TempleDirection.UP -> (currentIndex - 1).coerceAtLeast(0)
            TempleDirection.DOWN -> (currentIndex + 1).coerceAtMost(lastIndex)
            TempleDirection.LEFT,
            TempleDirection.RIGHT -> currentIndex.coerceIn(0, lastIndex)
        }
    }

    private fun applySelection() {
        normalizeAdvancedSelection()
        normalizeSubtitleSelection()
        val advancedVisible = isAdvancedOptionsVisible()
        val subtitleImportVisible = isSubtitleImportVisible()
        val subtitleVisible = isSubtitleMenuVisible()
        listOf(
            binding.backButton to (mainSelection == MainControl.BACK && !advancedVisible && !subtitleVisible && !subtitleImportVisible),
            binding.playPauseButton to (mainSelection == MainControl.PLAY_PAUSE && !advancedVisible && !subtitleVisible && !subtitleImportVisible),
            binding.restartButton to (mainSelection == MainControl.RESTART && !advancedVisible && !subtitleVisible && !subtitleImportVisible),
            binding.stopButton to (mainSelection == MainControl.STOP && !advancedVisible && !subtitleVisible && !subtitleImportVisible),
            binding.captionsButton to (mainSelection == MainControl.CAPTIONS && !advancedVisible && !subtitleVisible && !subtitleImportVisible),
            binding.optionsButton to (mainSelection == MainControl.OPTIONS && !advancedVisible && !subtitleVisible && !subtitleImportVisible),
        ).forEach { (button, selected) ->
            styleButton(button, selected)
        }
        styleProgressContainer(mainSelection == MainControl.PROGRESS && !advancedVisible && !subtitleVisible && !subtitleImportVisible)

        listOf(
            binding.advancedBackButton to (advancedSelection == AdvancedControl.BACK && advancedVisible),
            binding.seekBackButton to (advancedSelection == AdvancedControl.SEEK_BACK && advancedVisible),
            binding.seekForwardButton to (advancedSelection == AdvancedControl.SEEK_FORWARD && advancedVisible),
            binding.previousButton to (advancedSelection == AdvancedControl.PREVIOUS && advancedVisible),
            binding.nextButton to (advancedSelection == AdvancedControl.NEXT && advancedVisible),
        ).forEach { (button, selected) ->
            styleButton(button, selected)
        }

        styleButton(binding.subtitleUploadButton, subtitleVisible && subtitleMenuSelectionIndex == SUBTITLE_UPLOAD_INDEX)
        styleButton(binding.subtitleNoneButton, subtitleVisible && subtitleMenuSelectionIndex == SUBTITLE_NONE_INDEX)
        subtitleTrackOptions.forEachIndexed { offset, option ->
            subtitleTrackButtons[option.key]?.let { button ->
                styleButton(button, subtitleVisible && subtitleMenuSelectionIndex == offset + SUBTITLE_FIRST_UPLOADED_INDEX)
            }
        }

        subtitleImportButtons.forEachIndexed { index, button ->
            styleButton(button, subtitleImportVisible && subtitleImportSelectionIndex == index)
        }

        if (subtitleImportVisible) {
            subtitleImportSelectionView()?.requestFocus()
            ensureSubtitleImportSelectionVisible()
        } else if (subtitleVisible) {
            subtitleSelectionView()?.requestFocus()
            ensureSubtitleSelectionVisible()
        } else if (advancedVisible) {
            selectionView(advancedSelection)?.requestFocus()
        } else {
            selectionView(mainSelection)?.requestFocus()
        }
    }

    private fun normalizeAdvancedSelection() {
        advancedSelection = when (advancedSelection) {
            AdvancedControl.PREVIOUS -> {
                if (binding.previousButton.isEnabled) {
                    AdvancedControl.PREVIOUS
                } else if (binding.nextButton.isEnabled) {
                    AdvancedControl.NEXT
                } else {
                    AdvancedControl.BACK
                }
            }
            AdvancedControl.NEXT -> {
                if (binding.nextButton.isEnabled) {
                    AdvancedControl.NEXT
                } else if (binding.previousButton.isEnabled) {
                    AdvancedControl.PREVIOUS
                } else {
                    AdvancedControl.BACK
                }
            }
            AdvancedControl.SEEK_BACK -> AdvancedControl.SEEK_BACK
            AdvancedControl.SEEK_FORWARD -> AdvancedControl.SEEK_FORWARD
            AdvancedControl.BACK -> AdvancedControl.BACK
        }
    }

    private fun normalizeSubtitleSelection() {
        val lastIndex = (subtitleMenuItemCount() - 1).coerceAtLeast(SUBTITLE_NONE_INDEX)
        subtitleMenuSelectionIndex = subtitleMenuSelectionIndex.coerceIn(SUBTITLE_UPLOAD_INDEX, lastIndex)
    }

    private fun rebuildSubtitleTrackOptions(tracks: Tracks) {
        val previousMenuTrackKey = subtitleTrackForMenuIndex(subtitleMenuSelectionIndex)?.key
        var fallbackIndex = 0
        subtitleTrackOptions = tracks.groups
            .asSequence()
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group ->
                (0 until group.length).asSequence().map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    val externalId = format.id
                        ?.takeIf { it.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX) }
                        ?.removePrefix(EXTERNAL_SUBTITLE_TRACK_PREFIX)
                        ?.toLongOrNull()
                    val kind = if (externalId == null) {
                        SubtitleTrackKind.EMBEDDED
                    } else {
                        SubtitleTrackKind.EXTERNAL
                    }
                    val key = externalId?.let(::externalSubtitleTrackId)
                        ?: embeddedSubtitleTrackKey(group, trackIndex, format)
                    fallbackIndex += 1
                    val baseLabel = format.label
                        ?.takeIf { it.isNotBlank() }
                        ?: format.language
                            ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                            ?.uppercase()
                        ?: "Subtitle $fallbackIndex"
                    val sourceLabel = getString(
                        if (kind == SubtitleTrackKind.EMBEDDED) {
                            R.string.subtitle_track_embedded
                        } else {
                            R.string.subtitle_track_external
                        }
                    )
                    val formatLabel = subtitleFormatLabel(format)
                    val supported = group.isTrackSupported(trackIndex)
                    val details = listOfNotNull(sourceLabel, formatLabel)
                        .joinToString(separator = " - ")
                    val label = buildString {
                        append(baseLabel)
                        if (details.isNotBlank()) {
                            append(" - ")
                            append(details)
                        }
                        if (!supported) {
                            append(" - ")
                            append(getString(R.string.subtitle_track_unsupported))
                        }
                    }
                    Log.d(
                        TAG,
                        "Text track key=$key mime=${format.sampleMimeType} codecs=${format.codecs} " +
                            "supported=${group.getTrackSupport(trackIndex)} selected=${group.isTrackSelected(trackIndex)}"
                    )
                    SubtitleTrackOption(
                        key = key,
                        group = group,
                        trackIndex = trackIndex,
                        kind = kind,
                        externalSubtitleId = externalId,
                        label = label,
                        supported = supported,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
            }
            .sortedWith(compareBy<SubtitleTrackOption> { it.kind }.thenBy { it.label.lowercase() })
            .toList()
        renderSubtitleRows(previousMenuTrackKey)
    }

    private fun applyStoredSubtitleSelection() {
        val exoPlayer = player ?: return
        val selection = subtitleState.selection
        val builder = exoPlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)

        when (selection?.mode) {
            null -> {
                val option = subtitleTrackOptions.firstOrNull {
                    val format = it.group.getTrackFormat(it.trackIndex)
                    shouldAutoSelectEmbeddedSubtitle(
                        isEmbedded = it.kind == SubtitleTrackKind.EMBEDDED,
                        isSupported = it.supported,
                        isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    )
                }
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
                if (option != null) {
                    builder.setOverrideForType(
                        TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex)
                    )
                }
            }
            SubtitleSelectionMode.NONE -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            SubtitleSelectionMode.EMBEDDED -> {
                val option = subtitleTrackOptions.firstOrNull {
                    it.kind == SubtitleTrackKind.EMBEDDED &&
                        it.key == selection.embeddedTrackKey &&
                        it.supported
                }
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
                if (option != null) {
                    builder.setOverrideForType(
                        TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex)
                    )
                }
            }
            SubtitleSelectionMode.EXTERNAL -> {
                val option = subtitleTrackOptions.firstOrNull {
                    it.externalSubtitleId == selection.externalSubtitleId && it.supported
                }
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
                if (option != null) {
                    builder.setOverrideForType(
                        TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex)
                    )
                }
            }
        }

        val parameters = builder.build()
        if (parameters != exoPlayer.trackSelectionParameters) {
            exoPlayer.trackSelectionParameters = parameters
        }
    }

    private fun updateAudioTrackStatus(tracks: Tracks) {
        val audioTracks = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group ->
                (0 until group.length).map { trackIndex ->
                    Triple(group, trackIndex, group.getTrackFormat(trackIndex))
                }
            }
        audioTracks.forEach { (group, trackIndex, format) ->
            Log.d(
                TAG,
                "Audio track mime=${format.sampleMimeType} codecs=${format.codecs} " +
                    "supported=${group.getTrackSupport(trackIndex)} selected=${group.isTrackSelected(trackIndex)}"
            )
        }

        if (audioTracks.isEmpty() || audioTracks.any { (group, index, _) ->
                group.isTrackSelected(index) && group.isTrackSupported(index)
            }
        ) {
            clearPlaybackMessage()
            return
        }

        val supportedTrack = audioTracks.firstOrNull { (group, index, _) ->
            group.isTrackSupported(index)
        }
        if (supportedTrack != null) {
            val (group, trackIndex) = supportedTrack
            val exoPlayer = player ?: return
            val parameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                .build()
            if (parameters != exoPlayer.trackSelectionParameters) {
                exoPlayer.trackSelectionParameters = parameters
            }
            clearPlaybackMessage()
            return
        }

        val codecNames = audioTracks
            .map { (_, _, format) -> audioFormatLabel(format) }
            .distinct()
            .joinToString(separator = ", ")
            .ifBlank { "unknown codec" }
        val message = getString(
            if (BuildConfig.FFMPEG_AUDIO_ENABLED) {
                R.string.audio_track_unsupported
            } else {
                R.string.audio_track_unsupported_without_ffmpeg
            },
            codecNames,
        )
        showPlaybackMessage(message)
    }

    private fun renderSubtitleRows(previousMenuTrackKey: String? = null) {
        val noTrackSelected = subtitleTrackOptions.none { it.selected }
        binding.subtitleNoneButton.text = buildSubtitleRowLabel(
            getString(R.string.subtitles_none),
            noTrackSelected,
        )

        binding.uploadedSubtitleListContainer.removeAllViews()
        subtitleTrackButtons.clear()

        subtitleTrackOptions.forEach { option ->
            val button = layoutInflater.inflate(
                R.layout.row_subtitle_option,
                binding.uploadedSubtitleListContainer,
                false,
            ) as MaterialButton
            button.text = buildSubtitleRowLabel(
                option.label,
                option.selected,
            )
            button.isEnabled = option.supported
            button.setOnClickListener { selectSubtitleTrack(option) }
            binding.uploadedSubtitleListContainer.addView(button)
            subtitleTrackButtons[option.key] = button
        }

        subtitleMenuSelectionIndex = resolveSubtitleMenuSelectionIndex(previousMenuTrackKey)

        if (_binding != null && binding.controlsOverlay.visibility == View.VISIBLE) {
            applySelection()
        }
    }

    private fun renderSubtitleImportRows() {
        binding.subtitleImportListContainer.removeAllViews()
        subtitleImportButtons.clear()

        subtitleImportCandidates.forEach { candidate ->
            val button = layoutInflater.inflate(
                R.layout.row_subtitle_import_option,
                binding.subtitleImportListContainer,
                false,
            ) as MaterialButton
            button.text = buildSubtitleImportRowLabel(candidate)
            button.setOnClickListener {
                val index = subtitleImportButtons.indexOf(button)
                if (index >= 0) {
                    subtitleImportSelectionIndex = index
                    importSubtitleCandidate(index)
                }
            }
            binding.subtitleImportListContainer.addView(button)
            subtitleImportButtons += button
        }

        subtitleImportSelectionIndex = subtitleImportSelectionIndex.coerceIn(
            0,
            (subtitleImportButtons.lastIndex).coerceAtLeast(0),
        )

        if (_binding != null && binding.controlsOverlay.visibility == View.VISIBLE) {
            applySelection()
        }
    }

    private fun resolveSubtitleMenuSelectionIndex(previousMenuTrackKey: String?): Int {
        pendingSubtitleMenuSelectionKey?.let { preferredKey ->
            indexOfSubtitleTrack(preferredKey)?.let { preferredIndex ->
                pendingSubtitleMenuSelectionKey = null
                return preferredIndex
            }
            pendingSubtitleMenuSelectionKey = null
        }

        previousMenuTrackKey?.let { trackKey ->
            indexOfSubtitleTrack(trackKey)?.let { return it }
        }

        val lastIndex = (subtitleMenuItemCount() - 1).coerceAtLeast(SUBTITLE_NONE_INDEX)
        return subtitleMenuSelectionIndex.coerceIn(SUBTITLE_UPLOAD_INDEX, lastIndex)
    }

    private fun indexOfSubtitleTrack(trackKey: String): Int? {
        val subtitleIndex = subtitleTrackOptions.indexOfFirst { it.key == trackKey }
        return if (subtitleIndex >= 0) {
            subtitleIndex + SUBTITLE_FIRST_UPLOADED_INDEX
        } else {
            null
        }
    }

    private fun subtitleMenuItemCount(): Int = SUBTITLE_FIRST_UPLOADED_INDEX + subtitleTrackOptions.size

    private fun subtitleTrackForMenuIndex(index: Int): SubtitleTrackOption? {
        val subtitleListIndex = index - SUBTITLE_FIRST_UPLOADED_INDEX
        return subtitleTrackOptions.getOrNull(subtitleListIndex)
    }

    private fun buildSubtitleRowLabel(baseLabel: String, isCurrent: Boolean): String {
        return if (isCurrent) {
            baseLabel + getString(R.string.subtitles_current_suffix)
        } else {
            baseLabel
        }
    }

    private fun subtitleFormatLabel(format: Format): String? {
        val sourceMimeType = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
            format.codecs
        } else {
            format.sampleMimeType
        }
        return when (sourceMimeType) {
            "application/x-subrip" -> "SRT"
            "text/vtt" -> "WebVTT"
            "text/x-ssa" -> "SSA/ASS"
            "application/ttml+xml" -> "TTML"
            "application/vobsub" -> "VobSub"
            "application/pgs" -> "PGS"
            "application/dvbsubs" -> "DVB"
            else -> sourceMimeType?.substringAfter('/')
        }
    }

    private fun audioFormatLabel(format: Format): String {
        return when (format.sampleMimeType) {
            "audio/ac3" -> "AC-3"
            "audio/eac3" -> "E-AC-3"
            "audio/eac3-joc" -> "E-AC-3 JOC"
            "audio/vnd.dts" -> "DTS"
            "audio/vnd.dts.hd" -> "DTS-HD"
            "audio/true-hd" -> "TrueHD"
            "audio/opus" -> "Opus"
            "audio/flac" -> "FLAC"
            "audio/mp4a-latm" -> "AAC"
            else -> format.codecs
                ?.takeIf { it.isNotBlank() }
                ?: format.sampleMimeType
                    ?.substringAfter('/')
                ?: "unknown codec"
        }
    }

    private fun embeddedSubtitleTrackKey(
        group: Tracks.Group,
        trackIndex: Int,
        format: Format,
    ): String {
        return listOf(
            EMBEDDED_SUBTITLE_TRACK_PREFIX,
            group.mediaTrackGroup.id,
            trackIndex.toString(),
            format.id.orEmpty(),
            format.label.orEmpty(),
            format.language.orEmpty(),
            format.sampleMimeType.orEmpty(),
            format.codecs.orEmpty(),
        ).joinToString(separator = "|")
    }

    private fun externalSubtitleTrackId(subtitleId: Long): String {
        return "$EXTERNAL_SUBTITLE_TRACK_PREFIX$subtitleId"
    }

    private fun showPlaybackMessage(message: String) {
        binding.playbackMessageText.text = message
        binding.playbackMessageText.visibility = View.VISIBLE
    }

    private fun clearPlaybackMessage() {
        binding.playbackMessageText.text = ""
        binding.playbackMessageText.visibility = View.GONE
    }

    private fun buildSubtitleImportRowLabel(candidate: LocalSubtitleCandidate): String {
        val relativePath = candidate.relativePath
            ?.trim()
            ?.removeSuffix("/")
            .orEmpty()
        val displayName = insertSoftBreaks(candidate.displayName)
        return if (relativePath.isBlank()) {
            displayName
        } else {
            "$displayName\n${insertSoftBreaks(relativePath)}"
        }
    }

    private fun insertSoftBreaks(value: String): String {
        return value
            .replace("_", "_\u200B")
            .replace("-", "-\u200B")
            .replace(".", ".\u200B")
            .replace("/", "/\u200B")
    }

    private fun ensureSubtitleSelectionVisible() {
        val selectedView = subtitleSelectionView() ?: return
        if (selectedView === binding.subtitleUploadButton) return

        binding.subtitleListScrollView.post {
            val targetScrollY = (selectedView.top - binding.subtitleListScrollView.height / 3).coerceAtLeast(0)
            binding.subtitleListScrollView.scrollTo(0, targetScrollY)
        }
    }

    private fun ensureSubtitleImportSelectionVisible() {
        val selectedView = subtitleImportSelectionView() ?: return
        binding.subtitleImportScrollView.post {
            val targetScrollY = (selectedView.top - binding.subtitleImportScrollView.height / 3).coerceAtLeast(0)
            binding.subtitleImportScrollView.scrollTo(0, targetScrollY)
        }
    }

    private fun showSubtitleMessage(message: String) {
        binding.subtitleMessageText.text = message
        binding.subtitleMessageText.visibility = View.VISIBLE
    }

    private fun clearSubtitleMessage() {
        binding.subtitleMessageText.text = ""
        binding.subtitleMessageText.visibility = View.GONE
    }

    private fun styleButton(button: MaterialButton, selected: Boolean) {
        val backgroundColor = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.accent_cyan else R.color.panel_blue,
        )
        val strokeColor = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.accent_green else R.color.text_secondary,
        )
        val textColor = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.surface_black else R.color.text_primary,
        )
        button.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        button.strokeWidth = if (selected) 4 else 2
        button.strokeColor = ColorStateList.valueOf(strokeColor)
        button.setTextColor(textColor)
        button.iconTint = ColorStateList.valueOf(textColor)
        button.scaleX = if (selected) 1.06f else 1f
        button.scaleY = if (selected) 1.06f else 1f
        button.alpha = if (button.isEnabled) 1f else 0.45f
        button.isActivated = selected
    }

    private fun styleProgressContainer(selected: Boolean) {
        binding.progressContainer.isActivated = selected
        binding.progressContainer.scaleX = if (selected) 1.03f else 1f
        binding.progressContainer.scaleY = if (selected) 1.03f else 1f
        binding.positionText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selected) R.color.accent_cyan else R.color.text_primary,
            )
        )
        binding.playbackProgress.alpha = if (selected) 1f else 0.9f
    }

    private fun selectionView(selection: MainControl): View? {
        return when (selection) {
            MainControl.BACK -> binding.backButton
            MainControl.PLAY_PAUSE -> binding.playPauseButton
            MainControl.RESTART -> binding.restartButton
            MainControl.STOP -> binding.stopButton
            MainControl.PROGRESS -> binding.progressContainer
            MainControl.CAPTIONS -> binding.captionsButton
            MainControl.OPTIONS -> binding.optionsButton
        }
    }

    private fun selectionView(selection: AdvancedControl): View? {
        return when (selection) {
            AdvancedControl.BACK -> binding.advancedBackButton
            AdvancedControl.SEEK_BACK -> binding.seekBackButton
            AdvancedControl.SEEK_FORWARD -> binding.seekForwardButton
            AdvancedControl.PREVIOUS -> binding.previousButton
            AdvancedControl.NEXT -> binding.nextButton
        }
    }

    private fun subtitleSelectionView(): View? {
        return when (subtitleMenuSelectionIndex) {
            SUBTITLE_UPLOAD_INDEX -> binding.subtitleUploadButton
            SUBTITLE_NONE_INDEX -> binding.subtitleNoneButton
            else -> subtitleTrackForMenuIndex(subtitleMenuSelectionIndex)
                ?.key
                ?.let { subtitleTrackButtons[it] }
        }
    }

    private fun subtitleImportSelectionView(): View? {
        return subtitleImportButtons.getOrNull(subtitleImportSelectionIndex)
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                onTempleTap()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onTempleNavigate(TempleDirection.LEFT)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onTempleNavigate(TempleDirection.RIGHT)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                onTempleNavigate(TempleDirection.UP)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                onTempleNavigate(TempleDirection.DOWN)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-PROGRESS_SEEK_MS)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(PROGRESS_SEEK_MS)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                moveToPrevious()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                moveToNext()
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isSubtitleImportVisible()) {
                    closeSubtitleImportOverlay()
                } else if (isSubtitleMenuVisible()) {
                    closeSubtitleMenu()
                } else if (isAdvancedOptionsVisible()) {
                    closeAdvancedOptions()
                } else {
                    callbacks?.onExitPlayerRequested()
                }
                true
            }
            else -> false
        }
    }

    private fun resetProgressTapGesture() {
        uiHandler.removeCallbacks(progressForwardRunnable)
        lastProgressTapAt = 0L
    }

    private fun resetSubtitleMenuTapGesture() {
        uiHandler.removeCallbacks(subtitleMenuActionRunnable)
        lastSubtitleMenuTapAt = 0L
        pendingSubtitleMenuActionIndex = null
    }

    private fun isAdvancedOptionsVisible(): Boolean = binding.advancedOptionsOverlay.visibility == View.VISIBLE

    private fun isSubtitleImportVisible(): Boolean = binding.subtitleImportOverlay.visibility == View.VISIBLE

    private fun isSubtitleMenuVisible(): Boolean = binding.subtitleOverlay.visibility == View.VISIBLE

    companion object {
        private const val TAG = "X3Player"
        private const val CONTROL_HIDE_DELAY_MS = 3000L
        private const val PLAYBACK_RESTART_THRESHOLD_MS = 1000L
        private const val PROGRESS_DOUBLE_TAP_WINDOW_MS = 350L
        private const val SUBTITLE_MENU_DOUBLE_TAP_WINDOW_MS = 350L
        private const val PROGRESS_SEEK_MS = 10_000L
        private const val PROGRESS_MAX = 1000L
        private const val PLAY_SYMBOL = "\u25B6"
        private const val SUBTITLE_UPLOAD_INDEX = 0
        private const val SUBTITLE_NONE_INDEX = 1
        private const val SUBTITLE_FIRST_UPLOADED_INDEX = 2
        private const val EXTERNAL_SUBTITLE_TRACK_PREFIX = "external:"
        private const val EMBEDDED_SUBTITLE_TRACK_PREFIX = "embedded"

        fun newInstance(): PlayerFragment = PlayerFragment()
    }
}
