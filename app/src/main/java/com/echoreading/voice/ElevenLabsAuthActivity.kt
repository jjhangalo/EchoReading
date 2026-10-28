package com.echoreading.voice

import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.echoreading.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Only accepts the exact OAuth callback; state and issuer are checked before exchanging a code. */
class ElevenLabsAuthActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var processing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    private fun handleCallback(incoming: Intent?) {
        val callback = incoming?.data
        if (processing || incoming?.action != Intent.ACTION_VIEW || callback == null) {
            finish()
            return
        }
        processing = true
        scope.launch {
            val result = ElevenLabsAuth.finishSignIn(applicationContext, callback)
            Toast.makeText(this@ElevenLabsAuthActivity,
                if (result.isSuccess) "ElevenLabs ligado" else "Não foi possível ligar à ElevenLabs",
                Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@ElevenLabsAuthActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
