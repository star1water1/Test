package com.novelcharacter.app.ui.character

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CombatRank
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

    private var characterId: Long = -1L
    private var presetNovelId: Long = -1L
    private var existingCharacter: Character? = null
    private var novels: List<Novel> = emptyList()
    private val imagePaths = mutableListOf<String>()

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

        setupCombatRankSpinner()
        setupTranscendentSwitch()
        setupImageButton()
        setupSaveButton()

        lifecycleScope.launch {
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
        binding.editAge.setText(character.age)
        binding.editHeight.setText(character.height)
        binding.editBodyType.setText(character.bodyType)
        binding.editRace.setText(character.race)
        binding.editJobTitle.setText(character.jobTitle)
        binding.editMagicPower.setText(character.magicPower)
        binding.editAuthority.setText(character.authority)
        binding.editResidence.setText(character.residence)
        binding.editAffiliation.setText(character.affiliation)
        binding.editLikes.setText(character.likes)
        binding.editDislikes.setText(character.dislikes)
        binding.editPersonality.setText(character.personality)
        binding.editAppearance.setText(character.appearance)
        binding.editSpecialNotes.setText(character.specialNotes)
        binding.editBirthday.setText(character.birthday)

        // 성별
        when (character.gender) {
            "남" -> binding.radioMale.isChecked = true
            "여" -> binding.radioFemale.isChecked = true
            else -> binding.radioGenderUnknown.isChecked = true
        }

        // 생존 여부
        when (character.isAlive) {
            true -> binding.radioAliveYes.isChecked = true
            false -> binding.radioAliveNo.isChecked = true
            null -> binding.radioAliveUnknown.isChecked = true
        }

        // 전투력 등급
        val rankIndex = CombatRank.labels().indexOf(character.combatRank)
        if (rankIndex >= 0) binding.spinnerCombatRank.setSelection(rankIndex + 1)

        // 초월자
        binding.switchTranscendent.isChecked = character.isTranscendent == true
        character.transcendentNumber?.let {
            binding.editTranscendentNumber.setText(it.toString())
        }
        binding.editTranscendentGeneration.setText(character.transcendentGeneration)

        // 작품
        character.novelId?.let { novelId ->
            val index = novels.indexOfFirst { it.id == novelId }
            if (index >= 0) binding.spinnerNovel.setSelection(index + 1)
        }

        // 이미지
        val gson = Gson()
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

    private fun setupCombatRankSpinner() {
        val ranks = mutableListOf("등급 미정")
        ranks.addAll(CombatRank.labels())
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, ranks)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCombatRank.adapter = adapter
    }

    private fun setupTranscendentSwitch() {
        binding.switchTranscendent.setOnCheckedChangeListener { _, isChecked ->
            val visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.layoutTranscendentNumber.visibility = visibility
            binding.layoutTranscendentGeneration.visibility = visibility
        }
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
            Toast.makeText(requireContext(), "이미지 저장 실패", Toast.LENGTH_SHORT).show()
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
                val bitmap = BitmapFactory.decodeFile(imagePaths[position])
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
                imageView.setOnLongClickListener {
                    imagePaths.removeAt(position)
                    updateImageList()
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
                Toast.makeText(requireContext(), "이름을 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val gender = when {
                binding.radioMale.isChecked -> "남"
                binding.radioFemale.isChecked -> "여"
                else -> "?"
            }

            val isAlive = when {
                binding.radioAliveYes.isChecked -> true
                binding.radioAliveNo.isChecked -> false
                else -> null
            }

            val novelPosition = binding.spinnerNovel.selectedItemPosition
            val selectedNovelId = if (novelPosition > 0) novels[novelPosition - 1].id else null

            val rankPosition = binding.spinnerCombatRank.selectedItemPosition
            val combatRank = if (rankPosition > 0) CombatRank.labels()[rankPosition - 1] else ""

            val isTranscendent = binding.switchTranscendent.isChecked
            val transcendentNumber = if (isTranscendent) {
                binding.editTranscendentNumber.text.toString().toIntOrNull()
            } else null
            val transcendentGeneration = if (isTranscendent) {
                binding.editTranscendentGeneration.text.toString().trim()
            } else ""

            val character = Character(
                id = if (characterId != -1L) characterId else 0,
                name = name,
                novelId = selectedNovelId,
                age = binding.editAge.text.toString().trim(),
                isAlive = isAlive,
                gender = gender,
                height = binding.editHeight.text.toString().trim(),
                bodyType = binding.editBodyType.text.toString().trim(),
                race = binding.editRace.text.toString().trim(),
                jobTitle = binding.editJobTitle.text.toString().trim(),
                isTranscendent = if (isTranscendent) true else null,
                transcendentNumber = transcendentNumber,
                transcendentGeneration = transcendentGeneration,
                magicPower = binding.editMagicPower.text.toString().trim(),
                authority = binding.editAuthority.text.toString().trim(),
                residence = binding.editResidence.text.toString().trim(),
                affiliation = binding.editAffiliation.text.toString().trim(),
                likes = binding.editLikes.text.toString().trim(),
                dislikes = binding.editDislikes.text.toString().trim(),
                personality = binding.editPersonality.text.toString().trim(),
                specialNotes = binding.editSpecialNotes.text.toString().trim(),
                appearance = binding.editAppearance.text.toString().trim(),
                combatRank = combatRank,
                birthday = binding.editBirthday.text.toString().trim(),
                imagePaths = Gson().toJson(imagePaths),
                createdAt = existingCharacter?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            if (characterId != -1L) {
                viewModel.updateCharacter(character)
            } else {
                viewModel.insertCharacter(character)
            }

            Toast.makeText(requireContext(), "저장되었습니다", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
