package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.util.DuelFieldLinks

/**
 * 축에 걸 필드를 고르는 목록 (B-104 층 C).
 *
 * **고른 것이 위로 올라오고 그 차례가 곧 영향력 순위다.** 별도의 '순위' 칸을 두지 않는 것은
 * 사용자가 적은 숫자와 목록의 차례가 어긋날 수 있기 때문이다 — 목록 자체가 순위이면 두 자리가
 * 갈릴 수 없다(순서 편집은 드래그·화살표로 한다는 원칙 04의 요구와도 같은 자리).
 *
 * **산출 필드도 같은 목록을 쓴다.** 그쪽은 순위에 뜻이 없지만([rankable]이 false),
 * 고르는 일과 유리한 방향을 정하는 일은 똑같다 — 창을 둘로 만들면 같은 코드가 둘이 된다.
 */
class DuelFieldLinkAdapter(
    private val fields: List<FieldDefinition>,
    initial: List<DuelFieldLinks.Link>,
    /** 순위(위/아래 옮기기)를 보이는가. 영향 필드만 참이다. */
    private val rankable: Boolean,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<DuelFieldLinkAdapter.ViewHolder>() {

    private val byKey: Map<String, FieldDefinition> = fields.associateBy { it.key }

    /** 고른 것 — **차례가 순위다.** 지금 세계관에 없는 키는 버리지 않고 그대로 둔다(아래 설명). */
    private val picked: MutableList<DuelFieldLinks.Link> = initial.toMutableList()

    /**
     * 화면에 뿌릴 줄 — 고른 것이 순위 순으로 먼저, 나머지가 필드 차례로 뒤에.
     *
     * **지금 세계관에 없는 키는 목록에 뜨지 않지만 [picked]에는 남는다.** 엑셀로 들어온 파일이
     * 아직 만들지 않은 필드를 가리킬 수 있고, 그때 이 창을 한 번 열었다는 이유로 그 연결을
     * 조용히 지우면 사용자가 적은 것이 사라진다(개발 의도 2번). [currentLinks]가 그대로 돌려준다.
     */
    private var rows: List<Row> = emptyList()

    /**
     * 한 줄 — 필드 하나와 그 선택 상태.
     *
     * `private`이 아닌 것은 [ViewHolder.bind]가 이것을 받기 때문이다 — 안쪽 클래스의 공개
     * 함수가 바깥 클래스의 private 타입을 노출하면 Kotlin이 거부한다.
     */
    data class Row(val field: FieldDefinition, val rank: Int, val link: DuelFieldLinks.Link?)

    init {
        rebuild()
    }

    private fun rebuild() {
        val pickedRows = picked.mapIndexedNotNull { index, link ->
            byKey[link.key]?.let { Row(it, index + 1, link) }
        }
        val pickedKeys = picked.map { it.key }.toSet()
        val rest = fields.filter { it.key !in pickedKeys }.map { Row(it, 0, null) }
        rows = pickedRows + rest
    }

    /** 지금 고른 것 — 순위 순. */
    fun currentLinks(): List<DuelFieldLinks.Link> = picked.toList()

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_duel_field_link, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(rows[position])

    private fun toggle(key: String, checked: Boolean) {
        if (checked) {
            if (picked.none { it.key == key }) picked.add(DuelFieldLinks.Link(key))
        } else {
            picked.removeAll { it.key == key }
        }
        refresh()
    }

    private fun move(key: String, delta: Int) {
        val from = picked.indexOfFirst { it.key == key }
        if (from < 0) return
        val to = from + delta
        if (to < 0 || to >= picked.size) return
        picked.add(to, picked.removeAt(from))
        refresh()
    }

    private fun flipDirection(key: String) {
        val index = picked.indexOfFirst { it.key == key }
        if (index < 0) return
        val link = picked[index]
        picked[index] = link.copy(higherWins = !link.higherWins)
        refresh()
    }

    private fun refresh() {
        rebuild()
        // 한 줄을 건드리면 순위·차례가 함께 움직이므로 통째로 다시 그린다(수십 줄 규모다).
        notifyDataSetChanged()
        onChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val check: CheckBox = itemView.findViewById(R.id.checkField)
        private val name: TextView = itemView.findViewById(R.id.fieldName)
        private val note: TextView = itemView.findViewById(R.id.fieldNote)
        private val direction: MaterialButton = itemView.findViewById(R.id.btnDirection)
        private val up: MaterialButton = itemView.findViewById(R.id.btnUp)
        private val down: MaterialButton = itemView.findViewById(R.id.btnDown)

        fun bind(row: Row) {
            val context = itemView.context
            val selected = row.link != null

            // 리스너를 먼저 떼고 상태를 놓는다 — 재활용되는 줄에서 setChecked가 옛 리스너를 부른다.
            check.setOnCheckedChangeListener(null)
            check.isChecked = selected
            name.text = if (selected && rankable) {
                context.getString(R.string.duel_links_ranked_name, row.rank, row.field.name)
            } else {
                row.field.name
            }

            val type = FieldType.fromName(row.field.type)
            note.text = if (selected && type != null && !ORDERABLE_TYPES.contains(type)) {
                // 견줄 수 없는 값이라는 사실을 **고른 순간에** 말한다 — 나중에 "왜 대조가 안 되지"로
                // 되돌아오는 것보다 낫다(변수 제어: 조용히 빠뜨리지 않는다).
                context.getString(R.string.duel_links_display_only, type.label)
            } else {
                context.getString(R.string.duel_links_type_note, type?.label ?: row.field.type, row.field.key)
            }

            direction.visibility = if (selected) View.VISIBLE else View.GONE
            up.visibility = if (selected && rankable) View.VISIBLE else View.GONE
            down.visibility = if (selected && rankable) View.VISIBLE else View.GONE

            if (row.link != null) {
                direction.setText(
                    if (row.link.higherWins) R.string.duel_links_higher_wins else R.string.duel_links_lower_wins
                )
                direction.setOnClickListener { flipDirection(row.field.key) }
                up.isEnabled = row.rank > 1
                down.isEnabled = row.rank < picked.size
                up.setOnClickListener { move(row.field.key, -1) }
                down.setOnClickListener { move(row.field.key, 1) }
            }

            check.setOnCheckedChangeListener { _, checked -> toggle(row.field.key, checked) }
            itemView.setOnClickListener { check.isChecked = !check.isChecked }
        }
    }

    companion object {
        /**
         * 크고 작음을 말할 수 있는 타입 — 나머지는 **표시만** 한다.
         *
         * 목록이 아니라 값이 최종 판정자다([DuelFieldLinks.numberOf]가 실제 값을 본다).
         * 여기 든 것은 *"고를 때 미리 알려 주기 위한"* 힌트이며, 글자 필드에 숫자를 적어 두었다면
         * 그것도 견줘진다 — 타입으로 막지 않는 것은 이 앱이 필드의 쓸모를 사용자가 가리게
         * 하기 때문이다(자율성 우선).
         */
        private val ORDERABLE_TYPES = setOf(FieldType.NUMBER, FieldType.CALCULATED, FieldType.TEXT)
    }
}
