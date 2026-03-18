package com.novelcharacter.app.ui.field

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.databinding.DialogFieldEditBinding

class FieldEditDialog : DialogFragment() {

    private var onSave: ((FieldDefinition) -> Unit)? = null
    private var universeId: Long = 0
    private var existingField: FieldDefinition? = null

    fun setOnSaveListener(listener: (FieldDefinition) -> Unit) {
        onSave = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogFieldEditBinding.inflate(layoutInflater)

        universeId = arguments?.getLong(ARG_UNIVERSE_ID) ?: 0
        val fieldJson = arguments?.getString(ARG_FIELD_JSON)
        existingField = if (fieldJson != null) Gson().fromJson(fieldJson, FieldDefinition::class.java) else null

        setupTypeSpinner(binding)
        populateFields(binding)

        return AlertDialog.Builder(requireContext())
            .setTitle(if (existingField == null) R.string.add_field else R.string.edit_field)
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                saveField(binding)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    private fun setupTypeSpinner(binding: DialogFieldEditBinding) {
        val types = FieldType.entries.toTypedArray()
        val labels = types.map { it.label }
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFieldType.adapter = spinnerAdapter

        binding.spinnerFieldType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedType = types[position]
                binding.selectOptionsLayout.visibility =
                    if (selectedType == FieldType.SELECT) View.VISIBLE else View.GONE
                binding.gradeLayout.visibility =
                    if (selectedType == FieldType.GRADE) View.VISIBLE else View.GONE
                binding.calculatedLayout.visibility =
                    if (selectedType == FieldType.CALCULATED) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun populateFields(binding: DialogFieldEditBinding) {
        val field = existingField ?: return

        binding.editFieldName.setText(field.name)
        binding.editFieldKey.setText(field.key)
        binding.editGroupName.setText(field.groupName)
        binding.switchRequired.isChecked = field.isRequired

        // Set type spinner
        val types = FieldType.entries.toTypedArray()
        val typeIndex = types.indexOfFirst { it.name == field.type }
        if (typeIndex >= 0) binding.spinnerFieldType.setSelection(typeIndex)

        // Parse config
        val config = try {
            Gson().fromJson<Map<String, Any>>(field.config, Map::class.java) ?: emptyMap()
        } catch (e: Exception) { emptyMap<String, Any>() }

        // SELECT options
        val options = (config["options"] as? List<*>)?.joinToString(",")
        if (options != null) binding.editSelectOptions.setText(options)

        // GRADE mappings
        val grades = config["grades"] as? Map<*, *>
        if (grades != null) {
            binding.editGradeC.setText((grades["C"] as? Number)?.toString() ?: "0.5")
            binding.editGradeB.setText((grades["B"] as? Number)?.toString() ?: "1")
            binding.editGradeA.setText((grades["A"] as? Number)?.toString() ?: "2")
            binding.editGradeS.setText((grades["S"] as? Number)?.toString() ?: "3")
        }
        val allowNeg = config["allowNegative"] as? Boolean ?: false
        binding.switchAllowNegative.isChecked = allowNeg

        // CALCULATED formula
        val formula = config["formula"] as? String
        if (formula != null) binding.editFormula.setText(formula)
    }

    private fun saveField(binding: DialogFieldEditBinding) {
        val name = binding.editFieldName.text.toString().trim()
        val key = binding.editFieldKey.text.toString().trim()
        val groupName = binding.editGroupName.text.toString().trim().ifEmpty { getString(R.string.default_group_name) }
        val isRequired = binding.switchRequired.isChecked

        if (name.isEmpty() || key.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.field_name_key_required), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val types = FieldType.entries.toTypedArray()
        val selectedType = types[binding.spinnerFieldType.selectedItemPosition]

        val config = buildConfig(binding, selectedType)

        val field = if (existingField != null) {
            existingField!!.copy(
                name = name,
                key = key,
                type = selectedType.name,
                config = config,
                groupName = groupName,
                isRequired = isRequired
            )
        } else {
            FieldDefinition(
                universeId = universeId,
                name = name,
                key = key,
                type = selectedType.name,
                config = config,
                groupName = groupName,
                isRequired = isRequired
            )
        }

        // Support both callback (for non-rotation case) and FragmentResult (survives rotation)
        if (onSave != null) {
            onSave?.invoke(field)
        } else {
            setFragmentResult(RESULT_KEY, bundleOf(RESULT_FIELD_JSON to Gson().toJson(field)))
        }
    }

    private fun buildConfig(binding: DialogFieldEditBinding, type: FieldType): String {
        val config = mutableMapOf<String, Any>()

        when (type) {
            FieldType.SELECT -> {
                val optionsText = binding.editSelectOptions.text.toString().trim()
                if (optionsText.isNotEmpty()) {
                    config["options"] = optionsText.split(",").map { it.trim() }
                }
            }
            FieldType.GRADE -> {
                val grades = mapOf(
                    "C" to (binding.editGradeC.text.toString().toDoubleOrNull() ?: 0.5),
                    "B" to (binding.editGradeB.text.toString().toDoubleOrNull() ?: 1.0),
                    "A" to (binding.editGradeA.text.toString().toDoubleOrNull() ?: 2.0),
                    "S" to (binding.editGradeS.text.toString().toDoubleOrNull() ?: 3.0)
                )
                config["grades"] = grades
                config["allowNegative"] = binding.switchAllowNegative.isChecked
            }
            FieldType.CALCULATED -> {
                val formula = binding.editFormula.text.toString().trim()
                if (formula.isNotEmpty()) {
                    config["formula"] = formula
                }
            }
            FieldType.BODY_SIZE -> {
                config["separator"] = "-"
                config["inputReplace"] = mapOf(" " to "-")
            }
            else -> {}
        }

        return Gson().toJson(config)
    }

    companion object {
        const val RESULT_KEY = "field_edit_result"
        const val RESULT_FIELD_JSON = "field_json"
        private const val ARG_UNIVERSE_ID = "universeId"
        private const val ARG_FIELD_JSON = "fieldJson"

        fun newInstance(universeId: Long, field: FieldDefinition?): FieldEditDialog {
            return FieldEditDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_UNIVERSE_ID, universeId)
                    if (field != null) {
                        putString(ARG_FIELD_JSON, Gson().toJson(field))
                    }
                }
            }
        }
    }
}
