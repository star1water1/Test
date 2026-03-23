package com.novelcharacter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.novelcharacter.app.databinding.ActivityMainBinding
import com.novelcharacter.app.util.ThemeHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "Notification permission granted")
        } else {
            Log.w("MainActivity", "Notification permission denied")
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(ThemeHelper.getSavedTheme(this))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        requestNotificationPermission()
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val deeplink = intent?.getStringExtra("deeplink") ?: return
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        val navController = navHostFragment.navController

        try {
            when (deeplink) {
                "add_character" -> {
                    navController.navigate(R.id.characterEditFragment)
                }
                "add_event" -> {
                    navController.navigate(R.id.timelineFragment)
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Deep link navigation failed: $deeplink", e)
        }
        // 딥링크 처리 후 extra 제거 (재처리 방지)
        intent?.removeExtra("deeplink")
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        val navController = navHostFragment.navController

        // 하단 바 6탭: 홈, 캐릭터, 관계도, 통계, 보충, 설정
        val topLevelIds = setOf(
            R.id.homeFragment,
            R.id.characterListFragment,
            R.id.relationshipGraphFragment,
            R.id.statsMainFragment,
            R.id.settingsFragment
        )

        binding.bottomNav.setupWithNavController(navController)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val navOptions = NavOptions.Builder()
                .setPopUpTo(navController.graph.startDestinationId, inclusive = false)
                .setLaunchSingleTop(true)
                .build()
            navController.navigate(item.itemId, null, navOptions)
            true
        }

        binding.bottomNav.setOnItemReselectedListener { item ->
            navController.popBackStack(item.itemId, inclusive = false)
        }

        // 하단 바는 최상위 탭에서만 표시
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.visibility = if (destination.id in topLevelIds) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
