package com.novelcharacter.app.ai

/** Process-local ownership distinguishes a live request in another editor from an interrupted one. */
class ReviewRequestLease {
    private val owners = mutableMapOf<String, Any>()
    @Synchronized fun acquire(key: String, token: Any): Boolean {
        if (key in owners) return false
        owners[key] = token
        return true
    }
    @Synchronized fun release(key: String, token: Any) {
        if (owners[key] === token) owners.remove(key)
    }
    @Synchronized fun heldByOther(key: String, token: Any) = owners[key]?.let { it !== token } == true
}
