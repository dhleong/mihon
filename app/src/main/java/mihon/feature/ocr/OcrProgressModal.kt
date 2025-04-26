package mihon.feature.ocr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.presentation.core.components.material.padding

@Composable
fun OcrProgressModal(
    onDismissRequest: () -> Unit,
    text: RecognizedText,
    isLoading: Boolean,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            Text(text.text)
        }
    }
}
