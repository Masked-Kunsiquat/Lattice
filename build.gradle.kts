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
// Gemma 3 1B Instruct (LiteRT) is not committed to git. Run once before building:
//   ./gradlew downloadModels
//
// The correct hardware tier is selected by querying the connected device via ADB.
// Override with -PdownloadTier=elite|ultra|universal if no device is attached.
//
//   elite     gemma3-1b-it-elite.litertlm   SM8750 (S25 Ultra) — Adreno 830 AOT kernels
//   ultra     gemma3-1b-it-ultra.litertlm   SM8650 (S24 Ultra) — Adreno 750 AOT kernels
//   universal gemma3-1b-it-int4.litertlm    Any ARM64           — JIT / OpenCL fallback
//
// Source: https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert
// NOTE: Requires accepting Google's Gemma Terms of Use. Authenticate with
//       `huggingface-cli login` (or set HF_TOKEN) before running.

private val HF_GEMMA = "https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert/resolve/main"

tasks.register("downloadModels") {
    group = "lattice"
    description = "Downloads the Gemma 3 1B LiteRT model tier for the connected device into app/src/main/assets/."
    doLast {
        val assetDir = file("app/src/main/assets")

        // ── Tier selection ────────────────────────────────────────────────────
        // Priority: -PdownloadTier CLI property > ADB device query > universal fallback
        val tier: String = run {
            val propOverride = findProperty("downloadTier") as String?
            if (propOverride != null) {
                logger.lifecycle("  ℹ  Using -PdownloadTier=$propOverride")
                return@run propOverride
            }
            try {
                val pb = ProcessBuilder("adb", "shell", "getprop", "ro.product.board")
                val proc = pb.start()
                val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited) {
                    proc.destroyForcibly()
                    logger.lifecycle("  ℹ  ADB timed out — using universal tier.")
                    return@run "universal"
                }
                val board = proc.inputStream.bufferedReader().use { it.readText() }.trim()
                logger.lifecycle("  ℹ  Detected ro.product.board=$board")
                when (board.lowercase()) {
                    "kailua" -> "elite"   // Snapdragon 8 Elite (SM8750)
                    "kalama" -> "ultra"   // Snapdragon 8 Gen 3 (SM8650)
                    else -> {
                        if (board.isNotEmpty())
                            logger.lifecycle("  ℹ  Unknown board '$board' — falling back to universal tier")
                        "universal"
                    }
                }
            } catch (e: Exception) {
                logger.lifecycle(
                    "  ℹ  ADB unavailable (${e.message}) — using universal tier. " +
                    "Connect a device or pass -PdownloadTier=elite|ultra|universal."
                )
                "universal"
            }
        }

        val modelFile = when (tier) {
            "elite" -> "gemma3-1b-it-elite.litertlm"
            "ultra" -> "gemma3-1b-it-ultra.litertlm"
            else    -> "gemma3-1b-it-int4.litertlm"
        }

        // ── Download ──────────────────────────────────────────────────────────
        val dest = assetDir.resolve(modelFile)
        if (dest.exists()) {
            logger.lifecycle("  ✓  $modelFile  (already present)")
        } else {
            logger.lifecycle("  ↓  $modelFile …")
            val tmp = assetDir.resolve("$modelFile.tmp")
            try {
                hfDownload("$HF_GEMMA/$modelFile", tmp, logger)
                if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true).also { tmp.delete() }
                logger.lifecycle("  ✓  $modelFile  (${dest.length().toHuman()})")
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
        logger.lifecycle("downloadModels complete — $modelFile ready in app/src/main/assets/.")
    }
}

/** Streams [url] (following up to 10 redirects) into [dest]. */
fun hfDownload(url: String, dest: File, logger: org.gradle.api.logging.Logger) {
    dest.parentFile.mkdirs()
    var location = url

    val hfToken = System.getenv("HF_TOKEN")
        ?: file("${System.getProperty("user.home")}/.cache/huggingface/token").let { if (it.exists()) it.readText().trim() else null }

    repeat(10) { attempt ->
        val conn = java.net.URI(location).toURL()
            .openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 30_000
        conn.readTimeout    = 60_000
        conn.setRequestProperty("User-Agent", "Gradle/Lattice-downloadModels")
        if (hfToken != null) {
            conn.setRequestProperty("Authorization", "Bearer $hfToken")
        }

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
                    if (resolved.scheme != "https" || (resolved.host != originalHost && resolved.host?.endsWith(".huggingface.co") != true)) {
                        throw GradleException(
                            "Redirect to untrusted location '${resolved}' rejected (expected https://$originalHost or *.huggingface.co)"
                        )
                    }
                    location = resolved.toString()
                }
                401, 403 -> {
                    throw GradleException(
                        "HTTP $code: HuggingFace access denied. Please ensure you have accepted the Gemma license " +
                        "at https://huggingface.co/google/gemma-3-1b-it and are authenticated. " +
                        "Run `huggingface-cli login` or set the HF_TOKEN environment variable."
                    )
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
