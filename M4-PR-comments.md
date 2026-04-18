## Running commit message

```
fix: address inline review findings from m4-stability (part 5)

- ModelDownloadWorker: move from :core-logic to :app; add ModelDownloader fun
  interface in core-logic; inject into LocalFallbackProvider; implement via
  WorkManagerModelDownloader; remove getDownloadWorkInfo() from LocalFallbackProvider;
  SettingsViewModel now queries WorkManager directly
- ModelDownloadWorker: add PermanentDownloadException; rethrow CancellationException;
  Result.failure() for permanent errors (size/hash/engine); Result.retry() for transient
- ModelDownloadWorker.downloadFile: resolve redirects against current URL via URI.resolve();
  validate HTTPS + host allowlist; always disconnect HttpURLConnection in finally block
- ReframingLoop: inject Logger (no-op default); replace all Log.d/Log.w with logger calls;
  remove android.util.Log import
- ReframingLoop.parseDotOutput: require DISTORTIONS: sentinel; return empty distortions
  immediately when absent; remove greedy label-scan fallback
- DistortionMlpTest: rename "predicts all false at default threshold" →
  "predicts all true at default threshold" to match actual >= boundary behavior
- DistortionMlpTrainer: remove Context/Log; add CheckpointWriter fun interface and Logger;
  inject both via constructor; save() delegates to writer (throws if no writer injected)
- DistortionMlpTrainer.trainStep: guard embedding finiteness before any mlp mutation
- DistortionCheckpointWriter (new :app class): Android implementation of CheckpointWriter;
  propagates hash failures and throws on manifest commit returning false
```

---

Verify each finding against the current code and only fix it if needed.

- [x] In `@app/src/main/java/com/github/maskedkunisquat/lattice/LatticeApplication.kt`
  around lines 142 - 150, reframingLoop is being initialized with
  runBlocking(Dispatchers.IO) calls for AffectiveMlp.load and DistortionMlp.load
  which blocks startup; instead construct ReframingLoop with null heads and kick
  off non-blocking coroutines in applicationScope to load and hot-swap them once
  ready—call AffectiveMlp.load and DistortionMlp.load from
  applicationScope.launch(Dispatchers.IO) and assign the results into
  ReframingLoop's mutable properties or via provided setter methods (referencing
  reframingLoop, ReframingLoop, affectiveMlp, distortionMlp, AffectiveMlp.load,
  DistortionMlp.load, and applicationScope) so startup is not blocked and the loop
  degrades safely until the models are loaded.

-----

- [x] In
  `@app/src/main/java/com/github/maskedkunisquat/lattice/ui/SearchHistoryViewModel.kt`
  around lines 56 - 58, SearchHistoryViewModel is caching full unmasked entries
  via journalRepository.getEntries() in allEntriesState which exposes sensitive
  content; change the ViewModel to use a projection/count API that only returns
  IDs/tagIds/placeIds or dedicated count methods (e.g., getEntryRefs(),
  countEntriesByPlaceIds(), countEntriesByTagIds()) instead of getEntries(),
  update all usages in this file (including the block around lines 140-147) to
  consume the lightweight projection/count results, and leave unmasking to the UI
  layer where full content is needed.


- [x] In
  `@app/src/main/java/com/github/maskedkunisquat/lattice/ui/SearchHistoryViewModel.kt`
  around lines 100 - 159, dispatchSearch currently cancels semanticJob/likeJob but
  leaves previous result lists visible, so clear stale results immediately or
  ensure results are only applied for the active query: at the start of
  dispatchSearch (when query is non-blank) update _uiState to reset
  entryResults/peopleResults/placeResults/tagResults and set
  isSemanticLoading/isLikeLoading appropriately; alternatively, capture the
  current query into a local val (e.g., currentQuery) inside each coroutine and
  before updating _uiState verify the query still equals currentQuery (or that
  semanticJob/likeJob has not been cancelled) so repository responses
  (searchRepository.findSimilarEntries, peopleRepository.searchByName,
  placeRepository.searchPlaces, tagRepository.searchTags) only overwrite state
  when they match the active query.

-----

- [x] In `@build.gradle.kts` around lines 43 - 46, The code currently reads
  proc.inputStream.bufferedReader().use { it.readText() } before calling
  proc.waitFor(...), which can block indefinitely; change the sequence in the
  block that creates the ProcessBuilder/Process (ProcessBuilder("adb"...),
  pb.start(), proc) to call proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
  first, check the boolean result, and only then read the output via
  proc.inputStream.bufferedReader().use { it.readText() } if the process exited;
  if waitFor returns false, call proc.destroy() or proc.destroyForcibly() and
  handle the timeout case (e.g., set board to empty or an error) so the ADB hang
  cannot block the build.

-----

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionDatasetLoader.kt`
  around lines 58 - 60, The load() path returns a stale/corrupt cache file
  directly by calling deserialize on cacheFile.inputStream(), causing permanent
  failures; wrap the cache read in a try/catch around deserialize (in the method
  load() where cacheFile and deserialize are used), catch IO/serialization-related
  exceptions (e.g., IOException, SerializationException), delete the invalid
  cacheFile, log the error and then continue to the normal generation path to
  recreate the cache (i.e., retry the load by falling through to the code that
  builds and writes the fresh dataset); do not suppress non-recoverable
  exceptions—rethrow them if regeneration fails.

Verify each finding against the current code and only fix it if needed.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionDatasetLoader.kt`
  around lines 63 - 84, The code currently generates and serializes zero-vector
  embeddings when embeddingProvider.isInitialized is false; change this so we
  don't write a bad cache: either fail fast by throwing or return early after
  logging (e.g., check embeddingProvider.isInitialized before the ASSET_PATHS loop
  and throw IllegalStateException or return), or allow generation but set a flag
  (e.g., skipCacheWrite) if !embeddingProvider.isInitialized and only call
  serialize(samples, cacheFile) when that flag is false; refer to
  embeddingProvider.isInitialized, generateEmbedding(...), and serialize(...) to
  locate and guard the cache write.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionDatasetLoader.kt`
  around lines 76 - 78, The loader currently passes raw text from
  parseJsonlRow(line) directly to embeddingProvider.generateEmbedding; update
  DistortionDatasetLoader to mask PII before embedding by calling
  PiiShield.mask(text) (or validate that text is already masked) and then pass the
  masked text into embeddingProvider.generateEmbedding; keep construction of
  DistortionSample(embedding, labels) unchanged but ensure the variable used is
  the masked text to satisfy the requirement that all names are PiiShield-masked
  before any embedding call.

- [x] In
`@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionDatasetLoader.kt`
around lines 3 - 4, DistortionDatasetLoader currently imports and uses Android
APIs (Context, android.util.Log, context.assets, filesDir); refactor it to
remove direct Android dependencies by injecting small JVM-friendly abstractions:
replace Context usage with an AssetSource (method(s) to open asset
InputStreams), a CacheFileProvider (method to get cache directory File or
File.createTempFile), and a Logger interface used instead of android.util.Log;
update the DistortionDatasetLoader constructor (or factory) to accept these
interfaces and change internal calls that use context.assets, filesDir, and Log
to use the new interfaces so the core-logic module contains no Android framework
references (alternatively move all asset/cache I/O and logging code into the
:app module and keep DistortionDatasetLoader pure business logic).

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionDatasetLoader.kt`
  around lines 70 - 72, In the ASSET_PATHS loop inside DistortionDatasetLoader
  (where you call context.assets.open(assetPath).bufferedReader().readLines()),
  the asset stream is never closed; change this to use a Kotlin use or useLines
  block so the BufferedReader/InputStream is closed automatically — e.g. call
  context.assets.open(assetPath).bufferedReader().use { it.readLines().filter {
  it.isNotBlank() } } (or useLines with sequence processing) and keep the rest of
  the logic that consumes the filtered lines unchanged.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionDatasetLoader.kt`
  around lines 123 - 129, The deserialize(InputStream) implementation reads the
  entire stream into bytes and immediately wraps it in a ByteBuffer, but it
  doesn't validate the header length and will throw BufferUnderflowException when
  the stream is shorter than the 12-byte header; before creating/reading from the
  ByteBuffer (i.e., before reading count, dim, numClasses) check bytes.size (or
  bytes.length) >= 12 and if not throw an IllegalArgumentException with a clear
  message about the expected 12-byte header, so callers receive the documented
  exception type and a helpful error message.
-----

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionMlp.kt`
  at line 3, DistortionMlp currently depends on Android (import android.util.Log
  and usage of Context/assets/filesDir/SharedPreferences) which must be removed;
  keep the public loadWeights(file, thresholds) API but refactor the
  implementation to accept plain-Java inputs (e.g., java.io.File,
  java.nio.file.Path, InputStream or a byte array) or an injected StorageProvider
  interface and move any Android-specific manifest/hash/file resolution into the
  app module or an injected implementation; remove the android.util.Log import and
  any Context/SharedPreferences/assets/filesDir usage from class DistortionMlp and
  related methods, update methods that read weight files (e.g., loadWeights, any
  helper methods in DistortionMlp) to work with the neutral storage types or an
  interface, and add a minimal storage abstraction to be implemented in :app for
  Android specifics.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionMlp.kt`
  around lines 47 - 69, The model currently trusts thresholds and inference
  embeddings without guarding against NaN/Inf or out-of-range values; add
  validation so thresholds are finite and within [0,1] and embeddings are finite
  before computing logits. In the DistortionMlp init block validate thresholds
  with something like thresholds.all { it.isFinite() && it in 0f..1f } and fail
  fast if not; in forward add require(embedding.all { it.isFinite() }) (or
  similarly bounded checks) before calling rawLogits(embedding) so
  rawLogits/forward cannot produce silently skewed comparisons against NaN/Inf.

-----

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/LlmProvider.kt`
  at line 10, Update the KDoc references that incorrectly say "MediaPipe Tasks
  GenAI" to "LiteRT-LM" so comments match the implementation: change the
  occurrence in the doc for LocalFallbackProvider (referenced symbol
  LocalFallbackProvider) and any related KDoc in LlmOrchestrator (referenced
  symbol LlmOrchestrator and ConversationConfig) to mention "LiteRT-LM" (and keep
  the existing ConversationConfig/Contents.of()/Engine references) for consistency
  with the imported com.google.ai.edge.litertlm package.

-----

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/LocalFallbackProvider.kt`
  around lines 94 - 111, downloadModel() never passes the expected SHA-256 to
  ModelDownloadWorker so integrity verification is skipped; add a companion map
  (e.g., MODEL_SHA256) keyed by the model file names and pass the corresponding
  hash into the WorkRequest input data under ModelDownloadWorker.KEY_SHA256 when
  building downloadRequest (use the selected modelFile from
  selectModelAndBackends()); keep existing HF_BASE_URL/KEY_URL wiring and only add
  the KEY_SHA256 entry so ModelDownloadWorker can verify the downloaded blob.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/LocalFallbackProvider.kt`
  around lines 264 - 295, The selectModelAndBackends logic incorrectly omits
  Build.BOARD == "kailua" from the Elite branch; update the condition in
  selectModelAndBackends so that the Elite tier branch includes "kailua" (same way
  as "sun" or "sm8750") and ensure isQualcommDevice() still treats "kailua" as
  Qualcomm if needed—adjust the branch that returns MODEL_FILE_ELITE to include
  board == "kailua" (and keep dispatchLibAvailable check) so Pixel 9 Pro devices
  route to MODEL_FILE_ELITE with Backend.NPU(nativeLibraryDir = nativeLibDir).

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/LocalFallbackProvider.kt`
  around lines 300 - 314, The build script still references the removed
  gemma3-1b-it-universal.task which 404s; update the fallback else branch in
  build.gradle.kts to use the int4 artifact name "gemma3-1b-it-int4.litertlm" to
  match the runtime constant MODEL_FILE_INT4 (and the MODEL_FILE alias) in
  LocalFallbackProvider.kt so the fallback download succeeds when no device/tier
  matches.
-----

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/ModelDownloadWorker.kt`
  around lines 3 - 20, ModelDownloadWorker and its Android/WorkManager/network
  code (class ModelDownloadWorker, methods using CoroutineWorker, ForegroundInfo,
  NotificationCompat, and direct HTTP/MessageDigest file code) must be removed
  from :core-logic and implemented in the :app module; expose a simple
  platform-agnostic interface in core-logic (e.g., ModelDownloader or
  ModelAcquisition with methods like downloadModelIfNeeded(modelId: String): File)
  and update callers in core-logic to depend only on that interface, then
  implement the Android-specific worker and notification/network logic in :app
  (providing the interface implementation via DI or a factory) so core-logic
  contains no Android framework or runtime network code; also ensure build/test
  config uses unitTests.returnDefaultValues = true and that AndroidManifest /
  gradle in :core-logic do not request internet permissions.


- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/ModelDownloadWorker.kt`
  around lines 73 - 101, The catch-all Exception handler in ModelDownloadWorker
  swallows cancellation and permanent failures; change the error model so
  permanent errors are not retried: introduce a custom unchecked
  PermanentDownloadException and throw it instead of IOException for the
  "Downloaded file too small", SHA-256 mismatch, and engine-init failure checks
  (references: the length check, sha256Hex result comparison, and
  localFallbackProvider.modelLoadState check inside ModelDownloadWorker), then
  replace the current catch (e: Exception) block with: rethrow immediately for
  CancellationException, treat PermanentDownloadException as a hard failure (log,
  delete tmp, return Result.failure()), and treat all other exceptions as
  transient (log, delete tmp, return Result.retry()); this ensures cancelled work
  and permanent errors are not rescheduled.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/ModelDownloadWorker.kt`
  around lines 117 - 132, In downloadFile, validate and safely resolve redirects
  before following them: when handling the 3xx branch in downloadFile(), resolve
  the "Location" header against the current URL (use the original
  URL/URL(location) as base), ensure the resulting URL uses HTTPS and its host is
  in an allowlist, and only then assign location to the resolved value; also limit
  redirect count as already done. Additionally ensure each HttpURLConnection
  (conn) is disconnected in a finally block to free resources (i.e., always call
  conn.disconnect() for the connection created in the loop). Use the existing
  variables/method names (downloadFile, location, conn,
  getHeaderField("Location")) to locate and change the logic.

-----

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/ReframingLoop.kt`
  around lines 119 - 127, The core module now directly uses android.util.Log
  (Log.d/Log.w with TAG) inside ReframingLoop.kt (notably the Stage2 block that
  builds DiagnosisResult and its onFailure handler), which introduces an Android
  dependency; instead add an injected, platform-agnostic logger interface (e.g.,
  Logger with debug/warn methods) to ReframingLoop (or accept one via
  constructor), replace the Log.d and Log.w calls in the Stage2 path and the other
  occurrences (around lines mentioned) with that logger, and keep the same
  messages and exception logging behavior before returning the
  DiagnosisResult/getOrNull; ensure the core module does not import
  android.util.Log after this change.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/ReframingLoop.kt`
  around lines 322 - 360, parseDotOutput currently falls back to greedy label
  scanning when the "DISTORTIONS:" sentinel is missing which can misclassify
  prompt echoes; change parseDotOutput to require the sentinel and not infer
  labels from the whole output: in the function parseDotOutput, use sentinelLine
  (found by searching for "DISTORTIONS:") as the only source of CSV; if
  sentinelLine is null, return DiagnosisResult with distortions = emptyList() (and
  reasoning = raw) instead of performing the greedy scan and remove the
  commaTokens->greedy fallback block (the code paths that call
  CognitiveDistortion.entries and csv.contains); also keep the existing
  comma-splitting and CognitiveDistortion.fromLabel mapping when sentinel is
  present and log an appropriate message when sentinel is missing.

-----

- [x] In
  `@core-logic/src/test/java/com/github/maskedkunisquat/lattice/core/logic/DistortionMlpTest.kt`
  around lines 78 - 90, Test name is inconsistent with its assertions: the body
  checks that sigmoid(0) (0.5) is treated as true but the function is named
  "...predicts all false"; rename the test to reflect the boundary behavior (e.g.,
  change `fun \`forward with zero weights predicts all false at default
  threshold\`()` to `fun \`forward with zero weights predicts all true at default
  threshold\`()` or similar) so the test name matches the verified contract for
  DistortionMlp.forward and the default threshold behavior.

- [x] In
  `@core-logic/src/test/java/com/github/maskedkunisquat/lattice/core/logic/DistortionMlpTest.kt`
  around lines 78 - 90, Rename the test function whose current name is `forward
with zero weights predicts all false at default threshold` to reflect the
  asserted behavior (sigmoid(0) = 0.5 and boundary uses >=), e.g. `forward with
zero weights predicts all true at default threshold`; update only the test
  method name in the DistortionMlpTest file and verify references to the test name
  (if any) still match; no logic changes needed—keep the use of
  DistortionMlp.forward and the existing assertions intact.
-----
- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionMlpTrainer.kt`
  around lines 3 - 4, The DistortionMlpTrainer currently imports Android APIs
  (android.content.Context and android.util.Log) making core-logic Android-bound;
  refactor it to remove these imports by extracting persistence and logging:
  introduce a platform-neutral interface (e.g., CheckpointWriter with a save/write
  method) and inject it into DistortionMlpTrainer (constructor or setter) so the
  trainer calls the writer instead of using Context to persist; replace direct Log
  calls with a neutral logger interface or use the injected writer to report
  errors, and move any Android-specific save(...) implementations into the app
  module where you implement CheckpointWriter using Context/Log.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionMlpTrainer.kt`
  around lines 83 - 90, The trainStep function may accept embeddings containing
  NaN/Infinity which will corrupt mlp weights when gradients are applied; before
  any mutation of mlp (in DistortionMlpTrainer.trainStep), validate the input
  embedding array for finiteness and reject it early (e.g., throw
  IllegalArgumentException or return an error) if any element is not finite.
  Specifically, add a guard after the size checks in trainStep that verifies every
  embedding element is finite (use Float.isFinite / .all { it.isFinite() }) and
  stop execution before any calls that mutate mlp or its weights if the check
  fails.

- [x] In
  `@core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionMlpTrainer.kt`
  around lines 194 - 214, Do not swallow failures when computing the embedding
  hash or persisting the manifest: remove or change the empty-catch around the
  sha256Hex call for EMBEDDING_ASSET so a hashing exception propagates (or rethrow
  after logging) instead of producing an empty modelHash, and after creating
  DistortionManifest call DistortionManifestStore.write (or the underlying
  prefs.edit().commit()) and check its return value; if the write/commit fails,
  throw an exception (e.g. IllegalStateException) so the checkpoint save fails
  atomically rather than leaving weights with an empty/unstored manifest (refer to
  modelHash, EMBEDDING_ASSET, sha256Hex, DistortionManifest,
  DistortionManifestStore.write, prefs).
