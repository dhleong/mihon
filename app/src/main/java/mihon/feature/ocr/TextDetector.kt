package mihon.feature.ocr

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import androidx.annotation.RequiresPermission
import androidx.collection.LruCache
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.dhleong.mangaocr.Detector
import net.dhleong.mangaocr.DetectorManager
import net.dhleong.mangaocr.MangaOcr
import net.dhleong.mangaocr.MangaOcrManager
import okio.Buffer
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withIOContext

class TextDetector(
    activity: ReaderActivity,
    private val scope: LifecycleCoroutineScope,
    lifecycle: Lifecycle,
) {
    private val context = activity.applicationContext
    private val viewModel = activity.viewModel
    private val ocr = MangaOcrManager(context, scope, lifecycle)
    private val detection = DetectorManager(context, scope, lifecycle)
    private val size = 200
    private val halfSize = size / 2

    private val job = SupervisorJob()

    private var currentPage: ReaderPage? = null
    private val regions = LruCache<ReaderPage, List<Detector.Result>>(8)

    fun detectText(view: View, x: Float, y: Float): Boolean {
        val regions = regions[currentPage ?: return false] ?: return false
        // TODO map x, y to the un-zoomed image coords
        if (!regions.any { it.bbox.rect.contains(x, y) }) {
            Log.v("TextDetector", "Tap ($x, $y)  of any regions")
            return false
        }

        Log.v("TextDetector", "Launching analysis at $x, $y")
        val bitmap = view.drawToBitmap()

        job.cancelChildren()
        scope.launch(job) {
            val dx = (x.toInt() - halfSize).coerceAtLeast(0)
            val dy = (y.toInt() - halfSize).coerceAtLeast(0)
            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                dx, dy,
                size.coerceAtMost(bitmap.width - dx),
                size.coerceAtMost(bitmap.height - dy)
            )

            // Show progress right away
            viewModel.updateDetectingText(RecognizedText(
                text = "",
                language = "jp"
            ))

            ocr.process(croppedBitmap)
                .collect { value ->
                    when (value) {
                        is MangaOcr.Result.Partial ->
                            viewModel.updateDetectingText(RecognizedText(
                                text = value.text.toString(),
                                language = "jp"
                            ))
                        is MangaOcr.Result.FinalResult ->
                            viewModel.finishDetectingText(RecognizedText(
                                text = value.text,
                                language = "jp"
                            ))
                    }
                }

            croppedBitmap.recycle()
            bitmap.recycle()
        }
        return true
    }

    fun onPageSelected(page: ReaderPage) {
        currentPage = page
        scanPageForRegions(page)
    }

    fun scanPageForRegions(page: ReaderPage) {
        scope.launchUI {
            val existing = regions[page]
            Log.v("TextDetector", "onPageSelected ($page) existing = $existing")
            if (existing != null) {
                return@launchUI
            }

            val bitmap = page.loadBitmap()
            if (bitmap == null) {
                Log.v("TextDetector", "Failed to load bitmap for $page")
                return@launchUI
            }

            Log.v("TextDetector", "Process $page...")
            val results = detection.process(bitmap)
            Log.v("TextDetector", "Got: $results")
            regions.put(page, results)
        }
    }

    private suspend fun ReaderPage.loadBitmap(): Bitmap? {
        if (status != Page.State.Ready) {
            statusFlow.first { it == Page.State.Ready }
        }

        val stream = stream?.invoke()
            ?: return null

        val buffer = withIOContext {
            stream.use { input -> Buffer().readFrom(input) }
        }

        val request = ImageRequest.Builder(context)
            .data(buffer)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .allowHardware(false)
        val image = when (val result = context.imageLoader.execute(request.build())) {
            is SuccessResult -> result.image
            else -> {
                Log.v("TextDetector", "Failed to load bitmap: $result")
                return null
            }
        }
        return image.toBitmap(1024, 1024)
    }
}
