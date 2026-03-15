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
            .setTitle(if (existing != null) "이름 편집" else "이름 추가")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val name = editName.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(context, "이름을 입력하세요", Toast.LENGTH_SHORT).show()
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
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showOptionsDialog(entry: NameBankEntry) {
        val options = mutableListOf("편집", "삭제")
        if (entry.isUsed) {
            options.add("미사용으로 변경")
        }

        AlertDialog.Builder(requireContext())
            .setTitle(entry.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "편집" -> showEditDialog(entry)
                    "삭제" -> {
                        AlertDialog.Builder(requireContext())
                            .setMessage("\"${entry.name}\"을(를) 삭제하시겠습니까?")
                            .setPositiveButton("예") { _, _ -> viewModel.delete(entry) }
                            .setNegativeButton("아니오", null)
                            .show()
                    }
                    "미사용으로 변경" -> viewModel.markAsAvailable(entry.id)
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
