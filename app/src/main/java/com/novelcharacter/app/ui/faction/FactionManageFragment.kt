package com.novelcharacter.app.ui.faction

import android.graphics.Color
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.databinding.FragmentFactionManageBinding
import com.novelcharacter.app.ui.adapter.FactionAdapter
import com.novelcharacter.app.ui.adapter.FactionMemberAdapter
import com.novelcharacter.app.ui.adapter.FactionMemberItem
import com.novelcharacter.app.util.FactionStanding
import com.novelcharacter.app.util.MembershipTimeline
import com.novelcharacter.app.util.dismissSafely
import com.novelcharacter.app.util.factionSpanLabel
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
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
        // B-28: 이름·관계 유형 검증이 토스트 후 자동 닫힘이라, 한 칸만 비어도 고른 색·강도·설명이
        // 함께 사라졌다. 실패 시 창을 유지하고 빈 칸에 오류를 건다.
        val layoutName = dialogView.findViewById<TextInputLayout>(R.id.layoutFactionName)
        val layoutRelationType = dialogView.findViewById<TextInputLayout>(R.id.layoutAutoRelationType)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setValidatedPositiveButton {
            val name = editName.text.toString().trim()
            val description = editDescription.text.toString().trim()
            val autoRelationType = editAutoRelationType.text.toString().trim()
            val intensity = sliderIntensity.value.toInt()

            if (name.isEmpty()) {
                layoutName.showInlineError(getString(R.string.faction_name_required))
                return@setValidatedPositiveButton false
            }
            if (autoRelationType.isEmpty()) {
                layoutRelationType.showInlineError(getString(R.string.faction_relation_type_required))
                return@setValidatedPositiveButton false
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
            true
        }
        dialog.show()
    }

    // ===== 세력 옵션 (편집/관계/삭제) =====

    private fun showFactionOptionsDialog(faction: Faction) {
        val items = arrayOf(
            getString(R.string.edit),
            getString(R.string.faction_relationships),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(requireContext())
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

            val builder = MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(relationship.relationType)
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> showFactionRelationshipEditDialog(faction, relationship, nameById[otherId])
                    1 -> MaterialAlertDialogBuilder(requireContext())
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

            // B-28: 유형 검증은 토스트 후 닫힘이었고, 중복 판정은 창이 닫힌 **뒤** 비동기로 나서
            // 사용자가 적은 설명·강도가 두 경로 모두에서 사라졌다. 동기 검증은 창을 유지하고,
            // 비동기 판정은 결과를 받은 뒤에 닫는다(성공일 때만).
            val relationshipDialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            // 창을 살려 두면 저장 중에 한 번 더 누를 수 있다 — 종전에는 즉시 닫혀서 불가능했던 창이다.
            var saving = false
            relationshipDialog.setValidatedPositiveButton {
                    if (saving) return@setValidatedPositiveButton false
                    val type = editType.text.toString().trim()
                    if (type.isEmpty()) {
                        editType.showInlineError(getString(R.string.faction_relationship_type_required))
                        return@setValidatedPositiveButton false
                    }
                    // 저장할 것은 코루틴에 들어가기 **전에** 짓는다 — 창이 살아 있는 동안 사용자가
                    // 슬라이더를 더 움직이면, 중단 뒤에 읽는 값은 누른 순간의 값이 아니다.
                    val toInsert = if (existing == null) {
                        val target = others.getOrNull(targetSpinner.selectedItemPosition)
                        if (target == null) {
                            editType.showInlineError(getString(R.string.faction_relationship_need_two))
                            return@setValidatedPositiveButton false
                        }
                        com.novelcharacter.app.data.model.FactionRelationship(
                            factionId1 = faction.id,
                            factionId2 = target.id,
                            relationType = type,
                            description = editDesc.text.toString().trim(),
                            intensity = intensitySlider.value.toInt(),
                            isBidirectional = bidirectionalCheck.isChecked
                        )
                    } else null
                    val toUpdate = existing?.copy(
                        relationType = type,
                        description = editDesc.text.toString().trim(),
                        intensity = intensitySlider.value.toInt(),
                        isBidirectional = bidirectionalCheck.isChecked
                    )
                    saving = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        // finally로 되돌린다 — 저장이 예외로 끝나면 잠금이 남아 창이 영영 반응하지
                        // 않는다(창을 살려 둔 대가로 새로 생긴 자리다).
                        val duplicated: Boolean = try {
                            when {
                                toInsert != null ->
                                    factionRepository.insertFactionRelationship(toInsert) == -1L
                                toUpdate != null -> try {
                                    factionRepository.updateFactionRelationship(toUpdate)
                                    false
                                } catch (e: Exception) {
                                    // (세력쌍, 유형) 유니크 충돌
                                    true
                                }
                                else -> false
                            }
                        } finally {
                            saving = false
                        }
                        if (!isAdded) return@launch
                        if (duplicated) {
                            // 중복이면 창을 살려 둔다 — 적어 둔 설명·강도로 유형만 고쳐 다시 저장할 수 있다.
                            editType.showInlineError(getString(R.string.faction_relationship_duplicate))
                            return@launch
                        }
                        relationshipDialog.dismissSafely()
                        showFactionRelationshipsDialog(faction)
                    }
                    // 저장 여부는 위 코루틴이 정한다 — 여기서 닫으면 중복 판정을 받을 창이 사라진다.
                    false
                }
            relationshipDialog.show()
        }
    }

    /**
     * 세력 삭제 확인 — **무엇이 함께 사라지는지 세어서 먼저 말한다** (R-4).
     *
     * 형제 여섯 창(작품·세계관·캐릭터·사건·필드·관계)은 전부 규모를 말하는데 이 창만
     * 선택지 둘을 내놓고 끝이었다. 그런데 **기본 선택이 캐릭터 관계까지 지우는 쪽**이라,
     * 무심코 확인을 누르면 세력과 무관한 자동 관계가 함께 사라진다.
     *
     * 자동 관계 줄은 다른 둘과 성질이 다르므로 그 사실을 문구가 말한다 — 그 줄만
     * **고른 쪽에 따라** 삭제되거나 수동 관계로 남는다.
     */
    private fun showDeleteFactionDialog(faction: Faction) {
        viewLifecycleOwner.lifecycleScope.launch {
            val impact = runCatching { viewModel.getFactionDeleteImpact(faction.id) }
                .getOrDefault(com.novelcharacter.app.data.repository.FactionDeleteImpact())
            // 컨텍스트는 **정지점 뒤에** 잡는다 — 세는 사이에 화면이 사라질 수 있다.
            if (!isAdded) return@launch

            val lines = listOfNotNull(
                impact.members.takeIf { it > 0 }
                    ?.let { "  • " + getString(R.string.faction_delete_impact_members, it) },
                impact.factionRelations.takeIf { it > 0 }
                    ?.let { "  • " + getString(R.string.faction_delete_impact_faction_relations, it) },
                impact.autoRelations.takeIf { it > 0 }
                    ?.let { "  • " + getString(R.string.faction_delete_impact_auto_relations, it) }
            )
            val message = if (lines.isEmpty()) {
                getString(R.string.faction_delete_plain_message)
            } else {
                getString(R.string.faction_delete_impact_message, lines.joinToString("\n"))
            }

            // **`setMessage`와 `setSingleChoiceItems`를 함께 쓰지 않는다.** `AlertController`는
            // 메시지가 있으면 목록을 화면에 붙이지 않는다 — 그러면 선택지 둘이 통째로 사라진
            // 채 [삭제]가 기본값(관계까지 삭제)으로 실행된다. 고지를 더하려다 죽은 선택지를
            // 만드는 셈이라, 둘을 한 뷰에 담는다(이 파일의 다른 창들이 쓰는 그 꼴).
            val ctx = requireContext()
            val density = resources.displayMetrics.density
            val pad = (20 * density).toInt()
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, (8 * density).toInt(), pad, 0)
            }
            container.addView(android.widget.TextView(ctx).apply {
                text = message
                textSize = 14f
            })
            val choices = android.widget.RadioGroup(ctx).apply {
                orientation = android.widget.RadioGroup.VERTICAL
                val topMargin = (12 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, topMargin, 0, 0) }
            }
            // 고른 것은 **id가 아니라 상태 변수**로 든다 — 손으로 매긴 뷰 id는 다른 뷰와
            // 겹칠 수 있고, 0은 프레임워크가 '없음'과 헷갈리기 쉬운 값이다.
            var deleteRelations = true
            val withRelations = android.widget.RadioButton(ctx).apply {
                id = View.generateViewId()
                text = getString(R.string.faction_delete_with_relations)
                textSize = 14f
            }
            val keepRelations = android.widget.RadioButton(ctx).apply {
                id = View.generateViewId()
                text = getString(R.string.faction_delete_keep_relations)
                textSize = 14f
            }
            choices.addView(withRelations)
            choices.addView(keepRelations)
            choices.setOnCheckedChangeListener { _, checkedId ->
                deleteRelations = checkedId == withRelations.id
            }
            // 기본 선택은 종전과 같은 자리다 — 사용자가 익힌 손이 그대로 맞아야 한다.
            choices.check(withRelations.id)
            container.addView(choices)

            MaterialAlertDialogBuilder(ctx)
                .setIcon(R.drawable.ic_warning)
                .setTitle(getString(R.string.faction_delete_title, faction.name))
                .setView(container)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.deleteFaction(faction, deleteRelationships = deleteRelations)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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
            onSelect = { item -> showMemberOptionsDialog(faction, item) }
        )
        memberRecyclerView.adapter = memberAdapter
        container.addView(memberRecyclerView)

        val dialog = MaterialAlertDialogBuilder(ctx)
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
        // 종전에는 탈퇴한 멤버를 길게 누르면 **아무 일도 일어나지 않았다**(조용한 무동작).
        // 화면상 눌리기는 하므로 사용자는 앱이 멈춘 줄 안다 — R-27과 같은 부류다.
        // 끝난 소속에는 끝난 소속에 맞는 선택지를 준다.
        if (!FactionStanding.isCurrent(item.membership)) {
            showDepartedMemberOptionsDialog(faction, item)
            return
        }

        val options = arrayOf(
            getString(R.string.faction_join_year_edit),
            getString(R.string.faction_member_remove),
            getString(R.string.faction_member_depart)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.characterName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showJoinYearEditDialog(faction, item)
                    1 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.faction_member_remove)
                            .setMessage(getString(R.string.faction_member_remove_confirm, item.characterName, faction.name))
                            .setPositiveButton(R.string.delete) { _, _ ->
                                viewModel.removeMember(faction.id, item.membership.characterId)
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    2 -> showDepartureDialog(faction, item)
                }
            }
            .show()
    }

    /**
     * 끝난 소속(탈퇴/제거)의 선택지 — 다시 소속시키거나, 기록을 지운다.
     * 재가입은 [showAddMemberDialog]와 **같은 저장소 경로**(`addMembers`)를 쓴다.
     * 새 소속 행이 하나 생기고 이전 탈퇴 행은 남는다 — 그것이 이 앱의 소속 이력 규약이다
     * (`FactionMembership`에 유니크 제약이 없는 이유).
     */
    private fun showDepartedMemberOptionsDialog(faction: Faction, item: FactionMemberItem) {
        val options = arrayOf(
            getString(R.string.faction_departure_edit),
            getString(R.string.faction_member_rejoin),
            getString(R.string.faction_membership_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.characterName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showDepartureEditDialog(faction, item)
                    1 -> showRejoinDialog(faction, item)
                    2 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.faction_membership_delete)
                        .setMessage(getString(R.string.faction_membership_delete_confirm, item.characterName))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            viewModel.deleteMembershipRecord(item.membership.id)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 소속 구간을 사람이 읽는 말로 — 겹침 고지에 쓴다.
     * 표기는 캐릭터 상세의 세력 칩과 **같은 자리**에서 가져온다(각자 조립하면 갈라진다).
     */
    private fun spanLabel(span: MembershipTimeline.Span): String =
        requireContext().factionSpanLabel(span.joinYear, span.leaveYear)
            ?: getString(R.string.faction_span_unknown)

    /**
     * 끝난 소속(탈퇴) 기록 편집 — 네 값 전부.
     * 활성 멤버는 가입 연도만 고치면 되지만, 끝난 소속에는 사용자가 정한 값이 넷 있고
     * 종전에는 **하나도 고칠 수 없었다**(잘못 적었으면 지우고 처음부터 다시).
     */
    private fun showDepartureEditDialog(faction: Faction, item: FactionMemberItem) =
        viewLifecycleOwner.lifecycleScope.launch {
            val others = viewModel.otherSpansFor(
                faction.id, item.membership.characterId, item.membership.id)
            if (!isAdded) return@launch
            val ctx = requireContext()
            val density = resources.displayMetrics.density
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
            }
            val editJoin = EditText(ctx).apply {
                hint = getString(R.string.faction_join_year_optional)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(item.membership.joinYear?.toString() ?: "")
            }
            container.addView(editJoin)
            val editLeave = EditText(ctx).apply {
                hint = getString(R.string.faction_leave_year)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(item.membership.leaveYear?.toString() ?: "")
            }
            container.addView(editLeave)
            val editRelType = EditText(ctx).apply {
                hint = getString(R.string.faction_departed_relation)
                setText(item.membership.departedRelationType ?: "전 ${faction.autoRelationType}")
            }
            container.addView(editRelType)
            container.addView(android.widget.TextView(ctx).apply {
                text = getString(R.string.faction_auto_relation_intensity)
                setPadding(0, (12 * density).toInt(), 0, 0)
            })
            val sliderIntensity = Slider(ctx).apply {
                valueFrom = 1f; valueTo = 10f; stepSize = 1f
                value = (item.membership.departedIntensity ?: 1).coerceIn(1, 10).toFloat()
            }
            container.addView(sliderIntensity)
            container.addView(android.widget.TextView(ctx).apply {
                text = getString(R.string.faction_departure_edit_hint)
                textSize = 12f
                setPadding(0, (8 * density).toInt(), 0, 0)
            })

            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.faction_departure_edit)
                .setView(container)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.setValidatedPositiveButton {
                val joinText = editJoin.text.toString().trim()
                val join = if (joinText.isEmpty()) null else joinText.toIntOrNull()
                if (joinText.isNotEmpty() && join == null) {
                    editJoin.showInlineError(getString(R.string.faction_join_year_invalid))
                    return@setValidatedPositiveButton false
                }
                val leave = editLeave.text.toString().trim().toIntOrNull()
                if (leave == null) {
                    editLeave.showInlineError(getString(R.string.faction_leave_year_required))
                    return@setValidatedPositiveButton false
                }
                // 만들 때와 달리 앞뒤 양쪽에 다른 소속이 있을 수 있어 구간 겹침으로 본다.
                when (val v = MembershipTimeline.validateSpan(
                    MembershipTimeline.Span(join, leave), others)) {
                    is MembershipTimeline.SpanVerdict.JoinNotBeforeLeave -> {
                        editJoin.showInlineError(getString(
                            R.string.faction_join_not_before_leave, v.joinYear, v.leaveYear))
                        return@setValidatedPositiveButton false
                    }
                    is MembershipTimeline.SpanVerdict.OverlapsOther -> {
                        editLeave.showInlineError(getString(
                            R.string.faction_span_overlaps, spanLabel(v.other)))
                        return@setValidatedPositiveButton false
                    }
                    MembershipTimeline.SpanVerdict.Ok -> Unit
                }
                val relType = editRelType.text.toString().trim()
                    .ifEmpty { "전 ${faction.autoRelationType}" }
                viewModel.updateDepartedMembership(
                    item.membership.id, join, leave, relType, sliderIntensity.value.toInt()
                ) { moved ->
                    if (!isAdded || moved <= 0) return@updateDepartedMembership
                    Toast.makeText(ctx, getString(
                        R.string.faction_departure_relation_changes_moved, moved),
                        Toast.LENGTH_LONG).show()
                }
                true
            }
            dialog.show()
        }

    /**
     * 가입 연도 편집 — 비우면 '시점 불명'으로 되돌린다. 검증 실패 시 창을 유지한다(R-27).
     * 겹침 판정은 탈퇴 기록 편집과 **같은 규칙**을 쓴다 — 이 캐릭터가 같은 세력에 여러 번
     * 소속됐다면 가입 연도를 앞으로 당기다 옛 소속을 침범할 수 있다.
     */
    private fun showJoinYearEditDialog(faction: Faction, item: FactionMemberItem) =
        viewLifecycleOwner.lifecycleScope.launch {
        val others = viewModel.otherSpansFor(faction.id, item.membership.characterId, item.membership.id)
        if (!isAdded) return@launch
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
        }
        val editYear = EditText(ctx).apply {
            hint = getString(R.string.faction_join_year_optional)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(item.membership.joinYear?.toString() ?: "")
        }
        container.addView(editYear)
        container.addView(android.widget.TextView(ctx).apply {
            text = getString(R.string.faction_join_year_edit_hint)
            textSize = 12f
            setPadding(0, (8 * density).toInt(), 0, 0)
        })

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.faction_join_year_edit)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setValidatedPositiveButton {
            val text = editYear.text.toString().trim()
            val year = if (text.isEmpty()) null else text.toIntOrNull()
            if (text.isNotEmpty() && year == null) {
                editYear.showInlineError(getString(R.string.faction_join_year_invalid))
                return@setValidatedPositiveButton false
            }
            when (val v = MembershipTimeline.validateSpan(
                MembershipTimeline.Span(year, item.membership.leaveYear), others)) {
                is MembershipTimeline.SpanVerdict.JoinNotBeforeLeave -> {
                    editYear.showInlineError(getString(
                        R.string.faction_join_not_before_leave, v.joinYear, v.leaveYear))
                    return@setValidatedPositiveButton false
                }
                is MembershipTimeline.SpanVerdict.OverlapsOther -> {
                    editYear.showInlineError(getString(
                        R.string.faction_span_overlaps, spanLabel(v.other)))
                    return@setValidatedPositiveButton false
                }
                MembershipTimeline.SpanVerdict.Ok -> Unit
            }
            viewModel.updateJoinYear(item.membership.id, year)
            true
        }
        dialog.show()
    }

    /** 재가입 — 새 소속을 만든다. 이전 탈퇴 기록은 이력으로 남는다. */
    private fun showRejoinDialog(faction: Faction, item: FactionMemberItem) = viewLifecycleOwner.lifecycleScope.launch {
        // 새 소속은 **마지막 탈퇴 뒤**여야 한다 — 앞이면 같은 해에 나가 있으면서 들어와 있게 된다.
        // 사용자가 옛 탈퇴 줄을 눌러 재가입할 수 있으므로 그 줄이 아니라 가장 늦은 탈퇴를 본다.
        val previousLeave = viewModel.latestLeaveYearFor(faction.id, item.membership.characterId)
        if (!isAdded) return@launch
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
        }
        val editYear = EditText(ctx).apply {
            hint = getString(R.string.faction_join_year_optional)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        container.addView(editYear)
        container.addView(android.widget.TextView(ctx).apply {
            text = getString(R.string.faction_member_rejoin_notice)
            textSize = 12f
            setPadding(0, (8 * density).toInt(), 0, 0)
        })

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.faction_member_rejoin))
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setValidatedPositiveButton {
            val text = editYear.text.toString().trim()
            val year = if (text.isEmpty()) null else text.toIntOrNull()
            if (text.isNotEmpty() && year == null) {
                editYear.showInlineError(getString(R.string.faction_join_year_invalid))
                return@setValidatedPositiveButton false
            }
            when (val verdict = MembershipTimeline.validateJoinYear(year, previousLeave)) {
                is MembershipTimeline.JoinYearVerdict.Required -> {
                    editYear.showInlineError(getString(
                        R.string.faction_join_year_required_after_leave, verdict.previousLeaveYear))
                    return@setValidatedPositiveButton false
                }
                is MembershipTimeline.JoinYearVerdict.BeforePreviousLeave -> {
                    editYear.showInlineError(getString(
                        R.string.faction_join_year_before_leave,
                        verdict.joinYear, verdict.previousLeaveYear))
                    return@setValidatedPositiveButton false
                }
                MembershipTimeline.JoinYearVerdict.Ok -> Unit
            }
            // 멤버 추가와 같은 경로 — 자동 관계 생성 규칙도 그대로 따라간다.
            viewModel.addMembers(faction.id, listOf(item.membership.characterId), year) { result ->
                if (!isAdded) return@addMembers
                val parts = mutableListOf<String>()
                if (result.added > 0) parts.add(getString(R.string.faction_member_rejoined))
                // 탈퇴는 관계를 지우지 않고 변화 이력만 남기므로, 재가입 때 자동 관계가
                // 이미 있는 것이 정상이다 — '수동 관계 우선'과 사유가 달라 문구를 가른다.
                if (result.autoRelationsSkipped > 0) {
                    parts.add(getString(R.string.faction_auto_relation_kept, result.autoRelationsSkipped))
                }
                if (result.alreadyMember > 0) {
                    parts.add(getString(R.string.faction_members_already, result.alreadyMember))
                }
                if (parts.isNotEmpty()) {
                    Toast.makeText(ctx, parts.joinToString("\n"), Toast.LENGTH_LONG).show()
                }
            }
            true
        }
        dialog.show()
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

        // B-28: 연도 검증 둘 다 토스트 후 닫힘이라, 연도를 잘못 적으면 고쳐 쓴 관계 유형·강도까지
        // 함께 사라졌다. 실패 시 창을 유지하고 연도 칸에 오류를 건다.
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.faction_member_depart))
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setValidatedPositiveButton {
            val yearStr = editYear.text.toString().trim()
            val year = yearStr.toIntOrNull()
            if (year == null) {
                editYear.showInlineError(getString(R.string.faction_leave_year_required))
                return@setValidatedPositiveButton false
            }
            val joinYear = item.membership.joinYear
            if (joinYear != null && year < joinYear) {
                editYear.showInlineError(
                    getString(R.string.faction_leave_year_before_join, year, joinYear)
                )
                return@setValidatedPositiveButton false
            }
            val relType = editRelType.text.toString().trim().ifEmpty { "전 ${faction.autoRelationType}" }
            val intensity = sliderIntensity.value.toInt()

            viewModel.departMember(faction.id, item.membership.characterId, year, relType, intensity)
            true
        }
        dialog.show()
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

            // 후보 목록은 세계관 캐릭터 전체다 — 종전에는 **이미 소속 중인 사람과 탈퇴한 사람,
            // 한 번도 소속된 적 없는 사람이 전부 똑같이 보였다.** 그래서 재가입이 되는데도
            // 그것이 재가입인지 알 수 없었고, 이미 소속된 사람을 골라도 조용히 건너뛰었다.
            val memberStates = viewModel.getMembershipSummaryForFaction(faction.id)

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

            // scroll-cap-exempt: 다이얼로그 본문이 아니라 본문 안의 한 칸이고, 높이가 이미
            // dp(250)으로 묶여 있다 — 검색으로 목록이 바뀌어도 창 높이가 흔들리지 않게 하려는
            // 고정이다(B-91 판정: 상한 없음이 아니라 다른 상한).
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
                        text = when (memberStates[char.id]?.state) {
                            MemberState.ACTIVE ->
                                "${char.name} · ${getString(R.string.faction_member_state_active)}"
                            MemberState.DEPARTED ->
                                "${char.name} · ${getString(R.string.faction_member_state_departed)}"
                            else -> char.name
                        }
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

            // B-28: 아무도 고르지 않은 채 확인을 누르면 **아무 말 없이** 닫혔다 — 사용자는 추가된 줄
            // 알았다(무통보 조기 return). 이제 창을 유지하고 무엇이 빠졌는지 말한다.
            val memberDialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.faction_member_add)
                .setView(container)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            memberDialog.setValidatedPositiveButton {
                    if (selectedIds.isEmpty()) {
                        Toast.makeText(ctx, R.string.faction_member_select_required, Toast.LENGTH_SHORT).show()
                        return@setValidatedPositiveButton false
                    }

                    val yearText = editYear.text.toString().trim()
                    val joinYear = if (yearText.isEmpty()) null else yearText.toIntOrNull()
                    if (yearText.isNotEmpty() && joinYear == null) {
                        editYear.showInlineError(getString(R.string.faction_join_year_invalid))
                        return@setValidatedPositiveButton false
                    }

                    // 이 창도 재가입의 문이다 — 탈퇴자를 다시 고르면 새 소속이 생긴다.
                    // 규칙을 여기에만 안 걸면 같은 모순이 다른 문으로 들어온다.
                    val conflicts = selectedIds.mapNotNull { id ->
                        val prev = memberStates[id]?.latestLeaveYear ?: return@mapNotNull null
                        val verdict = MembershipTimeline.validateJoinYear(joinYear, prev)
                        if (verdict is MembershipTimeline.JoinYearVerdict.Ok) null
                        else (allCharacters.firstOrNull { it.id == id }?.name ?: "") to prev
                    }
                    if (conflicts.isNotEmpty()) {
                        val names = conflicts.map { it.first }.filter { it.isNotEmpty() }
                        val shown = if (names.size <= 2) names.joinToString(", ")
                        else getString(R.string.faction_join_year_conflict_more,
                            names.take(2).joinToString(", "), names.size - 2)
                        editYear.showInlineError(getString(
                            R.string.faction_join_year_conflict_members,
                            shown, conflicts.maxOf { it.second }))
                        return@setValidatedPositiveButton false
                    }
                    viewModel.addMembers(faction.id, selectedIds.toList(), joinYear) { result ->
                        if (!isAdded) return@addMembers
                        // 수동 관계 우선 정책: 건너뛴 자동관계는 반드시 통보 (조용한 불일치 방지)
                        val parts = mutableListOf<String>()
                        if (result.added > 0) parts.add(getString(R.string.faction_members_added, result.added))
                        if (result.autoRelationsSkipped > 0) {
                            parts.add(getString(R.string.faction_auto_relation_skipped, result.autoRelationsSkipped))
                        }
                        // 골랐는데 안 들어간 사람은 사유를 말한다 — 종전에는 "N명 추가"의 N에서만
                        // 빠져서, 고른 것과 결과가 왜 다른지 알 수 없었다.
                        if (result.alreadyMember > 0) {
                            parts.add(getString(R.string.faction_members_already, result.alreadyMember))
                        }
                        if (parts.isNotEmpty()) {
                            val long = result.autoRelationsSkipped > 0 || result.alreadyMember > 0
                            val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                            Toast.makeText(ctx, parts.joinToString("\n"), duration).show()
                        }
                    }
                    true
                }
            memberDialog.show()
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
