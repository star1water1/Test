package com.novelcharacter.app.ui.universe

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.novelcharacter.app.data.model.RecentActivity
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentUniverseListBinding
import androidx.recyclerview.widget.ItemTouchHelper
import com.novelcharacter.app.ui.adapter.UniverseAdapter
import com.novelcharacter.app.util.PresetTemplates
import com.novelcharacter.app.util.navigateSafe

class UniverseListFragment : Fragment() {

    private var _binding: FragmentUniverseListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UniverseViewModel by viewModels()

    private lateinit var adapter: UniverseAdapter
    private var recentAdapter: RecentActivityAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private var pendingImageCallback: ((String) -> Unit)? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveImageForEntity(it) }
    }

    private fun saveImageForEntity(uri: Uri) {
        try {
            val ctx = requireContext()
            val inputStream = ctx.contentResolver.openInputStream(uri) ?: return
            val fileName = "universe_${java.util.UUID.randomUUID()}.jpg"
            val file = java.io.File(ctx.filesDir, fileName)
            file.outputStream().use { out -> inputStream.copyTo(out) }
            inputStream.close()
            pendingImageCallback?.invoke(file.absolutePath)
            pendingImageCallback = null
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.image_save_failed, Toast.LENGTH_SHORT).show()
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
        _binding = FragmentUniverseListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.getString("pendingExportFilePath")?.let {
            pendingExportFile = java.io.File(it)
        }
        importer.registerLauncher(this)
        setupRecyclerView()
        setupFab()
        setupToolbarMenu()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = UniverseAdapter(
            onClick = { universe ->
                viewModel.recordRecentActivity(RecentActivity.TYPE_UNIVERSE, universe.id, universe.name)
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.novelListFragment, bundle)
            },
            onEditClick = { universe ->
                showUniverseEditDialog(universe)
            },
            onDeleteClick = { universe ->
                AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.confirm_delete_universe, universe.name))
                    .setPositiveButton(R.string.yes) { _, _ ->
                        viewModel.deleteUniverse(universe)
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            },
            onFieldManageClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.fieldManageFragment, bundle)
            }
        )
        binding.universeRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.universeRecyclerView.adapter = adapter

        adapter.onOrderChanged = { reorderedList ->
            viewModel.updateDisplayOrders(reorderedList)
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
            it.attachToRecyclerView(binding.universeRecyclerView)
            adapter.itemTouchHelper = it
        }
    }

    private fun setupFab() {
        binding.fabAddUniverse.setOnClickListener {
            showUniverseEditDialog(null)
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    exportToExcel()
                    true
                }
                R.id.action_import -> {
                    importFromExcel()
                    true
                }
                R.id.action_preset -> {
                    showPresetDialog()
                    true
                }
                R.id.action_reorder -> {
                    toggleReorderMode()
                    true
                }
                R.id.action_stats -> {
                    findNavController().navigateSafe(R.id.universeListFragment, R.id.statsMainFragment, null)
                    true
                }
                R.id.action_settings -> {
                    findNavController().navigateSafe(R.id.universeListFragment, R.id.settingsFragment, null)
                    true
                }
                else -> false
            }
        }
    }

    private fun showPresetDialog() {
        val builtInTemplates = viewModel.getPresetTemplates()
        val userPresets = viewModel.userPresets.value ?: emptyList()
        val userTemplates = userPresets.map { PresetTemplates.fromUserPreset(it) }
        val allTemplates = builtInTemplates + userTemplates

        val names = allTemplates.map { t ->
            val prefix = if (t.isBuiltIn) "[기본] " else "[사용자] "
            "$prefix${t.universe.name} — ${t.universe.description}"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_preset)
            .setItems(names) { _, which ->
                val template = allTemplates[which]
                if (template.isBuiltIn) {
                    viewModel.applyPreset(template)
                } else {
                    // 사용자 프리셋: 적용/편집/삭제 선택
                    showUserPresetOptionsDialog(template, userPresets[which - builtInTemplates.size])
                }
            }
            .setNeutralButton(R.string.preset_save_current, null)
            .setNegativeButton(R.string.cancel, null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        dialog.dismiss()
                        showSaveAsPresetDialog()
                    }
                }
            }
            .show()
    }

    private fun showUserPresetOptionsDialog(template: PresetTemplates.PresetTemplate, preset: com.novelcharacter.app.data.model.UserPresetTemplate) {
        val options = arrayOf(
            getString(R.string.preset_apply),
            getString(R.string.preset_edit_name),
            getString(R.string.delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(preset.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.applyPreset(template)
                    1 -> showEditPresetNameDialog(preset)
                    2 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.delete_warning_title)
                            .setMessage(getString(R.string.confirm_delete_preset, preset.name))
                            .setPositiveButton(R.string.yes) { _, _ ->
                                viewModel.deleteUserPreset(preset)
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditPresetNameDialog(preset: com.novelcharacter.app.data.model.UserPresetTemplate) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val dp24 = (24 * ctx.resources.displayMetrics.density).toInt()
            setPadding(dp24, dp24, dp24, 0)
        }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.preset_name_hint)
            setText(preset.name)
        }
        val descEdit = EditText(ctx).apply {
            hint = getString(R.string.preset_desc_hint)
            setText(preset.description)
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.preset_edit_name)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val desc = descEdit.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.updateUserPreset(preset.copy(name = name, description = desc))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSaveAsPresetDialog() {
        // 세계관 선택 → 해당 세계관의 필드를 프리셋으로 저장
        val universes = viewModel.allUniverses.value ?: emptyList()
        if (universes.isEmpty()) {
            Toast.makeText(requireContext(), R.string.preset_no_universes, Toast.LENGTH_SHORT).show()
            return
        }
        val names = universes.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset_select_source)
            .setItems(names) { _, which ->
                val universe = universes[which]
                showPresetNameInputDialog(universe)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPresetNameInputDialog(universe: Universe) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val dp24 = (24 * ctx.resources.displayMetrics.density).toInt()
            setPadding(dp24, dp24, dp24, 0)
        }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.preset_name_hint)
            setText(universe.name)
        }
        val descEdit = EditText(ctx).apply {
            hint = getString(R.string.preset_desc_hint)
            setText(universe.description)
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.preset_save_title)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val desc = descEdit.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.saveAsUserPreset(universe.id, name, desc)
                    Toast.makeText(ctx, getString(R.string.preset_saved, name), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeData() {
        viewModel.allUniverses.observe(viewLifecycleOwner) { universes ->
            adapter.submitList(universes)
            binding.emptyText.visibility = if (universes.isEmpty()) View.VISIBLE else View.GONE
            viewModel.loadCounts(universes)
        }

        viewModel.universeNovelCounts.observe(viewLifecycleOwner) { counts ->
            adapter.updateNovelCounts(counts)
        }

        viewModel.universeFieldCounts.observe(viewLifecycleOwner) { counts ->
            adapter.updateFieldCounts(counts)
        }

        viewModel.presetApplied.observe(viewLifecycleOwner) { event ->
            event?.getContentIfNotHandled()?.let { name ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.preset_loaded, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Recent activities cards
        viewModel.recentActivities.observe(viewLifecycleOwner) { recents ->
            if (recents.isNullOrEmpty()) {
                binding.recentSection.visibility = View.GONE
            } else {
                binding.recentSection.visibility = View.VISIBLE
                setupRecentCards(recents)
            }
        }
    }

    private fun setupRecentCards(recents: List<RecentActivity>) {
        if (recentAdapter == null) {
            binding.recentRecyclerView.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            recentAdapter = RecentActivityAdapter(
                getTypeLabel = { entityType ->
                    when (entityType) {
                        RecentActivity.TYPE_CHARACTER -> getString(R.string.recent_type_character)
                        RecentActivity.TYPE_NOVEL -> getString(R.string.recent_type_novel)
                        RecentActivity.TYPE_UNIVERSE -> getString(R.string.recent_type_universe)
                        else -> entityType
                    }
                },
                onClick = { navigateToRecentItem(it) }
            )
            binding.recentRecyclerView.adapter = recentAdapter
        }
        recentAdapter?.submitList(recents)
    }

    private class RecentActivityAdapter(
        private val getTypeLabel: (String) -> String,
        private val onClick: (RecentActivity) -> Unit
    ) : RecyclerView.Adapter<RecentActivityAdapter.ViewHolder>() {

        private var items: List<RecentActivity> = emptyList()

        fun submitList(newItems: List<RecentActivity>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val typeLabel: TextView = view.findViewById(R.id.recentTypeLabel)
            val titleView: TextView = view.findViewById(R.id.recentTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_activity, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.typeLabel.text = getTypeLabel(item.entityType)
            holder.titleView.text = item.title
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    private fun navigateToRecentItem(item: RecentActivity) {
        when (item.entityType) {
            RecentActivity.TYPE_UNIVERSE -> {
                val bundle = Bundle().apply { putLong("universeId", item.entityId) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.novelListFragment, bundle)
            }
            RecentActivity.TYPE_NOVEL -> {
                val bundle = Bundle().apply { putLong("novelId", item.entityId) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.characterListFragment, bundle)
            }
            RecentActivity.TYPE_CHARACTER -> {
                val bundle = Bundle().apply { putLong("characterId", item.entityId) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.characterDetailFragment, bundle)
            }
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

    private fun showUniverseEditDialog(universe: Universe?) {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val dp16 = (16 * dp).toInt()
            val dp24 = (24 * dp).toInt()
            val dp8 = (8 * dp).toInt()
            setPadding(dp24, dp16, dp24, dp8)
        }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.universe_name_hint)
            universe?.let { setText(it.name) }
        }
        val descEdit = EditText(ctx).apply {
            hint = getString(R.string.universe_desc_hint)
            universe?.let { setText(it.description) }
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)

        // Border color picker section
        val colorLabel = TextView(ctx).apply {
            text = getString(R.string.border_color_label)
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        }
        layout.addView(colorLabel)

        var selectedColor = universe?.borderColor ?: ""

        val colorPreview = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
                bottomMargin = (8 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * dp
                if (selectedColor.isNotBlank()) {
                    try { setColor(Color.parseColor(selectedColor)) } catch (_: Exception) { setColor(Color.LTGRAY) }
                } else {
                    setColor(Color.LTGRAY)
                }
                setStroke((1 * dp).toInt(), Color.GRAY)
            }
        }
        layout.addView(colorPreview)

        // HEX input (declared first so presets can reference it)
        val colorHexEdit = EditText(ctx).apply {
            hint = getString(R.string.border_color_hex_hint)
            setText(selectedColor)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val hex = s?.toString()?.trim() ?: ""
                    selectedColor = hex
                    try {
                        if (hex.isNotBlank()) {
                            (colorPreview.background as? GradientDrawable)?.setColor(Color.parseColor(hex))
                        }
                    } catch (_: Exception) {}
                }
            })
        }

        // Color presets row
        val presetsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        val presets = com.novelcharacter.app.excel.BORDER_COLOR_PRESETS
        for (preset in presets) {
            val swatch = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply {
                    marginEnd = (4 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    try { setColor(Color.parseColor(preset)) } catch (_: Exception) {}
                }
                setOnClickListener {
                    selectedColor = preset
                    (colorPreview.background as? GradientDrawable)?.setColor(Color.parseColor(preset))
                    colorHexEdit.setText(preset)
                }
            }
            presetsRow.addView(swatch)
        }
        layout.addView(presetsRow)
        layout.addView(colorHexEdit)

        // Clear button
        val clearBtn = TextView(ctx).apply {
            text = getString(R.string.border_color_reset)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                selectedColor = ""
                colorHexEdit.setText("")
                (colorPreview.background as? GradientDrawable)?.setColor(Color.LTGRAY)
            }
        }
        layout.addView(clearBtn)

        // 이미지 모드 선택
        val imageLabel = TextView(ctx).apply {
            text = getString(R.string.image_mode_label)
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        }
        layout.addView(imageLabel)

        val imageModes = arrayOf(
            getString(R.string.image_mode_none),
            getString(R.string.image_mode_custom),
            getString(R.string.image_mode_random_character)
        )
        val imageModeValues = arrayOf(Universe.IMAGE_MODE_NONE, Universe.IMAGE_MODE_CUSTOM, Universe.IMAGE_MODE_RANDOM_CHARACTER)
        var selectedImageMode = universe?.imageMode ?: Universe.IMAGE_MODE_NONE
        var selectedImagePath = universe?.imagePath ?: ""

        val imageModeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, imageModes)
            setSelection(imageModeValues.indexOf(selectedImageMode).coerceAtLeast(0))
        }
        layout.addView(imageModeSpinner)

        val imageSelectBtn = TextView(ctx).apply {
            text = if (selectedImagePath.isNotBlank()) getString(R.string.image_change) else getString(R.string.image_select)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_CUSTOM) View.VISIBLE else View.GONE
            setOnClickListener {
                pendingImageCallback = { path ->
                    selectedImagePath = path
                    text = getString(R.string.image_selected)
                }
                imagePickerLauncher.launch("image/*")
            }
        }
        layout.addView(imageSelectBtn)

        imageModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedImageMode = imageModeValues[pos]
                imageSelectBtn.visibility = if (selectedImageMode == Universe.IMAGE_MODE_CUSTOM) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(ctx)
            .setTitle(if (universe == null) R.string.add_universe else R.string.edit_universe)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val desc = descEdit.text.toString().trim()
                val borderColor = colorHexEdit.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (universe == null) {
                        viewModel.insertUniverse(Universe(
                            name = name, description = desc, borderColor = borderColor,
                            imagePath = selectedImagePath, imageMode = selectedImageMode
                        ))
                    } else {
                        viewModel.updateUniverse(universe.copy(
                            name = name, description = desc, borderColor = borderColor,
                            imagePath = selectedImagePath, imageMode = selectedImageMode
                        ))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var exporter: com.novelcharacter.app.excel.ExcelExporter? = null
    private var pendingExportFile: java.io.File? = null

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_mode_title)
            .setItems(arrayOf(getString(R.string.export_mode_share), getString(R.string.export_mode_save))) { _, which ->
                exporter?.cancel()
                exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext().applicationContext)
                when (which) {
                    0 -> exporter?.exportAll()
                    1 -> exporter?.exportAll { file, fileName ->
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
        importer.showImportDialog(this)
    }

    override fun onDestroyView() {
        binding.universeRecyclerView.adapter = null
        binding.recentRecyclerView.adapter = null
        recentAdapter = null
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
