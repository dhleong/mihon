package mihon.feature.ocr.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.flow.Flow
import net.dhleong.mangaocr.Detector
import net.dhleong.mangaocr.MangaOcr

interface Ocr {
    interface Factory {
        fun getSupportedLanguages(): Set<String>
        fun create(
            context: Context,
            scope: LifecycleCoroutineScope,
            lifecycle: Lifecycle,
        ): Ocr
    }

    suspend fun detectTextRegions(bitmap: Bitmap): List<Detector.Result>
    suspend fun extractText(bitmap: Bitmap, region: RectF): Flow<MangaOcr.Result>
}
