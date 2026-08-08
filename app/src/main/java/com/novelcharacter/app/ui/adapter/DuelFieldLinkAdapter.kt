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
 * **산출·프로필 필드도 같은 목록을 쓴다.** 그쪽은 순위에 뜻이 없고([rankable]이 false)
 * 프로필은 유리한 방향에도 뜻이 없지만([directional]이 false), **고르는 일은 똑같다** —
 * 창을 셋으로 만들면 같은 코드가 셋이 된다.
 *
 * **줄이 `FieldDefinition`이 아니라 [Choice]인 것은 B-167 때문이다.** 이명 같은 시스템 열은
 * `FieldDefinition` 행이 아예 없어(`Character`의 열이다) 그 타입으로는 목록에 낼 수가 없었다.
 * 가짜 `FieldDefinition`을 지어 끼우는 길도 있었지만, 그 가짜가 저장 경로로 새어 나가면
 * **없는 필드가 DB에 생긴다** — 줄이 무엇인지를 한 겹 갈라 두는 편이 값이 싸다.
 */
class DuelFieldLinkAdapter(
    private val choices: List<Choice>,
    initial: List<DuelFieldLinks.Link>,
    /** 순위(위/아래 옮기기)를 보이는가. 영향 필드만 참이다. */
    private val rankable: Boolean,
    private val onChanged: () -> Unit,
    /** 유리한 방향(높을수록/낮을수록)을 고를 수 있는가. 견주지 않는 프로필 필드는 거짓이다. */
    private val directional: Boolean = true,
    /**
     * **고를 수 없는 필드와 그 사유** (R-24 — 성립하지 않는 조합은 막고 이유를 말한다).
     *
     * 지금 쓰는 곳은 프로필 고르기이고, 막히는 것은 **산출 필드**다: 그 값이 곧 이 대결이
     * 물으려는 답이라 프로필 경로로 카드에 올라오면 물음과 답이 한 화면에 놓인다.
     * 감추지 않고 **비활성 + 사유**로 두는 것은, 목록에서 지우면 사용자가 *"내가 만든 필드가
     * 왜 없지"*로 되돌아오기 때문이다.
     */
    private val blockedKeys: Map<String, String> = emptyMap()
) : RecyclerView.Adapter<DuelFieldLinkAdapter.ViewHolder>() {

    /**
     * 고를 수 있는 것 한 줄 — 커스텀 필드이거나 시스템 열이다(B-167).
     *
     * @property key 연결에 실리는 키. 커스텀 필드는 `FieldDefinition.key`,
     *   시스템 열은 `sys:` 의사키다([com.novelcharacter.app.util.DuelSystemFields]).
     * @property label 사람이 읽는 이름.
     * @property typeLabel 안내 줄에 뜨는 부류 — 타입 이름이거나 *"시스템 열"*이다.
     * @property orderable 크고 작음을 말할 수 있는 부류인가. **힌트일 뿐이고 최종 판정자는
     *   값이다**([com.novelcharacter.app.util.DuelFieldLinks.numberOf]가 실제 값을 본다) —
     *   거짓이면 고른 순간에 *"차례를 매길 수 없다"*를 말해 줄 뿐 고르기를 막지는 않는다.
     *   **모르는 타입은 참이다**([choiceOf]) — 모르는 것을 두고 *매길 수 없다*고 단언할
     *   근거가 없고, 그 단언은 값이 실제로 수로 읽히면 거짓말이 된다.
     */
    data class Choice(
        val key: String,
        val label: String,
        val typeLabel: String,
        val orderable: Boolean
    )

    private val byKey: Map<String, Choice> = choices.associateBy { it.key }

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
    data class Row(val choice: Choice, val rank: Int, val link: DuelFieldLinks.Link?)

    init {
        rebuild()
    }

    private fun rebuild() {
        val pickedRows = picked.mapIndexedNotNull { index, link ->
            byKey[link.key]?.let { Row(it, index + 1, link) }
        }
        val pickedKeys = picked.map { it.key }.toSet()
        val rest = choices.filter { it.key !in pickedKeys }.map { Row(it, 0, null) }
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
        // **막힌 것은 새로 고를 수 없지만 빼는 것은 언제나 된다.** 막기만 하고 빼는 길을
        // 닫으면, 엑셀로 들어온 잘못된 연결을 이 창에서 바로잡을 수 없다 — 검증 → 알림 →
        // **바로잡을 경로**의 마지막이 빠진 꼴이다(개발 의도 2번).
        if (checked && key in blockedKeys) return
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
            val blockedReason = blockedKeys[row.choice.key]
            val blocked = blockedReason != null

            // 리스너를 먼저 떼고 상태를 놓는다 — 재활용되는 줄에서 setChecked가 옛 리스너를 부른다.
            check.setOnCheckedChangeListener(null)
            check.isChecked = selected
            name.text = if (selected && rankable) {
                context.getString(R.string.duel_links_ranked_name, row.rank, row.choice.label)
            } else {
                row.choice.label
            }

            note.text = when {
                // 막혔는데 이미 골라져 있으면 **빼는 법**을 말한다 — 사유만 적어 두면
                // 사용자는 그 줄을 어떻게 없애는지 알 수 없다.
                blockedReason != null && selected ->
                    context.getString(R.string.duel_links_profile_blocked_picked)
                // 막힌 사유가 다른 무엇보다 앞선다 — 고를 수 없는 줄에서 타입 안내는 소음이다.
                blockedReason != null -> blockedReason
                // 견줄 수 없는 값이라는 사실을 **고른 순간에** 말한다 — 나중에 "왜 대조가 안 되지"로
                // 되돌아오는 것보다 낫다(변수 제어: 조용히 빠뜨리지 않는다).
                selected && directional && !row.choice.orderable ->
                    context.getString(R.string.duel_links_display_only, row.choice.typeLabel)
                // 키를 함께 말하는 것은 **엑셀에서 손으로 적을 때 쓰라고**다 — 시스템 열의
                // `sys:` 의사키를 사용자가 알 다른 길이 없다(B-167).
                else ->
                    context.getString(R.string.duel_links_type_note, row.choice.typeLabel, row.choice.key)
            }

            // **막혔어도 이미 골라져 있으면 끌 수는 있다** — 그것이 바로잡는 유일한 길이다
            // (엑셀로 들어온 연결은 이 창을 지나지 않고 들어온다). 새로 고르는 것만 막힌다.
            val interactive = !blocked || selected
            check.isEnabled = interactive
            name.isEnabled = interactive
            direction.visibility = if (selected && directional && !blocked) View.VISIBLE else View.GONE
            up.visibility = if (selected && rankable && !blocked) View.VISIBLE else View.GONE
            down.visibility = if (selected && rankable && !blocked) View.VISIBLE else View.GONE

            if (row.link != null && !blocked) {
                direction.setText(
                    if (row.link.higherWins) R.string.duel_links_higher_wins else R.string.duel_links_lower_wins
                )
                direction.setOnClickListener { flipDirection(row.choice.key) }
                up.isEnabled = row.rank > 1
                down.isEnabled = row.rank < picked.size
                up.setOnClickListener { move(row.choice.key, -1) }
                down.setOnClickListener { move(row.choice.key, 1) }
            }

            if (interactive) {
                itemView.isClickable = true
                check.setOnCheckedChangeListener { _, checked -> toggle(row.choice.key, checked) }
                itemView.setOnClickListener { check.isChecked = !check.isChecked }
            } else {
                // 누름을 아예 받지 않는다 — 체크만 꺼 두면 줄을 눌렀을 때 아무 일도 안 일어나는
                // 것처럼 보여 사용자가 고장으로 읽는다.
                itemView.setOnClickListener(null)
                itemView.isClickable = false
            }
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

        /**
         * 커스텀 필드 한 줄.
         *
         * **타입을 못 알아본 필드는 [Choice.orderable]이 참이다** — 종전 동작 그대로다.
         * 거짓으로 두면 *"차례를 매길 수 없다"*를 **모르는 것을 두고 단언하는** 셈이고,
         * 그 필드에 숫자가 적혀 있으면 실제로는 견줘지므로 안내가 거짓이 된다.
         */
        fun choiceOf(field: FieldDefinition): Choice {
            val type = FieldType.fromName(field.type)
            return Choice(
                key = field.key,
                label = field.name,
                typeLabel = type?.label ?: field.type,
                orderable = type == null || type in ORDERABLE_TYPES
            )
        }

        /**
         * 시스템 열 한 줄 (B-167).
         *
         * **[orderable]이 언제나 거짓인 것**은 이 열들이 전부 글이기 때문이다 — 이름·이명·메모·
         * 작품·태그 어디에도 크고 작음이 없다. 그래도 **막지는 않는다**: 값이 최종 판정자라
         * 나이를 이명에 적어 둔 사용자가 있다면 그것도 견줘진다(자율성 우선). 여기서 하는 일은
         * 고른 순간에 *"차례를 매길 수 없다"*를 말해 주는 것뿐이다.
         */
        fun systemChoice(key: String, label: String, typeLabel: String): Choice =
            Choice(key = key, label = label, typeLabel = typeLabel, orderable = false)
    }
}
