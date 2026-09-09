package com.novelcharacter.app.ui.common

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.novelcharacter.app.speech.SpeechVocabulary
import com.novelcharacter.app.ai.CreativeInputState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class NaturalLanguageInputModel(application: android.app.Application, private val savedState: SavedStateHandle) :
    androidx.lifecycle.AndroidViewModel(application) {
    private val slots=mutableMapOf<String,ReviewSlot<CreativeInputState.Snapshot>>()
    private val states=mutableMapOf<String,CreativeInputState>()
    // The domain still has one unsaved character slot. Creator Briefs have their own identities;
    // a fresh editor explicitly chooses an old brief instead of inheriting briefing:-1 silently.
    fun resolvedKey(key: String): String? = if(key=="briefing:-1") savedState["newCharacterBrief"] else key
    fun chooseNewCharacterBrief(key: String): Boolean {
        if(key!="briefing:-1" && !key.startsWith("briefing:draft:")) return false
        if (!prepare(key,"")) return false
        savedState["newCharacterBrief"]=key
        return true
    }
    data class SavedBrief(val key: String, val label: String)
    fun savedNewCharacterBriefs(): List<SavedBrief> {
        val slot=ReviewSlot(getApplication(),CreativeInputState.Snapshot::class.java)
        return slot.savedKeys("creative-input:briefing:")
            .map { it.removePrefix("creative-input:") }
            .filter { it=="briefing:-1" || it.startsWith("briefing:draft:") }
            .map { key ->
                val text=slot.read("creative-input:$key")?.text.orEmpty()
                val preview=text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
                    ?: "입력 전 구상 · 보관한 전사 확인"
                SavedBrief(key, if(key=="briefing:-1") "이전 버전 · $preview" else preview)
            }
    }
    fun prepare(key: String, initial: String): Boolean {
        if(key !in slots) {
            val slot=ReviewSlot(getApplication(),CreativeInputState.Snapshot::class.java)
            val saved=slot.read("creative-input:$key")
            if(slot.failed) return false
            val state=try { CreativeInputState(key,saved,initial) } catch(_: IllegalArgumentException) {
                Toast.makeText(getApplication(),"보관한 입력의 대상이 일치하지 않습니다. 기존 내용은 지우지 않았습니다.",Toast.LENGTH_LONG).show()
                return false
            }
            // Persist a blank brief as well: a voice session can exist before any text arrives.
            if(!slot.save(state.snapshot)) return false
            slots[key]=slot
            states[key]=state
            // This input's checkpoint may be newer than the enclosing review snapshot
            // (e.g. a transcript arrived immediately before process death).
            values[key]=state.snapshot.text
            if(saved!=null) {
                if(saved.pending) pending.add(key)
            }
        }
        return true
    }
    fun edit(key: String, text: String) {
        values[key]=text; pending.remove(key)
        states[key]?.edit(text) { slots.getValue(key).save(it) }
    }
    val values=mutableMapOf<String,String>()
    val updates=MutableLiveData<String>()
    val pending=mutableSetOf<String>()
    fun accept(delivery: CreativeInputState.Delivery): Boolean {
        val key=delivery.inputKey
        if(!prepare(key,"")) return false
        val state=states.getValue(key)
        val slot=slots.getValue(key)
        // Reuse the journal revision check/lease even for an already accepted delivery.
        // An unchanged snapshot shortcut alone cannot detect another editor's newer record.
        if(!slot.beginRequest()) return false
        try {
            val text=values[key].orEmpty()
            if(text!=state.snapshot.text && !state.edit(text) { slot.save(it) }) return false
            if(!state.accept(delivery) { slot.save(it) }) return false
            values[key]=state.snapshot.text
            pending.add(key)
            updates.value=key
            return true
        } finally { slot.endRequest() }
    }
}

/** Text/voice entry shared across creative features; never starts generation or writes domain data. */
object NaturalLanguageInput {
    fun create(fragment: Fragment, key: String, hint: String, initial: String,
        onChanged: (String)->Unit,
        terms: suspend ()->List<SpeechVocabulary.Term> = {emptyList()}): LinearLayout {
        val context=fragment.requireContext()
        val model=ViewModelProvider(fragment)[NaturalLanguageInputModel::class.java]
        if(key=="briefing:-1") {
            val holder=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
            val input=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
            holder.addView(input)
            fun open(inputKey: String) {
                if(!model.chooseNewCharacterBrief(inputKey)) return
                input.removeAllViews()
                input.addView(createBound(fragment,inputKey,hint,"",{ text ->
                    // Old views can still receive lifecycle updates after choosing another brief.
                    if(model.resolvedKey(key)==inputKey) onChanged(text)
                },terms))
            }
            open(model.resolvedKey(key) ?: "briefing:draft:${java.util.UUID.randomUUID()}")
            holder.addView(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text="다른 구상·보관한 전사 열기"
                setOnClickListener {
                    val keys=model.savedNewCharacterBriefs()
                    MaterialAlertDialogBuilder(context).setTitle("캐릭터 구상 선택")
                        .setItems((listOf("새 구상 시작")+keys.map { it.label }).toTypedArray()) { _,index ->
                            if(index==0) open("briefing:draft:${java.util.UUID.randomUUID()}")
                            else {
                                val candidate=keys[index-1].key
                                if(model.prepare(candidate,"")) {
                                    MaterialAlertDialogBuilder(context).setTitle("이 구상을 이어서 사용하시겠습니까?")
                                        .setMessage(model.values[candidate].orEmpty().ifBlank {
                                            "입력된 글이 없습니다. 마이크를 열면 보관된 전사를 확인할 수 있습니다."
                                        })
                                        .setPositiveButton("이 구상 사용") { _,_->open(candidate) }
                                        .setNegativeButton("취소",null).show()
                                }
                            }
                        }.setNegativeButton("취소",null).show()
                }
            })
            return holder
        }
        return createBound(fragment,key,hint,initial,onChanged,terms)
    }

    internal fun createBound(fragment: Fragment, key: String, hint: String, initial: String,
        onChanged: (String)->Unit, terms: suspend ()->List<SpeechVocabulary.Term>): LinearLayout {
        val context=fragment.requireContext()
        val model=ViewModelProvider(fragment)[NaturalLanguageInputModel::class.java]
        if(!model.prepare(key,initial)) return LinearLayout(context).apply {
            addView(android.widget.TextView(context).apply {text="입력 내용을 복구하지 못했습니다. 창을 다시 열어 주세요."})
        }
        val editor=EditText(context).apply {
            this.hint=hint; setSingleLine(false);minLines=3
            setText(model.values[key])
            doAfterTextChanged { onChanged(it.toString());model.edit(key,it.toString()) }
        }
        onChanged(model.values[key].orEmpty())
        model.edit(key,model.values[key].orEmpty())
        val owner=fragment.viewLifecycleOwnerLiveData.value ?: fragment
        model.updates.observe(owner) { changed->
            if(changed==key && editor.text.toString()!=model.values[key]) {
                editor.setText(model.values[key]);model.pending.remove(key)
            }
        }
        return LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            addView(editor)
            addView(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text="마이크로 입력"
                setOnClickListener {
                    isEnabled=false
                    fragment.lifecycleScope.launch {
                        try { VoiceInputSheet.open(fragment,key,terms()) }
                        catch(e: kotlinx.coroutines.CancellationException) {throw e}
                        catch(_: Exception) {Toast.makeText(context,"음성 입력 자료를 준비하지 못했습니다. 다시 시도하세요.",Toast.LENGTH_LONG).show()}
                        finally {isEnabled=true}
                    }
                }
            })
        }
    }
}
