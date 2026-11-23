package com.t.kuvis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 쿠비스를 '디지털 어시스턴트 앱'으로 고를 수 있게 하는 부분.
 * 측면 버튼 길게 누르기 → 이 세션이 열림 → AssistActivity(대화 창)를 띄움.
 */
class KuvisVoiceService : VoiceInteractionService() {
    companion object { @Volatile var instance: KuvisVoiceService? = null }
    override fun onReady() { super.onReady(); instance = this }
    override fun onShutdown() { if (instance === this) instance = null; super.onShutdown() }
}

class KuvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = KuvisSession(this)
}

class KuvisSession(ctx: Context) : VoiceInteractionSession(ctx) {

    companion object { var current: KuvisSession? = null }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false) // 세션 자체 창은 안 쓰고 AssistActivity를 씀
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        current = this
        val i = Intent(context, AssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(i) else context.startActivity(i)
    }

    override fun onHide() {
        super.onHide()
        if (current === this) current = null
    }
}

/**
 * 어시스턴트 앱은 음성 인식 서비스도 하나 가지고 있어야 한다.
 * 쿠비스가 어시스턴트가 되면 휴대폰 기본 음성 인식이 이것으로 바뀔 수 있으므로,
 * 다른 앱의 음성 입력이 깨지지 않게 받은 요청을 구글 음성 인식에 그대로 넘긴다.
 */
class KuvisRecognitionService : RecognitionService() {
    private var rec: SpeechRecognizer? = null

    private fun google(): ComponentName? {
        val list = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != packageName }
        return list.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: list.firstOrNull { it.packageName.contains("google") } ?: list.firstOrNull()
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val cb = listener ?: return
        val target = google()
        if (target == null) { safe { cb.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this, target)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { cb.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { cb.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { cb.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { if (buffer != null) cb.bufferReceived(buffer) }
            override fun onEndOfSpeech() = safe { cb.endOfSpeech() }
            override fun onError(error: Int) = safe { cb.error(error) }
            override fun onResults(results: Bundle?) = safe { cb.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { cb.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(recognizerIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
    }

    override fun onCancel(listener: Callback?) { rec?.cancel() }
    override fun onStopListening(listener: Callback?) { rec?.stopListening() }

    override fun onDestroy() {
        super.onDestroy()
        rec?.destroy(); rec = null
    }

    private inline fun safe(f: () -> Unit) { try { f() } catch (_: Exception) {} }
}
