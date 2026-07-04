package com.novelcharacter.app.ui.faction

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
import com.novelcharacter.app.util.notifyResult
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

    // 드래그 후 저장할 순서를 보관 (ListAdapter 비동기 diff가 끝나기 전 currentList가 stale할 수 있음)
    private var pendingOrderList: List<Faction>? = null

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
                // 빠른 연속 이동 시 currentList는 아직 이전 diff가 커밋되지 않았을 수 있으므로 pending을 우선 사용
                val list = (pendingOrderList ?: adapter.currentList).toMutableList()
                if (from < 0 || to < 0 || from >= list.size || to >= list.size) return false
                val item = list.removeAt(from)
                list.add(to, item)
                pendingOrderList = list
                adapter.submitList(list)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 이동이 한 번도 없었다면(pending == null) 저장할 변경도 없다
                val listToSave = pendingOrderList ?: return
                pendingOrderList = null
                viewModel.updateFactionOrder(listToSave)
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

        // 데이터 처리 결과 알림 (성공/실패 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
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
        val colorPreview = dialogView.findViewById<View>(R.id.factionColorPreview)
        val hexEdit = dialogView.findViewById<EditText>(R.id.editFactionColor)
        val btnFullSpectrum = dialogView.findViewById<android.widget.TextView>(R.id.btnFullSpectrum)

        // 프리셋 색상
        val presetColors = listOf(
            "#F44336", "#E91E63", "#9C27B0", "#673AB7",
            "#3F51B5", "#2196F3", "#03A9F4", "#009688",
            "#4CAF50", "#8BC34A", "#FF9800", "#795548"
        )
        var selectedColor = faction?.color ?: "#2196F3"

        // 색상 미리보기 초기화
        val density = resources.displayMetrics.density
        val previewBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            try { setColor(Color.parseColor(selectedColor)) } catch (_: Exception) { setColor(Color.LTGRAY) }
            setStroke((1 * density).toInt(), Color.GRAY)
        }
        colorPreview.background = previewBg
        hexEdit.setText(selectedColor)

        // 색상 미리보기 업데이트 함수
        fun updateColorPreview(color: String) {
            selectedColor = color
            try { previewBg.setColor(Color.parseColor(color)) } catch (_: Exception) {}
        }

        // HEX 입력 동기화
        hexEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hex = s?.toString()?.trim() ?: ""
                if (hex.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                    updateColorPreview(hex)
                }
            }
        })

        // 색상 팔레트 생성
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
                    updateColorPreview(color)
                    hexEdit.setText(color)
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

        // 전체 색상 선택 버튼
        btnFullSpectrum.setOnClickListener {
            com.novelcharacter.app.util.ColorPickerHelper.showFullSpectrumColorPicker(requireContext(), selectedColor) { newColor ->
                updateColorPreview(newColor)
                hexEdit.setText(newColor)
                // 프리셋 테두리 모두 해제 (커스텀 색상이므로)
                colorViews.forEachIndexed { i, v ->
                    (v.background as? GradientDrawable)?.setStroke(0, Color.BLACK)
                }
            }
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

    // ===== 세력 옵션 (편집/관계/삭제) =====

    private fun showFactionOptionsDialog(faction: Faction) {
        val items = arrayOf(
            getString(R.string.edit),
            getString(R.string.faction_relationships),
            getString(R.string.delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(faction.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showFactionEditDialog(faction)
                    1 -> showFactionRelationshipsDialog(faction)
                    2 -> showDeleteFactionDialog(faction)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ===== 세력 간 관계 (B-3) =====

    private val factionRepository
        get() = (requireActivity().application as com.novelcharacter.app.NovelCharacterApp).factionRepository

    /** 세력의 관계 목록 다이얼로그 — 항목 클릭 시 편집/삭제, 버튼으로 추가 */
    private fun showFactionRelationshipsDialog(faction: Faction) {
        viewLifecycleOwner.lifecycleScope.launch {
            val relationships = factionRepository.getFactionRelationshipsForFactionList(faction.id)
            val factions = factionRepository.getFactionsByUniverseList(universeId)
            if (!isAdded) return@launch
            val nameById = factions.associateBy({ it.id }, { it.name })

            val labels = relationships.map { rel ->
                val otherId = if (rel.factionId1 == faction.id) rel.factionId2 else rel.factionId1
                val otherName = nameById[otherId] ?: "?"
                val directed = when {
                    rel.isBidirectional -> getString(R.string.faction_relationship_direction_both, otherName)
                    rel.factionId1 == faction.id -> getString(R.string.faction_relationship_direction_to, otherName)
                    else -> getString(R.string.faction_relationship_direction_from, otherName)
                }
                getString(R.string.faction_relationship_item_format, directed, rel.relationType, rel.intensity)
            }

            val builder = AlertDialog.Builder(requireContext())
                .setTitle("${getString(R.string.faction_relationships)} — ${faction.name}")
            if (labels.isEmpty()) {
                builder.setMessage(R.string.faction_relationship_empty)
            } else {
                builder.setItems(labels.toTypedArray()) { _, which ->
                    showFactionRelationshipOptions(faction, relationships[which], nameById)
                }
            }
            builder.setPositiveButton(R.string.faction_relationship_add) { _, _ ->
                showFactionRelationshipEditDialog(faction, existing = null, otherName = null)
            }
            builder.setNegativeButton(R.string.cancel, null)
            builder.show()
        }
    }

    private fun showFactionRelationshipOptions(
        faction: Faction,
        relationship: com.novelcharacter.app.data.model.FactionRelationship,
        nameById: Map<Long, String>
    ) {
        val otherId = if (relationship.factionId1 == faction.id) relationship.factionId2 else relationship.factionId1
        AlertDialog.Builder(requireContext())
            .setTitle(relationship.relationType)
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> showFactionRelationshipEditDialog(faction, relationship, nameById[otherId])
                    1 -> AlertDialog.Builder(requireContext())
                        .setMessage(R.string.faction_relationship_delete_confirm)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                factionRepository.deleteFactionRelationship(relationship)
                                if (isAdded) showFactionRelationshipsDialog(faction)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 관계 추가/편집 다이얼로그.
     * 신규: 상대 세력 스피너 선택. 편집: 상대는 고정, 유형/설명/강도/방향만 수정.
     */
    private fun showFactionRelationshipEditDialog(
        faction: Faction,
        existing: com.novelcharacter.app.data.model.FactionRelationship?,
        otherName: String?
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val others = factionRepository.getFactionsByUniverseList(universeId)
                .filter { it.id != faction.id }
            if (!isAdded) return@launch
            if (existing == null && others.isEmpty()) {
                Toast.makeText(requireContext(), R.string.faction_relationship_need_two, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val ctx = requireContext()
            val density = resources.displayMetrics.density
            val pad = (20 * density).toInt()
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, (8 * density).toInt(), pad, 0)
            }

            // 상대 세력 — 신규일 때만 선택 가능
            val targetSpinner = android.widget.Spinner(ctx)
            if (existing == null) {
                val spinnerAdapter = android.widget.ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_item, others.map { it.name }
                )
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                targetSpinner.adapter = spinnerAdapter
                container.addView(android.widget.TextView(ctx).apply {
                    text = getString(R.string.faction_relationship_target)
                    textSize = 13f
                })
                container.addView(targetSpinner)
            }

            val editType = EditText(ctx).apply {
                hint = getString(R.string.faction_relationship_type_hint)
                setText(existing?.relationType ?: "")
                isSingleLine = true
            }
            container.addView(editType)

            val editDesc = EditText(ctx).apply {
                hint = getString(R.string.faction_relationship_desc_hint)
                setText(existing?.description ?: "")
            }
            container.addView(editDesc)

            val intensityLabel = android.widget.TextView(ctx).apply {
                text = getString(R.string.faction_relationship_intensity, existing?.intensity ?: 5)
                textSize = 13f
            }
            container.addView(intensityLabel)
            val intensitySlider = Slider(ctx).apply {
                valueFrom = 1f
                valueTo = 10f
                stepSize = 1f
                value = (existing?.intensity ?: 5).toFloat()
                addOnChangeListener { _, value, _ ->
                    intensityLabel.text = getString(R.string.faction_relationship_intensity, value.toInt())
                }
            }
            container.addView(intensitySlider)

            val bidirectionalCheck = CheckBox(ctx).apply {
                text = getString(R.string.faction_relationship_bidirectional)
                isChecked = existing?.isBidirectional ?: true
            }
            container.addView(bidirectionalCheck)

            val title = if (existing == null) {
                getString(R.string.faction_relationship_add)
            } else {
                "${getString(R.string.faction_relationship_edit)} — ${otherName ?: ""}"
            }

            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(R.string.save) { _, _ ->
                    val type = editType.text.toString().trim()
                    if (type.isEmpty()) {
                        Toast.makeText(ctx, R.string.faction_relationship_type_required, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        val duplicated: Boolean = if (existing == null) {
                            val target = others.getOrNull(targetSpinner.selectedItemPosition)
                                ?: return@launch
                            val inserted = factionRepository.insertFactionRelationship(
                                com.novelcharacter.app.data.model.FactionRelationship(
                                    factionId1 = faction.id,
                                    factionId2 = target.id,
                                    relationType = type,
                                    description = editDesc.text.toString().trim(),
                                    intensity = intensitySlider.value.toInt(),
                                    isBidirectional = bidirectionalCheck.isChecked
                                )
                            )
                            inserted == -1L
                        } else {
                            try {
                                factionRepository.updateFactionRelationship(
                                    existing.copy(
                                        relationType = type,
                                        description = editDesc.text.toString().trim(),
                                        intensity = intensitySlider.value.toInt(),
                                        isBidirectional = bidirectionalCheck.isChecked
                                    )
                                )
                                false
                            } catch (e: Exception) {
                                // (세력쌍, 유형) 유니크 충돌
                                true
                            }
                        }
                        if (!isAdded) return@launch
                        if (duplicated) {
                            Toast.makeText(requireContext(), R.string.faction_relationship_duplicate, Toast.LENGTH_SHORT).show()
                        }
                        showFactionRelationshipsDialog(faction)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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
                val joinYear = item.membership.joinYear
                if (joinYear != null && year < joinYear) {
                    Toast.makeText(ctx, "탈퇴 연도($year)는 가입 연도($joinYear) 이후여야 합니다.", Toast.LENGTH_LONG).show()
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
            val dp = { value: Int -> (value * density).toInt() }

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), 0)
            }

            // 가입 연도 입력 (선택)
            val editYear = EditText(ctx).apply {
                hint = getString(R.string.faction_join_year_optional)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            container.addView(editYear)

            // 검색창
            val searchEdit = EditText(ctx).apply {
                hint = getString(R.string.name_search_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setSingleLine()
            }
            container.addView(searchEdit)

            // 캐릭터 체크박스 목록 (검색 필터링 지원)
            val selectedIds = mutableSetOf<Long>()
            var filteredCharacters = allCharacters.toList()

            val listContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val scrollView = android.widget.ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(250)
                )
                addView(listContainer)
            }
            container.addView(scrollView)

            fun rebuildList() {
                listContainer.removeAllViews()
                for (char in filteredCharacters) {
                    val checkBox = CheckBox(ctx).apply {
                        text = char.name
                        isChecked = char.id in selectedIds
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) selectedIds.add(char.id) else selectedIds.remove(char.id)
                        }
                    }
                    listContainer.addView(checkBox)
                }
            }

            rebuildList()

            searchEdit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    filteredCharacters = if (query.isEmpty()) {
                        allCharacters.toList()
                    } else {
                        allCharacters.filter { it.name.contains(query, ignoreCase = true) }
                    }
                    rebuildList()
                }
            })

            AlertDialog.Builder(ctx)
                .setTitle(R.string.faction_member_add)
                .setView(container)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    if (selectedIds.isEmpty()) return@setPositiveButton

                    val joinYear = editYear.text.toString().trim().toIntOrNull()
                    viewModel.addMembers(faction.id, selectedIds.toList(), joinYear) { result ->
                        if (!isAdded) return@addMembers
                        // 수동 관계 우선 정책: 건너뛴 자동관계는 반드시 통보 (조용한 불일치 방지)
                        val parts = mutableListOf<String>()
                        if (result.added > 0) parts.add(getString(R.string.faction_members_added, result.added))
                        if (result.autoRelationsSkipped > 0) {
                            parts.add(getString(R.string.faction_auto_relation_skipped, result.autoRelationsSkipped))
                        }
                        if (parts.isNotEmpty()) {
                            val duration = if (result.autoRelationsSkipped > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                            Toast.makeText(ctx, parts.joinToString("\n"), duration).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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
