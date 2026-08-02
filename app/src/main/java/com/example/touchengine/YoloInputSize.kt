package com.example.touchengine

/** The input sizes exported with the bundled UI detector models. */
enum class YoloInputSize(
    val pixels: Int,
    val modelAsset: String
) {
    SIZE_640(640, "yolo/ui-detector-fp32.tflite"),
    SIZE_960(960, "yolo/ui-detector-fp32-960.tflite");

    companion object {
        fun fromPreference(value: String?): YoloInputSize =
            entries.firstOrNull { it.name == value } ?: SIZE_640
    }
}
