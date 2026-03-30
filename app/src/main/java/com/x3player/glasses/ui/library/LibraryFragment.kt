package com.x3player.glasses.ui.library

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.x3player.glasses.MainActivity
import com.x3player.glasses.R
import com.x3player.glasses.TempleDirection
import com.x3player.glasses.TempleNavigationHandler
import com.x3player.glasses.X3PlayerApplication
import com.x3player.glasses.data.VideoItem
import com.x3player.glasses.databinding.FragmentLibraryBinding
import kotlinx.coroutines.launch

class LibraryFragment : Fragment(), TempleNavigationHandler {

    interface Callbacks {
        fun onVideoSelected(items: List<VideoItem>, index: Int)
    }

    private sealed class LibrarySelection {
        object OptionsButton : LibrarySelection()
        data class Video(val index: Int) : LibrarySelection()
        data class Overlay(val index: Int) : LibrarySelection()
    }

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private var callbacks: Callbacks? = null
    private var renderingState = false
    private var latestItems: List<VideoItem> = emptyList()
    private var selection: LibrarySelection = LibrarySelection.Video(0)
    private var lastListSelection: LibrarySelection = LibrarySelection.Video(0)

    private val viewModel: LibraryViewModel by viewModels {
        val appContainer = (requireActivity().application as X3PlayerApplication).appContainer
        LibraryViewModel.Factory(appContainer.videoRepository, appContainer.settingsRepository)
    }

    private val adapter = VideoListAdapter { _, position ->
        callbacks?.onVideoSelected(latestItems, position)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.videoList.layoutManager = LinearLayoutManager(requireContext())
        binding.videoList.adapter = adapter

        binding.optionsButton.setOnClickListener { openOptionsOverlay() }
        binding.overlayBackButton.setOnClickListener { closeOptionsOverlay() }
        binding.filterButton.setOnClickListener { viewModel.cycleFilter() }
        binding.sortButton.setOnClickListener { viewModel.cycleSort() }
        binding.refreshButton.setOnClickListener { viewModel.refresh() }
        binding.autoResumeCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (!renderingState) viewModel.setAutoResume(isChecked)
        }
        binding.reopenCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (!renderingState) viewModel.setReopenLastVideoOnLaunch(isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    latestItems = state.items
                    render(state)
                    (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.binocularRenderer?.setMirrorMode(false)
        onTempleEnsureFocus()
        (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
    }

    override fun onDestroyView() {
        binding.videoList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }

    override fun onTempleNavigate(direction: TempleDirection): Boolean {
        selection = when (val current = normalizeSelection(selection, latestItems)) {
            is LibrarySelection.OptionsButton -> navigateFromOptionsButton(direction)
            is LibrarySelection.Video -> navigateFromVideo(current, direction)
            is LibrarySelection.Overlay -> navigateFromOverlay(current, direction)
        }
        applySelection()
        return true
    }

    override fun onTempleTap(): Boolean {
        when (val current = normalizeSelection(selection, latestItems)) {
            is LibrarySelection.OptionsButton -> {
                openOptionsOverlay()
                return true
            }
            is LibrarySelection.Video -> {
                callbacks?.onVideoSelected(latestItems, current.index)
                return true
            }
            is LibrarySelection.Overlay -> {
                selectionViewFor(current)?.performClick()
            }
        }
        applySelection()
        return true
    }

    override fun onTempleEnsureFocus() {
        selection = normalizeSelection(selection, latestItems)
        binding.root.post { applySelection() }
    }

    private fun render(state: LibraryUiState) {
        renderingState = true
        val previousSelection = selection
        binding.filterButton.text = "Location: ${state.filter.label}"
        binding.sortButton.text = "Sort: ${state.sort.label}"
        binding.autoResumeCheckbox.isChecked = state.autoResume
        binding.reopenCheckbox.isChecked = state.reopenLastVideoOnLaunch
        binding.progressBar.visibility = if (state.isRefreshing) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (state.items.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = state.emptyMessage
        binding.videoList.visibility = if (state.items.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(state.items)
        renderingState = false
        binding.root.post {
            selection = normalizeSelection(previousSelection, state.items)
            applySelection()
        }
    }

    private fun navigateFromOptionsButton(direction: TempleDirection): LibrarySelection {
        return when (direction) {
            TempleDirection.DOWN -> if (latestItems.isNotEmpty()) LibrarySelection.Video(0) else LibrarySelection.OptionsButton
            TempleDirection.UP,
            TempleDirection.LEFT,
            TempleDirection.RIGHT -> LibrarySelection.OptionsButton
        }
    }

    private fun navigateFromVideo(current: LibrarySelection.Video, direction: TempleDirection): LibrarySelection {
        return when (direction) {
            TempleDirection.DOWN -> {
                if (current.index < latestItems.lastIndex) LibrarySelection.Video(current.index + 1) else current
            }
            TempleDirection.UP -> {
                if (current.index > 0) LibrarySelection.Video(current.index - 1) else LibrarySelection.OptionsButton
            }
            TempleDirection.LEFT,
            TempleDirection.RIGHT -> current
        }
    }

    private fun navigateFromOverlay(current: LibrarySelection.Overlay, direction: TempleDirection): LibrarySelection {
        return when (direction) {
            TempleDirection.UP -> LibrarySelection.Overlay((current.index - 1).coerceAtLeast(0))
            TempleDirection.DOWN -> LibrarySelection.Overlay((current.index + 1).coerceAtMost(5))
            TempleDirection.LEFT,
            TempleDirection.RIGHT -> current
        }
    }

    private fun normalizeSelection(selection: LibrarySelection, items: List<VideoItem>): LibrarySelection {
        return when (selection) {
            is LibrarySelection.Overlay -> {
                if (isOptionsOverlayVisible()) {
                    LibrarySelection.Overlay(selection.index.coerceIn(0, 5))
                } else {
                    normalizeListSelection(lastListSelection, items)
                }
            }
            else -> normalizeListSelection(selection, items)
        }
    }

    private fun normalizeListSelection(selection: LibrarySelection, items: List<VideoItem>): LibrarySelection {
        return when (selection) {
            is LibrarySelection.OptionsButton -> LibrarySelection.OptionsButton
            is LibrarySelection.Video -> {
                if (items.isNotEmpty()) {
                    LibrarySelection.Video(selection.index.coerceIn(0, items.lastIndex))
                } else {
                    LibrarySelection.OptionsButton
                }
            }
            is LibrarySelection.Overlay -> {
                if (items.isNotEmpty()) {
                    LibrarySelection.Video(0)
                } else {
                    LibrarySelection.OptionsButton
                }
            }
        }
    }

    private fun applySelection() {
        val current = normalizeSelection(selection, latestItems)
        selection = current
        when (current) {
            is LibrarySelection.OptionsButton -> {
                adapter.setSelectedIndex(-1)
                styleButton(binding.optionsButton, true)
                clearOverlaySelection()
                binding.optionsButton.requestFocus()
            }
            is LibrarySelection.Video -> {
                adapter.setSelectedIndex(current.index)
                styleButton(binding.optionsButton, false)
                clearOverlaySelection()
                focusVideoItem(current.index)
            }
            is LibrarySelection.Overlay -> {
                adapter.setSelectedIndex(-1)
                styleButton(binding.optionsButton, false)
                val target = selectionViewFor(current)
                highlightOverlayControls(target)
                target?.requestFocus()
            }
        }
    }

    private fun focusVideoItem(index: Int) {
        val layoutManager = binding.videoList.layoutManager as? LinearLayoutManager ?: return
        layoutManager.scrollToPositionWithOffset(index, 0)
        binding.videoList.post {
            val holder = binding.videoList.findViewHolderForAdapterPosition(index)
            val target = holder?.itemView ?: binding.videoList.getChildAt(0)
            target?.requestFocus() ?: binding.videoList.requestFocus()
        }
    }

    private fun highlightOverlayControls(selected: View?) {
        val actionViews = listOf<View>(
            binding.overlayBackButton,
            binding.filterButton,
            binding.sortButton,
            binding.refreshButton,
            binding.autoResumeCheckbox,
            binding.reopenCheckbox,
        )
        actionViews.forEach { view ->
            val isSelected = view === selected
            view.isActivated = isSelected
            view.scaleX = if (isSelected) 1.05f else 1f
            view.scaleY = if (isSelected) 1.05f else 1f
            when (view) {
                is MaterialButton -> styleButton(view, isSelected)
                is CheckBox -> styleCheckBox(view, isSelected)
            }
        }
    }

    private fun clearOverlaySelection() {
        highlightOverlayControls(null)
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
    }

    private fun styleCheckBox(checkBox: CheckBox, selected: Boolean) {
        val backgroundColor = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.panel_blue_light else R.color.surface_black,
        )
        val textColor = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.accent_cyan else R.color.text_secondary,
        )
        checkBox.setBackgroundColor(backgroundColor)
        checkBox.setTextColor(textColor)
        checkBox.setPadding(18, 12, 18, 12)
    }

    private fun selectionViewFor(selection: LibrarySelection): View? {
        return when (selection) {
            is LibrarySelection.OptionsButton -> binding.optionsButton
            is LibrarySelection.Video -> null
            is LibrarySelection.Overlay -> when (selection.index) {
                0 -> binding.overlayBackButton
                1 -> binding.filterButton
                2 -> binding.sortButton
                3 -> binding.refreshButton
                4 -> binding.autoResumeCheckbox
                else -> binding.reopenCheckbox
            }
        }
    }

    private fun openOptionsOverlay() {
        if (isOptionsOverlayVisible()) return
        lastListSelection = normalizeListSelection(selection, latestItems)
        binding.optionsOverlay.visibility = View.VISIBLE
        selection = LibrarySelection.Overlay(0)
        applySelection()
        (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
    }

    private fun closeOptionsOverlay() {
        if (!isOptionsOverlayVisible()) return
        binding.optionsOverlay.visibility = View.GONE
        selection = normalizeListSelection(lastListSelection, latestItems)
        applySelection()
        (activity as? MainActivity)?.binocularRenderer?.notifyFrameChanged()
    }

    private fun isOptionsOverlayVisible(): Boolean = binding.optionsOverlay.visibility == View.VISIBLE

    companion object {
        fun newInstance(): LibraryFragment = LibraryFragment()
    }
}
