package com.novelcharacter.app.ui.character

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
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
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.CharacterFieldValue
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

        // 작품명 + 동적 필드 로드
        lifecycleScope.launch {
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            binding.detailNovel.text = "작품: ${novel?.title ?: "미지정"}"

            // 동적 필드 로드: novel -> universeId -> FieldDefinitions -> CharacterFieldValues
            val universeId = novel?.universeId
            if (universeId != null) {
                val fields = viewModel.getFieldsByUniverseList(universeId)
                val values = viewModel.getValuesByCharacterList(character.id)
                displayDynamicFields(fields, values)
            } else {
                binding.dynamicFieldsContainer.removeAllViews()
            }
        }

        // 이미지
        setupImages(character.imagePaths)
    }

    private fun displayDynamicFields(
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>
    ) {
        binding.dynamicFieldsContainer.removeAllViews()

        val valueMap = values.associateBy { it.fieldDefinitionId }

        // 그룹별로 정렬하여 표시
        val grouped = fields
            .sortedBy { it.displayOrder }
            .groupBy { it.groupName }

        val context = requireContext()
        val density = resources.displayMetrics.density

        for ((groupName, groupFields) in grouped) {
            // 카드 생성
            val card = CardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                radius = 8 * density
                cardElevation = 2 * density
                setContentPadding(
                    (16 * density).toInt(),
                    (12 * density).toInt(),
                    (16 * density).toInt(),
                    (12 * density).toInt()
                )
            }

            val cardContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 그룹 제목
            val titleView = TextView(context).apply {
                text = groupName
                setTypeface(null, Typeface.BOLD)
                textSize = 16f
                setTextColor(context.getColor(R.color.primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }
            cardContent.addView(titleView)

            // 필드 행들
            for (field in groupFields) {
                val fieldValue = valueMap[field.id]?.value ?: ""
                val displayValue = fieldValue.ifEmpty { "-" }

                val rowView = TextView(context).apply {
                    text = "${field.name}: $displayValue"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (4 * density).toInt()
                    }
                }
                cardContent.addView(rowView)
            }

            card.addView(cardContent)
            binding.dynamicFieldsContainer.addView(card)
        }
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
