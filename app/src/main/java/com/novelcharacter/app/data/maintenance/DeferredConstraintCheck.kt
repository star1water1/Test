package com.novelcharacter.app.data.maintenance

import com.novelcharacter.app.data.database.AppDatabase

object DeferredConstraintCheck {
    /** Call inside the active Room transaction, before irreversible file effects. */
    fun verify(db: AppDatabase) {
        val sqlite = db.openHelper.writableDatabase
        val deferred = sqlite.query("PRAGMA defer_foreign_keys").use {
            it.moveToFirst() && it.getInt(0) != 0
        }
        if (!deferred) return
        // Android's SQLiteSession can release the connection after a failed COMMIT without
        // rolling back a deferred FK violation. Reject it while Room still owns the transaction.
        sqlite.query("PRAGMA foreign_key_check").use {
            if (it.moveToFirst()) throw android.database.sqlite.SQLiteConstraintException("Deferred image deletion constraint")
        }
    }
}
