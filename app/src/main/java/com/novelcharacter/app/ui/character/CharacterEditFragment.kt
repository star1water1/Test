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
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
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

        // Restore imagePaths from saved state (rotation)
        savedInstanceState?.getStringArrayList("imagePaths")?.let { saved ->
            imagePaths.clear()
            imagePaths.addAll(saved)
            restoredFromSavedState = true
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.toolbar.title = if (characterId == -1L) getString(R.string.add_character) else getString(R.string.edit_character)

        setupImageButton()
        setupSaveButton()

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
        val novelNames = mutableListOf(getString(R.string.no_novel_selected))
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

        // 태그 로드
        val tags = viewModel.getTagsByCharacterList(characterId)
        binding.editTags.setText(tags.joinToString(", ") { it.tag })
    }

    private fun fillForm(character: Character) {
        binding.editName.setText(character.name)

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
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
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
        val context = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val filePath = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                    // Reject files larger than 20MB to prevent storage exhaustion
                    val fileName = "char_${UUID.randomUUID()}.jpg"
                    val file = File(context.filesDir, fileName)
                    inputStream.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Check file size after copy and delete if too large
                    if (file.length() > 20L * 1024 * 1024) {
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
                    Toast.makeText(requireContext(), R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var imageAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    private fun updateImageList() {
        if (imageAdapter == null) {
            imageAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val imageView = ImageView(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(200, 200).apply {
                            marginEnd = 8
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    return object : RecyclerView.ViewHolder(imageView) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val imageView = holder.itemView as ImageView
                    // Set placeholder first, then recycle old bitmap to avoid drawing recycled bitmap
                    val oldBitmap = imageView.tag as? android.graphics.Bitmap
                    imageView.tag = null
                    imageView.setImageResource(R.drawable.ic_character_placeholder)
                    oldBitmap?.let { if (!it.isRecycled) it.recycle() }
                    if (position < imagePaths.size) {
                        val path = imagePaths[position]
                        val boundPosition = position
                        viewLifecycleOwner.lifecycleScope.launch {
                            val bitmap = withContext(Dispatchers.IO) {
                                decodeSampledBitmap(path, 200, 200)
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
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
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

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val name = binding.editName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), R.string.enter_name, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val novelPosition = binding.spinnerNovel.selectedItemPosition
            val selectedNovelId = if (novelPosition > 0 && novelPosition - 1 < novels.size) novels[novelPosition - 1].id else null

            val memo = binding.editMemo.text.toString()

            val character = Character(
                id = if (characterId != -1L) characterId else 0,
                name = name,
                novelId = selectedNovelId,
                imagePaths = gson.toJson(imagePaths),
                createdAt = existingCharacter?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                memo = memo
            )

            viewLifecycleOwner.lifecycleScope.launch {
                val savedCharId: Long
                if (characterId != -1L) {
                    // 기존 캐릭터 수정
                    viewModel.updateCharacter(character)
                    val fieldValues = collectFieldValues(characterId)
                    viewModel.saveAllFieldValues(characterId, fieldValues)
                    savedCharId = characterId
                } else {
                    // 새 캐릭터 생성 - suspend로 ID를 받아온 뒤 필드값 저장
                    val newId = viewModel.insertCharacterSuspend(character)
                    val fieldValues = collectFieldValues(newId)
                    viewModel.saveAllFieldValues(newId, fieldValues)
                    savedCharId = newId
                }

                // 태그 저장 (트랜잭션으로 원자적 교체)
                val tagText = binding.editTags.text.toString()
                val tagList = tagText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                viewModel.replaceAllTagsSuspend(savedCharId, tagList.map { CharacterTag(characterId = savedCharId, tag = it) })

                if (isAdded && view != null) {
                    Toast.makeText(requireContext(), R.string.saved_successfully, Toast.LENGTH_SHORT).show()
                    findNavController().popBackStack()
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.imageRecyclerView.adapter = null
        super.onDestroyView()
        imageAdapter = null
        _binding = null
    }
}
