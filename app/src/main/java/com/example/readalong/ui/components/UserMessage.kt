package com.example.readalong.ui.components

/**
 * The text to show a person for [this] failure. Messages the app wrote itself (require/check/error,
 * or one of its own exception types) are shown as they are; anything from the platform or a library
 * ("Unable to resolve host…", "FileNotFoundException: /storage/…", "Check failed.") becomes
 * [fallback], so no developer text reaches the screen. The original is still in the log.
 */
fun Throwable.userMessage(fallback: String): String {
    val text = message?.trim().orEmpty()
    val ours = this is IllegalArgumentException || this is IllegalStateException || javaClass.name.startsWith("com.example.readalong")
    val technical = text.isEmpty() || !text.first().isUpperCase() || text in setOf("Check failed.", "Failed requirement.", "Required value was null.") ||
        "Exception" in text || "://" in text || text.startsWith("/") || text.contains(" answered ")
    if (!ours || technical) android.util.Log.w("ReadAlong", "Shown as \"$fallback\"", this)
    return if (ours && !technical) text else fallback
}
