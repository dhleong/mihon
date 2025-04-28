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
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import coil3.toBitmap
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
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
                Log.v("TextDetector", "Translated tap ($viewX, $viewY) -> ($x, $y)")
            }
        }

        val state = states[currentPage ?: return false] ?: return false
        val region = state.regions.find { it.bbox.rect.contains(x, y) }
        if (region == null) {
            Log.v("TextDetector", "Tap ($x, $y) did not match any region in ${state.regions}")
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

    fun onPageSelected(config: PagerConfig, view: View, page: ReaderPage) {
        Log.v("TextDetector", "onPageSelected ($page / ${page.number})")
        currentPage = page
        scanPageForRegions(config, view, page)
    }

    fun scanPageForRegions(config: PagerConfig, view: View, page: ReaderPage) {
        scope.launchUI {
            val existing = states[page]
            Log.v("TextDetector", "scanPageForRegions ($page / ${page.number}) existing = $existing")
            if (existing != null) {
                return@launchUI
            }

            val bitmap = page.loadBitmap(config, view)
            if (bitmap == null) {
                Log.v("TextDetector", "Failed to load bitmap for $page")
                return@launchUI
            }

            Log.v("TextDetector", "Process ($page / ${page.number}) @ ${bitmap.width} / ${bitmap.height}...")
            val results = detection.process(bitmap)
            Log.v("TextDetector", "Got: $results")
            states.put(page, PageState(bitmap, results))
        }
    }

    private suspend fun ReaderPage.loadBitmap(config: PagerConfig, view: View): Bitmap? {
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
            .precision(Precision.INEXACT)
            .size(ViewSizeResolver(view))
            .cropBorders(config.imageCropBorders)
            .customDecoder(true)
            .crossfade(false)
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
