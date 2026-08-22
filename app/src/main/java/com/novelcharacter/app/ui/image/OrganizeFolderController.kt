package com.novelcharacter.app.ui.image

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.notifyError
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.notifySuccess
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
        // **여기서 스스로 건다 — 호스트가 빠뜨릴 수 있는 배선이었다.**
        // 이미지 탭은 `onViewCreated`에서 걸어 회전을 넘기지만(B-136), 설정 화면은 이 컨트롤러를
        // 세워 놓고 그 호출을 하지 않았다. 그래서 그 경로로 받아오면 **AI 태그 요청은 결제되고
        // 검토 시트는 영영 뜨지 않았다**(결과가 아무도 안 보는 LiveData에 앉는다). 관측을
        // 시작 시점에도 걸어 두면 호스트가 무엇을 기억하든 결과가 닿는다 — 회전 복구를 위한
        // `onViewCreated` 호출은 그대로 필요하고, 위 겹걸기 가드가 둘을 함께 성립시킨다.
        observeFolderTagRun()
        observeOrganizeResult()
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
            // 후보 라디오 묶음이 모호한 폴더 수 × 동명 캐릭터 수만큼 늘어난다(B-91).
            .setView(cappedScrollView(ctx).apply { addView(container) })
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
        val lines = ArrayList<String>()
        if (plan.imports.isNotEmpty()) lines.add(fragment.getString(R.string.organize_folder_summary_new, plan.imports.size))
        if (plan.moves.isNotEmpty()) lines.add(fragment.getString(R.string.organize_folder_summary_move, plan.moves.size))
        // 배정 해제와 묶음 해제는 **단위가 다른 두 수**다 — 되돌리는 자리에서는 배정이 없고
        // 묶음만 있는 이미지도 풀리므로, 한 줄에 합치면 어느 쪽이 몇 장인지 알 수 없다.
        val detachCount = plan.detaches.count { it.fromCharacterIds.isNotEmpty() }
        val unlinkCount = plan.detaches.count { it.unlinks }
        // '살림'(D5) — 배정도 묶음도 없이 표식만 있던 것은 위 두 수에 안 잡힌다.
        val reviveCount = plan.detaches.count { it.hadDetachedMark && !it.keepsDetachedMark }
        if (detachCount > 0) lines.add(fragment.getString(R.string.organize_folder_summary_detach, detachCount))
        if (reviveCount > 0) lines.add(fragment.getString(R.string.organize_folder_summary_revive, reviveCount))
        if (unlinkCount > 0) lines.add(fragment.getString(R.string.organize_folder_summary_unlink, unlinkCount))
        // `_삭제승인/` — 이 창에서 **유일하게 되돌릴 수 없는** 처분이다(B-107 D6).
        // 그래서 개수만 말하지 않고 결과를 함께 말한다: 공유본인가 · 대표인가 · 원본은 어디 남는가.
        // 확정 ⓑ가 앱 안 되돌리기를 없앴으므로 마지막 안전망이 폴더 쪽에 있다는 사실이 특히 중요하다.
        if (plan.deletes.isNotEmpty()) {
            lines.add(fragment.getString(R.string.organize_folder_summary_delete, plan.deletes.size))
            val sharedDeletes = plan.deletes.count { it.ownerCharacterIds.size >= 2 }
            if (sharedDeletes > 0) {
                lines.add(fragment.getString(R.string.organize_folder_summary_delete_shared, sharedDeletes))
            }
            if (bundle.deleteRepresentativeOf.isNotEmpty()) {
                lines.add(fragment.getString(
                    R.string.organize_folder_summary_delete_representative,
                    bundle.deleteRepresentativeOf.joinToString(", ")
                ))
            }
            lines.add(fragment.getString(R.string.organize_folder_summary_delete_originals))
        }
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
        if (plan.deleteApprovalUnknown > 0) {
            lines.add(fragment.getString(
                R.string.organize_folder_summary_delete_unknown, plan.deleteApprovalUnknown
            ))
        }
        // 예약을 쓰려다 빗나간 폴더 — 이름째 말해야 사용자가 고칠 수 있다(그 폴더는 이번에
        // 아무 일도 하지 않으므로, 말하지 않으면 조용히 무시한 것이 된다).
        if (plan.unknownReservedFolders.isNotEmpty()) {
            lines.add(fragment.getString(
                R.string.organize_folder_summary_unknown_reserved,
                plan.unknownReservedFolders.joinToString(", ")
            ))
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

        // **반영할 것이 없어도 고지는 그대로 낸다.** 종전에는 여기서 일찍 빠져나가
        // "반영할 것을 찾지 못했습니다" 한 줄만 띄웠는데, `plan.isEmpty`는 *행동*이 없다는 뜻이지
        // *말할 것*이 없다는 뜻이 아니다 — 보류(공유본·같은 토큰 두 위치)·동명 폴더·미지 코드·
        // 예약 오타·무시한 파일이 전부 그 창에서 사라졌다. 폴더에 넣은 것이 통째로 보류되는
        // 경우가 실제로 흔한데(`_공유/`에 넣으면 전부 보류다), 그때 사용자는 앱이 폴더를
        // 못 읽은 것으로 이해한다. 줄 만드는 자리를 하나로 두고 **버튼만 가른다.**
        // **바꿀 것은 없지만 정리할 것은 있는 경우** — 이미 제자리에 있는 파일을 `_처리됨/`으로
        // 옮겨 "이 파일은 봤다"를 확정한다. 이것이 없으면 그 파일이 폴더에 영영 남아 진입 배너가
        // 매번 같은 수를 말한다. **묻지 않고 옮기지는 않는다** — 사용자 폴더를 비우는 일이라
        // 결과를 먼저 말하고 [적용]을 받는다(R-4).
        if (plan.settled.isNotEmpty()) {
            lines.add(fragment.getString(R.string.organize_folder_summary_settled, plan.settled.size))
        }
        if (bundle.isEmpty && plan.settled.isEmpty()) {
            val head = fragment.getString(R.string.organize_folder_nothing)
            val body = if (lines.isEmpty()) head else head + "\n\n" + lines.joinToString("\n")
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.organize_folder_import)
                .setView(cappedScrollView(fragment.requireContext()).apply {
                    addView(android.widget.TextView(context).apply {
                        text = body
                        setPadding(64, 16, 64, 0)
                    })
                })
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }

        // AI 태그 제안 체크박스 — **미설정이면 줄 자체를 감춘다**(R-24: 성립하지 않는 조합의
        // 설정은 보이지 않는다). 체크 상태는 기억하지 않는다: 비용이 드는 동작의 기본값은
        // 꺼짐이어야 하고, 폴더 구성은 매번 다르다.
        val tagFolders = plan.aiTagFolders.keys.toList()
        val aiUsable = tagFolders.isNotEmpty() &&
            runCatching { com.novelcharacter.app.ai.AiService(fragment.requireContext()).hasUsableProvider() }
                .getOrDefault(false)

        val builder = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.organize_folder_import)
            .setNegativeButton(R.string.cancel, null)

        if (aiUsable) {
            val requests = com.novelcharacter.app.ai.AiPromptPolicy.imageTagRequestCount(tagFolders.size)
            val box = com.google.android.material.checkbox.MaterialCheckBox(fragment.requireContext()).apply {
                text = fragment.getString(R.string.image_tag_review_option)
                isChecked = false
            }
            val container = android.widget.LinearLayout(fragment.requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(64, 16, 64, 0)
                addView(android.widget.TextView(context).apply { text = lines.joinToString("\n") })
                addView(box)
                addView(android.widget.TextView(context).apply {
                    text = fragment.getString(
                        R.string.image_tag_review_option_desc, tagFolders.size, requests
                    )
                    textSize = 12f
                })
            }
            // 요약 줄은 '서랍 이름이 캐릭터에 먹힌 것'마다 한 줄씩 늘고, 모호·미상 폴더 이름은
            // 한 줄에 전부 이어 붙는다 — 폴더가 많으면 화면을 넘는다(B-91).
            builder.setView(cappedScrollView(fragment.requireContext()).apply { addView(container) })
                .setPositiveButton(R.string.organize_folder_apply) { _, _ ->
                    runOrganizePlan(bundle, if (box.isChecked) tagFolders else emptyList())
                }
        } else {
            builder.setMessage(lines.joinToString("\n"))
                .setPositiveButton(R.string.organize_folder_apply) { _, _ -> runOrganizePlan(bundle, emptyList()) }
        }
        builder.show()
    }

    private fun runOrganizePlan(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.PlanBundle,
        aiTagFolders: List<String>
    ) {
        val total = bundle.plan.actionCount
        val cancelled = AtomicBoolean(false)   // 메인이 쓰고 IO가 읽는다 — 평범한 var는 happens-before가 없어 취소가 안 보일 수 있다
        val progress = com.novelcharacter.app.ui.common.TaskProgressDialog.show(
            fragment.requireContext(),
            titleRes = R.string.organize_folder_import,
            total = total,
            stageRes = R.string.organize_folder_stage_apply,
            onCancel = { cancelled.set(true) }
        )
        // 결과 창·AI 제안은 **관측이 낸다**(아래 [observeOrganizeResult]) — 이 콜백은 화면이
        // 죽으면 오지 않지만 결과는 ViewModel에 남으므로, 다시 선 화면이 그것을 받아 띄운다.
        // 여기서는 진행 창만 닫는다.
        viewModel.applyOrganizePlan(
            bundle,
            aiTagFolders,
            onProgress = { done, t -> progress.update(done, t) },
            isCancelled = { cancelled.get() }
        ) { progress.dismiss() }
    }

    /**
     * 받아오기 결과 관측 — **회전을 넘기는 배선**. 폴더 태그 결과와 같은 모양이다(B-136).
     *
     * 종전에는 결과가 콜백으로만 왔고 그 콜백은 `isAdded`에서 조용히 빠져나갔다. 그래서 반영
     * 도중 회전하면 결과 창도 조작 로그도 없이 끝났다 — `_삭제승인/`이 지운 이미지까지 무기록이다.
     */
    fun observeOrganizeResult() {
        val owner = fragment.viewLifecycleOwner
        if (observedResultOwner === owner) return
        observedResultOwner = owner
        viewModel.organizeResult.observe(owner) { outcome ->
            if (outcome == null) return@observe
            viewModel.clearOrganizeResult()
            refreshOrganizeFolderBanner()
            showOrganizeResult(outcome.result)
            // 무통보 무시 금지 — 받아오기는 끝났는데 태그 제안만 조용히 빠지면
            // 사용자는 그 폴더에 제안할 것이 없었다고 잘못 배운다.
            if (outcome.aiTagSkipped) {
                fragment.notifyError(fragment.getString(R.string.image_ai_tag_already_running))
            }
        }
    }

    private var observedResultOwner: androidx.lifecycle.LifecycleOwner? = null
    private var observedExportOwner: androidx.lifecycle.LifecycleOwner? = null

    /**
     * 폴더 태그 제안 결과 관측 — **회전을 넘기는 배선** (B-136). 화면이 붙을 때 한 번 부른다.
     *
     * 종전에는 결과가 이 컨트롤러의 지역 변수에만 살아, 검토 중 회전하면 되살아난 시트가
     * 빈 껍데기였고 이미 결제한 응답이 사라졌다.
     */
    fun observeFolderTagRun() {
        val owner = fragment.viewLifecycleOwner
        // **뷰 수명주기 단위로 한 번만 건다.** 같은 뷰에 두 번 걸면 시트가 두 번 서고,
        // 뷰가 새로 서면(회전·재부착) 새 주인에게 다시 걸어야 결과가 닿는다.
        if (observedTagOwner === owner) return
        observedTagOwner = owner
        viewModel.folderTagResult.observe(owner) { outcome ->
            if (outcome != null) showFolderTagReview(outcome)
        }
    }

    /** [observeFolderTagRun]을 이미 건 뷰 수명주기의 주인. 겹걸기·빠뜨림을 함께 막는다. */
    private var observedTagOwner: androidx.lifecycle.LifecycleOwner? = null

    private fun showFolderTagReview(outcome: ImageManagerViewModel.TagSuggestOutcome) {
        val notices = ArrayList<String>()
        val d = outcome.result.drops
        val dropped = d.unknownFolder + d.blankOrTooLong + d.overPerFolderCap
        if (dropped > 0) notices.add(fragment.getString(R.string.image_tag_review_notice_dropped, dropped))
        if (d.vocabTruncated > 0) notices.add(fragment.getString(R.string.image_tag_review_notice_vocab, d.vocabTruncated))
        if (d.policyTruncated > 0) notices.add(fragment.getString(R.string.image_tag_review_notice_policy, d.policyTruncated))
        // 링크 묶음 전개 고지 — 적용은 살아 있는 명단으로 묶음 전원에 붙으므로(공유 불변식 —
        // ViewModel.applyTagWork), 폴더 안 경로가 물고 오는 **폴더 밖 식구 수**를 적용 전에
        // 말한다(변수 제어 — 조용한 확대 금지. 이미지판 검토 시트의 묶음 고지와 같은 자리).
        // 제안이 있어야만 싣는다 — 이미지판이 같은 조건을 다는 근거(B-144)와 같다.
        if (outcome.result.suggestions.isNotEmpty()) {
            val folderPaths = outcome.pathsByFolder.values.flatten()
            val addedByLink = viewModel.expandWithLinkedGroups(folderPaths).addedByLink.size
            if (addedByLink > 0) {
                notices.add(fragment.getString(R.string.image_folder_tag_link_notice, addedByLink))
            }
        }
        // 프로바이더 자동 전환 고지 (B-108 확정 ⓑ) — 실패가 아니므로 실패 요약보다 앞에 둔다.
        notices.addAll(outcome.result.notes)
        if (outcome.result.failures.isNotEmpty()) {
            val reason = com.novelcharacter.app.ai.AiErrorMessages.of(
                fragment.requireContext(), outcome.result.failures.first()
            )
            notices.add(fragment.getString(R.string.image_tag_review_notice_failed, reason))
        }
        // 제안도 없고 할 말도 없으면 시트를 띄우지 않는다 — 빈 창은 그 자체가 소음이다.
        if (outcome.result.suggestions.isEmpty() && notices.isEmpty()) {
            viewModel.clearFolderTagResult()
            return
        }

        // 이미 떠 있으면(회전으로 되살아난 시트) 새로 만들지 않고 다시 먹인다 — 새로 만들면
        // 사용자의 체크가 날아가고 창이 겹친다.
        // `isAdded` — 이미지판과 같은 이유다(B-163). 적용 실패로 되살아나는 경로에서는
        // 시트가 닫히는 중이라 태그로는 찾히지만 다시 먹여도 화면에 닿지 않는다.
        val existing = (fragment.childFragmentManager
            .findFragmentByTag(ImageFolderTagReviewSheet.TAG) as? ImageFolderTagReviewSheet)
            ?.takeIf { it.isAdded }
        val sheet = existing ?: ImageFolderTagReviewSheet()
        // 비우는 일은 여기서 하지 않는다 — 실패 시 되살릴 것이 남아야 한다(R-38 · B-163).
        // 이미지판과 같은 규칙이다 — 문장·이력은 ViewModel이 들고 이 람다는 *"화면이 알렸는가"*만
        // 돌려준다(B-164). 두 자리가 같은 모양이라야 한쪽만 고쳐지는 일이 없다.
        sheet.onApply = { picked ->
            viewModel.applyFolderTags(picked, outcome.pathsByFolder) { result ->
                fragment.notifyResult(result)
            }
        }
        sheet.onDismissed = { viewModel.clearFolderTagResult() }
        if (existing != null) {
            sheet.rebind(outcome.result.suggestions, notices)
        } else {
            sheet.suggestions = outcome.result.suggestions
            sheet.notices = notices
            sheet.show(fragment.childFragmentManager, ImageFolderTagReviewSheet.TAG)
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
        // 되돌릴 수 없는 처분은 결과에서도 수로 남긴다 — 확인창이 약속한 수와 같아야 한다.
        if (result.deleted > 0) {
            lines.add(fragment.getString(
                R.string.organize_folder_result_deleted,
                result.deleted,
                com.novelcharacter.app.util.StorageAnalyzer.formatBytes(result.deletedBytes)
            ))
        }
        // 서랍에 넣었는데 자동 링크라 그대로인 것 — 아무 말도 없으면 "넣었는데 왜 그대로지"가 된다.
        if (result.settledArchived > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_settled, result.settledArchived))
        }
        if (result.markCleared > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_revive, result.markCleared))
        }
        if (result.autoLinkedKept > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_auto_kept, result.autoLinkedKept))
        }
        // 묶음이 쪼개져 혼자 남은 것 — 어시스턴트 카드에 뜰 수와 같다. 여기서 미리 알려야
        // 사용자가 "왜 갑자기 카드가 생겼지"를 겪지 않는다.
        if (result.scattered > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_scattered, result.scattered))
        }
        if (result.cancelled) lines.add(fragment.getString(R.string.organize_folder_result_cancelled))
        // 중단은 **취소와 다르다** — 사용자가 멈춘 것이 아니라 오류로 끊긴 것이고, 그 앞까지는
        // 이미 반영됐다. 위 수치가 그 반영분이므로 "아무 일도 없었다"로 읽히지 않게 사유를 붙인다.
        result.abortedReason?.let {
            lines.add(fragment.getString(R.string.organize_folder_result_aborted, it))
        }
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
        // 묶기만 실패한 세트 — 본문의 '링크 세트 N개'가 조용히 줄어 있으면 사용자가 자기가
        // 만든 폴더 묶음이 어디 갔는지 알 방법이 없다. 파일 실패 목록과 단위가 달라 줄을 나눈다.
        if (result.linkSetFailed > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_link_failed, result.linkSetFailed))
        }
        if (result.unmovedOriginals > 0) {
            lines.add(fragment.getString(R.string.organize_folder_result_unmoved, result.unmovedOriginals))
        }
        val opResult = if (result.failed.isEmpty() && result.abortedReason == null) {
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
        // 받아오기와 같은 이유로 여기서도 스스로 건다(호스트가 빠뜨릴 수 있는 배선).
        observeExportResult()
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
            fragment.getString(R.string.organize_folder_export_scope_unassigned),
            fragment.getString(R.string.organize_folder_export_scope_detached)
        )
        // 라벨 순서는 `Scope` 선언 순서와 **같아야 한다** — `scopes[picked]`가 그 짝을 믿는다.
        require(labels.size == scopes.size) { "Scope와 라벨 수가 어긋났습니다" }
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
            // `_분리됨/`도 센다 — 갈라 놓은 서랍을 요약이 말하지 않으면, 「뗀 것만」 범위로
            // 내보낼 때 **모든 칸이 0인 요약**이 뜬다(장수는 맞는데 어디로 가는지가 안 보인다).
            lines.add(fragment.getString(
                R.string.organize_folder_export_summary_buckets,
                plan.characterFolders, plan.unassignedCount, plan.setFolders, plan.sharedCount,
                plan.detachedCount
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
        val cancelled = AtomicBoolean(false)   // 메인이 쓰고 IO가 읽는다 — 평범한 var는 happens-before가 없어 취소가 안 보일 수 있다
        val progress = com.novelcharacter.app.ui.common.TaskProgressDialog.show(
            fragment.requireContext(),
            titleRes = R.string.organize_folder_export,
            total = bundle.workCount,
            stageRes = R.string.organize_folder_export_stage,
            onCancel = { cancelled.set(true) }
        )
        observeExportResult()
        viewModel.runOrganizeExport(
            bundle,
            onProgress = { done, t -> progress.update(done, t) },
            isCancelled = { cancelled.get() }
        ) { progress.dismiss() }
    }

    /** 내보내기 결과 관측 — 받아오기와 같은 배선(회전을 넘긴다). */
    fun observeExportResult() {
        val owner = fragment.viewLifecycleOwner
        if (observedExportOwner === owner) return
        observedExportOwner = owner
        viewModel.exportResult.observe(owner) { result ->
            if (result == null) return@observe
            viewModel.clearExportResult()
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
