package com.novelcharacter.app.ui.common

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.novelcharacter.app.ai.*
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import com.novelcharacter.app.data.model.FieldType

class ReviewUiTestModel : ViewModel() {
    val state=FieldSuggestionReviewState()
    val running=MutableLiveData(false)
    val progress=MutableLiveData(0 to 1)
    val specs=(1..12).map {FieldSpec("$it","항목 $it",FieldType.TEXT,emptyList(),false,null,"")}
    var outcome=SuggestOutcome(specs.map {Suggestion(it.key,"처음 값 ${it.key}","이유")},0,emptyList(),emptyList(),0,0)
    var refinements=0
}
class ReviewUiTestFragment : Fragment() {
    override fun onCreateView(inflater:android.view.LayoutInflater,container:ViewGroup?,state:Bundle?)=FrameLayout(requireContext())
}
/** Debug test host only; no application navigation, microphone or paid transport. */
class ReviewUiTestActivity : FragmentActivity() {
    val model get()=ViewModelProvider(this)[ReviewUiTestModel::class.java]
    val host get()=supportFragmentManager.findFragmentByTag("host")!!
    lateinit var review: AlertDialog
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        setContentView(FrameLayout(this).apply {id=12345})
        if(state==null) supportFragmentManager.beginTransaction().add(12345,ReviewUiTestFragment(),"host").commitNow()
    }
    override fun onResume() {super.onResume();showReview()}
    fun showReview(): AlertDialog = FieldSuggestionReviewDialog.show(host,model.specs,model.outcome,model.state,"수신 12개",
        onApply={false},onClose={},onRefine={_,_->model.refinements++;true},running=model.running,
        liveSpecs={model.specs},progress=model.progress).also {review=it}
}

