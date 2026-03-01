package com.novelcharacter.app.ui.character

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import kotlinx.coroutines.launch

class CharacterDetailFragment : Fragment() {

    private var _binding: FragmentCharacterDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private var characterId: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        characterId = arguments?.getLong("characterId", -1L) ?: -1L

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.fabEdit.setOnClickListener {
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigate(R.id.characterEditFragment, bundle)
        }

        observeCharacter()
        observeEvents()
    }

    private fun observeCharacter() {
        viewModel.getCharacterById(characterId).observe(viewLifecycleOwner) { character ->
            character?.let { displayCharacter(it) }
        }
    }

    private fun observeEvents() {
        val timelineAdapter = TimelineAdapter(
            onClick = { /* 연표 클릭 시 */ },
            onLongClick = { /* 연표 롱클릭 시 */ }
        )
        binding.eventsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.eventsRecyclerView.adapter = timelineAdapter

        viewModel.getEventsForCharacter(characterId).observe(viewLifecycleOwner) { events ->
            timelineAdapter.submitList(events)
        }
    }

    private fun displayCharacter(character: Character) {
        binding.toolbar.title = character.name

        // 기본 정보
        binding.detailName.text = character.name
        binding.detailAge.text = "나이: ${character.age.ifEmpty { "불명" }}"
        binding.detailGender.text = "성별: ${character.gender.ifEmpty { "불명" }}"
        binding.detailHeight.text = "키: ${character.height.ifEmpty { "불명" }}"
        binding.detailBodyType.text = "체형: ${character.bodyType.ifEmpty { "불명" }}"
        binding.detailRace.text = "종족: ${character.race.ifEmpty { "불명" }}"
        binding.detailJobTitle.text = "직업/직책: ${character.jobTitle.ifEmpty { "없음" }}"

        // 생존 여부
        val aliveText = when (character.isAlive) {
            true -> "생존"
            false -> "사망"
            null -> "불명"
        }
        binding.detailAlive.text = "생존: $aliveText"

        // 전투력 등급
        val rank = CombatRank.fromLabel(character.combatRank)
        binding.detailCombatRank.text = if (rank != null) {
            "전투력: ${rank.label} - ${rank.description}"
        } else {
            "전투력: ${character.combatRank.ifEmpty { "미정" }}"
        }

        // 작품명
        lifecycleScope.launch {
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            binding.detailNovel.text = "작품: ${novel?.title ?: "미지정"}"
        }

        // 초월자 정보
        if (character.isTranscendent == true) {
            binding.transcendentCard.visibility = View.VISIBLE
            binding.detailTranscendentNumber.text =
                "제 ${character.transcendentNumber ?: "?"} 초월자"
            binding.detailTranscendentGeneration.text =
                "세대: ${character.transcendentGeneration.ifEmpty { "불명" }}"
        }

        // 능력 정보
        binding.detailMagicPower.text = "마력: ${character.magicPower.ifEmpty { "없음" }}"
        binding.detailAuthority.text = "권능: ${character.authority.ifEmpty { "없음" }}"

        // 소속 정보
        binding.detailResidence.text = "거주지: ${character.residence.ifEmpty { "불명" }}"
        binding.detailAffiliation.text = "소속: ${character.affiliation.ifEmpty { "없음" }}"

        // 성격/개인 정보
        binding.detailPersonality.text = "성격: ${character.personality.ifEmpty { "불명" }}"
        binding.detailLikes.text = "좋아하는 것: ${character.likes.ifEmpty { "불명" }}"
        binding.detailDislikes.text = "싫어하는 것: ${character.dislikes.ifEmpty { "불명" }}"

        // 외모/특이사항
        binding.detailAppearance.text = "외모: ${character.appearance.ifEmpty { "불명" }}"
        binding.detailSpecialNotes.text = "특이사항: ${character.specialNotes.ifEmpty { "없음" }}"

        // 이미지
        setupImages(character.imagePaths)
    }

    private fun setupImages(imagePathsJson: String) {
        val gson = Gson()
        val type = object : TypeToken<List<String>>() {}.type
        val imagePaths: List<String> = try {
            gson.fromJson(imagePathsJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (imagePaths.isEmpty()) {
            binding.imageViewPager.visibility = View.GONE
            return
        }

        binding.imageViewPager.visibility = View.VISIBLE
        binding.imageViewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val imageView = ImageView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                return object : RecyclerView.ViewHolder(imageView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val imageView = holder.itemView as ImageView
                val bitmap = BitmapFactory.decodeFile(imagePaths[position])
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(R.drawable.ic_character_placeholder)
                }
            }

            override fun getItemCount() = imagePaths.size
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
