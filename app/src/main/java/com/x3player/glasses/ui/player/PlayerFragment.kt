package com.x3player.glasses.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private enum class PlayerSelection {
        BACK,
        PREVIOUS,
        SEEK_BACK,
        PLAY_PAUSE,
        SEEK_FORWARD,
        NEXT,
        RESTART,
        VOLUME_DOWN,
        VOLUME_UP,
        LIBRARY,
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
    private var selection: PlayerSelection = PlayerSelection.PLAY_PAUSE
    private val uiHandler = Handler(Looper.getMainLooper())
    private val controlsHideRunnable = Runnable { setControlsVisible(false) }
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
            return true
        }
        selection = navigate(selection, direction)
        applySelection()
        return true
    }

    override fun onTempleTap(): Boolean {
        if (binding.controlsOverlay.visibility != View.VISIBLE) {
            setControlsVisible(true)
            return true
        }
        selectionView(selection)?.performClick()
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
                setControlsVisible(!binding.controlsOverlay.isShown)
            }
        }

        binding.playerView.setOnClickListener {
            setControlsVisible(!binding.controlsOverlay.isShown)
        }

        binding.backButton.setOnClickListener { callbacks?.onExitPlayerRequested() }
        binding.playPauseButton.setOnClickListener { togglePlayback() }
        binding.seekBackButton.setOnClickListener { seekBy(-10_000L) }
        binding.seekForwardButton.setOnClickListener { seekBy(10_000L) }
        binding.previousButton.setOnClickListener { moveToPrevious() }
        binding.nextButton.setOnClickListener { moveToNext() }
        binding.restartButton.setOnClickListener { restartCurrentVideo() }
        binding.volumeDownButton.setOnClickListener { adjustVolume(AudioManager.ADJUST_LOWER) }
        binding.volumeUpButton.setOnClickListener { adjustVolume(AudioManager.ADJUST_RAISE) }
        binding.libraryButton.setOnClickListener { callbacks?.onExitPlayerRequested() }
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
            exoPlayer.play()
        }
        setControlsVisible(true)
    }

    private fun restartCurrentVideo() {
        player?.seekTo(0L)
        player?.playWhenReady = true
        setControlsVisible(true)
    }

    private fun seekBy(deltaMs: Long) {
        val exoPlayer = player ?: return
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceAtLeast(0L))
        setControlsVisible(true)
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
        binding.playPauseButton.text = if (player?.isPlaying == true) "Pause" else "Play"
    }

    private fun updatePositionViews() {
        val exoPlayer = player ?: return
        val currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration.takeIf { it > 0L } ?: 0L
        binding.positionText.text = "${formatDuration(currentPosition)} / ${formatDuration(duration)}"
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
        binding.controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (!keepVisible && visible && player?.isPlaying == true) {
            uiHandler.removeCallbacks(controlsHideRunnable)
            uiHandler.postDelayed(controlsHideRunnable, 3000L)
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

    private fun navigate(current: PlayerSelection, direction: TempleDirection): PlayerSelection {
        return when (current) {
            PlayerSelection.BACK -> when (direction) {
                TempleDirection.DOWN -> PlayerSelection.PLAY_PAUSE
                else -> current
            }
            PlayerSelection.PREVIOUS -> when (direction) {
                TempleDirection.RIGHT -> PlayerSelection.SEEK_BACK
                TempleDirection.DOWN -> PlayerSelection.RESTART
                TempleDirection.UP -> PlayerSelection.BACK
                else -> current
            }
            PlayerSelection.SEEK_BACK -> when (direction) {
                TempleDirection.LEFT -> PlayerSelection.PREVIOUS
                TempleDirection.RIGHT -> PlayerSelection.PLAY_PAUSE
                TempleDirection.DOWN -> PlayerSelection.VOLUME_DOWN
                TempleDirection.UP -> PlayerSelection.BACK
            }
            PlayerSelection.PLAY_PAUSE -> when (direction) {
                TempleDirection.LEFT -> PlayerSelection.SEEK_BACK
                TempleDirection.RIGHT -> PlayerSelection.SEEK_FORWARD
                TempleDirection.DOWN -> PlayerSelection.VOLUME_UP
                TempleDirection.UP -> PlayerSelection.BACK
            }
            PlayerSelection.SEEK_FORWARD -> when (direction) {
                TempleDirection.LEFT -> PlayerSelection.PLAY_PAUSE
                TempleDirection.RIGHT -> PlayerSelection.NEXT
                TempleDirection.DOWN -> PlayerSelection.LIBRARY
                TempleDirection.UP -> PlayerSelection.BACK
            }
            PlayerSelection.NEXT -> when (direction) {
                TempleDirection.LEFT -> PlayerSelection.SEEK_FORWARD
                TempleDirection.DOWN -> PlayerSelection.LIBRARY
                TempleDirection.UP -> PlayerSelection.BACK
                else -> current
            }
            PlayerSelection.RESTART -> when (direction) {
                TempleDirection.RIGHT -> PlayerSelection.VOLUME_DOWN
                TempleDirection.UP -> PlayerSelection.PREVIOUS
                else -> current
            }
            PlayerSelection.VOLUME_DOWN -> when (direction) {
                TempleDirection.LEFT -> PlayerSelection.RESTART
                TempleDirection.RIGHT -> PlayerSelection.VOLUME_UP
                TempleDirection.UP -> PlayerSelection.SEEK_BACK
                else -> current
            }
            PlayerSelection.VOLUME_UP -> when (direction) {
                TempleDirection.LEFT -> PlayerSelection.VOLUME_DOWN
                TempleDirection.RIGHT -> PlayerSelection.LIBRARY
                TempleDirection.UP -> PlayerSelection.PLAY_PAUSE
                else -> current
            }
            PlayerSelection.LIBRARY -> when (direction) {
                TempleDirection.LEFT -> PlayerSelection.VOLUME_UP
                TempleDirection.UP -> PlayerSelection.SEEK_FORWARD
                else -> current
            }
        }
    }

    private fun applySelection() {
        val selectedView = selectionView(selection)
        listOf(
            binding.backButton,
            binding.previousButton,
            binding.seekBackButton,
            binding.playPauseButton,
            binding.seekForwardButton,
            binding.nextButton,
            binding.restartButton,
            binding.volumeDownButton,
            binding.volumeUpButton,
            binding.libraryButton,
        ).forEach { button ->
            styleButton(button, button === selectedView)
        }
        selectedView?.requestFocus()
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
        button.scaleX = if (selected) 1.06f else 1f
        button.scaleY = if (selected) 1.06f else 1f
        button.isActivated = selected
    }

    private fun selectionView(selection: PlayerSelection): MaterialButton? {
        return when (selection) {
            PlayerSelection.BACK -> binding.backButton
            PlayerSelection.PREVIOUS -> binding.previousButton
            PlayerSelection.SEEK_BACK -> binding.seekBackButton
            PlayerSelection.PLAY_PAUSE -> binding.playPauseButton
            PlayerSelection.SEEK_FORWARD -> binding.seekForwardButton
            PlayerSelection.NEXT -> binding.nextButton
            PlayerSelection.RESTART -> binding.restartButton
            PlayerSelection.VOLUME_DOWN -> binding.volumeDownButton
            PlayerSelection.VOLUME_UP -> binding.volumeUpButton
            PlayerSelection.LIBRARY -> binding.libraryButton
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
                seekBy(-10_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(10_000L)
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
                callbacks?.onExitPlayerRequested()
                true
            }
            else -> false
        }
    }

    companion object {
        fun newInstance(): PlayerFragment = PlayerFragment()
    }
}
