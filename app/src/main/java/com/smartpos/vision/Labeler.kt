package com.smartpos.vision

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Automatic labelling of a product photo using ML Kit's on-device image labeler
 * (bundled model, works offline).
 */
object Labeler {

    /** Labels that are technically correct but useless for grouping products. */
    private val tooGeneric = setOf(
        "packaging and labeling", "plastic", "material property", "font", "rectangle",
        "circle", "pattern", "tints and shades", "event", "fun", "technology",
        "electric blue", "magenta", "metal", "wood", "paper", "paper product",
        "liquid", "gas", "automotive design", "art", "sky", "cloud", "grass"
    )

    private val client by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build()
        )
    }

    /** Returns up to 6 suggested labels, best first. Empty if nothing was recognized. */
    suspend fun suggest(context: Context, file: File): List<String> = withContext(Dispatchers.IO) {
        val image = try {
            InputImage.fromFilePath(context, Uri.fromFile(file))
        } catch (e: Exception) {
            return@withContext emptyList<String>()
        }
        suspendCancellableCoroutine<List<String>> { cont ->
            client.process(image)
                .addOnSuccessListener { labels ->
                    val sorted = labels.sortedByDescending { it.confidence }.map { it.text }
                    val useful = sorted.filter { it.lowercase() !in tooGeneric }
                    val result = (if (useful.isNotEmpty()) useful else sorted).take(6)
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }
    }
}
