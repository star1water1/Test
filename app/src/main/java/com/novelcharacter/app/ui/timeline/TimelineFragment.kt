package com.novelcharacter.app.ui.timeline

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.databinding.DialogTimelineEditBinding
import com.novelcharacter.app.databinding.FragmentTimelineBinding
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import kotlinx.coroutines.launch

class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TimelineViewModel by viewModels()

    private lateinit var adapter: TimelineAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimelineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()
        setupFab()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter(
            onClick = { event ->
                showEditEventDialog(event)
            },
            onLongClick = { event ->
                AlertDialog.Builder(requireContext())
                    .setTitle("${event.year}년")
                    .setItems(arrayOf("편집", "삭제")) { _, which ->
                        when (which) {
                            0 -> showEditEventDialog(event)
                            1 -> {
                                AlertDialog.Builder(requireContext())
                                    .setMessage(R.string.confirm_delete)
                                    .setPositiveButton(R.string.yes) { _, _ ->
                                        viewModel.deleteEvent(event)
                                    }
                                    .setNegativeButton(R.string.no, null)
                                    .show()
                            }
                        }
                    }
                    .show()
            }
        )
        binding.timelineRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.timelineRecyclerView.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFab() {
        binding.fabAddEvent.setOnClickListener {
            showEditEventDialog(null)
        }
    }

    private fun observeData() {
        viewModel.allEvents.observe(viewLifecycleOwner) { events ->
            adapter.submitList(events)
            binding.emptyText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.searchResults.observe(viewLifecycleOwner) { events ->
            adapter.submitList(events)
        }
    }

    private fun showEditEventDialog(event: TimelineEvent?) {
        lifecycleScope.launch {
            val novels = viewModel.getAllNovelsList()
            val characters = viewModel.getAllCharactersList()
            val selectedCharIds = if (event != null) {
                viewModel.getCharacterIdsForEvent(event.id).toMutableSet()
            } else {
                mutableSetOf()
            }

            val dialogBinding = DialogTimelineEditBinding.inflate(layoutInflater)

            // 기존 데이터 채우기
            event?.let {
                dialogBinding.editYear.setText(it.year.toString())
                dialogBinding.editCalendarType.setText(it.calendarType)
                dialogBinding.editDescription.setText(it.description)
            }

            // 작품 스피너
            val novelNames = mutableListOf("전체")
            novelNames.addAll(novels.map { it.title })
            val novelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, novelNames)
            novelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            dialogBinding.spinnerNovel.adapter = novelAdapter

            event?.novelId?.let { novelId ->
                val index = novels.indexOfFirst { it.id == novelId }
                if (index >= 0) dialogBinding.spinnerNovel.setSelection(index + 1)
            }

            // 캐릭터 체크박스 목록
            setupCharacterCheckboxes(dialogBinding, characters, selectedCharIds)

            AlertDialog.Builder(requireContext())
                .setTitle(if (event == null) R.string.add_event else R.string.edit_event)
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.save) { _, _ ->
                    val yearStr = dialogBinding.editYear.text.toString().trim()
                    val description = dialogBinding.editDescription.text.toString().trim()

                    if (yearStr.isEmpty() || description.isEmpty()) {
                        Toast.makeText(requireContext(), "연도와 설명을 입력하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val year = yearStr.toIntOrNull()
                    if (year == null) {
                        Toast.makeText(requireContext(), "올바른 연도를 입력하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val calendarType = dialogBinding.editCalendarType.text.toString().trim()
                    val novelPosition = dialogBinding.spinnerNovel.selectedItemPosition
                    val novelId = if (novelPosition > 0) novels[novelPosition - 1].id else null

                    val newEvent = TimelineEvent(
                        id = event?.id ?: 0,
                        year = year,
                        calendarType = calendarType,
                        description = description,
                        novelId = novelId,
                        createdAt = event?.createdAt ?: System.currentTimeMillis()
                    )

                    if (event == null) {
                        viewModel.insertEvent(newEvent, selectedCharIds.toList())
                    } else {
                        viewModel.updateEvent(newEvent, selectedCharIds.toList())
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupCharacterCheckboxes(
        dialogBinding: DialogTimelineEditBinding,
        characters: List<Character>,
        selectedIds: MutableSet<Long>
    ) {
        // 간단한 체크박스 리스트로 구현
        val recyclerView = dialogBinding.characterSelectRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val checkBox = CheckBox(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                    textSize = 14f
                }
                return object : RecyclerView.ViewHolder(checkBox) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val character = characters[position]
                val checkBox = holder.itemView as CheckBox
                checkBox.text = character.name
                checkBox.isChecked = selectedIds.contains(character.id)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIds.add(character.id)
                    else selectedIds.remove(character.id)
                }
            }

            override fun getItemCount() = characters.size
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
