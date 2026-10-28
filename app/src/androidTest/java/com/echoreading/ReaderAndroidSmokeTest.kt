package com.echoreading

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level smoke tests for the paths that JVM tests cannot exercise.
 *
 * These use ActivityScenario/UIAutomator rather than Compose's Espresso bridge:
 * the API 37 emulator no longer exposes the legacy InputManager.getInstance()
 * method that Espresso's injection strategy reflects on.
 *
 * Run with:
 *   .\gradlew.bat :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ReaderAndroidSmokeTest {

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var device: UiDevice

    @Before
    fun resetReader() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .grantRuntimePermission(
                        targetContext.packageName,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
            }
        }
        targetContext.stopService(Intent(targetContext, ReaderPlaybackService::class.java))
        ReaderState.snapshot.value = ReaderSnapshot()
        targetContext.getSharedPreferences("reading", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun stopReader() {
        targetContext.stopService(Intent(targetContext, ReaderPlaybackService::class.java))
        ReaderState.snapshot.value = ReaderSnapshot()
    }

    @Test
    fun appLaunchesOnHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Texto de Leitura")), 10_000))
            assertTrue(device.hasObject(By.text("Transcrever")))
            assertTrue(device.hasObject(By.text("Biblioteca")))
            assertTrue(device.hasObject(By.text("Definições")))
        }
    }

    @Test
    fun shareFromSettingsUpdatesDraftAndReturnsHome() {
        val shared = "Texto partilhado a partir das definicoes num teste instrumentado."
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var launchIntent: Intent? = null
            assertTrue(device.wait(Until.hasObject(By.text("Definições")), 10_000))
            device.findObject(By.text("Definições"))?.click()
            assertTrue(
                device.wait(
                    Until.hasObject(By.text("Armazenamento e Privacidade")),
                    5_000,
                ),
            )

            try {
                scenario.onActivity { activity ->
                    launchIntent = activity.intent
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shared)
                    }
                    // ComponentActivity exposes onNewIntent as protected; invoke the
                    // app override exactly as the system would for a single-top share.
                    val method = MainActivity::class.java.getDeclaredMethod(
                        "onNewIntent",
                        Intent::class.java,
                    )
                    method.isAccessible = true
                    method.invoke(activity, intent)
                }

                assertTrue(waitFor(5_000) { ReaderState.snapshot.value.text == shared })
                assertTrue(device.wait(Until.hasObject(By.text("Texto de Leitura")), 5_000))
                assertTrue(device.wait(Until.hasObject(By.text(shared)), 5_000))
                assertEquals(ReadingStatus.IDLE, ReaderState.snapshot.value.status)
            } finally {
                // ActivityScenario matches lifecycle events against the launch
                // intent. onNewIntent replaces MainActivity.intent, so restore it
                // or close() ignores the destroy it requested and times out.
                runCatching {
                    scenario.onActivity { activity -> launchIntent?.let { activity.intent = it } }
                }
            }
        }
    }

    @Test
    fun loadTextResetsPositionAndEpoch() {
        val before = ReaderState.epoch
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "old",
            status = ReadingStatus.PLAYING,
            positionMs = 5_000,
            durationMs = 10_000,
        )

        ReaderState.loadText(targetContext, "new text for the reader")

        val snapshot = ReaderState.snapshot.value
        assertEquals("new text for the reader", snapshot.text)
        assertEquals(ReadingStatus.IDLE, snapshot.status)
        assertEquals(0L, snapshot.positionMs)
        assertEquals(0L, snapshot.durationMs)
        assertTrue(ReaderState.epoch > before)
        assertTrue(snapshot.loadEpoch > 0L)
    }

    @Test
    fun playbackSeekSpeedAndHistoryLoad() {
        val text = "Este e um teste de leitura offline para validar a reproducao, " +
            "o avanco na barra, a mudanca de velocidade e o carregamento do historico. " +
            "O texto e repetido para dar tempo suficiente aos controlos. ".repeat(5)

        ReaderState.loadText(targetContext, text)
        val read = Intent(targetContext, ReaderPlaybackService::class.java).apply {
            action = ReaderPlaybackService.ACTION_READ
            putExtra(ReaderPlaybackService.EXTRA_TEXT, text)
            putExtra(ReaderPlaybackService.EXTRA_POSITION, 0L)
            putExtra(ReaderPlaybackService.EXTRA_VOICE, "pt-PT")
            putExtra(ReaderPlaybackService.EXTRA_SPEED, 1.0f)
        }
        targetContext.startService(read)

        assertTrue(
            "reader did not start playing",
            waitFor(90_000) { ReaderState.snapshot.value.status == ReadingStatus.PLAYING },
        )
        assertTrue(
            "reader position did not advance",
            waitFor(30_000) { ReaderState.snapshot.value.positionMs > 0 },
        )
        val beforeSpeed = ReaderState.snapshot.value.positionMs

        ReaderState.setSpeed(targetContext, 1.5f)
        assertTrue(
            "speed did not update",
            waitFor(10_000) { ReaderState.snapshot.value.speed == 1.5f },
        )
        assertTrue(
            "reader did not restart at the new speed",
            waitFor(60_000) { ReaderState.snapshot.value.status == ReadingStatus.PLAYING },
        )
        assertTrue(
            "speed change lost the live position",
            waitFor(30_000) { ReaderState.snapshot.value.positionMs > 0 },
        )

        targetContext.startService(
            Intent(targetContext, ReaderPlaybackService::class.java).apply {
                action = ReaderPlaybackService.ACTION_SEEK
                putExtra(ReaderPlaybackService.EXTRA_POSITION, 2_000L)
            },
        )
        assertTrue(
            "seek did not update the position",
            waitFor(10_000) { ReaderState.snapshot.value.positionMs >= 1_500L },
        )

        ReaderState.loadText(targetContext, "new text loaded while playing")
        Thread.sleep(1_000)
        assertEquals("new text loaded while playing", ReaderState.snapshot.value.text)
        assertEquals(ReadingStatus.IDLE, ReaderState.snapshot.value.status)
        assertEquals(0L, ReaderState.snapshot.value.positionMs)
        assertTrue(beforeSpeed > 0)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
