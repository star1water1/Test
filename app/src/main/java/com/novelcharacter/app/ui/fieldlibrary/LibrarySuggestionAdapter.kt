package com.novelcharacter.app.ui.fieldlibrary

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import com.novelcharacter.app.data.model.FieldValueEntry

/**
 * 라이브러리 기반 자동완성 어댑터 — canonical·표시 라벨·별칭 모두에 매칭하고,
 * 별칭으로 매칭된 행은 "별칭 → canonical"로 표시하되 선택 시 canonical을 삽입한다.
 * 항목 순서는 전달된 엔트리 순서(usageCount 내림차순 — DAO 정렬)를 따른다.
 *
 * 폼 자동완성(DynamicFieldFormBuilder)·일괄 편집(BatchFieldValueBottomSheet)·
 * 사건 편집(EventEditDialogFragment)이 공유한다.
 */
class LibrarySuggestionAdapter(
    context: Context,
    entries: List<FieldValueEntry>
) : BaseAdapter(), Filterable {

    private data class Row(val displayText: String, val insertText: String)

    private val inflater = LayoutInflater.from(context)

    private val source: List<FieldValueEntry> = entries.filter { !it.isHidden }
    private var rows: List<Row> = emptyList()

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(
            android.R.layout.simple_dropdown_item_1line, parent, false)
        (view as TextView).text = rows[position].displayText
        return view
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim().orEmpty()
            val matched = mutableListOf<Row>()
            for (e in source) {
                val canonicalHit = query.isEmpty() ||
                    e.value.contains(query, true) ||
                    e.displayLabel.contains(query, true)
                if (canonicalHit) {
                    val display = if (e.displayLabel.isNotBlank() && !e.displayLabel.equals(e.value, true)) {
                        "${e.value} (${e.displayLabel})"
                    } else e.value
                    matched.add(Row(display, e.value))
                    continue
                }
                val aliasHit = e.aliases().firstOrNull { it.contains(query, true) }
                if (aliasHit != null) {
                    matched.add(Row("$aliasHit → ${e.value}", e.value))
                }
            }
            return FilterResults().apply {
                values = matched
                count = matched.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            rows = (results?.values as? List<Row>).orEmpty()
            if (rows.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? Row)?.insertText ?: resultValue?.toString().orEmpty()
    }
}
