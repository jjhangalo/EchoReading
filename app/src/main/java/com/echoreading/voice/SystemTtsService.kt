package com.echoreading.voice

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SystemTtsService : TextToSpeechService() {
    private val cancelled = AtomicBoolean(false)
    private fun installed() = OfflineVoice.voices.filter { OfflineVoice.isInstalled(this, it) }

    private fun matches(voice: VoiceOption, lang: String?, country: String?): Boolean {
        val locale = Locale.forLanguageTag(voice.id)
        val requestedLanguage = lang.orEmpty()
        val requestedCountry = country.orEmpty()
        return (requestedLanguage == locale.language || requestedLanguage == locale.getISO3Language()) &&
            (requestedCountry.isEmpty() || requestedCountry.equals(locale.country, true) ||
                requestedCountry.equals(locale.getISO3Country(), true))
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (installed().any { matches(it, lang, country) }) {
            if (country.isNullOrEmpty()) TextToSpeech.LANG_AVAILABLE
            else TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else TextToSpeech.LANG_NOT_SUPPORTED

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> {
        val preferred = getSharedPreferences("reading", MODE_PRIVATE).getString("voice", "pt-PT")
        val locale = Locale.forLanguageTag(installed().firstOrNull { it.id == preferred }?.id ?: "pt-PT")
        return arrayOf(locale.getISO3Language(), locale.getISO3Country(), "")
    }

    override fun onGetVoices(): MutableList<Voice> = installed().map { voice ->
        Voice(
            voice.id, Locale.forLanguageTag(voice.id), Voice.QUALITY_NORMAL,
            Voice.LATENCY_NORMAL, false, emptySet(),
        )
    }.toMutableList()

    override fun onIsValidVoiceName(name: String?): Int =
        if (installed().any { it.id == name }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        installed().firstOrNull { it.id == getSharedPreferences("reading", MODE_PRIVATE).getString("voice", "pt-PT") && matches(it, lang, country) }?.id
            ?: installed().firstOrNull { matches(it, lang, country) }?.id

    override fun onStop() {
        cancelled.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        cancelled.set(false)
        if (onIsLanguageAvailable(request.language, request.country, request.variant) < 0) {
            callback.error()
            return
        }
        try {
            val rate = (request.speechRate / 100f).coerceIn(0.5f, 2.5f)
            val voiceId = request.voiceName?.takeIf { name -> installed().any { it.id == name } }
                ?: onGetDefaultVoiceNameFor(request.language, request.country, request.variant)
                ?: run { callback.error(); return }
            val text = request.charSequenceText.toString()
            if (text.isBlank()) {
                if (callback.start(OfflineVoice.option(voiceId).sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                    callback.done()
                }
                return
            }
            var started = false
            var failed = false
            val audio = OfflineVoice.synthesize(this, text, voiceId, rate) { samples ->
                if (cancelled.get()) return@synthesize false
                if (!started) {
                    if (callback.start(OfflineVoice.option(voiceId).sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                        failed = true
                        return@synthesize false
                    }
                    started = true
                }
                val bytes = ByteArray(samples.size * 2)
                samples.forEachIndexed { index, value ->
                    val pcm = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    bytes[index * 2] = pcm.toByte()
                    bytes[index * 2 + 1] = (pcm ushr 8).toByte()
                }
                var offset = 0
                while (offset < bytes.size && !cancelled.get()) {
                    val count = minOf(callback.maxBufferSize, bytes.size - offset)
                    if (callback.audioAvailable(bytes, offset, count) != TextToSpeech.SUCCESS) {
                        failed = true
                        return@synthesize false
                    }
                    offset += count
                }
                !cancelled.get()
            }
            if (cancelled.get() || failed) return
            if (!started && callback.start(audio.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
            callback.done()
        } catch (_: Exception) {
            if (!cancelled.get()) callback.error()
        } catch (_: LinkageError) {
            if (!cancelled.get()) callback.error()
        }
    }
}
