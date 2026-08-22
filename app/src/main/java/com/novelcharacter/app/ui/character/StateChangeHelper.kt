package com.novelcharacter.app.ui.character

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.databinding.DialogStateChangeBinding
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.ui.adapter.StateChangeAdapter
import com.novelcharacter.app.util.DetailListSort

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

    /** 보기 정렬(B-85). 상태 변화에는 저장 순서가 없어(DAO가 연·월·일 고정) 드래그와 충돌하지 않는다. */
    private var sortMode = DetailListSort.StateChangeMode.CHRONO

    /** 마지막으로 받은 원본 — 정렬만 바꿀 때 DB를 다시 읽지 않기 위해 들고 있는다. */
    private var currentChanges: List<CharacterStateChange> = emptyList()

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

        sortMode = CharacterDetailSortPrefs.stateChangeMode(contextGetter())
        binding.btnSortStateChanges.setOnClickListener { showSortMenu(it) }
        updateSortButton()
    }

    fun observe() {
        viewModel.getChangesByCharacter(characterId).observe(viewLifecycleOwner) { changes ->
            currentChanges = changes
            render()
            onSliderUpdate()
        }
    }

    // ── 보기 정렬(B-85) ──

    private fun showSortMenu(anchor: View) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        val modes = DetailListSort.StateChangeMode.values()
        android.widget.PopupMenu(context, anchor).apply {
            modes.forEachIndexed { i, m -> menu.add(0, i, i, sortLabelRes(m)) }
            setOnMenuItemClickListener { item ->
                sortMode = modes[item.itemId]
                CharacterDetailSortPrefs.setStateChangeMode(context, sortMode)
                updateSortButton()
                render()
                true
            }
            show()
        }
    }

    private fun updateSortButton() {
        binding.btnSortStateChanges.setText(sortLabelRes(sortMode))
    }

    private fun sortLabelRes(mode: DetailListSort.StateChangeMode): Int = when (mode) {
        DetailListSort.StateChangeMode.CHRONO -> R.string.sort_year_asc
        DetailListSort.StateChangeMode.CHRONO_DESC -> R.string.sort_year_desc
        DetailListSort.StateChangeMode.FIELD -> R.string.sort_field_key
    }

    /** 보이는 순서만 만든다 — 정렬을 바꿔도 DB를 다시 읽지 않는다(원칙 04). */
    private fun render() {
        val sorted = currentChanges.sortedWith(DetailListSort.stateChanges(sortMode))
        stateChangeAdapter.submitList(sorted)

        if (sorted.isEmpty()) {
            binding.textNoStateChanges.visibility = View.VISIBLE
            binding.stateChangesRecyclerView.visibility = View.GONE
        } else {
            binding.textNoStateChanges.visibility = View.GONE
            binding.stateChangesRecyclerView.visibility = View.VISIBLE
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

        // **목록에 없는 키를 들고 온 행은 자기 키를 옵션으로 얹는다** (2026.08.22).
        //
        // 종전에는 `indexOfFirst`가 못 찾으면 **아무 일도 하지 않았고**, 스피너는 0번
        // (출생)에 머물렀다. 저장 검증은 `selectedIndex < 0`만 막는데 0은 유효 범위라
        // 통과했고, 그래서 연도만 고쳐 저장하면 그 행의 `fieldKey`가 조용히 `__birth`로
        // **뒤바뀌었다** — 원래 상태변화는 사라지고 없던 출생 기록이 생겼다(같은 id·같은
        // code를 덮어쓰므로 새 행도 아니다). 경고도 확인창도 없었다.
        //
        // 닿는 길이 실재한다: 작품 미배정 캐릭터의 상세 화면은 `cachedFields`를 빈 목록으로
        // 못박으므로, 커스텀 필드에 상태변화를 둔 캐릭터를 미배정으로 옮기기만 해도 걸린다.
        // 필드 정의를 지운 경우도 같다.
        //
        // 얹어 두면 **키가 보존되고**(연도만 고치는 것이 뜻대로 된다) 사용자가 원하면
        // 다른 필드로 옮기는 것도 여전히 된다.
        val orphanKey = existingChange?.fieldKey
            ?.takeIf { key -> fieldOptions.none { it.first == key } }
        if (orphanKey != null) {
            fieldOptions.add(
                orphanKey to getString(
                    R.string.state_change_missing_field,
                    com.novelcharacter.app.ui.common.StateChangeFieldLabel
                        .of(context, orphanKey, cachedFields)
                )
            )
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

            // 위에서 고아 키를 얹었으므로 여기서 **반드시 찾힌다** — 못 찾는 갈래가 곧
            // 조용한 키 뒤바뀜이었다.
            val index = fieldOptions.indexOfFirst { it.first == existingChange.fieldKey }
            if (index >= 0) {
                dialogBinding.spinnerFieldKey.setSelection(index)
            }
        }

        val title = if (existingChange != null) getString(R.string.edit_state_change)
        else getString(R.string.add_state_change)

        // B-28: 검증 5종이 전부 '토스트 후 자동 닫힘'이라, 연도 하나를 잘못 적으면 필드·값·설명까지
        // 통째로 사라졌다. 실패 시 창을 열어 둔 채 틀린 칸에 오류를 걸어 교정 경로를 남긴다(S-12와 같은 형태).
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setValidatedPositiveButton {
            val yearStr = dialogBinding.editYear.text.toString().trim()
            val year = yearStr.toIntOrNull()
            if (year == null) {
                dialogBinding.layoutYear.showInlineError(
                    getString(if (yearStr.isEmpty()) R.string.year_required else R.string.enter_valid_year)
                )
                return@setValidatedPositiveButton false
            }

            val month = dialogBinding.editMonth.text.toString().trim().toIntOrNull()
            if (month != null && month !in 1..12) {
                dialogBinding.layoutMonth.showInlineError(getString(R.string.month_valid_range))
                return@setValidatedPositiveButton false
            }
            val day = dialogBinding.editDay.text.toString().trim().toIntOrNull()
            if (day != null && !com.novelcharacter.app.util.isValidDay(month, day)) {
                dialogBinding.layoutDay.showInlineError(getString(R.string.day_valid_range))
                return@setValidatedPositiveButton false
            }
            val selectedIndex = dialogBinding.spinnerFieldKey.selectedItemPosition
            if (selectedIndex < 0 || selectedIndex >= fieldOptions.size) {
                // 스피너에는 오류를 걸 자리가 없다 — 여기만 토스트를 남기되 창은 유지한다.
                Toast.makeText(context, getString(R.string.field_required), Toast.LENGTH_SHORT).show()
                return@setValidatedPositiveButton false
            }
            val fieldKey = fieldOptions[selectedIndex].first
            val newValue = dialogBinding.editNewValue.text.toString().trim()
            if (newValue.isEmpty()) {
                dialogBinding.layoutNewValue.showInlineError(getString(R.string.value_required))
                return@setValidatedPositiveButton false
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
                // 편집은 정체성을 보존한다(R-1) — 명시하지 않으면 기본값이 code를 재발급해
                // 엑셀 재가져오기 중복·복원 중복 차단 오동작을 만든다. 구버전 무코드 행은 1회 부여.
                code = existingChange?.code ?: generateEntityCode(),
                createdAt = existingChange?.createdAt ?: System.currentTimeMillis()
            )

            if (existingChange != null) {
                viewModel.updateStateChange(change)
            } else {
                viewModel.insertStateChange(change)
            }
            // 결과는 viewModel.result 채널이 실제 완료 후 통보 (낙관적 오탐·중복 알림 방지)
            true
        }
        dialog.show()
    }

    private fun showEditDeleteDialog(change: CharacterStateChange) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        // 필드 이름은 목록 행과 **같은 함수**가 짓는다 — 종전에는 이 자리만 내부 키를
        // 날것으로 보여 줘, 같은 행이 목록에서는 '출생'이고 창에서는 `__birth`였다.
        val label = com.novelcharacter.app.ui.common.StateChangeFieldLabel
            .of(context, change.fieldKey, cachedFieldsGetter())
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.edit_or_delete))
            .setMessage("$label → ${change.newValue}")
            .setPositiveButton(R.string.edit) { _, _ ->
                showStateChangeDialog(change)
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                confirmDelete(change, label)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    /**
     * 삭제 전 한 번 더 묻는다 — **형제 자리(명대사)가 이미 그렇게 한다.**
     *
     * 종전에는 이 자리만 [삭제]를 누르는 즉시 지웠다. 그 버튼은 수정·취소와 나란히 선
     * 세 갈래 중 하나라 **오탭이 곧 유실**이었고, 상태변화는 되돌릴 경로가 화면에 없다
     * (R-4 — 파괴적 동작은 실행 전에 결과를 알리고 취소 경로를 남긴다).
     */
    private fun confirmDelete(change: CharacterStateChange, label: String) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.state_change_delete_confirm, label, change.newValue))
            .setPositiveButton(R.string.delete) { _, _ ->
                // 결과는 viewModel.result 채널이 실제 완료 후 통보
                viewModel.deleteStateChange(change)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
