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
    private val navController: () -> NavController
) {
    private lateinit var relationshipAdapter: RelationshipAdapter
    private var relationshipJob: Job? = null

    fun setup() {
        relationshipAdapter = RelationshipAdapter(
            onClick = { item ->
                val bundle = Bundle().apply { putLong("characterId", item.otherCharacterId) }
                navController().navigate(R.id.characterDetailFragment, bundle)
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
                binding.textNoRelationships.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
                binding.relationshipsRecyclerView.visibility = if (displayItems.isEmpty()) View.GONE else View.VISIBLE
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
            if (otherCharacters.isEmpty()) {
                Toast.makeText(contextGetter(), "다른 캐릭터가 없습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val charNames = otherCharacters.map { it.name }.toTypedArray()
            val typeNames = CharacterRelationship.TYPES.toTypedArray()

            val context = contextGetter()
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
                .setTitle("관계 추가")
                .setView(dialogView)
                .setPositiveButton("저장") { _, _ ->
                    val selectedCharIndex = spinnerCharacter.selectedItemPosition
                    val selectedType = typeNames[spinnerType.selectedItemPosition]
                    val desc = editDesc.text.toString().trim()

                    if (selectedCharIndex >= 0) {
                        val otherChar = otherCharacters[selectedCharIndex]
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
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun showRelationshipOptionsDialog(item: RelationshipDisplayItem) {
        val context = contextGetter()
        AlertDialog.Builder(context)
            .setTitle("${item.otherCharacterName} (${item.relationshipType})")
            .setItems(arrayOf("삭제")) { _, which ->
                when (which) {
                    0 -> {
                        AlertDialog.Builder(context)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle(getString(R.string.delete_warning_title))
                            .setMessage("이 관계를 삭제하시겠습니까?")
                            .setPositiveButton("예") { _, _ ->
                                viewModel.deleteRelationshipById(item.relationshipId)
                            }
                            .setNegativeButton("아니오", null)
                            .show()
                    }
                }
            }
            .show()
    }
}
