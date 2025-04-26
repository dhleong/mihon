package mihon.feature.ocr

import android.graphics.Bitmap
import android.view.View
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import net.dhleong.mangaocr.MangaOcr
import net.dhleong.mangaocr.MangaOcrManager

class TextDetector(
    activity: ReaderActivity,
    private val scope: LifecycleCoroutineScope,
    lifecycle: Lifecycle,
) {
    private val viewModel = activity.viewModel
    private val ocr = MangaOcrManager(activity.applicationContext, scope, lifecycle)
    private val size = 200
    private val halfSize = size / 2

    private val job = SupervisorJob()

    fun detectText(view: View, x: Float, y: Float): Boolean {
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
}
