package com.echoreading

import com.echoreading.voice.CatalogVoice
import com.echoreading.voice.VoiceCatalog
import com.echoreading.voice.VoiceOption
import com.echoreading.voice.catalogVoiceGroupLabel
import com.echoreading.voice.sampleGreetingFor
import com.echoreading.voice.visibleCatalogVoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {

    @Test
    fun discoveryShowsOnlySelectedLanguageAndNamesPortugueseRegions() {
        val portugal = CatalogVoice(
            key = "pt_PT-joana-medium", name = "joana", languageCode = "pt_PT",
            languageFamily = "pt", languageRegion = "PT", languageNative = "Português",
            languageEnglish = "Portuguese", countryEnglish = "Portugal", quality = "medium",
            numSpeakers = 1, onnxFilePath = "pt_PT.onnx", onnxSizeBytes = 1,
            onnxMd5 = "", configFilePath = "pt_PT.onnx.json", configSizeBytes = 1,
        )
        val brazil = portugal.copy(
            key = "pt_BR-edresson-medium", name = "edresson", languageCode = "pt_BR",
            languageRegion = "BR", countryEnglish = "Brazil",
        )
        val english = portugal.copy(
            key = "en_US-amy-low", name = "amy", languageCode = "en_US",
            languageFamily = "en", languageRegion = "US", languageEnglish = "English",
        )

        assertEquals(listOf(portugal, brazil), visibleCatalogVoices(listOf(brazil, english, portugal), "pt", ""))
        assertEquals(listOf(brazil), visibleCatalogVoices(listOf(brazil, english, portugal), "pt", "edr"))
        assertEquals(listOf(brazil), visibleCatalogVoices(listOf(brazil, english, portugal), "pt", "Brasil"))
        assertEquals(listOf(english), visibleCatalogVoices(listOf(brazil, english, portugal), "en", ""))
        assertEquals("Português (Portugal)", catalogVoiceGroupLabel(portugal))
        assertEquals("Português (Brasil)", catalogVoiceGroupLabel(brazil))
    }

    @Test
    fun sampleGreetingForLanguageFamilies() {
        val ptVoice = VoiceOption("pt-PT", "Tugão", "model.onnx", 22050, languageCode = "pt_PT")
        val enVoice = VoiceOption("en-US", "Amy", "model.onnx", 16000, languageCode = "en_US")
        val frVoice = VoiceOption("fr-FR", "Siwis", "model.onnx", 22050, languageCode = "fr_FR")
        val unknownVoice = VoiceOption("xx-XX", "Unknown", "model.onnx", 22050, languageCode = "xx_XX")

        assertTrue(sampleGreetingFor(ptVoice).contains("Olá"))
        assertTrue(sampleGreetingFor(enVoice).contains("Hello"))
        assertTrue(sampleGreetingFor(frVoice).contains("Bonjour"))
        assertTrue(sampleGreetingFor(unknownVoice).contains("Hello"))
    }

    @Test
    fun downloadUrlGeneration() {
        val url = VoiceCatalog.downloadUrl("en/en_US/amy/low/en_US-amy-low.onnx")
        assertEquals(
            "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/amy/low/en_US-amy-low.onnx",
            url
        )
    }

    @Test
    fun downloadUrlWithLeadingSlash() {
        val url = VoiceCatalog.downloadUrl("/en/en_US/amy/low/en_US-amy-low.onnx")
        assertEquals(
            "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/amy/low/en_US-amy-low.onnx",
            url
        )
    }

    @Test
    fun sampleGreetingForCatalogVoice() {
        val catalogVoice = CatalogVoice(
            key = "de_DE-thorsten-medium",
            name = "thorsten",
            languageCode = "de_DE",
            languageFamily = "de",
            languageRegion = "DE",
            languageNative = "Deutsch",
            languageEnglish = "German",
            countryEnglish = "Germany",
            quality = "medium",
            numSpeakers = 1,
            onnxFilePath = "de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx",
            onnxSizeBytes = 63000000L,
            onnxMd5 = "abc123md5",
            configFilePath = "de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx.json",
            configSizeBytes = 4000L,
        )

        val greeting = sampleGreetingFor(catalogVoice)
        assertTrue(greeting.contains("Hallo"))
    }

    @Test
    fun parseCatalogJsonExtractsValidVoicesAndFiltersInvalid() {
        val sampleJson = """
        {
          "en_US-amy-low": {
            "key": "en_US-amy-low",
            "name": "amy",
            "language": {
              "code": "en_US",
              "family": "en",
              "region": "US",
              "name_native": "English",
              "name_english": "English",
              "country_english": "United States"
            },
            "quality": "low",
            "num_speakers": 1,
            "files": {
              "en/en_US/amy/low/en_US-amy-low.onnx": {
                "size_bytes": 63104657,
                "md5_digest": "8275b02c37c4ce6483b26a91704807e6"
              },
              "en/en_US/amy/low/en_US-amy-low.onnx.json": {
                "size_bytes": 4827,
                "md5_digest": "abcd1234efgh5678"
              }
            }
          },
          "fr_FR-siwis-medium": {
            "key": "fr_FR-siwis-medium",
            "name": "siwis",
            "language": {
              "code": "fr_FR",
              "family": "fr",
              "region": "FR",
              "name_native": "Français",
              "name_english": "French",
              "country_english": "France"
            },
            "quality": "medium",
            "num_speakers": 1,
            "files": {
              "fr/fr_FR/siwis/medium/fr_FR-siwis-medium.onnx": {
                "size_bytes": 65000000,
                "md5_digest": "siwis_md5_hash"
              },
              "fr/fr_FR/siwis/medium/fr_FR-siwis-medium.onnx.json": {
                "size_bytes": 5000,
                "md5_digest": "config_md5_hash"
              }
            }
          },
          "invalid-voice-no-onnx": {
            "key": "invalid-voice",
            "name": "invalid",
            "language": {
              "code": "xx_XX",
              "family": "xx",
              "region": "XX",
              "name_native": "Invalid",
              "name_english": "Invalid",
              "country_english": "Nowhere"
            },
            "quality": "low",
            "num_speakers": 1,
            "files": {
              "xx/invalid.txt": {
                "size_bytes": 100,
                "md5_digest": "xyz"
              }
            }
          }
        }
        """.trimIndent()

        val voices = VoiceCatalog.parse(sampleJson)
        assertEquals(2, voices.size)
        val amy = voices.find { it.key == "en_US-amy-low" }
        assertNotNull(amy)
        assertEquals("amy", amy!!.name)
        assertEquals("en_US", amy.languageCode)
        assertEquals("English", amy.languageEnglish)
        assertEquals("United States", amy.countryEnglish)
        assertEquals(63104657L, amy.onnxSizeBytes)
        assertEquals("8275b02c37c4ce6483b26a91704807e6", amy.onnxMd5)

        val siwis = voices.find { it.key == "fr_FR-siwis-medium" }
        assertNotNull(siwis)
        assertEquals("siwis", siwis!!.name)
        assertEquals("French", siwis.languageEnglish)
    }

    @Test
    fun sampleGreetingForHebrewFixed() {
        val heVoice = VoiceOption("he-IL", "HebrewVoice", "model.onnx", 22050, languageCode = "he_IL")
        val greeting = sampleGreetingFor(heVoice)
        assertTrue(greeting.contains("שלום"))
        assertTrue(greeting.contains("הדגמה"))
    }

    @Test
    fun sampleGreetingsCoverAll53LanguageFamilies() {
        assertEquals(53, com.echoreading.voice.SAMPLE_GREETINGS.size)
        com.echoreading.voice.SAMPLE_GREETINGS.forEach { (lang, greeting) ->
            assertTrue("Greeting for $lang should not be blank", greeting.isNotBlank())
        }
    }

    @Test
    fun parseEmptyOrCorruptedJsonReturnsEmptyList() {
        val voices = VoiceCatalog.parse("{}")
        assertTrue(voices.isEmpty())
    }

    @Test
    fun tokensExtractionFromPhonemeIdMapFormat() {
        val phonemeJson = org.json.JSONObject("""
            {
                "_": [0],
                "^": [1],
                "$": [2],
                " ": [3],
                "a": [14]
            }
        """.trimIndent())

        val sb = StringBuilder()
        val keys = phonemeJson.keys()
        while (keys.hasNext()) {
            val sym = keys.next()
            val ids = phonemeJson.optJSONArray(sym)
            if (ids != null) {
                for (i in 0 until ids.length()) {
                    sb.append(sym).append(' ').append(ids.getInt(i)).append('\n')
                }
            }
        }
        val result = sb.toString()
        assertTrue(result.contains("_ 0\n"))
        assertTrue(result.contains("^ 1\n"))
        assertTrue(result.contains("$ 2\n"))
        assertTrue(result.contains("  3\n"))
        assertTrue(result.contains("a 14\n"))
    }
}
