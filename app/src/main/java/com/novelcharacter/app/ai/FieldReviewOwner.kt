package com.novelcharacter.app.ai

/** Unsaved entities have independent review slots; old -1 slots require explicit recovery. */
class FieldReviewOwner(private val entity: String) {
    private var selected: String? = null
    val needsChoice: Boolean get() = selected == null
    fun isNewKey(key: String) = key == "$entity:-1:fields" ||
        (key.startsWith("$entity:draft:") && key.endsWith(":fields"))
    fun choose(key: String?) {
        require(key == null || isNewKey(key))
        selected = key ?: "$entity:draft:${java.util.UUID.randomUUID()}:fields"
    }
    fun key(id: Long): String {
        if (id != -1L) return "$entity:$id:fields"
        if (selected == null) choose(null)
        return requireNotNull(selected)
    }
    fun clear() { selected = null }
}
