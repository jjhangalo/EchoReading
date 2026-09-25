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
    private fun installed() = OfflineVoice.allVoices(this).filter { OfflineVoice.isInstalled(this, it) }

    private fun matches(voice: VoiceOption, lang: String?, country: String?): Boolean {
        val tag = voice.languageCode?.replace('_', '-') ?: voice.id
        val locale = Locale.forLanguageTag(tag)
        val requestedLanguage = lang.orEmpty()
        val requestedCountry = country.orEmpty()
        val iso3Lang = try { locale.isO3Language } catch (_: Exception) { "" }
        val iso3Country = try { locale.isO3Country } catch (_: Exception) { "" }
        return (requestedLanguage.equals(locale.language, true) || requestedLanguage.equals(iso3Lang, true)) &&
            (requestedCountry.isEmpty() || requestedCountry.equals(locale.country, true) ||
                requestedCountry.equals(iso3Country, true))
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
        val voice = installed().firstOrNull { it.id == preferred } ?: installed().firstOrNull() ?: OfflineVoice.voices.first()
        val tag = voice.languageCode?.replace('_', '-') ?: voice.id
        val locale = Locale.forLanguageTag(tag)
        val iso3Lang = try { locale.isO3Language } catch (_: Exception) { locale.language }
        val iso3Country = try { locale.isO3Country } catch (_: Exception) { locale.country }
        return arrayOf(iso3Lang, iso3Country, "")
    }

    override fun onGetVoices(): MutableList<Voice> = installed().map { voice ->
        val tag = voice.languageCode?.replace('_', '-') ?: voice.id
        Voice(
            voice.id, Locale.forLanguageTag(tag), Voice.QUALITY_NORMAL,
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
                if (callback.start(OfflineVoice.option(this, voiceId).sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                    callback.done()
                }
                return
            }
            if (cancelled.get()) return
            // The JNI callback path expects a concrete invoke(float[]) method and can abort the process.
            val audio = OfflineVoice.synthesize(this, text, voiceId, rate)
            if (cancelled.get()) return
            if (callback.start(audio.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
            val samplesPerBlock = (callback.maxBufferSize / 2).coerceAtMost(4096)
            if (samplesPerBlock == 0) {
                callback.error()
                return
            }
            val bytes = ByteArray(samplesPerBlock * 2)
            var offset = 0
            while (offset < audio.samples.size) {
                if (cancelled.get()) return
                val count = minOf(samplesPerBlock, audio.samples.size - offset)
                for (index in 0 until count) {
                    val pcm = (audio.samples[offset + index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    bytes[index * 2] = pcm.toByte()
                    bytes[index * 2 + 1] = (pcm ushr 8).toByte()
                }
                if (callback.audioAvailable(bytes, 0, count * 2) != TextToSpeech.SUCCESS) return
                offset += count
            }
            if (cancelled.get()) return
            callback.done()
        } catch (_: Exception) {
            if (!cancelled.get()) callback.error()
        } catch (_: LinkageError) {
            if (!cancelled.get()) callback.error()
        }
    }
}
