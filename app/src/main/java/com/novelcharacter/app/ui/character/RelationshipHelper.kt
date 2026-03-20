package com.novelcharacter.app.ui.character

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.ui.adapter.RelationshipAdapter
import com.novelcharacter.app.ui.adapter.RelationshipDisplayItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RelationshipHelper(
    private val binding: FragmentCharacterDetailBinding,
    private val viewModel: CharacterViewModel,
    private val viewLifecycleOwner: LifecycleOwner,
    private val characterId: Long,
    private val contextGetter: () -> Context,
    private val getString: (Int) -> String,
    private val getFormattedString: (Int, Array<out Any>) -> String,
    private val navController: () -> NavController
) {
    private lateinit var relationshipAdapter: RelationshipAdapter
    private var relationshipJob: Job? = null

    fun setup() {
        relationshipAdapter = RelationshipAdapter(
            onClick = { item ->
                val bundle = Bundle().apply { putLong("characterId", item.otherCharacterId) }
                navController().navigateSafe(R.id.characterDetailFragment, R.id.characterDetailFragment, bundle)
            },
            onLongClick = { item ->
                showRelationshipOptionsDialog(item)
            }
        )
        binding.relationshipsRecyclerView.layoutManager = LinearLayoutManager(contextGetter())
        binding.relationshipsRecyclerView.adapter = relationshipAdapter

        binding.btnAddRelationship.setOnClickListener {
            showAddRelationshipDialog()
        }
    }

    fun observe() {
        viewModel.getRelationshipsForCharacter(characterId).observe(viewLifecycleOwner) { relationships ->
            relationshipJob?.cancel()
            relationshipJob = viewLifecycleOwner.lifecycleScope.launch {
                val displayItems = relationships.mapNotNull { rel ->
                    val otherId = if (rel.characterId1 == characterId) rel.characterId2 else rel.characterId1
                    val otherChar = viewModel.getCharacterByIdSuspend(otherId)
                    if (otherChar != null) {
                        RelationshipDisplayItem(
                            relationshipId = rel.id,
                            otherCharacterName = otherChar.name,
                            otherCharacterId = otherId,
                            relationshipType = rel.relationshipType,
                            description = rel.description
                        )
                    } else null
                }
                relationshipAdapter.submitList(displayItems)
                try {
                    binding.textNoRelationships.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
                    binding.relationshipsRecyclerView.visibility = if (displayItems.isEmpty()) View.GONE else View.VISIBLE
                } catch (_: Exception) {
                    // Fragment view may have been destroyed during suspend
                }
            }
        }
    }

    fun cancelJob() {
        relationshipJob?.cancel()
        relationshipJob = null
    }

    private fun showAddRelationshipDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val allCharacters = viewModel.getAllCharactersList()
            val otherCharacters = allCharacters.filter { it.id != characterId }

            val context = try { contextGetter() } catch (_: Exception) { return@launch }

            if (otherCharacters.isEmpty()) {
                Toast.makeText(context, getString(R.string.no_other_characters), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val charNames = otherCharacters.map { it.name }.toTypedArray()
            val typeNames = viewModel.getRelationshipTypesForCharacter(characterId).toTypedArray()
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_relationship_edit, null)
            val spinnerCharacter = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerRelCharacter)
            val spinnerType = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerRelType)
            val editDesc = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editRelDescription)

            spinnerCharacter.adapter = android.widget.ArrayAdapter(
                context, android.R.layout.simple_spinner_item, charNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            spinnerType.adapter = android.widget.ArrayAdapter(
                context, android.R.layout.simple_spinner_item, typeNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            AlertDialog.Builder(context)
                .setTitle(getString(R.string.add_relationship))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    val selectedCharIndex = spinnerCharacter.selectedItemPosition
                    val selectedType = typeNames[spinnerType.selectedItemPosition]
                    val desc = editDesc.text.toString().trim()

                    if (selectedCharIndex >= 0) {
                        val otherChar = otherCharacters[selectedCharIndex]
                        // Check for existing relationship to prevent duplicates
                        viewLifecycleOwner.lifecycleScope.launch {
                            val existingRels = viewModel.getRelationshipsForCharacterList(characterId)
                            val alreadyExists = existingRels.any {
                                (it.characterId1 == characterId && it.characterId2 == otherChar.id) ||
                                (it.characterId1 == otherChar.id && it.characterId2 == characterId)
                            }
                            if (alreadyExists) {
                                val ctx = try { contextGetter() } catch (_: Exception) { return@launch }
                                Toast.makeText(ctx, getString(R.string.relationship_already_exists), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            viewModel.insertRelationship(
                                CharacterRelationship(
                                    characterId1 = characterId,
                                    characterId2 = otherChar.id,
                                    relationshipType = selectedType,
                                    description = desc
                                )
                            )
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun showRelationshipOptionsDialog(item: RelationshipDisplayItem) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        AlertDialog.Builder(context)
            .setTitle(getFormattedString(R.string.relationship_title_format, arrayOf(item.otherCharacterName, item.relationshipType)))
            .setItems(arrayOf(getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> {
                        AlertDialog.Builder(context)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle(getString(R.string.delete_warning_title))
                            .setMessage(getString(R.string.confirm_delete_relationship))
                            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                viewModel.deleteRelationshipById(item.relationshipId)
                            }
                            .setNegativeButton(getString(R.string.no), null)
                            .show()
                    }
                }
            }
            .show()
    }
}
