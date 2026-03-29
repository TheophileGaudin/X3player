package com.x3player.glasses

import android.net.Uri
import android.os.Bundle
import android.view.FocusFinder
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.x3player.glasses.binocular.BinocularPlayerLayout
import com.x3player.glasses.data.VideoItem
import com.x3player.glasses.databinding.ActivityMainBinding
import com.x3player.glasses.ui.library.LibraryFragment
import com.x3player.glasses.ui.player.PlayerFragment
import com.x3player.glasses.util.StoragePermissionHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), LibraryFragment.Callbacks, PlayerFragment.Callbacks {

    companion object {
        private const val TEMPLE_SENSITIVITY_X = 1.0f
        private const val TEMPLE_SENSITIVITY_Y = 2.5f
        private const val MIN_MOVEMENT_THRESHOLD = 0.5f
        private const val TAP_MAX_DISTANCE = 50f
        private const val TAP_MAX_DURATION = 300L
        private const val FOCUS_MOVE_THRESHOLD_X = 18f
        private const val FOCUS_MOVE_THRESHOLD_Y = 18f
    }

    private lateinit var binding: ActivityMainBinding
    private val playbackQueueViewModel: PlaybackQueueViewModel by viewModels()
    private var templeDownX = 0f
    private var templeDownY = 0f
    private var lastTempleX = 0f
    private var lastTempleY = 0f
    private var templeDownTime = 0L
    private var templeTotalDistance = 0f
    private var templeFocusAccumulatorX = 0f
    private var templeFocusAccumulatorY = 0f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionState()
    }

    private val appContainer: AppContainer
        get() = (application as X3PlayerApplication).appContainer

    val binocularRenderer: BinocularPlayerLayout
        get() = binding.binocularLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.binocularLayout.initialize(this)

        binding.permissionButton.setOnClickListener {
            val permissions = StoragePermissionHelper.requiredPermissions()
            if (permissions.isEmpty()) {
                refreshPermissionState()
            } else {
                permissionLauncher.launch(permissions)
            }
        }
        binding.permissionSecondaryButton.setOnClickListener {
            refreshPermissionState()
        }

        refreshPermissionState()
    }

    override fun onDestroy() {
        binding.binocularLayout.release()
        super.onDestroy()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return if (handleTempleInteraction(event)) true else super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (handleTempleInteraction(event)) true else super.onTouchEvent(event)
    }

    override fun onVideoSelected(items: List<VideoItem>, index: Int) {
        playbackQueueViewModel.openQueue(items, index)
        lifecycleScope.launch {
            val item = items.getOrNull(index) ?: return@launch
            appContainer.settingsRepository.setLastVideoUri(item.contentUri.toString())
        }
        showPlayer()
    }

    override fun onExitPlayerRequested() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            showLibrary(clearBackStack = true)
        }
    }

    private fun refreshPermissionState() {
        val hasPermissions = StoragePermissionHelper.hasRequiredPermissions(this)
        binding.permissionPanel.isVisible = !hasPermissions
        binding.fragmentContainer.isVisible = hasPermissions

        if (!hasPermissions) {
            binding.binocularLayout.setMirrorMode(false)
            binding.permissionButton.post { binding.permissionButton.requestFocus() }
            return
        }

        if (supportFragmentManager.findFragmentById(binding.fragmentContainer.id) == null) {
            lifecycleScope.launch {
                openInitialScreen()
            }
        }
    }

    private suspend fun openInitialScreen() {
        val settings = appContainer.settingsRepository.settings.first()
        if (settings.reopenLastVideoOnLaunch && !settings.lastVideoUri.isNullOrBlank()) {
            val item = appContainer.videoRepository.getByUri(Uri.parse(settings.lastVideoUri))
            if (item != null) {
                playbackQueueViewModel.openSingle(item)
                showPlayer(clearBackStack = true)
                return
            }
        }
        showLibrary(clearBackStack = true)
    }

    private fun showLibrary(clearBackStack: Boolean = false) {
        binding.binocularLayout.setMirrorMode(false)
        if (clearBackStack) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        val current = supportFragmentManager.findFragmentById(binding.fragmentContainer.id)
        if (current is LibraryFragment) return
        supportFragmentManager.commit {
            replace(binding.fragmentContainer.id, LibraryFragment.newInstance())
        }
    }

    private fun showPlayer(clearBackStack: Boolean = false) {
        if (clearBackStack) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        val current = supportFragmentManager.findFragmentById(binding.fragmentContainer.id)
        if (current is PlayerFragment) return
        supportFragmentManager.commit {
            replace(binding.fragmentContainer.id, PlayerFragment.newInstance())
            if (!clearBackStack) {
                addToBackStack(PlayerFragment::class.java.simpleName)
            }
        }
    }

    private fun handleTempleInteraction(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER -> {
                lastTempleX = event.x
                lastTempleY = event.y
                templeDownX = event.x
                templeDownY = event.y
                templeDownTime = System.currentTimeMillis()
                templeTotalDistance = 0f
                templeFocusAccumulatorX = 0f
                templeFocusAccumulatorY = 0f
                ensureInteractionFocus()
                return true
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val rawDx = event.x - lastTempleX
                val rawDy = event.y - lastTempleY
                val dx = rawDx * TEMPLE_SENSITIVITY_X
                val dy = rawDy * TEMPLE_SENSITIVITY_Y
                val movementMagnitude = sqrt(dx * dx + dy * dy)
                templeTotalDistance += sqrt(rawDx * rawDx + rawDy * rawDy)
                lastTempleX = event.x
                lastTempleY = event.y
                if (movementMagnitude >= MIN_MOVEMENT_THRESHOLD) {
                    handleTempleMove(dx, dy)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_HOVER_EXIT -> {
                val duration = System.currentTimeMillis() - templeDownTime
                if (templeTotalDistance < TAP_MAX_DISTANCE && duration < TAP_MAX_DURATION) {
                    performFocusedClick()
                }
                templeFocusAccumulatorX = 0f
                templeFocusAccumulatorY = 0f
                return true
            }
        }
        return false
    }

    private fun handleTempleMove(dx: Float, dy: Float) {
        templeFocusAccumulatorX += dx
        templeFocusAccumulatorY += dy

        val absX = abs(templeFocusAccumulatorX)
        val absY = abs(templeFocusAccumulatorY)

        if (absX < FOCUS_MOVE_THRESHOLD_X && absY < FOCUS_MOVE_THRESHOLD_Y) {
            return
        }

        val direction = when {
            absX > absY * 1.15f && absX >= FOCUS_MOVE_THRESHOLD_X -> {
                if (templeFocusAccumulatorX > 0f) TempleDirection.RIGHT else TempleDirection.LEFT
            }

            absY >= FOCUS_MOVE_THRESHOLD_Y -> {
                if (templeFocusAccumulatorY > 0f) TempleDirection.DOWN else TempleDirection.UP
            }

            else -> null
        } ?: return

        moveFocus(direction)
        templeFocusAccumulatorX = 0f
        templeFocusAccumulatorY = 0f
    }

    private fun moveFocus(direction: TempleDirection) {
        val templeHandler = activeTempleHandler()
        if (templeHandler?.onTempleNavigate(direction) == true) {
            binocularRenderer.notifyFrameChanged()
            return
        }

        val androidDirection = when (direction) {
            TempleDirection.UP -> View.FOCUS_UP
            TempleDirection.DOWN -> View.FOCUS_DOWN
            TempleDirection.LEFT -> View.FOCUS_LEFT
            TempleDirection.RIGHT -> View.FOCUS_RIGHT
        }
        val root = activeInteractionRoot() ?: return
        val focused = currentFocusedChildWithin(root) ?: findFirstFocusableDescendant(root) ?: return
        if (!focused.isFocused) {
            focused.requestFocus()
        }
        val next = FocusFinder.getInstance().findNextFocus(root as? ViewGroup, focused, androidDirection)
            ?: focused.focusSearch(androidDirection)
            ?: findFirstFocusableDescendant(root)
            ?: return
        if (next !== focused && next.isShown && next.isFocusable) {
            next.requestFocus()
            binocularRenderer.notifyFrameChanged()
        }
    }

    private fun performFocusedClick() {
        val templeHandler = activeTempleHandler()
        if (templeHandler?.onTempleTap() == true) {
            binocularRenderer.notifyFrameChanged()
            return
        }

        val root = activeInteractionRoot() ?: return
        val target = currentFocusedChildWithin(root) ?: findFirstFocusableDescendant(root) ?: return
        if (!target.isFocused) {
            target.requestFocus()
        }
        target.performClick()
        binocularRenderer.notifyFrameChanged()
    }

    private fun ensureInteractionFocus() {
        activeTempleHandler()?.onTempleEnsureFocus()
        val root = activeInteractionRoot() ?: return
        if (currentFocusedChildWithin(root) != null) return
        findFirstFocusableDescendant(root)?.requestFocus()
    }

    private fun activeInteractionRoot(): View? {
        return if (binding.permissionPanel.isVisible) {
            binding.permissionPanel
        } else {
            binding.fragmentContainer
        }
    }

    private fun activeTempleHandler(): TempleNavigationHandler? {
        if (binding.permissionPanel.isVisible) return null
        return supportFragmentManager.findFragmentById(binding.fragmentContainer.id) as? TempleNavigationHandler
    }

    private fun currentFocusedChildWithin(root: View): View? {
        val focused = currentFocus ?: return null
        return if (isDescendant(root, focused) && focused.isShown) focused else null
    }

    private fun findFirstFocusableDescendant(root: View?): View? {
        if (root == null || !root.isShown) return null
        if (root.isFocusable && root.visibility == View.VISIBLE && root.alpha > 0f) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val found = findFirstFocusableDescendant(root.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun isDescendant(parent: View, child: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === parent) return true
            current = (current.parent as? View)
        }
        return false
    }
}
