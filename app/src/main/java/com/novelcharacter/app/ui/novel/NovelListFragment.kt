package com.novelcharacter.app.ui.novel

import com.novelcharacter.app.ui.common.inViewModelScope
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
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.data.repository.TrashRetentionPolicy
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.RequiredFieldMark
import com.novelcharacter.app.databinding.DialogNovelEditBinding
import com.novelcharacter.app.databinding.FragmentNovelListBinding
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.ui.adapter.NovelAdapter
import com.novelcharacter.app.util.FieldValueFixRoute
import com.novelcharacter.app.util.dismissSafely
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.reportAndNotify
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NovelListFragment : Fragment() {

    private var _binding: FragmentNovelListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NovelViewModel by viewModels()

    private lateinit var adapter: NovelAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private var universeId: Long = -1L
    private val pendingImagePaths = mutableListOf<String>()
    private var novelImageRecyclerView: RecyclerView? = null
    private var novelImageAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    private val novelImagePickerLauncher = registerForActivityResult(
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
                    com.novelcharacter.app.util.ImageImportHelper.importImage(ctx, uri, "novel", settings)
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
                novelImageAdapter?.notifyDataSetChanged()
                if (pendingImagePaths.isNotEmpty()) {
                    novelImageRecyclerView?.visibility = View.VISIBLE
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

    /**
     * 열려 있는 작품 편집 다이얼로그의 **작품 필드 섹션 상태** (확-3).
     *
     * 다이얼로그가 AlertDialog(프래그먼트 아님)라 상태를 프래그먼트가 들고 있어야 필드를
     * 새로 만든 뒤 섹션을 다시 그릴 수 있다. 다이얼로그가 닫히면 반드시 null로 되돌린다 —
     * 남겨 두면 다음에 연 다이얼로그가 옛 위젯을 읽는다.
     */
    private var novelFieldSection: NovelFieldSection? = null

    /**
     * 폼이 서면 잡을 칸 (B-198) — `필드 정의 id`와 **못 찾았을 때 말할 이름**.
     *
     * 창이 아니라 프래그먼트가 든다: 창은 [showNovelEditDialog]가 매번 새로 짓고,
     * 칸은 그 안의 비동기 조회가 끝나야 선다.
     */
    private var pendingFieldFocus: Pair<Long, String>? = null

    /**
     * @param universeId 이 폼이 필드를 조회·생성하는 세계관. **폼을 여는 시점에 확정**되고
     *   도중에 바뀌지 않는다(작품 편집에는 세계관 선택기가 없다). **null이면 전역 구역**이고
     *   (B-129 — 무소속 작품도 전역 기본 필드를 든다) 읽고 쓰는 것은 그대로 되지만
     *   **만드는 경로만 열지 않는다** — 그 구역의 필드는 기본 필드 템플릿의 그림자라
     *   만들고 지우는 자리가 설정 화면 하나다.
     * @param covered 폼이 조회한 정의 전체(CALCULATED 포함) — 저장 권한의 범위(R-5).
     * @param inputs 실제로 렌더한 입력 위젯. 커버와 다른 이유는 위 [covered] 주석 참조.
     */
    private class NovelFieldSection(
        val binding: DialogNovelEditBinding,
        val universeId: Long?
    ) {
        var fields: List<com.novelcharacter.app.data.model.FieldDefinition> = emptyList()
        var covered: Set<Long> = emptySet()
        val inputs = mutableMapOf<Long, Any>()
        /** 필드를 새로 만들어 다시 그릴 때 입력 중이던 값을 보존한다 */
        val pendingValues = mutableMapOf<Long, String>()
        /**
         * 렌더한 필드의 값 라이브러리 엔트리 — **저장 시점의 restricted 판정을 동기로 만들기
         * 위해** 폼을 그릴 때 함께 읽어 둔다(숨김 포함: 숨긴 값도 허용 목록의 일부다).
         * 판정이 동기라야 위반 시 창을 닫지 않고 그 자리에서 고칠 수 있다(R-27).
         */
        var entriesByField: Map<Long, List<com.novelcharacter.app.data.model.FieldValueEntry>> = emptyMap()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNovelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        universeId = arguments?.getLong("universeId", -1L) ?: -1L
        viewModel.setUniverseFilter(universeId)

        setupRecyclerView()
        setupFab()
        setupToolbarMenu()
        setupAddNovelFieldPath()
        observeData()

        // **인자를 지우기 전에** 판정한다 — 아래 소비가 인자를 걷어 간다.
        val arrivedForFix = (arguments?.getLong(FieldValueFixRoute.ARG_FOCUS_NOVEL_ID, 0L) ?: 0L) > 0L
        // 값을 고치러 온 길은 세계관 필터가 없어도 **밀려 들어온 화면**이다 — 돌아갈 단추가
        // 없으면 통계에서 온 사람이 나갈 길을 못 찾는다(원칙 04).
        if (universeId != -1L || arrivedForFix) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        }
        consumeFixRequest()
    }

    /**
     * 타입이 안 맞는 값을 고치러 온 요청을 소비한다 (B-198).
     *
     * **인자는 창을 여는 그 자리에서 지운다 — 읽는 자리가 아니다.** 작품 조회는 중단점이라,
     * 먼저 지우면 그사이 화면이 내려갈 때 **요청만 사라진다.** 남겨 두면 다시 선 화면이
     * 이어 연다. 열고 나서 지우므로 회전에 두 번 열리지도 않는다.
     *
     * **작품을 못 찾으면 말한다.** 통계 스냅샷은 뜬 시점의 사진이라 그사이 지워진 작품의
     * 줄이 아직 목록에 서 있을 수 있다(개발 의도 2번 — 조용히 버리지 않는다).
     */
    private fun consumeFixRequest() {
        val args = arguments ?: return
        val novelId = args.getLong(FieldValueFixRoute.ARG_FOCUS_NOVEL_ID, 0L)
        if (novelId <= 0L) return
        val fieldId = args.getLong(FieldValueFixRoute.ARG_FOCUS_FIELD_ID, 0L)
        val fieldName = args.getString(FieldValueFixRoute.ARG_FOCUS_FIELD_NAME).orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            val novel = viewModel.getNovelById(novelId)
            // 창을 여는 그 자리에서 지운다 — 먼저 지우면 조회 중에 화면이 내려갈 때
            // **요청만 사라진다**(연표 쪽 [consumeFixRequest]와 같은 이유).
            if (!isAdded || _binding == null) return@launch
            clearFixRequest()
            if (novel == null) {
                Toast.makeText(requireContext(), R.string.fix_target_novel_missing, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (fieldId > 0L) pendingFieldFocus = fieldId to fieldName
            showNovelEditDialog(novel)
        }
    }

    private fun clearFixRequest() {
        val args = arguments ?: return
        args.remove(FieldValueFixRoute.ARG_FOCUS_NOVEL_ID)
        args.remove(FieldValueFixRoute.ARG_FOCUS_FIELD_ID)
        args.remove(FieldValueFixRoute.ARG_FOCUS_FIELD_NAME)
    }

    /**
     * 고치러 온 요청이 가리킨 칸을 잡는다 (B-198).
     *
     * **칸이 없으면 무엇이 없는지 말한다** — 이 창은 그 작품의 구역(세계관, 없으면 전역)
     * 필드만 그리므로 다른 구역 정의에 매달린 값은 그릴 자리가 없다(B-258).
     */
    private fun consumeFieldFocus(section: NovelFieldSection) {
        val (fieldId, fieldName) = pendingFieldFocus ?: return
        pendingFieldFocus = null
        val widget = section.inputs[fieldId] as? View
        if (widget == null) {
            Toast.makeText(
                requireContext(), getString(R.string.fix_field_not_in_form, fieldName),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // 부착 뒤에 잡는다 — 스크롤 컨테이너는 자식이 초점을 얻을 때 그 자리로 스크롤한다.
        widget.post {
            if (_binding == null) return@post
            widget.requestFocus()
            (widget as? EditText)?.let { it.setSelection(it.text?.length ?: 0) }
        }
    }

    private fun setupRecyclerView() {
        adapter = NovelAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onClick = { novel ->
                viewModel.recordRecentActivity(novel.id, novel.title)
                val bundle = Bundle().apply { putLong("novelId", novel.id) }
                findNavController().navigateSafe(R.id.novelListFragment, R.id.characterListFragment, bundle)
            },
            onEditClick = { novel ->
                showNovelEditDialog(novel)
            },
            onDeleteClick = { novel ->
                // 계단식 삭제 범위(소속 캐릭터)를 집계해 사전 고지 — 말없는 유실 방지(변수 제어)
                viewLifecycleOwner.lifecycleScope.launch {
                    val characterCount = viewModel.getNovelDeleteImpact(novel.id)
                    if (!isAdded) return@launch
                    val message = buildString {
                        append(getString(R.string.confirm_delete_novel, novel.title))
                        if (characterCount > 0) {
                            append("\n\n")
                            // 보관 한도는 사용자가 정한다(B-74) — 문구가 실제 정책을 말해야
                            // 사용자가 "먼저 복원할지"를 판단할 수 있다.
                            val policy = TrashRetentionPolicy.currentOrDefault()
                            append(getString(
                                R.string.delete_impact_novel,
                                characterCount, policy.maxOperations, policy.retentionDays
                            ))
                        }
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            viewModel.deleteNovel(novel)
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
            },
            onPinClick = { novel ->
                viewModel.togglePin(novel)
            }
        )
        binding.novelRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.novelRecyclerView.adapter = adapter

        adapter.onOrderChanged = { orderedIds ->
            viewModel.updateDisplayOrders(orderedIds)
        }
        adapter.resolveCharacterImage = { novelId, characterId, seed, callback ->
            viewModel.resolveCharacterImage(novelId, characterId, seed, callback)
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
            it.attachToRecyclerView(binding.novelRecyclerView)
            adapter.itemTouchHelper = it
        }
    }

    private fun setupFab() {
        binding.fabAddNovel.setOnClickListener {
            showNovelEditDialog(null)
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_global_search -> {
                    findNavController().navigateSafe(R.id.novelListFragment, R.id.globalSearchFragment)
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
                R.id.action_reorder -> {
                    toggleReorderMode()
                    true
                }
                else -> false
            }
        }
    }

    private fun observeData() {
        // **화면 진입 1회만 재추첨한다** (B-106 ⓑ · 확정 7-3 — 캐릭터 목록과 같은 자리·같은 이유).
        // 관찰자 **안**에서 부르면 작품이 하나 바뀔 때마다(이름·경계선·이미지·개수 갱신) 전 카드의
        // 그림이 함께 갈린다 — 그것이 이 항목이 없애려던 "재방출마다 재추첨"이다.
        adapter.refreshRandomImages()
        viewModel.filteredNovels.observe(viewLifecycleOwner) { novels ->
            adapter.submitList(novels)
            loadNovelFieldSummaries(novels)
            binding.emptyText.visibility = if (novels.isEmpty()) View.VISIBLE else View.GONE
        }

        // Load universe border color for inheritance
        if (universeId != -1L) {
            viewModel.loadUniverseBorder(universeId)
        }
        viewModel.universeBorder.observe(viewLifecycleOwner) { (color, width) ->
            adapter.setUniverseBorder(color, width)
        }

        // 데이터 처리 결과 알림 (성공/실패 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }
    }

    /** 목록에 그려지는 작품의 필드값 요약만 조회한다 (B-67 — 연표가 B-5에서 세운 규약 그대로다). */
    private var fieldSummaryJob: kotlinx.coroutines.Job? = null

    private fun loadNovelFieldSummaries(novels: List<Novel>) {
        // 앞선 조회는 이미 낡았다 — 취소하지 않으면 늦게 끝난 쪽이 최신 결과를 덮는다.
        fieldSummaryJob?.cancel()
        if (novels.isEmpty()) {
            adapter.fieldSummaries = emptyMap()
            return
        }
        fieldSummaryJob = viewLifecycleOwner.lifecycleScope.launch {
            adapter.fieldSummaries = viewModel.getNovelFieldSummaries(novels.map { it.id })
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

    private fun showNovelEditDialog(novel: Novel?) {
        val dialogBinding = DialogNovelEditBinding.inflate(layoutInflater)
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density

        novel?.let {
            dialogBinding.editTitle.setText(it.title)
            dialogBinding.editDescription.setText(it.description)
            dialogBinding.editBorderColor.setText(it.borderColor)
            it.standardYear?.let { year -> dialogBinding.editStandardYear.setText(year.toString()) }
        }

        var selectedColor = novel?.borderColor ?: ""

        // Setup color preview
        val previewBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * dp
            if (selectedColor.isNotBlank()) {
                try { setColor(Color.parseColor(selectedColor)) } catch (e: Exception) { android.util.Log.w("NovelList", "Invalid color: $selectedColor", e); setColor(Color.LTGRAY) }
            } else {
                setColor(Color.LTGRAY)
            }
            setStroke((1 * dp).toInt(), Color.GRAY)
        }
        dialogBinding.colorPreview.background = previewBg

        // Setup color presets
        val presets = com.novelcharacter.app.excel.BORDER_COLOR_PRESETS
        for (preset in presets) {
            val swatch = View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply {
                    marginEnd = (4 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    try { setColor(Color.parseColor(preset)) } catch (e: Exception) { android.util.Log.w("NovelList", "Invalid preset color: $preset", e) }
                }
                setOnClickListener {
                    selectedColor = preset
                    previewBg.setColor(Color.parseColor(preset))
                    dialogBinding.editBorderColor.setText(preset)
                }
            }
            dialogBinding.colorPresetsRow.addView(swatch)
        }

        // HEX edit watcher
        dialogBinding.editBorderColor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hex = s?.toString()?.trim() ?: ""
                selectedColor = hex
                try {
                    if (hex.isNotBlank()) previewBg.setColor(Color.parseColor(hex))
                } catch (e: Exception) {
                    android.util.Log.w("NovelList", "Invalid HEX color input: $hex", e)
                }
            }
        })

        // Reset button
        dialogBinding.resetBorderColor.setOnClickListener {
            selectedColor = ""
            dialogBinding.editBorderColor.setText("")
            previewBg.setColor(Color.LTGRAY)
        }

        // 전체 색상 선택 버튼
        dialogBinding.btnFullSpectrum.setOnClickListener {
            com.novelcharacter.app.util.ColorPickerHelper.showFullSpectrumColorPicker(ctx, selectedColor.ifBlank { "#5C6BC0" }) { newColor ->
                selectedColor = newColor
                dialogBinding.editBorderColor.setText(newColor)
                try { previewBg.setColor(Color.parseColor(newColor)) } catch (_: Exception) {}
            }
        }

        // 이미지 모드 설정
        val imageModes = arrayOf(
            getString(R.string.image_mode_none),
            getString(R.string.image_mode_custom),
            getString(R.string.image_mode_random_character),
            getString(R.string.image_mode_select_character)
        )
        val imageModeValues = arrayOf(
            Novel.IMAGE_MODE_NONE, Novel.IMAGE_MODE_CUSTOM,
            Novel.IMAGE_MODE_RANDOM_CHARACTER, Novel.IMAGE_MODE_SELECT_CHARACTER
        )
        var selectedImageMode = novel?.imageMode ?: Novel.IMAGE_MODE_NONE
        var selectedImageCharId = novel?.imageCharacterId

        // 기존 이미지 경로 목록 로드
        pendingImagePaths.clear()
        val existingPaths = parseImagePaths(novel?.imagePaths ?: "[]")
        pendingImagePaths.addAll(existingPaths)

        dialogBinding.spinnerImageMode.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, imageModes)
        dialogBinding.spinnerImageMode.setSelection(imageModeValues.indexOf(selectedImageMode).coerceAtLeast(0))

        val isCustom = selectedImageMode == Novel.IMAGE_MODE_CUSTOM
        dialogBinding.btnSelectImage.visibility = if (isCustom) View.VISIBLE else View.GONE
        dialogBinding.imageRecyclerView.visibility = if (isCustom && pendingImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
        if (pendingImagePaths.isNotEmpty()) dialogBinding.btnSelectImage.text = getString(R.string.image_change)

        // 이미지 목록 RecyclerView 설정
        setupNovelImageRecyclerView(dialogBinding.imageRecyclerView)

        dialogBinding.btnSelectImage.setOnClickListener {
            novelImagePickerLauncher.launch("image/*")
        }

        // 캐릭터 선택 버튼 (select_character 모드 전용, 언제든 재선택 가능)
        dialogBinding.btnSelectCharacter.setOnClickListener {
            if (novel == null) {
                Toast.makeText(ctx, R.string.image_save_novel_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val chars = viewModel.getCharactersWithImages(novel.id)
                if (!isAdded) return@launch
                if (chars.isEmpty()) {
                    Toast.makeText(ctx, R.string.image_no_characters_with_images, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val charNames = chars.map { it.name }.toTypedArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.image_select_character)
                    .setItems(charNames) { _, which ->
                        selectedImageCharId = chars[which].id
                        dialogBinding.btnSelectCharacter.text = getString(R.string.image_character_selected, chars[which].name)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        // 기존 선택 캐릭터가 있으면 표시
        if (selectedImageCharId != null && novel != null) {
            dialogBinding.btnSelectCharacter.text = getString(R.string.image_change_character)
        }

        dialogBinding.spinnerImageMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedImageMode = imageModeValues[pos]
                val custom = selectedImageMode == Novel.IMAGE_MODE_CUSTOM
                dialogBinding.btnSelectImage.visibility = if (custom) View.VISIBLE else View.GONE
                dialogBinding.imageRecyclerView.visibility = if (custom && pendingImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
                dialogBinding.btnSelectCharacter.visibility = if (selectedImageMode == Novel.IMAGE_MODE_SELECT_CHARACTER) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 작품 커스텀 필드 섹션 (확-3) — 구역은 **폼을 여는 시점에 확정**된다.
        // 편집이면 그 작품의 세계관, 신규면 이 목록이 필터 중인 세계관이다.
        // **`novel?.universeId ?: …`로 쓰지 않는다**(B-129) — 엘비스는 *작품이 없다*와
        // *작품에 세계관이 없다*를 뭉개고, null이 '전역 구역'이라는 뜻을 가진 뒤로는 그 뭉갬이
        // **무소속 작품에 남의 세계관 필드를 그리는** 형태로 나타난다. 오늘은 세계관으로 거른
        // 목록에 무소속 작품이 뜨지 않아 닿지 않지만, 그 안전이 **다른 화면의 성질**에 기대고 있다.
        val fieldUniverseId =
            if (novel != null) novel.universeId else universeId.takeIf { it != -1L }
        val section = NovelFieldSection(dialogBinding, fieldUniverseId)
        novelFieldSection = section
        dialogBinding.btnAddNovelField.setOnClickListener {
            val uid = section.universeId ?: return@setOnClickListener
            com.novelcharacter.app.ui.field.FieldEditDialog
                .newInstance(uid, null, com.novelcharacter.app.data.model.FieldDefinition.ENTITY_NOVEL)
                .show(childFragmentManager, "NovelFieldEditDialog")
        }
        loadNovelFieldSection(section, novel?.id)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (novel == null) R.string.add_novel else R.string.edit_novel)
            .setView(dialogBinding.root)
            // 검증 실패로 창이 닫히면 사용자가 채운 필드값까지 함께 사라진다(R-27) —
            // 종전에는 제목이 비면 **아무 말 없이 닫히고** 저장도 되지 않았다.
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnDismissListener {
            novelFieldSection = null
            // 폼이 서기 전에 닫혔으면 요청도 여기서 끝난다 — 남기면 **다음에 여는 창**이
            // 그 요청을 집어 엉뚱한 칸을 잡는다(B-198).
            pendingFieldFocus = null
        }

        // 저장 실행 — **누른 순간에 지은 값**([snapshot])만 쓴다(R-27). 허용 목록 확인을 거쳐
        // 한 걸음 늦게 저장될 수도 있으므로, 그때 위젯을 다시 읽으면 그사이 값을 읽게 된다.
        fun performSave(
            snapshot: NovelFormSnapshot,
            submission: com.novelcharacter.app.data.repository.NovelFieldValueMerge.Submission
        ) {
            if (novel == null) {
                viewModel.insertNovel(
                    Novel(
                        title = snapshot.title,
                        description = snapshot.description,
                        universeId = if (universeId != -1L) universeId else null,
                        borderColor = snapshot.borderColor,
                        inheritUniverseBorder = snapshot.borderColor.isBlank(),
                        imagePaths = snapshot.imagePaths,
                        imageMode = snapshot.imageMode,
                        imageCharacterId = snapshot.imageCharacterId,
                        standardYear = snapshot.standardYear
                    ),
                    submission
                )
            } else {
                val oldStdYear = novel.standardYear
                val updatedNovel = novel.copy(
                    title = snapshot.title,
                    description = snapshot.description,
                    borderColor = snapshot.borderColor,
                    inheritUniverseBorder = snapshot.borderColor.isBlank(),
                    imagePaths = snapshot.imagePaths,
                    imageMode = snapshot.imageMode,
                    imageCharacterId = snapshot.imageCharacterId,
                    standardYear = snapshot.standardYear
                )
                viewModel.updateNovel(updatedNovel, submission)
                if (oldStdYear != snapshot.standardYear) {
                    viewModel.onStandardYearChanged(updatedNovel, oldStdYear, snapshot.standardYear)
                }
            }
        }

        dialog.setValidatedPositiveButton {
            val title = dialogBinding.editTitle.text.toString().trim()
            if (title.isEmpty()) {
                dialogBinding.titleLayout.showInlineError(getString(R.string.novel_title_required))
                return@setValidatedPositiveButton false
            }
            val standardYearStr = dialogBinding.editStandardYear.text.toString().trim()
            val snapshot = NovelFormSnapshot(
                title = title,
                description = dialogBinding.editDescription.text.toString().trim(),
                borderColor = dialogBinding.editBorderColor.text.toString().trim(),
                imagePaths = org.json.JSONArray(pendingImagePaths).toString(),
                imageMode = selectedImageMode,
                // **모드와 참조는 한 벌이다** — 세계관 폼이 이미 지키는 불변식이고(`finalCharId`),
                // 여기만 조건 없이 실어 *직접 등록* 작품에도 옛 캐릭터 id가 남았다. 그 남은 id가
                // 그 캐릭터를 지울 때 정리 질의에 걸려 **표지를 잃게 했다**(정리 쪽도 함께 고쳤다).
                imageCharacterId = selectedImageCharId
                    .takeIf { selectedImageMode == Novel.IMAGE_MODE_SELECT_CHARACTER },
                standardYear = if (standardYearStr.isNotEmpty()) standardYearStr.toIntOrNull() else null
            )
            // 커버 집합도 같은 시점의 폼 상태여야 고지 건수와 실제 반영 범위가 갈리지 않는다.
            val submission = buildNovelFieldSubmission(section)

            // '제한' 입력 모드 필드에 허용 목록 밖 값이 들어왔는가 — 판정은 폼을 그릴 때 미리
            // 읽어 둔 엔트리로 **동기 수행**한다. 비동기로 미루면 창이 먼저 닫혀 고칠 자리가
            // 사라진다(R-27). 사건 편집은 같은 검사를 비동기로 해 창을 닫는다 — 그쪽의 한계다.
            val violations = restrictedNovelViolations(section, submission.values)
            if (violations.isNotEmpty()) {
                showRestrictedNovelDialog(violations) {
                    performSave(snapshot, submission)
                    dialog.dismissSafely()
                }
                return@setValidatedPositiveButton false
            }
            performSave(snapshot, submission)
            true
        }
        dialog.show()
    }

    /** 저장 버튼을 누른 **그 순간**의 폼 값 — 늦게 저장돼도 사용자가 확인한 값이 저장된다. */
    private data class NovelFormSnapshot(
        val title: String,
        val description: String,
        val borderColor: String,
        val imagePaths: String,
        val imageMode: String,
        val imageCharacterId: Long?,
        val standardYear: Int?
    )

    /**
     * '제한' 입력 모드 작품 필드의 허용 목록 위반 — (필드, 위반 토큰) 목록.
     * 판정 자체는 순수 함수([FieldValueLibraryRepository.validateRestricted])가 하고,
     * 여기서는 폼이 미리 읽어 둔 엔트리를 먹인다.
     */
    private fun restrictedNovelViolations(
        section: NovelFieldSection,
        values: List<com.novelcharacter.app.data.model.NovelFieldValue>
    ): List<Pair<com.novelcharacter.app.data.model.FieldDefinition, List<String>>> {
        if (values.isEmpty()) return emptyList()
        val fieldsById = section.fields.associateBy { it.id }
        val result = mutableListOf<Pair<com.novelcharacter.app.data.model.FieldDefinition, List<String>>>()
        for (v in values) {
            val fd = fieldsById[v.fieldDefinitionId] ?: continue
            if (!com.novelcharacter.app.util.FieldValueTokenizer.supportsLibrary(fd)) continue
            if (!com.novelcharacter.app.data.model.FieldValueLibraryConfig
                    .fromConfig(fd.config).isRestricted
            ) continue
            val bad = com.novelcharacter.app.data.repository.FieldValueLibraryRepository
                .validateRestricted(fd, v.value, section.entriesByField[fd.id].orEmpty())
            if (bad.isNotEmpty()) result.add(fd to bad)
        }
        return result
    }

    /**
     * 위반 고지 + 교정 경로 둘 — 허용 목록에 넣고 저장하거나, 입력을 고치러 돌아간다.
     * 거부만 하고 길을 주지 않으면 사용자는 막다른 길에 선다(변수 제어: 검증 → 알림 → 교정).
     */
    private fun showRestrictedNovelDialog(
        violations: List<Pair<com.novelcharacter.app.data.model.FieldDefinition, List<String>>>,
        onAddedAndSave: () -> Unit
    ) {
        val message = violations.joinToString("\n") { (fd, tokens) ->
            getString(R.string.field_library_restricted_violation_line, fd.name, tokens.joinToString(", "))
        } + "\n\n" + getString(R.string.field_library_restricted_violation_paths)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.field_library_restricted_violation_title)
            .setMessage(message)
            .setPositiveButton(R.string.field_library_restricted_add_and_save) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.addLibraryValues(violations.map { (fd, tokens) -> fd.id to tokens })
                    if (!isAdded) return@launch
                    onAddedAndSave()
                }
            }
            .setNegativeButton(R.string.field_library_restricted_edit_input, null)
            .show()
    }

    // ── 작품 커스텀 필드 (확-3) ──

    /**
     * 작품 편집 자리에서 **작품 필드를 만드는 경로** — 사건 편집이 P5에서 세운 규약 그대로다.
     * 여는 것은 필드 관리와 같은 다이얼로그이고, 부모(작품 편집)가 살아 있으므로 입력 중인
     * 값은 그대로 보존된다(러프 입력 → 정밀 조정의 이중 경로).
     */
    private fun setupAddNovelFieldPath() {
        childFragmentManager.setFragmentResultListener(
            com.novelcharacter.app.ui.field.FieldEditDialog.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val json = bundle.getString(
                com.novelcharacter.app.ui.field.FieldEditDialog.RESULT_FIELD_JSON
            ) ?: return@setFragmentResultListener
            val field = com.google.gson.Gson()
                .fromJson(json, com.novelcharacter.app.data.model.FieldDefinition::class.java)
                ?: return@setFragmentResultListener
            // 이 경로는 생성 전용이다(편집은 필드 관리에서 한다) — id가 붙어 오면 무시한다.
            if (field.id != 0L) return@setFragmentResultListener
            // 생성 창에서 미리 적어 둔 값(값 사전 등록)도 함께 온다 — 받지 않으면
            // 사용자가 적은 것이 조용히 사라진다(필드 관리 경로는 이미 받고 있다).
            val initialValues = bundle.getString(
                com.novelcharacter.app.ui.field.FieldEditDialog.RESULT_INITIAL_VALUES
            ).orEmpty()
            createNovelField(field, initialValues)
        }
    }

    private fun createNovelField(
        field: com.novelcharacter.app.data.model.FieldDefinition,
        initialValues: String
    ) {
        val section = novelFieldSection ?: return
        val universeIdForField = section.universeId ?: return
        // 이 경로로 들어온 것은 작품 필드다 — 종류를 여기서 못박아 호출부마다 되풀이하지 않는다(R-29).
        val toInsert = field.copy(
            universeId = universeIdForField,
            entityType = com.novelcharacter.app.data.model.FieldDefinition.ENTITY_NOVEL
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                // **정의와 사전 등록 값은 한 수명에서 만든다** — 정본은
                // `FieldViewModel.insertField`이고 그쪽은 `viewModelScope`에서 돈다.
                // 이 자리만 화면 수명이라, 만들고 나서 회전하면 **정의는 섰는데 값이 없다**
                // (그리고 부분 실패를 말하는 아래 문구는 취소돼 뜨지도 않는다).
                val outcome = viewModel.inViewModelScope {
                    val id = viewModel.insertNovelField(toInsert)
                    viewModel.registerInitialValues(id, toInsert, initialValues)
                }
                com.novelcharacter.app.util.OpResult.success(
                    com.novelcharacter.app.util.OpResult.CAT_FIELD,
                    getString(R.string.novel_field_created, field.name),
                    // 등재하지 못한 값을 말한다 — 사건 편집이 쓰는 그 문구를 그대로 쓴다.
                    // 촉발은 중복이 아니라 DB 예외라, 말하지 않으면 사용자는 자기가 미리
                    // 적어 둔 값이 어디에도 없다는 것을 알 길이 없다(개발 의도 2번).
                    outcome.failed.takeIf { it > 0 }
                        ?.let { getString(R.string.field_initial_values_partial, it) }
                )
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                android.util.Log.e("NovelList", "Duplicate novel field key: ${field.key}", e)
                com.novelcharacter.app.util.OpResult.failure(
                    com.novelcharacter.app.util.OpResult.CAT_FIELD,
                    getString(R.string.novel_field_key_duplicate, field.key)
                )
            } catch (e: Exception) {
                android.util.Log.e("NovelList", "Failed to insert novel field", e)
                com.novelcharacter.app.util.OpResult.failure(
                    com.novelcharacter.app.util.OpResult.CAT_FIELD,
                    getString(R.string.novel_field_create_failed), e.message
                )
            }
            if (!isAdded) return@launch
            reportAndNotify(result)
            // 성공한 것만 폼에 반영한다. 입력 중인 값은 다시 그리기가 보존한다.
            if (result.success) {
                novelFieldSection?.let { current -> loadNovelFieldSection(current, novelIdOfSection) }
            }
        }
    }

    /** 다시 그릴 때 기존 값을 어느 작품에서 읽을지 — 신규 작품이면 null(읽을 값이 없다) */
    private var novelIdOfSection: Long? = null

    /**
     * 이 작품이 속한 구역의 작품 필드를 읽어 입력 섹션을 만든다 — 세계관이면 그 세계관,
     * 무소속이면 전역 구역이다(B-129). 입력 중이던 값은 보존한다.
     *
     * 커버는 조회된 정의 **전체**(CALCULATED 포함)이고 렌더는 입력 가능한 것만이다 —
     * 계산 필드를 가리키는 잔여 저장 행이 있으면 저장 시 함께 정리되고, 매 저장마다
     * "보관했습니다"를 반복하는 거짓 고지를 막는다(사건판과 같은 규칙).
     */
    private fun loadNovelFieldSection(section: NovelFieldSection, novelId: Long?) {
        novelIdOfSection = novelId
        // 입력 중이던 값 보존
        for ((fieldId, widget) in section.inputs) {
            section.pendingValues[fieldId] = novelFieldWidgetValue(widget)
        }

        // 세계관이 없으면 **전역 구역**을 읽는다(B-129) — 종전에는 여기서 사유만 남기고
        // 돌아서 무소속 작품 폼이 필드를 0개로 그렸다. 무소속 캐릭터는 B-119 확장이 이미
        // 그 구역을 받고 있었으므로, 작품만 못 보는 상태가 원칙 01·05에 어긋났다.
        viewLifecycleOwner.lifecycleScope.launch {
            val fields = viewModel.getNovelFields(section.universeId)
            val existing = if (novelId != null) viewModel.getNovelFieldValues(novelId) else emptyList()
            // 다이얼로그가 이미 닫혔으면 옛 위젯을 건드리지 않는다
            if (!isAdded || novelFieldSection !== section) return@launch
            for (v in existing) {
                // 사용자가 이미 입력 중인 값이 우선이다 — DB 값으로 덮으면 입력이 사라진다
                if (!section.pendingValues.containsKey(v.fieldDefinitionId)) {
                    section.pendingValues[v.fieldDefinitionId] = v.value
                }
            }
            section.covered = fields.mapTo(HashSet()) { it.id }
            section.entriesByField = viewModel.novelFieldEntries(fields.map { it.id })
            // 엔트리 조회도 중단점이다 — 그사이 창이 닫혔으면 옛 위젯을 다시 그리지 않는다
            if (!isAdded || novelFieldSection !== section) return@launch
            section.fields = fields
                .filter {
                    com.novelcharacter.app.data.model.FieldType.fromName(it.type) !=
                        com.novelcharacter.app.data.model.FieldType.CALCULATED
                }
                .sortedBy { it.displayOrder }
            buildNovelFieldInputs(section)
        }
    }

    private fun buildNovelFieldInputs(section: NovelFieldSection) {
        val ctx = context ?: return
        val binding = section.binding
        section.inputs.clear()
        binding.novelFieldContainer.removeAllViews()
        binding.novelFieldSectionLabel.visibility = View.VISIBLE

        // **전역 구역에는 여기서 만드는 경로가 없다**(B-129). 그 구역의 필드는 기본 필드
        // 템플릿의 그림자이고, 만들고 고치고 지우는 자리가 설정 화면 하나다. 단추를 열면
        // **관리 화면이 없는 필드**가 생겨 만든 사람도 다시 찾아가 고칠 수 없다(원칙 04).
        // 세계관을 아는 한 만드는 경로는 항상 남긴다 — 필드가 있을 때도 하나 더 필요할 수 있다.
        val globalScope = section.universeId == null
        binding.btnAddNovelField.visibility = if (globalScope) View.GONE else View.VISIBLE

        if (section.fields.isEmpty()) {
            binding.novelFieldContainer.visibility = View.GONE
            // 빈 상태에서도 머리글과 사유를 남긴다(B-31이 세운 규약과 같은 취지).
            binding.novelFieldEmptyHint.visibility = View.VISIBLE
            binding.novelFieldEmptyHint.text = getString(
                if (globalScope) R.string.novel_field_global_scope else R.string.novel_field_empty_hint
            )
            consumeFieldFocus(section)   // 그릴 칸이 없다는 것도 답이다 — 말없이 끝내지 않는다
            return
        }
        binding.novelFieldContainer.visibility = View.VISIBLE
        // 전역 구역에서는 필드가 있어도 안내를 남긴다 — 만드는 단추가 없으므로 **어디서
        // 만드는지를 말하는 자리가 여기뿐**이다(P4의 교훈: 발견성).
        if (globalScope) {
            binding.novelFieldEmptyHint.visibility = View.VISIBLE
            binding.novelFieldEmptyHint.text = getString(R.string.novel_field_global_scope)
        } else {
            binding.novelFieldEmptyHint.visibility = View.GONE
        }

        val density = resources.displayMetrics.density
        for (field in section.fields) {
            val saved = section.pendingValues[field.id].orEmpty()
            when (com.novelcharacter.app.data.model.FieldType.fromName(field.type)) {
                com.novelcharacter.app.data.model.FieldType.SELECT,
                com.novelcharacter.app.data.model.FieldType.GRADE -> {
                    val label = TextView(ctx).apply {
                        text = RequiredFieldMark.label(field)
                        textSize = 13f
                    }
                    binding.novelFieldContainer.addView(label)

                    val options = mutableListOf(getString(R.string.no_selection))
                    options.addAll(
                        if (com.novelcharacter.app.data.model.FieldType.fromName(field.type) ==
                            com.novelcharacter.app.data.model.FieldType.SELECT
                        ) {
                            com.novelcharacter.app.util.FieldOptionParser.parseSelectOptions(field.config)
                        } else {
                            com.novelcharacter.app.util.FieldOptionParser.parseGradeOptions(field.config)
                        }
                    )
                    // 고아 값 보존: 저장된 값이 현재 옵션에 없어도 유실하지 않는다
                    if (saved.isNotBlank() && saved !in options) options.add(saved)

                    val spinner = Spinner(ctx).apply {
                        val spinnerAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, options)
                        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        adapter = spinnerAdapter
                        val idx = options.indexOf(saved)
                        if (idx > 0) setSelection(idx)
                    }
                    binding.novelFieldContainer.addView(spinner)
                    section.inputs[field.id] = spinner
                }
                else -> {
                    val editText = com.google.android.material.textfield.MaterialAutoCompleteTextView(ctx).apply {
                        hint = RequiredFieldMark.label(field)
                        setText(saved)
                        threshold = 1
                        if (com.novelcharacter.app.data.model.FieldType.fromName(field.type) ==
                            com.novelcharacter.app.data.model.FieldType.NUMBER
                        ) {
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (4 * density).toInt() }
                    }
                    binding.novelFieldContainer.addView(editText)
                    section.inputs[field.id] = editText
                }
            }
        }
        attachNovelFieldSuggestions(section)
        consumeFieldFocus(section)
    }

    /**
     * 작품 필드 자동완성 — **폼이 이미 읽어 둔 엔트리에서 고른다**(B-129. 제안 꺼진 필드는 제외).
     *
     * 종전에는 여기서 세계관 단위 질의를 한 번 더 했다. 그 질의는 세계관에 묶여 있어
     * **전역 구역에서는 원리적으로 아무것도 돌려주지 못했고**, 같은 필드의 엔트리를
     * `entriesByField`로 이미 읽어 둔 뒤라 조회 자체가 두 번째였다. 잣대(숨김 제외 ·
     * 사용 횟수 내림차순)는 [com.novelcharacter.app.util.FieldSuggestionEntries]가 한 벌로 든다.
     */
    private fun attachNovelFieldSuggestions(section: NovelFieldSection) {
        val ctx = context ?: return
        val suggestions = com.novelcharacter.app.util.FieldSuggestionEntries
            .from(section.entriesByField)
        if (suggestions.isEmpty()) return
        for (field in section.fields) {
            val widget = section.inputs[field.id]
                as? com.google.android.material.textfield.MaterialAutoCompleteTextView ?: continue
            if (!com.novelcharacter.app.data.model.FieldValueLibraryConfig
                    .fromConfig(field.config).isSuggestEnabled
            ) continue
            val entries = suggestions[field.id].orEmpty()
            if (entries.isNotEmpty()) {
                widget.setAdapter(
                    com.novelcharacter.app.ui.fieldlibrary.LibrarySuggestionAdapter(ctx, entries)
                )
            }
        }
    }

    private fun novelFieldWidgetValue(widget: Any): String = when (widget) {
        is android.widget.EditText -> widget.text.toString().trim()
        is Spinner -> {
            val pos = widget.selectedItemPosition
            if (pos <= 0) "" else widget.selectedItem?.toString() ?: ""
        }
        else -> ""
    }

    /**
     * 폼 제출 한 벌 (R-5). 커버는 조회된 정의 전체이고, 아직 조회가 끝나지 않았으면
     * 커버가 공집합이라 **기존 값은 전량 보존**된다 — 로딩 중 저장이 값을 지우지 않는다.
     */
    private fun buildNovelFieldSubmission(
        section: NovelFieldSection
    ): com.novelcharacter.app.data.repository.NovelFieldValueMerge.Submission {
        val values = mutableListOf<com.novelcharacter.app.data.model.NovelFieldValue>()
        for (field in section.fields) {
            val widget = section.inputs[field.id] ?: continue
            val value = novelFieldWidgetValue(widget)
            // 빈 값은 저장하지 않는다 — 커버된 필드가 폼에 없으면 삭제(비움 의도)로 처리된다
            if (value.isNotBlank()) {
                values.add(
                    com.novelcharacter.app.data.model.NovelFieldValue(
                        novelId = novelIdOfSection ?: 0,
                        fieldDefinitionId = field.id,
                        value = value
                    )
                )
            }
        }
        return com.novelcharacter.app.data.repository.NovelFieldValueMerge
            .Submission(values, section.covered)
    }

    private fun setupNovelImageRecyclerView(recyclerView: RecyclerView) {
        novelImageRecyclerView = recyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        novelImageAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
                        // 공용 유틸 위임(P2-6) — filesDir 경로 가드(기존엔 exists만) + 총 픽셀 상한 + 밀도 절단 버그
                        // (64*density.toInt())→((64*density).toInt()) 교정.
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
                                    novelImageAdapter?.notifyItemRemoved(currentPos)
                                    novelImageAdapter?.notifyItemRangeChanged(currentPos, pendingImagePaths.size - currentPos)
                                    if (pendingImagePaths.isEmpty()) {
                                        novelImageRecyclerView?.visibility = View.GONE
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
        recyclerView.adapter = novelImageAdapter
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        excel.saveState(outState)
    }

    private fun exportToExcel() {
        // 내보내기 흐름은 ExcelTransferController가 단일 소스다 — 종전에는 이 프래그먼트가
        // exporter·SAF 런처·모드 다이얼로그를 통째로 복제해, 컨트롤러만 고치면 이 진입은
        // 옛 흐름에 남는 구조였다(설계 D3 후단). 메뉴는 진입이 하나뿐이라 2단 선택 창을 쓴다.
        excel.showExportEntry()
    }

    private fun importFromExcel() {
        // 실패 고지는 컨트롤러 뒤의 `ExcelImporter.showImportDialog`가 든다 (B-229 ①) —
        // 종전에는 이 자리가 모든 예외를 '파일이 너무 큽니다'로 옮겼는데, 그것은 **파일을
        // 고르기도 전에** 하는 말이었고 형제 진입 셋과도 달랐다.
        excel.showImportDialog()
    }

    override fun onDestroyView() {
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        binding.novelRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    // exporter·importer의 수명은 ExcelTransferController가 생명주기 관찰자로 정리한다.
}
