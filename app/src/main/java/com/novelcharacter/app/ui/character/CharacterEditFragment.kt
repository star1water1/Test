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
import android.widget.Toast
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
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.FragmentCharacterEditBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class CharacterEditFragment : Fragment() {

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
    private var hasUnsavedChanges = false

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveImageToInternalStorage(it) }
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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        characterId = arguments?.getLong("characterId", -1L) ?: -1L
        presetNovelId = arguments?.getLong("novelId", -1L) ?: -1L
        appDir = requireContext().filesDir

        // Restore imagePaths from saved state (rotation), filtering invalid paths
        savedInstanceState?.getStringArrayList("imagePaths")?.let { saved ->
            val dir = appDir
            val validated = if (dir != null) {
                saved.filter { path ->
                    try {
                        java.io.File(path).canonicalPath.startsWith(dir.canonicalPath)
                    } catch (_: Exception) { false }
                }
            } else {
                emptyList()
            }
            imagePaths.clear()
            imagePaths.addAll(validated)
            restoredFromSavedState = true
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
        setupSaveButton()
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

                    // 기존 캐릭터 편집 시, 필드 값 채우기
                    val existing = existingCharacter
                    if (existing != null) {
                        loadFieldValues(existing.id)
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
            val type = object : TypeToken<List<String>>() {}.type
            val paths: List<String> = try {
                gson.fromJson(character.imagePaths, type) ?: emptyList()
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

    private suspend fun loadFieldValues(characterId: Long) {
        val values = viewModel.getValuesByCharacterList(characterId)
        val valueMap = values.associateBy { it.fieldDefinitionId }

        for (field in fieldDefinitions) {
            val savedValue = valueMap[field.id]?.value ?: ""
            val widget = fieldInputMap[field.id] ?: continue

            when (widget) {
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
                        if (idx >= 0) widget.setSelection(idx)
                    } else if (fieldType == FieldType.GRADE) {
                        val grades = parseGradeOptions(field.config)
                        val gradeWithBlank = mutableListOf(getString(R.string.no_grade_selected))
                        gradeWithBlank.addAll(grades)
                        val idx = gradeWithBlank.indexOf(savedValue)
                        if (idx >= 0) widget.setSelection(idx)
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

        for (field in fieldDefinitions.sortedBy { it.displayOrder }) {
            val fieldType = FieldType.fromName(field.type)

            when (fieldType) {
                FieldType.TEXT, FieldType.BODY_SIZE -> {
                    val structuredConfig = StructuredInputConfig.fromConfig(field.config)
                    if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                        // 구조화 입력: 파트별 개별 입력 필드
                        val label = TextView(context).apply {
                            text = field.name
                            textSize = 14f
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = (4 * density).toInt()
                            }
                        }
                        binding.dynamicFormContainer.addView(label)

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
                        val editText = TextInputEditText(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            if (format == DisplayFormat.MULTILINE) {
                                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                minLines = 2
                            }
                        }
                        inputLayout.addView(editText)
                        binding.dynamicFormContainer.addView(inputLayout)
                        fieldInputMap[field.id] = editText
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
                    binding.dynamicFormContainer.addView(inputLayout)
                    fieldInputMap[field.id] = editText
                }

                FieldType.SELECT -> {
                    val label = TextView(context).apply {
                        text = field.name
                        textSize = 14f
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (4 * density).toInt()
                        }
                    }
                    binding.dynamicFormContainer.addView(label)

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
                    val label = TextView(context).apply {
                        text = field.name
                        textSize = 14f
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (4 * density).toInt()
                        }
                    }
                    binding.dynamicFormContainer.addView(label)

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
    }

    private fun parseSelectOptions(configJson: String): List<String> {
        return try {
            val configMap: Map<String, Any> = gson.fromJson(
                configJson, object : TypeToken<Map<String, Any>>() {}.type
            )
            @Suppress("UNCHECKED_CAST")
            (configMap["options"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseGradeOptions(configJson: String): List<String> {
        val baseGrades = listOf("C", "B", "A", "S")
        return try {
            val configMap: Map<String, Any> = gson.fromJson(
                configJson, object : TypeToken<Map<String, Any>>() {}.type
            )
            val allowNegative = configMap["allowNegative"] as? Boolean ?: false
            if (allowNegative) {
                val result = mutableListOf<String>()
                for (grade in baseGrades) {
                    result.add("-$grade")
                    result.add(grade)
                }
                result
            } else {
                baseGrades
            }
        } catch (e: Exception) {
            baseGrades
        }
    }

    private fun validateRequiredFields(): String? {
        for (field in fieldDefinitions) {
            if (!field.isRequired) continue
            val fieldType = FieldType.fromName(field.type)
            if (fieldType == FieldType.CALCULATED) continue
            val widget = fieldInputMap[field.id] ?: continue
            val isEmpty = when (widget) {
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
                    // Reset to placeholder; let GC handle old bitmap lifecycle
                    imageView.tag = null
                    imageView.setImageResource(R.drawable.ic_character_placeholder)
                    if (position < imagePaths.size) {
                        val path = imagePaths[position]
                        val boundPosition = position
                        viewLifecycleOwner.lifecycleScope.launch {
                            val targetSize = (80 * holder.itemView.context.resources.displayMetrics.density).toInt()
                            val bitmap = withContext(Dispatchers.IO) {
                                decodeSampledBitmap(path, targetSize, targetSize)
                            }
                            if (bitmap != null && holder.bindingAdapterPosition == boundPosition && isAdded) {
                                imageView.tag = bitmap
                                imageView.setImageBitmap(bitmap)
                            }
                        }
                    }
                    imageView.setOnLongClickListener {
                        val adapterPosition = holder.bindingAdapterPosition
                        if (adapterPosition >= 0 && adapterPosition < imagePaths.size) {
                            imagePaths.removeAt(adapterPosition)
                            imageAdapter?.notifyItemRemoved(adapterPosition)
                            imageAdapter?.notifyItemRangeChanged(adapterPosition, imagePaths.size - adapterPosition)
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

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
        return try {
            val file = java.io.File(path)
            val dir = appDir ?: return null
            if (!file.canonicalPath.startsWith(dir.canonicalPath)) {
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
                code = existingCharacter?.code ?: generateEntityCode()
            )

            isSaving = true
            binding.btnSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val savedCharId: Long
                    if (characterId != -1L) {
                        val fieldValues = collectFieldValues(characterId)
                        viewModel.updateCharacterWithFields(character, fieldValues)
                        savedCharId = characterId
                    } else {
                        val newId = viewModel.insertCharacterSuspend(character)
                        val fieldValues = collectFieldValues(newId)
                        viewModel.saveAllFieldValues(newId, fieldValues)
                        savedCharId = newId
                    }

                    val tagText = binding.editTags.text.toString()
                    val tagList = tagText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    viewModel.replaceAllTagsSuspend(savedCharId, tagList.map { CharacterTag(characterId = savedCharId, tag = it) })

                    if (isAdded && view != null) {
                        Toast.makeText(requireContext(), R.string.saved_successfully, Toast.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                } catch (e: Exception) {
                    if (isAdded && _binding != null) {
                        isSaving = false
                        binding.btnSave.isEnabled = true
                        Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun handleBackPress() {
        if (hasUnsavedChanges) {
            val ctx = context ?: return
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.unsaved_changes_title)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
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
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        binding.editName.addTextChangedListener(changeWatcher)
        binding.editFirstName.addTextChangedListener(changeWatcher)
        binding.editLastName.addTextChangedListener(changeWatcher)
        binding.editAnotherName.addTextChangedListener(changeWatcher)
        binding.editMemo.addTextChangedListener(changeWatcher)
    }

    override fun onDestroyView() {
        binding.imageRecyclerView.adapter = null
        super.onDestroyView()
        imageAdapter = null
        _binding = null
    }
}
