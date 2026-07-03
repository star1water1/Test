package com.novelcharacter.app.util

import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavOptions

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

/**
 * Navigates with optional [NavOptions], absorbing the two known crash cases:
 * an unresolvable destination (IllegalArgumentException) and navigation after
 * onSaveInstanceState (IllegalStateException). Returns whether navigation ran.
 */
fun NavController.navigateSafe(destId: Int, args: Bundle? = null, navOptions: NavOptions? = null): Boolean {
    return try {
        navigate(destId, args, navOptions)
        true
    } catch (e: IllegalArgumentException) {
        android.util.Log.w("SafeNavigation", "Navigation failed: ${e.message}")
        false
    } catch (e: IllegalStateException) {
        android.util.Log.w("SafeNavigation", "Navigation failed: ${e.message}")
        false
    }
}
