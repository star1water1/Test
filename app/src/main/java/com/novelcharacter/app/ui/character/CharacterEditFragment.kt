package com.novelcharacter.app.ui.character

import android.graphics.BitmapFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.LinearLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.novelcharacter.app.util.navigateSafe
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.FragmentCharacterEditBinding
import com.novelcharacter.app.ui.namebank.NameBankPickerSheet
import com.novelcharacter.app.ui.timeline.EventEditDialogFragment
import com.novelcharacter.app.util.CharacterDraftPrefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class CharacterEditFragment : Fragment(), EventEditDialogFragment.Host {

    private var _binding: FragmentCharacterEditBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()
    private val gson = Gson()

    private var characterId: Long = -1L
    private var presetNovelId: Long = -1L
    private var existingCharacter: Character? = null
    private var novels: List<Novel> = emptyList()
    // 이미지 스트립(경로 목록·썸네일·가져오기) — 공용 컨트롤러에 위임
    private lateinit var imageStrip: CharacterImageStripController
    private var restoredFromSavedState = false

    /**
     * 뷰가 죽을 때 챙겨 두는 폼 상태 (B-169·B-170) — **화면 이동 복귀의 복원 원천.**
     *
     * 썸네일을 탭해 뷰어로 갔다 오면 백스택 복귀라 `savedInstanceState`가 null이고, 조각
     * 인스턴스만 살아남는다. 종전에는 그 경로가 회전용 Bundle을 못 타 폼이 DB에서 다시 서고
     * 사용자에게 *롤백 + [복원] 다이얼로그*로 보였다(B-169) — 잃은 적 없는 것을 되찾겠냐고
     * 묻는 것 자체가 마찰이다(원칙 04). 이 필드가 그 경로를 무음 복원으로 바꾼다.
     *
     * **진짜 태스크 종료 후 복원과는 갈린다**(설계 물음 ⓒ) — 그쪽은 조각도 새로 나
     * 이 필드가 null이고, 종전대로 영구 드래프트 다이얼로그가 묻는다. 시스템 프로세스
     * 재생성은 `savedInstanceState`가 맡는다(같은 내용을 담는다 — [saveFormState]).
     */
    private var pendingViewState: Bundle? = null

    /**
     * [은행] 시트에서 고른 이름은행 엔트리 id (B-124 ⓐ) — **저장 시점에** 사용 표시가 걸린다.
     *
     * 즉시 걸지 않는 것이 이 화면의 계약이다(취소하면 무해해야 한다 — `pendingDeletes`와 같다).
     * 그래서 이것도 폼 상태이고, R-41이 요구하는 세 자리
     * ([saveFormState]·[collectDraft]·[applyDraft])에 함께 등재돼 있다.
     */
    private var pendingNameBankEntryId: Long? = null

    // 동적 필드 관리 — 폼 구성/값 적재/수집/검증은 공용 빌더에 위임
    private lateinit var formBuilder: DynamicFieldFormBuilder
    // ✨ AI 추천 진행 다이얼로그 — 실행 상태는 VM(aiSuggestRunning), 표시만 뷰 수명에 묶는다
    private var aiProgressDialog: androidx.appcompat.app.AlertDialog? = null
    // 저장 체인(검증→중복→연동 충돌→교차 세계관→DB) — 공용 코디네이터에 위임
    private lateinit var saveCoordinator: CharacterSaveCoordinator
    private var hasUnsavedChanges = false
    private var pendingFieldValues: Bundle? = null
    // 저장 완료/명시적 폐기 후 onPause의 드래프트 재기록 차단 (B-6)
    private var suppressDraftSave = false

    // 초기 적재(작품 목록·기존 캐릭터·동적 필드값) 완료 전 저장 차단 — 미적재 상태의 저장이
    // 빈 스냅샷으로 태그·필드값·작품 배정을 지우는(무음 유실) 것을 방지.
    // 신규 캐릭터는 지울 데이터가 없어 즉시 연다.
    private var saveGateOpen = false
    private var initialLoadsDone = false
    // 마지막으로 동적 폼 구성+값 적재가 끝난 스피너 위치 — 현재 선택과 일치할 때만 게이트를 연다
    private var lastHydratedNovelPos = -1
    // DB 필드값 적재(loadFieldValues) 중 프로그램적 setText가 더티를 세우지 않도록
    // (기존 캐릭터를 열기만 해도 미저장 상태가 되던 가짜 양성 차단)
    private var suppressFieldDirty = false

    // 보충 모드
    private var supplementMode = false
    private var supplementIndex = 0
    private var supplementIds = longArrayOf()
    private var supplementIssueLabels: String? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) imageStrip.importUris(uris)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveFormState(outState)
    }

    /**
     * 폼 상태를 한 벌로 담는다 — 회전·프로세스 재생성([onSaveInstanceState])과 화면 이동
     * 복귀([pendingViewState] — onDestroyView)가 **같은 내용**을 들어야 두 경로의 복원이
     * 갈리지 않는다.
     */
    private fun saveFormState(outState: Bundle) {
        outState.putStringArrayList("imagePaths", ArrayList(imageStrip.paths))

        // 동적 필드 입력값 보존.
        // 폼이 실제로 적재된 상태였는지도 함께 남긴다 — 적재 전에 회전하면 빈 Bundle이 저장되는데,
        // 복원 쪽이 그것을 "값이 없다"로 읽으면 DB 적재를 건너뛰어 필드값이 전량 삭제된다.
        val fieldValues = Bundle()
        val hydrated = ::formBuilder.isInitialized && formBuilder.fieldDefinitions.isNotEmpty()
        if (::formBuilder.isInitialized) formBuilder.saveStateTo(fieldValues)
        outState.putBundle("fieldValues", fieldValues)
        outState.putBoolean(STATE_FIELDS_HYDRATED, hydrated)

        // "지우기로 했다"와 "대표로 골랐다"도 폼 상태다 (B-170) — imagePaths만 싣고 이 둘을
        // 빠뜨리면, *뺐다는 사실*은 살아남고 *지우기로 했다는 사실*만 사라지는 비대칭이 된다
        // (사용자가 명시적으로 고른 조작이 말없이 무시되는 것 — 개발 의도 2번의 정면 위반).
        outState.putStringArrayList(STATE_PENDING_DELETES, ArrayList(imageStrip.pendingDeletes))
        outState.putString(STATE_REPRESENTATIVE, imageStrip.representativePath)

        // "은행에서 이 항목을 골랐다"도 폼 상태다 (B-124 ⓐ · R-41). 이름 글자만 살아남고
        // 어느 항목을 골랐는지가 사라지면 저장이 자동 대조로 조용히 강등된다 —
        // 동명 항목이 둘일 때 고른 것과 다른 것이 걸리는, B-170과 같은 부류의 비대칭이다.
        pendingNameBankEntryId?.let { outState.putLong(STATE_NAME_BANK_ENTRY, it) }

        // 미저장 입력 전체 — 복귀 시 무음 복원의 재료(B-169). 드래프트와 같은 그릇을 쓰는
        // 것은 적용 경로([applyDraft])를 하나로 두기 위해서다(두 벌이면 갈린다).
        if (hasUnsavedChanges && _binding != null && ::formBuilder.isInitialized) {
            outState.putString(STATE_DRAFT, gson.toJson(collectDraft()))
        }
    }

    /**
     * 영구 드래프트 자동저장 (B-6).
     * onSaveInstanceState는 태스크 제거 시 소실되므로, 화면 이탈 시점(onPause)에
     * 미저장 변경이 있으면 SharedPreferences 드래프트로도 기록한다.
     */
    override fun onPause() {
        super.onPause()
        if (_binding == null || suppressDraftSave || !hasUnsavedChanges) return
        val ctx = context?.applicationContext ?: return
        CharacterDraftPrefs.save(ctx, characterId, collectDraft())
    }

    private fun collectDraft(): CharacterDraftPrefs.Draft {
        val novelPosition = binding.spinnerNovel.selectedItemPosition
        val selectedNovelId =
            if (novelPosition > 0 && novelPosition - 1 < novels.size) novels[novelPosition - 1].id else -1L
        val fieldValues = formBuilder.widgetStateStrings()
        return CharacterDraftPrefs.Draft(
            name = binding.editName.text.toString(),
            firstName = binding.editFirstName.text.toString(),
            lastName = binding.editLastName.text.toString(),
            anotherName = binding.editAnotherName.text.toString(),
            tags = binding.editTags.text.toString(),
            memo = binding.editMemo.text.toString(),
            novelId = selectedNovelId,
            imagePaths = imageStrip.paths.toList(),
            fieldValues = fieldValues,
            savedAt = System.currentTimeMillis(),
            // B-170 — 이 둘이 빠져 있던 것이 결함의 본체다(Draft KDoc).
            pendingDeletePaths = imageStrip.pendingDeletes,
            representativePath = imageStrip.representativePath,
            // R-41 — 새 폼 상태 필드는 세 자리에 함께 등재한다(여기가 둘째).
            nameBankEntryId = pendingNameBankEntryId
        )
    }

    /** 저장 완료/명시적 폐기 시 드래프트 제거 + 이후 onPause의 재기록 차단 */
    private fun clearDraft() {
        suppressDraftSave = true
        val ctx = context?.applicationContext ?: return
        CharacterDraftPrefs.clear(ctx, characterId)
    }

    /** 태스크 제거 등으로 살아남은 미저장 드래프트가 있으면 복원 제안 (B-6) */
    private fun maybeOfferDraftRestore() {
        val ctx = context ?: return
        val draft = CharacterDraftPrefs.load(ctx, characterId) ?: return
        val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(draft.savedAt))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.draft_restore_title)
            .setMessage(getString(R.string.draft_restore_message, timeStr))
            .setPositiveButton(R.string.draft_restore_apply) { _, _ -> applyDraft(draft) }
            .setNegativeButton(R.string.draft_restore_discard) { _, _ ->
                // 저장된 드래프트만 버림 — 이후 새 변경은 다시 자동저장된다
                CharacterDraftPrefs.clear(ctx, characterId)
            }
            .setNeutralButton(R.string.draft_restore_later, null)
            .show()
    }

    private fun applyDraft(draft: CharacterDraftPrefs.Draft) {
        if (_binding == null) return
        binding.editName.setText(draft.name)
        binding.editFirstName.setText(draft.firstName)
        binding.editLastName.setText(draft.lastName)
        binding.editAnotherName.setText(draft.anotherName)
        binding.editTags.setText(draft.tags)
        binding.editMemo.setText(draft.memo)

        // 이미지 경로: 내부 저장소 경로만 수용 (회전 복원과 동일한 검증)
        if (draft.imagePaths.isNotEmpty()) {
            val validated = CharacterImageStripController.validateInternalPaths(
                draft.imagePaths, context?.filesDir
            )
            imageStrip.setPaths(validated)
        }

        // "지우기로 했다"·"대표로 골랐다"의 복원 (B-170). null은 *기록되지 않았다*이지
        // *없앴다*가 아니다 — 옛 드래프트(칸이 없던 시절)에서 없는 사실을 지우기로 넓혀
        // 읽으면 그것이 새 데이터 유실이다(Draft KDoc의 계약).
        draft.pendingDeletePaths?.let { pending ->
            imageStrip.restorePendingDeletes(
                CharacterImageStripController.validateInternalPaths(pending, context?.filesDir)
            )
        }
        draft.representativePath?.let { imageStrip.setRepresentativePath(it) }
        // R-41 — 세 자리 중 셋째. null은 *고른 적 없다*이지 *고르기를 취소했다*가 아니다.
        draft.nameBankEntryId?.let { pendingNameBankEntryId = it }

        // 동적 필드: 회전 복원과 동일한 지연 소비 메커니즘(pendingFieldValues) 재사용.
        // **빈 값 묶음은 보관하지 않는다** — 폼 적재 전에 담긴 드래프트가 그 모양인데,
        // 빈 묶음을 소비하면 DB 적재를 건너뛰어 필드값이 전량 삭제된다(saveFormState의
        // STATE_FIELDS_HYDRATED가 막는 것과 같은 무음 유실의 드래프트판).
        if (draft.novelId != -1L && draft.fieldValues.isNotEmpty()) {
            val index = novels.indexOfFirst { it.id == draft.novelId }
            if (index >= 0) {
                val bundle = Bundle()
                draft.fieldValues.forEach { (k, v) -> bundle.putString(k, v) }
                val targetPos = index + 1
                if (binding.spinnerNovel.selectedItemPosition == targetPos && formBuilder.fieldDefinitions.isNotEmpty()) {
                    // 해당 작품 기준으로 폼이 이미 빌드됨 — 즉시 적용
                    formBuilder.restoreFieldValues(bundle)
                } else {
                    // 스피너 콜백의 buildDynamicForm 이후 소비되도록 보관
                    pendingFieldValues = bundle
                    binding.spinnerNovel.setSelection(targetPos)
                }
            }
        }

        hasUnsavedChanges = true
        updateSaveButtonState()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        characterId = arguments?.getLong("characterId", -1L) ?: -1L
        presetNovelId = arguments?.getLong("novelId", -1L) ?: -1L
        supplementMode = arguments?.getBoolean("supplementMode", false) ?: false
        supplementIndex = arguments?.getInt("supplementIndex", 0) ?: 0
        supplementIds = arguments?.getLongArray("supplementIds") ?: longArrayOf()
        supplementIssueLabels = arguments?.getString("supplementIssueLabels")

        imageStrip = CharacterImageStripController(
            fragment = this,
            recyclerViewGetter = { _binding?.imageRecyclerView },
            navOriginDestId = R.id.characterEditFragment,
            onChanged = { hasUnsavedChanges = true; updateSaveButtonState() },
            onRemoved = { refreshRecommendations() }
        )

        formBuilder = DynamicFieldFormBuilder(
            containerGetter = { binding.dynamicFormContainer },
            contextGetter = { context },
            scopeGetter = { viewLifecycleOwner.lifecycleScope },
            isAlive = { _binding != null && isAdded },
            fragmentManagerGetter = { if (isAdded) childFragmentManager else null },
            viewModel = viewModel,
            onFieldChanged = {
                if (!suppressFieldDirty) {
                    hasUnsavedChanges = true
                    updateSaveButtonState()
                }
            }
        )
        // ✨ AI 필드 추천 — 필드별(폼 인라인 버튼) + 전체(폼 상단 버튼) 이중 진입 (원칙 04)
        formBuilder.aiSuggestHandler = { field ->
            // 경로 분기의 단일 소스는 필드 속성이다(사용자가 직접 정하고, 자동은 표시 형식으로 판단).
            // 서술형은 긴 글 전용 경로 — 짧은 값 추천기에 산문을 끼워 넣으면 둘 다 망가진다.
            if (com.novelcharacter.app.data.model.NarrativeMode.isNarrative(field)) {
                NarrativeWriteSheet.show(
                    this, field, characterId, formBuilder, viewModel,
                    imageStrip.paths.toList(), imageStrip.representativePath
                ) { buildAiContext() }
            } else {
                AiFieldSuggestSheet.showForField(
                    this, field, formBuilder, viewModel, characterId,
                    imageStrip.paths.toList(), imageStrip.representativePath
                ) { buildAiContext() }
            }
        }
        binding.btnAiSuggest.setOnClickListener {
            AiFieldSuggestSheet.showForCharacter(
                this, formBuilder, viewModel, characterId,
                // 보충 플로우에서는 기대치 조정 한 줄 — AI가 메울 수 있는 미흡은 필드 값뿐 (A-3 §5-1)
                extraNote = if (supplementMode) getString(R.string.ai_supplement_scope_note) else null,
                imagePaths = imageStrip.paths.toList(),
                representativePath = imageStrip.representativePath
            ) { buildAiContext() }
        }
        // AI 추천 실행 상태·결과 관측 — 실행은 VM(회전 생존)이 수행하므로 진행 다이얼로그와
        // 결과 다이얼로그가 화면 재생성을 넘어 복원된다. 결과 소비(clear)는 다이얼로그 액션 시점.
        viewModel.aiSuggestRunning.observe(viewLifecycleOwner) { running ->
            if (running == true) {
                if (aiProgressDialog == null) {
                    aiProgressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.ai_field_running)
                        .setCancelable(false)
                        .show()
                }
            } else {
                aiProgressDialog?.dismiss()
                aiProgressDialog = null
            }
        }
        viewModel.aiSuggestResult.observe(viewLifecycleOwner) { run ->
            if (run != null) {
                AiFieldSuggestSheet.showResult(this, formBuilder, viewModel, run) { buildAiContext() }
            }
        }
        // 서술형 작성 — 진행 다이얼로그는 추천 경로와 공유한다(동시 실행은 VM이 막는다).
        viewModel.aiNarrativeRunning.observe(viewLifecycleOwner) { running ->
            if (running == true) {
                if (aiProgressDialog == null) {
                    aiProgressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.ai_field_running)
                        .setCancelable(false)
                        .show()
                }
            } else {
                aiProgressDialog?.dismiss()
                aiProgressDialog = null
            }
        }
        viewModel.aiNarrativeResult.observe(viewLifecycleOwner) { run ->
            if (run != null) {
                NarrativeWriteSheet.showResult(this, formBuilder, viewModel, run) { id ->
                    formBuilder.fieldDefinitions.firstOrNull { it.id == id }
                }
            }
        }

        saveCoordinator = CharacterSaveCoordinator(
            fragment = this,
            viewModel = viewModel,
            // 순차 보충 모드는 기존 동작 유지: 중복 이름 검사 생략
            checkDuplicates = !supplementMode,
            host = object : CharacterSaveCoordinator.Host {
                override fun snapshot() = formSnapshot()
                override fun collectFieldValues(characterId: Long) = formBuilder.collectFieldValues(characterId)
                override fun coveredFieldDefinitionIds() = formBuilder.coveredFieldDefinitionIds()
                override fun validateRequiredFields() = formBuilder.validateRequiredFields()
                override fun emptyRequiredNoticeCount() = formBuilder.emptyRequiredNoticeCount()
                override fun editingCharacterId() = characterId
                override fun existingCharacter() = existingCharacter
                override fun onSavingChanged(saving: Boolean) {
                    if (_binding == null) return
                    if (supplementMode) binding.btnSaveAndNext.isEnabled = !saving
                    else binding.btnSave.isEnabled = !saving
                }
                override fun onSaved(savedCharacterId: Long) {
                    clearDraft()
                    if (!isAdded || view == null) return
                    if (supplementMode) navigateToNextSupplement()
                    else findNavController().popBackStack()
                }
                override fun pendingImageDeletes() = imageStrip.pendingDeletes
                override fun onPendingImageDeletesApplied(deleted: Int, protectedCount: Int) {
                    imageStrip.clearPendingDeletes()
                    if (!isAdded) return
                    // 못 지운 것을 조용히 넘기지 않는다 — 사용자는 지웠다고 알고 나간다.
                    val ctx = context ?: return
                    if (protectedCount > 0) {
                        Toast.makeText(
                            ctx,
                            getString(R.string.image_remove_pending_delete_protected, protectedCount),
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (deleted > 0) {
                        Toast.makeText(
                            ctx,
                            getString(R.string.image_remove_pending_delete_done, deleted),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                override fun requestedNameBankEntryId() = pendingNameBankEntryId
                override fun onNameBankLinkApplied() {
                    // 한 저장이 한 번만 쓴다 — 남겨 두면 다음 저장이 옛 선택을 다시 걸려 든다.
                    pendingNameBankEntryId = null
                }
            }
        )
        saveCoordinator.registerResultListeners() // 회전 안전(P1-A): 결과 리스너를 onViewCreated에서 1회 등록

        if (supplementMode) {
            setupSupplementMode()
        }

        // 복원 원천은 둘이다 (B-169): 회전·프로세스 재생성은 savedInstanceState, **화면 이동
        // 복귀(백스택)는 pendingViewState** — 종전에는 뒤엣것이 없어 뷰어에 갔다 오는 것만으로
        // 폼이 DB로 롤백됐다. 회전은 둘 다 있고 내용이 같다([saveFormState] 한 벌).
        val restoreSource = savedInstanceState ?: pendingViewState
        pendingViewState = null

        // Restore imagePaths from saved state, filtering invalid paths
        restoreSource?.getStringArrayList("imagePaths")?.let { saved ->
            val validated = CharacterImageStripController.validateInternalPaths(
                saved, context?.filesDir
            )
            imageStrip.setPaths(validated)
            restoredFromSavedState = true
        }
        // 회전 시점에 동적 폼이 아직 적재되지 않았다면 그때 저장된 빈 Bundle은 "값이 없다"가
        // 아니라 "아직 모른다"다. 그걸 복원값으로 소비하면 DB 적재 경로를 건너뛰어 폼이 빈 채로
        // 남고, 저장이 폼 커버 범위의 필드값을 전량 삭제한다 (N2와 같은 무음 유실의 다른 문).
        pendingFieldValues = if (restoreSource?.getBoolean(STATE_FIELDS_HYDRATED) == true) {
            restoreSource.getBundle("fieldValues")
        } else {
            null
        }

        binding.toolbar.setNavigationOnClickListener { handleBackPress() }

        // 시스템 뒤로가기 버튼 인터셉트
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            }
        )
        binding.toolbar.title = if (characterId == -1L) getString(R.string.add_character) else getString(R.string.edit_character)

        setupImageButton()
        setupRecommendations()
        setupSaveButton()
        setupNameBankButton()
        setupEventButton()
        setupChangeTracking()

        // Show restored images if any (from rotation)
        if (imageStrip.paths.isNotEmpty()) {
            imageStrip.refresh()
        }

        // 저장 게이트는 뷰 수명 단위로 다시 잠근다. 게이트 3필드는 프래그먼트 필드라
        // 뷰만 재생성되는 복귀(이미지 뷰어 등 전진 내비, 보충 체인 뒤로가기, 탭 전환)에도
        // 살아남는데, 아래의 버튼 비활성화는 뷰마다 다시 실행된다. 지난 뷰에서 열린 래치가
        // 남아 있으면 maybeOpenSaveGate가 첫 줄에서 반환해 버튼이 영영 다시 켜지지 않고,
        // 재적재 더티 억제(initialHydration = !saveGateOpen)도 같은 래치를 보고 어긋난다.
        saveGateOpen = false
        initialLoadsDone = false
        lastHydratedNovelPos = -1

        // 기존 캐릭터는 초기 적재 완료까지 저장 차단 (maybeOpenSaveGate가 다시 연다)
        if (characterId != -1L) {
            if (supplementMode) binding.btnSaveAndNext.isEnabled = false
            else binding.btnSave.isEnabled = false
        } else {
            saveGateOpen = true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            loadNovels()
            if (characterId != -1L) {
                loadExistingCharacter()
                if (_binding == null) return@launch
                initialLoadsDone = true
                maybeOpenSaveGate()
            }
            if (restoreSource != null) {
                // **무음 복원** (B-169) — 같은 세션의 상태가 눈앞에 있는데 *되찾겠냐고* 묻는
                // 것이 종전의 마찰이었다(사용자가 잃은 적 없는 것이다 — 원칙 04). DB 적재
                // 뒤에 얹어야 하므로 여기이고, 적용 경로는 드래프트와 같은 한 벌이다.
                restoreFormStateSilently(restoreSource)
            } else {
                // 조각이 새로 났다 = 진짜 새 진입이거나 태스크 종료 후다(설계 물음 ⓒ의
                // 구별) — 영구 드래프트는 세션을 넘은 값이라 종전대로 **묻는다** (B-6).
                maybeOfferDraftRestore()
            }
        }
    }

    /**
     * 화면 이동·회전 복귀의 무음 복원 (B-169·B-170) — [saveFormState]가 담은 것을 되살린다.
     *
     * 이미지·필드값 묶음은 onViewCreated 앞머리가 이미 복원했고([restoredFromSavedState] ·
     * [pendingFieldValues]), 여기서는 나머지를 얹는다: 미저장 입력 전체(있을 때만 — 없으면
     * DB 값이 곧 진실이라 얹을 것이 없다)와 **삭제 예약·대표 지정**(미저장 여부와 무관하게
     * 폼 상태다 — 회전만 해도 ☆이 풀리던 것이 이 두 줄로 닫힌다).
     */
    private fun restoreFormStateSilently(state: Bundle) {
        if (_binding == null) return
        state.getString(STATE_DRAFT)?.let { json ->
            val draft = try {
                gson.fromJson(json, CharacterDraftPrefs.Draft::class.java)
            } catch (_: Exception) {
                null
            }
            draft?.let { applyDraft(it) }
        }
        state.getStringArrayList(STATE_PENDING_DELETES)?.let { pending ->
            imageStrip.restorePendingDeletes(
                CharacterImageStripController.validateInternalPaths(pending, context?.filesDir)
            )
        }
        state.getString(STATE_REPRESENTATIVE)?.let { imageStrip.setRepresentativePath(it) }
        // 은행에서 고른 항목 (B-124 ⓐ). `containsKey`로 보는 이유는 **없는 것과 0이 다른
        // 사실**이기 때문이다 — `getLong`의 기본값 0L을 그대로 받으면 고른 적 없는 폼이
        // *id 0번을 골랐다*고 말하게 된다(R-36이 엑셀 열에서 가른 것과 같은 축).
        if (state.containsKey(STATE_NAME_BANK_ENTRY)) {
            pendingNameBankEntryId = state.getLong(STATE_NAME_BANK_ENTRY)
        }
    }

    /**
     * 초기 적재가 끝나고, 동적 폼이 현재 선택된 작품 기준으로 구성·적재 완료된 순간 저장을 연다.
     * 스피너 콜백 코루틴과 초기 적재 코루틴 중 무엇이 먼저 끝나도 동작한다(양방향 래치).
     */
    private fun maybeOpenSaveGate() {
        if (saveGateOpen || !initialLoadsDone) return
        val b = _binding ?: return
        if (lastHydratedNovelPos != b.spinnerNovel.selectedItemPosition) return
        saveGateOpen = true
        if (supplementMode) b.btnSaveAndNext.isEnabled = true else b.btnSave.isEnabled = true
    }

    private suspend fun loadNovels() {
        novels = viewModel.getAllNovelsList()
        if (_binding == null || !isAdded) return
        val novelNames = mutableListOf(getString(R.string.no_novel_selected))
        novelNames.addAll(novels.map { it.title })

        val ctx = context ?: return
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, novelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerNovel.adapter = adapter

        // 작품 선택 시 해당 universe의 동적 필드 로드
        binding.spinnerNovel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (position > 0) {
                        val novel = novels[position - 1]
                        val universeId = novel.universeId
                        formBuilder.currentUniverseId = universeId
                        // 실루엣 편집기의 상대 생성·분포·작품 평균이 이 작품 축으로 모인다.
                        formBuilder.currentNovelId = novel.id
                        if (universeId != null) {
                            formBuilder.fieldDefinitions = viewModel.getFieldsByUniverseList(universeId)
                        } else {
                            // 세계관 없는 작품 — 전역 필드는 그래도 가진다 (B-119 확장,
                            // 2026.08.07 사용자 확정). 종전에는 빈 목록이라 무소속 캐릭터만
                            // "전역" 필드가 없는 역설이 있었다.
                            formBuilder.fieldDefinitions = viewModel.getGlobalFieldsList()
                        }
                    } else {
                        formBuilder.currentNovelId = null
                        // 작품 미선택(완전 무소속)도 같다 — 전역 구역의 필드를 그린다.
                        formBuilder.fieldDefinitions = viewModel.getGlobalFieldsList()
                    }
                    if (_binding == null) return@launch
                    formBuilder.buildForm()
                    // AI 추천은 세계관 필드가 대상 — 렌더된 필드가 있을 때만 진입 버튼 노출
                    binding.btnAiSuggest.visibility =
                        if (formBuilder.fieldDefinitions.isEmpty()) View.GONE else View.VISIBLE

                    // 회전 복원된 필드값이 있으면 우선 적용, 없으면 DB에서 로드.
                    // 스피너 초기(position 0) 콜백은 폼이 비어 있으므로 복원값을 소비하지 않고 보존한다
                    // (여기서 소거하면 이후 실제 작품 선택 콜백이 DB 값으로 덮어써 회전 직전 입력이 유실됨)
                    val saved = pendingFieldValues
                    if (saved != null && formBuilder.fieldDefinitions.isNotEmpty()) {
                        formBuilder.restoreFieldValues(saved)
                        pendingFieldValues = null
                    } else if (saved == null) {
                        val existing = existingCharacter
                        if (existing != null) {
                            // 초기 하이드레이션의 DB 값 적재는 사용자 변경이 아니다 — 워처의
                            // 가짜 미저장(캐릭터를 열기만 해도 더티) 차단. 게이트가 열린 뒤의
                            // 재적재(사용자 작품 변경)는 억제하지 않아 기존 더티 보호를 유지한다.
                            // (회전/드래프트 복원 경로도 복원값이 곧 미저장 입력이므로 억제 없음)
                            val initialHydration = !saveGateOpen
                            if (initialHydration) suppressFieldDirty = true
                            try {
                                formBuilder.loadFieldValues(existing.id)
                            } finally {
                                if (initialHydration) suppressFieldDirty = false
                            }
                        }
                    }

                    lastHydratedNovelPos = position
                    maybeOpenSaveGate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 미리 지정된 작품이 있으면 선택
        if (presetNovelId != -1L) {
            val index = novels.indexOfFirst { it.id == presetNovelId }
            if (index >= 0) binding.spinnerNovel.setSelection(index + 1)
        }
    }

    private suspend fun loadExistingCharacter() {
        existingCharacter = viewModel.getCharacterByIdSuspend(characterId)
        if (_binding == null) return
        existingCharacter?.let { fillForm(it) }

        // 태그 로드
        val tags = viewModel.getTagsByCharacterList(characterId)
        if (_binding == null) return
        binding.editTags.setText(tags.joinToString(", ") { it.tag })

        // fillForm과 태그 로드로 인해 TextWatcher가 트리거되어 false positive가 발생하므로 초기화
        hasUnsavedChanges = false
    }

    private fun fillForm(character: Character) {
        binding.editName.setText(character.name)
        binding.editFirstName.setText(character.firstName)
        binding.editLastName.setText(character.lastName)
        binding.editAnotherName.setText(character.anotherName)

        // 작품 선택
        character.novelId?.let { novelId ->
            val index = novels.indexOfFirst { it.id == novelId }
            if (index >= 0) binding.spinnerNovel.setSelection(index + 1)
        }

        // 이미지 — 회전 복원된 경우 사용자가 추가한 이미지를 보존
        if (!restoredFromSavedState) {
            val paths: List<String> = try {
                gson.fromJson(character.imagePaths, com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            imageStrip.setPaths(paths)
            // 대표 지정도 함께 되살린다(B-103 D7) — 안 하면 편집창을 열 때마다 ☆가 꺼져
            // 있고, 그 상태로 저장하면 지정이 사라진다.
            imageStrip.setRepresentativePath(character.representativeImagePath)
        } else {
            imageStrip.refresh()
        }

        // 메모
        binding.editMemo.setText(character.memo)
    }

    private fun setupImageButton() {
        binding.btnAddImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
        binding.btnPickFromLibrary.setOnClickListener { openLibraryPicker() }

        imageStrip.attach()
    }

    /**
     * 라이브러리 피커를 연다 — 추천 스트립과 **같은 첨부 자리**를 지난다.
     *
     * 추천은 태그가 겹칠 때만 열리므로(교집합 0이면 섹션이 숨는다) 태그를 쓰지 않는 사용자에게는
     * 라이브러리로 가는 문이 없었다. 이 버튼이 그 문이고, 목록은 태그와 무관하게 항상 열린다.
     */
    private fun openLibraryPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = viewModel.getLibraryImages()
            if (!isAdded || _binding == null) return@launch
            if (data.images.isEmpty()) {
                Toast.makeText(
                    requireContext(), R.string.image_library_picker_empty, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val sheet = ImageLibraryPickerBottomSheet()
            sheet.images = data.images
            // 이미 붙어 있는 것은 뺀다 — canonical로 맞춰야 같은 파일의 다른 표기를 걸러낸다
            // (추천 스트립의 excluded 계산과 같은 규칙이다).
            val currentCanon = imageStrip.paths.mapTo(HashSet()) { canonicalOrSelf(it) }
            sheet.excludePaths = data.images
                .filter { canonicalOrSelf(it.path) in currentCanon }
                .mapTo(HashSet()) { it.path }
            sheet.onConfirm = { picked -> attachLibraryImages(picked, data.metas) }
            sheet.show(parentFragmentManager, "image_library_picker")
        }
    }

    /**
     * 고른 라이브러리 이미지를 붙인다.
     *
     * **링크 그룹 확장을 여기서도 한다** — 추천 첨부([attachRecommendedImage])와 같은 규칙이라야
     * "추천으로 붙이면 묶음이 따라오는데 피커로 붙이면 안 따라온다"는 갈림이 생기지 않는다.
     */
    private fun attachLibraryImages(
        paths: List<String>,
        metas: List<com.novelcharacter.app.util.ImageLinkResolver.Meta>
    ) {
        if (paths.isEmpty()) return
        val expansion = com.novelcharacter.app.util.ImageLinkResolver.expand(paths, metas)
        val currentCanon = imageStrip.paths.mapTo(HashSet()) { canonicalOrSelf(it) }
        val toAdd = expansion.allPaths.filter { canonicalOrSelf(it) !in currentCanon }
        if (toAdd.isEmpty()) return
        // 첨부는 미저장 변경 — addPaths가 더티 훅을 호출해 무음 유실을 막는다
        imageStrip.addPaths(toAdd)
        if (isAdded) {
            Toast.makeText(
                requireContext(),
                getString(R.string.image_library_picker_attached, toAdd.size),
                Toast.LENGTH_SHORT
            ).show()
        }
        // 붙인 것은 추천에서 빠져야 한다(추천은 첨부된 경로를 제외한다)
        refreshRecommendations()
    }

    // ===== 추천 이미지 스트립 (G3) =====

    private var recCandidates: List<com.novelcharacter.app.util.ImageRecommendationHelper.Candidate> = emptyList()
    private var recMetas: List<com.novelcharacter.app.util.ImageLinkResolver.Meta> = emptyList()
    private var recFetched = false
    private var recMatchJob: kotlinx.coroutines.Job? = null
    private var recommendedAdapter: RecommendedImageAdapter? = null

    /**
     * 태그 매칭 추천(D9): 후보는 에디터 오픈당 **1회만** 비동기 페치하고,
     * 이후 태그 입력(400ms 디바운스)·첨부·삭제 시에는 인메모리 재매칭만 수행한다.
     */
    private fun setupRecommendations() {
        binding.recommendationRecyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        recommendedAdapter = RecommendedImageAdapter(viewLifecycleOwner.lifecycleScope) { rec ->
            attachRecommendedImage(rec)
        }
        binding.recommendationRecyclerView.adapter = recommendedAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            val data = viewModel.getRecommendationCandidates()
            if (_binding == null) return@launch
            recCandidates = data.candidates
            recMetas = data.metas
            recFetched = true
            refreshRecommendations()
        }

        binding.editTags.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                recMatchJob?.cancel()
                recMatchJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(400)
                    refreshRecommendations()
                }
            }
        })
    }

    private fun canonicalOrSelf(path: String): String =
        try { java.io.File(path).canonicalPath } catch (_: Exception) { path }

    private fun refreshRecommendations() {
        if (!recFetched || _binding == null) return
        val tags = binding.editTags.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val excluded = HashSet<String>()
        for (p in imageStrip.paths) {
            excluded.add(p)
            excluded.add(canonicalOrSelf(p))
        }
        val recs = com.novelcharacter.app.util.ImageRecommendationHelper.recommend(tags, recCandidates, excluded)
        binding.recommendationSection.visibility = if (recs.isEmpty()) View.GONE else View.VISIBLE
        recommendedAdapter?.submitList(recs)
    }

    /**
     * 추천 탭 = 첨부. 링크 그룹은 전원 함께 첨부(D5 — 사용자 명시 요구).
     * DB 쓰기는 저장 시점의 imagePaths 반영으로 일원화 — 편집 취소 시 무해(라이브러리 meta가 파일 보호).
     */
    private fun attachRecommendedImage(rec: com.novelcharacter.app.util.ImageRecommendationHelper.Recommendation) {
        val expansion = com.novelcharacter.app.util.ImageLinkResolver.expand(listOf(rec.candidate.path), recMetas)
        val currentCanon = imageStrip.paths.mapTo(HashSet()) { canonicalOrSelf(it) }
        val toAdd = expansion.allPaths.filter { canonicalOrSelf(it) !in currentCanon }
        if (toAdd.isEmpty()) return
        // 첨부는 미저장 변경 — addPaths가 더티 훅(onChanged)을 호출해 무음 유실을 막는다
        imageStrip.addPaths(toAdd)
        val linkedExtra = toAdd.size - 1
        if (linkedExtra > 0 && isAdded) {
            Toast.makeText(
                requireContext(),
                getString(R.string.image_recommend_attached_with_link, linkedExtra),
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshRecommendations()
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            saveCoordinator.requestSave()
        }
    }

    /**
     * 이름 줄의 [은행] — 창고에서 꺼내고 넣는 두 방향을 한 창에 연다 (B-124 ⓐ).
     *
     * 고른 것을 **즉시 쓰지 않는다**: 이름 칸만 채우고 [pendingNameBankEntryId]에 담아
     * 저장 시점에 사용 표시가 걸린다(편집 취소 계약). 반대로 **담기는 즉시 쓴다** —
     * 그쪽은 캐릭터 편집과 무관한 창고 조작이라 취소로 되돌릴 대상이 아니다.
     */
    private fun setupNameBankButton() {
        binding.btnNameBank.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val (entries, usedByNames) = viewModel.nameBankPickerData()
                if (_binding == null || !isAdded) return@launch
                // 상태 저장 후면 show()가 IllegalStateException — 생략해도 1탭으로 재시도된다
                // (주입식 시트 공통 계약, `openBulkRegisterSheet`와 같은 가드).
                if (parentFragmentManager.isStateSaved) return@launch
                val sheet = NameBankPickerSheet()
                sheet.setup = NameBankPickerSheet.Setup(
                    entries = entries,
                    usedByNames = usedByNames,
                    preferredGender = currentGenderValue(),
                    currentName = binding.editName.text.toString()
                )
                sheet.onPick = { entry ->
                    if (_binding != null) {
                        binding.editName.setText(entry.name)
                        // 사용 중인 항목은 걸리지 않으므로(빼앗기 금지) 들고 갈 필요가 없다 —
                        // 들고 가면 저장 때 "옮기지 않았습니다" 고지만 한 번 더 뜬다.
                        pendingNameBankEntryId = if (entry.isUsed) null else entry.id
                        hasUnsavedChanges = true
                        updateSaveButtonState()
                    }
                }
                sheet.onSaveCurrent = { name ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val ctx = context?.applicationContext
                        try {
                            viewModel.saveNameToBank(name, currentGenderValue().orEmpty())
                            ctx?.let {
                                Toast.makeText(it, getString(R.string.name_bank_save_current_done, name), Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CharacterEditFragment", "Failed to save name to bank", e)
                            ctx?.let { Toast.makeText(it, R.string.name_bank_save_current_failed, Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
                sheet.show(parentFragmentManager, NameBankPickerSheet.TAG)
            }
        }
    }

    /**
     * 폼에 지금 적혀 있는 성별 값 — [SemanticRole.GENDER]를 단 필드에서 읽는다. 없거나 비면 null이고, 그때 선택 시트의 성별 축은 무효다.
     *
     * **DB가 아니라 폼에서 읽는다** — 편집 중에 성별을 바꾼 사용자에게는 그쪽이 지금의 사실이다.
     * **라벨로 추측하지 않는다**(R-20) — 역할 표식이 유일한 근거다.
     */
    private fun currentGenderValue(): String? {
        if (!::formBuilder.isInitialized) return null
        val genderField = formBuilder.fieldDefinitions.firstOrNull {
            SemanticRole.fromConfig(it.config) == SemanticRole.GENDER
        } ?: return null
        return formBuilder.collectFieldValues(characterId)
            .firstOrNull { it.fieldDefinitionId == genderField.id }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * AI 추천 컨텍스트 조립 — 규칙은 [CharacterAiContextBuilder]가 단일 소스다
     * (보충 랜덤 탭 인라인 편집과 공유 — 복사하면 두 화면의 추천 근거가 갈린다).
     */
    private suspend fun buildAiContext(): com.novelcharacter.app.ai.CharacterFieldAiSuggester.CharacterAiContext =
        CharacterAiContextBuilder.build(object : CharacterAiContextBuilder.Host {
            override fun rawName() = binding.editName.text.toString()
            override fun firstName() = binding.editFirstName.text.toString()
            override fun lastName() = binding.editLastName.text.toString()
            override fun aliasesRaw() = binding.editAnotherName.text.toString()
            override fun tagsRaw() = binding.editTags.text.toString()
            override fun memo() = binding.editMemo.text.toString()
            override fun imagePaths() = imageStrip.paths.toList()
            override fun characterId() = characterId
            override fun formBuilder() = formBuilder
        }, viewModel)

    /** 저장 시점의 폼 입력 스냅샷 — 코디네이터가 최신 폼 기준으로 Character를 조립할 때 사용 */
    private fun formSnapshot(): CharacterSaveCoordinator.FormSnapshot {
        val novelPosition = binding.spinnerNovel.selectedItemPosition
        val selectedNovelId = if (novelPosition > 0 && novelPosition - 1 < novels.size) novels[novelPosition - 1].id else null
        return CharacterSaveCoordinator.FormSnapshot(
            name = binding.editName.text.toString(),
            firstName = binding.editFirstName.text.toString(),
            lastName = binding.editLastName.text.toString(),
            anotherName = binding.editAnotherName.text.toString(),
            tags = binding.editTags.text.toString(),
            memo = binding.editMemo.text.toString(),
            novelId = selectedNovelId,
            imagePaths = imageStrip.paths.toList(),
            representativeImagePath = imageStrip.representativePath
        )
    }

    private fun setupEventButton() {
        binding.btnAddEvent.setOnClickListener {
            // 새 캐릭터(아직 저장 안 됨)면 사건 추가 불가
            if (characterId == -1L) {
                Toast.makeText(requireContext(), R.string.save_character_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val novelPosition = binding.spinnerNovel.selectedItemPosition
            val selectedNovelId = if (novelPosition > 0 && novelPosition - 1 < novels.size) novels[novelPosition - 1].id else null
            EventEditDialogFragment.show(
                childFragmentManager,
                preSelectedCharacterIds = setOf(characterId),
                preSelectedNovelIds = listOfNotNull(selectedNovelId)
            )
        }
    }

    override fun eventDialogDataProvider(): EventEditDialogFragment.DataProvider =
        object : EventEditDialogFragment.DataProvider {
            override suspend fun getAllNovelsList(): List<Novel> = viewModel.getAllNovelsList()
            override suspend fun getAllCharactersList(): List<com.novelcharacter.app.data.model.Character> = viewModel.getAllCharactersList()
            override suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> = viewModel.getCharacterIdsForEvent(eventId)
            override suspend fun getNovelIdsForEvent(eventId: Long): List<Long> = viewModel.getNovelIdsForEvent(eventId)
            override suspend fun getEventFieldsForUniverse(universeId: Long) = viewModel.getEventFieldsForUniverse(universeId)
            override suspend fun getEventFieldValuesForEvent(eventId: Long) = viewModel.getEventFieldValuesForEvent(eventId)
            override suspend fun insertEventField(field: com.novelcharacter.app.data.model.FieldDefinition) =
                viewModel.insertEventField(field)
            override fun insertEvent(event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission) {
                viewModel.insertEvent(event, characterIds, novelIds, fieldSubmission)
            }
            override fun updateEvent(event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission) {
                viewModel.updateEvent(event, characterIds, novelIds, fieldSubmission)
            }
            override suspend fun getEventsInScope(novelIds: List<Long>, universeId: Long?): List<com.novelcharacter.app.data.model.TimelineEvent> {
                return when {
                    novelIds.isNotEmpty() ->
                        novelIds.flatMap { viewModel.getEventsByNovelList(it) }.distinctBy { it.id }
                    universeId != null -> viewModel.getEventsByUniverseList(universeId)
                    else -> viewModel.getAllEventsList()
                }
            }
            override fun updateEventAndShiftOthers(
                event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>,
                shiftDirection: EventEditDialogFragment.ShiftDirection,
                delta: Int, originalNovelIds: List<Long>, originalUniverseId: Long?,
                fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission
            ) {
                viewModel.updateEventAndShiftOthers(event, characterIds, novelIds, shiftDirection, delta, originalNovelIds, originalUniverseId, fieldSubmission)
            }
        }

    private fun handleBackPress() {
        if (hasUnsavedChanges) {
            val ctx = context ?: return
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.unsaved_changes_title)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    // 명시적 폐기 — 드래프트도 함께 제거 (재진입 시 되살아나지 않도록)
                    clearDraft()
                    findNavController().popBackStack()
                }
                .setNegativeButton(R.string.stay, null)
                .show()
        } else {
            findNavController().popBackStack()
        }
    }

    private fun setupChangeTracking() {
        val changeWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                hasUnsavedChanges = true
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        binding.editName.addTextChangedListener(changeWatcher)
        binding.editFirstName.addTextChangedListener(changeWatcher)
        binding.editLastName.addTextChangedListener(changeWatcher)
        binding.editAnotherName.addTextChangedListener(changeWatcher)
        binding.editMemo.addTextChangedListener(changeWatcher)
        binding.editTags.addTextChangedListener(changeWatcher)
    }

    private fun updateSaveButtonState() {
        if (_binding == null) return
        if (hasUnsavedChanges) {
            binding.btnSave.alpha = 1f
            binding.btnSave.text = getString(R.string.save_unsaved)
        }
    }

    // ===== 보충 모드 =====

    private fun setupSupplementMode() {
        // 배너 표시
        binding.supplementBanner.visibility = View.VISIBLE
        binding.supplementProgressText.text = getString(
            R.string.supplement_progress_format,
            supplementIndex + 1,
            supplementIds.size
        )

        // 미흡 항목 표시
        val labels = supplementIssueLabels
        if (!labels.isNullOrBlank()) {
            binding.supplementIssueText.text = getString(R.string.supplement_banner_issues, labels)
        } else {
            binding.supplementIssueText.text = ""
        }

        // 기존 저장/사건 버튼 숨기기, 보충 버튼 표시
        binding.btnSave.visibility = View.GONE
        binding.btnAddEvent.visibility = View.GONE
        binding.supplementButtonLayout.visibility = View.VISIBLE

        // 저장 & 다음 — 공용 코디네이터 사용 (중복 검사 생략은 checkDuplicates=false로 유지)
        binding.btnSaveAndNext.setOnClickListener {
            saveCoordinator.requestSave()
        }

        // 건너뛰기
        binding.btnSkip.setOnClickListener {
            navigateToNextSupplement()
        }

        // 툴바 제목 변경
        binding.toolbar.title = getString(R.string.supplement_title)
    }

    private fun navigateToNextSupplement() {
        val nextIndex = supplementIndex + 1
        if (nextIndex >= supplementIds.size) {
            Toast.makeText(requireContext(), R.string.supplement_queue_done, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack(R.id.supplementFragment, false)
            return
        }
        val issueLabelsArray = arguments?.getStringArray("supplementIssueLabelsArray")
        val bundle = Bundle().apply {
            putLong("characterId", supplementIds[nextIndex])
            putBoolean("supplementMode", true)
            putInt("supplementIndex", nextIndex)
            putLongArray("supplementIds", supplementIds)
            if (issueLabelsArray != null) {
                putStringArray("supplementIssueLabelsArray", issueLabelsArray)
                putString("supplementIssueLabels", issueLabelsArray.getOrNull(nextIndex) ?: "")
            }
        }
        val navOptions = androidx.navigation.NavOptions.Builder()
            .setPopUpTo(R.id.supplementFragment, inclusive = false)
            .build()
        findNavController().navigate(R.id.characterEditFragment, bundle, navOptions)
    }

    override fun onDestroyView() {
        // 화면 이동 복귀(백스택)의 복원 원천을 챙긴다 (B-169·B-170) — 이 조각은 살아남고
        // 뷰만 죽는 경로라 savedInstanceState가 없다. **detach·binding 해제보다 먼저**여야
        // 읽을 것이 남아 있다.
        if (_binding != null) {
            pendingViewState = Bundle().also { saveFormState(it) }
        }
        // 회전 시 액티비티 파괴 전에 진행 다이얼로그를 닫는다 — 파괴된 윈도우 dismiss 크래시 방지.
        // 재생성 뷰의 aiSuggestRunning 관측이 필요하면 다시 띄운다.
        aiProgressDialog?.dismiss()
        aiProgressDialog = null
        imageStrip.detach()
        binding.recommendationRecyclerView.adapter = null
        recMatchJob?.cancel()
        super.onDestroyView()
        recommendedAdapter = null
        _binding = null
    }

    private companion object {
        /** 회전 저장 시점에 동적 폼이 적재돼 있었는가 — 빈 Bundle의 의미를 가르는 표시 */
        const val STATE_FIELDS_HYDRATED = "fieldsHydrated"

        /** "앱에서 삭제" 예약 (B-170) — imagePaths와 같은 수명이어야 하는 폼 상태다. */
        const val STATE_PENDING_DELETES = "pendingDeletePaths"

        /** 대표 이미지 지정 (B-170의 형제) — 회전만 해도 풀리던 ☆. */
        const val STATE_REPRESENTATIVE = "representativePath"

        /** 미저장 입력 전체(드래프트 JSON) — 화면 이동·회전 복귀의 무음 복원 재료 (B-169). */
        const val STATE_DRAFT = "silentRestoreDraft"

        /** [은행]에서 고른 항목 (B-124 ⓐ) — 저장 시점에 쓰이므로 저장 전까지 살아 있어야 한다. */
        const val STATE_NAME_BANK_ENTRY = "nameBankEntryId"
    }
}
