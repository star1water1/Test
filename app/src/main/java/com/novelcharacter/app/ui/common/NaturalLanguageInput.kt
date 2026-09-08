package com.novelcharacter.app.ui.common

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.novelcharacter.app.speech.SpeechVocabulary
import kotlinx.coroutines.launch

class NaturalLanguageInputModel : ViewModel() {
    val values=mutableMapOf<String,String>()
    val updates=MutableLiveData<String>()
    val pending=mutableSetOf<String>()
    fun accept(key: String, transcript: String) {
        val old=values[key].orEmpty()
        values[key]=if(old.isBlank()) transcript else old.trimEnd()+"\n"+transcript
        pending.add(key)
        updates.value=key
    }
}

/** Text/voice entry shared across creative features; never starts generation or writes domain data. */
object NaturalLanguageInput {
    fun create(fragment: Fragment, key: String, hint: String, initial: String,
        onChanged: (String)->Unit,
        terms: suspend ()->List<SpeechVocabulary.Term> = {emptyList()}): LinearLayout {
        val context=fragment.requireContext()
        val model=ViewModelProvider(fragment)[NaturalLanguageInputModel::class.java]
        if(key !in model.pending) model.values[key]=initial
        val editor=EditText(context).apply {
            this.hint=hint; setSingleLine(false);minLines=3
            setText(model.values[key])
            doAfterTextChanged { model.values[key]=it.toString();onChanged(it.toString()) }
        }
        onChanged(model.values[key].orEmpty())
        model.pending.remove(key)
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
