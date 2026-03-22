package com.novelcharacter.app.ui.faction

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.databinding.FragmentFactionManageBinding
import com.novelcharacter.app.ui.adapter.FactionAdapter
import com.novelcharacter.app.ui.adapter.FactionMemberAdapter
import com.novelcharacter.app.ui.adapter.FactionMemberItem
import kotlinx.coroutines.launch

class FactionManageFragment : Fragment() {

    private var _binding: FragmentFactionManageBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FactionViewModel by viewModels()

    private lateinit var adapter: FactionAdapter
    private var universeId: Long = -1L
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFactionManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        universeId = arguments?.getLong("universeId", -1L) ?: -1L
        if (universeId == -1L) {
            findNavController().popBackStack()
            return
        }

        viewModel.setUniverseId(universeId)
        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeData()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerView() {
        adapter = FactionAdapter(
            onClick = { faction -> showFactionDetailDialog(faction) },
            onLongClick = { faction -> showFactionOptionsDialog(faction) }
        )
        binding.factionRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.factionRecyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val list = adapter.currentList.toMutableList()
                if (from < 0 || to < 0 || from >= list.size || to >= list.size) return false
                val item = list.removeAt(from)
                list.add(to, item)
                adapter.submitList(list)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewModel.updateFactionOrder(adapter.currentList)
            }
        })
        itemTouchHelper?.attachToRecyclerView(binding.factionRecyclerView)
    }

    private fun setupFab() {
        binding.fabAddFaction.setOnClickListener {
            showFactionEditDialog(null)
        }
    }

    private fun observeData() {
        viewModel.factions.observe(viewLifecycleOwner) { factions ->
            adapter.submitList(factions)
            val isEmpty = factions.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.factionRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE

            // 멤버 수 계산
            viewLifecycleOwner.lifecycleScope.launch {
                val counts = viewModel.getActiveMemberCountsForFactions(factions.map { it.id })
                adapter.updateMemberCounts(counts)
            }
        }
    }

    // ===== 세력 편집 다이얼로그 =====

    private fun showFactionEditDialog(faction: Faction?) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_faction_edit, null)
        val editName = dialogView.findViewById<EditText>(R.id.editFactionName)
        val editDescription = dialogView.findViewById<EditText>(R.id.editFactionDescription)
        val editAutoRelationType = dialogView.findViewById<EditText>(R.id.editAutoRelationType)
        val sliderIntensity = dialogView.findViewById<Slider>(R.id.sliderIntensity)
        val colorPalette = dialogView.findViewById<LinearLayout>(R.id.colorPalette)

        // 프리셋 색상
        val presetColors = listOf(
            "#F44336", "#E91E63", "#9C27B0", "#673AB7",
            "#3F51B5", "#2196F3", "#03A9F4", "#009688",
            "#4CAF50", "#8BC34A", "#FF9800", "#795548"
        )
        var selectedColor = faction?.color ?: "#2196F3"

        // 색상 팔레트 생성
        val density = resources.displayMetrics.density
        val colorSize = (32 * density).toInt()
        val colorMargin = (4 * density).toInt()
        val colorViews = mutableListOf<View>()

        for (color in presetColors) {
            val colorView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(colorSize, colorSize).apply {
                    setMargins(colorMargin, colorMargin, colorMargin, colorMargin)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(color))
                    if (color == selectedColor) {
                        setStroke((3 * density).toInt(), Color.BLACK)
                    }
                }
                setOnClickListener {
                    selectedColor = color
                    // 모든 색상의 테두리 초기화
                    colorViews.forEachIndexed { i, v ->
                        (v.background as? GradientDrawable)?.apply {
                            setStroke(
                                if (presetColors[i] == color) (3 * density).toInt() else 0,
                                Color.BLACK
                            )
                        }
                    }
                }
            }
            colorViews.add(colorView)
            colorPalette.addView(colorView)
        }

        // 기존 값 채우기
        if (faction != null) {
            editName.setText(faction.name)
            editDescription.setText(faction.description)
            editAutoRelationType.setText(faction.autoRelationType)
            sliderIntensity.value = faction.autoRelationIntensity.toFloat()
        }

        val title = if (faction == null) R.string.faction_add else R.string.faction_edit
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = editName.text.toString().trim()
                val description = editDescription.text.toString().trim()
                val autoRelationType = editAutoRelationType.text.toString().trim()
                val intensity = sliderIntensity.value.toInt()

                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.faction_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (autoRelationType.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.faction_relation_type_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (faction == null) {
                    viewModel.insertFaction(Faction(
                        universeId = universeId,
                        name = name,
                        description = description,
                        color = selectedColor,
                        autoRelationType = autoRelationType,
                        autoRelationIntensity = intensity
                    ))
                } else {
                    viewModel.updateFaction(faction.copy(
                        name = name,
                        description = description,
                        color = selectedColor,
                        autoRelationType = autoRelationType,
                        autoRelationIntensity = intensity
                    ))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ===== 세력 옵션 (편집/삭제) =====

    private fun showFactionOptionsDialog(faction: Faction) {
        AlertDialog.Builder(requireContext())
            .setTitle(faction.name)
            .setPositiveButton(R.string.edit) { _, _ ->
                showFactionEditDialog(faction)
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                showDeleteFactionDialog(faction)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteFactionDialog(faction: Faction) {
        val items = arrayOf(
            getString(R.string.faction_delete_with_relations),
            getString(R.string.faction_delete_keep_relations)
        )
        var selectedIndex = 0
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.faction_delete_title, faction.name))
            .setSingleChoiceItems(items, 0) { _, which -> selectedIndex = which }
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteFaction(faction, deleteRelationships = selectedIndex == 0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ===== 세력 상세 (멤버 관리) =====

    private fun showFactionDetailDialog(faction: Faction) {
        viewModel.setSelectedFactionId(faction.id)

        val ctx = requireContext()
        val density = resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        }

        val memberRecyclerView = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (300 * density).toInt()
            )
        }

        val memberAdapter = FactionMemberAdapter(
            onLongClick = { item -> showMemberOptionsDialog(faction, item) }
        )
        memberRecyclerView.adapter = memberAdapter
        container.addView(memberRecyclerView)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("${faction.name} — ${getString(R.string.faction_members)}")
            .setView(container)
            .setPositiveButton(R.string.faction_member_add) { _, _ ->
                showAddMemberDialog(faction)
            }
            .setNegativeButton(R.string.close, null)
            .show()

        // 멤버십 관찰 (다이얼로그가 표시되는 동안만)
        val memberObserver = androidx.lifecycle.Observer<List<FactionMembership>> { memberships ->
            viewLifecycleOwner.lifecycleScope.launch {
                val items = memberships.mapNotNull { m ->
                    val char = viewModel.getCharacterById(m.characterId)
                    if (char != null) FactionMemberItem(m, char.name) else null
                }
                memberAdapter.submitList(items)
            }
        }
        viewModel.memberships.observe(viewLifecycleOwner, memberObserver)
        dialog.setOnDismissListener {
            viewModel.memberships.removeObserver(memberObserver)
        }
    }

    private fun showMemberOptionsDialog(faction: Faction, item: FactionMemberItem) {
        if (item.membership.leaveType != null) return // 이미 탈퇴/제거된 멤버

        val options = arrayOf(
            getString(R.string.faction_member_remove),
            getString(R.string.faction_member_depart)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(item.characterName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.faction_member_remove)
                            .setMessage(getString(R.string.faction_member_remove_confirm, item.characterName, faction.name))
                            .setPositiveButton(R.string.delete) { _, _ ->
                                viewModel.removeMember(faction.id, item.membership.characterId)
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    1 -> showDepartureDialog(faction, item)
                }
            }
            .show()
    }

    private fun showDepartureDialog(faction: Faction, item: FactionMemberItem) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
        }

        val editYear = EditText(ctx).apply {
            hint = getString(R.string.faction_leave_year)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        container.addView(editYear)

        val editRelType = EditText(ctx).apply {
            hint = getString(R.string.faction_departed_relation)
            setText("전 ${faction.autoRelationType}")
        }
        container.addView(editRelType)

        val intensityLabel = android.widget.TextView(ctx).apply {
            text = getString(R.string.faction_auto_relation_intensity)
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        container.addView(intensityLabel)

        val sliderIntensity = Slider(ctx).apply {
            valueFrom = 1f
            valueTo = 10f
            stepSize = 1f
            value = 1f
        }
        container.addView(sliderIntensity)

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.faction_member_depart))
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val yearStr = editYear.text.toString().trim()
                val year = yearStr.toIntOrNull()
                if (year == null) {
                    Toast.makeText(ctx, R.string.faction_leave_year_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val relType = editRelType.text.toString().trim().ifEmpty { "전 ${faction.autoRelationType}" }
                val intensity = sliderIntensity.value.toInt()

                viewModel.departMember(faction.id, item.membership.characterId, year, relType, intensity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddMemberDialog(faction: Faction) {
        viewLifecycleOwner.lifecycleScope.launch {
            val allCharacters = viewModel.getCharactersForUniverse(universeId)
            if (allCharacters.isEmpty()) {
                Toast.makeText(requireContext(), R.string.faction_no_characters, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val ctx = requireContext()
            val density = resources.displayMetrics.density

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
            }

            // 가입 연도 입력 (선택)
            val editYear = EditText(ctx).apply {
                hint = getString(R.string.faction_join_year_optional)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            container.addView(editYear)

            val listView = ListView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (250 * density).toInt()
                )
            }

            val charNames = allCharacters.map { it.name }.toTypedArray()
            listView.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, charNames)
            container.addView(listView)

            val dialog = AlertDialog.Builder(ctx)
                .setTitle(R.string.faction_member_add)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .show()

            listView.setOnItemClickListener { _, _, position, _ ->
                val character = allCharacters[position]
                val joinYear = editYear.text.toString().trim().toIntOrNull()
                viewModel.addMember(faction.id, character.id, joinYear)
                Toast.makeText(ctx, getString(R.string.faction_member_added, character.name), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }

    override fun onDestroyView() {
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        binding.factionRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
