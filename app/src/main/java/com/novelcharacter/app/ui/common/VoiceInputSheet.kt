package com.novelcharacter.app.ui.common

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.ai.AiProviderStore
import com.novelcharacter.app.speech.*
import com.novelcharacter.app.util.cappedScrollView

/** Explicit record → transcribe → inspect/edit → append. Generation and domain saving are separate actions. */
class VoiceInputSheet : DialogFragment() {
    private val inputKey get()=requireArguments().getString("key")!!
    private val model by lazy { ViewModelProvider(requireParentFragment()).get("voice:$inputKey",VoiceInputViewModel::class.java) }
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.start(granted) }

    private var viewContext: ReviewViewContext? = null
    private var restoredViewport: com.novelcharacter.app.ai.ReviewPresentation.Viewport? = null
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("viewport", com.google.gson.Gson().toJson(viewContext?.capture()))
        super.onSaveInstanceState(outState)
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context=requireContext()
        restoredViewport = savedInstanceState?.getString("viewport")?.let {
            com.google.gson.Gson().fromJson(it, com.novelcharacter.app.ai.ReviewPresentation.Viewport::class.java)
        } ?: model.viewport
        model.bind(inputKey,requireArguments().getString("audioId"))
        model.terms=requireArguments().getStringArrayList("terms").orEmpty()
        model.omitted=requireArguments().getInt("omitted")
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            val pad=(20*resources.displayMetrics.density).toInt();setPadding(pad,pad/2,pad,pad)
        }
        fun label()=TextView(context).apply {textSize=14f;setTextIsSelectable(true);setPadding(0,12,0,12)}
        fun button(title:String, action:()->Unit)=MaterialButton(context,null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {text=title;setOnClickListener {action()}}
        val scroll=cappedScrollView(context).apply {addView(panel)}
        val viewport=ReviewViewContext(scroll).apply {expanded.addAll(restoredViewport?.expanded.orEmpty())}
        viewContext=viewport
        fun disclosure(key:String,title:String,body:LinearLayout): MaterialButton {
            body.isVisible=key in viewport.expanded
            val toggle=button("") {}
            fun caption() {toggle.text=title+if(body.isVisible) " 접기" else " 펼치기"}
            caption()
            toggle.setOnClickListener {
                body.isVisible=!body.isVisible
                if(body.isVisible) viewport.expanded.add(key) else viewport.expanded.remove(key)
                caption()
            }
            panel.addView(toggle);panel.addView(body)
            return toggle
        }
        val provider=label(); panel.addView(provider)
        val details=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        disclosure("details","전사 설정·참고 용어",details)
        val settings=button("음성 입력 설정") {SpeechSettingsDialog.show(this) {model.refresh()}}
        details.addView(settings)
        val technical=label();details.addView(technical)
        val hints=label();details.addView(hints)
        val status=label();panel.addView(status,0)
        val permissions=button("마이크 권한 설정 열기") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")))
        };panel.addView(permissions)
        val record=button("녹음 시작") {
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) model.start(true)
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
        panel.addView(record)
        val stop=button("녹음 마치고 전사") {model.stop(true)};panel.addView(stop)
        val keep=button("녹음 마치고 보관 · 전사는 나중에") {model.stop(false)};panel.addView(keep)
        val retry=button("현재 녹음 전사 · 외부 요청 1건") {model.transcribe()};panel.addView(retry)
        val cancel=button("전사 중단 · 녹음 보관") {model.cancelTranscription()};panel.addView(cancel)
        val cleanup=button("이미 처리한 녹음의 남은 파일 정리") {model.discard()};panel.addView(cleanup)
        val raw=label()
        val comparison=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;addView(raw)}
        val compare=disclosure("raw","전사 원문 비교",comparison)
        val editor=EditText(context).apply {
            hint="전사 내용을 확인하고 고쳐 주세요";setSingleLine(false);minLines=8;maxLines=16;gravity=android.view.Gravity.TOP;setText(model.session.draft)
        }
        panel.addView(editor)
        viewport.anchor("transcript",editor);viewport.editor("transcript",editor)
        val expand=button("긴 글 넓게 편집") { TranscriptEditorSheet.open(requireParentFragment(),inputKey,requireArguments().getString("audioId"),editor.selectionStart,editor.selectionEnd) }
        panel.addView(expand)
        val corrections=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        val correct=disclosure("corrections","고유명사 교정 후보",corrections)
        var renderedCorrections: Pair<String,List<String>>? = null
        fun renderCorrections() {
            if (!corrections.isVisible) return
            val signature=editor.text.toString() to model.terms.toList()
            if (renderedCorrections==signature) return
            renderedCorrections=signature
            corrections.removeAllViews()
            val proposals=SpeechVocabulary.corrections(editor.text.toString(),model.terms)
            if(proposals.isNotEmpty()) corrections.addView(label().apply {text="고유명사 교정 제안 · 원문과 뜻을 확인하고 필요한 것만 선택하세요."})
            proposals.forEach { proposal ->
                corrections.addView(button("${proposal.heard} → ${proposal.suggested}") {
                    SpeechVocabulary.apply(editor.text.toString(),proposal)?.let {
                        editor.text.replace(proposal.start,proposal.end,proposal.suggested)
                        editor.setSelection(proposal.start,proposal.start+proposal.suggested.length)
                        editor.requestFocus()
                    }
                })
            }
        }
        correct.setOnClickListener {
            corrections.isVisible=!corrections.isVisible
            if(corrections.isVisible) viewport.expanded.add("corrections") else viewport.expanded.remove("corrections")
            correct.text="고유명사 교정 후보"+if(corrections.isVisible) " 접기" else " 펼치기"
            renderCorrections()
        }
        editor.doAfterTextChanged {model.editDraft(it.toString());renderCorrections()}

        val reset=button("전사 원문으로 되돌리기") {editor.setText(model.session.original);editor.setSelection(editor.length())};panel.addView(reset)
        editor.doAfterTextChanged {reset.isVisible=it.toString()!=model.session.original}
        val accept=button("확인한 내용을 입력에 추가") {
            val text=editor.text.toString()
            if(text.isBlank()) editor.error="추가할 내용을 입력하세요."
            else {
                model.editDraft(text)
                if(model.acceptInto(ViewModelProvider(requireParentFragment())[NaturalLanguageInputModel::class.java])) {
                    dismiss()
                }
            }
        };panel.addView(accept)
        val discard=button("녹음과 전사 내용 버리기") {
            MaterialAlertDialogBuilder(context).setTitle("녹음과 전사 내용을 버리시겠습니까?")
                .setMessage("이 녹음과 아직 추가하지 않은 전사를 삭제합니다. 이미 입력에 추가한 내용은 남습니다.")
                .setPositiveButton("버리기") {_,_->model.discard()}.setNegativeButton("취소",null).show()
        };panel.addView(discard)
        panel.addView(label().apply {text="입력에 추가한 뒤 내용을 더 고칠 수 있습니다. AI 생성과 저장은 따로 실행합니다. 외부 전사용 녹음은 확인한 전사를 입력에 추가하거나 직접 버릴 때까지 이 기기에 보관합니다. 전사 실패나 창 닫기로 삭제하지 않습니다. 앱 삭제·데이터 삭제 시에는 지워집니다."})
        model.updates.observe(this) {
            val config=SpeechSettings(context).read()
            val phase=model.session.phase
            val busy=phase==SpeechSession.Phase.RECORDING || phase==SpeechSession.Phase.TRANSCRIBING || model.isDeviceBusy()
            (dialog as? androidx.appcompat.app.AlertDialog)?.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.text=
                if(model.isServiceRecording()) "닫기 · 녹음 계속" else "닫기 · 내용 보관"
            val cloud=config.mode==SpeechMode.CLOUD
            val vocabulary=SpeechVocabulary.select(requireArguments().getStringArrayList("terms").orEmpty()
                .mapIndexed {index,term->SpeechVocabulary.Term(term,index)},if(config.model.trim()=="gpt-transcribe" || !cloud) 1200 else 220)
            model.terms=vocabulary.terms
            model.omitted=requireArguments().getInt("omitted")+vocabulary.omitted
            val selected=AiProviderStore(context).list().firstOrNull {it.id==config.providerId}
            provider.text=if(cloud) "외부 전사: ${selected?.displayName ?: "제공자 미설정"}\n녹음 파일을 선택한 서버에 보내며 전사 비용이 별도로 발생합니다. 재시도도 새 요청입니다."
                else "온디바이스 인식 · 음성을 기기 안에서 처리합니다. 화면을 벗어나면 인식을 마칩니다. 인식기가 제공한 전사만 보관하며 녹음 파일 복구와 장시간 인식은 지원 여부가 확인되지 않았습니다."
            if(cloud) provider.append("\n화면 잠금·앱 전환 중에도 알림의 녹음 서비스를 사용합니다. 최대 약 40분이며 종료 3분 전부터 안내합니다. 서버별 한도·비용은 다를 수 있습니다.")
            if(cloud && (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("voice-recording")?.importance==android.app.NotificationManager.IMPORTANCE_NONE))
                provider.append("\n알림이 꺼져 있어 알림에서 중지할 수 없습니다. 앱의 보관한 녹음 목록을 열어 중지하거나 시스템 설정에서 알림을 켜세요.")
            technical.text=if(cloud) "전사 모델: ${config.model}\n앱 전송 기준 20 MB · 원본 보관은 분당 약 1.9 MB, 전송 파일은 약 0.5 MB를 더 사용합니다." else "기기의 음성 인식 서비스를 사용합니다."
            hints.text=if(!config.sendHints) "고유명사 힌트 전송 꺼짐" else
                "전사에 참고할 용어 ${model.terms.size}개 (범위·길이 제한으로 ${model.omitted}개 제외)\n"+model.terms.joinToString(", ")
            status.text=listOfNotNull(when(phase) {
                SpeechSession.Phase.IDLE->"녹음 준비"
                SpeechSession.Phase.RECORDING->if(model.isServiceRecording()) "저장한 음성 · ${model.seconds}초" else "온디바이스 인식 중 · 경과 ${model.seconds}초"
                SpeechSession.Phase.READY->"녹음 완료 · ${model.seconds}초 · 전사를 실행하세요."
                SpeechSession.Phase.TRANSCRIBING->"전사 중 · 창을 닫아도 현재 편집 화면에서 결과를 보관합니다."
                SpeechSession.Phase.REVIEW->"전사 결과 도착 · 전체 발화가 반영됐는지와 고유명사를 확인하세요. 결과 수신만으로 누락 없음을 확인할 수는 없습니다."
                SpeechSession.Phase.ERROR->model.session.error?.message
            },model.notice.takeIf {it.isNotBlank()}).joinToString("\n")
            settings.isEnabled=!busy
            permissions.isVisible=model.session.error==SpeechError.PERMISSION
            record.isVisible=!busy && !model.hasAudio() && !model.needsCleanup() && model.session.original.isBlank()
            stop.isVisible=phase==SpeechSession.Phase.RECORDING
            keep.isVisible=phase==SpeechSession.Phase.RECORDING && model.isServiceRecording()
            retry.isVisible=!busy && model.hasAudio() && cloud
            retry.text=if(model.session.original.isNotBlank()) "보관한 녹음 다시 전사 · 외부 요청 1건" else "현재 녹음 전사 · 외부 요청 1건"
            cancel.isVisible=phase==SpeechSession.Phase.TRANSCRIBING
            cleanup.isVisible=model.needsCleanup()
            raw.text="전사 원문\n${model.session.original}"
            val hasText=model.session.original.isNotBlank()
            compare.isVisible=hasText;comparison.isVisible=hasText && "raw" in viewport.expanded
            editor.isVisible=hasText;reset.isVisible=hasText && model.session.draft!=model.session.original;accept.isVisible=hasText
            expand.isVisible=hasText;correct.isVisible=hasText;corrections.isVisible=hasText && "corrections" in viewport.expanded
            accept.isEnabled=!busy && !model.needsCleanup();discard.isEnabled=phase!=SpeechSession.Phase.TRANSCRIBING
            if(editor.text.toString()!=model.session.draft) {
                val position=editor.selectionStart.coerceAtLeast(0)
                editor.setText(model.session.draft);editor.setSelection(position.coerceAtMost(editor.length()))
            }
            model.pendingEditorSelection?.let { (start,end) ->
                editor.setSelection(start.coerceIn(0,editor.length()),end.coerceIn(0,editor.length()))
                model.pendingEditorSelection=null
            }
            renderCorrections()
        }
        return MaterialAlertDialogBuilder(context).setTitle("음성으로 입력")
            .setView(scroll)
            .setNegativeButton("닫기 · 내용 보관",null).create()
    }

    override fun onStart() {
        super.onStart();model.refresh()
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        viewContext?.restore(restoredViewport)
    }
    override fun onStop() {
        model.viewport=viewContext?.capture()
        if(activity?.isChangingConfigurations!=true) model.leaveVisibleScreen()
        super.onStop()
    }
    override fun onDestroyView() { model.updates.removeObservers(this); viewContext=null; super.onDestroyView() }
    override fun onDismiss(dialog: android.content.DialogInterface) {
        parentFragment?.takeIf { it.isAdded }?.parentFragmentManager?.setFragmentResult(PendingAudioRecoverySheet.CHANGED, Bundle())
        (activity as? com.novelcharacter.app.MainActivity)?.refreshPendingAudio()
        super.onDismiss(dialog)
    }

    companion object {
        fun open(host:Fragment,key:String,terms:List<SpeechVocabulary.Term>,audioId:String?=null) {
            val tag="voice:$key"
            if(host.childFragmentManager.isStateSaved || host.childFragmentManager.findFragmentByTag(tag)!=null) return
            val selected=SpeechVocabulary.select(terms,byteBudget=1200)
            VoiceInputSheet().apply {
                arguments=Bundle().apply {putString("key",key);putString("audioId",audioId);putStringArrayList("terms",ArrayList(selected.terms));putInt("omitted",selected.omitted)}
            }.showNow(host.childFragmentManager,tag)
        }
    }
}
