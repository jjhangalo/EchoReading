package com.echoreading.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Metadata for a single voice in the remote Piper catalog.
 * This is distinct from [VoiceOption] — it represents an available voice,
 * not necessarily one that's installed or hardcoded.
 */
data class CatalogVoice(
    val key: String,                // "en_US-amy-low"
    val name: String,               // "amy"
    val languageCode: String,       // "en_US"
    val languageFamily: String,     // "en"
    val languageRegion: String,     // "US"
    val languageNative: String,     // "English"
    val languageEnglish: String,    // "English"
    val countryEnglish: String,     // "United States"
    val quality: String,            // "low", "medium", "high"
    val numSpeakers: Int,
    val onnxFilePath: String,       // "en/en_US/amy/low/en_US-amy-low.onnx"
    val onnxSizeBytes: Long,
    val onnxMd5: String,
    val configFilePath: String,     // "en/en_US/amy/low/en_US-amy-low.onnx.json"
    val configSizeBytes: Long,
)

object VoiceCatalog {
    const val CATALOG_URL =
        "https://huggingface.co/rhasspy/piper-voices/resolve/main/voices.json"
    const val BASE_DOWNLOAD_URL =
        "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0"
    const val CACHE_FILE = "voice_catalog_cache.json"
    const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours

    /** Load the catalog, using disk cache if fresh enough. Falls back to stale cache on network failure. */
    suspend fun load(context: Context): Result<List<CatalogVoice>> =
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, CACHE_FILE)
            val isFresh = cacheFile.isFile &&
                (System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS)

            if (isFresh) {
                try {
                    val text = cacheFile.readText()
                    if (text.isNotBlank()) {
                        return@withContext Result.success(parse(text))
                    }
                } catch (_: Exception) {}
            }

            try {
                val json = fetchRemote()
                try {
                    cacheFile.writeText(json)
                } catch (_: Exception) {}
                Result.success(parse(json))
            } catch (e: Exception) {
                // Network error: fallback to stale cache if present
                try {
                    if (cacheFile.isFile) {
                        val staleText = cacheFile.readText()
                        if (staleText.isNotBlank()) {
                            return@withContext Result.success(parse(staleText))
                        }
                    }
                } catch (_: Exception) {}
                Result.failure(e)
            }
        }

    /** Force refresh, ignoring cache. */
    suspend fun refresh(context: Context): Result<List<CatalogVoice>> =
        withContext(Dispatchers.IO) {
            try {
                val json = fetchRemote()
                try {
                    File(context.cacheDir, CACHE_FILE).writeText(json)
                } catch (_: Exception) {}
                Result.success(parse(json))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Build the full download URL for a catalog file path. */
    fun downloadUrl(filePath: String): String =
        "$BASE_DOWNLOAD_URL/${filePath.removePrefix("/")}"

    private fun fetchRemote(): String {
        var currentUrl = CATALOG_URL
        var redirects = 0
        var connection: HttpURLConnection? = null

        while (redirects < 5) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308
            ) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) throw IOException("Redirect without Location header")
                currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                redirects++
                continue
            }
            connection = conn
            break
        }

        val conn = connection ?: throw IOException("Too many redirects: $CATALOG_URL")
        try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    fun parse(jsonString: String): List<CatalogVoice> {
        val root = JSONObject(jsonString)
        return root.keys().asSequence().mapNotNull { key ->
            val obj = root.optJSONObject(key) ?: return@mapNotNull null
            val lang = obj.optJSONObject("language") ?: return@mapNotNull null
            val files = obj.optJSONObject("files") ?: return@mapNotNull null

            // Find the .onnx and .onnx.json file entries
            val onnxEntry = files.keys().asSequence()
                .firstOrNull { it.endsWith(".onnx") && !it.endsWith(".onnx.json") }
                ?: return@mapNotNull null
            val configEntry = files.keys().asSequence()
                .firstOrNull { it.endsWith(".onnx.json") }
                ?: return@mapNotNull null

            val onnxFile = files.optJSONObject(onnxEntry) ?: return@mapNotNull null
            val configFile = files.optJSONObject(configEntry) ?: return@mapNotNull null

            val onnxSizeBytes = onnxFile.optLong("size_bytes", 0L)
            if (onnxSizeBytes <= 0L) return@mapNotNull null

            CatalogVoice(
                key = key,
                name = obj.optString("name", key),
                languageCode = lang.optString("code", ""),
                languageFamily = lang.optString("family", ""),
                languageRegion = lang.optString("region", ""),
                languageNative = lang.optString("name_native", lang.optString("name_english", "")),
                languageEnglish = lang.optString("name_english", ""),
                countryEnglish = lang.optString("country_english", ""),
                quality = obj.optString("quality", "medium"),
                numSpeakers = obj.optInt("num_speakers", 1),
                onnxFilePath = onnxEntry,
                onnxSizeBytes = onnxSizeBytes,
                onnxMd5 = onnxFile.optString("md5_digest", ""),
                configFilePath = configEntry,
                configSizeBytes = configFile.optLong("size_bytes", 0L),
            )
        }.sortedWith(compareBy({ it.languageEnglish }, { it.name }, { it.quality }))
            .toList()
    }
}

/** Short sample greetings in each language, used for voice preview/audition. */
val SAMPLE_GREETINGS: Map<String, String> = mapOf(
    "ar" to "مرحباً! هذا عرض توضيحي لهذا الصوت وهو يقرأ النص بصوت عالٍ.",
    "bg" to "Здравейте! Това е демонстрация на този глас, който чете текст на глас.",
    "bn" to "নমস্কার! এটি এই কণ্ঠস্বরের একটি প্রদর্শন।",
    "ca" to "Hola! Aquesta és una demostració d'aquesta veu llegint text en veu alta.",
    "cs" to "Dobrý den! Toto je ukázka tohoto hlasu při čtení textu nahlas.",
    "cy" to "Helo! Dyma arddangosiad o'r llais hwn yn darllen testun yn uchel.",
    "da" to "Hej! Dette er en demonstration af denne stemme, der læser tekst højt.",
    "de" to "Hallo! Dies ist eine Demonstration dieser Stimme beim Vorlesen von Text.",
    "el" to "Γεια σας! Αυτή είναι μια επίδειξη αυτής της φωνής που διαβάζει κείμενο δυνατά.",
    "en" to "Hello! This is a preview of this voice reading text aloud.",
    "es" to "¡Hola! Esta es una demostración de esta voz leyendo texto en voz alta.",
    "et" to "Tere! See on selle hääle demonstratsioon teksti ettelugemiseks.",
    "eu" to "Kaixo! Hau ahots honen erakustaldia da testua ozen irakurtzen.",
    "fa" to "سلام! این نمایشی از این صدا در خواندن متن با صدای بلند است.",
    "fi" to "Hei! Tämä on esittely tästä äänestä lukemassa tekstiä ääneen.",
    "fr" to "Bonjour ! Ceci est une démonstration de cette voix lisant du texte à haute voix.",
    "he" to "שלום! זו הדגמה של קול זה הקורא טקסט בקול רם.",
    "hi" to "नमस्ते! यह इस आवाज़ का एक प्रदर्शन है जो पाठ को ज़ोर से पढ़ रही है।",
    "hu" to "Helló! Ez egy bemutató erről a hangról, amely szöveget olvas fel.",
    "hy" to "Բարև Ձեզ! Սա այս ձայնի ցուցադրումն է՝ տեքստ կարդալիս:",
    "id" to "Halo! Ini adalah demonstrasi suara ini membaca teks dengan lantang.",
    "is" to "Halló! Þetta er sýnishorn af þessari röddu sem les texta upphátt.",
    "it" to "Ciao! Questa è una dimostrazione di questa voce che legge il testo ad alta voce.",
    "ja" to "こんにちは！これはこの音声がテキストを読み上げるデモンストレーションです。",
    "ka" to "გამარჯობა! ეს არის ამ ხმის დემონსტრაცია ტექსტის ხმამაღლა კითხვისას.",
    "kk" to "Сәлем! Бұл дауыстың мәтінді дауыстап оқуының демонстрациясы.",
    "ko" to "안녕하세요! 이것은 이 음성이 텍스트를 소리 내어 읽는 시연입니다.",
    "ku" to "Silav! Ev pêşandana vê dengê ye ku nivîsê bi deng dixwîne.",
    "lb" to "Moien! Dëst ass eng Demonstratioun vun dëser Stëmm déi Text virliéist.",
    "lt" to "Sveiki! Tai šio balso demonstracija skaitant tekstą garsiai.",
    "lv" to "Sveiki! Šī ir šīs balss demonstrācija, lasot tekstu skaļi.",
    "ml" to "നമസ്കാരം! ഈ ശബ്ദം ഉറക്കെ വായിക്കുന്നതിന്റെ ഒരു ഡെമോ ആണിത്.",
    "mr" to "नमस्कार! हा या आवाजाचा मजकूर मोठ्याने वाचण्याचा प्रात्यक्षिक आहे.",
    "ne" to "नमस्ते! यो यस आवाजको पाठ ठूलो स्वरमा पढ्ने प्रदर्शन हो।",
    "nl" to "Hallo! Dit is een demonstratie van deze stem die tekst hardop voorleest.",
    "no" to "Hei! Dette er en demonstrasjon av denne stemmen som leser tekst høyt.",
    "pl" to "Cześć! To jest demonstracja tego głosu czytającego tekst na głos.",
    "pt" to "Olá! Esta é uma demonstração desta voz lendo texto em voz alta com clareza e ritmo natural.",
    "ro" to "Bună! Aceasta este o demonstrație a acestei voci citind textul cu voce tare.",
    "ru" to "Здравствуйте! Это демонстрация этого голоса, читающего текст вслух.",
    "sk" to "Dobrý deň! Toto je ukážka tohto hlasu pri čítaní textu nahlas.",
    "sl" to "Zdravo! To je predstavitev tega glasu, ki bere besedilo na glas.",
    "sq" to "Përshëndetje! Kjo është një demonstrim i kësaj zëri që lexon tekstin me zë.",
    "sr" to "Здраво! Ово је демонстрација овог гласа који чита текст наглас.",
    "sv" to "Hej! Det här är en demonstration av denna röst som läser text högt.",
    "sw" to "Habari! Hii ni onyesho wa sauti hii inayosoma maandishi kwa sauti.",
    "te" to "నమస్కారం! ఇది ఈ స్వరం బిగ్గరగా చదవడం యొక్క ప్రదర్శన.",
    "th" to "สวัสดี! นี่คือการสาธิตเสียงนี้ในการอ่านข้อความออกเสียง",
    "tr" to "Merhaba! Bu sesin metni sesli okumasının bir gösterimidir.",
    "uk" to "Привіт! Це демонстрація цього голосу, який читає текст вголос.",
    "ur" to "السلام علیکم! یہ اس آواز کا مظاہرہ ہے جو متن بلند آواز سے پڑھ رہی ہے۔",
    "vi" to "Xin chào! Đây là bản trình diễn giọng đọc này đọc văn bản thành tiếng.",
    "zh" to "你好！这是这个语音朗读文本的演示。",
)

/**
 * Get the appropriate sample greeting for a voice.
 * Falls back to English if the language family is not mapped.
 */
fun sampleGreetingFor(voice: VoiceOption): String {
    val langTag = voice.languageCode ?: voice.id
    val family = langTag.substringBefore('-').substringBefore('_')
    return SAMPLE_GREETINGS[family] ?: SAMPLE_GREETINGS["en"]!!
}

fun sampleGreetingFor(voice: CatalogVoice): String =
    SAMPLE_GREETINGS[voice.languageFamily] ?: SAMPLE_GREETINGS["en"]!!
