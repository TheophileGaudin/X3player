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
import com.x3player.glasses.data.LibraryFilter
import com.x3player.glasses.data.VideoItem
import com.x3player.glasses.databinding.FragmentLibraryBinding
import kotlinx.coroutines.launch

class LibraryFragment : Fragment(), TempleNavigationHandler {

    interface Callbacks {
        fun onVideoSelected(items: List<VideoItem>, index: Int)
    }

    private sealed class LibrarySelection {
        data class Filters(val index: Int) : LibrarySelection()
        data class Actions(val index: Int) : LibrarySelection()
        data class Settings(val index: Int) : LibrarySelection()
        data class Video(val index: Int) : LibrarySelection()
    }

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private var callbacks: Callbacks? = null
    private var renderingState = false
    private var latestItems: List<VideoItem> = emptyList()
    private var selection: LibrarySelection = LibrarySelection.Video(0)

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

        binding.filterToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || renderingState) return@addOnButtonCheckedListener
            when (checkedId) {
                binding.filterAllButton.id -> viewModel.setFilter(LibraryFilter.ALL)
                binding.filterMoviesButton.id -> viewModel.setFilter(LibraryFilter.MOVIES)
                binding.filterDownloadsButton.id -> viewModel.setFilter(LibraryFilter.DOWNLOADS)
            }
        }

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
            is LibrarySelection.Video -> navigateFromVideo(current, direction)
            is LibrarySelection.Settings -> navigateFromSettings(current, direction)
            is LibrarySelection.Actions -> navigateFromActions(current, direction)
            is LibrarySelection.Filters -> navigateFromFilters(current, direction)
        }
        applySelection()
        return true
    }

    override fun onTempleTap(): Boolean {
        when (val current = normalizeSelection(selection, latestItems)) {
            is LibrarySelection.Video -> {
                callbacks?.onVideoSelected(latestItems, current.index)
                return true
            }
            is LibrarySelection.Filters -> selectionViewFor(current)?.performClick()
            is LibrarySelection.Actions -> selectionViewFor(current)?.performClick()
            is LibrarySelection.Settings -> selectionViewFor(current)?.performClick()
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
        when (state.filter) {
            LibraryFilter.ALL -> binding.filterToggleGroup.check(binding.filterAllButton.id)
            LibraryFilter.MOVIES -> binding.filterToggleGroup.check(binding.filterMoviesButton.id)
            LibraryFilter.DOWNLOADS -> binding.filterToggleGroup.check(binding.filterDownloadsButton.id)
        }
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

    private fun navigateFromVideo(current: LibrarySelection.Video, direction: TempleDirection): LibrarySelection {
        return when (direction) {
            TempleDirection.DOWN -> {
                if (current.index < latestItems.lastIndex) LibrarySelection.Video(current.index + 1) else current
            }
            TempleDirection.UP -> {
                if (current.index > 0) LibrarySelection.Video(current.index - 1) else LibrarySelection.Settings(0)
            }
            TempleDirection.LEFT,
            TempleDirection.RIGHT -> current
        }
    }

    private fun navigateFromSettings(current: LibrarySelection.Settings, direction: TempleDirection): LibrarySelection {
        return when (direction) {
            TempleDirection.LEFT -> LibrarySelection.Settings((current.index - 1).coerceAtLeast(0))
            TempleDirection.RIGHT -> LibrarySelection.Settings((current.index + 1).coerceAtMost(1))
            TempleDirection.DOWN -> if (latestItems.isNotEmpty()) LibrarySelection.Video(0) else current
            TempleDirection.UP -> LibrarySelection.Actions(0)
        }
    }

    private fun navigateFromActions(current: LibrarySelection.Actions, direction: TempleDirection): LibrarySelection {
        return when (direction) {
            TempleDirection.LEFT -> LibrarySelection.Actions((current.index - 1).coerceAtLeast(0))
            TempleDirection.RIGHT -> LibrarySelection.Actions((current.index + 1).coerceAtMost(1))
            TempleDirection.DOWN -> LibrarySelection.Settings(0)
            TempleDirection.UP -> LibrarySelection.Filters(0)
        }
    }

    private fun navigateFromFilters(current: LibrarySelection.Filters, direction: TempleDirection): LibrarySelection {
        return when (direction) {
            TempleDirection.LEFT -> LibrarySelection.Filters((current.index - 1).coerceAtLeast(0))
            TempleDirection.RIGHT -> LibrarySelection.Filters((current.index + 1).coerceAtMost(2))
            TempleDirection.DOWN -> LibrarySelection.Actions(0)
            TempleDirection.UP -> current
        }
    }

    private fun normalizeSelection(selection: LibrarySelection, items: List<VideoItem>): LibrarySelection {
        return when (selection) {
            is LibrarySelection.Video -> {
                if (items.isNotEmpty()) {
                    LibrarySelection.Video(selection.index.coerceIn(0, items.lastIndex))
                } else {
                    LibrarySelection.Actions(1)
                }
            }
            else -> selection
        }
    }

    private fun applySelection() {
        val current = normalizeSelection(selection, latestItems)
        selection = current
        when (current) {
            is LibrarySelection.Video -> {
                adapter.setSelectedIndex(current.index)
                focusVideoItem(current.index)
                highlightControls(null)
            }
            is LibrarySelection.Filters,
            is LibrarySelection.Actions,
            is LibrarySelection.Settings -> {
                adapter.setSelectedIndex(-1)
                val target = selectionViewFor(current)
                highlightControls(target)
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

    private fun highlightControls(selected: View?) {
        val actionViews = listOf<View>(
            binding.filterAllButton,
            binding.filterMoviesButton,
            binding.filterDownloadsButton,
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
            is LibrarySelection.Filters -> when (selection.index) {
                0 -> binding.filterAllButton
                1 -> binding.filterMoviesButton
                else -> binding.filterDownloadsButton
            }
            is LibrarySelection.Actions -> when (selection.index) {
                0 -> binding.sortButton
                else -> binding.refreshButton
            }
            is LibrarySelection.Settings -> when (selection.index) {
                0 -> binding.autoResumeCheckbox
                else -> binding.reopenCheckbox
            }
            is LibrarySelection.Video -> null
        }
    }

    companion object {
        fun newInstance(): LibraryFragment = LibraryFragment()
    }
}
