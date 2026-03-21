package com.novelcharacter.app.ui.novel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.DialogNovelEditBinding
import com.novelcharacter.app.databinding.FragmentNovelListBinding
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.ui.adapter.NovelAdapter
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NovelListFragment : Fragment() {

    private var _binding: FragmentNovelListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NovelViewModel by viewModels()

    private lateinit var adapter: NovelAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private var universeId: Long = -1L
    private val pendingImagePaths = mutableListOf<String>()
    private var novelImageRecyclerView: RecyclerView? = null
    private var novelImageAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    private val novelImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            for (uri in uris) {
                try {
                    val savedPath = withContext(Dispatchers.IO) {
                        val inputStream = ctx.contentResolver.openInputStream(uri) ?: return@withContext null
                        val fileName = "novel_${java.util.UUID.randomUUID()}.jpg"
                        val file = java.io.File(ctx.filesDir, fileName)
                        inputStream.use { input ->
                            file.outputStream().use { out -> input.copyTo(out) }
                        }
                        val canonical = file.canonicalPath
                        if (!canonical.startsWith(ctx.filesDir.canonicalPath)) {
                            file.delete()
                            return@withContext null
                        }
                        canonical
                    }
                    if (savedPath != null) {
                        pendingImagePaths.add(savedPath)
                    }
                } catch (e: Exception) {
                    // 개별 실패는 건너뜀
                }
            }
            if (isAdded) {
                novelImageAdapter?.notifyDataSetChanged()
                if (pendingImagePaths.isNotEmpty()) {
                    novelImageRecyclerView?.visibility = View.VISIBLE
                }
            }
        }
    }

    private var importerInitialized = false
    private val importer by lazy {
        importerInitialized = true
        com.novelcharacter.app.excel.ExcelImporter(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNovelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.getString("pendingExportFilePath")?.let {
            pendingExportFile = java.io.File(it)
        }

        importer.registerLauncher(this)
        universeId = arguments?.getLong("universeId", -1L) ?: -1L
        viewModel.setUniverseFilter(universeId)

        setupRecyclerView()
        setupFab()
        setupToolbarMenu()
        observeData()

        if (universeId != -1L) {
            binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        }
    }

    private fun setupRecyclerView() {
        adapter = NovelAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onClick = { novel ->
                viewModel.recordRecentActivity(novel.id, novel.title)
                val bundle = Bundle().apply { putLong("novelId", novel.id) }
                findNavController().navigateSafe(R.id.novelListFragment, R.id.characterListFragment, bundle)
            },
            onEditClick = { novel ->
                showNovelEditDialog(novel)
            },
            onDeleteClick = { novel ->
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setMessage(R.string.confirm_delete)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        viewModel.deleteNovel(novel)
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            },
            onPinClick = { novel ->
                viewModel.togglePin(novel)
            }
        )
        binding.novelRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.novelRecyclerView.adapter = adapter

        adapter.onOrderChanged = { reorderedList ->
            viewModel.updateDisplayOrders(reorderedList)
        }
        adapter.resolveCharacterImage = { novelId, characterId, callback ->
            viewModel.resolveCharacterImage(novelId, characterId, callback)
        }

        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.onDragCompleted()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.novelRecyclerView)
            adapter.itemTouchHelper = it
        }
    }

    private fun setupFab() {
        binding.fabAddNovel.setOnClickListener {
            showNovelEditDialog(null)
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_global_search -> {
                    findNavController().navigateSafe(R.id.novelListFragment, R.id.globalSearchFragment)
                    true
                }
                R.id.action_export -> {
                    exportToExcel()
                    true
                }
                R.id.action_import -> {
                    importFromExcel()
                    true
                }
                R.id.action_reorder -> {
                    toggleReorderMode()
                    true
                }
                else -> false
            }
        }
    }

    private fun observeData() {
        viewModel.filteredNovels.observe(viewLifecycleOwner) { novels ->
            adapter.submitList(novels)
            binding.emptyText.visibility = if (novels.isEmpty()) View.VISIBLE else View.GONE
        }

        // Load universe border color for inheritance
        if (universeId != -1L) {
            viewModel.loadUniverseBorder(universeId)
        }
        viewModel.universeBorder.observe(viewLifecycleOwner) { (color, width) ->
            adapter.setUniverseBorder(color, width)
        }
    }

    private fun toggleReorderMode() {
        if (adapter.isReorderMode()) {
            // 자동 저장이 이미 되므로 모드만 종료
            adapter.setReorderMode(false)
            Toast.makeText(requireContext(), R.string.reorder_saved, Toast.LENGTH_SHORT).show()
        } else {
            adapter.setReorderMode(true)
            Toast.makeText(requireContext(), R.string.reorder_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNovelEditDialog(novel: Novel?) {
        val dialogBinding = DialogNovelEditBinding.inflate(layoutInflater)
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density

        novel?.let {
            dialogBinding.editTitle.setText(it.title)
            dialogBinding.editDescription.setText(it.description)
            dialogBinding.editBorderColor.setText(it.borderColor)
            it.standardYear?.let { year -> dialogBinding.editStandardYear.setText(year.toString()) }
        }

        var selectedColor = novel?.borderColor ?: ""

        // Setup color preview
        val previewBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * dp
            if (selectedColor.isNotBlank()) {
                try { setColor(Color.parseColor(selectedColor)) } catch (e: Exception) { android.util.Log.w("NovelList", "Invalid color: $selectedColor", e); setColor(Color.LTGRAY) }
            } else {
                setColor(Color.LTGRAY)
            }
            setStroke((1 * dp).toInt(), Color.GRAY)
        }
        dialogBinding.colorPreview.background = previewBg

        // Setup color presets
        val presets = com.novelcharacter.app.excel.BORDER_COLOR_PRESETS
        for (preset in presets) {
            val swatch = View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply {
                    marginEnd = (4 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    try { setColor(Color.parseColor(preset)) } catch (e: Exception) { android.util.Log.w("NovelList", "Invalid preset color: $preset", e) }
                }
                setOnClickListener {
                    selectedColor = preset
                    previewBg.setColor(Color.parseColor(preset))
                    dialogBinding.editBorderColor.setText(preset)
                }
            }
            dialogBinding.colorPresetsRow.addView(swatch)
        }

        // HEX edit watcher
        dialogBinding.editBorderColor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hex = s?.toString()?.trim() ?: ""
                selectedColor = hex
                try {
                    if (hex.isNotBlank()) previewBg.setColor(Color.parseColor(hex))
                } catch (e: Exception) {
                    android.util.Log.w("NovelList", "Invalid HEX color input: $hex", e)
                }
            }
        })

        // Reset button
        dialogBinding.resetBorderColor.setOnClickListener {
            selectedColor = ""
            dialogBinding.editBorderColor.setText("")
            previewBg.setColor(Color.LTGRAY)
        }

        // 이미지 모드 설정
        val imageModes = arrayOf(
            getString(R.string.image_mode_none),
            getString(R.string.image_mode_custom),
            getString(R.string.image_mode_random_character),
            getString(R.string.image_mode_select_character)
        )
        val imageModeValues = arrayOf(
            Novel.IMAGE_MODE_NONE, Novel.IMAGE_MODE_CUSTOM,
            Novel.IMAGE_MODE_RANDOM_CHARACTER, Novel.IMAGE_MODE_SELECT_CHARACTER
        )
        var selectedImageMode = novel?.imageMode ?: Novel.IMAGE_MODE_NONE
        var selectedImageCharId = novel?.imageCharacterId

        // 기존 이미지 경로 목록 로드
        pendingImagePaths.clear()
        val existingPaths = parseImagePaths(novel?.imagePaths ?: "[]")
        pendingImagePaths.addAll(existingPaths)

        dialogBinding.spinnerImageMode.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, imageModes)
        dialogBinding.spinnerImageMode.setSelection(imageModeValues.indexOf(selectedImageMode).coerceAtLeast(0))

        val isCustom = selectedImageMode == Novel.IMAGE_MODE_CUSTOM
        dialogBinding.btnSelectImage.visibility = if (isCustom) View.VISIBLE else View.GONE
        dialogBinding.imageRecyclerView.visibility = if (isCustom && pendingImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
        if (pendingImagePaths.isNotEmpty()) dialogBinding.btnSelectImage.text = getString(R.string.image_change)

        // 이미지 목록 RecyclerView 설정
        setupNovelImageRecyclerView(dialogBinding.imageRecyclerView)

        dialogBinding.btnSelectImage.setOnClickListener {
            novelImagePickerLauncher.launch("image/*")
        }

        // 캐릭터 선택 버튼 (select_character 모드 전용, 언제든 재선택 가능)
        dialogBinding.btnSelectCharacter.setOnClickListener {
            if (novel == null) {
                Toast.makeText(ctx, R.string.image_save_novel_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val chars = viewModel.getCharactersWithImages(novel.id)
                if (!isAdded) return@launch
                if (chars.isEmpty()) {
                    Toast.makeText(ctx, R.string.image_no_characters_with_images, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val charNames = chars.map { it.name }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.image_select_character)
                    .setItems(charNames) { _, which ->
                        selectedImageCharId = chars[which].id
                        dialogBinding.btnSelectCharacter.text = getString(R.string.image_character_selected, chars[which].name)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        // 기존 선택 캐릭터가 있으면 표시
        if (selectedImageCharId != null && novel != null) {
            dialogBinding.btnSelectCharacter.text = getString(R.string.image_change_character)
        }

        dialogBinding.spinnerImageMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedImageMode = imageModeValues[pos]
                val custom = selectedImageMode == Novel.IMAGE_MODE_CUSTOM
                dialogBinding.btnSelectImage.visibility = if (custom) View.VISIBLE else View.GONE
                dialogBinding.imageRecyclerView.visibility = if (custom && pendingImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
                dialogBinding.btnSelectCharacter.visibility = if (selectedImageMode == Novel.IMAGE_MODE_SELECT_CHARACTER) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(ctx)
            .setTitle(if (novel == null) R.string.add_novel else R.string.edit_novel)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = dialogBinding.editTitle.text.toString().trim()
                val description = dialogBinding.editDescription.text.toString().trim()
                val borderColor = dialogBinding.editBorderColor.text.toString().trim()
                val finalImagePaths = org.json.JSONArray(pendingImagePaths).toString()
                val standardYearStr = dialogBinding.editStandardYear.text.toString().trim()
                val standardYear = if (standardYearStr.isNotEmpty()) standardYearStr.toIntOrNull() else null
                if (title.isNotEmpty()) {
                    if (novel == null) {
                        val newNovel = Novel(
                            title = title,
                            description = description,
                            universeId = if (universeId != -1L) universeId else null,
                            borderColor = borderColor,
                            inheritUniverseBorder = borderColor.isBlank(),
                            imagePaths = finalImagePaths,
                            imageMode = selectedImageMode,
                            imageCharacterId = selectedImageCharId,
                            standardYear = standardYear
                        )
                        viewModel.insertNovel(newNovel)
                    } else {
                        val oldStdYear = novel.standardYear
                        val updatedNovel = novel.copy(
                            title = title,
                            description = description,
                            borderColor = borderColor,
                            inheritUniverseBorder = borderColor.isBlank(),
                            imagePaths = finalImagePaths,
                            imageMode = selectedImageMode,
                            imageCharacterId = selectedImageCharId,
                            standardYear = standardYear
                        )
                        viewModel.updateNovel(updatedNovel)
                        if (oldStdYear != standardYear) {
                            viewModel.onStandardYearChanged(updatedNovel, oldStdYear, standardYear)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupNovelImageRecyclerView(recyclerView: RecyclerView) {
        novelImageRecyclerView = recyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        novelImageAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val d = parent.context.resources.displayMetrics.density
                val sizePx = (64 * d).toInt()
                val imageView = android.widget.ImageView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(sizePx, sizePx).apply {
                        marginEnd = (4 * d).toInt()
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
                return object : RecyclerView.ViewHolder(imageView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val imageView = holder.itemView as android.widget.ImageView
                imageView.setImageResource(R.drawable.ic_character_placeholder)
                if (position < pendingImagePaths.size) {
                    val path = pendingImagePaths[position]
                    viewLifecycleOwner.lifecycleScope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            try {
                                val file = java.io.File(path)
                                if (!file.exists()) return@withContext null
                                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(path, options)
                                val size = 64 * imageView.context.resources.displayMetrics.density.toInt()
                                var sample = 1
                                val h = options.outHeight / 2; val w = options.outWidth / 2
                                while (h / sample >= size && w / sample >= size) sample *= 2
                                options.inSampleSize = sample
                                options.inJustDecodeBounds = false
                                android.graphics.BitmapFactory.decodeFile(path, options)
                            } catch (_: Exception) { null }
                        }
                        if (bitmap != null && isAdded) imageView.setImageBitmap(bitmap)
                    }
                }
                imageView.setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos >= 0 && pos < pendingImagePaths.size) {
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.delete)
                            .setMessage(R.string.image_delete_confirm)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                val currentPos = holder.bindingAdapterPosition
                                if (currentPos >= 0 && currentPos < pendingImagePaths.size) {
                                    pendingImagePaths.removeAt(currentPos)
                                    novelImageAdapter?.notifyItemRemoved(currentPos)
                                    novelImageAdapter?.notifyItemRangeChanged(currentPos, pendingImagePaths.size - currentPos)
                                    if (pendingImagePaths.isEmpty()) {
                                        novelImageRecyclerView?.visibility = View.GONE
                                    }
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    true
                }
            }

            override fun getItemCount() = pendingImagePaths.size
        }
        recyclerView.adapter = novelImageAdapter
    }

    private fun parseImagePaths(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private var exporter: com.novelcharacter.app.excel.ExcelExporter? = null
    private var pendingExportFile: java.io.File? = null

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (!isAdded) return@registerForActivityResult
        val file = pendingExportFile
        if (uri != null && file != null) {
            if (exporter == null) {
                exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext().applicationContext)
            }
            exporter?.writeToUri(uri, file)
        }
        pendingExportFile = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExportFile?.absolutePath?.let {
            outState.putString("pendingExportFilePath", it)
        }
    }

    private fun exportToExcel() {
        if (!isAdded) return
        val labels = com.novelcharacter.app.excel.ExportOptions.LABELS
        val checked = com.novelcharacter.app.excel.ExportOptions.ALL.toBooleanArray()

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_options_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.confirm) { _, _ ->
                val options = com.novelcharacter.app.excel.ExportOptions.fromBooleanArray(checked)
                showExportModeDialog(options)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExportModeDialog(options: com.novelcharacter.app.excel.ExportOptions) {
        if (!isAdded) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_mode_title)
            .setItems(arrayOf(getString(R.string.export_mode_share), getString(R.string.export_mode_save))) { _, which ->
                exporter?.cancel()
                exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext().applicationContext)
                when (which) {
                    0 -> exporter?.exportAll(options)
                    1 -> exporter?.exportAll(options) { file, fileName ->
                        if (isAdded) {
                            pendingExportFile = file
                            saveFileLauncher.launch(fileName)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importFromExcel() {
        if (!isAdded) return
        try {
            importer.showImportDialog(this)
        } catch (e: Exception) {
            if (isAdded) {
                Toast.makeText(requireContext(), R.string.import_file_too_large, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        binding.novelRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        exporter?.cancel()
        exporter = null
        if (importerInitialized) {
            importer.cleanup()
        }
    }
}
