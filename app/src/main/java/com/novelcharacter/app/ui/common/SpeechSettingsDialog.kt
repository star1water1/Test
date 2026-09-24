package com.novelcharacter.app.ui.common

import android.os.Build
import android.speech.SpeechRecognizer
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.text.InputType
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.ai.AiProtocol
import com.novelcharacter.app.ai.AiProviderStore
import com.novelcharacter.app.speech.*
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.setValidatedPositiveButton

object SpeechSettingsDialog {
    fun show(fragment: Fragment, onSaved: ()->Unit = {}) {
        val context=fragment.requireContext()
        val store=SpeechSettings(context)
        val saved=store.read()
        val providers=AiProviderStore(context).list().filter {it.protocol==AiProtocol.OPENAI_COMPAT}
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            val pad=(20*resources.displayMetrics.density).toInt();setPadding(pad,pad/2,pad,pad)
        }
        val device=CheckBox(context).apply {
            text="온디바이스 인식 사용"
            isChecked=saved.mode==SpeechMode.ON_DEVICE
            isEnabled=Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            if(!isEnabled) isChecked=false
        }
        panel.addView(device)
        panel.addView(TextView(context).apply {
            text="온디바이스 인식은 지원 기기에서 음성을 기기 안에서 처리합니다. 외부 전사는 아래 제공자로 음성을 보내며 별도로 과금됩니다."
        })
        val provider=Spinner(context).apply {
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,
                listOf("전사 제공자 선택")+providers.map {it.displayName})
            setSelection(providers.indexOfFirst {it.id==saved.providerId}.let {if(it<0) 0 else it+1})
        }
        panel.addView(provider)
        panel.addView(TextView(context).apply {
            text="AI 연동에 등록한 키를 사용합니다. 선택한 서버가 음성 전사를 지원해야 합니다. 텍스트 생성 모델과 전사 모델은 별도로 선택합니다."
        })
        val model=EditText(context).apply {hint="전사 모델";setText(saved.model);setSingleLine(true)}
        panel.addView(model)
        panel.addView(TextView(context).apply {
            text="OpenAI: gpt-transcribe / gpt-4o-transcribe\nGroq: whisper-large-v3 / whisper-large-v3-turbo\n직접 등록한 호환 서버의 전사 모델도 입력할 수 있습니다."
        })
        panel.addView(TextView(context).apply {
            text="전사 단가 (선택). 제공자가 녹음 분당 요금을 청구할 때만 그 단가를 직접 입력하세요. 다른 방식의 요금이면 비워 두세요. 예상 비용은 실제 청구액과 다를 수 있습니다."
        })
        val oldPrice = store.pricePerMinute(saved,
            providers.firstOrNull { it.id == saved.providerId }?.baseUrl)?.toString().orEmpty()
        val price = EditText(context).apply {
            hint="녹음 1분당 단가"; inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(oldPrice); setSingleLine(true)
        }
        panel.addView(price)
        val language=EditText(context).apply {hint="인식 언어 코드 (ko, ja, en · 비우면 자동)";setText(saved.language);setSingleLine(true)}
        panel.addView(language)
        val hints=CheckBox(context).apply {text="현재 문맥의 고유명사를 인식에 참고";isChecked=saved.sendHints}
        panel.addView(hints)
        panel.addView(TextView(context).apply {
            text="켜면 녹음 화면에 표시되는 이름과 용어만 전사에 함께 보냅니다. 전체 메모나 DB는 보내지 않습니다."
        })
        val usage=store.usage()
        if (usage.first > 0) panel.addView(TextView(context).apply {
            text="이전 버전 전사 기록 ${usage.first}건 · 녹음 ${usage.second}초는 날짜·제공자·모델을 구분할 수 없습니다. 새 기록은 AI 연동의 사용량 카드에서 확인하세요."
        })
        val dialog=MaterialAlertDialogBuilder(context).setTitle("음성 입력 설정")
            .setView(cappedScrollView(context).apply {addView(panel)})
            .setPositiveButton("설정 저장",null).setNegativeButton("취소",null).create()
        dialog.setValidatedPositiveButton {
            val picked=providers.getOrNull(provider.selectedItemPosition-1)
            when {
                !device.isChecked && picked==null->{model.error="전사 제공자를 먼저 선택하세요.";false}
                !device.isChecked && (model.text.isNullOrBlank() || SpeechProtocol.endpoint(picked!!.baseUrl)==null)->{
                    model.error=SpeechError.CONFIG.message;false
                }
                !device.isChecked && price.text.isNotBlank() &&
                    (price.text.toString().toDoubleOrNull()?.let { !it.isFinite() || it < 0 } != false) -> {
                    price.error="0 이상의 숫자를 입력하거나 비워 주세요.";false
                }
                !device.isChecked && (picked?.id != saved.providerId || model.text.toString().trim() != saved.model.trim()) &&
                    oldPrice.isNotBlank() && price.text.toString() == oldPrice -> {
                    price.error="제공자나 모델이 바뀌었습니다. 새 단가를 다시 입력하거나 비워 주세요.";false
                }
                else->{
                    val next = SpeechConfig(if(device.isChecked) SpeechMode.ON_DEVICE else SpeechMode.CLOUD,
                        picked?.id.orEmpty(),model.text.toString(),language.text.toString(),hints.isChecked)
                    store.save(next)
                    if (!device.isChecked) store.savePrice(next, picked?.baseUrl,
                        price.text.toString().toDoubleOrNull())
                    onSaved();true
                }
            }
        }
        dialog.show()
    }
}
