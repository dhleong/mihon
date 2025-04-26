package mihon.feature.ocr

import android.content.Context
import android.content.Intent

object TranslationIntent {
    private const val GOOGLE_TRANSLATE_PACKAGE = "com.google.android.apps.translate"

    fun resolve(context: Context, text: RecognizedText): Intent? {
        val packageManager = context.packageManager
        for (intent in generateIntents(text)) {
            if (packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }

        return null
    }

    private fun generateIntents(recognizedText: RecognizedText) = sequence {
        yield(
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                `package` = GOOGLE_TRANSLATE_PACKAGE
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, recognizedText.text)
            },
        )

        yield(
            Intent(Intent.ACTION_SEND).apply {
                `package` = GOOGLE_TRANSLATE_PACKAGE
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, recognizedText.text)
                putExtra("key_text_input", recognizedText.text)
                putExtra("key_language_from", recognizedText.language)
                putExtra("key_from_floating_window", true)
            },
        )
    }
}
