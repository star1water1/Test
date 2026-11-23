package com.novelcharacter.app.pcbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.novelcharacter.app.MainActivity
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.repository.CharacterFormFields
import com.novelcharacter.app.data.repository.CharacterFormGates
import com.novelcharacter.app.data.repository.CharacterFormSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * PC 동반 편집 서버를 **화면 밖에서 살려 두는 서비스**.
 *
 * ## 왜 포그라운드 서비스인가
 *
 * 사용자가 PC에서 작업하는 동안 폰 화면은 꺼져 있다. 평범한 서비스는 그 상태에서 죽고,
 * `WorkManager`는 짧은 작업용이라 소켓을 들고 있을 수 없다. **이 앱에는 이미 같은 틀이
 * 있다** — `speech/VoiceRecordingService`가 포그라운드 서비스이고 `PARTIAL_WAKE_LOCK`을
 * 잡는다. 그쪽과 다른 점은 둘이다.
 *
 * 1. **종류가 `specialUse`다.** Android 15는 `dataSync` 포그라운드 서비스를 24시간당 누적
 *    6시간으로 묶는다 — PC로 하루 작업하는 용도에는 맞지 않는다. `specialUse`는 그 상한이
 *    없고, **이 앱은 사이드로드**라(`app/build.gradle.kts`의 서명 설정 주석) 스토어 정책
 *    심사를 지나지 않는다.
 * 2. **`WifiLock`을 함께 잡는다.** 화면이 꺼지면 Wi-Fi 칩이 절전으로 내려가 소켓이 닿지
 *    않을 수 있다. `WAKE_LOCK`만으로는 그 축이 덮이지 않는다.
 *
 * ## 이 파일에는 판정을 두지 않는다
 *
 * `tools/probe_compile.sh`의 `PCBRIDGE_EXCLUDE`에 있어 **지역 컴파일 증명이 없다**(안드로이드
 * `Service`·알림·`WifiManager`가 그 프로브의 좁은 스텁 밖이고, 넓히면 그 파일 머리가 경계한
 * *거짓 초록*이 된다). 그래서 여기 있는 것은 수명 관리뿐이고, 요청 하나의 판정은 전부
 * [BridgeHandler]가 든다.
 */
class PcBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "pc_bridge"
        private const val NOTIFICATION_ID = 4821

        /** 설정 화면이 서버를 켠다. */
        fun start(context: Context) {
            val intent = Intent(context, PcBridgeService::class.java)
            context.startForegroundService(intent)
        }

        /** 설정 화면이 서버를 끈다. */
        fun stop(context: Context) {
            context.stopService(Intent(context, PcBridgeService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: BridgeServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 알림을 **먼저** 올린다 — Android는 포그라운드 서비스가 시작 직후 알림을 띄우지
        // 않으면 앱을 죽인다. 포트 조회(DataStore)는 그 뒤다.
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.pc_bridge_notice_starting)))

        if (server != null) return START_STICKY

        scope.launch {
            val app = applicationContext as NovelCharacterApp
            val store = BridgeSettingsStore(applicationContext)
            val settings = store.get()
            val token = store.ensureToken()

            val data = BridgeData(
                characters = app.characterRepository,
                novels = app.novelRepository,
                universes = app.universeRepository,
                factions = app.factionRepository,
                gates = CharacterFormGates(
                    app.database, app.characterRepository, app.universeRepository,
                    app.novelRepository, app.fieldValueLibraryRepository
                ),
                formFields = CharacterFormFields(app.universeRepository, app.defaultFieldTemplateRepository),
                formSaver = CharacterFormSaver(
                    app.database, app.characterRepository, app.universeRepository, app.novelRepository
                ),
                filesDir = applicationContext.filesDir
            )

            val started = BridgeServer(
                port = settings.port,
                settingsProvider = { store.get() },
                data = data
            )
            val ok = started.start()
            if (!ok) {
                // 포트를 못 열었다 — 조용히 꺼지지 않는다. 알림이 사유를 들고 남는다.
                updateNotification(getString(R.string.pc_bridge_notice_port_failed, settings.port))
                store.setEnabled(false)
                return@launch
            }
            server = started
            acquireWifiLock()
            val urls = BridgeAddresses.reachableUrls(settings.mode, settings.port, token)
            updateNotification(
                if (urls.isEmpty()) getString(R.string.pc_bridge_notice_no_route)
                else getString(R.string.pc_bridge_notice_running, urls.first())
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWifiLock()
        server?.stop()
        server = null
        scope.cancel()
        super.onDestroy()
    }

    // ───────── 알림 ─────────

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.pc_bridge_channel_name),
            // 낮은 중요도 — 소리도 배너도 없이 상태만 남는다. 작업 중 방해하지 않는 것이
            // 이 알림의 일이고, 알림 자체는 포그라운드 서비스의 요건이라 뺄 수 없다.
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.pc_bridge_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.pc_bridge_notice_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ───────── Wi-Fi 절전 ─────────

    /**
     * 화면이 꺼진 동안에도 소켓이 닿게 둔다.
     *
     * `WIFI_MODE_FULL_LOW_LATENCY`는 API 29부터다. 이 앱의 `minSdk`는 26이라 그 아래에서는
     * `WIFI_MODE_FULL_HIGH_PERF`를 쓴다 — 둘 다 **없는 상수를 부르면 그 기기에서 죽으므로**
     * 판올림으로 가른다.
     */
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = runCatching { wifi.createWifiLock(mode, "novelcharacter:pcbridge") }.getOrNull()
        runCatching { wifiLock?.acquire() }
    }

    private fun releaseWifiLock() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }
}
