package com.novelcharacter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.novelcharacter.app.databinding.ActivityMainBinding
import com.novelcharacter.app.util.ThemeHelper
import com.novelcharacter.app.util.navigateSafe

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

        setupEdgeToEdge()
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

    /**
     * targetSdk 35: Android 15+는 edge-to-edge를 강제하고 statusBarColor를 무시한다.
     * 루트에서 시스템 바(+IME) 인셋을 패딩으로 소비해 콘텐츠가 상태바·내비바 아래에
     * 깔리지 않게 한다. 바 영역에는 루트 배경(@color/background)이 노출된다.
     * pre-15 기기는 테마의 statusBarColor 폴백과 병행 동작한다.
     */
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        val navController = navHostFragment.navController

        // 하단 바 5탭: 홈(대시보드), 세계관, 캐릭터, 분석(통계·관계도·어시스턴트), 설정
        val topLevelIds = setOf(
            R.id.homeFragment,
            R.id.universeListFragment,
            R.id.characterTabFragment,
            R.id.analysisFragment,
            R.id.settingsFragment
        )

        binding.bottomNav.setupWithNavController(navController)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val navOptions = NavOptions.Builder()
                .setPopUpTo(navController.graph.startDestinationId, inclusive = false)
                .setLaunchSingleTop(true)
                .build()
            // 그래프에 없는 목적지/상태 저장 후 탐색으로 인한 크래시 방어 (실패 시 탭 선택 안 함)
            navController.navigateSafe(item.itemId, null, navOptions)
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
