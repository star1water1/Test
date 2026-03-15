package com.novelcharacter.app.ui.adapter

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.ui.search.SearchResultItem

class GlobalSearchAdapter(
    private val onCharacterClick: (Character) -> Unit,
    private val onEventClick: (TimelineEvent) -> Unit,
    private val onNovelClick: (Novel) -> Unit
) : ListAdapter<SearchResultItem, RecyclerView.ViewHolder>(SearchDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHARACTER = 1
        private const val TYPE_EVENT = 2
        private const val TYPE_NOVEL = 3
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SearchResultItem.SectionHeader -> TYPE_HEADER
            is SearchResultItem.CharacterResult -> TYPE_CHARACTER
            is SearchResultItem.EventResult -> TYPE_EVENT
            is SearchResultItem.NovelResult -> TYPE_NOVEL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_search_header, parent, false))
            else -> ResultViewHolder(inflater.inflate(R.layout.item_search_result, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchResultItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is SearchResultItem.CharacterResult -> (holder as ResultViewHolder).bindCharacter(item.character)
            is SearchResultItem.EventResult -> (holder as ResultViewHolder).bindEvent(item.event)
            is SearchResultItem.NovelResult -> (holder as ResultViewHolder).bindNovel(item.novel)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.sectionTitle)
        fun bind(header: SearchResultItem.SectionHeader) {
            titleText.text = header.title
        }
    }

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val mainText: TextView = itemView.findViewById(R.id.resultMainText)
        private val subText: TextView = itemView.findViewById(R.id.resultSubText)
        private val typeIndicator: TextView = itemView.findViewById(R.id.resultTypeIndicator)

        private fun setTypeBadgeColor(colorResId: Int) {
            val bg = typeIndicator.background?.mutate()
            if (bg is GradientDrawable) {
                bg.setColor(itemView.context.getColor(colorResId))
                typeIndicator.background = bg
            } else {
                val drawable = GradientDrawable().apply {
                    cornerRadius = 4f * itemView.context.resources.displayMetrics.density
                    setColor(itemView.context.getColor(colorResId))
                }
                typeIndicator.background = drawable
            }
        }

        fun bindCharacter(character: Character) {
            mainText.text = character.name
            subText.text = ""
            subText.visibility = View.GONE
            typeIndicator.text = itemView.context.getString(R.string.search_type_character)
            setTypeBadgeColor(R.color.search_type_character)
            itemView.setOnClickListener { onCharacterClick(character) }
        }

        fun bindEvent(event: TimelineEvent) {
            mainText.text = event.description
            subText.text = "${event.year}년"
            subText.visibility = View.VISIBLE
            typeIndicator.text = itemView.context.getString(R.string.search_type_event)
            setTypeBadgeColor(R.color.search_type_event)
            itemView.setOnClickListener { onEventClick(event) }
        }

        fun bindNovel(novel: Novel) {
            mainText.text = novel.title
            subText.text = novel.description.take(50)
            subText.visibility = if (novel.description.isNotBlank()) View.VISIBLE else View.GONE
            typeIndicator.text = itemView.context.getString(R.string.search_type_novel)
            setTypeBadgeColor(R.color.search_type_novel)
            itemView.setOnClickListener { onNovelClick(novel) }
        }
    }

    class SearchDiffCallback : DiffUtil.ItemCallback<SearchResultItem>() {
        override fun areItemsTheSame(oldItem: SearchResultItem, newItem: SearchResultItem): Boolean {
            return when {
                oldItem is SearchResultItem.SectionHeader && newItem is SearchResultItem.SectionHeader -> oldItem.title == newItem.title
                oldItem is SearchResultItem.CharacterResult && newItem is SearchResultItem.CharacterResult -> oldItem.character.id == newItem.character.id
                oldItem is SearchResultItem.EventResult && newItem is SearchResultItem.EventResult -> oldItem.event.id == newItem.event.id
                oldItem is SearchResultItem.NovelResult && newItem is SearchResultItem.NovelResult -> oldItem.novel.id == newItem.novel.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: SearchResultItem, newItem: SearchResultItem) = oldItem == newItem
    }
}
