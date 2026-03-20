package com.novelcharacter.app.ui.field

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentFieldManageBinding
import com.novelcharacter.app.ui.adapter.FieldDefinitionAdapter
import kotlinx.coroutines.launch

class FieldManageFragment : Fragment() {

    private var _binding: FragmentFieldManageBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FieldViewModel by viewModels()

    private lateinit var adapter: FieldDefinitionAdapter
    private var universeId: Long = -1L
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFieldManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        universeId = arguments?.getLong("universeId", -1L) ?: -1L
        if (universeId == -1L) {
            findNavController().popBackStack()
            return
        }

        viewModel.setUniverseId(universeId)
        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeData()
        setupFieldEditResultListener()
    }

    private fun setupFieldEditResultListener() {
        childFragmentManager.setFragmentResultListener(
            FieldEditDialog.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val json = bundle.getString(FieldEditDialog.RESULT_FIELD_JSON) ?: return@setFragmentResultListener
            val savedField = Gson().fromJson(json, FieldDefinition::class.java)
            if (savedField.id == 0L) {
                viewModel.insertField(savedField)
            } else {
                viewModel.updateField(savedField)
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.toolbar.inflateMenu(R.menu.field_manage_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_fields -> {
                    showImportFieldsDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = FieldDefinitionAdapter(
            onClick = { field ->
                showFieldEditDialog(field)
            },
            onLongClick = { field ->
                showDeleteDialog(field)
            }
        )
        binding.fieldRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.fieldRecyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val list = adapter.currentList.toMutableList()
                if (from < 0 || to < 0 || from >= list.size || to >= list.size) return false
                val item = list.removeAt(from)
                list.add(to, item)
                adapter.submitList(list)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewModel.updateFieldOrder(adapter.currentList)
            }
        })
        itemTouchHelper?.attachToRecyclerView(binding.fieldRecyclerView)
    }

    private fun setupFab() {
        binding.fabAddField.setOnClickListener {
            showFieldEditDialog(null)
        }
    }

    private fun observeData() {
        viewModel.fields.observe(viewLifecycleOwner) { fields ->
            adapter.submitList(fields)
            val isEmpty = fields.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.fieldRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun showFieldEditDialog(field: FieldDefinition?) {
        val dialog = FieldEditDialog.newInstance(universeId, field)
        dialog.setOnSaveListener { savedField ->
            if (field == null) {
                viewModel.insertField(savedField)
            } else {
                viewModel.updateField(savedField)
            }
        }
        dialog.show(childFragmentManager, "FieldEditDialog")
    }

    private fun showDeleteDialog(field: FieldDefinition) {
        AlertDialog.Builder(requireContext())
            .setTitle(field.name)
            .setMessage("[${field.groupName}] ${field.type}")
            .setPositiveButton(R.string.edit) { _, _ ->
                showFieldEditDialog(field)
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                viewModel.deleteField(field)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    /** 다른 세계관에서 필드 가져오기 (단일 다이얼로그) */
    private fun showImportFieldsDialog() {
        lifecycleScope.launch {
            val otherFields = viewModel.getFieldsFromOtherUniverses(universeId)
            if (otherFields.isEmpty()) {
                Toast.makeText(requireContext(), R.string.import_no_other_universes, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val ctx = requireContext()
            val universes = otherFields.keys.toList()
            val density = resources.displayMetrics.density

            // 현재 선택된 세계관의 필드와 체크 상태
            var currentFields = otherFields[universes[0]] ?: emptyList()
            var currentChecked = BooleanArray(currentFields.size) { true }
            var currentFieldNames = currentFields.map { "[${it.groupName}] ${it.name} (${it.type})" }.toTypedArray()

            // 컨테이너: Spinner + MultiChoice를 단일 다이얼로그에 배치
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
            }

            val universeSpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                    universes.map { "${it.name} (${otherFields[it]?.size ?: 0}개 필드)" }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            }
            container.addView(universeSpinner)

            val dialog = AlertDialog.Builder(ctx)
                .setTitle(R.string.import_select_universe)
                .setView(container)
                .setMultiChoiceItems(currentFieldNames, currentChecked) { _, which, isChecked ->
                    currentChecked[which] = isChecked
                }
                .setPositiveButton(R.string.import_action) { _, _ ->
                    val selected = currentFields.filterIndexed { i, _ -> currentChecked[i] }
                    if (selected.isNotEmpty()) {
                        viewModel.importFields(universeId, selected)
                        Toast.makeText(ctx,
                            getString(R.string.import_success, selected.size),
                            Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            // 세계관 변경 시 필드 목록 갱신
            universeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentFields = otherFields[universes[position]] ?: emptyList()
                    currentChecked = BooleanArray(currentFields.size) { true }
                    currentFieldNames = currentFields.map { "[${it.groupName}] ${it.name} (${it.type})" }.toTypedArray()
                    val listView = dialog.listView
                    listView?.adapter = ArrayAdapter(ctx,
                        android.R.layout.select_dialog_multichoice, currentFieldNames)
                    for (i in currentChecked.indices) {
                        listView?.setItemChecked(i, true)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            dialog.show()
        }
    }

    override fun onDestroyView() {
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        binding.fieldRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
