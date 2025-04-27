package mihon.feature.ocr

import android.graphics.Bitmap
import android.util.Log
import android.view.View
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
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.firstOrNull
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
    private class PageState(
        val bitmap: Bitmap,
        val regions: List<Detector.Result>,
    )

    private val context = activity.applicationContext
    private val viewModel = activity.viewModel
    private val ocr = MangaOcrManager(context, scope, lifecycle)
    private val detection = DetectorManager(context, scope, lifecycle)

    private val job = SupervisorJob()

    private var currentPage: ReaderPage? = null
    private val states = LruCache<ReaderPage, PageState>(4)

    fun detectText(view: View, viewX: Float, viewY: Float): Boolean {
        var x = viewX
        var y = viewY
        if (view is ReaderPageImageView) {
            // whooo hacks
            val unscaled = view.viewToSourceCoord(viewX, viewY)
            if (unscaled != null) {
                x = unscaled.x
                y = unscaled.y
            }
        }

        val state = states[currentPage ?: return false] ?: return false
        val region = state.regions.find { it.bbox.rect.contains(x, y) }
        if (region == null) {
            Log.v("TextDetector", "Tap ($x, $y) did not match any region")
            return false
        }

        Log.v("TextDetector", "Launching analysis at $x, $y")

        job.cancelChildren()
        scope.launch(job) {
            val croppedBitmap = Bitmap.createBitmap(
                state.bitmap,
                region.bbox.rect.left.toInt(),
                region.bbox.rect.top.toInt(),
                region.bbox.rect.width().toInt(),
                region.bbox.rect.height().toInt(),
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

            if (croppedBitmap !== state.bitmap) {
                croppedBitmap.recycle()
            }
        }
        return true
    }

    fun onPageSelected(page: ReaderPage) {
        currentPage = page
        scanPageForRegions(page)
    }

    fun scanPageForRegions(page: ReaderPage) {
        scope.launchUI {
            val existing = states[page]
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
            states.put(page, PageState(bitmap, results))
        }
    }

    private suspend fun ReaderPage.loadBitmap(): Bitmap? {
        if (status != Page.State.Ready) {
            statusFlow.firstOrNull { it == Page.State.Ready }
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
        return image.toBitmap()
    }
}
