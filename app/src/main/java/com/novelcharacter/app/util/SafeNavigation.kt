package com.novelcharacter.app.util

import android.os.Bundle
import androidx.navigation.NavController

/**
 * Prevents duplicate navigation crashes caused by rapid double-taps.
 * Only navigates if the current destination matches [currentDestId].
 */
fun NavController.navigateSafe(currentDestId: Int, destId: Int, args: Bundle? = null) {
    if (currentDestination?.id == currentDestId) {
        try {
            navigate(destId, args)
        } catch (e: IllegalArgumentException) {
            // Navigation destination not found or already navigated
            android.util.Log.w("SafeNavigation", "Navigation failed: ${e.message}")
        }
    }
}
