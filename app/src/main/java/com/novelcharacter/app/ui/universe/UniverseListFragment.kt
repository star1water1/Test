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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val pendingImagePaths = mutableListOf<String>()
    private var universeImageRecyclerView: RecyclerView? = null
    private var universeImageAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            for (uri in uris) {
                try {
                    val savedPath = withContext(Dispatchers.IO) {
                        val inputStream = ctx.contentResolver.openInputStream(uri) ?: return@withContext null
                        val maxSize = 20L * 1024 * 1024 // 20MB
                        val fileName = "universe_${java.util.UUID.randomUUID()}.jpg"
                        val file = java.io.File(ctx.filesDir, fileName)
                        var totalBytes = 0L
                        var exceededLimit = false
                        inputStream.use { input ->
                            file.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    totalBytes += bytesRead
                                    if (totalBytes > maxSize) { exceededLimit = true; break }
                                    out.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                        if (exceededLimit) { file.delete(); return@withContext null }
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
                universeImageAdapter?.notifyDataSetChanged()
                if (pendingImagePaths.isNotEmpty()) {
                    universeImageRecyclerView?.visibility = View.VISIBLE
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
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onClick = { universe ->
                viewModel.recordRecentActivity(RecentActivity.TYPE_UNIVERSE, universe.id, universe.name)
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.homeFragment, R.id.novelListFragment, bundle)
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
                findNavController().navigateSafe(R.id.homeFragment, R.id.fieldManageFragment, bundle)
            },
            onFactionManageClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.homeFragment, R.id.factionManageFragment, bundle)
            }
        )
        binding.universeRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.universeRecyclerView.adapter = adapter

        adapter.onOrderChanged = { reorderedList ->
            viewModel.updateDisplayOrders(reorderedList)
        }
        adapter.resolveRandomCharacterImage = { universeId, callback ->
            viewModel.resolveRandomCharacterImage(universeId, callback)
        }
        adapter.resolveCharacterImageById = { characterId, callback ->
            viewModel.resolveCharacterImageById(characterId, callback)
        }
        adapter.resolveRandomNovelImage = { universeId, callback ->
            viewModel.resolveRandomNovelImage(universeId, callback)
        }
        adapter.resolveNovelImageById = { novelId, callback ->
            viewModel.resolveNovelImageById(novelId, callback)
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
                else -> false
            }
        }
    }

    /** 최신 사용자 프리셋 데이터 — observe 패턴 */
    private var cachedUserPresets: List<com.novelcharacter.app.data.model.UserPresetTemplate> = emptyList()

    private fun showPresetDialog() {
        // 빌트인 프리셋도 이제 DB에서 로드 — 모든 프리셋을 동일하게 처리
        val userPresetList = cachedUserPresets
        val allTemplates = userPresetList.map { PresetTemplates.fromUserPreset(it) }

        // 각 템플릿에 원본 UserPresetTemplate을 직접 매핑
        val presetByTemplateId = userPresetList.associateBy { it.id }

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_preset, null)
        bottomSheet.setContentView(sheetView)

        val recyclerView = sheetView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.presetRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        recyclerView.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class VH(val view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_preset_template, parent, false)
                return VH(v)
            }

            override fun getItemCount() = allTemplates.size

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val vh = holder as VH
                val t = allTemplates[position]
                val tag = vh.view.findViewById<TextView>(R.id.presetTag)
                val name = vh.view.findViewById<TextView>(R.id.presetName)
                val desc = vh.view.findViewById<TextView>(R.id.presetDescription)

                if (t.isBuiltIn) {
                    tag.text = getString(R.string.preset_tag_builtin)
                    tag.setBackgroundResource(R.drawable.bg_preset_tag_builtin)
                } else {
                    tag.text = getString(R.string.preset_tag_user)
                    tag.setBackgroundResource(R.drawable.bg_preset_tag_user)
                }
                name.text = t.universe.name
                desc.text = t.universe.description

                // 클릭 → 즉시 적용 (모든 프리셋 동일)
                vh.view.setOnClickListener {
                    bottomSheet.dismiss()
                    viewModel.applyPreset(t)
                }
                // 롱프레스 → 옵션 다이얼로그 (편집/삭제)
                vh.view.setOnLongClickListener {
                    val preset = t.userPresetId?.let { id -> presetByTemplateId[id] }
                    if (preset != null) {
                        bottomSheet.dismiss()
                        showUserPresetOptionsDialog(t, preset)
                    }
                    true
                }
            }
        }

        sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveAsPreset)
            .setOnClickListener {
                bottomSheet.dismiss()
                showSaveAsPresetDialog()
            }

        sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRestoreBuiltIn)?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                bottomSheet.dismiss()
                viewModel.restoreBuiltInPresets()
                Toast.makeText(requireContext(), R.string.preset_restored, Toast.LENGTH_SHORT).show()
            }
        }

        bottomSheet.show()
    }

    private fun showUserPresetOptionsDialog(template: PresetTemplates.PresetTemplate, preset: com.novelcharacter.app.data.model.UserPresetTemplate) {
        val options = arrayOf(
            getString(R.string.preset_edit_name),
            getString(R.string.preset_edit_fields),
            getString(R.string.delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(preset.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditPresetNameDialog(preset)
                    1 -> showPresetFieldEditDialog(preset)
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

    private fun showPresetFieldEditDialog(preset: com.novelcharacter.app.data.model.UserPresetTemplate) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val dp16 = (16 * density).toInt()
        val dp8 = (8 * density).toInt()
        val dp4 = (4 * density).toInt()

        // Parse existing fields from preset JSON
        val fields = PresetTemplates.fieldsFromJson(preset.fieldsJson).toMutableList()

        // Build field list container
        val fieldListContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Helper to rebuild the field list UI
        fun rebuildFieldList() {
            fieldListContainer.removeAllViews()
            fields.forEachIndexed { index, field ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp4 }
                    setPadding(dp8, dp8, dp8, dp8)
                    setBackgroundResource(android.R.attr.selectableItemBackground.let {
                        val typedValue = android.util.TypedValue()
                        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                        typedValue.resourceId
                    })
                }

                // Move up button
                if (index > 0) {
                    val btnUp = android.widget.ImageButton(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                        setImageResource(android.R.drawable.arrow_up_float)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = getString(R.string.move_up)
                        setOnClickListener {
                            val temp = fields[index]
                            fields[index] = fields[index - 1]
                            fields[index - 1] = temp
                            rebuildFieldList()
                        }
                    }
                    row.addView(btnUp)
                } else {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                    })
                }

                // Move down button
                if (index < fields.size - 1) {
                    val btnDown = android.widget.ImageButton(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                        setImageResource(android.R.drawable.arrow_down_float)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = getString(R.string.move_down)
                        setOnClickListener {
                            val temp = fields[index]
                            fields[index] = fields[index + 1]
                            fields[index + 1] = temp
                            rebuildFieldList()
                        }
                    }
                    row.addView(btnDown)
                } else {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                    })
                }

                // Field info (name, type, group)
                val infoLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp8
                    }
                }
                val nameText = TextView(ctx).apply {
                    text = field.name
                    textSize = 15f
                    setTextColor(android.graphics.Color.parseColor("#DD000000"))
                }
                val detailText = TextView(ctx).apply {
                    text = "${field.type} · ${field.groupName} · ${field.key}"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#88000000"))
                }
                infoLayout.addView(nameText)
                infoLayout.addView(detailText)
                row.addView(infoLayout)

                // Edit button
                val btnEdit = android.widget.ImageButton(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
                    setImageResource(android.R.drawable.ic_menu_edit)
                    setBackgroundResource(android.R.color.transparent)
                    contentDescription = getString(R.string.edit)
                    setOnClickListener {
                        val dialog = com.novelcharacter.app.ui.field.FieldEditDialog.newInstance(0, field)
                        dialog.setOnSaveListener { editedField ->
                            val dupIdx = fields.indexOfFirst { it.key == editedField.key && it !== field }
                            if (dupIdx >= 0) {
                                Toast.makeText(ctx, R.string.preset_field_key_duplicate, Toast.LENGTH_SHORT).show()
                                return@setOnSaveListener
                            }
                            fields[index] = editedField
                            rebuildFieldList()
                        }
                        dialog.show(childFragmentManager, "edit_preset_field")
                    }
                }
                row.addView(btnEdit)

                // Delete button
                val btnDelete = android.widget.ImageButton(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
                    setImageResource(android.R.drawable.ic_delete)
                    setBackgroundResource(android.R.color.transparent)
                    contentDescription = getString(R.string.delete)
                    setOnClickListener {
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.delete_warning_title)
                            .setMessage(getString(R.string.preset_field_delete_confirm, field.name))
                            .setPositiveButton(R.string.yes) { _, _ ->
                                fields.removeAt(index)
                                rebuildFieldList()
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                }
                row.addView(btnDelete)

                fieldListContainer.addView(row)
            }
        }

        rebuildFieldList()

        // Add field button
        val btnAddField = com.google.android.material.button.MaterialButton(ctx).apply {
            text = getString(R.string.preset_field_add)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp8 }
            setOnClickListener {
                val dialog = com.novelcharacter.app.ui.field.FieldEditDialog.newInstance(0, null)
                dialog.setOnSaveListener { newField ->
                    if (fields.any { it.key == newField.key }) {
                        Toast.makeText(ctx, R.string.preset_field_key_duplicate, Toast.LENGTH_SHORT).show()
                        return@setOnSaveListener
                    }
                    fields.add(newField)
                    rebuildFieldList()
                }
                dialog.show(childFragmentManager, "add_preset_field")
            }
        }

        // Root layout
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp8)
            addView(fieldListContainer)
            addView(btnAddField)
        }

        val scrollView = android.widget.ScrollView(ctx).apply {
            addView(rootLayout)
            isFillViewport = true
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.preset_edit_fields) + " - " + preset.name)
            .setView(scrollView)
            .setPositiveButton(R.string.save) { _, _ ->
                // Update displayOrder and save
                val updatedFields = fields.mapIndexed { i, f -> f.copy(displayOrder = i) }
                val newJson = PresetTemplates.fieldsToJson(updatedFields)
                viewModel.updateUserPreset(preset.copy(fieldsJson = newJson))
                Toast.makeText(ctx, R.string.preset_fields_saved, Toast.LENGTH_SHORT).show()
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
            adapter.refreshRandomImages(context)
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

        // 사용자 프리셋 캐시 (observe 패턴)
        viewModel.userPresets.observe(viewLifecycleOwner) { presets ->
            cachedUserPresets = presets ?: emptyList()
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
                findNavController().navigateSafe(R.id.homeFragment, R.id.novelListFragment, bundle)
            }
            RecentActivity.TYPE_NOVEL -> {
                val bundle = Bundle().apply { putLong("novelId", item.entityId) }
                findNavController().navigateSafe(R.id.homeFragment, R.id.characterListFragment, bundle)
            }
            RecentActivity.TYPE_CHARACTER -> {
                val bundle = Bundle().apply { putLong("characterId", item.entityId) }
                findNavController().navigateSafe(R.id.homeFragment, R.id.characterDetailFragment, bundle)
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
                    try { setColor(Color.parseColor(selectedColor)) } catch (e: Exception) { android.util.Log.w("UniverseList", "Invalid color: $selectedColor", e); setColor(Color.LTGRAY) }
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
                    } catch (e: Exception) {
                        android.util.Log.w("UniverseList", "Invalid HEX color input: $hex", e)
                    }
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
                    try { setColor(Color.parseColor(preset)) } catch (e: Exception) { android.util.Log.w("UniverseList", "Invalid preset color: $preset", e) }
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

        // Full spectrum color picker button
        val fullSpectrumBtn = TextView(ctx).apply {
            text = getString(R.string.color_picker_full_spectrum)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                com.novelcharacter.app.util.ColorPickerHelper.showFullSpectrumColorPicker(ctx, selectedColor) { newColor ->
                    selectedColor = newColor
                    colorHexEdit.setText(newColor)
                    (colorPreview.background as? GradientDrawable)?.setColor(Color.parseColor(newColor))
                }
            }
        }
        layout.addView(fullSpectrumBtn)
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

        // 관계 유형 편집 섹션
        val relTypeLabel = TextView(ctx).apply {
            text = getString(R.string.relationship_types_label)
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        }
        layout.addView(relTypeLabel)

        val currentTypes = (universe?.getRelationshipTypes() ?: Universe.DEFAULT_RELATIONSHIP_TYPES).toMutableList()
        val currentColors = (universe?.getRelationshipColorMap() ?: Universe.DEFAULT_RELATIONSHIP_COLORS).toMutableMap()

        val relTypeChipsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (4 * dp).toInt())
        }

        fun showColorPickerForType(typeName: String, onColorSelected: (String) -> Unit) {
            val presetColors = arrayOf("#E91E63", "#F44336", "#FF5722", "#FF9800", "#FFC107",
                "#4CAF50", "#2196F3", "#3F51B5", "#9C27B0", "#00BCD4", "#795548", "#607D8B", "#212121", "#9E9E9E")
            val colorNames = arrayOf("핑크", "빨강", "주황빨강", "주황", "노랑",
                "초록", "파랑", "남색", "보라", "청록", "갈색", "회남색", "검정", "회색")
            val options = colorNames.toMutableList()
            options.add(getString(R.string.color_picker_full_spectrum))

            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.relationship_color_pick_title, typeName))
                .setItems(options.toTypedArray()) { _, which ->
                    if (which < presetColors.size) {
                        onColorSelected(presetColors[which])
                    } else {
                        val currentColor = currentColors[typeName] ?: "#9E9E9E"
                        com.novelcharacter.app.util.ColorPickerHelper.showFullSpectrumColorPicker(ctx, currentColor, onColorSelected)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        fun refreshRelTypeChips() {
            relTypeChipsContainer.removeAllViews()
            currentTypes.forEach { typeName ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                }
                // 색상 원 (클릭 시 색상 피커)
                val colorCircle = View(ctx).apply {
                    val size = (20 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (8 * dp).toInt()
                    }
                    val colorHex = currentColors[typeName] ?: "#9E9E9E"
                    val drawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(colorHex))
                    }
                    background = drawable
                    setOnClickListener {
                        showColorPickerForType(typeName) { newColor ->
                            currentColors[typeName] = newColor
                            (background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(newColor))
                        }
                    }
                }
                row.addView(colorCircle)
                row.addView(TextView(ctx).apply {
                    text = typeName
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(ctx).apply {
                    text = "✕"
                    setTextColor(ctx.getColor(R.color.primary))
                    setPadding((8 * dp).toInt(), 0, 0, 0)
                    setOnClickListener {
                        currentTypes.remove(typeName)
                        currentColors.remove(typeName)
                        refreshRelTypeChips()
                    }
                })
                relTypeChipsContainer.addView(row)
            }
        }
        refreshRelTypeChips()
        layout.addView(relTypeChipsContainer)

        // 새 유형 추가 행
        val addRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        val newTypeEdit = EditText(ctx).apply {
            hint = getString(R.string.relationship_types_hint)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = TextView(ctx).apply {
            text = getString(R.string.relationship_types_add)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                val newType = newTypeEdit.text.toString().trim()
                if (newType.isNotEmpty() && newType !in currentTypes) {
                    currentTypes.add(newType)
                    refreshRelTypeChips()
                    newTypeEdit.text.clear()
                }
            }
        }
        addRow.addView(newTypeEdit)
        addRow.addView(addBtn)
        layout.addView(addRow)

        val relResetBtn = TextView(ctx).apply {
            text = getString(R.string.relationship_types_reset)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                currentTypes.clear()
                currentTypes.addAll(Universe.DEFAULT_RELATIONSHIP_TYPES)
                refreshRelTypeChips()
            }
        }
        layout.addView(relResetBtn)

        // 이미지 모드 선택
        val imageLabel = TextView(ctx).apply {
            text = getString(R.string.image_mode_label)
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        }
        layout.addView(imageLabel)

        val imageModes = arrayOf(
            getString(R.string.image_mode_none),
            getString(R.string.image_mode_custom),
            getString(R.string.image_mode_random_character),
            getString(R.string.image_mode_select_character),
            getString(R.string.image_mode_random_novel),
            getString(R.string.image_mode_select_novel)
        )
        val imageModeValues = arrayOf(Universe.IMAGE_MODE_NONE, Universe.IMAGE_MODE_CUSTOM, Universe.IMAGE_MODE_RANDOM_CHARACTER, Universe.IMAGE_MODE_SELECT_CHARACTER, Universe.IMAGE_MODE_RANDOM_NOVEL, Universe.IMAGE_MODE_SELECT_NOVEL)
        var selectedImageMode = universe?.imageMode ?: Universe.IMAGE_MODE_NONE

        // 기존 이미지 경로 목록 로드
        pendingImagePaths.clear()
        pendingImagePaths.addAll(parseImagePaths(universe?.imagePaths ?: "[]"))

        val imageModeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, imageModes)
            setSelection(imageModeValues.indexOf(selectedImageMode).coerceAtLeast(0))
        }
        layout.addView(imageModeSpinner)

        var selectedCharacterId: Long? = universe?.imageCharacterId

        val imageSelectBtn = TextView(ctx).apply {
            text = if (pendingImagePaths.isNotEmpty()) getString(R.string.image_change) else getString(R.string.image_select)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_CUSTOM) View.VISIBLE else View.GONE
            setOnClickListener {
                imagePickerLauncher.launch("image/*")
            }
        }
        layout.addView(imageSelectBtn)

        // 이미지 목록 RecyclerView
        val imageRv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_CUSTOM && pendingImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
        }
        universeImageRecyclerView = imageRv
        setupUniverseImageRecyclerView(imageRv)
        layout.addView(imageRv)

        // 캐릭터 선택 버튼 (select_character 모드 전용)
        val charSelectBtn = TextView(ctx).apply {
            text = getString(R.string.image_select_character)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_CHARACTER) View.VISIBLE else View.GONE
        }
        layout.addView(charSelectBtn)

        // 기존 세계관 편집 시 선택된 캐릭터 이름 표시
        if (universe != null && selectedCharacterId != null) {
            viewModel.getCharactersWithImageForUniverse(universe.id) { chars ->
                if (!isAdded) return@getCharactersWithImageForUniverse
                val match = chars.firstOrNull { it.first == selectedCharacterId }
                if (match != null) {
                    charSelectBtn.text = getString(R.string.image_selected_character, match.second)
                }
            }
        }

        charSelectBtn.setOnClickListener {
            val uid = universe?.id
            if (uid == null) {
                Toast.makeText(ctx, R.string.save_universe_first_for_image, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.getCharactersWithImageForUniverse(uid) { chars ->
                if (!isAdded) return@getCharactersWithImageForUniverse
                if (chars.isEmpty()) {
                    Toast.makeText(ctx, R.string.no_character_with_image, Toast.LENGTH_SHORT).show()
                    return@getCharactersWithImageForUniverse
                }
                val names = chars.map { it.second }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.image_select_character)
                    .setItems(names) { _, which ->
                        selectedCharacterId = chars[which].first
                        charSelectBtn.text = getString(R.string.image_selected_character, chars[which].second)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        // 작품 선택 버튼 (select_novel 모드 전용)
        var selectedNovelId: Long? = universe?.imageNovelId
        val novelSelectBtn = TextView(ctx).apply {
            text = getString(R.string.image_select_novel)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_NOVEL) View.VISIBLE else View.GONE
        }
        layout.addView(novelSelectBtn)

        // 기존 세계관 편집 시 선택된 작품 이름 표시
        if (universe != null && selectedNovelId != null) {
            viewModel.getNovelsWithImageForUniverse(universe.id) { novels ->
                if (!isAdded) return@getNovelsWithImageForUniverse
                val match = novels.firstOrNull { it.first == selectedNovelId }
                if (match != null) {
                    novelSelectBtn.text = getString(R.string.image_selected_novel, match.second)
                }
            }
        }

        novelSelectBtn.setOnClickListener {
            val uid = universe?.id
            if (uid == null) {
                Toast.makeText(ctx, R.string.save_universe_first_for_image, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.getNovelsWithImageForUniverse(uid) { novels ->
                if (!isAdded) return@getNovelsWithImageForUniverse
                if (novels.isEmpty()) {
                    Toast.makeText(ctx, R.string.no_novel_with_image, Toast.LENGTH_SHORT).show()
                    return@getNovelsWithImageForUniverse
                }
                val names = novels.map { it.second }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.image_select_novel)
                    .setItems(names) { _, which ->
                        selectedNovelId = novels[which].first
                        novelSelectBtn.text = getString(R.string.image_selected_novel, novels[which].second)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        imageModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedImageMode = imageModeValues[pos]
                val custom = selectedImageMode == Universe.IMAGE_MODE_CUSTOM
                imageSelectBtn.visibility = if (custom) View.VISIBLE else View.GONE
                imageRv.visibility = if (custom && pendingImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
                charSelectBtn.visibility = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_CHARACTER) View.VISIBLE else View.GONE
                novelSelectBtn.visibility = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_NOVEL) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ScrollView로 래핑하여 긴 다이얼로그 스크롤 가능하게
        val scrollView = android.widget.ScrollView(ctx).apply {
            addView(layout)
            isFillViewport = true
        }

        AlertDialog.Builder(ctx)
            .setTitle(if (universe == null) R.string.add_universe else R.string.edit_universe)
            .setView(scrollView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val desc = descEdit.text.toString().trim()
                val borderColor = colorHexEdit.text.toString().trim()
                val finalImagePaths = org.json.JSONArray(pendingImagePaths).toString()
                // 관계 유형을 JSON 배열로 직렬화 (기본값과 동일하면 빈 문자열 저장)
                val relTypesJson = if (currentTypes == Universe.DEFAULT_RELATIONSHIP_TYPES) ""
                    else org.json.JSONArray(currentTypes).toString()
                // 기본 색상과 동일하면 빈 문자열, 아니면 JSON 저장
                val relColorsJson = run {
                    val customOnly = currentColors.filter { (k, v) ->
                        Universe.DEFAULT_RELATIONSHIP_COLORS[k] != v
                    }
                    if (customOnly.isEmpty()) "" else org.json.JSONObject(customOnly as Map<*, *>).toString()
                }
                if (name.isNotEmpty()) {
                    val finalCharId = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_CHARACTER) selectedCharacterId else null
                    val finalNovelId = if (selectedImageMode == Universe.IMAGE_MODE_SELECT_NOVEL) selectedNovelId else null
                    if (universe == null) {
                        viewModel.insertUniverse(Universe(
                            name = name, description = desc, borderColor = borderColor,
                            imagePaths = finalImagePaths, imageMode = selectedImageMode,
                            imageCharacterId = finalCharId,
                            imageNovelId = finalNovelId,
                            customRelationshipTypes = relTypesJson,
                            customRelationshipColors = relColorsJson
                        ))
                    } else {
                        viewModel.updateUniverse(universe.copy(
                            name = name, description = desc, borderColor = borderColor,
                            imagePaths = finalImagePaths, imageMode = selectedImageMode,
                            imageCharacterId = finalCharId,
                            imageNovelId = finalNovelId,
                            customRelationshipTypes = relTypesJson,
                            customRelationshipColors = relColorsJson
                        ))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * HSV 풀 스펙트럼 색상 선택 다이얼로그.
     * SeekBar 3개(Hue, Saturation, Value)와 미리보기 + HEX 입력을 제공한다.
     */
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
        importer.showImportDialog(this)
    }

    private fun setupUniverseImageRecyclerView(recyclerView: RecyclerView) {
        universeImageAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
                                    universeImageAdapter?.notifyItemRemoved(currentPos)
                                    universeImageAdapter?.notifyItemRangeChanged(currentPos, pendingImagePaths.size - currentPos)
                                    if (pendingImagePaths.isEmpty()) {
                                        universeImageRecyclerView?.visibility = View.GONE
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
        recyclerView.adapter = universeImageAdapter
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
