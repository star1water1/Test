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

    // 동적 필드 관리
    private var fieldDefinitions: List<FieldDefinition> = emptyList()
    private val fieldInputMap = mutableMapOf<Long, Any>() // fieldDefinitionId -> input widget
    private var currentUniverseId: Long? = null
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
        uris.forEach { uri -> saveImageToInternalStorage(uri) }
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
        for (field in fieldDefinitions) {
            val widget = fieldInputMap[field.id] ?: continue
            val fieldType = FieldType.fromName(field.type)
            if (fieldType == FieldType.CALCULATED) continue
            fieldValues.putString(field.id.toString(), widgetStateString(widget))
        }
        outState.putBundle("fieldValues", fieldValues)
    }

    /** 입력 위젯의 현재 값 직렬화 — 회전 Bundle과 영구 드래프트가 같은 포맷을 공유한다 */
    private fun widgetStateString(widget: Any): String = when (widget) {
        is MaterialAutoCompleteTextView -> widget.text.toString()
        is TextInputEditText -> widget.text.toString()
        is Spinner -> widget.selectedItemPosition.toString()
        is LinearLayout -> {
            val parts = mutableListOf<String>()
            for (i in 0 until widget.childCount) {
                val partLayout = widget.getChildAt(i) as? TextInputLayout
                parts.add(partLayout?.editText?.text?.toString() ?: "")
            }
            parts.joinToString("\u001F")
        }
        else -> ""
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
        val fieldValues = mutableMapOf<String, String>()
        for (field in fieldDefinitions) {
            val widget = fieldInputMap[field.id] ?: continue
            if (FieldType.fromName(field.type) == FieldType.CALCULATED) continue
            fieldValues[field.id.toString()] = widgetStateString(widget)
        }
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
                if (binding.spinnerNovel.selectedItemPosition == targetPos && fieldDefinitions.isNotEmpty()) {
                    // 해당 작품 기준으로 폼이 이미 빌드됨 — 즉시 적용
                    restoreFieldValues(bundle)
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
        setupSaveButton()
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
                        currentUniverseId = universeId
                        if (universeId != null) {
                            fieldDefinitions = viewModel.getFieldsByUniverseList(universeId)
                        } else {
                            fieldDefinitions = emptyList()
                        }
                    } else {
                        fieldDefinitions = emptyList()
                    }
                    if (_binding == null) return@launch
                    buildDynamicForm()

                    // 회전 복원된 필드값이 있으면 우선 적용, 없으면 DB에서 로드.
                    // 스피너 초기(position 0) 콜백은 폼이 비어 있으므로 복원값을 소비하지 않고 보존한다
                    // (여기서 소거하면 이후 실제 작품 선택 콜백이 DB 값으로 덮어써 회전 직전 입력이 유실됨)
                    val saved = pendingFieldValues
                    if (saved != null && fieldDefinitions.isNotEmpty()) {
                        restoreFieldValues(saved)
                        pendingFieldValues = null
                    } else if (saved == null) {
                        val existing = existingCharacter
                        if (existing != null) {
                            loadFieldValues(existing.id)
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
    }

    // ===== 🎲 랜덤 값 생성 =====

    private fun createDiceButton(context: android.content.Context, density: Float, action: () -> Unit): com.google.android.material.button.MaterialButton {
        return com.google.android.material.button.MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "🎲"; textSize = 14f; minWidth = 0; minimumWidth = 0
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { action() }
        }
    }

    private fun showBirthdaySeasonDialog(field: com.novelcharacter.app.data.model.FieldDefinition) {
        val seasons = com.novelcharacter.app.util.FieldRandomGenerator.Season.entries
        val labels = seasons.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.random_birthday_season)
            .setItems(labels) { _, which ->
                val date = com.novelcharacter.app.util.FieldRandomGenerator.generateBirthday(seasons[which])
                applyRandomValue(field, date)
            }
            .show()
    }

    private fun applyRandomForField(field: com.novelcharacter.app.data.model.FieldDefinition, type: FieldType) {
        val config = com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config)
        val value: String? = when (type) {
            FieldType.NUMBER -> com.novelcharacter.app.util.FieldRandomGenerator.generateNumber(
                config.min ?: 0.0, config.max ?: 100.0, config.decimalPlaces
            )
            FieldType.SELECT -> {
                val options = try {
                    val json = org.json.JSONObject(field.config)
                    val arr = json.optJSONArray("options")
                    if (arr != null) (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
                    else emptyList()
                } catch (_: Exception) { emptyList() }
                com.novelcharacter.app.util.FieldRandomGenerator.generateSelect(options)
            }
            FieldType.GRADE -> {
                // parseGradeOptions()를 사용하여 allowNegative 포함 전체 등급 목록 획득
                val grades = parseGradeOptions(field.config)
                com.novelcharacter.app.util.FieldRandomGenerator.generateGrade(grades)
            }
            else -> return
        }
        if (value == null) {
            android.widget.Toast.makeText(requireContext(), R.string.random_no_options, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        applyRandomValue(field, value)
    }

    private fun applyRandomValue(field: com.novelcharacter.app.data.model.FieldDefinition, value: String) {
        val widget = fieldInputMap[field.id]
        when (widget) {
            is android.widget.EditText -> widget.setText(value)
            is android.widget.LinearLayout -> {
                // 구조화 입력: "MM-DD" 등을 parts로 분리
                val parts = value.split("-")
                for (i in 0 until minOf(widget.childCount, parts.size)) {
                    val child = widget.getChildAt(i)
                    if (child is com.google.android.material.textfield.TextInputLayout) {
                        child.editText?.setText(parts.getOrNull(i) ?: "")
                    }
                }
            }
            is android.widget.Spinner -> {
                val idx = (0 until widget.count).firstOrNull {
                    widget.getItemAtPosition(it).toString() == value
                } ?: return
                widget.setSelection(idx)
            }
        }
        hasUnsavedChanges = true
        updateSaveButtonState()
        android.widget.Toast.makeText(requireContext(), getString(R.string.random_applied, value), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showBodyGenerator(bodySizeField: com.novelcharacter.app.data.model.FieldDefinition) {
        val sheet = BodyGeneratorBottomSheet()
        sheet.analysisConfig = com.novelcharacter.app.data.model.BodyAnalysisConfig.fromConfig(bodySizeField.config)
        // TODO: 같은 작품의 캐릭터 목록을 제공하여 상대 생성/분포 활성화
        sheet.onApply = { body ->
            // BWH 필드에 값 채우기
            val bwhView = fieldInputMap[bodySizeField.id]
            if (bwhView is android.widget.LinearLayout) {
                val parts = body.bwhString.split("-")
                for (i in 0 until minOf(bwhView.childCount, parts.size)) {
                    val child = bwhView.getChildAt(i)
                    if (child is com.google.android.material.textfield.TextInputLayout) {
                        child.editText?.setText(parts[i])
                    }
                }
            } else if (bwhView is android.widget.EditText) {
                bwhView.setText(body.bwhString)
            }
            // 키 필드 채우기
            for ((id, view) in fieldInputMap) {
                val fd = fieldDefinitions.find { it.id == id } ?: continue
                val role = com.novelcharacter.app.data.model.SemanticRole.fromConfig(fd.config)
                if (role == com.novelcharacter.app.data.model.SemanticRole.HEIGHT) {
                    val editText = when (view) {
                        is android.widget.EditText -> view
                        is com.google.android.material.textfield.TextInputLayout -> view.editText
                        else -> null
                    }
                    editText?.setText(body.height.toInt().toString())
                } else if (role == com.novelcharacter.app.data.model.SemanticRole.WEIGHT) {
                    val editText = when (view) {
                        is android.widget.EditText -> view
                        is com.google.android.material.textfield.TextInputLayout -> view.editText
                        else -> null
                    }
                    editText?.setText(body.weight.toInt().toString())
                }
            }
        }
        sheet.show(childFragmentManager, "body_generator")
    }

    private suspend fun loadFieldValues(characterId: Long) {
        val values = viewModel.getValuesByCharacterList(characterId)
        val valueMap = values.associateBy { it.fieldDefinitionId }

        for (field in fieldDefinitions) {
            val savedValue = valueMap[field.id]?.value ?: ""
            val widget = fieldInputMap[field.id] ?: continue

            when (widget) {
                is MaterialAutoCompleteTextView -> widget.setText(savedValue, false)
                is TextInputEditText -> widget.setText(savedValue)
                is LinearLayout -> {
                    // 구조화 입력: 저장된 값을 파트별로 분리하여 채움
                    val config = widget.tag as? StructuredInputConfig
                    if (config != null && savedValue.isNotEmpty()) {
                        val parts = config.splitValue(savedValue)
                        for (i in 0 until minOf(widget.childCount, parts.size)) {
                            val partLayout = widget.getChildAt(i) as? TextInputLayout
                            partLayout?.editText?.setText(parts[i].second)
                        }
                    }
                }
                is Spinner -> {
                    val fieldType = FieldType.fromName(field.type)
                    if (fieldType == FieldType.SELECT) {
                        val options = parseSelectOptions(field.config)
                        val optionWithBlank = mutableListOf(getString(R.string.no_selection))
                        optionWithBlank.addAll(options)
                        val idx = optionWithBlank.indexOf(savedValue)
                        if (idx >= 0) {
                            widget.setSelection(idx)
                        } else if (savedValue.isNotBlank()) {
                            // 고아 값: 현재 옵션에 없지만 저장된 값을 스피너에 추가하여 보존
                            optionWithBlank.add(savedValue)
                            val ctx = context ?: continue
                            widget.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, optionWithBlank).also {
                                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                            }
                            widget.setSelection(optionWithBlank.size - 1)
                        }
                    } else if (fieldType == FieldType.GRADE) {
                        val grades = parseGradeOptions(field.config)
                        val gradeWithBlank = mutableListOf(getString(R.string.no_grade_selected))
                        gradeWithBlank.addAll(grades)
                        val idx = gradeWithBlank.indexOf(savedValue)
                        if (idx >= 0) {
                            widget.setSelection(idx)
                        } else if (savedValue.isNotBlank()) {
                            gradeWithBlank.add(savedValue)
                            val ctx = context ?: continue
                            widget.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, gradeWithBlank).also {
                                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                            }
                            widget.setSelection(gradeWithBlank.size - 1)
                        }
                    }
                }
            }
        }
    }

    private fun restoreFieldValues(saved: Bundle) {
        for (field in fieldDefinitions) {
            val widget = fieldInputMap[field.id] ?: continue
            val fieldType = FieldType.fromName(field.type)
            if (fieldType == FieldType.CALCULATED) continue
            val value = saved.getString(field.id.toString()) ?: continue

            when (widget) {
                is MaterialAutoCompleteTextView -> widget.setText(value, false)
                is TextInputEditText -> widget.setText(value)
                is Spinner -> {
                    val pos = value.toIntOrNull() ?: 0
                    if (pos in 0 until widget.adapter.count) widget.setSelection(pos)
                }
                is LinearLayout -> {
                    val parts = value.split("\u001F")
                    for (i in 0 until minOf(widget.childCount, parts.size)) {
                        val partLayout = widget.getChildAt(i) as? TextInputLayout
                        partLayout?.editText?.setText(parts[i])
                    }
                }
            }
        }
    }

    private fun buildDynamicForm() {
        if (_binding == null || !isAdded) return
        binding.dynamicFormContainer.removeAllViews()
        fieldInputMap.clear()

        val context = context ?: return
        val density = resources.displayMetrics.density

        // 자동완성 대상 수집 (필드마다 개별 쿼리 대신 루프 종료 후 1회 배치 조회)
        val autoCompleteTargets = mutableListOf<Triple<Long, DisplayFormat?, MaterialAutoCompleteTextView>>()

        for (field in fieldDefinitions.sortedBy { it.displayOrder }) {
            val fieldType = FieldType.fromName(field.type)

            when (fieldType) {
                FieldType.TEXT, FieldType.BODY_SIZE -> {
                    var structuredConfig = StructuredInputConfig.fromConfig(field.config)
                    // BODY_SIZE 타입인데 structuredInput 설정이 없으면 기본 B-W-H 구조화 입력 자동 적용
                    if (fieldType == FieldType.BODY_SIZE && !structuredConfig.enabled) {
                        structuredConfig = StructuredInputConfig(
                            enabled = true,
                            separator = "-",
                            parts = listOf(
                                StructuredInputConfig.Part("B", "cm", "number"),
                                StructuredInputConfig.Part("W", "cm", "number"),
                                StructuredInputConfig.Part("H", "cm", "number")
                            )
                        )
                    }
                    // BIRTH_DATE semanticRole인데 structuredInput이 없으면 월/일 구조화 입력 자동 적용
                    if (!structuredConfig.enabled && SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE) {
                        structuredConfig = StructuredInputConfig(
                            enabled = true,
                            separator = "-",
                            parts = listOf(
                                StructuredInputConfig.Part("월", "", "number"),
                                StructuredInputConfig.Part("일", "", "number")
                            )
                        )
                    }
                    if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                        // 구조화 입력: 파트별 개별 입력 필드
                        val labelRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (4 * density).toInt() }
                        }
                        val label = TextView(context).apply {
                            text = field.name
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        labelRow.addView(label)
                        // 🎲 생성 버튼
                        val diceAction: (() -> Unit)? = when {
                            fieldType == FieldType.BODY_SIZE -> {{ showBodyGenerator(field) }}
                            SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE -> {{ showBirthdaySeasonDialog(field) }}
                            com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled -> {{ applyRandomForField(field, fieldType) }}
                            else -> null
                        }
                        if (diceAction != null) {
                            val genBtn = createDiceButton(context, density, diceAction)
                            labelRow.addView(genBtn)
                        }
                        binding.dynamicFormContainer.addView(labelRow)

                        val partsContainer = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (8 * density).toInt()
                            }
                            tag = structuredConfig // 나중에 값 수집 시 사용
                        }
                        for (part in structuredConfig.parts) {
                            val partLayout = TextInputLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f
                                ).apply {
                                    marginEnd = (4 * density).toInt()
                                }
                                hint = if (part.suffix.isNotEmpty()) "${part.label} (${part.suffix})" else part.label
                            }
                            val partEdit = TextInputEditText(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                if (part.inputType == "number") {
                                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                                }
                            }
                            partLayout.addView(partEdit)
                            partsContainer.addView(partLayout)
                        }
                        binding.dynamicFormContainer.addView(partsContainer)
                        fieldInputMap[field.id] = partsContainer
                    } else {
                        // 일반 텍스트 입력
                        val format = DisplayFormat.fromConfig(field.config)
                        val inputLayout = TextInputLayout(context).apply {
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (8 * density).toInt()
                            }
                            hint = field.name
                            if (format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST) {
                                helperText = getString(R.string.hint_comma_list_helper)
                                isHelperTextEnabled = true
                            } else if (format == DisplayFormat.MULTILINE) {
                                helperText = getString(R.string.hint_multiline_helper)
                                isHelperTextEnabled = true
                            }
                        }
                        val editText = MaterialAutoCompleteTextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            if (format == DisplayFormat.MULTILINE) {
                                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                minLines = 2
                            }
                            threshold = 1 // 1글자부터 자동완성 제안
                        }
                        inputLayout.addView(editText)
                        // 비구조화 필드에 🎲 버튼 추가 판정
                        val unstructuredDiceAction: (() -> Unit)? = when {
                            fieldType == FieldType.BODY_SIZE -> {{ showBodyGenerator(field) }}
                            SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE -> {{ showBirthdaySeasonDialog(field) }}
                            com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled -> {{ applyRandomForField(field, fieldType) }}
                            else -> null
                        }
                        if (unstructuredDiceAction != null) {
                            val row = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                layoutParams = ViewGroup.MarginLayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (8 * density).toInt() }
                            }
                            inputLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            row.addView(inputLayout)
                            val genBtn = createDiceButton(context, density, unstructuredDiceAction).apply {
                                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                                    topMargin = (8 * density).toInt()
                                }
                            }
                            row.addView(genBtn)
                            binding.dynamicFormContainer.addView(row)
                        } else {
                            binding.dynamicFormContainer.addView(inputLayout)
                        }
                        fieldInputMap[field.id] = editText

                        // 자동완성 데이터는 루프 종료 후 배치 조회로 채움
                        autoCompleteTargets.add(Triple(field.id, format, editText))
                    }
                }

                FieldType.NUMBER -> {
                    val inputLayout = TextInputLayout(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        hint = field.name
                    }
                    val editText = TextInputEditText(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    inputLayout.addView(editText)
                    if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = (8 * density).toInt() }
                        }
                        inputLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        row.addView(inputLayout)
                        row.addView(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                        binding.dynamicFormContainer.addView(row)
                    } else {
                        binding.dynamicFormContainer.addView(inputLayout)
                    }
                    fieldInputMap[field.id] = editText
                }

                FieldType.SELECT -> {
                    val labelRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (4 * density).toInt() }
                    }
                    val label = TextView(context).apply {
                        text = field.name
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    labelRow.addView(label)
                    if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                        labelRow.addView(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                    }
                    binding.dynamicFormContainer.addView(labelRow)

                    val options = parseSelectOptions(field.config)
                    val optionsWithBlank = mutableListOf(getString(R.string.no_selection))
                    optionsWithBlank.addAll(options)

                    val spinner = Spinner(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        adapter = ArrayAdapter(
                            context,
                            android.R.layout.simple_spinner_item,
                            optionsWithBlank
                        ).also {
                            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                    }
                    binding.dynamicFormContainer.addView(spinner)
                    fieldInputMap[field.id] = spinner
                }

                FieldType.GRADE -> {
                    val gradeLabelRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (4 * density).toInt() }
                    }
                    val label = TextView(context).apply {
                        text = field.name
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    gradeLabelRow.addView(label)
                    if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                        gradeLabelRow.addView(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                    }
                    binding.dynamicFormContainer.addView(gradeLabelRow)

                    val grades = parseGradeOptions(field.config)
                    val gradesWithBlank = mutableListOf(getString(R.string.no_grade_selected))
                    gradesWithBlank.addAll(grades)

                    val spinner = Spinner(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        adapter = ArrayAdapter(
                            context,
                            android.R.layout.simple_spinner_item,
                            gradesWithBlank
                        ).also {
                            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                    }
                    binding.dynamicFormContainer.addView(spinner)
                    fieldInputMap[field.id] = spinner
                }

                FieldType.MULTI_TEXT -> {
                    val inputLayout = TextInputLayout(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        hint = getString(R.string.hint_multi_text_format, field.name)
                    }
                    val editText = MaterialAutoCompleteTextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        threshold = 1
                    }
                    inputLayout.addView(editText)
                    binding.dynamicFormContainer.addView(inputLayout)
                    fieldInputMap[field.id] = editText

                    // 자동완성: 기존 값의 개별 항목을 제안
                    val uId = currentUniverseId
                    if (uId != null) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val existingValues = viewModel.getFieldValuesForUniverse(uId, field.id)
                            val suggestions = existingValues
                                .flatMap { it.split(",").map { v -> v.trim() } }
                                .filter { it.isNotBlank() }.distinct().sorted()
                            if (suggestions.isNotEmpty() && _binding != null) {
                                editText.setAdapter(ArrayAdapter(
                                    requireContext(),
                                    android.R.layout.simple_dropdown_item_1line,
                                    suggestions
                                ))
                            }
                        }
                    }
                }

                FieldType.CALCULATED -> {
                    val textView = TextView(context).apply {
                        text = getString(R.string.auto_calculated, field.name)
                        textSize = 14f
                        isEnabled = false
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                            topMargin = (4 * density).toInt()
                        }
                    }
                    binding.dynamicFormContainer.addView(textView)
                    fieldInputMap[field.id] = textView
                }

                null -> {
                    // 알 수 없는 필드 타입 - 기본 텍스트 입력
                    val inputLayout = TextInputLayout(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        hint = field.name
                    }
                    val editText = TextInputEditText(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    inputLayout.addView(editText)
                    binding.dynamicFormContainer.addView(inputLayout)
                    fieldInputMap[field.id] = editText
                }
            }
        }

        // 자동완성 데이터 배치 로드: 필드마다 개별 쿼리(M회) 대신 세계관 전체 값을 1회 조회 후 필드별 분배
        val uId = currentUniverseId
        if (uId != null && autoCompleteTargets.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val valuesByField = viewModel.getAllFieldValuesForUniverse(uId)
                    .groupBy { it.fieldDefinitionId }
                if (_binding == null) return@launch
                for ((fieldId, format, editText) in autoCompleteTargets) {
                    val existingValues = valuesByField[fieldId].orEmpty().map { it.value }
                    val suggestions = if (format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST) {
                        existingValues.flatMap { it.split(",").map { v -> v.trim() } }
                            .filter { it.isNotBlank() }.distinct().sorted()
                    } else {
                        existingValues.filter { it.isNotBlank() }.distinct().sorted()
                    }
                    if (suggestions.isNotEmpty()) {
                        editText.setAdapter(ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            suggestions
                        ))
                    }
                }
            }
        }

        // 동적 필드에 변경 추적 리스너 추가
        attachDynamicFieldChangeTracking()
    }

    private fun attachDynamicFieldChangeTracking() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                hasUnsavedChanges = true
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        for ((_, widget) in fieldInputMap) {
            when (widget) {
                is TextInputEditText -> widget.addTextChangedListener(watcher)
                is MaterialAutoCompleteTextView -> widget.addTextChangedListener(watcher)
                is Spinner -> widget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    private var initialized = false
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (initialized) { hasUnsavedChanges = true; updateSaveButtonState() }
                        initialized = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                is LinearLayout -> {
                    for (i in 0 until widget.childCount) {
                        val partLayout = widget.getChildAt(i) as? TextInputLayout
                        partLayout?.editText?.addTextChangedListener(watcher)
                    }
                }
            }
        }
    }

    private fun parseSelectOptions(configJson: String): List<String> =
        com.novelcharacter.app.util.FieldOptionParser.parseSelectOptions(configJson)

    private fun parseGradeOptions(configJson: String): List<String> =
        com.novelcharacter.app.util.FieldOptionParser.parseGradeOptions(configJson)

    private fun validateRequiredFields(): String? {
        for (field in fieldDefinitions) {
            if (!field.isRequired) continue
            val fieldType = FieldType.fromName(field.type)
            if (fieldType == FieldType.CALCULATED) continue
            val widget = fieldInputMap[field.id] ?: continue
            val isEmpty = when (widget) {
                is MaterialAutoCompleteTextView -> widget.text.isNullOrBlank()
                is TextInputEditText -> widget.text.isNullOrBlank()
                is Spinner -> widget.selectedItemPosition <= 0
                is LinearLayout -> {
                    // 구조화 입력: 모든 파트가 비어있으면 isEmpty
                    (0 until widget.childCount).all { i ->
                        val partLayout = widget.getChildAt(i) as? TextInputLayout
                        partLayout?.editText?.text.isNullOrBlank()
                    }
                }
                else -> true
            }
            if (isEmpty) return field.name
        }
        return null
    }

    private fun collectFieldValues(characterId: Long): List<CharacterFieldValue> {
        val values = mutableListOf<CharacterFieldValue>()

        for (field in fieldDefinitions) {
            val widget = fieldInputMap[field.id] ?: continue
            val fieldType = FieldType.fromName(field.type)

            // CALCULATED 필드는 저장하지 않음
            if (fieldType == FieldType.CALCULATED) continue

            val value: String = when (widget) {
                is MaterialAutoCompleteTextView -> widget.text.toString().trim()
                is TextInputEditText -> widget.text.toString().trim()
                is Spinner -> {
                    if (widget.selectedItemPosition > 0) {
                        widget.selectedItem.toString()
                    } else {
                        ""
                    }
                }
                is LinearLayout -> {
                    // 구조화 입력: 파트별 값을 separator로 합침
                    val config = widget.tag as? StructuredInputConfig
                    if (config != null) {
                        val partValues = mutableListOf<String>()
                        for (i in 0 until widget.childCount) {
                            val partLayout = widget.getChildAt(i) as? TextInputLayout
                            val partEdit = partLayout?.editText
                            partValues.add(partEdit?.text?.toString()?.trim() ?: "")
                        }
                        if (partValues.any { it.isNotEmpty() }) {
                            config.joinValues(partValues)
                        } else ""
                    } else ""
                }
                else -> ""
            }

            if (value.isNotEmpty()) {
                values.add(
                    CharacterFieldValue(
                        characterId = characterId,
                        fieldDefinitionId = field.id,
                        value = value
                    )
                )
            }
        }
        return values
    }

    private fun setupImageButton() {
        binding.btnAddImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.imageRecyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
    }

    private fun saveImageToInternalStorage(uri: Uri) {
        val context = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val filePath = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                    val maxSize = 20L * 1024 * 1024 // 20MB
                    val fileName = "char_${UUID.randomUUID()}.jpg"
                    val file = File(context.filesDir, fileName)
                    var totalBytes = 0L
                    var exceededLimit = false
                    inputStream.use { input ->
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                totalBytes += bytesRead
                                if (totalBytes > maxSize) {
                                    exceededLimit = true
                                    break
                                }
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                    if (exceededLimit) {
                        file.delete()
                        return@withContext null
                    }
                    file.absolutePath
                }
                if (_binding == null) return@launch
                if (filePath != null) {
                    imagePaths.add(filePath)
                    updateImageList()
                } else {
                    val ctx = context ?: return@launch
                    Toast.makeText(ctx, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    val ctx = context ?: return@launch
                    Toast.makeText(ctx, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                }
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

    /** 이전 이미지 목록과 현재 목록을 비교하여 제거된 파일을 디스크에서 삭제 */
    private fun cleanupRemovedImages(oldPathsJson: String?, currentPaths: List<String>) {
        val oldPaths: List<String> = try {
            gson.fromJson(oldPathsJson ?: "[]", com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val currentSet = currentPaths.toSet()
        for (path in oldPaths) {
            if (path !in currentSet) {
                try {
                    val file = java.io.File(path)
                    if (file.exists()) file.delete()
                } catch (_: Exception) { /* best effort */ }
            }
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
        return try {
            val file = java.io.File(path)
            val dir = appDir ?: return null
            if (!file.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator)) {
                return null // Reject paths outside app directory
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            val (height, width) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (inSampleSize < 1024 && halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            options.inSampleSize = inSampleSize
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            null
        }
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

            val missingRequired = validateRequiredFields()
            if (missingRequired != null) {
                Toast.makeText(requireContext(), getString(R.string.required_field_empty, missingRequired), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
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
                        showDuplicateDialog(candidates, isEdit, character)
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
        isEditMode: Boolean,
        character: Character
    ) {
        // Fragment Result API로 결과 수신 (회전에도 안전)
        childFragmentManager.setFragmentResultListener(
            DuplicateCharacterDialog.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val resolutionName = bundle.getString(DuplicateCharacterDialog.RESULT_RESOLUTION)
                ?: DuplicateCharacterDialog.Resolution.CANCEL.name
            val resolution = DuplicateCharacterDialog.Resolution.valueOf(resolutionName)
            val selectedCharId = bundle.getLong(DuplicateCharacterDialog.RESULT_SELECTED_CHARACTER_ID, -1L)

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

        val dialog = DuplicateCharacterDialog.newInstance(candidates, isEditMode)
        dialog.show(childFragmentManager, "duplicate_character")
    }

    private suspend fun performSave(character: Character, isUpdate: Boolean, targetCharacterId: Long) {
        // 저장 전 나이-출생연도 불일치 감지 (삽입 전에 체크하여 고아 레코드 방지)
        val tempCharId = if (isUpdate) targetCharacterId else -1L
        val tempFieldValues = collectFieldValues(tempCharId)
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
                ?: collectFieldValues(targetCharacterId)
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
                ?: collectFieldValues(newId)
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

        val missingRequired = validateRequiredFields()
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
                val tempFieldValues = collectFieldValues(tempCharId)

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
        super.onDestroyView()
        imageAdapter = null
        _binding = null
    }
}
