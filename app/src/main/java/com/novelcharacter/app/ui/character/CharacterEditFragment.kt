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
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.FragmentCharacterEditBinding
import kotlinx.coroutines.launch
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

    // 동적 필드 관리
    private var fieldDefinitions: List<FieldDefinition> = emptyList()
    private val fieldInputMap = mutableMapOf<Long, Any>() // fieldDefinitionId -> input widget

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        characterId = arguments?.getLong("characterId", -1L) ?: -1L
        presetNovelId = arguments?.getLong("novelId", -1L) ?: -1L

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.toolbar.title = if (characterId == -1L) getString(R.string.add_character) else getString(R.string.edit_character)

        setupImageButton()
        setupSaveButton()

        viewLifecycleOwner.lifecycleScope.launch {
            loadNovels()
            if (characterId != -1L) {
                loadExistingCharacter()
            }
        }
    }

    private suspend fun loadNovels() {
        novels = viewModel.getAllNovelsList()
        val novelNames = mutableListOf("작품 미지정")
        novelNames.addAll(novels.map { it.title })

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, novelNames)
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
                    buildDynamicForm()

                    // 기존 캐릭터 편집 시, 필드 값 채우기
                    if (existingCharacter != null) {
                        loadFieldValues(existingCharacter!!.id)
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
        existingCharacter?.let { fillForm(it) }
    }

    private fun fillForm(character: Character) {
        binding.editName.setText(character.name)

        // 작품 선택
        character.novelId?.let { novelId ->
            val index = novels.indexOfFirst { it.id == novelId }
            if (index >= 0) binding.spinnerNovel.setSelection(index + 1)
        }

        // 이미지
        val type = object : TypeToken<List<String>>() {}.type
        val paths: List<String> = try {
            gson.fromJson(character.imagePaths, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        imagePaths.clear()
        imagePaths.addAll(paths)
        updateImageList()
    }

    private suspend fun loadFieldValues(characterId: Long) {
        val values = viewModel.getValuesByCharacterList(characterId)
        val valueMap = values.associateBy { it.fieldDefinitionId }

        for (field in fieldDefinitions) {
            val savedValue = valueMap[field.id]?.value ?: ""
            val widget = fieldInputMap[field.id] ?: continue

            when (widget) {
                is TextInputEditText -> widget.setText(savedValue)
                is Spinner -> {
                    val fieldType = FieldType.fromName(field.type)
                    if (fieldType == FieldType.SELECT) {
                        val options = parseSelectOptions(field.config)
                        val optionWithBlank = mutableListOf("선택 안함")
                        optionWithBlank.addAll(options)
                        val idx = optionWithBlank.indexOf(savedValue)
                        if (idx >= 0) widget.setSelection(idx)
                    } else if (fieldType == FieldType.GRADE) {
                        val grades = parseGradeOptions(field.config)
                        val gradeWithBlank = mutableListOf("등급 미정")
                        gradeWithBlank.addAll(grades)
                        val idx = gradeWithBlank.indexOf(savedValue)
                        if (idx >= 0) widget.setSelection(idx)
                    }
                }
            }
        }
    }

    private fun buildDynamicForm() {
        binding.dynamicFormContainer.removeAllViews()
        fieldInputMap.clear()

        val context = requireContext()
        val density = resources.displayMetrics.density

        for (field in fieldDefinitions.sortedBy { it.displayOrder }) {
            val fieldType = FieldType.fromName(field.type)

            when (fieldType) {
                FieldType.TEXT, FieldType.BODY_SIZE -> {
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
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    inputLayout.addView(editText)
                    binding.dynamicFormContainer.addView(inputLayout)
                    fieldInputMap[field.id] = editText
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
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
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
                    val optionsWithBlank = mutableListOf("선택 안함")
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
                    val gradesWithBlank = mutableListOf("등급 미정")
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
                        hint = "${field.name} (쉼표로 구분)"
                    }
                    val editText = TextInputEditText(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    inputLayout.addView(editText)
                    binding.dynamicFormContainer.addView(inputLayout)
                    fieldInputMap[field.id] = editText
                }

                FieldType.CALCULATED -> {
                    val textView = TextView(context).apply {
                        text = "${field.name}: (자동 계산)"
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
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
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
            (configMap["options"] as? List<String>) ?: emptyList()
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
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return
            val fileName = "char_${UUID.randomUUID()}.jpg"
            val file = File(requireContext().filesDir, fileName)
            FileOutputStream(file).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            imagePaths.add(file.absolutePath)
            updateImageList()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.image_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateImageList() {
        binding.imageRecyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val imageView = ImageView(parent.context).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(200, 200).apply {
                        marginEnd = 8
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                return object : RecyclerView.ViewHolder(imageView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val imageView = holder.itemView as ImageView
                if (position < imagePaths.size) {
                    val bitmap = BitmapFactory.decodeFile(imagePaths[position])
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
                imageView.setOnLongClickListener {
                    val adapterPosition = holder.adapterPosition
                    if (adapterPosition >= 0 && adapterPosition < imagePaths.size) {
                        imagePaths.removeAt(adapterPosition)
                        updateImageList()
                    }
                    true
                }
            }

            override fun getItemCount() = imagePaths.size
        }
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val name = binding.editName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), R.string.enter_name, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val novelPosition = binding.spinnerNovel.selectedItemPosition
            val selectedNovelId = if (novelPosition > 0) novels[novelPosition - 1].id else null

            val character = Character(
                id = if (characterId != -1L) characterId else 0,
                name = name,
                novelId = selectedNovelId,
                imagePaths = gson.toJson(imagePaths),
                createdAt = existingCharacter?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            viewLifecycleOwner.lifecycleScope.launch {
                if (characterId != -1L) {
                    // 기존 캐릭터 수정
                    viewModel.updateCharacter(character)
                    val fieldValues = collectFieldValues(characterId)
                    viewModel.saveAllFieldValues(characterId, fieldValues)
                } else {
                    // 새 캐릭터 생성 - suspend로 ID를 받아온 뒤 필드값 저장
                    val newId = viewModel.insertCharacterSuspend(character)
                    val fieldValues = collectFieldValues(newId)
                    viewModel.saveAllFieldValues(newId, fieldValues)
                }

                Toast.makeText(requireContext(), R.string.saved_successfully, Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
