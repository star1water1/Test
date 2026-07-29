package com.novelcharacter.app.ui.image

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.notifySuccess
import kotlinx.coroutines.launch

/**
 * 정리 폴더 왕복(받아오기·정리용 내보내기·폴더 설정)의 **화면 흐름 단일 소스**.
 *
 * ## 왜 프래그먼트 밖으로 나왔는가
 *
 * 이 흐름은 원래 [ImageManagerFragment] 안에만 있었다. 그런데 같은 기능을 설정 화면에서도
 * 쓰게 되면서(사용자 요청) 선택지는 둘뿐이었다 — 흐름을 복제하거나, 한 곳으로 모으거나.
 * 복제는 이 저장소가 반복해서 값비싸게 배운 실패다(같은 규칙이 두 곳에 있으면 반드시
 * 갈라진다 — `architecture` 3장). 그래서 모았다.
 *
 * 화면에 붙는 부분은 **[onBanner] 하나뿐**이다. 이미지 탭에는 진입 감지 배너가 있고 설정
 * 화면에는 없다 — 그 차이만 호스트가 정하고, 판정·확인·진행도·결과 고지는 전부 여기 있다.
 *
 * ## 수명
 *
 * SAF 폴더 선택기를 `registerForActivityResult`로 등록하므로 **프래그먼트의 필드 초기화
 * 시점에 만들어야 한다**(STARTED 이후에 등록하면 예외가 난다). 호스트가 `by lazy`나
 * `onViewCreated`에서 만들면 안 된다.
 *
 * @param fragment 이 흐름을 띄우는 화면. 다이얼로그·문자열·수명이 전부 여기에 붙는다.
 * @param viewModelProvider 폴더 왕복 동작의 소유자를 **필요할 때** 돌려준다. 람다인 것이
 *        중요하다 — 이 컨트롤러는 프래그먼트 필드 초기화 시점에 만들어지는데, 그때는 아직
 *        프래그먼트가 붙기 전이라 `by viewModels()`를 건드리면 "detached fragment" 예외가 난다.
 *        SAF 등록은 생성자에서(일찍), 뷰모델 해소는 첫 사용에서(늦게) 일어나야 한다.
 * @param onBanner 진입 감지 결과(새 이미지 수). 배너가 없는 화면은 비워 두면 된다.
 */
class OrganizeFolderController(
    private val fragment: Fragment,
    private val viewModelProvider: () -> ImageManagerViewModel,
    private val onBanner: (Int) -> Unit = {}
) {

    private val viewModel: ImageManagerViewModel get() = viewModelProvider()

    /**
     * 폴더 지정 후에 이어서 할 일 — 지정 플로우가 "받아오기·내보내기를 누르다 온 것"인지
     * 기억한다. 지정만 하고 원래 하려던 일을 다시 누르게 하는 것은 마찰이다(원칙 04).
     */
    private var pendingAfterPick: (() -> Unit)? = null

    private val organizeFolderPicker = fragment.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) { pendingAfterPick = null; return@registerForActivityResult }
        // 앱이 재시작해도 폴더를 계속 읽고 쓸 수 있어야 한다(재지정 요구는 마찰이다).
        runCatching {
            fragment.requireContext().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        viewModel.setOrganizeFolderUri(uri.toString()) {
            if (!fragment.isAdded || fragment.view == null) return@setOrganizeFolderUri
            fragment.notifySuccess(fragment.getString(R.string.organize_folder_set))
            refreshOrganizeFolderBanner()
            pendingAfterPick?.let { pendingAfterPick = null; it() }
        }
    }

    fun showOrganizeFolderSettings() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val current = viewModel.getOrganizeFolderUri()
            if (!fragment.isAdded || fragment.view == null) return@launch
            val message = if (current == null) {
                fragment.getString(R.string.organize_folder_none)
            } else {
                fragment.getString(R.string.organize_folder_current, android.net.Uri.decode(current))
            }
            val builder = MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.organize_folder_settings)
                // 삭제 의미론을 여기서 밝힌다 — "폴더를 정리한다"는 말은 자연스럽게 "폴더가
                // 진실이 된다"로 읽히지만, 실제로는 **폴더는 지시서**다(있는 것은 지시, 없는
                // 것은 침묵). 오해하면 사용자는 폴더에서 지운 것이 앱에서도 지워졌다고 믿는다.
                .setMessage(
                    fragment.getString(R.string.organize_folder_purpose) + "\n\n" +
                        fragment.getString(R.string.organize_folder_delete_note) + "\n\n" + message
                )
                .setPositiveButton(R.string.organize_folder_pick) { _, _ ->
                    pendingAfterPick = null
                    organizeFolderPicker.launch(null)
                }
                .setNegativeButton(R.string.close, null)
            if (current != null) {
                builder.setNeutralButton(R.string.organize_folder_clear) { _, _ ->
                    viewModel.setOrganizeFolderUri(null) {
                        if (!fragment.isAdded || fragment.view == null) return@setOrganizeFolderUri
                        fragment.notifySuccess(fragment.getString(R.string.organize_folder_cleared))
                        refreshOrganizeFolderBanner()
                    }
                }
            }
            builder.show()
        }
    }

    /** 진입 감지 — 신규 후보가 있을 때만 배너를 보인다. 실패·지연은 조용히 생략(수동 메뉴가 있다). */
    fun refreshOrganizeFolderBanner() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val count = runCatching { viewModel.countOrganizeFolderCandidates() }.getOrDefault(0)
            if (!fragment.isAdded || fragment.view == null) return@launch
            // 배너를 그리는 것은 호스트의 몫이다 — 이미지 탭엔 배너가 있고 설정 화면엔 없다.
            onBanner(count)
        }
    }

    /**
     * 폴더가 없거나 접근할 수 없을 때의 안내 — 받아오기·내보내기가 같은 문구·같은 복귀 경로를
     * 쓴다. 지정이 끝나면 [retry]로 원래 하려던 일을 이어서 한다.
     */
    private fun guideOrganizeFolderPick(titleRes: Int, messageRes: Int, retry: () -> Unit) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.organize_folder_pick) { _, _ ->
                pendingAfterPick = retry
                organizeFolderPicker.launch(null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun startOrganizeFolderImport() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.getOrganizeFolderUri() == null) {
                if (!fragment.isAdded) return@launch
                // 폴더 미지정 — 지정 플로우로 안내하고, 지정이 끝나면 이어서 받아온다.
                guideOrganizeFolderPick(
                    R.string.organize_folder_import, R.string.organize_folder_purpose
                ) { startOrganizeFolderImport() }
                return@launch
            }
            viewModel.scanOrganizeFolder { outcome ->
                if (!fragment.isAdded || fragment.view == null) return@scanOrganizeFolder
                if (!outcome.accessible || outcome.bundle == null) {
                    guideOrganizeFolderPick(
                        R.string.organize_folder_import, R.string.organize_folder_unavailable
                    ) { startOrganizeFolderImport() }
                    return@scanOrganizeFolder
                }
                resolveAmbiguousThenConfirm(outcome.bundle)
            }
        }
    }

    /**
     * 동명 폴더가 있으면 **먼저 물어보고** 그 답으로 계획을 다시 세운 뒤 사전 확인으로 간다.
     *
     * 폴더마다 창을 띄우지 않고 **한 창에 목록으로** 묶는다 — 엑셀 가져오기의 '동명이인 충돌
     * 해결'과 같은 형태다. 동명 그룹이 여러 개일 때 창이 여러 번 뜨는 것이 이 기능에서 가장
     * 흔한 마찰이 된다(원칙 04).
     */
    private fun resolveAmbiguousThenConfirm(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.PlanBundle
    ) {
        val folders = bundle.plan.ambiguousFolders.filter { !bundle.ambiguousCandidates[it].isNullOrEmpty() }
        if (folders.isEmpty()) { confirmOrganizePlan(bundle); return }

        val ctx = fragment.requireContext()
        val dp = ctx.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        container.addView(android.widget.TextView(ctx).apply {
            text = fragment.getString(R.string.organize_folder_ambiguous_message, folders.size)
            setPadding(0, 0, 0, pad / 2)
        })

        // 폴더명 → 고른 캐릭터 id. 고르지 않으면 담기지 않는다(= 배정하지 않음, 종전 동작).
        val picked = HashMap<String, Long>()
        for (folder in folders) {
            container.addView(android.widget.TextView(ctx).apply {
                text = folder
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, pad / 2, 0, 0)
            })
            val group = android.widget.RadioGroup(ctx)
            val candidates = bundle.ambiguousCandidates[folder].orEmpty()
            for (c in candidates) {
                group.addView(android.widget.RadioButton(ctx).apply {
                    id = View.generateViewId()
                    text = fragment.getString(
                        R.string.organize_folder_candidate_format,
                        c.name,
                        c.novelTitle ?: fragment.getString(R.string.duplicate_novel_none)
                    )
                    setOnClickListener { picked[folder] = c.characterId }
                })
            }
            group.addView(android.widget.RadioButton(ctx).apply {
                id = View.generateViewId()
                text = fragment.getString(R.string.organize_folder_ambiguous_skip)
                isChecked = true // 기본은 종전 동작 — 고르지 않으면 아무것도 배정하지 않는다
                setOnClickListener { picked.remove(folder) }
            })
            container.addView(group)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.organize_folder_ambiguous_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(container) })
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (picked.isEmpty()) {
                    confirmOrganizePlan(bundle)
                } else {
                    viewModel.replanOrganizeFolder(bundle.scan, picked) { replanned ->
                        if (fragment.isAdded && fragment.view != null) confirmOrganizePlan(replanned)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 사전 확인 — 폴더 재배열은 대량 메타 변경이라 조용히 반영하지 않는다(조용한 확대 금지). */
    private fun confirmOrganizePlan(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.PlanBundle
    ) {
        val plan = bundle.plan
        if (bundle.isEmpty) {
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.organize_folder_import)
                .setMessage(R.string.organize_folder_nothing)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }
        val lines = ArrayList<String>()
        if (plan.imports.isNotEmpty()) lines.add(fragment.getString(R.string.organize_folder_summary_new, plan.imports.size))
        if (plan.moves.isNotEmpty()) lines.add(fragment.getString(R.string.organize_folder_summary_move, plan.moves.size))
        // 배정 해제와 묶음 해제는 **단위가 다른 두 수**다 — 되돌리는 자리에서는 배정이 없고
        // 묶음만 있는 이미지도 풀리므로, 한 줄에 합치면 어느 쪽이 몇 장인지 알 수 없다.
        val detachCount = plan.detaches.count { it.fromCharacterIds.isNotEmpty() }
        val unlinkCount = plan.detaches.count { it.unlinks }
        if (detachCount > 0) lines.add(fragment.getString(R.string.organize_folder_summary_detach, detachCount))
        if (unlinkCount > 0) lines.add(fragment.getString(R.string.organize_folder_summary_unlink, unlinkCount))
        if (plan.linkSets.isNotEmpty()) lines.add(fragment.getString(R.string.organize_folder_summary_sets, plan.linkSets.size))
        if (bundle.mergedGroups > 0) {
            lines.add(fragment.getString(R.string.organize_folder_summary_merge, bundle.mergedGroups, bundle.mergedOutsiders))
        }
        // '기타' 서랍 — 낱개 편입과 묶음 해제는 단위가 다른 두 수라 줄을 나눈다.
        if (plan.miscImported > 0) {
            lines.add(fragment.getString(R.string.organize_folder_summary_misc, plan.miscImported))
        }
        if (plan.unlinkOnly.isNotEmpty()) {
            lines.add(fragment.getString(R.string.organize_folder_summary_misc_unlink, plan.unlinkOnly.size))
        }
        // 서랍으로 쓰려던 이름이 캐릭터에 먹혔다면 그 사실과 빠져나갈 길을 함께 알린다(D-1).
        plan.miscReadAsCharacter.forEach { name ->
            lines.add(fragment.getString(R.string.organize_folder_summary_misc_is_character, name))
        }
        if (plan.holds.isNotEmpty()) lines.add(fragment.getString(R.string.organize_folder_summary_hold, plan.holds.size))
        if (plan.ambiguousFolders.isNotEmpty()) {
            lines.add(fragment.getString(
                R.string.organize_folder_summary_ambiguous,
                plan.ambiguousFolders.joinToString(", ")
            ))
        }
        if (plan.unknownCodeFolders.isNotEmpty()) {
            lines.add(fragment.getString(
                R.string.organize_folder_summary_unknown_code,
                plan.unknownCodeFolders.joinToString(", ")
            ))
        }
        if (plan.unknownTokenFiles > 0) {
            lines.add(fragment.getString(R.string.organize_folder_summary_unknown_token, plan.unknownTokenFiles))
        }
        if (bundle.scan.skippedByFingerprint > 0) {
            lines.add(fragment.getString(R.string.organize_folder_summary_already, bundle.scan.skippedByFingerprint))
        }
        if (bundle.scan.nonImageIgnored > 0 || plan.deeperIgnored > 0 || bundle.scan.unreadFolders > 0) {
            lines.add(fragment.getString(
                R.string.organize_folder_summary_ignored,
                bundle.scan.nonImageIgnored, plan.deeperIgnored + bundle.scan.unreadFolders
            ))
        }

        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.organize_folder_import)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.organize_folder_apply) { _, _ -> runOrganizePlan(bundle) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runOrganizePlan(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.PlanBundle
    ) {
        val total = bundle.plan.actionCount
        var cancelled = false
        val progress = com.novelcharacter.app.ui.common.TaskProgressDialog.show(
            fragment.requireContext(),
            titleRes = R.string.organize_folder_import,
            total = total,
            stageRes = R.string.organize_folder_stage_apply,
            onCancel = { cancelled = true }
        )
        viewModel.applyOrganizePlan(
            bundle,
            onProgress = { done, t -> progress.update(done, t) },
            isCancelled = { cancelled }
        ) { result ->
            progress.dismiss()
            if (!fragment.isAdded || fragment.view == null) return@applyOrganizePlan
            refreshOrganizeFolderBanner()
            showOrganizeResult(result)
        }
    }

    private fun showOrganizeResult(
        result: com.novelcharacter.app.util.OrganizeFolderService.ApplyResult
    ) {
        val lines = ArrayList<String>()
        lines.add(fragment.getString(
            R.string.organize_folder_result_main,
            result.imported, result.moved, result.detached, result.linkedSets
        ))
        // 링크가 풀린 건 되돌리기 번거로운 변화라 결과에도 숫자로 남긴다(0이면 줄을 만들지 않는다).
        // 본문의 '배정 해제'와 겹치지 않는 별개의 수다 — 묶음만 풀린 항목은 여기에만 잡힌다.
        if (result.unlinked > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_unlinked, result.unlinked))
        }
        // 서랍에 넣었는데 자동 링크라 그대로인 것 — 아무 말도 없으면 "넣었는데 왜 그대로지"가 된다.
        if (result.autoLinkedKept > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_auto_kept, result.autoLinkedKept))
        }
        if (result.cancelled) lines.add(fragment.getString(R.string.organize_folder_result_cancelled))
        if (result.heldNames.isNotEmpty()) {
            lines.add(fragment.getString(
                R.string.organize_folder_result_hold,
                result.heldNames.size, result.heldNames.take(5).joinToString(", ")
            ))
        }
        if (result.failed.isNotEmpty()) {
            lines.add(fragment.getString(
                R.string.organize_folder_result_failed,
                result.failed.size, result.failed.take(5).joinToString(", ")
            ))
        }
        if (result.unmovedOriginals > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_unmoved, result.unmovedOriginals))
        }
        val opResult = if (result.failed.isEmpty()) {
            OpResult.success(OpResult.CAT_MAINTENANCE, lines.first())
        } else {
            OpResult.failure(OpResult.CAT_MAINTENANCE, lines.first())
        }
        fragment.logOperation(opResult)
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.organize_folder_import)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    // ---------- 정리 폴더 왕복 (내보내기) ----------

    fun startOrganizeFolderExport() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.getOrganizeFolderUri() == null) {
                if (!fragment.isAdded) return@launch
                guideOrganizeFolderPick(
                    R.string.organize_folder_export, R.string.organize_folder_purpose
                ) { startOrganizeFolderExport() }
                return@launch
            }
            if (!fragment.isAdded) return@launch
            chooseOrganizeExportScope()
        }
    }

    /** 무엇을 내보낼지 먼저 고른다 — 전부 내보내는 것만이 답은 아니다(자율성). */
    private fun chooseOrganizeExportScope() {
        val scopes = com.novelcharacter.app.util.FolderExportPlanner.Scope.values()
        val labels = arrayOf(
            fragment.getString(R.string.organize_folder_export_scope_all),
            fragment.getString(R.string.organize_folder_export_scope_assigned),
            fragment.getString(R.string.organize_folder_export_scope_unassigned)
        )
        var picked = 0
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.organize_folder_export)
            .setSingleChoiceItems(labels, picked) { _, which -> picked = which }
            .setPositiveButton(R.string.organize_folder_export_next) { _, _ -> planOrganizeExport(scopes[picked]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun planOrganizeExport(scope: com.novelcharacter.app.util.FolderExportPlanner.Scope) {
        viewModel.planOrganizeExport(scope) { outcome ->
            if (!fragment.isAdded || fragment.view == null) return@planOrganizeExport
            val bundle = outcome.bundle
            if (!outcome.accessible || bundle == null) {
                guideOrganizeFolderPick(
                    R.string.organize_folder_export, R.string.organize_folder_unavailable
                ) { startOrganizeFolderExport() }
                return@planOrganizeExport
            }
            confirmOrganizeExport(bundle)
        }
    }

    /**
     * 사전 확인 — 용량과 **이전 사본 정리**를 미리 보인다. 특히 아직 받아오지 않은 배치가
     * 있으면 그것부터 알린다(내보내기가 사용자의 정리 결과를 덮어쓰기 전에).
     */
    private fun confirmOrganizeExport(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.ExportBundle
    ) {
        val plan = bundle.plan
        val lines = ArrayList<String>()
        if (plan.isEmpty) {
            lines.add(fragment.getString(R.string.organize_folder_export_nothing))
        } else {
            lines.add(fragment.getString(
                R.string.organize_folder_export_summary_main,
                plan.files.size,
                com.novelcharacter.app.util.StorageAnalyzer.formatBytes(plan.totalBytes)
            ))
            lines.add(fragment.getString(
                R.string.organize_folder_export_summary_buckets,
                plan.characterFolders, plan.unassignedCount, plan.setFolders, plan.sharedCount
            ))
        }
        if (bundle.cleanup.staleIds.isNotEmpty()) {
            lines.add(fragment.getString(R.string.organize_folder_export_summary_replace, bundle.cleanup.staleIds.size))
        }
        if (bundle.cleanup.rearrangedIds.isNotEmpty()) {
            lines.add(fragment.getString(
                R.string.organize_folder_export_summary_pending, bundle.cleanup.rearrangedIds.size
            ))
        }
        for (blocked in plan.blockedCharacters) {
            lines.add(fragment.getString(
                when (blocked.reason) {
                    com.novelcharacter.app.util.FolderExportPlanner.BlockReason.RESERVED_NAME ->
                        R.string.organize_folder_export_blocked_reserved
                    com.novelcharacter.app.util.FolderExportPlanner.BlockReason.UNSAFE_NAME ->
                        R.string.organize_folder_export_blocked_unsafe
                },
                blocked.name, blocked.imageCount
            ))
        }
        if (plan.legacySkipped > 0) {
            lines.add(fragment.getString(R.string.organize_folder_export_summary_legacy, plan.legacySkipped))
        }
        if (plan.entityOnlySkipped > 0) {
            lines.add(fragment.getString(R.string.organize_folder_export_summary_entity, plan.entityOnlySkipped))
        }
        if (plan.missingSkipped > 0) {
            lines.add(fragment.getString(R.string.organize_folder_export_summary_missing, plan.missingSkipped))
        }
        if (!plan.isEmpty) lines.add(fragment.getString(R.string.organize_folder_export_copy_notice))

        val builder = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.organize_folder_export)
            .setMessage(lines.joinToString("\n"))
            .setNegativeButton(if (plan.isEmpty) R.string.close else R.string.cancel, null)
        if (!plan.isEmpty) {
            builder.setPositiveButton(R.string.organize_folder_export_run) { _, _ ->
                runOrganizeExport(bundle)
            }
            // 아직 받아오지 않은 배치가 있으면 "먼저 받아오기"를 나란히 준다 — 알리기만 하고
            // 되돌릴 길을 안 주면 고지가 아니다(변수 제어).
            if (bundle.cleanup.rearrangedIds.isNotEmpty()) {
                builder.setNeutralButton(R.string.organize_folder_import) { _, _ ->
                    startOrganizeFolderImport()
                }
            }
        }
        builder.show()
    }

    private fun runOrganizeExport(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.ExportBundle
    ) {
        var cancelled = false
        val progress = com.novelcharacter.app.ui.common.TaskProgressDialog.show(
            fragment.requireContext(),
            titleRes = R.string.organize_folder_export,
            total = bundle.workCount,
            stageRes = R.string.organize_folder_export_stage,
            onCancel = { cancelled = true }
        )
        viewModel.runOrganizeExport(
            bundle,
            onProgress = { done, t -> progress.update(done, t) },
            isCancelled = { cancelled }
        ) { result ->
            progress.dismiss()
            if (!fragment.isAdded || fragment.view == null) return@runOrganizeExport
            refreshOrganizeFolderBanner()
            showOrganizeExportResult(result)
        }
    }

    private fun showOrganizeExportResult(
        result: com.novelcharacter.app.util.OrganizeFolderService.ExportResult
    ) {
        val lines = ArrayList<String>()
        lines.add(fragment.getString(
            R.string.organize_folder_export_result_main,
            result.exported, com.novelcharacter.app.util.StorageAnalyzer.formatBytes(result.bytes)
        ))
        if (result.removed > 0) {
            lines.add(fragment.getString(R.string.organize_folder_export_result_removed, result.removed))
        }
        if (result.cancelled) lines.add(fragment.getString(R.string.organize_folder_export_result_cancelled))
        if (result.failed.isNotEmpty()) {
            lines.add(fragment.getString(
                R.string.organize_folder_result_failed,
                result.failed.size, result.failed.take(5).joinToString(", ")
            ))
        }
        if (result.removeFailed > 0) {
            lines.add(fragment.getString(R.string.organize_folder_export_result_remove_failed, result.removeFailed))
        }
        val opResult = if (result.failed.isEmpty() && result.removeFailed == 0) {
            OpResult.success(OpResult.CAT_MAINTENANCE, lines.first())
        } else {
            OpResult.failure(OpResult.CAT_MAINTENANCE, lines.first())
        }
        fragment.logOperation(opResult)
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.organize_folder_export)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.confirm, null)
            .show()
    }
}
