package com.novelcharacter.app.ui.field

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.databinding.FragmentFieldManageBinding
import com.novelcharacter.app.ui.adapter.FieldDefinitionAdapter

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

    /**
     * Handles field save result after configuration change (rotation).
     * When the dialog is recreated by FragmentManager, the callback listener is lost,
     * so FragmentResult is the fallback mechanism.
     */
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

        // 드래그로 순서 변경
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
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> showFieldEditDialog(field)
                    1 -> {
                        AlertDialog.Builder(requireContext())
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle(R.string.delete_warning_title)
                            .setMessage(getString(R.string.confirm_delete_field, field.name))
                            .setPositiveButton(R.string.yes) { _, _ ->
                                viewModel.deleteField(field)
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        binding.fieldRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
