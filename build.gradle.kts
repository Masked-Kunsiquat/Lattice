// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
}

// ── Model download ────────────────────────────────────────────────────────────
//
// Gemma 3 1B Instruct (LiteRT / MediaPipe task bundle) is not committed to git.
// Run once before building:
//   ./gradlew downloadModels
//
// The file lands in app/src/main/assets/ (gitignored). Existing file is skipped.
// Source: https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert
//
// NOTE: The model requires accepting Google's Gemma Terms of Use on HuggingFace.
// Authenticate with `huggingface-cli login` (or set HF_TOKEN env var) before running.

private val HF_GEMMA = "https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert/resolve/main"

private val gemmaFiles = listOf(
    "gemma3_1b_it.task",
)

tasks.register("downloadModels") {
    group = "lattice"
    description = "Downloads Gemma 3 1B LiteRT task bundle from HuggingFace into app/src/main/assets/."
    doLast {
        val assetDir = file("app/src/main/assets")
        gemmaFiles.forEach { name ->
            val dest = assetDir.resolve(name)
            if (dest.exists()) {
                logger.lifecycle("  ✓  $name  (already present)")
                return@forEach
            }
            logger.lifecycle("  ↓  $name …")
            val tmp = assetDir.resolve("$name.tmp")
            try {
                hfDownload("$HF_GEMMA/$name", tmp, logger)
                if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true).also { tmp.delete() }
                logger.lifecycle("  ✓  $name  (${dest.length().toHuman()})")
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
        logger.lifecycle("downloadModels complete — all assets present.")
    }
}

/** Streams [url] (following up to 10 redirects) into [dest]. */
fun hfDownload(url: String, dest: File, logger: org.gradle.api.logging.Logger) {
    dest.parentFile.mkdirs()
    var location = url
    repeat(10) { attempt ->
        val conn = java.net.URI(location).toURL()
            .openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 30_000
        conn.readTimeout    = 60_000
        conn.setRequestProperty("User-Agent", "Gradle/Lattice-downloadModels")
        try {
            conn.connect()
            when (val code = conn.responseCode) {
                200 -> {
                    val total = conn.contentLengthLong
                    var downloaded = 0L
                    var lastMilestone = 0L
                    val milestone = 200 * 1024 * 1024L // log every 200 MB
                    conn.inputStream.buffered(8 * 1024 * 1024).use { src ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(1024 * 1024)
                            var n: Int
                            while (src.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                downloaded += n
                                if (total > 0 && downloaded - lastMilestone >= milestone) {
                                    lastMilestone = downloaded
                                    val pct = downloaded * 100 / total
                                    logger.lifecycle("      $pct%  (${downloaded.toHuman()} / ${total.toHuman()})")
                                }
                            }
                        }
                    }
                    return
                }
                301, 302, 303, 307, 308 -> {
                    val raw = conn.getHeaderField("Location")
                        ?: throw GradleException("Redirect $code with no Location header (attempt $attempt) for $url")
                    // Resolve relative redirects against the current URL, then validate the
                    // result before following it (guards against open-redirect exploitation).
                    val resolved = java.net.URI(location).resolve(raw)
                    val originalHost = java.net.URI(url).host
                    if (resolved.scheme != "https" || resolved.host != originalHost) {
                        throw GradleException(
                            "Redirect to untrusted location '${resolved}' rejected (expected https://$originalHost)"
                        )
                    }
                    location = resolved.toString()
                }
                else -> throw GradleException("HTTP $code downloading $url")
            }
        } finally {
            conn.disconnect()
        }
    }
    throw GradleException("Too many redirects for $url")
}

fun Long.toHuman(): String = when {
    this >= 1_000_000_000 -> "%.1f GB".format(this / 1_000_000_000.0)
    this >= 1_000_000     -> "%.1f MB".format(this / 1_000_000.0)
    this >= 1_000         -> "%.1f KB".format(this / 1_000.0)
    else                  -> "$this B"
}
