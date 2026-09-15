package com.novelcharacter.app.ui.common

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.speech.PendingAudio
import com.novelcharacter.app.speech.PendingAudioStore
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.setValidatedPositiveButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recovery and explicit file cleanup never guess a domain owner or remove input text. */
class PendingAudioRecoverySheet : DialogFragment() {
    private var reload: (() -> Unit)? = null
    private var loading: Job? = null
    internal var confirmation: androidx.appcompat.app.AlertDialog? = null
        private set

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String) { panel.addView(TextView(context).apply { this.text = text; setPadding(0,12,0,12) }) }
        fun button(text: String, description: String = text, action: () -> Unit) {
            panel.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = text; contentDescription = description; setOnClickListener { action() }
            })
        }
        val key = arguments?.getString("key")
        if (key != null) {
            label("이 녹음의 원래 입력을 확인하고 수정할 수 있습니다. 전사 추가는 이 입력만 바꾸며 캐릭터 필드를 자동 저장하지 않습니다.")
            panel.addView(NaturalLanguageInput.createBound(this,key,"보관한 입력","",{}, {emptyList()}))
            button("보관한 녹음·전사 확인") { VoiceInputSheet.open(this,key,emptyList(),requireArguments().getString("audioId")) }
        } else {
            val store = PendingAudio.store(context)
            fun open(item: PendingAudioStore.Item) {
                if (parentFragmentManager.isStateSaved) return
                val current = try { store.read(item.record.id) } catch (_: Exception) { null }
                if (current == null) { reload?.invoke(); return }
                if (PendingAudioStore.isActive(item.record.id) &&
                    com.novelcharacter.app.speech.VoiceRecordingService.state?.let { it.id == item.record.id && it.running } != true) {
                    label("다른 창에서 이 녹음을 사용 중입니다. 해당 창에서 마친 뒤 다시 열어 주세요."); return
                }
                val tag = "recovered-audio:${item.record.id}"
                if (parentFragmentManager.findFragmentByTag(tag) != null) return
                PendingAudioRecoverySheet().apply { arguments = Bundle().apply {
                    putString("key",current.record.inputKey); putString("audioId",current.record.id)
                } }.showNow(parentFragmentManager,tag)
            }
            fun confirm(title: String, message: String, action: () -> Unit) {
                if (confirmation?.isShowing == true) return
                val prompt = MaterialAlertDialogBuilder(context).setTitle(title).setMessage(message)
                    .setPositiveButton("삭제",null).setNegativeButton("취소",null).create()
                confirmation = prompt
                prompt.setValidatedPositiveButton {
                    prompt.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = false
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { action() }
                            prompt.dismiss()
                            reload?.invoke()
                            (activity as? com.novelcharacter.app.MainActivity)?.refreshPendingAudio()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            prompt.setMessage("정리하지 못했습니다. 다른 창에서 사용 중이거나 보관 상태가 바뀌었을 수 있습니다. 창을 닫고 목록을 새로고침한 뒤 다시 확인하세요.")
                            reload?.invoke()
                            (activity as? com.novelcharacter.app.MainActivity)?.refreshPendingAudio()
                        } finally { prompt.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = true }
                    }
                    false
                }
                prompt.show()
            }
            reload = {
                loading?.cancel()
                loading = lifecycleScope.launch {
                    val catalog = try { withContext(Dispatchers.IO) { store.catalog() } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        panel.removeAllViews()
                        label("보관한 녹음 목록을 읽지 못했습니다. 파일은 지우지 않았습니다. 저장 공간을 확인한 뒤 다시 시도하세요.")
                        button("목록 새로고침") { reload?.invoke() }
                        return@launch
                    }
                    panel.removeAllViews()
                    label("필요 없는 녹음 파일은 여기서 삭제할 수 있습니다. 이미 입력한 글과 음성 편집창에 별도로 보관된 전사 글은 남습니다. 복구만으로 녹음이나 유료 전사를 시작하지 않습니다.")
                    button("목록 새로고침") { reload?.invoke() }
                    if (catalog.items.isEmpty() && catalog.orphans.isEmpty() && catalog.unreadable == 0) label("보관한 녹음이 없습니다.")
                    catalog.items.forEach { item ->
                        val r = item.record
                        val state = when (r.phase) {
                            PendingAudioStore.Phase.RECORDING,PendingAudioStore.Phase.INTERRUPTED -> "중단된 녹음 · 음성 구간 확인 필요"
                            PendingAudioStore.Phase.CAPTURED -> "녹음 보관 완료 · 전사 대기"
                            PendingAudioStore.Phase.READY -> "전사 대기"
                            PendingAudioStore.Phase.TRANSCRIBING -> "이전 전사 완료 여부 확인 필요"
                            PendingAudioStore.Phase.REVIEW -> "전사 확인 대기"
                            PendingAudioStore.Phase.RELEASED -> "남은 파일 정리"
                        }
                        val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.createdAt))
                        val status = if (PendingAudioStore.isActive(r.id)) "사용 중" else state
                        val summary = "$date · ${r.durationMs / 1000}초 · $status"
                        button(summary) { open(item) }
                        button("녹음 파일 삭제", "$summary · 녹음 파일 삭제") {
                            confirm("이 녹음 파일을 삭제하시겠습니까?", "$summary\n\n녹음 파일과 이 녹음의 복구 기록을 삭제하며 되돌릴 수 없습니다. 이미 입력한 글과 음성 편집창에 별도로 보관된 전사 글은 남습니다. 사용 중인 녹음은 먼저 마쳐 주세요.") { store.discard(item) }
                        }
                    }
                    catalog.damaged.forEachIndexed { index, record ->
                        val title = "읽지 못하는 보관 기록 ${index + 1}"
                        label(title)
                        button("읽지 못하는 기록 정리", "$title 정리") {
                            confirm("이 보관 기록을 삭제하시겠습니까?", "읽지 못하는 기록만 삭제하며 되돌릴 수 없습니다. 입력한 글은 남습니다. 연결을 확인할 수 없는 녹음 파일은 삭제하지 않으며 아래 목록에서 따로 확인할 수 있습니다. 녹음·전사 중에는 정리할 수 없습니다.") { store.discardDamaged(record) }
                        }
                    }
                    catalog.orphans.forEachIndexed { index, orphan ->
                        val title = "대상을 확인할 수 없는 녹음 ${index + 1}"
                        label(title)
                        button("새 구상으로 복구", "$title · 새 구상으로 복구") {
                            lifecycleScope.launch {
                                try {
                                    val item = withContext(Dispatchers.IO) { store.importOrphan(orphan,PendingAudio.durationMs(store.orphanFile(orphan))) }
                                    reload?.invoke(); open(item)
                                    (activity as? com.novelcharacter.app.MainActivity)?.refreshPendingAudio()
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { label("녹음 복구를 마치지 못했습니다. 원본은 남겨 두었습니다. 목록을 새로고침해 보관 상태를 확인하세요.") }
                            }
                        }
                        button("녹음 파일 삭제", "$title · 녹음 파일 삭제") {
                            confirm("이 녹음 파일을 삭제하시겠습니까?", "$title\n\n이 파일은 복구할 수 없습니다. 입력한 글은 남습니다.") { store.discardOrphan(orphan) }
                        }
                    }
                }
            }
            parentFragmentManager.setFragmentResultListener(CHANGED,this) { _,_ -> reload?.invoke() }
        }
        return MaterialAlertDialogBuilder(context).setTitle("보관한 녹음")
            .setView(cappedScrollView(context).apply { addView(panel) })
            .setNegativeButton("닫기 · 내용 보관",null).create()
    }
    override fun onStart() { super.onStart(); reload?.invoke() }
    override fun onDestroyView() {
        loading?.cancel(); loading = null; reload = null
        confirmation?.dismiss(); confirmation = null
        super.onDestroyView()
    }
    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (isAdded) parentFragmentManager.setFragmentResult(CHANGED,Bundle())
        (activity as? com.novelcharacter.app.MainActivity)?.refreshPendingAudio()
        super.onDismiss(dialog)
    }
    companion object {
        const val TAG = "pending-audio-recovery"
        const val CHANGED = "pending-audio-changed"
        fun open(manager: androidx.fragment.app.FragmentManager) {
            if (!manager.isStateSaved && manager.findFragmentByTag(TAG) == null)
                PendingAudioRecoverySheet().showNow(manager,TAG)
        }
    }
}
