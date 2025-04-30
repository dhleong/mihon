package mihon.feature.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import android.view.View
import androidx.annotation.MainThread
import androidx.collection.LruCache
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import coil3.toBitmap
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val eagerOCR: Boolean = false,
    private val language: String = "jp" // TODO
) {
    companion object {
        private val SUPPORTED_LANGUAGES = setOf("jp")
    }

    private class RegionState(
        val rect: RectF,
        val ocr: MutableStateFlow<MangaOcr.Result?> = MutableStateFlow(null),
    )
    private sealed interface PageState {
        val regions: List<RegionState>
        class FullyScanned(override val regions: List<RegionState>) : PageState
        class Pending(
            val bitmap: Bitmap,
            override val regions: List<RegionState>
        ) : PageState
    }

    private val context = activity.applicationContext
    private val viewModel = activity.viewModel
    private val ocr = MangaOcrManager(context, scope, lifecycle)
    private val detection = DetectorManager(context, scope, lifecycle)

    private val job = SupervisorJob()
    private var lastActiveJob: Job? = null
    private val eagerDetectLock = Mutex()

    private var currentPage: ReaderPage? = null
    private val states = LruCache<ReaderPage, PageState>(4)

    @MainThread
    fun detectText(view: View, viewX: Float, viewY: Float): Boolean {
        if (language !in SUPPORTED_LANGUAGES) {
            return false
        }

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
        val region = state.regions.find { it.rect.contains(x, y) }
        if (region == null) {
            Log.v("TextDetector", "Tap ($x, $y) did not match any region in ${state.regions}")
            return false
        }

        Log.v("TextDetector", "Launching analysis at $x, $y")

        lastActiveJob?.cancelChildren()
        lastActiveJob = scope.launch(job) {
            coroutineScope {
                val cancel = { this@coroutineScope.cancel() }
                eagerDetectLock.withLock {
                    performOcr(state, region) { value ->
                        when (value) {
                            is MangaOcr.Result.Partial -> viewModel.updateDetectingText(
                                RecognizedText(
                                    text = value.text.toString(),
                                    language = language
                                ),
                                cancel
                            )

                            is MangaOcr.Result.FinalResult -> viewModel.finishDetectingText(
                                RecognizedText(
                                    text = value.text,
                                    language = language
                                )
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun performOcr(
        page: PageState,
        region: RegionState,
        onEmit: (MangaOcr.Result) -> Unit = {},
    ) {
        if (region.ocr.value != null) {
            // Reuse existing value/follow existing flow
            region.ocr.transformWhile { state ->
                if (state != null) {
                    emit(state)
                }
                state !is MangaOcr.Result.FinalResult
            }.collect(onEmit)
            return
        }

        val bitmap = (page as? PageState.Pending)?.bitmap
            ?: throw IllegalStateException("FullyScanned page should not have any null OCR values")

        val croppedBitmap = Bitmap.createBitmap(
            page.bitmap,
            region.rect.left.toInt(),
            region.rect.top.toInt(),
            region.rect.width().toInt(),
            region.rect.height().toInt(),
        )

        // Show progress right away
        onEmit(MangaOcr.Result.Partial(""))

        ocr.process(croppedBitmap)
            .collect { value ->
                region.ocr.value = value
                onEmit(value)
            }

        if (croppedBitmap !== bitmap) {
            croppedBitmap.recycle()
        }
    }

    fun onPageSelected(config: PagerConfig, view: View, page: ReaderPage) {
        Log.v("TextDetector", "onPageSelected ($page / ${page.number})")
        currentPage = page

        if (language in SUPPORTED_LANGUAGES) {
            scanPageForRegions(config, view, page)
        }
    }

    private fun scanPageForRegions(config: PagerConfig, view: View, page: ReaderPage) {
        // NOTE: This function *could* be public to pre-scan... but may not be necessary
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
            val results = detection.process(bitmap).map {
                RegionState(it.bbox.rect)
            }
            Log.v("TextDetector", "Got: $results")
            val state = PageState.Pending(bitmap, results)
            states.put(page, state)

            if (!eagerOCR) {
                Log.v("TextDetector", "EagerOCR disabled")
                return@launchUI
            }

            // Eagerly detect text
            for (region in state.regions) {
                val currentPageNumber = currentPage?.number ?: 0
                if (currentPageNumber > page.number) {
                    Log.v("TextDetector", "Stopping early; $currentPageNumber > ${page.number}")
                    break
                }

                eagerDetectLock.withLock {
                    performOcr(state, region)
                }
            }

            // NOTE: By switching to a FullyScanned we release the reference to the bitmap
            // we loaded, enabling the memory to be freed.
            Log.v("TextDetector", "Fully scanned $page / ${page.number}")
            states.put(page, PageState.FullyScanned(results))
            state.bitmap.recycle()
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
            .bitmapConfig(Bitmap.Config.RGB_565)
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
        return image.toBitmap(
            width = image.width,
            height = image.height,
            config = Bitmap.Config.RGB_565,
        )
    }
}
