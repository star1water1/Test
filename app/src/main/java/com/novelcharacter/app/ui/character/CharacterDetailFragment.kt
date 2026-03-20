package com.novelcharacter.app.ui.character

import android.graphics.Bitmap
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
import com.novelcharacter.app.util.navigateSafe
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CharacterDetailFragment : Fragment() {

    private var _binding: FragmentCharacterDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private var characterId: Long = -1L

    private var cachedCharacter: Character? = null

    // Helpers
    private lateinit var fieldRenderer: DynamicFieldRenderer
    private lateinit var timeSliderHelper: TimeSliderHelper
    private lateinit var stateChangeHelper: StateChangeHelper
    private lateinit var relationshipHelper: RelationshipHelper

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        characterId = arguments?.getLong("characterId", -1L) ?: -1L
        appDir = requireContext().filesDir

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.fabEdit.setOnClickListener {
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigateSafe(R.id.characterDetailFragment, R.id.characterEditFragment, bundle)
        }

        initHelpers()

        timeSliderHelper.setup()
        stateChangeHelper.setup()
        relationshipHelper.setup()
        observeCharacter()
        observeEvents()
        stateChangeHelper.observe()
        relationshipHelper.observe()
        loadCharacterStats()
    }

    private fun initHelpers() {
        fieldRenderer = DynamicFieldRenderer(
            containerGetter = { binding.dynamicFieldsContainer },
            contextGetter = { requireContext() },
            resourcesGetter = { resources },
            getString = { id -> getString(id) },
            getStringWithArg = { id, arg -> getString(id, arg) }
        )

        timeSliderHelper = TimeSliderHelper(
            binding = binding,
            viewModel = viewModel,
            viewLifecycleOwner = viewLifecycleOwner,
            characterId = characterId,
            fieldRenderer = fieldRenderer,
            getString = { id, arg -> getString(id, arg) }
        )

        stateChangeHelper = StateChangeHelper(
            binding = binding,
            viewModel = viewModel,
            viewLifecycleOwner = viewLifecycleOwner,
            characterId = characterId,
            contextGetter = { requireContext() },
            getString = { id -> getString(id) },
            cachedFieldsGetter = { timeSliderHelper.cachedFields },
            onSliderUpdate = { timeSliderHelper.updateSliderRange() }
        )

        relationshipHelper = RelationshipHelper(
            binding = binding,
            viewModel = viewModel,
            viewLifecycleOwner = viewLifecycleOwner,
            characterId = characterId,
            contextGetter = { requireContext() },
            getString = { id -> getString(id) },
            getFormattedString = { id, args -> getString(id, *args) },
            navController = { findNavController() }
        )
    }

    // ===== Character display =====

    private fun observeCharacter() {
        viewModel.getCharacterById(characterId).observe(viewLifecycleOwner) { character ->
            character?.let {
                cachedCharacter = it
                displayCharacter(it)
            }
        }

        viewModel.getTagsByCharacter(characterId).observe(viewLifecycleOwner) { tags ->
            if (tags.isNotEmpty()) {
                binding.detailTags.visibility = View.VISIBLE
                binding.detailTags.text = tags.joinToString("  ") { "#${it.tag}" }
            } else {
                binding.detailTags.visibility = View.GONE
            }
        }
    }

    private fun observeEvents() {
        val timelineAdapter = TimelineAdapter(
            onClick = { /* 연표 클릭 시 */ },
            onLongClick = { /* 연표 롱클릭 시 */ },
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            loadCharactersForEvent = { eventId -> viewModel.getCharactersForEvent(eventId) }
        )
        binding.eventsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.eventsRecyclerView.adapter = timelineAdapter

        viewModel.getEventsForCharacter(characterId).observe(viewLifecycleOwner) { events ->
            timelineAdapter.submitEventList(events)
        }
    }

    private fun displayCharacter(character: Character) {
        binding.toolbar.title = character.name

        binding.detailName.text = character.name

        // firstName/lastName 표시
        if (character.firstName.isNotBlank() || character.lastName.isNotBlank()) {
            binding.detailFullName.visibility = View.VISIBLE
            val parts = listOf(character.lastName, character.firstName).filter { it.isNotBlank() }
            binding.detailFullName.text = parts.joinToString(" ")
        } else {
            binding.detailFullName.visibility = View.GONE
        }

        // 별칭 칩 표시
        val aliases = character.aliases
        if (aliases.isNotEmpty()) {
            binding.aliasChipGroup.visibility = View.VISIBLE
            binding.aliasChipGroup.removeAllViews()
            for (alias in aliases) {
                val chip = Chip(requireContext()).apply {
                    text = alias
                    isClickable = false
                    isCheckable = false
                }
                binding.aliasChipGroup.addView(chip)
            }
        } else {
            binding.aliasChipGroup.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            if (_binding == null) return@launch
            binding.detailNovel.text = getString(R.string.novel_label_format, novel?.title ?: getString(R.string.novel_unassigned))

            if (character.memo.isNotBlank()) {
                binding.memoCard.visibility = View.VISIBLE
                binding.detailMemo.text = character.memo
            } else {
                binding.memoCard.visibility = View.GONE
            }

            if (_binding == null) return@launch
            val universeId = novel?.universeId
            if (universeId != null) {
                val fields = viewModel.getFieldsByUniverseList(universeId)
                if (_binding == null) return@launch
                val values = viewModel.getValuesByCharacterList(character.id)
                if (_binding == null) return@launch
                timeSliderHelper.cachedFields = fields
                timeSliderHelper.cachedValues = values

                if (timeSliderHelper.isTimeViewActive && timeSliderHelper.currentSliderYear != null) {
                    timeSliderHelper.applyTimeView(timeSliderHelper.currentSliderYear!!)
                } else {
                    fieldRenderer.displayDynamicFields(fields, values)
                }
            } else {
                timeSliderHelper.cachedFields = emptyList()
                timeSliderHelper.cachedValues = emptyList()
                binding.dynamicFieldsContainer.removeAllViews()
            }
        }

        setupImages(character.imagePaths)
    }

    // ===== Images =====

    private fun setupImages(imagePathsJson: String) {
        val imagePaths: List<String> = try {
            GSON.fromJson(imagePathsJson, IMAGE_PATHS_TYPE) ?: emptyList()
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
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                return object : RecyclerView.ViewHolder(imageView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val imageView = holder.itemView as ImageView
                // Cancel previous image loading job for this ViewHolder
                (imageView.getTag(R.id.image_load_job) as? kotlinx.coroutines.Job)?.cancel()
                imageView.setImageResource(R.drawable.ic_character_placeholder)
                val path = imagePaths[position]
                imageView.setOnClickListener {
                    val bundle = Bundle().apply {
                        putString("imagePaths", imagePathsJson)
                        putInt("startPosition", position)
                    }
                    findNavController().navigateSafe(R.id.characterDetailFragment, R.id.imageViewerFragment, bundle)
                }
                val boundPosition = position
                val job = viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeSampledBitmap(path, 1024, 1024)
                    }
                    if (bitmap != null && holder.bindingAdapterPosition == boundPosition && isAdded) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
                imageView.setTag(R.id.image_load_job, job)
            }

            override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
                val imageView = holder.itemView as ImageView
                (imageView.getTag(R.id.image_load_job) as? kotlinx.coroutines.Job)?.cancel()
                imageView.setImageDrawable(null)
            }

            override fun getItemCount() = imagePaths.size
        }
    }

    private var appDir: java.io.File? = null
    private fun getAppDir(): java.io.File {
        return appDir ?: requireContext().filesDir.also { appDir = it }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val file = java.io.File(path)
            val dir = appDir ?: return null
            if (!file.canonicalPath.startsWith(dir.canonicalPath)) {
                return null
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (inSampleSize < 1024 && halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun loadCharacterStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val character = viewModel.getCharacterByIdSuspend(characterId) ?: return@launch
            val relationships = viewModel.getRelationshipsForCharacterList(characterId)
            val events = viewModel.getEventsForCharacterSuspend(characterId)
            val stateChanges = viewModel.getChangesByCharacterList(characterId)

            // 필드 완성도 계산
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            val universeId = novel?.universeId
            val fieldCompletion = if (universeId != null) {
                val fields = viewModel.getFieldsByUniverseList(universeId)
                val values = viewModel.getValuesByCharacterList(characterId)
                if (fields.isNotEmpty()) {
                    values.count { it.value.isNotBlank() }.toFloat() / fields.size * 100f
                } else 0f
            } else 0f

            // 복잡도 점수
            val complexity = relationships.size * 2f +
                events.size * 1.5f +
                fieldCompletion * 0.3f +
                stateChanges.size * 1f

            if (_binding == null) return@launch

            val container = binding.characterStatsContainer
            container.removeAllViews()
            val ctx = context ?: return@launch

            val stats = listOf(
                getString(R.string.char_stats_relationship_count, relationships.size),
                getString(R.string.char_stats_event_count, events.size),
                getString(R.string.char_stats_field_completion, fieldCompletion),
                getString(R.string.char_stats_state_changes, stateChanges.size),
                getString(R.string.char_stats_alias_count, character.aliases.size),
                getString(R.string.char_stats_complexity, complexity)
            )

            stats.forEach { text ->
                val tv = android.widget.TextView(ctx).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface))
                    setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0,
                        (2 * resources.displayMetrics.density).toInt())
                }
                container.addView(tv)
            }
        }
    }

    override fun onDestroyView() {
        relationshipHelper.cancelJob()
        timeSliderHelper.cancelJob()
        binding.imageViewPager.adapter = null
        binding.eventsRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val GSON = Gson()
        private val IMAGE_PATHS_TYPE = object : TypeToken<List<String>>() {}.type
    }
}
