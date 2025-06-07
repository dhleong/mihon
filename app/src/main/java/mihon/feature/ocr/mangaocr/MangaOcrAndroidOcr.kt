package mihon.feature.ocr.mangaocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import mihon.feature.ocr.model.Ocr
import net.dhleong.mangaocr.Detector
import net.dhleong.mangaocr.DetectorManager
import net.dhleong.mangaocr.MangaOcr
import net.dhleong.mangaocr.MangaOcrManager

class MangaOcrAndroidOcr(
    context: Context,
    scope: LifecycleCoroutineScope,
    lifecycle: Lifecycle
) : Ocr {
    object Factory : Ocr.Factory {
        override fun getSupportedLanguages(): Set<String> = setOf("ja")

        override fun create(context: Context, scope: LifecycleCoroutineScope, lifecycle: Lifecycle): Ocr =
            MangaOcrAndroidOcr(context, scope, lifecycle)
    }

    private val ocr = MangaOcrManager(context, scope, lifecycle)
    private val detection = DetectorManager(context, scope, lifecycle)

    override suspend fun detectTextRegions(bitmap: Bitmap): List<Detector.Result> =
        detection.process(bitmap)

    override suspend fun extractText(bitmap: Bitmap, region: RectF): Flow<MangaOcr.Result> {
        val left = region.left.toInt().coerceAtLeast(0)
        val top = region.top.toInt().coerceAtLeast(0)
        val croppedBitmap = Bitmap.createBitmap(
            bitmap,
            left,
            top,
            region.width().toInt().coerceAtMost(
                bitmap.width - left,
            ),
            region.height().toInt().coerceAtMost(
                bitmap.height - top,
            ),
        )

        return ocr.process(croppedBitmap).onCompletion {
            if (croppedBitmap !== bitmap) {
                croppedBitmap.recycle()
            }
        }
    }
}
