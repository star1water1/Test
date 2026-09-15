package com.novelcharacter.app.ui.common

import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import com.novelcharacter.app.ai.ReviewPresentation.Viewport

/** Stable keys survive row insertion and view recreation; live updates never rewrite editor text. */
internal class ReviewViewContext(private val scroll: ScrollView) {
    private val anchors = linkedMapOf<String, View>()
    private val editors = linkedMapOf<String, EditText>()
    val expanded = mutableSetOf<String>()
    fun anchor(key: String, view: View) { anchors[key] = view }
    fun editor(key: String, view: EditText) { editors[key] = view }
    private fun top(view: View): Int {
        var y = view.top
        var parent = view.parent
        while (parent is View && parent !== scroll) {
            y += parent.top - parent.scrollY
            parent = parent.parent
        }
        return y
    }
    fun capture(): Viewport {
        val anchor = anchors.entries.lastOrNull { it.value.isShown && top(it.value) <= scroll.scrollY }
            ?: anchors.entries.firstOrNull { it.value.isShown }
        val focus = editors.entries.firstOrNull { it.value.hasFocus() && it.value.isShown }
        return Viewport(anchor?.key, anchor?.let { top(it.value) - scroll.scrollY } ?: 0,
            scroll.scrollY, focus?.key, focus?.value?.selectionStart ?: 0,
            focus?.value?.selectionEnd ?: 0, expanded.toSet())
    }
    private var restoration = 0
    private var pendingReveal: String? = null
    fun cancelRestore() { restoration++; pendingReveal=null }
    fun revealEditor(key: String) {
        cancelRestore()
        val editor=editors[key] ?: return
        pendingReveal=key
        val token=restoration
        scroll.doOnLayout {
            scroll.post {
                if (!scroll.isAttachedToWindow || token!=restoration) return@post
                editor.requestRectangleOnScreen(android.graphics.Rect(0,0,editor.width,
                    editor.height.coerceAtMost(scroll.height)),true)
                pendingReveal=null
            }
        }
    }
    fun restore(value: Viewport?, focus: Boolean = true) {
        if (value == null || pendingReveal != null) return
        val token = ++restoration
        scroll.doOnLayout {
            if (!scroll.isAttachedToWindow || token != restoration) return@doOnLayout
            if (focus) editors[value.focus]?.takeIf { it.isShown }?.let {
                it.requestFocus()
                it.setSelection(value.start.coerceIn(0, it.length()), value.end.coerceIn(0, it.length()))
            }
            scroll.post {
                if (!scroll.isAttachedToWindow || token != restoration) return@post
                val target = anchors[value.anchor]?.takeIf { it.isShown }?.let { top(it) - value.offset } ?: value.scroll
                scroll.scrollTo(0, target.coerceAtLeast(0))
            }
        }
    }
}
