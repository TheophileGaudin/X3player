package com.x3player.glasses.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.google.android.material.button.MaterialButton
import com.x3player.glasses.MainActivity
import com.x3player.glasses.PlaybackQueueViewModel
import com.x3player.glasses.R
import com.x3player.glasses.TempleDirection
import com.x3player.glasses.TempleNavigationHandler
import com.x3player.glasses.X3PlayerApplication
import com.x3player.glasses.data.PlaybackProgressStore
import com.x3player.glasses.data.SettingsRepository
import com.x3player.glasses.data.VideoItem
import com.x3player.glasses.databinding.FragmentPlayerBinding
import com.x3player.glasses.util.formatDuration
import com.x3player.glasses.util.resolveResumePosition
import com.x3player.glasses.util.shouldMarkCompleted
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
        OPTIONS,
    }

    private enum class AdvancedControl {
        BACK,
        PREVIOUS,
        NEXT,
    }

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val playbackQueueViewModel: PlaybackQueueViewModel by activityViewModels()
    private lateinit var playbackProgressStore: PlaybackProgressStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var audioManager: AudioManager

    private var callbacks: Callbacks? = null
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var currentItem: VideoItem? = null
    private var mainSelection: MainControl = MainControl.PLAY_PAUSE
    private var advancedSelection: AdvancedControl = AdvancedControl.BACK
    private var lastProgressTapAt = 0L
    private var lastProgressForwardDelta = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val controlsHideRunnable = Runnable { setControlsVisible(false) }
    private val progressForwardRunnable = Runnable { applyDeferredForwardSeek() }
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            updatePositionViews()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.text = "This file could not be played.\n${error.errorCodeName}"
            setControlsVisible(true, keepVisible = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    binding.errorText.visibility = View.GONE
                    updatePositionViews()
                }
                Player.STATE_ENDED -> {
                    savePlaybackProgress(forceCompleted = true)
                    setControlsVisible(true, keepVisible = true)
                }
            }
            updatePlayPauseLabel()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseLabel()
            if (isPlaying) {
                setControlsVisible(true)
            } else {
                setControlsVisible(true, keepVisible = true)
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val appContainer = (requireActivity().application as X3PlayerApplication).appContainer
        playbackProgressStore = appContainer.playbackProgressStore
        settingsRepository = appContainer.settingsRepository
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
        savePlaybackProgress()
        releasePlayer()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (activity as? MainActivity)?.binocularRenderer?.setMirrorMode(false)
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

        if (isAdvancedOptionsVisible()) {
            advancedSelection = navigateAdvanced(advancedSelection, direction)
        } else {
            val previousSelection = mainSelection
            mainSelection = navigateMain(mainSelection, direction)
            if (previousSelection != MainControl.PROGRESS || mainSelection != MainControl.PROGRESS) {
                resetProgressTapGesture()
            }
        }
        setControlsVisible(true, keepVisible = true)
        applySelection()
        return true
    }

    override fun onTempleTap(): Boolean {
        if (binding.controlsOverlay.visibility != View.VISIBLE) {
            setControlsVisible(true)
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

    private fun initializePlayer() {
        val exoPlayer = ExoPlayer.Builder(requireContext()).build().also {
            it.addListener(playerListener)
        }
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
                if (isAdvancedOptionsVisible()) {
                    closeAdvancedOptions()
                } else {
                    setControlsVisible(!binding.controlsOverlay.isShown)
                }
            }
        }

        binding.playerView.setOnClickListener {
            if (isAdvancedOptionsVisible()) {
                closeAdvancedOptions()
            } else {
                setControlsVisible(!binding.controlsOverlay.isShown)
            }
        }

        binding.backButton.setOnClickListener { callbacks?.onExitPlayerRequested() }
        binding.playPauseButton.setOnClickListener { togglePlayback() }
        binding.restartButton.setOnClickListener { restartCurrentVideo() }
        binding.stopButton.setOnClickListener { stopPlayback() }
        binding.progressContainer.setOnClickListener { handleProgressTap() }
        binding.optionsButton.setOnClickListener { openAdvancedOptions() }
        binding.advancedBackButton.setOnClickListener { closeAdvancedOptions() }
        binding.previousButton.setOnClickListener { moveToPrevious() }
        binding.nextButton.setOnClickListener { moveToNext() }

        updatePlayPauseLabel()
        updateNavigationButtons()
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
                        playVideo(item)
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

    private fun playVideo(item: VideoItem) {
        currentItem = item
        mainSelection = MainControl.PLAY_PAUSE
        resetProgressTapGesture()
        closeAdvancedOptions(notifyFrame = false)
        binding.titleText.text = item.displayName
        binding.errorText.visibility = View.GONE
        lifecycleScope.launch {
            settingsRepository.setLastVideoUri(item.contentUri.toString())
            val startPosition = computeStartPosition(item)
            val exoPlayer = player ?: return@launch
            exoPlayer.setMediaItem(MediaItem.fromUri(item.contentUri))
            exoPlayer.prepare()
            if (startPosition > 0L) {
                exoPlayer.seekTo(startPosition)
            }
            exoPlayer.playWhenReady = true
            updatePositionViews()
            setControlsVisible(true)
        }
    }

    private fun moveToPrevious() {
        savePlaybackProgress()
        playbackQueueViewModel.selectPrevious()?.let { playVideo(it) }
        updateNavigationButtons()
    }

    private fun moveToNext() {
        savePlaybackProgress()
        playbackQueueViewModel.selectNext()?.let { playVideo(it) }
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
        setControlsVisible(true, keepVisible = true)
    }

    private fun handleProgressTap() {
        val now = SystemClock.elapsedRealtime()
        if (lastProgressTapAt > 0L && now - lastProgressTapAt <= PROGRESS_DOUBLE_TAP_WINDOW_MS) {
            uiHandler.removeCallbacks(progressForwardRunnable)
            seekBy(-PROGRESS_SEEK_MS)
            resetProgressTapGesture()
            setControlsVisible(true, keepVisible = true)
            return
        }

        lastProgressTapAt = now
        uiHandler.removeCallbacks(progressForwardRunnable)
        uiHandler.postDelayed(progressForwardRunnable, PROGRESS_DOUBLE_TAP_WINDOW_MS)
        setControlsVisible(true, keepVisible = true)
    }

    private fun applyDeferredForwardSeek() {
        lastProgressForwardDelta = seekBy(PROGRESS_SEEK_MS)
    }

    private fun seekBy(deltaMs: Long): Long {
        val exoPlayer = player ?: return 0L
        val start = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration.takeIf { it > 0L }
        val target = if (duration != null) {
            (start + deltaMs).coerceIn(0L, duration)
        } else {
            (start + deltaMs).coerceAtLeast(0L)
        }
        exoPlayer.seekTo(target)
        updatePositionViews()
        return target - start
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        setControlsVisible(true, keepVisible = true)
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

    private fun setControlsVisible(visible: Boolean, keepVisible: Boolean = false) {
        if (!visible) {
            closeAdvancedOptions(notifyFrame = false)
            resetProgressTapGesture()
        }
        binding.controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (!keepVisible && visible && player?.isPlaying == true) {
            uiHandler.removeCallbacks(controlsHideRunnable)
            uiHandler.postDelayed(controlsHideRunnable, CONTROL_HIDE_DELAY_MS)
        } else {
            uiHandler.removeCallbacks(controlsHideRunnable)
        }
        if (visible) {
            applySelection()
        } else {
            binding.root.requestFocus()
        }
        (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
    }

    private fun openAdvancedOptions() {
        advancedSelection = AdvancedControl.BACK
        binding.advancedOptionsOverlay.visibility = View.VISIBLE
        resetProgressTapGesture()
        setControlsVisible(true, keepVisible = true)
    }

    private fun closeAdvancedOptions(notifyFrame: Boolean = true) {
        if (binding.advancedOptionsOverlay.visibility != View.VISIBLE) return
        binding.advancedOptionsOverlay.visibility = View.GONE
        advancedSelection = AdvancedControl.BACK
        if (binding.controlsOverlay.visibility == View.VISIBLE) {
            applySelection()
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
                TempleDirection.RIGHT -> MainControl.OPTIONS
                else -> current
            }
            MainControl.OPTIONS -> when (direction) {
                TempleDirection.LEFT -> MainControl.PROGRESS
                else -> current
            }
        }
    }

    private fun navigateAdvanced(current: AdvancedControl, direction: TempleDirection): AdvancedControl {
        return when (current) {
            AdvancedControl.BACK -> when (direction) {
                TempleDirection.DOWN -> AdvancedControl.PREVIOUS
                else -> current
            }
            AdvancedControl.PREVIOUS -> when (direction) {
                TempleDirection.UP -> AdvancedControl.BACK
                TempleDirection.DOWN -> AdvancedControl.NEXT
                else -> current
            }
            AdvancedControl.NEXT -> when (direction) {
                TempleDirection.UP -> AdvancedControl.PREVIOUS
                else -> current
            }
        }
    }

    private fun applySelection() {
        normalizeAdvancedSelection()
        val advancedVisible = isAdvancedOptionsVisible()
        listOf(
            binding.backButton to (mainSelection == MainControl.BACK && !advancedVisible),
            binding.playPauseButton to (mainSelection == MainControl.PLAY_PAUSE && !advancedVisible),
            binding.restartButton to (mainSelection == MainControl.RESTART && !advancedVisible),
            binding.stopButton to (mainSelection == MainControl.STOP && !advancedVisible),
            binding.optionsButton to (mainSelection == MainControl.OPTIONS && !advancedVisible),
        ).forEach { (button, selected) ->
            styleButton(button, selected)
        }
        styleProgressContainer(mainSelection == MainControl.PROGRESS && !advancedVisible)

        listOf(
            binding.advancedBackButton to (advancedSelection == AdvancedControl.BACK && advancedVisible),
            binding.previousButton to (advancedSelection == AdvancedControl.PREVIOUS && advancedVisible),
            binding.nextButton to (advancedSelection == AdvancedControl.NEXT && advancedVisible),
        ).forEach { (button, selected) ->
            styleButton(button, selected)
        }

        if (advancedVisible) {
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
            AdvancedControl.BACK -> AdvancedControl.BACK
        }
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
            MainControl.OPTIONS -> binding.optionsButton
        }
    }

    private fun selectionView(selection: AdvancedControl): View? {
        return when (selection) {
            AdvancedControl.BACK -> binding.advancedBackButton
            AdvancedControl.PREVIOUS -> binding.previousButton
            AdvancedControl.NEXT -> binding.nextButton
        }
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
                if (isAdvancedOptionsVisible()) {
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
        lastProgressForwardDelta = 0L
    }

    private fun isAdvancedOptionsVisible(): Boolean = binding.advancedOptionsOverlay.visibility == View.VISIBLE

    companion object {
        private const val CONTROL_HIDE_DELAY_MS = 3000L
        private const val PLAYBACK_RESTART_THRESHOLD_MS = 1000L
        private const val PROGRESS_DOUBLE_TAP_WINDOW_MS = 350L
        private const val PROGRESS_SEEK_MS = 10_000L
        private const val PROGRESS_MAX = 1000L
        private const val PLAY_SYMBOL = "\u25B6"

        fun newInstance(): PlayerFragment = PlayerFragment()
    }
}
