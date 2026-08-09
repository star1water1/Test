package com.novelcharacter.app.excel

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import kotlinx.coroutines.launch

/**
 * 내보낼 항목을 고르는 다이얼로그의 **단일 소스**.
 *
 * ## 왜 모았는가
 *
 * 같은 18항목 다이얼로그가 `ExcelTransferController`·`UniverseListFragment`·`NovelListFragment`
 * **세 곳에 그대로 복제**돼 있었다. 한 곳에만 버튼을 달면 나머지 둘이 뒤처지고, 항목이 늘 때
 * 세 곳이 갈라진다 — 이 저장소가 반복해서 값비싸게 배운 그 패턴이다(`architecture` 3장).
 *
 * **가져오기도 같은 창을 쓴다**([showForImport]). 처음 모을 때 내보내기 셋만 보고 가져오기를
 * 빠뜨렸는데, 항목이 같은 18개인 이상 짝이 맞아야 한다 — 한쪽에만 '전부 해제'가 있으면
 * 사용자는 다른 쪽에서 18번을 누르게 된다.
 *
 * ## 전부 선택 / 전부 해제
 *
 * 18개를 하나씩 끄는 것은 실제로 고통스럽다(원칙 04). 중립 버튼 하나로 전부를 뒤집되,
 * **누를 때마다 닫히지 않아야** 하므로 `show()` 이후 버튼을 재바인딩한다 — S-12가 세운
 * 패턴과 같다(기본 동작은 무조건 dismiss라 그대로 두면 한 번 누르고 창이 사라진다).
 *
 * 라벨은 남은 상태에 따라 뒤집는다: 하나라도 켜져 있으면 '전부 해제', 전부 꺼져 있으면
 * '전부 선택'. 지금 누르면 무슨 일이 일어나는지를 버튼이 말해야 한다.
 */
object ExportOptionsDialog {

    /**
     * 내보내기 항목 선택. 확인을 누르면 [onConfirm]에 고른 옵션이 전달된다(취소면 호출 없음).
     */
    fun show(fragment: Fragment, onConfirm: (ExportOptions) -> Unit) {
        if (!fragment.isAdded) return
        show(
            context = fragment.requireContext(),
            titleRes = R.string.export_options_title,
            initial = ExportOptions.ALL.toBooleanArray(),
            onConfirm = { options -> askAiKeyConsent(fragment, options, onConfirm) },
            onCancel = {}
        )
    }

    /**
     * API 키를 함께 실을지 **따로** 묻는다 — 사용자 확정 3번 ㄴ1('기본 제외 + 별도 동의').
     *
     * **묻는 자리를 항목 목록 밖에 둔 이유**는 [ExportOptions.aiKeys]의 주석이 든다:
     * 목록 안에 두면 '전체 선택' 한 번에 켜지고, 그것은 동의가 아니다.
     *
     * **키가 하나도 없으면 묻지 않는다.** 물을 것이 없는데 창을 하나 더 띄우면 그것은
     * 마찰일 뿐이다(원칙 04) — 대부분의 내보내기가 여기 해당한다.
     * '앱 설정'을 껐을 때도 묻지 않는다(시트 자체가 안 나간다).
     */
    private fun askAiKeyConsent(
        fragment: Fragment,
        options: ExportOptions,
        onConfirm: (ExportOptions) -> Unit
    ) {
        if (!options.appSettings) {
            onConfirm(options)
            return
        }
        // *무엇이 비밀인가*는 카탈로그가 안다 — 이 창이 `AiKeyStore`를 직접 뒤지면 비밀이
        // 늘 때 **묻는 쪽과 싣는 쪽이 갈린다**(그러면 동의 없이 새 비밀이 실린다).
        // `lifecycleScope`(뷰가 아니라 프래그먼트의 것)를 쓴다 — `viewLifecycleOwner`는
        // 뷰가 없는 순간에 부르면 던지는데, 이 창은 뷰를 건드리지 않고 다이얼로그만 띄운다.
        fragment.lifecycleScope.launch {
            val hasSecrets = AppSettingsBindings.hasStoredSecrets(fragment.requireContext())
            if (!fragment.isAdded) return@launch
            if (!hasSecrets) {
                onConfirm(options)
                return@launch
            }
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.export_ai_keys_title)
                .setMessage(R.string.export_ai_keys_message)
                // **기본이 '빼고'다** — 긍정 버튼이 안전한 쪽이어야 습관적인 확인이 키를 흘리지 않는다.
                .setPositiveButton(R.string.export_ai_keys_exclude) { _, _ -> onConfirm(options) }
                .setNegativeButton(R.string.export_ai_keys_include) { _, _ -> onConfirm(options.copy(aiKeys = true)) }
                .setCancelable(true)
                // 바깥을 탭해 닫아도 **빼는 쪽**으로 진행한다 — 되돌릴 수 없는 유출에서
                // 침묵이 동의가 되어서는 안 된다(창을 닫았을 뿐인데 키가 나가면 안 된다).
                .setOnCancelListener { onConfirm(options) }
                .show()
        }
    }

    /**
     * 가져오기 항목 선택 — 내보내기와 **같은 창**이되 제목과 초기 선택만 다르다.
     *
     * @param initial 초기 체크 상태. 가져오기는 ZIP에 이미지가 있을 때만 이미지를 켠다.
     * @param onCancel 취소·바깥 탭. 가져오기는 취소를 '중단'으로 다뤄야 해서 성공과 갈린다.
     */
    fun showForImport(
        context: android.content.Context,
        initial: BooleanArray,
        onConfirm: (ExportOptions) -> Unit,
        onCancel: () -> Unit
    ) = show(context, R.string.import_options_title, initial, onConfirm, onCancel)

    private fun show(
        context: android.content.Context,
        titleRes: Int,
        initial: BooleanArray,
        onConfirm: (ExportOptions) -> Unit,
        onCancel: () -> Unit
    ) {
        val checked = initial.copyOf()

        // 라벨 갱신은 show() 이후에야 실제 버튼을 잡을 수 있으므로 홀더로 미뤄 둔다.
        // `setMultiChoiceItems`의 콜백은 **그대로 살려 둔다** — 리스트의 클릭 리스너를
        // 갈아 끼우면 프레임워크가 체크 상태를 반영하는 경로와 싸우게 된다.
        var syncLabel: () -> Unit = {}

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setMultiChoiceItems(ExportOptions.LABELS, checked) { _, which, isChecked ->
                checked[which] = isChecked
                syncLabel()
            }
            .setNeutralButton(R.string.export_options_clear_all, null) // 리스너는 show() 뒤에 단다
            .setPositiveButton(R.string.confirm) { _, _ ->
                onConfirm(ExportOptions.fromBooleanArray(checked))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        dialog.setOnShowListener {
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL) ?: return@setOnShowListener
            syncLabel = {
                neutral.setText(
                    if (checked.any { it }) R.string.export_options_clear_all
                    else R.string.export_options_select_all
                )
            }
            syncLabel()

            // 기본 동작(dismiss)을 덮어쓴다 — 그대로 두면 한 번 누르고 창이 닫힌다(S-12 패턴).
            neutral.setOnClickListener {
                val turnOn = checked.none { it }
                for (i in checked.indices) {
                    checked[i] = turnOn
                    // 리스트의 체크 표시도 함께 움직여야 한다(상태만 바꾸면 화면이 거짓말을 한다).
                    dialog.listView?.setItemChecked(i, turnOn)
                }
                syncLabel()
            }
        }
        dialog.show()
    }
}
