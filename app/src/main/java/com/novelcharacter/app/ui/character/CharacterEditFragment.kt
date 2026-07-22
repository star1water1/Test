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
    private val imagePaths = mutableListOf<String>()
    private var restoredFromSavedState = false

    // 동적 필드 관리 — 폼 구성/값 적재/수집/검증은 공용 빌더에 위임
    private lateinit var formBuilder: DynamicFieldFormBuilder
    private var hasUnsavedChanges = false
    private var pendingFieldValues: Bundle? = null
    // 저장 완료/명시적 폐기 후 onPause의 드래프트 재기록 차단 (B-6)
    private var suppressDraftSave = false

    // 보충 모드
    private var supplementMode = false
    private var supplementIndex = 0
    private var supplementIds = longArrayOf()
    private var supplementIssueLabels: String? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) saveImagesToInternalStorage(uris)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("imagePaths", ArrayList(imagePaths))

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
            imagePaths = imagePaths.toList(),
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
        val dir = appDir
        if (draft.imagePaths.isNotEmpty() && dir != null) {
            val validated = draft.imagePaths.filter { path ->
                try {
                    File(path).canonicalPath.startsWith(dir.canonicalPath + File.separator)
                } catch (_: Exception) {
                    false
                }
            }
            imagePaths.clear()
            imagePaths.addAll(validated)
            updateImageList()
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
        appDir = requireContext().filesDir

        formBuilder = DynamicFieldFormBuilder(
            containerGetter = { binding.dynamicFormContainer },
            contextGetter = { context },
            scopeGetter = { viewLifecycleOwner.lifecycleScope },
            isAlive = { _binding != null && isAdded },
            fragmentManagerGetter = { if (isAdded) childFragmentManager else null },
            viewModel = viewModel,
            onFieldChanged = { hasUnsavedChanges = true; updateSaveButtonState() }
        )

        if (supplementMode) {
            setupSupplementMode()
        }

        // Restore imagePaths from saved state (rotation), filtering invalid paths
        savedInstanceState?.getStringArrayList("imagePaths")?.let { saved ->
            val dir = appDir
            val validated = if (dir != null) {
                saved.filter { path ->
                    try {
                        java.io.File(path).canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator)
                    } catch (_: Exception) { false }
                }
            } else {
                emptyList()
            }
            imagePaths.clear()
            imagePaths.addAll(validated)
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
        registerDuplicateResultListener() // 회전 안전(P1-A): 결과 리스너를 onViewCreated에서 1회 등록
        setupEventButton()
        setupChangeTracking()

        // Show restored images if any (from rotation)
        if (imagePaths.isNotEmpty()) {
            updateImageList()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            loadNovels()
            if (characterId != -1L) {
                loadExistingCharacter()
            }
            // 회전 재생성(savedInstanceState 보유)이 아니면 영구 드래프트 복원 제안 (B-6)
            if (savedInstanceState == null) {
                maybeOfferDraftRestore()
            }
        }
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
                            formBuilder.loadFieldValues(existing.id)
                        }
                    }
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
            imagePaths.clear()
            imagePaths.addAll(paths)
        }
        updateImageList()

        // 메모
        binding.editMemo.setText(character.memo)


    private fun setupImageButton() {
        binding.btnAddImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.imageRecyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
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
        for (p in imagePaths) {
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
        val currentCanon = imagePaths.mapTo(HashSet()) { canonicalOrSelf(it) }
        val toAdd = expansion.allPaths.filter { canonicalOrSelf(it) !in currentCanon }
        if (toAdd.isEmpty()) return
        imagePaths.addAll(toAdd)
        updateImageList()
        // 첨부는 미저장 변경 — 없으면 뒤로가기가 확인 없이 이탈하고 드래프트도 안 남는다(무음 유실)
        hasUnsavedChanges = true
        updateSaveButtonState()
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

    /**
     * 픽한 이미지들을 내부 저장소에 저장한다. 공용 [ImageImportHelper]로 라우팅하여
     * 압축 설정(용량↔화질)을 적용한다. 압축 설정은 배치당 1회만 로드한다.
     */
    private fun saveImagesToInternalStorage(uris: List<Uri>) {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = com.novelcharacter.app.util.ImageSettingsStore(ctx).getSettings()
            var anyFailed = false
            for (uri in uris) {
                val filePath = try {
                    com.novelcharacter.app.util.ImageImportHelper.importImage(ctx, uri, "char", settings)
                } catch (e: Exception) {
                    null
                }
                if (_binding == null) return@launch
                if (filePath != null) {
                    imagePaths.add(filePath)
                    updateImageList()
                    hasUnsavedChanges = true
                    updateSaveButtonState()
                } else {
                    anyFailed = true
                }
            }
            if (anyFailed && isAdded) {
                val c = context ?: return@launch
                Toast.makeText(c, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var imageAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    private fun updateImageList() {
        if (imageAdapter == null) {
            imageAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val d = parent.context.resources.displayMetrics.density
                    val sizePx = (80 * d).toInt()
                    val imageView = ImageView(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(sizePx, sizePx).apply {
                            marginEnd = (4 * d).toInt()
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    return object : RecyclerView.ViewHolder(imageView) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val imageView = holder.itemView as ImageView
                    // 이전 로드 작업 취소 + 이미지 초기화
                    (imageView.getTag(R.id.image_load_job) as? kotlinx.coroutines.Job)?.cancel()
                    imageView.setTag(R.id.image_load_job, null)
                    imageView.setImageResource(R.drawable.ic_character_placeholder)
                    if (position < imagePaths.size) {
                        val path = imagePaths[position]
                        val boundPosition = position
                        val job = viewLifecycleOwner.lifecycleScope.launch {
                            val targetSize = (80 * holder.itemView.context.resources.displayMetrics.density).toInt()
                            val bitmap = withContext(Dispatchers.IO) {
                                decodeSampledBitmap(path, targetSize, targetSize)
                            }
                            if (bitmap != null && holder.bindingAdapterPosition == boundPosition && isAdded) {
                                imageView.setImageBitmap(bitmap)
                            }
                        }
                        imageView.setTag(R.id.image_load_job, job)
                    }
                    // 탭 → 이미지 뷰어에서 확대
                    imageView.setOnClickListener {
                        if (!isAdded) return@setOnClickListener
                        val adapterPosition = holder.bindingAdapterPosition
                        if (adapterPosition >= 0 && adapterPosition < imagePaths.size) {
                            val bundle = Bundle().apply {
                                putString("imagePaths", gson.toJson(imagePaths))
                                putInt("startPosition", adapterPosition)
                            }
                            findNavController().navigateSafe(R.id.characterEditFragment, R.id.imageViewerFragment, bundle)
                        }
                    }
                    // 롱프레스 → 삭제
                    imageView.setOnLongClickListener {
                        val adapterPosition = holder.bindingAdapterPosition
                        if (adapterPosition >= 0 && adapterPosition < imagePaths.size) {
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.delete)
                                .setMessage(R.string.image_delete_confirm)
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    val currentPos = holder.bindingAdapterPosition
                                    if (currentPos >= 0 && currentPos < imagePaths.size) {
                                        imagePaths.removeAt(currentPos)
                                        imageAdapter?.notifyItemRemoved(currentPos)
                                        imageAdapter?.notifyItemRangeChanged(currentPos, imagePaths.size - currentPos)
                                        hasUnsavedChanges = true
                                        updateSaveButtonState()
                                        refreshRecommendations()
                                    }
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                        true
                    }
                }

                override fun getItemCount() = imagePaths.size
            }
            binding.imageRecyclerView.adapter = imageAdapter
        } else {
            imageAdapter?.notifyDataSetChanged()
        }
    }

    private var appDir: java.io.File? = null

    /**
     * 이전 이미지 목록과 현재 목록을 비교하여 제거된 파일을 정리한다.
     * 정책(이미지 설정): LIBRARY_ONLY(기본) = 공유·라이브러리·휴지통 보호 파일은 남기고 나머지만 삭제,
     * ALWAYS_ADOPT = 삭제 대신 라이브러리(미배정)로 입양(삭제는 이미지 탭에서만).
     */
    private suspend fun cleanupRemovedImages(oldPathsJson: String?, currentPaths: List<String>) {
        val oldPaths: List<String> = try {
            gson.fromJson(oldPathsJson ?: "[]", com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val currentSet = currentPaths.toSet()
        val removed = oldPaths.filter { it !in currentSet }
        if (removed.isEmpty()) return
        val appCtx = context?.applicationContext ?: return
        val db = (activity?.application as? com.novelcharacter.app.NovelCharacterApp)?.database ?: return
        try {
            when (com.novelcharacter.app.util.ImageSettingsStore(appCtx).getEditorRemovePolicy()) {
                com.novelcharacter.app.util.ImageSettingsStore.EditorRemovePolicy.LIBRARY_ONLY ->
                    com.novelcharacter.app.util.ImageOwnershipGuard.deleteIfUnprotected(db, appCtx, removed)
                com.novelcharacter.app.util.ImageSettingsStore.EditorRemovePolicy.ALWAYS_ADOPT ->
                    com.novelcharacter.app.util.ImageOwnershipGuard.adoptOrphans(db, appCtx, removed)
            }
        } catch (_: Exception) { /* 보존이 삭제보다 안전 — 실패 시 파일 유지 */ }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
        // 공용 유틸 위임 — filesDir 경로 가드 + 총 픽셀 상한(파노라마 OOM 방지, P2-6). 정상 이미지 화질 보존.
        val dir = appDir ?: return null
        return com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, dir, reqWidth)
    }

    private var isSaving = false

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            if (isSaving) return@setOnClickListener
            val name = binding.editName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), R.string.enter_name, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (name.length > 100) {
                Toast.makeText(requireContext(), R.string.name_too_long, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val missingRequired = formBuilder.validateRequiredFields()
            if (missingRequired != null) {
                Toast.makeText(requireContext(), getString(R.string.required_field_empty, missingRequired), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val character = buildCharacterFromForm()

            isSaving = true
            binding.btnSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // 중복 이름 체크
                    val duplicates = viewModel.getAllCharactersByName(name)
                        .filter { it.id != characterId } // 자기 자신 제외 (수정 시)

                    if (duplicates.isNotEmpty()) {
                        // 중복 후보 목록 구성 (작품명 포함)
                        val candidates = duplicates.map { dup ->
                            val novelTitle = dup.novelId?.let { viewModel.getNovelById(it)?.title }
                            DuplicateCandidate(dup, novelTitle)
                        }

                        val isEdit = characterId != -1L
                        if (!isAdded) { resetSavingState(); return@launch }
                        showDuplicateDialog(candidates, isEdit)
                    } else {
                        performSave(character, isUpdate = characterId != -1L, targetCharacterId = characterId)
                    }
                } catch (e: Exception) {
                    if (isAdded && _binding != null) {
                        Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                    }
                    resetSavingState()
                }
            }
        }
    }

    private fun showDuplicateDialog(
        candidates: List<DuplicateCandidate>,
        isEditMode: Boolean
    ) {
        // 결과 리스너는 onViewCreated에서 1회 등록(회전 안전, P1-A). 여기선 다이얼로그만 띄운다.
        val dialog = DuplicateCharacterDialog.newInstance(candidates, isEditMode)
        dialog.show(childFragmentManager, "duplicate_character")
    }

    /** 폼 입력으로 Character를 조립한다. 중복 다이얼로그 결과가 회전 후 도착해도 최신 폼에서 재구성하기 위해 공용화. */
    private fun buildCharacterFromForm(): Character {
        val novelPosition = binding.spinnerNovel.selectedItemPosition
        val selectedNovelId = if (novelPosition > 0 && novelPosition - 1 < novels.size) novels[novelPosition - 1].id else null
        return Character(
            id = if (characterId != -1L) characterId else 0,
            name = binding.editName.text.toString().trim(),
            firstName = binding.editFirstName.text.toString().trim(),
            lastName = binding.editLastName.text.toString().trim(),
            anotherName = binding.editAnotherName.text.toString().trim(),
            novelId = selectedNovelId,
            imagePaths = gson.toJson(imagePaths),
            createdAt = existingCharacter?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            memo = binding.editMemo.text.toString(),
            code = existingCharacter?.code ?: generateEntityCode(),
            displayOrder = existingCharacter?.displayOrder ?: 0,
            isPinned = existingCharacter?.isPinned ?: false
        )
    }

    /**
     * 중복 캐릭터 다이얼로그 결과 리스너 — **onViewCreated에서 1회 등록**(회전 안전, P1-A).
     * 예전엔 `showDuplicateDialog` 안에서 지연 등록해, 회전으로 재생성되면 재등록되지 않아 결과가
     * 유실되고 캐릭터가 조용히 저장되지 않았다(변수 제어 위반). 이제 폼에서 character를 재구성해 처리한다.
     */
    private fun registerDuplicateResultListener() {
        childFragmentManager.setFragmentResultListener(
            DuplicateCharacterDialog.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val resolutionName = bundle.getString(DuplicateCharacterDialog.RESULT_RESOLUTION)
                ?: DuplicateCharacterDialog.Resolution.CANCEL.name
            val resolution = DuplicateCharacterDialog.Resolution.valueOf(resolutionName)
            val selectedCharId = bundle.getLong(DuplicateCharacterDialog.RESULT_SELECTED_CHARACTER_ID, -1L)
            val character = buildCharacterFromForm()

            when (resolution) {
                DuplicateCharacterDialog.Resolution.CANCEL -> {
                    resetSavingState()
                }
                DuplicateCharacterDialog.Resolution.CREATE_NEW -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            performSave(character, isUpdate = false, targetCharacterId = -1L)
                        } catch (e: Exception) {
                            if (isAdded && _binding != null) {
                                Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                            }
                            resetSavingState()
                        }
                    }
                }
                DuplicateCharacterDialog.Resolution.UPDATE_EXISTING -> {
                    if (selectedCharId == -1L) { resetSavingState(); return@setFragmentResultListener }
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val target = viewModel.getCharacterByIdSuspend(selectedCharId)
                            if (target == null) { resetSavingState(); return@launch }
                            val updatedChar = character.copy(
                                id = target.id,
                                code = target.code,
                                createdAt = target.createdAt,
                                displayOrder = target.displayOrder,
                                isPinned = target.isPinned
                            )
                            performSave(updatedChar, isUpdate = true, targetCharacterId = target.id)
                        } catch (e: Exception) {
                            if (isAdded && _binding != null) {
                                Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                            }
                            resetSavingState()
                        }
                    }
                }
                DuplicateCharacterDialog.Resolution.SAVE_ANYWAY -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            performSave(character, isUpdate = true, targetCharacterId = characterId)
                        } catch (e: Exception) {
                            if (isAdded && _binding != null) {
                                Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                            }
                            resetSavingState()
                        }
                    }
                }
            }
        }
    }

    private suspend fun performSave(character: Character, isUpdate: Boolean, targetCharacterId: Long) {
        // 저장 전 나이-출생연도 불일치 감지 (삽입 전에 체크하여 고아 레코드 방지)
        val tempCharId = if (isUpdate) targetCharacterId else -1L
        val tempFieldValues = formBuilder.collectFieldValues(tempCharId)
        val conflict = viewModel.detectAgeLinkageConflict(
            novelId = character.novelId,
            characterId = tempCharId,
            fieldValues = tempFieldValues
        )
        if (conflict != null && isAdded && view != null) {
            showAgeLinkageConflictDialog(conflict, character, isUpdate, targetCharacterId, tempFieldValues)
            return
        }

        executeSave(character, isUpdate, targetCharacterId, tempFieldValues)
    }

    /**
     * 실제 저장 실행 (불일치 감지 후 또는 해결 후 호출)
     * @param resolvedFieldValues 불일치 해결 후 수정된 필드값 목록 (null이면 다시 수집)
     */
    private suspend fun executeSave(
        character: Character,
        isUpdate: Boolean,
        targetCharacterId: Long,
        resolvedFieldValues: List<CharacterFieldValue>? = null,
        crossUniverseConfirmed: Boolean = false
    ) {
        val savedCharId: Long
        if (isUpdate && targetCharacterId != -1L) {
            val fieldValues = resolvedFieldValues?.map { it.copy(characterId = targetCharacterId) }
                ?: formBuilder.collectFieldValues(targetCharacterId)
            // 다른 세계관으로 이동하는 저장이면 유실 고지·이관 처리(변수 제어: 조용한 필드값 유실 방지)
            val crossUniv = crossUniverseTargetId(character)
            if (crossUniv != null && !crossUniverseConfirmed) {
                val loss = viewModel.countCrossUniverseLoss(targetCharacterId, crossUniv)
                if (loss.hasRemoval) {
                    showCrossUniverseMoveDialog(loss,
                        onConfirm = {
                            viewLifecycleOwner.lifecycleScope.launch {
                                executeSave(character, isUpdate, targetCharacterId, fieldValues, crossUniverseConfirmed = true)
                            }
                        },
                        onCancel = { resetSavingState() })
                    return
                }
            }
            applyCharacterUpdate(character, fieldValues, crossUniv)
            savedCharId = targetCharacterId
            cleanupRemovedImages(existingCharacter?.imagePaths, imagePaths)
        } else {
            val newId = viewModel.insertCharacterSuspend(character)
            val fieldValues = resolvedFieldValues?.map { it.copy(characterId = newId) }
                ?: formBuilder.collectFieldValues(newId)
            viewModel.saveAllFieldValues(newId, fieldValues)
            savedCharId = newId
        }

        val tagText = binding.editTags.text.toString()
        val tagList = tagText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        viewModel.replaceAllTagsSuspend(savedCharId, tagList.map { CharacterTag(characterId = savedCharId, tag = it) })

        resetSavingState()
        clearDraft()
        if (isAdded && view != null) {
            Toast.makeText(requireContext(), R.string.saved_successfully, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    private fun showAgeLinkageConflictDialog(
        conflict: CharacterViewModel.AgeLinkageConflict,
        character: Character,
        isUpdate: Boolean,
        targetCharacterId: Long,
        originalFieldValues: List<CharacterFieldValue>
    ) {
        val options = arrayOf(
            getString(R.string.age_linkage_option_adjust_birth,
                conflict.suggestedBirthYear, conflict.inputAge),
            getString(R.string.age_linkage_option_adjust_age,
                conflict.expectedAge, conflict.inputBirthYear),
            if (conflict.affectedCharacterCount > 0)
                getString(R.string.age_linkage_option_adjust_std_year_with_warn,
                    conflict.suggestedStdYear, conflict.inputAge, conflict.inputBirthYear,
                    conflict.affectedCharacterCount)
            else
                getString(R.string.age_linkage_option_adjust_std_year,
                    conflict.suggestedStdYear, conflict.inputAge, conflict.inputBirthYear)
        )

        var selected = 0
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.age_linkage_conflict_title)
            .setMessage(getString(R.string.age_linkage_conflict_message,
                conflict.currentStdYear, conflict.inputAge, conflict.inputBirthYear, conflict.expectedAge))
            .setSingleChoiceItems(options, 0) { _, which -> selected = which }
            .setPositiveButton(R.string.apply) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val fieldValues = originalFieldValues.toMutableList()

                        when (selected) {
                            0 -> {
                                // 출생연도 변경 (나이 유지)
                                val idx = fieldValues.indexOfFirst { it.fieldDefinitionId == conflict.birthYearFieldId }
                                val newVal = CharacterFieldValue(
                                    characterId = 0, // executeSave에서 재설정됨
                                    fieldDefinitionId = conflict.birthYearFieldId,
                                    value = conflict.suggestedBirthYear.toString()
                                )
                                if (idx >= 0) fieldValues[idx] = newVal else fieldValues.add(newVal)
                            }
                            1 -> {
                                // 나이 변경 (출생연도 유지)
                                val idx = fieldValues.indexOfFirst { it.fieldDefinitionId == conflict.ageFieldId }
                                val newVal = CharacterFieldValue(
                                    characterId = 0,
                                    fieldDefinitionId = conflict.ageFieldId,
                                    value = conflict.expectedAge.toString()
                                )
                                if (idx >= 0) fieldValues[idx] = newVal else fieldValues.add(newVal)
                            }
                            2 -> {
                                // 기준연도 변경 (둘 다 유지) + 다른 캐릭터 일괄 재계산
                                viewModel.applyStandardYearChange(
                                    conflict.novelId,
                                    conflict.currentStdYear,
                                    conflict.suggestedStdYear
                                )
                            }
                        }

                        executeSave(character, isUpdate, targetCharacterId, fieldValues)
                    } catch (e: Exception) {
                        if (isAdded && _binding != null) {
                            Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                        }
                        resetSavingState()
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                resetSavingState()
            }
            .setOnCancelListener {
                resetSavingState()
            }
            .show()
    }

    private fun resetSavingState() {
        isSaving = false
        if (_binding != null) {
            binding.btnSave.isEnabled = true
        }
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

        // 저장 & 다음
        binding.btnSaveAndNext.setOnClickListener {
            performSupplementSave()
        }

        // 건너뛰기
        binding.btnSkip.setOnClickListener {
            navigateToNextSupplement()
        }

        // 툴바 제목 변경
        binding.toolbar.title = getString(R.string.supplement_title)
    }

    private fun performSupplementSave() {
        if (isSaving) return
        val name = binding.editName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.enter_name, Toast.LENGTH_SHORT).show()
            return
        }
        if (name.length > 100) {
            Toast.makeText(requireContext(), R.string.name_too_long, Toast.LENGTH_SHORT).show()
            return
        }

        val missingRequired = formBuilder.validateRequiredFields()
        if (missingRequired != null) {
            Toast.makeText(requireContext(), getString(R.string.required_field_empty, missingRequired), Toast.LENGTH_SHORT).show()
            return
        }

        val novelPosition = binding.spinnerNovel.selectedItemPosition
        val selectedNovelId = if (novelPosition > 0 && novelPosition - 1 < novels.size) novels[novelPosition - 1].id else null

        val memo = binding.editMemo.text.toString()
        val firstName = binding.editFirstName.text.toString().trim()
        val lastName = binding.editLastName.text.toString().trim()
        val anotherName = binding.editAnotherName.text.toString().trim()

        val character = Character(
            id = if (characterId != -1L) characterId else 0,
            name = name,
            firstName = firstName,
            lastName = lastName,
            anotherName = anotherName,
            novelId = selectedNovelId,
            imagePaths = gson.toJson(imagePaths),
            createdAt = existingCharacter?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            memo = memo,
            code = existingCharacter?.code ?: generateEntityCode(),
            displayOrder = existingCharacter?.displayOrder ?: 0,
            isPinned = existingCharacter?.isPinned ?: false
        )

        isSaving = true
        binding.btnSaveAndNext.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val isUpdate = characterId != -1L
                val tempCharId = if (isUpdate) characterId else -1L
                val tempFieldValues = formBuilder.collectFieldValues(tempCharId)

                // 나이-출생연도 불일치 감지
                val conflict = viewModel.detectAgeLinkageConflict(
                    novelId = character.novelId,
                    characterId = tempCharId,
                    fieldValues = tempFieldValues
                )
                if (conflict != null && isAdded && view != null) {
                    showSupplementAgeLinkageConflictDialog(conflict, character, isUpdate, tempFieldValues)
                    return@launch
                }

                executeSupplementSave(character, isUpdate, tempFieldValues)
            } catch (e: Exception) {
                if (isAdded && _binding != null) {
                    Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
                resetSupplementSavingState()
            }
        }
    }

    private suspend fun executeSupplementSave(
        character: Character,
        isUpdate: Boolean,
        resolvedFieldValues: List<CharacterFieldValue>,
        crossUniverseConfirmed: Boolean = false
    ) {
        val savedCharId: Long
        if (isUpdate && characterId != -1L) {
            val fieldValues = resolvedFieldValues.map { it.copy(characterId = characterId) }
            val crossUniv = crossUniverseTargetId(character)
            if (crossUniv != null && !crossUniverseConfirmed) {
                val loss = viewModel.countCrossUniverseLoss(characterId, crossUniv)
                if (loss.hasRemoval) {
                    showCrossUniverseMoveDialog(loss,
                        onConfirm = {
                            viewLifecycleOwner.lifecycleScope.launch {
                                executeSupplementSave(character, isUpdate, resolvedFieldValues, crossUniverseConfirmed = true)
                            }
                        },
                        onCancel = { resetSupplementSavingState() })
                    return
                }
            }
            applyCharacterUpdate(character, fieldValues, crossUniv)
            savedCharId = characterId
            cleanupRemovedImages(existingCharacter?.imagePaths, imagePaths)
        } else {
            val newId = viewModel.insertCharacterSuspend(character)
            val fieldValues = resolvedFieldValues.map { it.copy(characterId = newId) }
            viewModel.saveAllFieldValues(newId, fieldValues)
            savedCharId = newId
        }

        val tagText = binding.editTags.text.toString()
        val tagList = tagText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        viewModel.replaceAllTagsSuspend(savedCharId, tagList.map { CharacterTag(characterId = savedCharId, tag = it) })

        resetSupplementSavingState()
        clearDraft()
        if (isAdded && view != null) {
            Toast.makeText(requireContext(), R.string.saved_successfully, Toast.LENGTH_SHORT).show()
            navigateToNextSupplement()
        }
    }

    /** 다른 세계관으로 이동하는 저장이면 새 세계관 id, 아니면 null(기존·새 세계관 모두 있고 서로 다를 때). */
    private suspend fun crossUniverseTargetId(character: Character): Long? {
        val old = viewModel.universeIdForNovel(existingCharacter?.novelId)
        val new = viewModel.universeIdForNovel(character.novelId)
        return if (old != null && new != null && old != new) new else null
    }

    /** 세계관 이동 여부에 따라 이관 저장(같은 이름 필드 유지·유실 시 스냅샷) 또는 일반 저장을 선택한다. */
    private suspend fun applyCharacterUpdate(
        character: Character,
        values: List<CharacterFieldValue>,
        crossUniverseId: Long?
    ) {
        if (crossUniverseId != null) viewModel.updateCharacterAcrossUniverse(character, values, crossUniverseId)
        else viewModel.updateCharacterWithFields(character, values)
    }

    /** 세계관 이동 시 유실(제거) 고지 다이얼로그 — 같은 이름 필드 이관·제거분 휴지통 백업(복원 가능) 안내. */
    private fun showCrossUniverseMoveDialog(
        loss: com.novelcharacter.app.data.repository.UniverseMoveCounts,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (!isAdded || view == null) { onCancel(); return }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.cross_universe_move_title)
            .setMessage(getString(
                R.string.cross_universe_move_message,
                loss.removedValues, loss.removedMemberships, loss.remappedValues
            ))
            .setPositiveButton(R.string.cross_universe_move_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun resetSupplementSavingState() {
        isSaving = false
        if (_binding != null) {
            binding.btnSaveAndNext.isEnabled = true
        }
    }

    private fun showSupplementAgeLinkageConflictDialog(
        conflict: CharacterViewModel.AgeLinkageConflict,
        character: Character,
        isUpdate: Boolean,
        originalFieldValues: List<CharacterFieldValue>
    ) {
        val options = arrayOf(
            getString(R.string.age_linkage_option_adjust_birth,
                conflict.suggestedBirthYear, conflict.inputAge),
            getString(R.string.age_linkage_option_adjust_age,
                conflict.expectedAge, conflict.inputBirthYear),
            if (conflict.affectedCharacterCount > 0)
                getString(R.string.age_linkage_option_adjust_std_year_with_warn,
                    conflict.suggestedStdYear, conflict.inputAge, conflict.inputBirthYear,
                    conflict.affectedCharacterCount)
            else
                getString(R.string.age_linkage_option_adjust_std_year,
                    conflict.suggestedStdYear, conflict.inputAge, conflict.inputBirthYear)
        )

        var selected = 0
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.age_linkage_conflict_title)
            .setMessage(getString(R.string.age_linkage_conflict_message,
                conflict.currentStdYear, conflict.inputAge, conflict.inputBirthYear, conflict.expectedAge))
            .setSingleChoiceItems(options, 0) { _, which -> selected = which }
            .setPositiveButton(R.string.apply) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val fieldValues = originalFieldValues.toMutableList()

                        when (selected) {
                            0 -> {
                                val idx = fieldValues.indexOfFirst { it.fieldDefinitionId == conflict.birthYearFieldId }
                                val newVal = CharacterFieldValue(
                                    characterId = 0,
                                    fieldDefinitionId = conflict.birthYearFieldId,
                                    value = conflict.suggestedBirthYear.toString()
                                )
                                if (idx >= 0) fieldValues[idx] = newVal else fieldValues.add(newVal)
                            }
                            1 -> {
                                val idx = fieldValues.indexOfFirst { it.fieldDefinitionId == conflict.ageFieldId }
                                val newVal = CharacterFieldValue(
                                    characterId = 0,
                                    fieldDefinitionId = conflict.ageFieldId,
                                    value = conflict.expectedAge.toString()
                                )
                                if (idx >= 0) fieldValues[idx] = newVal else fieldValues.add(newVal)
                            }
                            2 -> {
                                viewModel.applyStandardYearChange(
                                    conflict.novelId,
                                    conflict.currentStdYear,
                                    conflict.suggestedStdYear
                                )
                            }
                        }

                        executeSupplementSave(character, isUpdate, fieldValues)
                    } catch (e: Exception) {
                        if (isAdded && _binding != null) {
                            Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                        }
                        resetSupplementSavingState()
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                resetSupplementSavingState()
            }
            .setOnCancelListener {
                resetSupplementSavingState()
            }
            .show()
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
        binding.imageRecyclerView.adapter = null
        binding.recommendationRecyclerView.adapter = null
        recMatchJob?.cancel()
        super.onDestroyView()
        imageAdapter = null
        recommendedAdapter = null
        _binding = null
    }
}
