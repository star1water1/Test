package com.novelcharacter.app.ui.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterQuote
import com.novelcharacter.app.databinding.ItemCharacterQuoteBinding

/**
 * 명대사 목록 (사용자 요청 2026.08.20).
 *
 * **순서가 데이터라 드래그를 든다**(원칙 04 — *"순서 편집 등 반복 작업은 드래그앤드롭 등
 * 직관적 조작을 우선 제공한다"*). 옮기는 동안에는 목록만 바꾸고 저장은 손을 뗄 때 한 번이다
 * — 한 칸 옮길 때마다 쓰면 열 칸 옮기는 데 열 번의 트랜잭션이 돈다.
 *
 * @property onDragStart 손잡이를 눌렀을 때 [androidx.recyclerview.widget.ItemTouchHelper]에
 *   넘겨 끌기를 시작한다. 화면이 그 helper를 들고 있어 어댑터는 신호만 올린다.
 */
class CharacterQuoteAdapter(
    private val onClick: (CharacterQuote) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<CharacterQuote, CharacterQuoteAdapter.QuoteViewHolder>(DiffCallback()) {

    /**
     * 끌기 중인 **화면상의 차례** — 제출된 목록과 갈릴 수 있다.
     *
     * `ListAdapter`에 곧바로 `submitList`를 하면 DiffUtil이 애니메이션을 다시 돌려 손가락
     * 밑에서 줄이 튄다. 그래서 끄는 동안은 이 벌을 직접 흔들고, 손을 뗄 때 화면이 이것을
     * 읽어 저장한다.
     */
    private var dragging: MutableList<CharacterQuote>? = null

    /** 지금 그리는 목록 — 끌기 중이면 그 벌, 아니면 제출된 것. */
    private fun rows(): List<CharacterQuote> = dragging ?: currentList

    override fun getItemCount(): Int = rows().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuoteViewHolder =
        QuoteViewHolder(
            ItemCharacterQuoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: QuoteViewHolder, position: Int) {
        holder.bind(rows()[position])
    }

    /** 끌기 시작 — 지금 목록을 흔들 수 있는 벌로 복사해 둔다. */
    fun beginDrag() {
        if (dragging == null) dragging = currentList.toMutableList()
    }

    /** 한 칸 옮기기. 화면 차례만 바뀌고 저장은 [endDrag]가 낸 목록으로 한 번에 한다. */
    fun moveRow(from: Int, to: Int) {
        val list = dragging ?: return
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        notifyItemMoved(from, to)
    }

    /**
     * 끌기 끝 — 바뀐 차례를 돌려주고 임시 벌을 놓는다.
     * 아무것도 안 옮겼으면 `null`이라 화면이 헛저장을 하지 않는다.
     */
    fun endDrag(): List<CharacterQuote>? {
        val list = dragging ?: return null
        dragging = null
        return if (list.map { it.id } == currentList.map { it.id }) null else list
    }

    inner class QuoteViewHolder(
        private val binding: ItemCharacterQuoteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(quote: CharacterQuote) {
            val context = binding.root.context
            binding.quoteText.text = quote.text

            // 상황 배지 — **일반은 배지가 없다.** 모든 줄에 '일반'을 달면 정작 눈에 띄어야 할
            // '생일'이 묻힌다(R-17의 역: 없는 것을 굳이 그리지 않는다).
            val badge = when {
                quote.isBirthday -> context.getString(R.string.quote_badge_birthday)
                quote.occasionKey.isNotBlank() -> quote.occasionKey
                else -> null
            }
            binding.quoteOccasionBadge.text = badge.orEmpty()
            binding.quoteOccasionBadge.visibility = if (badge == null) View.GONE else View.VISIBLE

            binding.quoteNote.text = quote.note
            binding.quoteNote.visibility = if (quote.note.isBlank()) View.GONE else View.VISIBLE
            binding.quoteMetaRow.visibility =
                if (badge == null && quote.note.isBlank()) View.GONE else View.VISIBLE

            binding.root.setOnClickListener { onClick(quote) }

            // 손잡이를 **누르는 순간** 끌기가 시작된다 — 길게 눌러야 하면 순서 바꾸기가
            // 마찰이 된다(원칙 04). 목록 몸통의 길게 누르기는 열지 않는다: 대사가 길어
            // 줄이 크고, 스크롤하려다 끌려가기 쉽다.
            binding.quoteDragHandle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragStart(this)
                    // 손잡이는 끌기 전용이라 눌림만 알리고 클릭으로 잇지 않는다.
                    view.performClick()
                }
                false
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<CharacterQuote>() {
        override fun areItemsTheSame(oldItem: CharacterQuote, newItem: CharacterQuote) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: CharacterQuote, newItem: CharacterQuote) =
            oldItem == newItem
    }
}
