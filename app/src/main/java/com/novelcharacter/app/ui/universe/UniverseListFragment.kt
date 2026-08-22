package com.novelcharacter.app.ui.universe

import android.graphics.Color
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.novelcharacter.app.data.model.RecentActivity
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.repository.TrashRetentionPolicy
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentUniverseListBinding
import androidx.recyclerview.widget.ItemTouchHelper
import com.novelcharacter.app.ui.adapter.UniverseAdapter
import com.novelcharacter.app.ui.common.parseColorOrNull
import com.novelcharacter.app.util.PresetTemplates
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.notifyResult

class UniverseListFragment : Fragment() {

    private var _binding: FragmentUniverseListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UniverseViewModel by viewModels()

    private lateinit var adapter: UniverseAdapter
    private var recentAdapter: RecentActivityAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private val pendingImagePaths = mutableListOf<String>()
    private var universeImageRecyclerView: RecyclerView? = null
    private var universeImageAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val ctx = context?.applicationContext ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            // 공용 유틸로 라우팅 — 압축 설정(용량↔화질) 적용. 설정은 배치당 1회 로드.
            val settings = com.novelcharacter.app.util.ImageSettingsStore(ctx).getSettings()
            // **실패를 고지한다.** 종전에는 `savedPath == null`에 else가 없어 조용히 빠졌다 —
            // 5장 골라 3장만 붙어도 아무 말이 없고, 전부 실패하면 화면이 안 변해 **취소와
            // 구별되지 않았다.** 캐릭터 이미지 줄은 같은 유틸을 쓰면서 이미 고지하고 있었다
            // (`CharacterImageStripController`) — 같은 조작이 화면마다 다르게 굴면 안 된다.
            var anyFailed = false
            for (uri in uris) {
                val savedPath = try {
                    com.novelcharacter.app.util.ImageImportHelper.importImage(ctx, uri, "universe", settings)
                } catch (e: Exception) {
                    null
                }
                if (savedPath != null) {
                    pendingImagePaths.add(savedPath)
                } else {
                    anyFailed = true
                }
            }
            if (isAdded) {
                universeImageAdapter?.notifyDataSetChanged()
                if (pendingImagePaths.isNotEmpty()) {
                    universeImageRecyclerView?.visibility = View.VISIBLE
                }
                if (anyFailed) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        com.novelcharacter.app.R.string.image_save_failed,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private lateinit var excel: com.novelcharacter.app.excel.ExcelTransferController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 런처 등록 순서 보존을 위해 onCreate에서 생성 (컨트롤러 KDoc 참조)
        excel = com.novelcharacter.app.excel.ExcelTransferController(this)
        excel.restoreState(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUniverseListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        presetFieldSessionPresetId = savedInstanceState?.getLong(STATE_PRESET_FIELD_PRESET_ID, -1L) ?: -1L
        presetFieldSessionOriginalKey = savedInstanceState?.getString(STATE_PRESET_FIELD_ORIGINAL_KEY)
        setupPresetFieldResultListener()
        setupRecyclerView()
        setupFab()
        setupToolbarMenu()
        observeData()
    }

    /**
     * 프리셋 필드 편집 세션 (R-65).
     *
     * [com.novelcharacter.app.ui.field.FieldEditDialog]는 결과를 FragmentResult로 돌려주므로
     * 회전 뒤에도 도착하는데, 그때 프리셋 필드 목록 창(일반 AlertDialog)은 이미 사라져 있다.
     * 결과를 어느 프리셋의 어느 필드에 반영할지는 이 두 값이 말하고, 회전을 넘기기 위해
     * 인스턴스 상태에 담는다.
     */
    private var presetFieldSessionPresetId: Long = -1L

    /** 편집 중이던 필드의 **연 시점 키** — null이면 추가 모드다. */
    private var presetFieldSessionOriginalKey: String? = null

    /**
     * 프리셋 필드 목록 창이 살아 있는 동안의 반영 경로 — 창이 든 작업본 목록에 얹는다.
     * 창과 함께 죽는 값이라 인스턴스 상태에 담지 않는다(회전 뒤에는 저장본 직접 반영으로 간다).
     */
    private var presetFieldResultHandler: ((com.novelcharacter.app.data.model.FieldDefinition) -> Unit)? = null

    /**
     * [com.novelcharacter.app.ui.field.FieldEditDialog] 결과 수신 — 회전 뒤 재생성돼도 다시
     * 서도록 onViewCreated에서 등록한다(R-65. 종전의 콜백 배선은 회전이 지워, 재생성된
     * 다이얼로그에서 누른 [저장]이 허공으로 갔다).
     */
    private fun setupPresetFieldResultListener() {
        childFragmentManager.setFragmentResultListener(
            com.novelcharacter.app.ui.field.FieldEditDialog.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val json = bundle.getString(com.novelcharacter.app.ui.field.FieldEditDialog.RESULT_FIELD_JSON)
                ?: return@setFragmentResultListener
            val edited = com.google.gson.Gson()
                .fromJson(json, com.novelcharacter.app.data.model.FieldDefinition::class.java)
                ?: return@setFragmentResultListener
            val handler = presetFieldResultHandler
            if (handler != null) {
                handler(edited)
            } else {
                applyPresetFieldResultToStoredPreset(edited)
            }
        }
    }

    /**
     * 회전 뒤 도착한 프리셋 필드 결과의 반영.
     *
     * 프리셋 필드 목록 창(일반 AlertDialog)은 회전을 넘지 못하므로, 창이 없으면 결과를 저장된
     * 프리셋에 직접 반영한다 — 버리면 사용자가 [저장]까지 누른 입력이 말없이 사라진다(변수
     * 제어). 목록 창에서 저장을 기다리던 다른 편집분(순서 등)은 창과 함께 사라진 뒤라 여기서
     * 살릴 수 있는 것은 이 결과 하나다. 반영 통보는 [UniverseViewModel.updateUserPreset]의
     * 결과 채널이 한다.
     */
    private fun applyPresetFieldResultToStoredPreset(edited: com.novelcharacter.app.data.model.FieldDefinition) {
        val presetId = presetFieldSessionPresetId
        val originalKey = presetFieldSessionOriginalKey
        presetFieldSessionPresetId = -1L
        presetFieldSessionOriginalKey = null
        if (presetId == -1L) {
            // 세션 기록 없이 온 결과 — 정상 경로에는 없다. 조용히 버리는 대신 실패를 알린다.
            android.util.Log.w("UniverseListFragment", "Preset field result without session")
            Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val preset = viewModel.getUserPresetById(presetId)
            if (preset == null) {
                if (isAdded) Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val fields = PresetTemplates.fieldsFromJson(preset.fieldsJson).toMutableList()
            val editedIndex = if (originalKey != null) fields.indexOfFirst { it.key == originalKey } else -1
            // 다이얼로그의 점유 키 거부는 창을 연 시점의 목록 기준이다 — 저장본과 다시 대조한다(마지막 빗장).
            // **판정이 다이얼로그와 같아야 한다**(같은 대상끼리만 점유) — 여기만 넓게 보면
            // 창이 받아들인 키를 이 빗장이 다시 거절해 사용자가 고칠 수 없는 막다른 골목이 된다.
            val duplicated = fields.withIndex().any { (i, f) ->
                i != editedIndex && f.key == edited.key && f.entityType == edited.entityType
            }
            if (duplicated) {
                if (isAdded) Toast.makeText(requireContext(), R.string.preset_field_key_duplicate, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (editedIndex >= 0) fields[editedIndex] = edited else fields.add(edited)
            // displayOrder는 목록 창의 저장 단추와 같은 규칙으로 정규화한다.
            viewModel.updateUserPreset(preset.copy(
                fieldsJson = PresetTemplates.fieldsToJson(fields.mapIndexed { i, f -> f.copy(displayOrder = i) })
            ))
        }
    }

    private fun setupRecyclerView() {
        adapter = UniverseAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onClick = { universe ->
                viewModel.recordRecentActivity(RecentActivity.TYPE_UNIVERSE, universe.id, universe.name)
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.novelListFragment, bundle)
            },
            onEditClick = { universe ->
                showUniverseEditDialog(universe)
            },
            onDeleteClick = { universe ->
                // 계단식 삭제 범위(작품·캐릭터·사건·필드)를 집계해 사전 고지 — 말없는 유실 방지(변수 제어)
                viewLifecycleOwner.lifecycleScope.launch {
                    val impact = viewModel.getUniverseDeleteImpact(universe.id)
                    if (!isAdded) return@launch
                    val message = getString(R.string.confirm_delete_universe, universe.name) + "\n\n" +
                        getString(
                            R.string.delete_impact_universe,
                            impact.novels, impact.characters, impact.events,
                            impact.fieldDefinitions, impact.fieldValues,
                            // 보관 한도는 사용자가 정한다(B-74).
                            TrashRetentionPolicy.currentOrDefault().maxOperations,
                            TrashRetentionPolicy.currentOrDefault().retentionDays
                        )
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            viewModel.deleteUniverse(universe)
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
            },
            onFieldManageClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.fieldManageFragment, bundle)
            },
            onFactionManageClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.factionManageFragment, bundle)
            },
            onDuelClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.duelAxisListFragment, bundle)
            }
        )
        binding.universeRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.universeRecyclerView.adapter = adapter

        adapter.onOrderChanged = { reorderedList ->
            viewModel.updateDisplayOrders(reorderedList)
        }
        adapter.resolveRandomCharacterImage = { universeId, seed, callback ->
            viewModel.resolveRandomCharacterImage(universeId, seed, callback)
        }
        adapter.resolveCharacterImageById = { characterId, seed, callback ->
            viewModel.resolveCharacterImageById(characterId, seed, callback)
        }
        adapter.resolveRandomNovelImage = { universeId, seed, callback ->
            viewModel.resolveRandomNovelImage(universeId, seed, callback)
        }
        adapter.resolveNovelImageById = { novelId, seed, callback ->
            viewModel.resolveNovelImageById(novelId, seed, callback)
        }

        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.onDragCompleted()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.universeRecyclerView)
            adapter.itemTouchHelper = it
        }
    }

    private fun setupFab() {
        binding.fabAddUniverse.setOnClickListener {
            showUniverseEditDialog(null)
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_global_search -> {
                    // 죽은 아이콘 수정 — 랜딩 화면에서 검색 아이콘이 무반응이던 문제
                    findNavController().navigateSafe(R.id.universeListFragment, R.id.globalSearchFragment)
                    true
                }
                R.id.action_export -> {
                    exportToExcel()
                    true
                }
                R.id.action_import -> {
                    importFromExcel()
                    true
                }
                R.id.action_preset -> {
                    showPresetDialog()
                    true
                }
                R.id.action_reorder -> {
                    toggleReorderMode()
                    true
                }
                else -> false
            }
        }
    }

    /** 최신 사용자 프리셋 데이터 — observe 패턴 */
    private var cachedUserPresets: List<com.novelcharacter.app.data.model.UserPresetTemplate> = emptyList()

    private fun showPresetDialog() {
        // 빌트인 프리셋도 이제 DB에서 로드 — 모든 프리셋을 동일하게 처리
        val userPresetList = cachedUserPresets
        val allTemplates = userPresetList.map { PresetTemplates.fromUserPreset(it) }

        // 각 템플릿에 원본 UserPresetTemplate을 직접 매핑
        val presetByTemplateId = userPresetList.associateBy { it.id }

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_preset, null)
        bottomSheet.setContentView(sheetView)

        val recyclerView = sheetView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.presetRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        recyclerView.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class VH(val view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_preset_template, parent, false)
                return VH(v)
            }

            override fun getItemCount() = allTemplates.size

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val vh = holder as VH
                val t = allTemplates[position]
                val tag = vh.view.findViewById<TextView>(R.id.presetTag)
                val name = vh.view.findViewById<TextView>(R.id.presetName)
                val desc = vh.view.findViewById<TextView>(R.id.presetDescription)

                if (t.isBuiltIn) {
                    tag.text = getString(R.string.preset_tag_builtin)
                    tag.setBackgroundResource(R.drawable.bg_preset_tag_builtin)
                } else {
                    tag.text = getString(R.string.preset_tag_user)
                    tag.setBackgroundResource(R.drawable.bg_preset_tag_user)
                }
                name.text = t.universe.name
                desc.text = t.universe.description

                // 클릭 → 즉시 적용 (모든 프리셋 동일)
                vh.view.setOnClickListener {
                    bottomSheet.dismiss()
                    viewModel.applyPreset(t)
                }
                // 롱프레스 → 옵션 다이얼로그 (편집/삭제)
                vh.view.setOnLongClickListener {
                    val preset = t.userPresetId?.let { id -> presetByTemplateId[id] }
                    if (preset != null) {
                        bottomSheet.dismiss()
                        showUserPresetOptionsDialog(t, preset)
                    }
                    true
                }
            }
        }

        sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveAsPreset)
            .setOnClickListener {
                bottomSheet.dismiss()
                showSaveAsPresetDialog()
            }

        sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRestoreBuiltIn)?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                bottomSheet.dismiss()
                // 결과는 viewModel.result 채널이 실제 복원 건수와 함께 통보
                viewModel.restoreBuiltInPresets()
            }
        }

        bottomSheet.show()
    }

    private fun showUserPresetOptionsDialog(template: PresetTemplates.PresetTemplate, preset: com.novelcharacter.app.data.model.UserPresetTemplate) {
        val options = arrayOf(
            getString(R.string.preset_edit_name),
            getString(R.string.preset_edit_fields),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(preset.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditPresetNameDialog(preset)
                    1 -> showPresetFieldEditDialog(preset)
                    2 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.delete_warning_title)
                            .setMessage(getString(R.string.confirm_delete_preset, preset.name))
                            .setPositiveButton(R.string.yes) { _, _ ->
                                viewModel.deleteUserPreset(preset)
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditPresetNameDialog(preset: com.novelcharacter.app.data.model.UserPresetTemplate) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val dp24 = (24 * ctx.resources.displayMetrics.density).toInt()
            setPadding(dp24, dp24, dp24, 0)
        }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.preset_name_hint)
            setText(preset.name)
        }
        val descEdit = EditText(ctx).apply {
            hint = getString(R.string.preset_desc_hint)
            setText(preset.description)
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)

        // R-27(B-76): 종전에는 이름을 비운 채 저장을 누르면 **아무 말도 없이** 창이 닫히고
        // 설명까지 함께 사라졌다. 문구는 프리셋 이름 창들이 한 벌로 쓴다(`preset_name_required`).
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.preset_edit_name)
            .setView(layout)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val name = nameEdit.text.toString().trim()
            if (name.isEmpty()) {
                nameEdit.showInlineError(getString(R.string.preset_name_required))
                return@setValidatedPositiveButton false
            }
            viewModel.updateUserPreset(
                preset.copy(name = name, description = descEdit.text.toString().trim())
            )
            true
        }
        dialog.show()
    }

    private fun showPresetFieldEditDialog(preset: com.novelcharacter.app.data.model.UserPresetTemplate) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val dp16 = (16 * density).toInt()
        val dp8 = (8 * density).toInt()
        val dp4 = (4 * density).toInt()

        // Parse existing fields from preset JSON
        val fields = PresetTemplates.fieldsFromJson(preset.fieldsJson).toMutableList()

        // Build field list container
        val fieldListContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Helper to rebuild the field list UI
        fun rebuildFieldList() {
            fieldListContainer.removeAllViews()
            fields.forEachIndexed { index, field ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp4 }
                    setPadding(dp8, dp8, dp8, dp8)
                    setBackgroundResource(android.R.attr.selectableItemBackground.let {
                        val typedValue = android.util.TypedValue()
                        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                        typedValue.resourceId
                    })
                }

                // Move up button
                if (index > 0) {
                    val btnUp = android.widget.ImageButton(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                        setImageResource(R.drawable.ic_arrow_up)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = getString(R.string.move_up)
                        setOnClickListener {
                            val temp = fields[index]
                            fields[index] = fields[index - 1]
                            fields[index - 1] = temp
                            rebuildFieldList()
                        }
                    }
                    row.addView(btnUp)
                } else {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                    })
                }

                // Move down button
                if (index < fields.size - 1) {
                    val btnDown = android.widget.ImageButton(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                        setImageResource(R.drawable.ic_arrow_down)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = getString(R.string.move_down)
                        setOnClickListener {
                            val temp = fields[index]
                            fields[index] = fields[index + 1]
                            fields[index + 1] = temp
                            rebuildFieldList()
                        }
                    }
                    row.addView(btnDown)
                } else {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                    })
                }

                // Field info (name, type, group)
                val infoLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp8
                    }
                }
                val nameText = TextView(ctx).apply {
                    text = field.name
                    textSize = 15f
                    setTextColor(android.graphics.Color.parseColor("#DD000000"))
                }
                val detailText = TextView(ctx).apply {
                    // 프리셋이 사건 필드에 이어 **작품 필드까지** 담는다 — 한 목록에 세 종류가
                    // 섞이는데 표시가 같으면 어느 것이 어느 종류인지 열어 보지 않고는 알 수
                    // 없다(원칙 04). 종전에는 사건만 2갈래로 처리해 **작품 필드가 캐릭터
                    // 필드와 똑같이 보였다** — 저장 쪽은 확-3에서 셋으로 늘었는데 표시 쪽만
                    // 둘에 남은 뒤처짐이다(R-29 — 열거는 그 자체가 다음 실수의 예약이다).
                    val targetPrefix = com.novelcharacter.app.ui.field.FieldTargetLabel
                        .prefixResOrNull(field.entityType)
                        ?.let { getString(it) + " · " } ?: ""
                    text = "$targetPrefix${field.type} · ${field.groupName} · ${field.key}"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#88000000"))
                }
                infoLayout.addView(nameText)
                infoLayout.addView(detailText)
                row.addView(infoLayout)

                // Edit button
                val btnEdit = android.widget.ImageButton(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
                    setImageResource(R.drawable.ic_edit)
                    setBackgroundResource(android.R.color.transparent)
                    contentDescription = getString(R.string.edit)
                    setOnClickListener {
                        presetFieldSessionPresetId = preset.id
                        presetFieldSessionOriginalKey = field.key
                        presetFieldResultHandler = { editedField ->
                            fields[index] = editedField
                            rebuildFieldList()
                        }
                        // 키 중복 거부(다이얼로그 유지·입력 보존)는 다이얼로그가 저장 전에 한다 —
                        // 결과(R-65)는 전달 즉시 창이 닫혀 사후 거부가 불가능하다. 점유 키에서 자신은 뺀다.
                        // **같은 대상끼리만 점유다**(R-29) — 필드의 정체는 `(세계관, 키, 대상)`이라
                        // 사건 필드 `place`와 캐릭터 필드 `place`는 부딪치지 않는다. 종전에는 종류를
                        // 안 봐서, 프리셋에 캐릭터 `장소`가 있으면 사건 `장소`를 **만들 수 없었다**
                        // (형제 자리인 기본 필드 템플릿은 이미 종류를 보고 있었다).
                        com.novelcharacter.app.ui.field.FieldEditDialog
                            .newInstance(
                                0, field,
                                entityType = field.entityType,
                                reservedKeys = fields
                                    .filter { it !== field && it.entityType == field.entityType }
                                    .map { it.key }
                            )
                            .show(childFragmentManager, "edit_preset_field")
                    }
                }
                row.addView(btnEdit)

                // Delete button
                val btnDelete = android.widget.ImageButton(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
                    setImageResource(R.drawable.ic_delete)
                    setBackgroundResource(android.R.color.transparent)
                    contentDescription = getString(R.string.delete)
                    setOnClickListener {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.delete_warning_title)
                            .setMessage(getString(R.string.preset_field_delete_confirm, field.name))
                            .setPositiveButton(R.string.yes) { _, _ ->
                                fields.removeAt(index)
                                rebuildFieldList()
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                }
                row.addView(btnDelete)

                fieldListContainer.addView(row)
            }
        }

        rebuildFieldList()

        // Add field button
        val btnAddField = com.google.android.material.button.MaterialButton(ctx).apply {
            text = getString(R.string.preset_field_add)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp8 }
            setOnClickListener {
                presetFieldSessionPresetId = preset.id
                presetFieldSessionOriginalKey = null
                presetFieldResultHandler = { newField ->
                    fields.add(newField)
                    rebuildFieldList()
                }
                // 편집 단추와 같은 규칙 — 새 필드는 캐릭터 종류이므로 점유도 그 종류에서만 센다.
                com.novelcharacter.app.ui.field.FieldEditDialog
                    .newInstance(
                        0, null,
                        reservedKeys = fields
                            .filter { it.entityType == com.novelcharacter.app.data.model.FieldDefinition.ENTITY_CHARACTER }
                            .map { it.key }
                    )
                    .show(childFragmentManager, "add_preset_field")
            }
        }

        // Root layout
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp8)
            addView(fieldListContainer)
            addView(btnAddField)
        }

        // 필드 줄이 프리셋 필드 수만큼 늘어난다 — 상한 없이 두면 긴 프리셋에서 잘린다(B-91).
        val scrollView = cappedScrollView(ctx).apply {
            addView(rootLayout)
            isFillViewport = true
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.preset_edit_fields) + " - " + preset.name)
            .setView(scrollView)
            .setPositiveButton(R.string.save) { _, _ ->
                // Update displayOrder and save
                val updatedFields = fields.mapIndexed { i, f -> f.copy(displayOrder = i) }
                val newJson = PresetTemplates.fieldsToJson(updatedFields)
                // 결과는 viewModel.result 채널이 통보 (중복 알림 방지)
                viewModel.updateUserPreset(preset.copy(fieldsJson = newJson))
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
            .apply {
                // 목록 창이 **정상적으로** 닫히면 세션도 끝이다 — 남기면 뒷날의 결과가 옛 자리로
                // 간다. 회전은 이 리스너를 거치지 않고 창을 없애므로 세션이 살아남고, 그 세션으로
                // 저장본 직접 반영 경로가 선다([applyPresetFieldResultToStoredPreset]).
                setOnDismissListener {
                    presetFieldResultHandler = null
                    presetFieldSessionPresetId = -1L
                    presetFieldSessionOriginalKey = null
                }
            }
            .show()
    }

    private fun showSaveAsPresetDialog() {
        // 세계관 선택 → 해당 세계관의 필드를 프리셋으로 저장
        val universes = viewModel.allUniverses.value ?: emptyList()
        if (universes.isEmpty()) {
            Toast.makeText(requireContext(), R.string.preset_no_universes, Toast.LENGTH_SHORT).show()
            return
        }
        val names = universes.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.preset_select_source)
            .setItems(names) { _, which ->
                val universe = universes[which]
                showPresetNameInputDialog(universe)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPresetNameInputDialog(universe: Universe) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val dp24 = (24 * ctx.resources.displayMetrics.density).toInt()
            setPadding(dp24, dp24, dp24, 0)
        }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.preset_name_hint)
            setText(universe.name)
        }
        val descEdit = EditText(ctx).apply {
            hint = getString(R.string.preset_desc_hint)
            setText(universe.description)
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)

        // R-27(B-76): 이름이 비면 알리고 창을 유지한다 — 종전에는 조용히 닫혀 설명까지 사라졌다.
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.preset_save_title)
            .setView(layout)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val name = nameEdit.text.toString().trim()
            if (name.isEmpty()) {
                nameEdit.showInlineError(getString(R.string.preset_name_required))
                return@setValidatedPositiveButton false
            }
            // 결과는 viewModel.result 채널이 통보 (중복 알림 방지)
            viewModel.saveAsUserPreset(universe.id, name, descEdit.text.toString().trim())
            true
        }
        dialog.show()
    }

    private fun observeData() {
        // **화면 진입 1회만 재추첨한다** (B-106 ⓑ · 확정 7-3). 근거는 `NovelListFragment`의
        // 같은 자리에 적어 두었다 — 관찰자 안에서 부르면 재방출마다 전 카드가 갈린다.
        adapter.refreshRandomImages()
        viewModel.allUniverses.observe(viewLifecycleOwner) { universes ->
            adapter.submitList(universes)
            binding.emptyText.visibility = if (universes.isEmpty()) View.VISIBLE else View.GONE
            viewModel.loadCounts(universes)
        }

        viewModel.universeNovelCounts.observe(viewLifecycleOwner) { counts ->
            adapter.updateNovelCounts(counts)
        }

        viewModel.universeFieldCounts.observe(viewLifecycleOwner) { counts ->
            adapter.updateFieldCounts(counts)
        }

        // 데이터 처리 결과 알림 (성공/실패 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }

        viewModel.presetApplied.observe(viewLifecycleOwner) { event ->
            event?.getContentIfNotHandled()?.let { name ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.preset_loaded, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 사용자 프리셋 캐시 (observe 패턴)
        viewModel.userPresets.observe(viewLifecycleOwner) { presets ->
            cachedUserPresets = presets ?: emptyList()
        }

        // Recent activities cards
        viewModel.recentActivities.observe(viewLifecycleOwner) { recents ->
            if (recents.isNullOrEmpty()) {
                binding.recentSection.visibility = View.GONE
            } else {
                binding.recentSection.visibility = View.VISIBLE
                setupRecentCards(recents)
            }
        }
    }

    private fun setupRecentCards(recents: List<RecentActivity>) {
        if (recentAdapter == null) {
            binding.recentRecyclerView.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            recentAdapter = RecentActivityAdapter(
                getTypeLabel = { entityType ->
                    when (entityType) {
                        RecentActivity.TYPE_CHARACTER -> getString(R.string.recent_type_character)
                        RecentActivity.TYPE_NOVEL -> getString(R.string.recent_type_novel)
                        RecentActivity.TYPE_UNIVERSE -> getString(R.string.recent_type_universe)
                        else -> entityType
                    }
                },
                onClick = { navigateToRecentItem(it) }
            )
            binding.recentRecyclerView.adapter = recentAdapter
        }
        recentAdapter?.submitList(recents)
    }

    private class RecentActivityAdapter(
        private val getTypeLabel: (String) -> String,
        private val onClick: (RecentActivity) -> Unit
    ) : RecyclerView.Adapter<RecentActivityAdapter.ViewHolder>() {

        private var items: List<RecentActivity> = emptyList()

        fun submitList(newItems: List<RecentActivity>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val typeLabel: TextView = view.findViewById(R.id.recentTypeLabel)
            val titleView: TextView = view.findViewById(R.id.recentTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_activity, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.typeLabel.text = getTypeLabel(item.entityType)
            holder.titleView.text = item.title
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    private fun navigateToRecentItem(item: RecentActivity) {
        when (item.entityType) {
            RecentActivity.TYPE_UNIVERSE -> {
                val bundle = Bundle().apply { putLong("universeId", item.entityId) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.novelListFragment, bundle)
            }
            RecentActivity.TYPE_NOVEL -> {
                val bundle = Bundle().apply { putLong("novelId", item.entityId) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.characterListFragment, bundle)
            }
            RecentActivity.TYPE_CHARACTER -> {
                val bundle = Bundle().apply { putLong("characterId", item.entityId) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.characterDetailFragment, bundle)
            }
        }
    }

    private fun toggleReorderMode() {
        if (adapter.isReorderMode()) {
            // 자동 저장이 이미 되므로 모드만 종료
            adapter.setReorderMode(false)
            Toast.makeText(requireContext(), R.string.reorder_saved, Toast.LENGTH_SHORT).show()
        } else {
            adapter.setReorderMode(true)
            Toast.makeText(requireContext(), R.string.reorder_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUniverseEditDialog(universe: Universe?) {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val dp16 = (16 * dp).toInt()
            val dp24 = (24 * dp).toInt()
            val dp8 = (8 * dp).toInt()
            setPadding(dp24, dp16, dp24, dp8)
        }
        // 섹션 목적문 캡션 (R-25) — 라벨 아래 한 줄, dialog_novel_edit.xml의 캡션과 같은 모양
        fun sectionCaption(resId: Int) = TextView(ctx).apply {
            text = getString(resId)
            setTextAppearance(R.style.TextAppearance_App_Caption)
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.universe_name_hint)
            universe?.let { setText(it.name) }
        }
        val descEdit = EditText(ctx).apply {
            hint = getString(R.string.universe_desc_hint)
            universe?.let { setText(it.description) }
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)

        // Border color picker section
        val colorLabel = TextView(ctx).apply {
            text = getString(R.string.border_color_label)
            setPadding(0, (16 * dp).toInt(), 0, (2 * dp).toInt())
        }
        layout.addView(colorLabel)
        layout.addView(sectionCaption(R.string.border_color_desc_universe))

        var selectedColor = universe?.borderColor ?: ""

        val colorPreview = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
                bottomMargin = (8 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * dp
                if (selectedColor.isNotBlank()) {
                    try { setColor(Color.parseColor(selectedColor)) } catch (e: Exception) { android.util.Log.w("UniverseList", "Invalid color: $selectedColor", e); setColor(Color.LTGRAY) }
                } else {
                    setColor(Color.LTGRAY)
                }
                setStroke((1 * dp).toInt(), Color.GRAY)
            }
        }
        layout.addView(colorPreview)

        // HEX input (declared first so presets can reference it)
        val colorHexEdit = EditText(ctx).apply {
            hint = getString(R.string.border_color_hex_hint)
            setText(selectedColor)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val hex = s?.toString()?.trim() ?: ""
                    selectedColor = hex
                    try {
                        if (hex.isNotBlank()) {
                            (colorPreview.background as? GradientDrawable)?.setColor(Color.parseColor(hex))
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("UniverseList", "Invalid HEX color input: $hex", e)
                    }
                }
            })
        }

        // Color presets row
        val presetsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        val presets = com.novelcharacter.app.excel.BORDER_COLOR_PRESETS
        for (preset in presets) {
            val swatch = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply {
                    marginEnd = (4 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    try { setColor(Color.parseColor(preset)) } catch (e: Exception) { android.util.Log.w("UniverseList", "Invalid preset color: $preset", e) }
                }
                setOnClickListener {
                    selectedColor = preset
                    (colorPreview.background as? GradientDrawable)?.setColor(Color.parseColor(preset))
                    colorHexEdit.setText(preset)
                }
            }
            presetsRow.addView(swatch)
        }
        layout.addView(presetsRow)

        // Full spectrum color picker button
        val fullSpectrumBtn = TextView(ctx).apply {
            text = getString(R.string.color_picker_full_spectrum)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                com.novelcharacter.app.util.ColorPickerHelper.showFullSpectrumColorPicker(ctx, selectedColor) { newColor ->
                    selectedColor = newColor
                    colorHexEdit.setText(newColor)
                    (colorPreview.background as? GradientDrawable)?.setColor(Color.parseColor(newColor))
                }
            }
        }
        layout.addView(fullSpectrumBtn)
        layout.addView(colorHexEdit)

        // Clear button
        val clearBtn = TextView(ctx).apply {
            text = getString(R.string.border_color_reset)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                selectedColor = ""
                colorHexEdit.setText("")
                (colorPreview.background as? GradientDrawable)?.setColor(Color.LTGRAY)
            }
        }
        layout.addView(clearBtn)

        // 관계 유형 편집 섹션
        val relTypeLabel = TextView(ctx).apply {
            text = getString(R.string.relationship_types_label)
            setPadding(0, (16 * dp).toInt(), 0, (2 * dp).toInt())
        }
        layout.addView(relTypeLabel)
        layout.addView(sectionCaption(R.string.relationship_types_desc))

        val currentTypes = (universe?.getRelationshipTypes() ?: Universe.DEFAULT_RELATIONSHIP_TYPES).toMutableList()
        val currentColors = (universe?.getRelationshipColorMap() ?: Universe.DEFAULT_RELATIONSHIP_COLORS).toMutableMap()

        val relTypeChipsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (4 * dp).toInt())
        }

        fun showColorPickerForType(typeName: String, onColorSelected: (String) -> Unit) {
            val presetColors = arrayOf("#E91E63", "#F44336", "#FF5722", "#FF9800", "#FFC107",
                "#4CAF50", "#2196F3", "#3F51B5", "#9C27B0", "#00BCD4", "#795548", "#607D8B", "#212121", "#9E9E9E")
            val colorNames = arrayOf("핑크", "빨강", "주황빨강", "주황", "노랑",
                "초록", "파랑", "남색", "보라", "청록", "갈색", "회남색", "검정", "회색")
            val options = colorNames.toMutableList()
            options.add(getString(R.string.color_picker_full_spectrum))

            MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.relationship_color_pick_title, typeName))
                .setItems(options.toTypedArray()) { _, which ->
                    if (which < presetColors.size) {
                        onColorSelected(presetColors[which])
                    } else {
                        val currentColor = currentColors[typeName] ?: "#9E9E9E"
                        com.novelcharacter.app.util.ColorPickerHelper.showFullSpectrumColorPicker(ctx, currentColor, onColorSelected)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        fun refreshRelTypeChips() {
            relTypeChipsContainer.removeAllViews()
            currentTypes.forEach { typeName ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                }
                // 색상 원 (클릭 시 색상 피커)
                val colorCircle = View(ctx).apply {
                    val size = (20 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (8 * dp).toInt()
                    }
                    val colorHex = currentColors[typeName] ?: "#9E9E9E"
                    val drawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        // **맨몸으로 부르지 않는다** — 색은 엑셀·월드패키지·손편집으로 들어오는
                        // 자유 입력이고, 이 자리는 다이얼로그 **조립 중**이라 예외가 나면
                        // 창이 뜨기도 전에 앱이 죽는다. 같은 파일의 다른 색 자리들은 이미
                        // 감싸고 있었고 여기만 그 관행 밖이었다.
                        setColor(parseColorOrNull(colorHex) ?: Color.LTGRAY)
                    }
                    background = drawable
                    setOnClickListener {
                        showColorPickerForType(typeName) { newColor ->
                            currentColors[typeName] = newColor
                            (background as? android.graphics.drawable.GradientDrawable)
                                ?.setColor(parseColorOrNull(newColor) ?: Color.LTGRAY)
                        }
                    }
                }
                row.addView(colorCircle)
                row.addView(TextView(ctx).apply {
                    text = typeName
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(ctx).apply {
                    text = "✕"
                    setTextColor(ctx.getColor(R.color.primary))
                    setPadding((8 * dp).toInt(), 0, 0, 0)
                    setOnClickListener {
                        currentTypes.remove(typeName)
                        currentColors.remove(typeName)
                        refreshRelTypeChips()
                    }
                })
                relTypeChipsContainer.addView(row)
            }
        }
        refreshRelTypeChips()
        layout.addView(relTypeChipsContainer)

        // 새 유형 추가 행
        val addRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        val newTypeEdit = EditText(ctx).apply {
            hint = getString(R.string.relationship_types_hint)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = TextView(ctx).apply {
            text = getString(R.string.relationship_types_add)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                val newType = newTypeEdit.text.toString().trim()
                if (newType.isNotEmpty() && newType !in currentTypes) {
                    currentTypes.add(newType)
                    refreshRelTypeChips()
                    newTypeEdit.text.clear()
                }
            }
        }
        addRow.addView(newTypeEdit)
        addRow.addView(addBtn)
        layout.addView(addRow)

        val relResetBtn = TextView(ctx).apply {
            text = getString(R.string.relationship_types_reset)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                currentTypes.clear()
                currentTypes.addAll(Universe.DEFAULT_RELATIONSHIP_TYPES)
                refreshRelTypeChips()
            }
        }
        layout.addView(relResetBtn)

        // 등급 체계 섹션 (U-1) — 저장·삭제가 참조 필드 전파와 한 몸이라 세계관 저장을 기다리지
        // 않고 즉시 DB에 쓴다. 새 세계관(universe == null)은 붙일 대상이 없어 섹션을 숨긴다(R-24).
        if (universe != null) {
            val gradeLabel = TextView(ctx).apply {
                text = getString(R.string.grade_systems_label)
                setPadding(0, (16 * dp).toInt(), 0, (2 * dp).toInt())
            }
            layout.addView(gradeLabel)
            layout.addView(sectionCaption(R.string.grade_systems_desc))

            val systemsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, (4 * dp).toInt())
            }
            layout.addView(systemsContainer)

            lateinit var refreshGradeSystems: () -> Unit
            refreshGradeSystems = {
                viewLifecycleOwner.lifecycleScope.launch {
                    val systems = viewModel.getGradeSystems(universe.id)
                    systemsContainer.removeAllViews()
                    for (system in systems) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                        }
                        val grades = com.novelcharacter.app.data.model.GradeSystemRef
                            .gradesFromJson(system.gradesJson)
                        val preview = grades.entries.sortedBy { it.value }
                            .joinToString(" · ") { it.key }
                        row.addView(TextView(ctx).apply {
                            text = getString(R.string.grade_system_row_format, system.name, preview)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        row.addView(TextView(ctx).apply {
                            text = getString(R.string.edit)
                            setTextColor(ctx.getColor(R.color.primary))
                            setPadding((8 * dp).toInt(), 0, 0, 0)
                            setOnClickListener {
                                showGradeSystemEditDialog(universe.id, system) { refreshGradeSystems() }
                            }
                        })
                        row.addView(TextView(ctx).apply {
                            text = "✕"
                            setTextColor(ctx.getColor(R.color.primary))
                            setPadding((8 * dp).toInt(), 0, 0, 0)
                            setOnClickListener {
                                confirmDeleteGradeSystem(system) { refreshGradeSystems() }
                            }
                        })
                        systemsContainer.addView(row)
                    }
                }
            }
            refreshGradeSystems()

            val addSystemBtn = TextView(ctx).apply {
                text = getString(R.string.grade_systems_add)
                setTextColor(ctx.getColor(R.color.primary))
                setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
                setOnClickListener {
                    showGradeSystemEditDialog(universe.id, null) { refreshGradeSystems() }
                }
            }
            layout.addView(addSystemBtn)
        }

        // 이미지 모드 선택
        val imageLabel = TextView(ctx).apply {
            text = getString(R.string.image_mode_label)
            setPadding(0, (16 * dp).toInt(), 0, (2 * dp).toInt())
        }
        layout.addView(imageLabel)
        layout.addView(sectionCaption(R.string.image_mode_desc_universe))

        val imageModes = arrayOf(
            getString(R.string.image_mode_none),
            getString(R.string.image_mode_custom),
            getString(R.string.image_mode_random_character),
            getString(R.string.image_mode_select_character),
            getString(R.string.image_mode_random_novel),
            getString(R.string.image_mode_select_novel)
        )
        val imageModeValues = arrayOf(Universe.IMAGE_MODE_NONE, Universe.IMAGE_MODE_CUSTOM, Universe.IMAGE_MODE_RANDOM_CHARACTER, Universe.IMAGE_MODE_SELECT_CHARACTER, Universe.IMAGE_MODE_RANDOM_NOVEL, Universe.IMAGE_MODE_SELECT_NOVEL)
        var selectedImageMode = universe?.imageMode ?: Universe.IMAGE_MODE_NONE

        // 기존 이미지 경로 목록 로드
        pendingImagePaths.clear()
        pendingImagePaths.addAll(parseImagePaths(universe?.imagePaths ?: "[]"))

        val imageModeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, imageModes)
            setSelection(imageModeValues.indexOf(selectedImageMode).coerceAtLeast(0))
        }
        layout.addView(imageModeSpinner)

        var selectedCharacterId: Long? = universe?.imageCharacterId

        val imageSelectBtn = TextView(ctx).apply {
            text = if (pendingImagePaths.isNotEmpty()) getString(R.string.image_change) else getString(R.string.image_select)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_CUSTOM) View.VISIBLE else View.GONE
            setOnClickListener {
                imagePickerLauncher.launch("image/*")
            }
        }
        layout.addView(imageSelectBtn)

        // 이미지 목록 RecyclerView
        val imageRv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_CUSTOM && pendingImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
        }
        universeImageRecyclerView = imageRv
        setupUniverseImageRecyclerView(imageRv)
        layout.addView(imageRv)

        // 캐릭터 선택 버튼 (select_character 모드 전용)
        val charSelectBtn = TextView(ctx).apply {
            text = getString(R.string.image_select_character)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_CHARACTER) View.VISIBLE else View.GONE
        }
        layout.addView(charSelectBtn)

        // 기존 세계관 편집 시 선택된 캐릭터 이름 표시
        if (universe != null && selectedCharacterId != null) {
            viewModel.getCharactersWithImageForUniverse(universe.id) { chars ->
                if (!isAdded) return@getCharactersWithImageForUniverse
                val match = chars.firstOrNull { it.first == selectedCharacterId }
                if (match != null) {
                    charSelectBtn.text = getString(R.string.image_selected_character, match.second)
                }
            }
        }

        charSelectBtn.setOnClickListener {
            val uid = universe?.id
            if (uid == null) {
                Toast.makeText(ctx, R.string.save_universe_first_for_image, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.getCharactersWithImageForUniverse(uid) { chars ->
                if (!isAdded) return@getCharactersWithImageForUniverse
                if (chars.isEmpty()) {
                    Toast.makeText(ctx, R.string.no_character_with_image, Toast.LENGTH_SHORT).show()
                    return@getCharactersWithImageForUniverse
                }
                val names = chars.map { it.second }.toTypedArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.image_select_character)
                    .setItems(names) { _, which ->
                        selectedCharacterId = chars[which].first
                        charSelectBtn.text = getString(R.string.image_selected_character, chars[which].second)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        // 작품 선택 버튼 (select_novel 모드 전용)
        var selectedNovelId: Long? = universe?.imageNovelId
        val novelSelectBtn = TextView(ctx).apply {
            text = getString(R.string.image_select_novel)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_NOVEL) View.VISIBLE else View.GONE
        }
        layout.addView(novelSelectBtn)

        // 기존 세계관 편집 시 선택된 작품 이름 표시
        if (universe != null && selectedNovelId != null) {
            viewModel.getNovelsWithImageForUniverse(universe.id) { novels ->
                if (!isAdded) return@getNovelsWithImageForUniverse
                val match = novels.firstOrNull { it.first == selectedNovelId }
                if (match != null) {
                    novelSelectBtn.text = getString(R.string.image_selected_novel, match.second)
                }
            }
        }

        novelSelectBtn.setOnClickListener {
            val uid = universe?.id
            if (uid == null) {
                Toast.makeText(ctx, R.string.save_universe_first_for_image, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.getNovelsWithImageForUniverse(uid) { novels ->
                if (!isAdded) return@getNovelsWithImageForUniverse
                if (novels.isEmpty()) {
                    Toast.makeText(ctx, R.string.no_novel_with_image, Toast.LENGTH_SHORT).show()
                    return@getNovelsWithImageForUniverse
                }
                val names = novels.map { it.second }.toTypedArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.image_select_novel)
                    .setItems(names) { _, which ->
                        selectedNovelId = novels[which].first
                        novelSelectBtn.text = getString(R.string.image_selected_novel, novels[which].second)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        imageModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedImageMode = imageModeValues[pos]
                val custom = selectedImageMode == Universe.IMAGE_MODE_CUSTOM
                imageSelectBtn.visibility = if (custom) View.VISIBLE else View.GONE
                imageRv.visibility = if (custom && pendingImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
                charSelectBtn.visibility = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_CHARACTER) View.VISIBLE else View.GONE
                novelSelectBtn.visibility = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_NOVEL) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ScrollView로 래핑하여 긴 다이얼로그 스크롤 가능하게 — 관계 유형 칩·등급 체계 줄·
        // 이미지 목록이 전부 개수만큼 늘어나므로 높이 상한이 필요하다(B-91).
        val scrollView = cappedScrollView(ctx).apply {
            addView(layout)
            isFillViewport = true
        }

        // R-27(B-76) — **이 창이 그 항목의 실증이다.** 종전에는 이름을 비운 채 저장을 누르면
        // 아무 말도 없이 닫히면서 설명·테두리 색·이미지·관계 유형까지 **통째로 사라졌다.**
        // 형제 창(작품 편집·등급 체계)은 이미 이 형태였고 호스트만 옛 형태로 남아 있었다.
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (universe == null) R.string.add_universe else R.string.edit_universe)
            .setView(scrollView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val name = nameEdit.text.toString().trim()
            if (name.isEmpty()) {
                // 이 창은 길어서 스크롤을 쥐고 있다(위 [cappedScrollView]) — 아래를 보고 있으면
                // 이름 칸의 인라인 오류가 **화면 밖**이라 사용자에게는 여전히 무반응이다.
                // 오류를 붙이기 전에 그 칸을 화면으로 데려온다.
                scrollView.smoothScrollTo(0, 0)
                nameEdit.showInlineError(getString(R.string.universe_name_required))
                return@setValidatedPositiveButton false
            }
            val desc = descEdit.text.toString().trim()
            val borderColor = colorHexEdit.text.toString().trim()
            val finalImagePaths = org.json.JSONArray(pendingImagePaths).toString()
            // 관계 유형을 JSON 배열로 직렬화 (기본값과 동일하면 빈 문자열 저장)
            val relTypesJson = if (currentTypes == Universe.DEFAULT_RELATIONSHIP_TYPES) ""
                else org.json.JSONArray(currentTypes).toString()
            // 기본 색상과 동일하면 빈 문자열, 아니면 JSON 저장
            val relColorsJson = run {
                val customOnly = currentColors.filter { (k, v) ->
                    Universe.DEFAULT_RELATIONSHIP_COLORS[k] != v
                }
                if (customOnly.isEmpty()) "" else org.json.JSONObject(customOnly as Map<*, *>).toString()
            }
            val finalCharId = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_CHARACTER) selectedCharacterId else null
            val finalNovelId = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_NOVEL) selectedNovelId else null
            if (universe == null) {
                viewModel.insertUniverse(Universe(
                    name = name, description = desc, borderColor = borderColor,
                    imagePaths = finalImagePaths, imageMode = selectedImageMode,
                    imageCharacterId = finalCharId,
                    imageNovelId = finalNovelId,
                    customRelationshipTypes = relTypesJson,
                    customRelationshipColors = relColorsJson
                ))
            } else {
                viewModel.updateUniverse(universe.copy(
                    name = name, description = desc, borderColor = borderColor,
                    imagePaths = finalImagePaths, imageMode = selectedImageMode,
                    imageCharacterId = finalCharId,
                    imageNovelId = finalNovelId,
                    customRelationshipTypes = relTypesJson,
                    customRelationshipColors = relColorsJson
                ))
            }
            true
        }
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        excel.saveState(outState)
        outState.putLong(STATE_PRESET_FIELD_PRESET_ID, presetFieldSessionPresetId)
        presetFieldSessionOriginalKey?.let { outState.putString(STATE_PRESET_FIELD_ORIGINAL_KEY, it) }
    }

    private fun exportToExcel() {
        // 내보내기 흐름은 ExcelTransferController가 단일 소스다 — 종전에는 이 프래그먼트가
        // exporter·SAF 런처·모드 다이얼로그를 통째로 복제해, 컨트롤러만 고치면 이 진입은
        // 옛 흐름에 남는 구조였다(설계 D3 후단). 메뉴는 진입이 하나뿐이라 2단 선택 창을 쓴다.
        excel.showExportEntry()
    }

    private fun importFromExcel() {
        excel.showImportDialog()
    }

    private fun setupUniverseImageRecyclerView(recyclerView: RecyclerView) {
        universeImageAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val d = parent.context.resources.displayMetrics.density
                val sizePx = (64 * d).toInt()
                val imageView = android.widget.ImageView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(sizePx, sizePx).apply {
                        marginEnd = (4 * d).toInt()
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
                return object : RecyclerView.ViewHolder(imageView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val imageView = holder.itemView as android.widget.ImageView
                imageView.setImageResource(R.drawable.ic_character_placeholder)
                if (position < pendingImagePaths.size) {
                    val path = pendingImagePaths[position]
                    viewLifecycleOwner.lifecycleScope.launch {
                        // 공용 유틸 위임(P2-6) — filesDir 경로 가드(기존엔 exists만) + 총 픽셀 상한 + 밀도 절단 버그 교정.
                        val reqPx = (64 * imageView.context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                        val bitmap = withContext(Dispatchers.IO) {
                            com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, imageView.context.filesDir, reqPx)
                        }
                        if (bitmap != null && isAdded) imageView.setImageBitmap(bitmap)
                    }
                }
                imageView.setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos >= 0 && pos < pendingImagePaths.size) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.delete)
                            .setMessage(R.string.image_delete_confirm)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                val currentPos = holder.bindingAdapterPosition
                                if (currentPos >= 0 && currentPos < pendingImagePaths.size) {
                                    pendingImagePaths.removeAt(currentPos)
                                    universeImageAdapter?.notifyItemRemoved(currentPos)
                                    universeImageAdapter?.notifyItemRangeChanged(currentPos, pendingImagePaths.size - currentPos)
                                    if (pendingImagePaths.isEmpty()) {
                                        universeImageRecyclerView?.visibility = View.GONE
                                    }
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    true
                }
            }

            override fun getItemCount() = pendingImagePaths.size
        }
        recyclerView.adapter = universeImageAdapter
    }

    // ── 등급 체계 편집·삭제 (U-1) ──

    /**
     * 등급 체계 편집·생성 — **본체는 [com.novelcharacter.app.ui.common.GradeSystemEditor]다** (B-71).
     *
     * 종전에는 그 본체가 이 파일 안에만 있어 필드 편집에서 체계를 만들 길이 없었다. 떼어 낸
     * 뒤로 두 화면이 같은 창·같은 검증·같은 전파 고지를 쓴다 — 베껴 두면 그중 한 벌만
     * 고쳐지는 날이 온다(개명 추적이 특히 그렇다: 재정의가 개명을 따라가는 유일한 근거다).
     */
    private fun showGradeSystemEditDialog(
        universeId: Long,
        existing: com.novelcharacter.app.data.model.GradeSystem?,
        onSaved: () -> Unit
    ) {
        com.novelcharacter.app.ui.common.GradeSystemEditor.show(
            requireContext(), viewLifecycleOwner.lifecycleScope, universeId, existing
        ) { onSaved() }
    }

    /** 삭제 확인(R-4) — 참조 필드가 어떻게 되는지(독자 표 전환)를 먼저 말한다. */
    private fun confirmDeleteGradeSystem(
        system: com.novelcharacter.app.data.model.GradeSystem,
        onDeleted: () -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val refCount = viewModel.countGradeSystemReferences(system)
            val message = if (refCount > 0) {
                getString(R.string.grade_system_delete_confirm_refs, system.name, refCount)
            } else {
                getString(R.string.grade_system_delete_confirm, system.name)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.grade_system_delete_title)
                .setMessage(message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val demoted = viewModel.deleteGradeSystem(system)
                        if (demoted != null) {
                            val doneMessage = if (demoted > 0) {
                                getString(R.string.grade_system_deleted_toast_refs, system.name, demoted)
                            } else {
                                getString(R.string.grade_system_deleted_toast, system.name)
                            }
                            Toast.makeText(requireContext(), doneMessage, Toast.LENGTH_SHORT).show()
                            onDeleted()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun parseImagePaths(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onDestroyView() {
        binding.universeRecyclerView.adapter = null
        binding.recentRecyclerView.adapter = null
        recentAdapter = null
        super.onDestroyView()
        _binding = null
    }

    // exporter·importer의 수명은 ExcelTransferController가 생명주기 관찰자로 정리한다.

    private companion object {
        /** 프리셋 필드 편집 세션을 회전 너머로 나르는 인스턴스 상태 키 (R-65). */
        const val STATE_PRESET_FIELD_PRESET_ID = "presetFieldSessionPresetId"
        const val STATE_PRESET_FIELD_ORIGINAL_KEY = "presetFieldSessionOriginalKey"
    }
}
