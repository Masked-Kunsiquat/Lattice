package com.github.maskedkunisquat.lattice

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.maskedkunisquat.lattice.core.data.model.LatticeTypeConverters
import com.github.maskedkunisquat.lattice.core.data.seed.SeedPersona
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "Lattice:SeedEmbedGen"

/**
 * One-shot generator: reads each persona seed file, runs every entry's content
 * through [EmbeddingProvider] (snowflake-arctic-embed-xs, 384-dim), and writes
 * updated JSON files with real embeddings to the device's Downloads/lattice-seeds/
 * folder — accessible directly from any file manager app.
 *
 * Run once per seed update:
 *   ./gradlew :app:connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.github.maskedkunisquat.lattice.SeedEmbeddingGenerator
 *
 * Then copy the three files from Downloads/lattice-seeds/ on the device into
 * app/src/main/assets/seeds/ and upload to the HuggingFace dataset.
 */
@RunWith(AndroidJUnit4::class)
class SeedEmbeddingGenerator {

    private val converters = LatticeTypeConverters()

    @Test
    fun generateEmbeddingsForAllPersonas() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val embeddingProvider = EmbeddingProvider()
        embeddingProvider.initialize(context)
        check(embeddingProvider.isInitialized) {
            "EmbeddingProvider failed to initialise — is snowflake-arctic-embed-xs.onnx in core-logic assets?"
        }
        Log.i(TAG, "EmbeddingProvider ready ✓")

        for (persona in SeedPersona.entries) {
            Log.i(TAG, "── $persona ──────────────────────────────")
            val rawJson = context.assets.open(persona.assetFileName).bufferedReader().readText()
            val root = JSONObject(rawJson)

            val entryCount  = embedArray(root.getJSONArray("journalEntries"), embeddingProvider)
            val moodCount   = root.optJSONArray("moodLogs")
                                  ?.let { embedArray(it, embeddingProvider) } ?: 0

            val total = entryCount + moodCount
            val filename = persona.assetFileName.substringAfterLast("/") // "holmes.json"
            writeToDownloads(context, filename, root.toString(2))
            Log.i(TAG, "✅ $persona — $total embeddings written → Downloads/lattice-seeds/$filename")
        }

        Log.i(TAG, "All done. Copy files from Downloads/lattice-seeds/ into app/src/main/assets/seeds/")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Iterates [array] of entry objects, generates an embedding for each
     * non-blank `content` field, and updates `embeddingBase64` in place.
     * Returns the number of embeddings generated.
     */
    private fun embedArray(array: JSONArray, provider: EmbeddingProvider): Int {
        var count = 0
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val content = entry.optString("content", "").trim()
            val text = content.ifEmpty {
                // Mood-only entries have no text — embed an empty string so the
                // field stays valid (zero vector, consistent with the fallback).
                ""
            }
            val embedding = runBlocking { provider.generateEmbedding(text) }
            val bytes = converters.fromFloatArray(embedding)
                ?: error("fromFloatArray returned null for entry $i (text length=${text.length})")
            entry.put("embeddingBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            count++
            if (count % 10 == 0) Log.i(TAG, "  ... $count embeddings so far")
        }
        return count
    }

    /**
     * Writes [content] as UTF-8 JSON to Downloads/lattice-seeds/[filename] using
     * MediaStore — no storage permission required on API 29+.
     *
     * If a file with the same name already exists in that folder a new versioned
     * entry is created by MediaStore automatically (OS handles deduplication).
     */
    private fun writeToDownloads(context: Context, filename: String, content: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/lattice-seeds")
        }
        val uri = context.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed for $filename — check WRITE_EXTERNAL_STORAGE or API level")
        context.contentResolver.openOutputStream(uri)
            ?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: error("Failed to open output stream for $filename")
    }
}
