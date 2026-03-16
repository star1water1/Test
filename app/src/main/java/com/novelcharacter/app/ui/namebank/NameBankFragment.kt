package com.novelcharacter.app.ui.namebank

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.databinding.FragmentNameBankBinding
import com.novelcharacter.app.ui.adapter.NameBankAdapter

class NameBankFragment : Fragment() {

    private var _binding: FragmentNameBankBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NameBankViewModel by viewModels()
    private lateinit var adapter: NameBankAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNameBankBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearch()
        setupFilter()
        setupFab()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = NameBankAdapter(
            onClick = { entry -> showEditDialog(entry) },
            onLongClick = { entry -> showOptionsDialog(entry) }
        )
        binding.nameBankRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.nameBankRecyclerView.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFilter() {
        binding.chipAvailableOnly.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowOnlyAvailable(isChecked)
        }
    }

    private fun setupFab() {
        binding.fabAddName.setOnClickListener {
            showEditDialog(null)
        }
    }

    private fun observeData() {
        viewModel.displayedNames.observe(viewLifecycleOwner) { names ->
            adapter.submitList(names)
            binding.emptyText.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showEditDialog(existing: NameBankEntry?) {
        val context = requireContext()
        val layout = LayoutInflater.from(context).inflate(R.layout.dialog_name_bank_edit, null)
        val editName = layout.findViewById<TextInputEditText>(R.id.editNameBank)
        val spinnerGender = layout.findViewById<Spinner>(R.id.spinnerGender)
        val editOrigin = layout.findViewById<TextInputEditText>(R.id.editOrigin)
        val editNotes = layout.findViewById<TextInputEditText>(R.id.editNotes)

        val genderOptions = listOf("미지정", "남", "여")
        spinnerGender.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, genderOptions).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        if (existing != null) {
            editName.setText(existing.name)
            val genderIndex = genderOptions.indexOf(existing.gender)
            if (genderIndex >= 0) spinnerGender.setSelection(genderIndex)
            editOrigin.setText(existing.origin)
            editNotes.setText(existing.notes)
        }

        AlertDialog.Builder(context)
            .setTitle(if (existing != null) getString(R.string.edit_name_title) else getString(R.string.add_name_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = editName.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(context, R.string.enter_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val gender = if (spinnerGender.selectedItemPosition > 0)
                    genderOptions[spinnerGender.selectedItemPosition] else ""
                val origin = editOrigin.text.toString().trim()
                val notes = editNotes.text.toString().trim()

                if (existing != null) {
                    viewModel.update(existing.copy(
                        name = name, gender = gender, origin = origin, notes = notes
                    ))
                } else {
                    viewModel.insert(NameBankEntry(
                        name = name, gender = gender, origin = origin, notes = notes
                    ))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showOptionsDialog(entry: NameBankEntry) {
        val editStr = getString(R.string.edit)
        val deleteStr = getString(R.string.delete)
        val markAvailableStr = getString(R.string.mark_as_available)
        val options = mutableListOf(editStr, deleteStr)
        if (entry.isUsed) {
            options.add(markAvailableStr)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(entry.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    editStr -> showEditDialog(entry)
                    deleteStr -> {
                        AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.confirm_delete_name, entry.name))
                            .setPositiveButton(R.string.yes) { _, _ -> viewModel.delete(entry) }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                    markAvailableStr -> viewModel.markAsAvailable(entry.id)
                }
            }
            .show()
    }

    override fun onDestroyView() {
        binding.nameBankRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
