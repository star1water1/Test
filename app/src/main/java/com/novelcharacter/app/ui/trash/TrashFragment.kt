package com.novelcharacter.app.ui.trash

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.dao.TrashSnapshotSummary
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.repository.RestoreLossCounts
import com.novelcharacter.app.data.repository.RestoreModes
import com.novelcharacter.app.data.repository.TrashFilter
import com.novelcharacter.app.data.repository.TrashGrouping
import com.novelcharacter.app.data.repository.TrashListPlan
import com.novelcharacter.app.data.repository.TrashPruneSelector
import com.novelcharacter.app.data.repository.TrashRepository
import com.novelcharacter.app.data.repository.TrashRetentionPolicy
import com.novelcharacter.app.data.settings.TrashSettingsStore
import com.novelcharacter.app.databinding.DialogTrashRetentionBinding
import com.novelcharacter.app.databinding.FragmentTrashBinding
import com.novelcharacter.app.databinding.ItemTrashBinding
import com.novelcharacter.app.databinding.ItemTrashOperationBinding
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.reportAndNotify
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 휴지통 (B-7 → B-1) — 삭제된 캐릭터·세계관·작품·세력·사건 스냅샷 목록.
 *
 * 목록은 **삭제 작업 단위로 묶여** 표시된다. 세계관 하나를 지우면 항목이 수백 개 생기므로
 * 평평한 목록만으로는 "무엇을 지웠는지"가 사라지고, 개별 복원만 있으면 사용자가 순서
 * (세계관 → 작품 → 세력 → 사건 → 캐릭터)를 직접 맞춰야 한다(원칙 04 위반).
 * 그룹 머리글의 '전체 복원'이 그 순서를 대신 지킨다.
 */
class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!

    private val trashRepository: TrashRepository by lazy {
        (requireActivity().application as NovelCharacterApp).trashRepository
    }

    private val adapter = TrashAdapter(
        onRestore = { confirmRestore(it) },
        onPurge = { item, siblings -> confirmPurge(item, siblings) },
        onRestoreOperation = { confirmRestoreOperation(it) },
        onPurgeOperation = { confirmPurgeOperation(it) },
        onToggleExpand = { toggleExpand(it) }
    )

    private val trashSettingsStore by lazy { TrashSettingsStore(requireContext().applicationContext) }

    /** 마지막으로 관찰한 스냅샷 — 필터가 바뀔 때 DB를 다시 읽지 않고 여기서 다시 그린다. */
    private var latestSnapshots: List<TrashSnapshotSummary> = emptyList()

    /**
     * 사용자가 **직접 접거나 편** 묶음만 담는다 (B-16). 없는 묶음은 기본값을 따른다 —
     * 평소에는 접힘, 거르는 중에는 펼침([TrashListPlan.build]가 그 근거를 든다).
     *
     * 기본을 접힘으로 둔 근거는 규모다 — 실사용 표본에서 세계관 하나가 캐릭터 24명(최대 43명)을
     * 거느리고, 목표 규모(표본 ×30)에서는 한 세계관 삭제가 **수백 행**을 만든다. 거기에 보관
     * 한도가 작업 30건이므로 펼친 목록은 수천 행이 될 수 있다. 그래서 접되, **머리글이 무엇이
     * 몇 건인지 말한다** — 원칙 04가 금지하는 것은 *존재를 알 수 없는 데이터*이지 접힌 데이터가
     * 아니다. 머리글이 종류·이름·건수를 말하는 한 존재는 한눈에 보인다.
     */
    private val expandChoices = mutableMapOf<String, Boolean>()

    /**
     * 직전에 필터가 걸려 있었는가 — **켜고 끄는 순간 사용자의 선택을 지우기 위해** 기억한다.
     *
     * 지우지 않으면 검색 중에 접어 둔 묶음이 검색을 지운 뒤에도 접힌 채로 남고, 반대로 검색 중에
     * 펼친 묶음이 평소 목록에서 수백 줄로 펼쳐진 채 남는다. 상황이 바뀌면 그 상황의 기본값으로
     * 되돌리는 편이 예측 가능하다.
     */
    private var lastFilterActive = false

    // ── 필터 상태 (B-50) ──
    private var selectedPeriod = TrashFilter.Period.ALL
    private val selectedTypes = linkedSetOf<String>()
    private var searchQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.toolbar.inflateMenu(R.menu.menu_trash)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_empty_trash -> { confirmEmptyTrash(); true }
                R.id.action_trash_retention -> { showRetentionDialog(); true }
                else -> false
            }
        }

        binding.trashRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.trashRecyclerView.adapter = adapter

        setupFilterControls()
        updateRetentionCaption()

        trashRepository.allSnapshots.observe(viewLifecycleOwner) { snapshots ->
            latestSnapshots = snapshots
            render()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 필터·검색 (B-50) · 접기 (B-16)
    // ──────────────────────────────────────────────────────────────────────

    private fun setupFilterControls() {
        binding.searchEdit.doOnTextChanged { text, _, _, _ ->
            searchQuery = text?.toString().orEmpty()
            render()
        }

        binding.periodChips.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedPeriod = when (checkedIds.firstOrNull()) {
                R.id.chipPeriod24h -> TrashFilter.Period.TODAY_24H
                R.id.chipPeriod7d -> TrashFilter.Period.LAST_7_DAYS
                R.id.chipPeriod30d -> TrashFilter.Period.LAST_30_DAYS
                else -> TrashFilter.Period.ALL
            }
            render()
        }

        binding.chipTypeFilter.setOnClickListener { showTypeFilterDialog() }

        // **고른 종류를 칩 글자에 다시 적는다.** 고른 값은 프래그먼트가 들고 있어 화면이 다시
        // 만들어져도(회전·되돌아오기) 살아남는데, 칩 글자는 XML 기본값("종류 전체")으로 되돌아간다.
        // 그러면 목록은 걸러진 채로 칩만 *안 걸렸다*고 말하게 된다 — 검색칸·기간 칩은 안드로이드가
        // 스스로 되살리므로 이 하나만 어긋났다.
        updateTypeChipLabel()
    }

    /**
     * 종류 고르기 — **지금 휴지통에 있는 종류**만 늘어놓는다. 열한 가지를 모두 보이면 고르는
     * 순간 아무것도 안 남는 갈래가 생기는데, 그것은 사용자에게 '없음'과 구별되지 않는다.
     * 이미 고른 종류는 지금 비어 있어도 남긴다 — 고른 것이 목록에서 사라지면 왜 걸러지는지
     * 알 수 없게 된다.
     */
    private fun showTypeFilterDialog() {
        val present = latestSnapshots.map { it.entityType }.toMutableSet()
        present.addAll(selectedTypes)
        val types = present.sortedWith(
            compareBy({ TrashSnapshot.restorePriority(it) }, { it })
        )
        if (types.isEmpty()) return
        val labels = types.map { typeLabel(requireContext(), it) }.toTypedArray()
        val checked = types.map { it in selectedTypes }.toBooleanArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_type_filter_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.confirm) { _, _ ->
                selectedTypes.clear()
                types.forEachIndexed { i, type -> if (checked[i]) selectedTypes.add(type) }
                updateTypeChipLabel()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateTypeChipLabel() {
        binding.chipTypeFilter.text = if (selectedTypes.isEmpty()) {
            getString(R.string.trash_type_filter_all)
        } else {
            getString(R.string.trash_type_filter_some, selectedTypes.size)
        }
    }

    private fun currentCriteria(): TrashFilter.Criteria = TrashFilter.Criteria(
        types = selectedTypes.toSet(),
        since = selectedPeriod.since(System.currentTimeMillis()),
        query = searchQuery
    )

    /** 누른 대로 뒤집는다 — 지금 보이는 상태의 반대가 사용자가 기대하는 결과다. */
    private fun toggleExpand(row: TrashListPlan.Row.Header) {
        expandChoices[row.stableKey] = !row.expanded
        render()
    }

    /** 목록·빈 상태 문구를 현재 스냅샷과 필터로 다시 그린다. */
    private fun render() {
        if (_binding == null) return
        val criteria = currentCriteria()
        // 거르는 상태가 바뀌면 접기 선택을 비운다 — 그래야 그 상황의 기본값이 다시 적용된다.
        if (criteria.isActive != lastFilterActive) {
            expandChoices.clear()
            lastFilterActive = criteria.isActive
        }
        val rows = TrashListPlan.build(latestSnapshots, criteria, expandChoices)
        adapter.submitList(rows)

        // 비어 있음과 걸러져서 없음은 다르다 — 같은 문구를 쓰면 사용자가 휴지통이 빈 줄 안다.
        binding.emptyText.text = if (latestSnapshots.isEmpty()) {
            getString(R.string.trash_empty_hint)
        } else {
            getString(R.string.trash_filter_empty)
        }
        binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // ──────────────────────────────────────────────────────────────────────
    // 보관 정책 (B-74)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 보관 안내를 현행 정책으로 적는다.
     *
     * **먼저 아는 값으로 적고, 저장소를 읽은 뒤 다시 적는다.** 앱 시작의 채우기가 아직
     * 끝나지 않았을 수 있는데, 그때 빈칸을 두면 화면이 잠깐 말을 못 하고 기본값만 적어 두면
     * 설정을 바꾼 사용자에게 거짓을 말한 채로 남는다.
     */
    private fun updateRetentionCaption() {
        writeRetentionCaption(TrashRetentionPolicy.currentOrDefault())
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = runCatching { trashSettingsStore.getSettings() }.getOrNull() ?: return@launch
            if (!isAdded || _binding == null) return@launch
            writeRetentionCaption(settings)
        }
    }

    private fun writeRetentionCaption(policy: TrashRetentionPolicy.Settings) {
        binding.retentionCaption.text = getString(
            R.string.trash_retention_caption, policy.maxOperations, policy.retentionDays
        )
    }

    /**
     * 보관 개수·기간을 고른다. **줄이는 쪽은 영구 삭제로 이어지므로** 무엇이 얼마나 사라지는지
     * 세어서 먼저 알린다(검증 → 알림 → 교정 경로).
     */
    private fun showRetentionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = try {
                trashSettingsStore.getSettings()
            } catch (_: Exception) {
                TrashRetentionPolicy.currentOrDefault()
            }
            if (!isAdded) return@launch

            val view = DialogTrashRetentionBinding.inflate(layoutInflater)
            val counts = TrashRetentionPolicy.MAX_OPERATION_CHOICES
            val days = TrashRetentionPolicy.RETENTION_DAY_CHOICES
            view.maxOperationsSpinner.adapter = spinnerAdapter(
                counts.map { getString(R.string.trash_retention_count_value, it) }
            )
            view.retentionDaysSpinner.adapter = spinnerAdapter(
                days.map { getString(R.string.trash_retention_days_value, it) }
            )
            // 저장된 값이 선택지에 없을 수 있다(엑셀에서 임의 숫자가 들어온 경우) — 가장 가까운
            // 선택지를 짚어 준다. 못 찾았다고 첫 칸을 보이면 사용자가 안 고른 값을 고른 것처럼 된다.
            view.maxOperationsSpinner.setSelection(closestIndex(counts, settings.maxOperations))
            view.retentionDaysSpinner.setSelection(closestIndex(days, settings.retentionDays))

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trash_retention_settings)
                .setView(view.root)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newMax = counts[view.maxOperationsSpinner.selectedItemPosition]
                    val newDays = days[view.retentionDaysSpinner.selectedItemPosition]
                    confirmShrinkThenSave(newMax, newDays)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun spinnerAdapter(labels: List<String>): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun closestIndex(choices: List<Int>, value: Int): Int {
        var best = 0
        for (i in choices.indices) {
            if (kotlin.math.abs(choices[i] - value) < kotlin.math.abs(choices[best] - value)) best = i
        }
        return best
    }

    /**
     * 새 정책이 지금 보관 중인 것을 지우게 되면 **먼저 세어서 알린다** (검증 → 알림 → 교정 경로).
     *
     * **세는 일을 손으로 다시 하지 않고 [TrashPruneSelector.plan]에 그대로 묻는다.** 정리가
     * 실제로 쓰는 함수라, 예고와 결과가 원리적으로 갈릴 수 없다 — 손으로 세면 종류별 독립 풀
     * (삭제 백업/편집 백업)이나 기한 축을 빠뜨리기 쉽고, 그렇게 빠뜨린 예고는 *줄여도
     * 안전하다*고 말한 뒤 조용히 지운다.
     *
     * **개수만이 아니라 기간도 본다** — 365일을 7일로 줄이는 것도 똑같이 영구 삭제로 이어진다.
     */
    private fun confirmShrinkThenSave(newMax: Int, newDays: Int) {
        val groups = TrashGrouping.group(latestSnapshots)
        val operations = groups.map {
            TrashPruneSelector.Operation(it.opKey, it.newestAt, it.size, it.isEditBackup)
        }
        val next = TrashRetentionPolicy.sanitize(TrashRetentionPolicy.Settings(newMax, newDays))
        val doomed = TrashPruneSelector.plan(
            operations,
            System.currentTimeMillis() - next.retentionMs,
            // 아직 일어나지 않은 정리를 미리 재는 것이라 '방금 만든 작업' 보호는 없다.
            protectedKeys = emptySet(),
            maxOperationsPerKind = next.maxOperations
        ).size
        if (doomed <= 0) {
            saveRetention(newMax, newDays)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_retention_settings)
            .setMessage(getString(R.string.trash_retention_shrink_warning, groups.size, doomed))
            .setPositiveButton(R.string.confirm) { _, _ -> saveRetention(newMax, newDays) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveRetention(newMax: Int, newDays: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                trashSettingsStore.setMaxOperations(newMax)
                trashSettingsStore.setRetentionDays(newDays)
                if (!isAdded) return@launch
                updateRetentionCaption()
                reportAndNotify(OpResult.success(OpResult.CAT_TRASH,
                    getString(R.string.trash_retention_saved, newMax, newDays)))
            } catch (e: Exception) {
                if (isAdded) reportAndNotify(OpResult.failure(OpResult.CAT_TRASH,
                    getString(R.string.trash_retention_failed), e.message))
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 항목 단위 복원
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 복원 확인 — 먼저 미리보기로 무엇이 되살아나지 않는지 확인한다.
     *
     * 복원은 성공하면 스냅샷을 소각하므로, 되살릴 수 없는 부분이 있는 채로 진행하면
     * payload에만 남아 있던 원본이 그 순간 영구 소멸한다. 취소하면 스냅샷은 그대로 남으므로
     * 세계관·작품·필드 정의를 먼저 되살린 뒤 다시 복원할 수 있다(검증 → 알림 → 교정 경로).
     */
    private fun confirmRestore(snapshot: TrashSnapshotSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            val preview = try {
                trashRepository.previewRestore(snapshot.id)
            } catch (_: Exception) {
                null
            }
            if (!isAdded) return@launch

            // 되살릴 수 없는 사유가 있으면 진행 자체를 막는다 — 스냅샷을 남겨야
            // 상위 항목을 먼저 복원한 뒤 다시 시도할 수 있다(R-4).
            preview?.blocker?.let { blocker ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.trash_restore_blocked_title)
                    .setMessage(blockerMessage(blocker, preview.entityType))
                    .setPositiveButton(R.string.confirm, null)
                    .show()
                return@launch
            }

            if (preview == null || !preview.needsConfirmation) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.trash_restore)
                    .setMessage(getString(R.string.trash_restore_confirm, snapshot.entityName))
                    .setPositiveButton(R.string.confirm) { _, _ -> restore(snapshot, warned = false) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@launch
            }

            val details = buildSkipDetails(preview.losses)
            val message = StringBuilder(
                if (details.isEmpty()) {
                    // 유실은 없고 다른 이유(편집 백업·구버전 payload)로만 확인이 필요한 경우 —
                    // 없는 유실을 있는 것처럼 적지 않는다.
                    getString(R.string.trash_restore_confirm, snapshot.entityName)
                } else {
                    getString(R.string.trash_restore_preview, snapshot.entityName, details)
                }
            )
            if (preview.duplicatesLivingEntity) {
                message.append(getString(R.string.trash_restore_duplicate_warning))
            }
            if (preview.revertsInPlace) {
                message.append(
                    getString(R.string.trash_restore_revert_warning, revertScopeLines(preview.revertScope))
                )
            }
            if (preview.legacyPayload) {
                message.append(getString(R.string.trash_restore_legacy_note))
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trash_restore_preview_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.trash_restore) { _, _ ->
                    restore(
                        snapshot, warned = true, predicted = preview.losses,
                        consentedRevert = preview.revertsInPlace
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun blockerMessage(blocker: TrashRepository.RestoreBlocker, entityType: String): String = when (blocker) {
        // 같은 사유라도 주어가 다르다 — 세력 문구를 등급 체계에 그대로 쓰면 사실과 다른 안내가 된다.
        TrashRepository.RestoreBlocker.MISSING_UNIVERSE -> when (entityType) {
            TrashSnapshot.TYPE_GRADE_SYSTEM -> getString(R.string.trash_restore_blocked_universe_grade_system)
            TrashSnapshot.TYPE_DUEL_AXIS -> getString(R.string.trash_restore_blocked_universe_duel_axis)
            else -> getString(R.string.trash_restore_blocked_universe)
        }
        TrashRepository.RestoreBlocker.MISSING_CHARACTER -> getString(R.string.trash_restore_blocked_character)
        TrashRepository.RestoreBlocker.MISSING_DUEL_AXIS -> getString(R.string.trash_restore_blocked_duel_axis)
        TrashRepository.RestoreBlocker.ALREADY_EXISTS -> getString(R.string.trash_restore_blocked_exists)
    }

    /**
     * 되돌리기가 덮어쓸 갈래를 사람이 읽는 목록으로 — 무엇이 덮이는지 **세어서** 알려야
     * 동의가 성립한다(R-4). 범위는 그 편집이 실제로 파괴한 것까지다.
     */
    private fun revertScopeLines(scope: Set<String>): String {
        val lines = mutableListOf<String>()
        if (RestoreModes.SCOPE_CHARACTER_ROW in scope) {
            lines.add(getString(R.string.trash_revert_scope_character_row))
        }
        if (RestoreModes.SCOPE_FIELD_VALUES in scope) {
            lines.add(getString(R.string.trash_revert_scope_field_values))
        }
        if (RestoreModes.SCOPE_MEMBERSHIPS in scope) {
            lines.add(getString(R.string.trash_revert_scope_memberships))
        }
        if (RestoreModes.SCOPE_STATE_CHANGES in scope) {
            lines.add(getString(R.string.trash_revert_scope_state_changes))
        }
        if (RestoreModes.SCOPE_FIELD_DEFINITIONS in scope) {
            lines.add(getString(R.string.trash_revert_scope_field_definitions))
        }
        return lines.joinToString("\n")
    }

    /**
     * 미리보기·결과가 공유하는 항목별 사유 문구 — 두 곳이 드리프트하지 않게 한 곳에서 만든다.
     * [RestoreLossCounts]를 통째로 받는다: 항목이 늘어날 때 인자 목록을 고치지 않아도 되고,
     * "새 유실 칸을 만들었는데 화면에는 안 나오는" 무음 경로가 생기지 않는다.
     */
    private fun buildSkipDetails(losses: RestoreLossCounts): String {
        val details = mutableListOf<String>()
        if (losses.novelCleared) details.add(getString(R.string.trash_skip_novel))
        if (losses.universeCleared) details.add(getString(R.string.trash_skip_universe))
        if (losses.fieldValues > 0) details.add(getString(R.string.trash_skip_fields, losses.fieldValues))
        if (losses.mergedFieldValues > 0) {
            details.add(getString(R.string.trash_skip_merged_fields, losses.mergedFieldValues))
        }
        if (losses.orphanFieldValues > 0) {
            details.add(getString(R.string.trash_skip_orphan_values, losses.orphanFieldValues))
        }
        if (losses.novelFieldValues > 0) {
            details.add(getString(R.string.trash_skip_novel_fields, losses.novelFieldValues))
        }
        if (losses.fieldDefinitions > 0) {
            details.add(getString(R.string.trash_skip_field_definitions, losses.fieldDefinitions))
        }
        if (losses.revertTargetsMissing > 0) {
            details.add(getString(R.string.trash_skip_revert_targets, losses.revertTargetsMissing))
        }
        if (losses.gradeSystems > 0) {
            details.add(getString(R.string.trash_skip_grade_systems, losses.gradeSystems))
        }
        if (losses.gradeSystemLinks > 0) {
            details.add(getString(R.string.trash_skip_grade_system_links, losses.gradeSystemLinks))
        }
        if (losses.duelBasisAxisCleared) {
            details.add(getString(R.string.trash_skip_duel_basis_axis))
        }
        if (losses.fieldValueEntries > 0) {
            details.add(getString(R.string.trash_skip_field_value_entries, losses.fieldValueEntries))
        }
        if (losses.relationships > 0) {
            details.add(getString(R.string.trash_skip_relationships, losses.relationships))
        }
        if (losses.relationshipChanges > 0) {
            details.add(getString(R.string.trash_skip_relationship_changes, losses.relationshipChanges))
        }
        if (losses.duplicateRelationshipChanges > 0) {
            details.add(getString(R.string.trash_skip_dup_relationship_changes, losses.duplicateRelationshipChanges))
        }
        if (losses.memberships > 0) details.add(getString(R.string.trash_skip_memberships, losses.memberships))
        if (losses.factionRelationships > 0) {
            details.add(getString(R.string.trash_skip_faction_relationships, losses.factionRelationships))
        }
        if (losses.detachedRelationships > 0) {
            details.add(getString(R.string.trash_skip_detached_relationships, losses.detachedRelationships))
        }
        if (losses.events > 0) details.add(getString(R.string.trash_skip_events, losses.events))
        if (losses.characterLinks > 0) {
            details.add(getString(R.string.trash_skip_character_links, losses.characterLinks))
        }
        if (losses.novelLinks > 0) details.add(getString(R.string.trash_skip_novel_links, losses.novelLinks))
        if (losses.changeLinks > 0) details.add(getString(R.string.trash_skip_change_links, losses.changeLinks))
        if (losses.relationshipFactions > 0) {
            details.add(getString(R.string.trash_skip_rel_factions, losses.relationshipFactions))
        }
        if (losses.changeEvents > 0) details.add(getString(R.string.trash_skip_change_events, losses.changeEvents))
        if (losses.imageLinks > 0) details.add(getString(R.string.trash_skip_image_links, losses.imageLinks))
        if (losses.stateChanges > 0) {
            details.add(getString(R.string.trash_skip_state_changes, losses.stateChanges))
        }
        if (losses.nameBankLinks > 0) {
            details.add(getString(R.string.trash_skip_name_bank, losses.nameBankLinks))
        }
        return details.joinToString("\n")
    }

    /**
     * @param warned 복원 전 확인 다이얼로그에서 유실 항목을 이미 고지하고 동의를 받았는가.
     * @param predicted 그때 고지한 항목별 유실 규모. **어느 항목이라도 실제가 이보다 커지면
     *   사후에도 알린다** — 미리보기는 예측이고 결과가 사실이다. 총량 하나로 비교하면
     *   예고분이 사라지고 다른 유실이 생긴 경우(총량은 줄었지만 동의한 적 없는 유실)를 놓친다.
     */
    private fun restore(
        snapshot: TrashSnapshotSummary,
        warned: Boolean,
        predicted: RestoreLossCounts = RestoreLossCounts(),
        /**
         * 되돌리기(살아 있는 대상 덮어쓰기)에 동의했는가 — 동의 없이는 덮어쓰지 않는다.
         * 미리보기 이후 원본이 되살아나 판정이 뒤집혀도 사용자가 동의한 범위를 넘지 않는다.
         */
        consentedRevert: Boolean = false
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = trashRepository.restoreSnapshot(snapshot.id, consentedRevert)
                resyncAutoLinkAfterRestore()
                if (!isAdded) return@launch
                if (result == null) {
                    Toast.makeText(requireContext(), R.string.trash_restore_failed, Toast.LENGTH_SHORT).show()
                    logOperation(OpResult.failure(OpResult.CAT_TRASH,
                        getString(R.string.result_trash_restore_failed)))
                    return@launch
                }
                // 실제로 한 일을 말한다 — 동의 시점의 예고가 아니라 결과가 사실이다.
                val doneMessage = if (result.revertedInPlace) {
                    getString(R.string.trash_reverted, result.restoredName)
                } else {
                    getString(R.string.trash_restored, result.restoredName)
                }
                Toast.makeText(requireContext(), doneMessage, Toast.LENGTH_SHORT).show()
                // 즉시 알림은 위 Toast/부분복원 다이얼로그가 담당 — 이력만 추가
                logOperation(OpResult.success(OpResult.CAT_TRASH, doneMessage))
                showRestoreNotes(
                    result.losses, result.relinkedByCode, result.duplicateRelationships,
                    warned, predicted, semanticStateChanges = result.restoredSemanticStateChanges
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.trash_restore_failed, Toast.LENGTH_SHORT).show()
                }
                logOperation(OpResult.failure(OpResult.CAT_TRASH,
                    getString(R.string.result_trash_restore_failed), e.message))
            }
        }
    }

    /**
     * 미리보기가 예고하지 못하는 결과만 사후에 알린다 — 참조 소실은 이미 복원 전 확인
     * 다이얼로그에서 고지하고 동의를 받았으므로 같은 내용을 두 번 띄우지 않는다.
     * 코드 재연결·중복 관계는 실제로 써 보기 전에는 알 수 없는 결과다.
     */
    private fun showRestoreNotes(
        losses: RestoreLossCounts,
        relinkedByCode: Int,
        duplicateRelationships: Int,
        warned: Boolean,
        predicted: RestoreLossCounts,
        extraNote: String? = null,
        semanticStateChanges: Int = 0
    ) {
        val notes = mutableListOf<String>()
        extraNote?.let { notes.add(it) }
        // 이력은 되살렸지만 파생 필드값은 되돌리지 않았다 — 사실대로 알린다.
        if (semanticStateChanges > 0) {
            notes.add(getString(R.string.trash_restore_semantic_note, semanticStateChanges))
        }
        // 고지 없이 진행했거나, 실제 유실이 예고보다 커졌으면 사실대로 알린다.
        if (losses.any && (!warned || losses.exceeds(predicted))) {
            val details = buildSkipDetails(losses)
            if (details.isNotEmpty()) {
                notes.add(getString(R.string.trash_restore_partial, details))
            }
        }
        if (relinkedByCode > 0) {
            notes.add(getString(R.string.trash_restore_relinked, relinkedByCode))
        }
        if (duplicateRelationships > 0) {
            notes.add(getString(R.string.trash_restore_duplicate_rel, duplicateRelationships))
        }
        if (notes.isNotEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trash_restore_note_title)
                .setMessage(notes.joinToString("\n"))
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 작업(그룹) 단위 복원·삭제
    // ──────────────────────────────────────────────────────────────────────

    private fun confirmRestoreOperation(row: TrashListPlan.Row.Header) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 그룹 미리보기는 "이 작업이 곧 되살릴 코드"를 알고 계산한다 — 그러지 않으면
            // "세력을 못 살린다"처럼 순서만 지키면 해결될 일을 유실로 예고하게 된다.
            val previews = try {
                trashRepository.previewOperation(row.opKey, row.editBackup)
            } catch (_: Exception) {
                emptyList()
            }
            if (!isAdded) return@launch

            // 막힌 항목(상위 엔티티가 없어 되살릴 수 없는 것)은 복원되지 않고 휴지통에 남는다.
            // 그 사실을 **실행 전에** 말해야 한다(R-4). 그 항목들의 예상 유실은 실제로 일어나지
            // 않으므로 예고 집계에서도 뺀다 — 넣으면 없는 유실을 예고하고, 사후 비교 기준까지
            // 부풀어 진짜 예상 밖 유실을 덮는다.
            val blocked = previews.filter { it.blocker != null }
            val restorable = previews.filter { it.blocker == null }
            val predicted = restorable.fold(RestoreLossCounts()) { acc, p -> acc + p.losses }
            val message = StringBuilder(
                getString(R.string.trash_operation_restore_confirm, row.itemCount - blocked.size)
            )
            if (blocked.isNotEmpty()) {
                message.append("\n\n").append(
                    getString(
                        R.string.trash_operation_blocked,
                        blocked.size,
                        blocked.joinToString("\n") { "- ${it.entityName}" }
                    )
                )
            }
            val details = buildSkipDetails(predicted)
            if (details.isNotEmpty()) {
                message.append("\n\n").append(getString(R.string.trash_restore_partial, details))
            }
            if (restorable.any { it.legacyPayload }) {
                message.append(getString(R.string.trash_restore_legacy_note))
            }
            if (restorable.any { it.duplicatesLivingEntity }) {
                message.append(getString(R.string.trash_restore_duplicate_warning))
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trash_restore_operation)
                .setMessage(message.toString())
                .setPositiveButton(R.string.trash_restore) { _, _ -> restoreOperation(row, predicted) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun restoreOperation(row: TrashListPlan.Row.Header, predicted: RestoreLossCounts) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = trashRepository.restoreOperation(row.opKey, row.editBackup)
                resyncAutoLinkAfterRestore()
                if (!isAdded) return@launch
                Toast.makeText(
                    requireContext(),
                    getString(R.string.trash_operation_restored, result.restored.size),
                    Toast.LENGTH_SHORT
                ).show()
                logOperation(OpResult.success(OpResult.CAT_TRASH,
                    getString(R.string.trash_operation_restored, result.restored.size)))
                // 되살리지 못한 항목은 휴지통에 남는다 — 사실을 먼저 알린다(막힌 항목이 있으면
                // 상위 항목이 없다는 뜻이라, 이것을 숨기면 사용자는 조용히 잃었다고 오해한다).
                val failedNote = if (result.failed.isEmpty()) null else getString(
                    R.string.trash_operation_failed,
                    result.failed.size,
                    result.failed.joinToString("\n") { "- $it" }
                )
                showRestoreNotes(
                    result.losses, result.relinkedByCode, result.duplicateRelationships,
                    warned = true, predicted = predicted, extraNote = failedNote,
                    semanticStateChanges = result.restored.sumOf { it.restoredSemanticStateChanges }
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.trash_restore_failed, Toast.LENGTH_SHORT).show()
                }
                logOperation(OpResult.failure(OpResult.CAT_TRASH,
                    getString(R.string.result_trash_restore_failed), e.message))
            }
        }
    }

    /**
     * 복원 직후 캐릭터 자동 링크 재동기화 — 복원은 등록 이벤트라, 되살아난 캐릭터의 이미지가
     * 다음 저장을 기다리지 않고 지금 다시 묶이게 한다(id 재발급으로 낡은 토큰도 함께 치유).
     * 실패해도 복원 결과를 되돌리지 않는다(다음 재동기화가 수렴시킨다).
     */
    private suspend fun resyncAutoLinkAfterRestore() {
        val appCtx = context?.applicationContext ?: return
        val db = (activity?.application as? com.novelcharacter.app.NovelCharacterApp)?.database ?: return
        runCatching { com.novelcharacter.app.util.CharacterImageAutoLinker.resyncIfEnabled(appCtx, db) }
    }

    private fun confirmPurgeOperation(row: TrashListPlan.Row.Header) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_delete_forever)
            .setMessage(getString(R.string.trash_operation_purge_confirm, row.itemCount))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val purged = trashRepository.purgeOperation(row.opKey, row.editBackup)
                        if (isAdded) reportAndNotify(OpResult.success(OpResult.CAT_TRASH,
                            getString(R.string.trash_operation_purged, purged)))
                    } catch (e: Exception) {
                        if (isAdded) reportAndNotify(OpResult.failure(OpResult.CAT_TRASH,
                            getString(R.string.result_trash_purge_failed), e.message))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * @param siblingCount 같은 삭제 작업에 남아 있는 다른 항목 수. 0이 아니면 **먼저 알린다** —
     *   묶음의 뿌리(세계관 등)를 혼자 영구 삭제하면 남은 항목들이 붙을 자리를 잃어 복원이
     *   막히거나 참조가 빠진 채 되살아난다. 그 사실을 말하지 않으면 '개별 영구 삭제'가
     *   조용히 나머지를 못 쓰게 만든다(R-4).
     */
    private fun confirmPurge(snapshot: TrashSnapshotSummary, siblingCount: Int = 0) {
        val message = StringBuilder(getString(R.string.trash_purge_confirm, snapshot.entityName))
        if (siblingCount > 0) {
            message.append("\n\n").append(getString(R.string.trash_purge_sibling_warning, siblingCount))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_delete_forever)
            .setMessage(message.toString())
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        trashRepository.purgeSnapshot(snapshot.id)
                        if (isAdded) reportAndNotify(OpResult.success(OpResult.CAT_TRASH,
                            getString(R.string.result_trash_purged, snapshot.entityName)))
                    } catch (e: Exception) {
                        if (isAdded) reportAndNotify(OpResult.failure(OpResult.CAT_TRASH,
                            getString(R.string.result_trash_purge_failed), e.message))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmEmptyTrash() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_empty_all)
            .setMessage(R.string.trash_empty_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val purged = trashRepository.emptyTrash()
                        if (isAdded) reportAndNotify(OpResult.success(OpResult.CAT_TRASH,
                            if (purged > 0) getString(R.string.result_trash_emptied, purged)
                            else getString(R.string.result_trash_empty_none)))
                    } catch (e: Exception) {
                        if (isAdded) reportAndNotify(OpResult.failure(OpResult.CAT_TRASH,
                            getString(R.string.result_trash_empty_failed), e.message))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        binding.trashRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // 목록 구성
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 휴지통 목록 어댑터 — [ListAdapter]라 갱신이 **바뀐 행만** 다시 그린다 (B-16).
     *
     * 종전에는 `notifyDataSetChanged` 하나였다. 그러면 관찰이 한 번 튈 때마다 보이는 행 전부가
     * 다시 묶이고, 스크롤 위치와 눌림 상태가 함께 튄다 — 수백 행짜리 묶음에서 그 대가가 크다.
     */
    private class TrashAdapter(
        private val onRestore: (TrashSnapshotSummary) -> Unit,
        private val onPurge: (TrashSnapshotSummary, Int) -> Unit,
        private val onRestoreOperation: (TrashListPlan.Row.Header) -> Unit,
        private val onPurgeOperation: (TrashListPlan.Row.Header) -> Unit,
        private val onToggleExpand: (TrashListPlan.Row.Header) -> Unit
    ) : ListAdapter<TrashListPlan.Row, RecyclerView.ViewHolder>(DIFF) {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        override fun getItemViewType(position: Int): Int =
            if (getItem(position) is TrashListPlan.Row.Header) VIEW_OPERATION else VIEW_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_OPERATION) {
                OperationHolder(ItemTrashOperationBinding.inflate(inflater, parent, false))
            } else {
                Holder(ItemTrashBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is TrashListPlan.Row.Header -> {
                    val b = (holder as OperationHolder).binding
                    b.opTitle.text = headerTitle(b.root.context, row)
                    val stamp = dateFormat.format(Date(row.newestAt))
                    // 걸러져 일부만 보일 때는 **전체 수까지** 적는다 — 일괄 동작이 건드리는 것은
                    // 언제나 작업 전체라, 걸린 수만 적으면 누르는 순간 예고보다 많이 움직인다.
                    b.opMeta.text = if (row.matchedCount < row.itemCount) {
                        b.root.context.getString(
                            R.string.trash_operation_meta_filtered,
                            row.matchedCount, row.itemCount, stamp
                        )
                    } else {
                        b.root.context.getString(R.string.trash_operation_meta, row.itemCount, stamp)
                    }
                    b.opExpandIcon.setImageResource(
                        if (row.expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                    )
                    b.root.setOnClickListener { onToggleExpand(row) }
                    // 편집 직전 백업 묶음은 접기만 내준다 — 원본이 살아 있어 '전체 복원'은
                    // 되돌리기가 아니라 원클릭 복제이고, 안내 문구도 거짓이 된다.
                    val bulk = if (row.allowsBulkActions) View.VISIBLE else View.GONE
                    b.btnRestoreAll.visibility = bulk
                    b.btnPurgeAll.visibility = bulk
                    b.btnRestoreAll.setOnClickListener { onRestoreOperation(row) }
                    b.btnPurgeAll.setOnClickListener { onPurgeOperation(row) }
                }
                is TrashListPlan.Row.Item -> {
                    val b = (holder as Holder).binding
                    val item = row.snapshot
                    b.trashItemName.text = item.entityName
                    b.trashItemMeta.text = b.root.context.getString(
                        R.string.trash_item_meta,
                        typeLabel(b.root.context, item.entityType),
                        dateFormat.format(Date(item.deletedAt))
                    )
                    b.btnRestore.setOnClickListener { onRestore(item) }
                    b.btnPurge.setOnClickListener { onPurge(item, row.siblingCount) }
                }
            }
        }

        class Holder(val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root)
        class OperationHolder(val binding: ItemTrashOperationBinding) :
            RecyclerView.ViewHolder(binding.root)

        companion object {
            const val VIEW_OPERATION = 0
            const val VIEW_ITEM = 1

            val DIFF = object : DiffUtil.ItemCallback<TrashListPlan.Row>() {
                override fun areItemsTheSame(a: TrashListPlan.Row, b: TrashListPlan.Row) = a.stableKey == b.stableKey
                // 행이 전부 data class라 내용 비교가 곧 "다시 그려야 하는가"다.
                override fun areContentsTheSame(a: TrashListPlan.Row, b: TrashListPlan.Row) = a == b
            }
        }
    }

    companion object {
        /** 알 수 없는 타입은 원문 그대로 보여준다 — 라벨이 없다고 항목을 숨기면 존재를 잃는다. */
        fun typeLabel(context: android.content.Context, entityType: String): String = when (entityType) {
            TrashSnapshot.TYPE_CHARACTER -> context.getString(R.string.trash_type_character)
            TrashSnapshot.TYPE_UNIVERSE -> context.getString(R.string.trash_type_universe)
            TrashSnapshot.TYPE_UNIVERSE_DATA -> context.getString(R.string.trash_type_universe_data)
            TrashSnapshot.TYPE_NOVEL -> context.getString(R.string.trash_type_novel)
            TrashSnapshot.TYPE_FACTION -> context.getString(R.string.trash_type_faction)
            TrashSnapshot.TYPE_EVENT -> context.getString(R.string.trash_type_event)
            TrashSnapshot.TYPE_STATE_CHANGE -> context.getString(R.string.trash_type_state_change)
            TrashSnapshot.TYPE_GRADE_SYSTEM -> context.getString(R.string.trash_type_grade_system)
            TrashSnapshot.TYPE_DUEL_AXIS -> context.getString(R.string.trash_type_duel_axis)
            TrashSnapshot.TYPE_DUEL_MATCHES -> context.getString(R.string.trash_type_duel_matches)
            TrashSnapshot.TYPE_FIELD_DEFINITION -> context.getString(R.string.trash_type_field_definition)
            else -> entityType
        }

        /**
         * 머리글 문구 — 묶음의 **뿌리 항목**(= 그 삭제의 주어)에서 만든다.
         *
         * 행을 짜는 일(무엇이 몇 줄로 보이는가)은 [TrashListPlan]이 하고 여기는 문구만 붙인다.
         * 그렇게 가른 이유는 **판정을 실행 검증하기 위해서다** — 보이지 않는 항목은 사용자에게
         * 없는 것과 구별되지 않는데, `Context`를 쥔 코드는 순수 JVM 시험에 올릴 수 없다.
         */
        fun headerTitle(context: android.content.Context, row: TrashListPlan.Row.Header): String =
            context.getString(
                // 편집 직전 백업은 지워진 적이 없다 — '삭제'라 부르면 그 자체가 거짓이다.
                if (row.editBackup) R.string.trash_operation_title_edit_backup
                else R.string.trash_operation_title,
                typeLabel(context, row.rootType),
                row.rootName
            )
    }
}
