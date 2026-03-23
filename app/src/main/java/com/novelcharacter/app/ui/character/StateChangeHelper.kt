package com.novelcharacter.app.ui.character

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.databinding.DialogStateChangeBinding
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.ui.adapter.StateChangeAdapter

class StateChangeHelper(
    private val binding: FragmentCharacterDetailBinding,
    private val viewModel: CharacterViewModel,
    private val viewLifecycleOwner: LifecycleOwner,
    private val characterId: Long,
    private val contextGetter: () -> Context,
    private val getString: (Int) -> String,
    private val cachedFieldsGetter: () -> List<FieldDefinition>,
    private val onSliderUpdate: () -> Unit
) {
    private lateinit var stateChangeAdapter: StateChangeAdapter

    fun setup() {
        stateChangeAdapter = StateChangeAdapter(
            onClick = { change -> showEditDeleteDialog(change) },
            onLongClick = { change -> showEditDeleteDialog(change) }
        )
        binding.stateChangesRecyclerView.layoutManager = LinearLayoutManager(contextGetter())
        binding.stateChangesRecyclerView.adapter = stateChangeAdapter

        binding.btnAddStateChange.setOnClickListener {
            showStateChangeDialog(null)
        }
    }

    fun observe() {
        viewModel.getChangesByCharacter(characterId).observe(viewLifecycleOwner) { changes ->
            val sortedChanges = changes.sortedWith(
                compareBy({ it.year }, { it.month ?: 0 }, { it.day ?: 0 })
            )
            stateChangeAdapter.submitList(sortedChanges)

            if (sortedChanges.isEmpty()) {
                binding.textNoStateChanges.visibility = View.VISIBLE
                binding.stateChangesRecyclerView.visibility = View.GONE
            } else {
                binding.textNoStateChanges.visibility = View.GONE
                binding.stateChangesRecyclerView.visibility = View.VISIBLE
            }

            onSliderUpdate()
        }
    }

    private fun showStateChangeDialog(existingChange: CharacterStateChange?) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        val dialogBinding = DialogStateChangeBinding.inflate(LayoutInflater.from(context))

        val cachedFields = cachedFieldsGetter()
        val fieldOptions = mutableListOf<Pair<String, String>>()
        fieldOptions.add(CharacterStateChange.KEY_BIRTH to getString(R.string.birth))
        fieldOptions.add(CharacterStateChange.KEY_DEATH to getString(R.string.death))
        fieldOptions.add(CharacterStateChange.KEY_ALIVE to getString(R.string.alive_status))

        for (field in cachedFields) {
            fieldOptions.add(field.key to field.name)
        }

        val displayNames = fieldOptions.map { it.second }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerFieldKey.adapter = adapter

        if (existingChange != null) {
            dialogBinding.editYear.setText(existingChange.year.toString())
            existingChange.month?.let { dialogBinding.editMonth.setText(it.toString()) }
            existingChange.day?.let { dialogBinding.editDay.setText(it.toString()) }
            dialogBinding.editNewValue.setText(existingChange.newValue)
            dialogBinding.editDescription.setText(existingChange.description)

            val index = fieldOptions.indexOfFirst { it.first == existingChange.fieldKey }
            if (index >= 0) {
                dialogBinding.spinnerFieldKey.setSelection(index)
            }
        }

        val title = if (existingChange != null) getString(R.string.edit_state_change)
        else getString(R.string.add_state_change)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val yearStr = dialogBinding.editYear.text.toString().trim()
                if (yearStr.isEmpty()) {
                    Toast.makeText(context, getString(R.string.year_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val year = yearStr.toIntOrNull()
                if (year == null) {
                    Toast.makeText(context, getString(R.string.year_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val month = dialogBinding.editMonth.text.toString().trim().toIntOrNull()
                if (month != null && month !in 1..12) {
                    Toast.makeText(context, getString(R.string.month_valid_range), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val day = dialogBinding.editDay.text.toString().trim().toIntOrNull()
                if (day != null && !com.novelcharacter.app.util.isValidDay(month, day)) {
                    Toast.makeText(context, getString(R.string.day_valid_range), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val selectedIndex = dialogBinding.spinnerFieldKey.selectedItemPosition
                if (selectedIndex < 0 || selectedIndex >= fieldOptions.size) {
                    Toast.makeText(context, getString(R.string.field_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val fieldKey = fieldOptions[selectedIndex].first
                val newValue = dialogBinding.editNewValue.text.toString().trim()
                if (newValue.isEmpty()) {
                    Toast.makeText(context, getString(R.string.value_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val description = dialogBinding.editDescription.text.toString().trim()

                val change = CharacterStateChange(
                    id = existingChange?.id ?: 0,
                    characterId = characterId,
                    year = year,
                    month = month,
                    day = day,
                    fieldKey = fieldKey,
                    newValue = newValue,
                    description = description,
                    createdAt = existingChange?.createdAt ?: System.currentTimeMillis()
                )

                if (existingChange != null) {
                    viewModel.updateStateChange(change)
                } else {
                    viewModel.insertStateChange(change)
                }

                Toast.makeText(context, getString(R.string.state_change_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditDeleteDialog(change: CharacterStateChange) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.edit_or_delete))
            .setMessage("${change.fieldKey} → ${change.newValue}")
            .setPositiveButton(R.string.edit) { _, _ ->
                showStateChangeDialog(change)
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                viewModel.deleteStateChange(change)
                Toast.makeText(context, getString(R.string.state_change_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }
}
