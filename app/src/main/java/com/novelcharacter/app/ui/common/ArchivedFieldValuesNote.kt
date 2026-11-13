package com.novelcharacter.app.ui.common

import android.content.Context
import android.view.View
import android.widget.TextView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.repository.ArchivedFieldValues
import com.novelcharacter.app.util.SqlInChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 사건·작품 편집 창이 함께 쓰는 '이 창에 칸이 없는 보관 값' 안내 (B-258 ⓑ). 읽기 전용이다. */
object ArchivedFieldValuesNote {

    suspend fun load(context: Context, stored: List<Pair<Long, String>>, covered: Set<Long>) =
        withContext(Dispatchers.IO) {
            val outside = stored.filter { (fieldId, value) -> fieldId !in covered && value.isNotBlank() }
            if (outside.isEmpty()) return@withContext emptyList()
            val db = AppDatabase.getDatabase(context.applicationContext)
            val definitions = SqlInChunks.flat(outside.map { it.first }.distinct()) {
                db.fieldDefinitionDao().getFieldsByIds(it)
            }.associateBy { it.id }
            val names = db.universeDao().getAllUniversesList().associate { it.id to it.name }
            ArchivedFieldValues.select(outside, covered, definitions, names)
        }

    fun bind(view: TextView, lines: List<ArchivedFieldValues.Line>) {
        if (lines.isEmpty()) {
            view.visibility = View.GONE
            view.text = ""
            return
        }
        val context = view.context
        val body = lines.joinToString("\n") {
            context.getString(R.string.archived_field_values_line,
                it.zone ?: context.getString(R.string.archived_field_values_global), it.fieldName, it.value)
        }
        view.text = context.getString(R.string.archived_field_values_title, lines.size) + "\n" + body +
            "\n" + context.getString(R.string.archived_field_values_note)
        view.visibility = View.VISIBLE
    }
}
