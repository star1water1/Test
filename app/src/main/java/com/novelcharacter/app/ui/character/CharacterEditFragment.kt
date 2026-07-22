package com.novelcharacter.app.ui.character

import android.graphics.BitmapFactory
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

    // 동적 필드 관리 — 폼 구성/값 적재/수집/검증은 공용 빌더에 위임
    private lateinit var formBuilder: DynamicFieldFormBuilder
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
        outState.putStringArrayList("imagePaths", ArrayList(imageStrip.paths))

        // 동적 필드 입력값 보존
        val fieldValues = Bundle()
        if (::formBuilder.isInitialized) formBuilder.saveStateTo(fieldValues)
        outState.putBundle("fieldValues", fieldValues)
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
            savedAt = System.currentTimeMillis()
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
        AlertDialog.Builder(ctx)
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

        // 동적 필드: 회전 복원과 동일한 지연 소비 메커니즘(pendingFieldValues) 재사용
        if (draft.novelId != -1L) {
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

        saveCoordinator = CharacterSaveCoordinator(
            fragment = this,
            viewModel = viewModel,
            // 순차 보충 모드는 기존 동작 유지: 중복 이름 검사 생략
            checkDuplicates = !supplementMode,
            host = object : CharacterSaveCoordinator.Host {
                override fun snapshot() = formSnapshot()
                override fun collectFieldValues(characterId: Long) = formBuilder.collectFieldValues(characterId)
                override fun validateRequiredFields() = formBuilder.validateRequiredFields()
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
            }
        )
        saveCoordinator.registerResultListeners() // 회전 안전(P1-A): 결과 리스너를 onViewCreated에서 1회 등록

        if (supplementMode) {
            setupSupplementMode()
        }

        // Restore imagePaths from saved state (rotation), filtering invalid paths
        savedInstanceState?.getStringArrayList("imagePaths")?.let { saved ->
            val validated = CharacterImageStripController.validateInternalPaths(
                saved, context?.filesDir
            )
            imageStrip.setPaths(validated)
            restoredFromSavedState = true
        }
        pendingFieldValues = savedInstanceState?.getBundle("fieldValues")

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
        setupEventButton()
        setupChangeTracking()

        // Show restored images if any (from rotation)
        if (imageStrip.paths.isNotEmpty()) {
            imageStrip.refresh()
        }

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
            // 회전 재생성(savedInstanceState 보유)이 아니면 영구 드래프트 복원 제안 (B-6)
            if (savedInstanceState == null) {
                maybeOfferDraftRestore()
            }
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
                        if (universeId != null) {
                            formBuilder.fieldDefinitions = viewModel.getFieldsByUniverseList(universeId)
                        } else {
                            formBuilder.fieldDefinitions = emptyList()
                        }
                    } else {
                        formBuilder.fieldDefinitions = emptyList()
                    }
                    if (_binding == null) return@launch
                    formBuilder.buildForm()

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

        imageStrip.attach()
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
            imagePaths = imageStrip.paths.toList()
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
            override fun insertEvent(event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, eventFieldValues: List<com.novelcharacter.app.data.model.EventFieldValue>) {
                viewModel.insertEvent(event, characterIds, novelIds, eventFieldValues)
            }
            override fun updateEvent(event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, eventFieldValues: List<com.novelcharacter.app.data.model.EventFieldValue>) {
                viewModel.updateEvent(event, characterIds, novelIds, eventFieldValues)
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
                eventFieldValues: List<com.novelcharacter.app.data.model.EventFieldValue>
            ) {
                viewModel.updateEventAndShiftOthers(event, characterIds, novelIds, shiftDirection, delta, originalNovelIds, originalUniverseId, eventFieldValues)
            }
        }

    private fun handleBackPress() {
        if (hasUnsavedChanges) {
            val ctx = context ?: return
            androidx.appcompat.app.AlertDialog.Builder(ctx)
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
            findNavController().popBackStack(R.id.characterHomeFragment, false)
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
            .setPopUpTo(R.id.characterHomeFragment, inclusive = false)
            .build()
        findNavController().navigate(R.id.characterEditFragment, bundle, navOptions)
    }

    override fun onDestroyView() {
        imageStrip.detach()
        binding.recommendationRecyclerView.adapter = null
        recMatchJob?.cancel()
        super.onDestroyView()
        recommendedAdapter = null
        _binding = null
    }
}
