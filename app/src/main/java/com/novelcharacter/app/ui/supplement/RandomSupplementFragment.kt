package com.novelcharacter.app.ui.supplement

import android.graphics.drawable.GradientDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.FragmentSupplementRandomBinding
import com.novelcharacter.app.ui.character.CharacterImageStripController
import com.novelcharacter.app.ui.character.CharacterSaveCoordinator
import com.novelcharacter.app.ui.character.CharacterViewModel
import com.novelcharacter.app.ui.character.DynamicFieldFormBuilder
import com.novelcharacter.app.ui.character.DynamicFieldRenderer
import com.novelcharacter.app.util.CharacterDraftPrefs
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 랜덤 보충 탭 — 랜덤으로 캐릭터 하나를 뽑아 화면 이동 없이 미리보기 ↔ 인라인 편집을 전환한다.
 *
 * - 뽑기: [RandomPickEngine] (SupplementViewModel 보유) — 3개 모드·셔플백·이전/다음 히스토리.
 * - 미리보기(OFF): 이미지 페이저 + 기본 정보 + 완성도 배지·미흡 칩(감사 연계) + [DynamicFieldRenderer].
 * - 편집(ON): 캐릭터 편집 화면과 동일한 공용 모듈([DynamicFieldFormBuilder]·
 *   [CharacterImageStripController]·[CharacterSaveCoordinator]) — 중복 이름/나이 연동/교차 세계관 검증 포함.
 * - 미저장 가드: 편집 OFF·다시 뽑기·이전/다음·모드 변경·탭 전환·필터 변경·백키 모든 이탈 경로에서
 *   확인 다이얼로그(저장 후 닫기/버리기/계속 편집). 자동저장 옵트인 시 확인 없이 저장.
 */
class RandomSupplementFragment : Fragment(), RandomEditGuard {

    private var _binding: FragmentSupplementRandomBinding? = null
    private val binding get() = _binding!!

    // 호스트 프래그먼트 스코프 공유 — 필터·감사·뽑기 엔진 상태
    private val supplementViewModel: SupplementViewModel by viewModels(ownerProducer = { requireParentFragment() })

    // 캐릭터 데이터·저장 API
    private val characterViewModel: CharacterViewModel by viewModels()

    private val gson = Gson()

    private enum class EditorState { PREVIEW, EDIT }

    private var editorState = EditorState.PREVIEW
    private var hasUnsavedChanges = false
    private var displayedCharacter: Character? = null
    private var novels: List<Novel> = emptyList()

    // 이벤트 억제 플래그 — 프로그램적 UI 조작이 리스너를 재귀 트리거하지 않도록
    private var suppressSwitchEvents = false
    private var suppressModeChipEvents = false
    private var suppressDirtyTracking = true
    private var novelSpinnerInitialized = false
    // 편집 폼 태그 비동기 적재 완료 여부 — 스피너 도달과 함께 하이드레이션 완료 조건
    private var editTagsLoaded = false
    // 편집 진입 시 프로그램적으로 선택할 스피너 위치 — 초기 콜백(중간 0 포함)을 사용자 변경과 구분
    private var expectedInitialNovelPos = -1

    // 드래프트/회전 상태
    private var suppressDraftSave = false
    private var pendingFieldValues: Bundle? = null
    private var pendingEditRestore: Bundle? = null

    // 저장 완료 후 실행할 이탈 동작 (편집 종료·탭 전환 등)
    private var pendingAfterSave: (() -> Unit)? = null

    private lateinit var formBuilder: DynamicFieldFormBuilder
    private lateinit var imageStrip: CharacterImageStripController
    private lateinit var saveCoordinator: CharacterSaveCoordinator
    private lateinit var fieldRenderer: DynamicFieldRenderer
    private lateinit var backCallback: OnBackPressedCallback

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) imageStrip.importUris(uris)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSupplementRandomBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupModules()
        setupControls()
        saveCoordinator.registerResultListeners() // 회전 안전(P1-A)

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                requestExitEditMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // 편집 중 이탈 가드 등록 (탭 전환·공유 필터 변경 인터셉트)
        (parentFragment as? SupplementFragment)?.editGuard = this

        // 회전 복원: 편집 모드였다면 currentPick 도착 후 편집 상태를 재구성한다
        pendingEditRestore = savedInstanceState?.takeIf { it.getBoolean("randomEditMode", false) }

        observeViewModel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (editorState == EditorState.EDIT && _binding != null) {
            outState.putBoolean("randomEditMode", true)
            outState.putBoolean("randomDirty", hasUnsavedChanges)
            outState.putLong("displayedCharacterId", displayedCharacter?.id ?: -1L)
            outState.putInt("novelSpinnerPos", binding.spinnerNovel.selectedItemPosition)
            if (::imageStrip.isInitialized) {
                outState.putStringArrayList("imagePaths", ArrayList(imageStrip.paths))
            }
            val fieldValues = Bundle()
            if (::formBuilder.isInitialized) formBuilder.saveStateTo(fieldValues)
            outState.putBundle("fieldValues", fieldValues)
        }
    }

    /** 화면 이탈(앱 백그라운드 등) 시 미저장 변경을 영구 드래프트로 기록 — 편집 화면과 같은 슬롯 공유 */
    override fun onPause() {
        super.onPause()
        if (_binding == null || editorState != EditorState.EDIT || suppressDraftSave || !hasUnsavedChanges) return
        val ctx = context?.applicationContext ?: return
        val id = displayedCharacter?.id ?: return
        CharacterDraftPrefs.save(ctx, id, collectDraft())
    }

    override fun onDestroyView() {
        val host = parentFragment as? SupplementFragment
        if (host?.editGuard === this) host.editGuard = null
        if (::imageStrip.isInitialized) imageStrip.detach()
        binding.imageViewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }

    // ===== 초기화 =====

    private fun setupModules() {
        fieldRenderer = DynamicFieldRenderer(
            containerGetter = { binding.dynamicFieldsContainer },
            contextGetter = { requireContext() },
            resourcesGetter = { resources },
            getString = { resId -> getString(resId) },
            getStringWithArg = { resId, arg -> getString(resId, arg) }
        )

        imageStrip = CharacterImageStripController(
            fragment = this,
            recyclerViewGetter = { _binding?.imageRecyclerView },
            navOriginDestId = R.id.characterHomeFragment,
            onChanged = { markDirty() }
        )

        formBuilder = DynamicFieldFormBuilder(
            containerGetter = { binding.dynamicFormContainer },
            contextGetter = { context },
            scopeGetter = { viewLifecycleOwner.lifecycleScope },
            isAlive = { _binding != null && isAdded },
            fragmentManagerGetter = { if (isAdded) childFragmentManager else null },
            viewModel = characterViewModel,
            onFieldChanged = { markDirty() }
        )

        saveCoordinator = CharacterSaveCoordinator(
            fragment = this,
            viewModel = characterViewModel,
            checkDuplicates = true,
            host = object : CharacterSaveCoordinator.Host {
                override fun snapshot() = CharacterSaveCoordinator.FormSnapshot(
                    name = binding.editName.text.toString(),
                    firstName = binding.editFirstName.text.toString(),
                    lastName = binding.editLastName.text.toString(),
                    anotherName = binding.editAnotherName.text.toString(),
                    tags = binding.editTags.text.toString(),
                    memo = binding.editMemo.text.toString(),
                    novelId = selectedNovelIdFromSpinner(),
                    imagePaths = imageStrip.paths.toList()
                )

                override fun collectFieldValues(characterId: Long) = formBuilder.collectFieldValues(characterId)
                override fun validateRequiredFields() = formBuilder.validateRequiredFields()
                override fun editingCharacterId() = displayedCharacter?.id ?: -1L
                override fun existingCharacter() = displayedCharacter

                override fun onSavingChanged(saving: Boolean) {
                    val b = _binding ?: return
                    b.btnSaveInline.isEnabled = !saving && isEditFormHydrated()
                    b.btnReroll.isEnabled = !saving
                    b.switchEditMode.isEnabled = !saving && displayedCharacter != null
                    if (saving) {
                        b.btnPickBack.isEnabled = false
                        b.btnPickForward.isEnabled = false
                    } else {
                        updatePickButtons()
                    }
                }

                override fun onSaved(savedCharacterId: Long) {
                    clearDraft()
                    hasUnsavedChanges = false
                    // 저장 후의 새 변경은 다시 드래프트로 보호되어야 한다
                    suppressDraftSave = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        // 중복 다이얼로그(새로 만들기/기존에 덮어쓰기)로 저장 대상 id가 바뀌었을 수 있다
                        val fresh = characterViewModel.getCharacterByIdSuspend(savedCharacterId)
                        if (fresh != null) displayedCharacter = fresh
                        // 감사·풀 리로드 — 완성도 배지·미흡 칩·양 탭 데이터 갱신
                        val ctx = context
                        if (ctx != null) {
                            supplementViewModel.loadData(SupplementCriteria.load(ctx))
                        }
                        val after = pendingAfterSave
                        pendingAfterSave = null
                        after?.invoke()
                    }
                }

                override fun onSaveAborted() {
                    // 이탈 가드가 걸어둔 예약 동작 해제 — 남겨두면 사용자가 저장을 취소하고
                    // 계속 편집하다 나중에 저장했을 때 이전 이탈 동작(리롤·탭 전환 등)이 실행된다
                    pendingAfterSave = null
                }
            }
        )
    }

    private fun setupControls() {
        syncModeChips()
        binding.modeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressModeChipEvents) return@setOnCheckedStateChangeListener
            val newMode = when (checkedIds.firstOrNull()) {
                R.id.chipModeIncomplete -> RandomPickEngine.PickMode.INCOMPLETE_FIRST
                R.id.chipModeLeastRecent -> RandomPickEngine.PickMode.LEAST_RECENT
                else -> RandomPickEngine.PickMode.PURE_RANDOM
            }
            if (newMode == supplementViewModel.randomMode) return@setOnCheckedStateChangeListener
            if (isBlocking()) {
                // 편집 중 — 칩을 되돌리고 가드 통과 후 적용
                syncModeChips()
                requestLeave {
                    supplementViewModel.setRandomMode(newMode)
                    syncModeChips()
                }
            } else {
                if (editorState == EditorState.EDIT) exitEditMode()
                supplementViewModel.setRandomMode(newMode)
            }
        }

        binding.btnReroll.setOnClickListener { guardedPickAction { supplementViewModel.rerollRandom() } }
        binding.btnPickBack.setOnClickListener { guardedPickAction { supplementViewModel.randomPickBack() } }
        binding.btnPickForward.setOnClickListener { guardedPickAction { supplementViewModel.randomPickForward() } }
        binding.btnRandomSettings.setOnClickListener { showRandomSettingsDialog() }

        binding.switchEditMode.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            if (isChecked) {
                if (displayedCharacter == null) {
                    revertSwitch(false)
                } else {
                    enterEditMode()
                }
            } else {
                requestExitEditMode()
            }
        }

        binding.btnSaveInline.setOnClickListener { saveCoordinator.requestSave() }

        binding.btnAddImage.setOnClickListener { imagePickerLauncher.launch("image/*") }
        imageStrip.attach()

        binding.btnGoDetail.setOnClickListener {
            val id = displayedCharacter?.id ?: return@setOnClickListener
            val bundle = Bundle().apply { putLong("characterId", id) }
            findNavController().navigateSafe(R.id.characterHomeFragment, R.id.characterDetailFragment, bundle)
        }

        binding.btnClearFilter.setOnClickListener {
            (parentFragment as? SupplementFragment)?.clearFilters()
        }

        // 기본 필드 변경 추적
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                markDirty()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        binding.editName.addTextChangedListener(watcher)
        binding.editFirstName.addTextChangedListener(watcher)
        binding.editLastName.addTextChangedListener(watcher)
        binding.editAnotherName.addTextChangedListener(watcher)
        binding.editTags.addTextChangedListener(watcher)
        binding.editMemo.addTextChangedListener(watcher)
    }

    private fun observeViewModel() {
        supplementViewModel.currentPick.observe(viewLifecycleOwner) { pick ->
            // 회전 복원: 편집하던 캐릭터가 다시 도착하면 편집 상태 재구성
            val restore = pendingEditRestore
            if (restore != null && pick != null && pick.id == restore.getLong("displayedCharacterId", -1L)) {
                pendingEditRestore = null
                displayedCharacter = pick
                enterEditMode(restoreState = restore)
                updatePickButtons()
                return@observe
            }

            if (editorState == EditorState.EDIT) {
                // 편집 중 리로드(저장 등) — 같은 캐릭터면 참조만 갱신하고 폼은 건드리지 않는다
                if (pick != null && pick.id == displayedCharacter?.id) displayedCharacter = pick
                return@observe
            }

            displayedCharacter = pick
            binding.switchEditMode.isEnabled = pick != null && !saveCoordinator.isSaving
            if (pick == null) showEmptyState() else renderPreview(pick)
            updatePickButtons()
        }

        supplementViewModel.randomNotice.observe(viewLifecycleOwner) { notice ->
            notice ?: return@observe
            val msgRes = when (notice) {
                SupplementViewModel.RandomNotice.CYCLE_COMPLETE -> R.string.random_cycle_complete
                SupplementViewModel.RandomNotice.FELL_BACK_TO_FULL_POOL -> R.string.random_mode_incomplete_empty
                SupplementViewModel.RandomNotice.PICK_OUTSIDE_FILTER -> R.string.random_pick_outside_filter
                SupplementViewModel.RandomNotice.CURRENT_DELETED -> R.string.random_character_deleted
            }
            if (isAdded) Toast.makeText(requireContext(), msgRes, Toast.LENGTH_SHORT).show()
            supplementViewModel.clearRandomNotice()
        }
    }

    // ===== 미리보기 =====

    private fun renderPreview(character: Character) {
        val b = _binding ?: return
        b.emptyLayout.visibility = View.GONE
        b.previewContainer.visibility = View.VISIBLE
        b.editContainer.visibility = View.GONE

        b.previewName.text = character.name

        val fullName = listOf(character.lastName, character.firstName)
            .filter { it.isNotBlank() }.joinToString(" ")
        b.previewFullName.visibility = if (fullName.isBlank()) View.GONE else View.VISIBLE
        b.previewFullName.text = fullName

        // 별명 칩
        b.aliasChipGroup.removeAllViews()
        val aliases = character.aliases
        if (aliases.isEmpty()) {
            b.aliasChipGroup.visibility = View.GONE
        } else {
            b.aliasChipGroup.visibility = View.VISIBLE
            for (alias in aliases) {
                b.aliasChipGroup.addView(Chip(requireContext()).apply {
                    text = alias
                    isClickable = false
                })
            }
        }

        // 작품·세계관
        val novel = supplementViewModel.novelList.value?.find { it.id == character.novelId }
        val universe = novel?.universeId?.let { uId ->
            supplementViewModel.universeList.value?.find { it.id == uId }
        }
        b.previewNovel.text = when {
            novel != null && universe != null -> "${universe.name} · ${novel.title}"
            novel != null -> novel.title
            else -> getString(R.string.no_novel_selected)
        }

        // 완성도 배지 + 미흡 항목 칩 (완성도 검사와 유기적 연결)
        renderAuditBadge(character)

        // 메모
        if (character.memo.isBlank()) {
            b.memoCard.visibility = View.GONE
        } else {
            b.memoCard.visibility = View.VISIBLE
            b.previewMemo.text = character.memo
        }

        // 이미지 페이저
        setupPreviewImages(character.imagePaths)

        // 태그 + 커스텀 필드 (비동기)
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = characterViewModel.getTagsByCharacterList(character.id)
            if (_binding == null || displayedCharacter?.id != character.id) return@launch
            if (tags.isEmpty()) {
                binding.previewTags.visibility = View.GONE
            } else {
                binding.previewTags.visibility = View.VISIBLE
                binding.previewTags.text = tags.joinToString(", ") { "#${it.tag}" }
            }

            val universeId = novel?.universeId
            if (universeId != null) {
                val fields = characterViewModel.getFieldsByUniverseList(universeId)
                val values = characterViewModel.getValuesByCharacterList(character.id)
                if (_binding == null || displayedCharacter?.id != character.id) return@launch
                fieldRenderer.displayDynamicFields(fields, values)
            } else {
                binding.dynamicFieldsContainer.removeAllViews()
            }
        }
    }

    private fun renderAuditBadge(character: Character) {
        val b = _binding ?: return
        val audit = supplementViewModel.getAuditForCharacter(character.id)
        val ctx = requireContext()

        if (audit == null) {
            b.completionBadge.visibility = View.GONE
            b.missingLabel.visibility = View.GONE
            b.missingChipGroup.visibility = View.GONE
            return
        }

        // 배지 — 완성도 검사 탭과 같은 색상 규칙
        b.completionBadge.visibility = View.VISIBLE
        val bg = GradientDrawable().apply { cornerRadius = 32f }
        if (audit.issues.isEmpty()) {
            b.completionBadge.text = getString(R.string.random_badge_complete)
            bg.setColor(ContextCompat.getColor(ctx, R.color.supplement_badge_low))
        } else if (audit.fieldCompletion >= 0) {
            val pct = audit.fieldCompletion.toInt()
            b.completionBadge.text = "${pct}%"
            val color = when {
                pct < 30 -> ContextCompat.getColor(ctx, R.color.supplement_badge_high)
                pct < 70 -> ContextCompat.getColor(ctx, R.color.supplement_badge_mid)
                else -> ContextCompat.getColor(ctx, R.color.supplement_badge_low)
            }
            bg.setColor(color)
        } else {
            b.completionBadge.text = getString(R.string.supplement_field_na)
            bg.setColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        b.completionBadge.background = bg

        // 미흡 항목 칩
        b.missingChipGroup.removeAllViews()
        if (audit.issues.isEmpty()) {
            b.missingLabel.visibility = View.GONE
            b.missingChipGroup.visibility = View.GONE
        } else {
            b.missingLabel.visibility = View.VISIBLE
            b.missingChipGroup.visibility = View.VISIBLE
            for (issue in audit.issues) {
                b.missingChipGroup.addView(Chip(ctx).apply {
                    text = issue.label
                    isClickable = false
                })
            }
        }
    }

    private fun setupPreviewImages(imagePathsJson: String) {
        val b = _binding ?: return
        val imagePaths: List<String> = try {
            gson.fromJson(imagePathsJson, com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (imagePaths.isEmpty()) {
            b.imageViewPager.visibility = View.GONE
            b.imageViewPager.adapter = null
            return
        }

        b.imageViewPager.visibility = View.VISIBLE
        b.imageViewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val imageView = ImageView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                return object : RecyclerView.ViewHolder(imageView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val imageView = holder.itemView as ImageView
                (imageView.getTag(R.id.image_load_job) as? kotlinx.coroutines.Job)?.cancel()
                imageView.setImageResource(R.drawable.ic_character_placeholder)
                val path = imagePaths[position]
                imageView.setOnClickListener {
                    val bundle = Bundle().apply {
                        putString("imagePaths", imagePathsJson)
                        putInt("startPosition", position)
                    }
                    findNavController().navigateSafe(R.id.characterHomeFragment, R.id.imageViewerFragment, bundle)
                }
                val boundPosition = position
                val job = viewLifecycleOwner.lifecycleScope.launch {
                    val dir = context?.filesDir ?: return@launch
                    val bitmap = withContext(Dispatchers.IO) {
                        com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, dir, 1024)
                    }
                    if (bitmap != null && holder.bindingAdapterPosition == boundPosition && isAdded) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
                imageView.setTag(R.id.image_load_job, job)
            }

            override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
                val imageView = holder.itemView as ImageView
                (imageView.getTag(R.id.image_load_job) as? kotlinx.coroutines.Job)?.cancel()
                imageView.setImageDrawable(null)
            }

            override fun getItemCount() = imagePaths.size
        }
    }

    private fun showEmptyState() {
        val b = _binding ?: return
        b.previewContainer.visibility = View.GONE
        b.editContainer.visibility = View.GONE
        b.emptyLayout.visibility = View.VISIBLE
        val filterActive = supplementViewModel.selectedUniverseId != null || supplementViewModel.selectedNovelId != null
        if (filterActive) {
            b.emptyRandomText.text = getString(R.string.random_no_characters_filtered)
            b.btnClearFilter.visibility = View.VISIBLE
        } else {
            b.emptyRandomText.text = getString(R.string.supplement_no_characters)
            b.btnClearFilter.visibility = View.GONE
        }
    }

    private fun updatePickButtons() {
        val b = _binding ?: return
        if (saveCoordinator.isSaving) return
        b.btnPickBack.isEnabled = supplementViewModel.canRandomGoBack
        b.btnPickForward.isEnabled = supplementViewModel.canRandomGoForward
    }

    private fun syncModeChips() {
        val b = _binding ?: return
        suppressModeChipEvents = true
        val chipId = when (supplementViewModel.randomMode) {
            RandomPickEngine.PickMode.PURE_RANDOM -> R.id.chipModePure
            RandomPickEngine.PickMode.INCOMPLETE_FIRST -> R.id.chipModeIncomplete
            RandomPickEngine.PickMode.LEAST_RECENT -> R.id.chipModeLeastRecent
        }
        b.modeChipGroup.check(chipId)
        suppressModeChipEvents = false
    }

    // ===== 편집 모드 상태 머신 =====

    private fun markDirty() {
        if (suppressDirtyTracking) return
        hasUnsavedChanges = true
    }

    private fun isEditFormHydrated(): Boolean = novelSpinnerInitialized && editTagsLoaded

    /**
     * 편집 폼 초기 적재(작품 스피너 목표 도달 + 태그 로드)가 **모두** 끝났을 때만
     * 더티 추적과 저장 버튼을 연다 — 태그·필드값이 적재되기 전의 저장이 스냅샷의
     * 빈 값으로 기존 데이터를 지우는(무음 유실) 것을 차단한다.
     */
    private fun maybeFinishEditHydration() {
        // 하이드레이션 중 편집을 이탈했으면 무시 — exitEditMode가 억제를 복구한 상태를 유지한다
        if (editorState != EditorState.EDIT) return
        if (!isEditFormHydrated()) return
        suppressDirtyTracking = false
        if (!saveCoordinator.isSaving) {
            _binding?.btnSaveInline?.isEnabled = true
        }
    }

    override fun isBlocking(): Boolean = editorState == EditorState.EDIT && hasUnsavedChanges

    override fun requestLeave(onProceed: () -> Unit) {
        if (!isBlocking()) {
            if (editorState == EditorState.EDIT) exitEditMode()
            onProceed()
            return
        }
        val ctx = context ?: return
        if (RandomSupplementSettings.load(ctx).autoSaveOnExit) {
            pendingAfterSave = { exitEditMode(); onProceed() }
            if (!saveCoordinator.requestSave()) {
                // 검증 실패(사유는 이미 토스트) — 자동저장 불가, 사용자 선택으로 폴백
                pendingAfterSave = null
                showUnsavedDialog(onProceed)
            }
        } else {
            showUnsavedDialog(onProceed)
        }
    }

    /** 미저장 확인 다이얼로그 — 저장 후 닫기 / 버리기 / 계속 편집 */
    private fun showUnsavedDialog(afterExit: (() -> Unit)?) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.random_unsaved_title)
            .setMessage(R.string.random_unsaved_message)
            .setPositiveButton(R.string.random_unsaved_save) { _, _ ->
                pendingAfterSave = { exitEditMode(); afterExit?.invoke() }
                if (!saveCoordinator.requestSave()) pendingAfterSave = null
            }
            .setNegativeButton(R.string.random_unsaved_discard) { _, _ ->
                clearDraft()
                exitEditMode()
                afterExit?.invoke()
            }
            .setNeutralButton(R.string.random_unsaved_keep, null)
            .show()
    }

    private fun guardedPickAction(action: () -> Unit) {
        if (editorState == EditorState.EDIT) {
            if (hasUnsavedChanges) {
                requestLeave { action() }
            } else {
                exitEditMode()
                action()
            }
        } else {
            action()
        }
        updatePickButtons()
    }

    /** 편집 OFF 요청 (스위치·백키) — 더티면 자동저장 또는 확인 다이얼로그 */
    private fun requestExitEditMode() {
        if (editorState != EditorState.EDIT) return
        if (!hasUnsavedChanges) {
            exitEditMode()
            return
        }
        val ctx = context ?: return
        if (RandomSupplementSettings.load(ctx).autoSaveOnExit) {
            pendingAfterSave = { exitEditMode() }
            if (saveCoordinator.requestSave()) {
                // 저장 진행 중 — 완료 시 미리보기로 전환. 그동안 스위치는 ON 유지.
                revertSwitch(true)
            } else {
                pendingAfterSave = null
                revertSwitch(true)
                showUnsavedDialog(null)
            }
        } else {
            revertSwitch(true)
            showUnsavedDialog(null)
        }
    }

    private fun enterEditMode(restoreState: Bundle? = null) {
        val character = displayedCharacter ?: run {
            revertSwitch(false)
            return
        }
        editorState = EditorState.EDIT
        suppressDraftSave = false
        suppressDirtyTracking = true
        novelSpinnerInitialized = false
        // 회전 복원은 태그가 View 상태로 자동 복원되므로 즉시 완료, 새 진입은 비동기 로드 대기
        editTagsLoaded = restoreState != null
        hasUnsavedChanges = restoreState?.getBoolean("randomDirty", false) ?: false
        updateModeUi()
        // 초기 적재 완료 전 저장 차단 — maybeFinishEditHydration이 다시 연다
        binding.btnSaveInline.isEnabled = false

        if (restoreState != null) {
            // 회전 복원 — 기본 필드는 View 상태로 자동 복원되므로 덮어쓰지 않는다
            restoreState.getStringArrayList("imagePaths")?.let { saved ->
                val validated = CharacterImageStripController.validateInternalPaths(saved, context?.filesDir)
                imageStrip.setPaths(validated)
            }
            pendingFieldValues = restoreState.getBundle("fieldValues")
            val spinnerPos = restoreState.getInt("novelSpinnerPos", -1)
            viewLifecycleOwner.lifecycleScope.launch {
                loadNovelsForEdit(character, presetSpinnerPos = spinnerPos)
            }
        } else {
            fillEditForm(character)
            viewLifecycleOwner.lifecycleScope.launch {
                loadNovelsForEdit(character, presetSpinnerPos = -1)
                if (_binding != null && isAdded) maybeOfferDraftRestore(character.id)
            }
        }
    }

    private fun fillEditForm(character: Character) {
        val b = _binding ?: return
        b.editName.setText(character.name)
        b.editLastName.setText(character.lastName)
        b.editFirstName.setText(character.firstName)
        b.editAnotherName.setText(character.anotherName)
        b.editMemo.setText(character.memo)
        b.editTags.setText("")

        val paths: List<String> = try {
            gson.fromJson(character.imagePaths, com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        imageStrip.setPaths(paths)

        // 태그 비동기 로드 — 하이드레이션 완료 전에는 suppressDirtyTracking이 유지되므로
        // 프로그램적 setText가 더티를 세우지 않는다
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = characterViewModel.getTagsByCharacterList(character.id)
            if (_binding == null || displayedCharacter?.id != character.id) return@launch
            binding.editTags.setText(tags.joinToString(", ") { it.tag })
            editTagsLoaded = true
            maybeFinishEditHydration()
        }
    }

    /**
     * 작품 스피너 구성 — 선택 변경 시 해당 세계관의 동적 필드 폼을 재구성한다
     * (CharacterEditFragment.loadNovels와 동일한 흐름).
     */
    private suspend fun loadNovelsForEdit(character: Character, presetSpinnerPos: Int) {
        novels = characterViewModel.getAllNovelsList()
        if (_binding == null || !isAdded) return
        val novelNames = mutableListOf(getString(R.string.no_novel_selected))
        novelNames.addAll(novels.map { it.title })

        val ctx = context ?: return
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, novelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerNovel.adapter = adapter

        binding.spinnerNovel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (position > 0) {
                        val novel = novels[position - 1]
                        val universeId = novel.universeId
                        formBuilder.currentUniverseId = universeId
                        formBuilder.fieldDefinitions = if (universeId != null) {
                            characterViewModel.getFieldsByUniverseList(universeId)
                        } else {
                            emptyList()
                        }
                    } else {
                        formBuilder.fieldDefinitions = emptyList()
                    }
                    if (_binding == null) return@launch
                    formBuilder.buildForm()

                    val saved = pendingFieldValues
                    if (saved != null && formBuilder.fieldDefinitions.isNotEmpty()) {
                        formBuilder.restoreFieldValues(saved)
                        pendingFieldValues = null
                    } else if (saved == null) {
                        val target = displayedCharacter
                        if (target != null && editorState == EditorState.EDIT) {
                            formBuilder.loadFieldValues(target.id)
                        }
                    }

                    if (!novelSpinnerInitialized) {
                        // 초기(프로그램적) 선택이 목표 위치에 도달했을 때만 변경 추적 시작
                        // — 목표 도달 전의 중간(0) 콜백은 사용자 변경이 아니다
                        if (position == expectedInitialNovelPos) {
                            novelSpinnerInitialized = true
                            maybeFinishEditHydration()
                        }
                    } else {
                        // 사용자가 작품을 변경 — 미저장 변경
                        markDirty()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val targetPos = if (presetSpinnerPos >= 0 && presetSpinnerPos < novelNames.size) {
            presetSpinnerPos
        } else {
            val index = character.novelId?.let { id -> novels.indexOfFirst { it.id == id } } ?: -1
            if (index >= 0) index + 1 else 0
        }
        expectedInitialNovelPos = targetPos
        binding.spinnerNovel.setSelection(targetPos)
    }

    private fun exitEditMode() {
        if (editorState != EditorState.EDIT) return
        editorState = EditorState.PREVIEW
        hasUnsavedChanges = false
        suppressDirtyTracking = true
        pendingFieldValues = null
        pendingAfterSave = null
        updateModeUi()
        val character = displayedCharacter
        if (character == null) showEmptyState() else renderPreview(character)
        updatePickButtons()
    }

    private fun updateModeUi() {
        val b = _binding ?: return
        val editing = editorState == EditorState.EDIT
        b.editContainer.visibility = if (editing) View.VISIBLE else View.GONE
        if (editing) {
            b.previewContainer.visibility = View.GONE
            b.emptyLayout.visibility = View.GONE
        }
        revertSwitch(editing)
        backCallback.isEnabled = editing
        // 편집 중 좌우 스와이프로 탭이 넘어가는 실수 방지
        (parentFragment as? SupplementFragment)?.setSwipeLocked(editing)
    }

    private fun revertSwitch(checked: Boolean) {
        val b = _binding ?: return
        suppressSwitchEvents = true
        b.switchEditMode.isChecked = checked
        suppressSwitchEvents = false
    }

    // ===== 드래프트 =====

    private fun collectDraft(): CharacterDraftPrefs.Draft {
        val novelId = selectedNovelIdFromSpinner() ?: -1L
        return CharacterDraftPrefs.Draft(
            name = binding.editName.text.toString(),
            firstName = binding.editFirstName.text.toString(),
            lastName = binding.editLastName.text.toString(),
            anotherName = binding.editAnotherName.text.toString(),
            tags = binding.editTags.text.toString(),
            memo = binding.editMemo.text.toString(),
            novelId = novelId,
            imagePaths = imageStrip.paths.toList(),
            fieldValues = formBuilder.widgetStateStrings(),
            savedAt = System.currentTimeMillis()
        )
    }

    private fun clearDraft() {
        suppressDraftSave = true
        val ctx = context?.applicationContext ?: return
        val id = displayedCharacter?.id ?: return
        CharacterDraftPrefs.clear(ctx, id)
    }

    /** 편집 화면과 같은 슬롯의 미저장 드래프트가 있으면 복원 제안 (B-6과 동일한 UX) */
    private fun maybeOfferDraftRestore(characterId: Long) {
        val ctx = context ?: return
        val draft = CharacterDraftPrefs.load(ctx, characterId) ?: return
        val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(draft.savedAt))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.draft_restore_title)
            .setMessage(getString(R.string.draft_restore_message, timeStr))
            .setPositiveButton(R.string.draft_restore_apply) { _, _ -> applyDraft(draft) }
            .setNegativeButton(R.string.draft_restore_discard) { _, _ ->
                CharacterDraftPrefs.clear(ctx, characterId)
            }
            .setNeutralButton(R.string.draft_restore_later, null)
            .show()
    }

    private fun applyDraft(draft: CharacterDraftPrefs.Draft) {
        val b = _binding ?: return
        if (editorState != EditorState.EDIT) return
        b.editName.setText(draft.name)
        b.editFirstName.setText(draft.firstName)
        b.editLastName.setText(draft.lastName)
        b.editAnotherName.setText(draft.anotherName)
        b.editTags.setText(draft.tags)
        b.editMemo.setText(draft.memo)

        if (draft.imagePaths.isNotEmpty()) {
            val validated = CharacterImageStripController.validateInternalPaths(
                draft.imagePaths, context?.filesDir
            )
            imageStrip.setPaths(validated)
        }

        // 동적 필드: 스피너 콜백의 buildForm 이후 소비되는 지연 메커니즘 재사용
        if (draft.novelId != -1L) {
            val index = novels.indexOfFirst { it.id == draft.novelId }
            if (index >= 0) {
                val bundle = Bundle()
                draft.fieldValues.forEach { (k, v) -> bundle.putString(k, v) }
                val targetPos = index + 1
                if (b.spinnerNovel.selectedItemPosition == targetPos && formBuilder.fieldDefinitions.isNotEmpty()) {
                    formBuilder.restoreFieldValues(bundle)
                } else {
                    pendingFieldValues = bundle
                    b.spinnerNovel.setSelection(targetPos)
                }
            }
        }

        hasUnsavedChanges = true
    }

    private fun selectedNovelIdFromSpinner(): Long? {
        val b = _binding ?: return null
        val position = b.spinnerNovel.selectedItemPosition
        return if (position > 0 && position - 1 < novels.size) novels[position - 1].id else null
    }

    // ===== 설정 =====

    private fun showRandomSettingsDialog() {
        val ctx = context ?: return
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_random_settings, null)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.random_settings_title)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val switchAutoSave = dialogView.findViewById<MaterialSwitch>(R.id.switchAutoSave)
        switchAutoSave.isChecked = RandomSupplementSettings.load(ctx).autoSaveOnExit
        switchAutoSave.setOnCheckedChangeListener { _, isChecked ->
            RandomSupplementSettings.save(ctx, RandomSupplementSettings(autoSaveOnExit = isChecked))
        }
        dialogView.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
