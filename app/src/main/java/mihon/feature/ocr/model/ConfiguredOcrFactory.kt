package mihon.feature.ocr.model

class ConfiguredOcrFactory(
    val factory: Ocr.Factory,
    val mayBeUsedForOther: Boolean = false
)
