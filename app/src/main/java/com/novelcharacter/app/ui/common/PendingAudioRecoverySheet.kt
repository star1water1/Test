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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A recovery host owns its input/voice ViewModels. No injected callback or guessed domain owner. */
class PendingAudioRecoverySheet : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context=requireContext()
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            val pad=(16*resources.displayMetrics.density).toInt();setPadding(pad,pad,pad,pad)
        }
        fun label(text:String) {panel.addView(TextView(context).apply {this.text=text;setPadding(0,12,0,12)})}
        fun button(text:String,action:()->Unit) {panel.addView(MaterialButton(context,null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {this.text=text;setOnClickListener {action()}})}
        val key=arguments?.getString("key")
        if(key!=null) {
            label("이 녹음의 원래 입력을 확인하고 수정할 수 있습니다. 전사 추가는 이 입력만 바꾸며 캐릭터 필드를 자동 저장하지 않습니다.")
            panel.addView(NaturalLanguageInput.createBound(this,key,"보관한 입력","",{}, {emptyList()}))
            button("보관한 녹음·전사 확인") {VoiceInputSheet.open(this,key,emptyList(),requireArguments().getString("audioId"))}
        } else {
            label("확인하지 않은 녹음과 정리가 필요한 파일입니다. 복구만으로 녹음이나 유료 전사를 시작하지 않습니다.")
            val store=PendingAudio.store(context)
            fun open(item:PendingAudioStore.Item) {
                val current=try {store.read(item.record.id)} catch(_: Exception) {null}
                if(current==null) {label("이 녹음의 보관 상태가 바뀌었습니다. 목록을 다시 열어 확인하세요.");return}
                if(PendingAudioStore.isActive(item.record.id)) {
                    label("다른 창에서 이 녹음을 사용 중입니다. 해당 창에서 마친 뒤 다시 열어 주세요.");return
                }
                PendingAudioRecoverySheet().apply {arguments=Bundle().apply {
                    putString("key",item.record.inputKey);putString("audioId",item.record.id)
                }}.show(parentFragmentManager,"recovered-audio:${item.record.id}")
            }
            lifecycleScope.launch {
                val catalog=try {withContext(Dispatchers.IO) {store.catalog()}} catch(_: Exception) {
                    label("보관한 녹음 목록을 읽지 못했습니다. 파일은 지우지 않았습니다. 저장 공간을 확인한 뒤 다시 열어 주세요.");return@launch
                }
                if(catalog.unreadable>0) label("읽을 수 없는 보관 기록 ${catalog.unreadable}건이 있습니다. 원본 파일은 남겨 두었습니다.")
                if(catalog.items.isEmpty() && catalog.orphans.isEmpty()) label("보관한 녹음이 없습니다.")
                catalog.items.forEach {item ->
                    val r=item.record
                    val state=when(r.phase) {
                        PendingAudioStore.Phase.RECORDING,PendingAudioStore.Phase.INTERRUPTED->"중단된 녹음 · 음성 구간 확인 필요"
                        PendingAudioStore.Phase.READY->"전사 대기"
                        PendingAudioStore.Phase.TRANSCRIBING->"이전 전사 완료 여부 확인 필요"
                        PendingAudioStore.Phase.REVIEW->"전사 확인 대기"
                        PendingAudioStore.Phase.RELEASED->"남은 파일 정리"
                    }
                    val date=java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.createdAt))
                    val status=if(PendingAudioStore.isActive(r.id)) "사용 중" else state
                    button("$date · ${r.durationMs/1000}초 · $status") {open(item)}
                }
                catalog.orphans.forEachIndexed {index,orphan ->
                    button("대상을 확인할 수 없는 녹음 ${index+1}") {
                        MaterialAlertDialogBuilder(context).setTitle("이 녹음을 어떻게 처리하시겠습니까?")
                            .setMessage("기존 캐릭터에 자동 연결하지 않습니다. 읽을 수 있는 녹음은 새 구상으로 복구할 수 있습니다.")
                            .setPositiveButton("새 구상으로 복구") {_,_-> lifecycleScope.launch {
                                try {
                                    val item=withContext(Dispatchers.IO) {store.importOrphan(orphan,PendingAudio.durationMs(store.orphanFile(orphan)))}
                                    open(item)
                                } catch(_: Exception) {label("녹음 복구를 마치지 못했습니다. 원본은 남겨 두었습니다. 목록을 다시 열어 보관 상태를 확인하세요.")}
                            }}
                            .setNeutralButton("파일 버리기") {_,_->
                                MaterialAlertDialogBuilder(context).setTitle("이 녹음 파일을 삭제하시겠습니까?")
                                    .setMessage("이 작업은 되돌릴 수 없습니다.")
                                    .setPositiveButton("삭제") {_,_-> lifecycleScope.launch {
                                        try {withContext(Dispatchers.IO) {store.discardOrphan(orphan)};label("선택한 파일을 삭제했습니다. 목록을 다시 열면 반영됩니다.")}
                                        catch(_: Exception) {label("파일을 삭제하지 못했습니다. 사용 중인 녹음인지 확인하고 다시 시도하세요.")}
                                    }}.setNegativeButton("취소",null).show()
                            }.setNegativeButton("취소",null).show()
                    }
                }
            }
        }
        return MaterialAlertDialogBuilder(context).setTitle("보관한 녹음")
            .setView(cappedScrollView(context).apply {addView(panel)})
            .setNegativeButton("닫기 · 내용 보관",null).create()
    }
    override fun onDismiss(dialog: android.content.DialogInterface) {
        (activity as? com.novelcharacter.app.MainActivity)?.refreshPendingAudio()
        super.onDismiss(dialog)
    }
    companion object {
        const val TAG="pending-audio-recovery"
        fun open(manager: androidx.fragment.app.FragmentManager) {
            if(!manager.isStateSaved && manager.findFragmentByTag(TAG)==null)
                PendingAudioRecoverySheet().show(manager,TAG)
        }
    }
}
