package com.t.kuvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 충전 중이거나 이어폰을 쓰는 중일 때 "쿠비스" 소리를 기다렸다가 쿠비스를 부른다.
 * 충전기·이어폰을 빼면 바로 멈춘다. 듣는 동안엔 알림창에 '듣는 중'이 보인다.
 */
object Hotword {
    // "쿠비스"라고 부르기는 T 요청으로 뺌 (2026-11-25) — 버튼으로만 부름. 켜져 있던 듣기는 sync에서 꺼짐
    fun enabled(ctx: Context) = false
    fun earEnabled(ctx: Context) = false

    /** 유선·블루투스·USB 이어폰이 연결돼 있나 */
    fun earphones(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            val types = mutableSetOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
            if (Build.VERSION.SDK_INT >= 31) types.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
        } catch (_: Exception) { false }
    }

    /** 지금 듣기를 켜 둘 상황인가: 충전 중(스위치 켜짐) 또는 이어폰 연결(스위치 켜짐) */
    fun shouldRun(ctx: Context) = (enabled(ctx) && charging(ctx)) || (earEnabled(ctx) && earphones(ctx))

    fun charging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return plugged != 0 || s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    /** 지금 상태에 맞게 켜거나 끄기. 켜기는 화면에 쿠비스(홈)가 보일 때 불러야 마이크가 허락됨 */
    fun sync(ctx: Context) {
        val want = shouldRun(ctx) &&
            ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (want && !HotwordService.running) {
            try { ctx.startForegroundService(Intent(ctx, HotwordService::class.java)) }
            catch (e: Exception) { ErrorLog.add(ctx, "말로 부르기 시작 못 함", e.toString()) }
        } else if (!want && HotwordService.running) {
            ctx.stopService(Intent(ctx, HotwordService::class.java))
        }
    }

    private var receiver: BroadcastReceiver? = null

    /** 앱이 살아 있는 동안 충전기·이어폰 꽂고 빼기를 지켜봄 */
    fun watch(app: Context) {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { safeSync(c) }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, f)
        try {
            app.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { safeSync(app) }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { safeSync(app) }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {}
    }

    private fun safeSync(c: Context) {
        if (!shouldRun(c)) { if (HotwordService.running) c.stopService(Intent(c, HotwordService::class.java)) }
        else sync(c)
    }

    /** 들은 말에 '쿠비스'가 들어 있나 (발음이 비슷한 것까지) */
    fun isWake(text: String): Boolean {
        val t = text.replace(" ", "")
        return listOf("쿠비스", "구비스", "쿠비수", "크비스", "꾸비스", "큐비스", "쿠빗스", "kuvis").any { t.contains(it, ignoreCase = true) }
    }
}

class HotwordService : Service() {

    companion object {
        @Volatile var running = false
        /** 쿠비스 대화 창이 떠 있는 동안은 마이크를 양보 */
        @Volatile var paused = false
        private var self: HotwordService? = null
        fun resume() { paused = false; self?.let { s -> s.main.postDelayed({ s.listen() }, 800) } }
        fun pause() {
            paused = true
            self?.let { s -> s.main.post { try { s.rec?.cancel(); s.rec?.destroy() } catch (_: Exception) {}; s.rec = null; s.listening = false } }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var listening = false
    private var errors = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        self = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hotword", "말로 부르기", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "hotword")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("쿠비스가 듣는 중")
            .setContentText("\"쿠비스\"라고 부르면 대답해요. 충전기·이어폰을 빼면 꺼져요.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(7, n)
        listen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun listen() {
        if (!running || paused || listening) return
        if (!Hotword.shouldRun(this)) { stopSelf(); return }
        try {
            if (rec == null) {
                rec = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            listening = true
            muteBeep(true)
            rec?.startListening(i)
            main.postDelayed({ muteBeep(false) }, 700)
        } catch (e: Exception) {
            listening = false
            main.postDelayed({ listen() }, 3000)
        }
    }

    /** 듣기를 다시 시작할 때 나는 '띵' 소리를 잠깐 줄임 */
    private var savedVol = -1
    private fun muteBeep(on: Boolean) {
        try {
            val am = getSystemService(AudioManager::class.java)
            if (on) {
                if (am.isMusicActive) return
                savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } else if (savedVol >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0); savedVol = -1
            }
        } catch (_: Exception) {}
    }

    private fun heard(texts: List<String>?) {
        if (texts.isNullOrEmpty() || paused) return
        if (texts.any { Hotword.isWake(it) }) wake()
    }

    private fun wake() {
        paused = true
        try { rec?.cancel() } catch (_: Exception) {}
        listening = false
        // 쿠비스가 어시스턴트로 정해져 있으면 정식 방법으로, 아니면 대화 창을 바로 띄움
        val vis = KuvisVoiceService.instance
        if (vis != null) {
            try { vis.showSession(Bundle(), 0); return } catch (_: Exception) {}
        }
        try {
            startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { paused = false; listen() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { errors = 0 }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partial: Bundle?) {
            heard(partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
        }
        override fun onResults(results: Bundle?) {
            listening = false
            heard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            if (!paused) main.postDelayed({ listen() }, 250)
        }
        override fun onError(error: Int) {
            listening = false
            errors++
            // 아무 말 없을 때(시간 초과·못 알아들음)는 곧바로, 다른 오류는 조금 쉬었다가
            val wait = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 250L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2000L
                else -> (1000L * errors).coerceAtMost(30_000L)
            }
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                try { rec?.destroy() } catch (_: Exception) {}
                rec = null
            }
            if (!paused) main.postDelayed({ listen() }, wait)
        }
    }

    override fun onDestroy() {
        running = false
        self = null
        main.removeCallbacksAndMessages(null)
        muteBeep(false)
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
        super.onDestroy()
    }
}
