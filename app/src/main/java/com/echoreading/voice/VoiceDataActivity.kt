package com.echoreading.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.echoreading.MainActivity
import java.util.Locale

class VoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                setResult(
                    TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
                    Intent().putStringArrayListExtra(
                        TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                        ArrayList(OfflineVoice.allVoices(this).filter { OfflineVoice.isInstalled(this, it) }.map { voice ->
                            val tag = voice.languageCode?.replace('_', '-') ?: voice.id
                            val locale = Locale.forLanguageTag(tag)
                            val lang = try { locale.isO3Language } catch (_: Exception) { locale.language }
                            val country = try { locale.isO3Country } catch (_: Exception) { locale.country }
                            if (country.isNotEmpty()) "$lang-$country" else lang
                        }),
                    ),
                )
            }
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, "Olá. Estou a ler em voz alta."),
                )
            }
            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA -> startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
