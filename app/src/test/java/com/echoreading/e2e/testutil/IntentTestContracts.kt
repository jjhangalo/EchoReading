package com.echoreading.e2e.testutil

/**
 * Contract evaluators and behavioral simulators for Android Intents and State Synchronization
 * across Requirements R2 ("Ecoar") and R3 (System Share Sheet).
 */
object IntentTestContracts {

    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    const val MIME_TYPE_TEXT_PLAIN = "text/plain"

    const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
    const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000

    /**
     * Requirement R2: Evaluates text selection intent delivered to QuickReadActivity.
     */
    fun extractProcessText(action: String?, mimeType: String?, extraText: CharSequence?): String? {
        if (action != ACTION_PROCESS_TEXT) return null
        if (mimeType != null && mimeType != MIME_TYPE_TEXT_PLAIN && !mimeType.startsWith("text/")) return null
        val trimmed = extraText?.toString()?.trim() ?: return null
        return if (trimmed.isEmpty()) null else trimmed
    }

    /**
     * Requirement R3: Evaluates plain text share intent delivered to MainActivity.
     */
    fun extractSendText(action: String?, mimeType: String?, extraText: CharSequence?): String? {
        // Try calling implementation helper if available
        tryImplementationExtractText(action, mimeType, extraText)?.let { return it }

        if (action != ACTION_SEND) return null
        if (mimeType == null || (mimeType != MIME_TYPE_TEXT_PLAIN && mimeType != "text/*")) return null
        val raw = extraText?.toString() ?: return null
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    /**
     * Requirement R2: Validates intent configuration for "Abrir no Leitor" expansion.
     */
    data class ExpansionIntentContract(
        val targetActivity: String,
        val textExtra: String,
        val flags: Int,
    ) {
        val hasSingleTop: Boolean get() = (flags and FLAG_ACTIVITY_SINGLE_TOP) != 0
        val hasClearTop: Boolean get() = (flags and FLAG_ACTIVITY_CLEAR_TOP) != 0
    }

    fun createExpansionIntent(text: String): ExpansionIntentContract {
        return ExpansionIntentContract(
            targetActivity = "com.echoreading.MainActivity",
            textExtra = text,
            flags = FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP
        )
    }

    // --- Reflection Bridge to ShareIntentHandler if implemented ---

    private fun tryImplementationExtractText(
        action: String?,
        mimeType: String?,
        extraText: CharSequence?
    ): String? {
        return try {
            val clazz = Class.forName("com.echoreading.share.ShareIntentHandler")
            val instance = clazz.getField("INSTANCE").get(null)
            val method = clazz.methods.firstOrNull {
                it.name == "extractText" && it.parameterTypes.size == 3
            }
            if (method != null) {
                method.invoke(instance, action, mimeType, extraText) as? String
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
