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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context=requireContext()
        model.bind(inputKey)
        model.terms=requireArguments().getStringArrayList("terms").orEmpty()
        model.omitted=requireArguments().getInt("omitted")
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            val pad=(20*resources.displayMetrics.density).toInt();setPadding(pad,pad/2,pad,pad)
        }
        fun label()=TextView(context).apply {textSize=14f;setTextIsSelectable(true);setPadding(0,12,0,12)}
        fun button(title:String, action:()->Unit)=MaterialButton(context,null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {text=title;setOnClickListener {action()}}
        val provider=label(); panel.addView(provider)
        val settings=button("음성 입력 설정") {SpeechSettingsDialog.show(this) {model.refresh()}}
        panel.addView(settings)
        val hints=label();panel.addView(hints)
        val status=label();panel.addView(status)
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
        val retry=button("현재 녹음 전사 · 외부 요청 1건") {model.transcribe()};panel.addView(retry)
        val raw=label();panel.addView(raw)
        val editor=EditText(context).apply {
            hint="전사 내용을 확인하고 고쳐 주세요";setSingleLine(false);minLines=4;setText(model.session.draft)
        }
        panel.addView(editor)
        val corrections=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL};panel.addView(corrections)
        fun renderCorrections() {
            corrections.removeAllViews()
            val proposals=SpeechVocabulary.corrections(editor.text.toString(),model.terms)
            if(proposals.isNotEmpty()) corrections.addView(label().apply {text="고유명사 교정 제안 · 원문과 뜻을 확인하고 필요한 것만 선택하세요."})
            proposals.forEach { proposal ->
                corrections.addView(button("${proposal.heard} → ${proposal.suggested}") {
                    SpeechVocabulary.apply(editor.text.toString(),proposal)?.let {editor.setText(it)}
                })
            }
        }
        editor.doAfterTextChanged {model.editDraft(it.toString());renderCorrections()}
        val reset=button("전사 원문으로 되돌리기") {editor.setText(model.session.original)};panel.addView(reset)
        val accept=button("확인한 내용을 입력에 추가") {
            val text=editor.text.toString()
            if(text.isBlank()) editor.error="추가할 내용을 입력하세요."
            else {
                if(ViewModelProvider(requireParentFragment())[NaturalLanguageInputModel::class.java].accept(inputKey,text)) {
                    model.discard();dismiss()
                }
            }
        };panel.addView(accept)
        val discard=button("녹음과 전사 내용 버리기") {model.discard()};panel.addView(discard)
        panel.addView(label().apply {text="입력에 추가한 뒤 내용을 더 고칠 수 있습니다. AI 생성과 저장은 각 화면에서 따로 실행합니다. 임시 녹음은 전사 성공·버리기·편집 화면 종료 때 삭제합니다. 실패한 녹음은 재시도를 위해 현재 편집 화면에만 보관합니다. 받은 전사 원문과 수정 내용은 이 기기에 보관하며 같은 입력의 마이크를 다시 열면 복구합니다. 앱 삭제·데이터 삭제 시에는 지워집니다."})
        model.updates.observe(this) {
            val config=SpeechSettings(context).read()
            val phase=model.session.phase
            val busy=phase==SpeechSession.Phase.RECORDING || phase==SpeechSession.Phase.TRANSCRIBING
            val cloud=config.mode==SpeechMode.CLOUD
            val vocabulary=SpeechVocabulary.select(requireArguments().getStringArrayList("terms").orEmpty()
                .mapIndexed {index,term->SpeechVocabulary.Term(term,index)},if(config.model.trim()=="gpt-transcribe" || !cloud) 1200 else 220)
            model.terms=vocabulary.terms
            model.omitted=requireArguments().getInt("omitted")+vocabulary.omitted
            val selected=AiProviderStore(context).list().firstOrNull {it.id==config.providerId}
            provider.text=if(cloud) "외부 전사: ${selected?.displayName ?: "제공자 미설정"} · ${config.model}\n녹음 파일을 선택한 서버에 보내며 전사 비용이 별도로 발생합니다. 재시도도 새 요청입니다."
                else "온디바이스 인식 · 음성을 기기 안에서 처리합니다."
            hints.text=if(!config.sendHints) "고유명사 힌트 전송 꺼짐" else
                "전사에 참고할 용어 ${model.terms.size}개 (범위·길이 제한으로 ${model.omitted}개 제외)\n"+model.terms.joinToString(", ")
            status.text=listOfNotNull(when(phase) {
                SpeechSession.Phase.IDLE->"녹음 준비"
                SpeechSession.Phase.RECORDING->"녹음 중 · ${model.seconds}초"
                SpeechSession.Phase.READY->"녹음 완료 · ${model.seconds}초 · 전사를 실행하세요."
                SpeechSession.Phase.TRANSCRIBING->"전사 중 · 창을 닫아도 현재 편집 화면에서 결과를 보관합니다."
                SpeechSession.Phase.REVIEW->"전사 완료 · 원문과 고유명사를 확인하세요."
                SpeechSession.Phase.ERROR->model.session.error?.message
            },model.notice.takeIf {it.isNotBlank()}).joinToString("\n")
            settings.isEnabled=!busy
            permissions.isVisible=model.session.error==SpeechError.PERMISSION
            record.isVisible=!busy && !model.hasAudio() && model.session.original.isBlank()
            stop.isVisible=phase==SpeechSession.Phase.RECORDING
            retry.isVisible=!busy && model.hasAudio() && cloud
            raw.text="전사 원문\n${model.session.original}"
            val hasText=model.session.original.isNotBlank()
            raw.isVisible=hasText;editor.isVisible=hasText;reset.isVisible=hasText;accept.isVisible=hasText
            accept.isEnabled=!busy;discard.isEnabled=phase!=SpeechSession.Phase.TRANSCRIBING
            if(editor.text.toString()!=model.session.draft) editor.setText(model.session.draft)
            renderCorrections()
        }
        return MaterialAlertDialogBuilder(context).setTitle("음성으로 입력")
            .setView(cappedScrollView(context).apply {addView(panel)})
            .setNegativeButton("닫기 · 내용 보관",null).create()
    }

    override fun onStop() {
        if(activity?.isChangingConfigurations!=true && model.session.phase==SpeechSession.Phase.RECORDING) model.stop(false)
        super.onStop()
    }

    companion object {
        fun open(host:Fragment,key:String,terms:List<SpeechVocabulary.Term>) {
            val tag="voice:$key"
            if(host.childFragmentManager.isStateSaved || host.childFragmentManager.findFragmentByTag(tag)!=null) return
            val selected=SpeechVocabulary.select(terms,byteBudget=1200)
            VoiceInputSheet().apply {
                arguments=Bundle().apply {putString("key",key);putStringArrayList("terms",ArrayList(selected.terms));putInt("omitted",selected.omitted)}
            }.showNow(host.childFragmentManager,tag)
        }
    }
}
