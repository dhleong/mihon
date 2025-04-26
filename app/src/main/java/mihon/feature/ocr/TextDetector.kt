package mihon.feature.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import net.dhleong.mangaocr.MangaOcrManager
import tachiyomi.presentation.core.components.material.AlertDialogContent

class TextDetector(
    context: Context,
    private val scope: LifecycleCoroutineScope,
    lifecycle: Lifecycle,
) {
    private val ocr = MangaOcrManager(context, scope, lifecycle)
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

            ocr.process(croppedBitmap)
                .collect {
                    Log.v("OCR", "state=$it")
                }

            croppedBitmap.recycle()
            bitmap.recycle()
        }
        return true
    }
}
