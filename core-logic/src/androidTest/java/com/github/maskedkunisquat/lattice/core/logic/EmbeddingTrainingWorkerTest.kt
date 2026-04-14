package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.content.SharedPreferences
import android.content.ContextWrapper
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Instrumented integration tests for [EmbeddingTrainingWorker].
 *
 * Uses [WorkManagerTestInitHelper] and a custom [WorkerFactory] that wraps the test application
 * context in a [TrainingTestContext] — a [ContextWrapper] that also implements
 * [TrainingDependencies], satisfying the DAO cast inside the worker without changing production
 * code or requiring a custom [android.app.Application].
 *
 * ## Awaiting completion
 * [EmbeddingTrainingWorker] is a [androidx.work.CoroutineWorker] whose `doWork()` runs on
 * [kotlinx.coroutines.Dispatchers.Default], not on WorkManager's synchronous test executor.
 * For the happy-path test, completion is detected by polling until [AffectiveManifestStore]
 * contains a manifest (the last thing written on success). For the short-circuit test,
 * the polling tracks the RUNNING → not-RUNNING transition via [WorkManager.getWorkInfoById].
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingTrainingWorkerTest {

    private lateinit var context: Context
    private lateinit var db: LatticeDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE)

        // Clean slate: clear all weight files, manifest, and warm-start guard flag.
        AffectiveManifestStore.resetAll(prefs)
        deleteWeightFiles()

        db = Room.inMemoryDatabaseBuilder(context, LatticeDatabase::class.java).build()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? = when (workerClassName) {
                    EmbeddingTrainingWorker::class.java.name ->
                        EmbeddingTrainingWorker(
                            TrainingTestContext(appContext, db.journalDao()),
                            workerParameters,
                        )
                    else -> null
                }
            })
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        db.close()
        AffectiveManifestStore.resetAll(prefs)
        deleteWeightFiles()
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    /**
     * Worker with 35 labeled entries runs to completion, writes exactly one checkpoint file,
     * and records the correct count in the manifest.
     */
    @Test
    fun worker_withSufficientEntries_writesCheckpointAndUpdatesManifest() {
        seedLabeledEntries(count = 35)

        val request = buildRequest()
        workManager.enqueueUniquePeriodicWork(
            EmbeddingTrainingWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        testDriver.setAllConstraintsMet(request.id)
        testDriver.setPeriodDelayMet(request.id)

        // Await: manifest is the last artifact written on success, so its presence is
        // the reliable signal that doWork() completed.
        awaitManifestWritten(timeoutMs = 30_000)

        // Exactly one checkpoint file must exist
        val weightFiles = weightFiles()
        assertEquals("Exactly one checkpoint file must exist after training", 1, weightFiles.size)

        // Manifest must reflect the 35 samples trained on
        val manifest = AffectiveManifestStore.read(prefs)
        assertNotNull("Manifest must be written after successful training", manifest)
        checkNotNull(manifest)
        assertEquals("trainedOnCount must equal the number of seeded entries", 35, manifest.trainedOnCount)
        assertEquals("headPath must match the checkpoint file name", weightFiles[0].name, manifest.headPath)
        assertTrue("lastTrainingTimestamp must be a positive epoch-ms value", manifest.lastTrainingTimestamp > 0L)
    }

    // ── Short-circuit ─────────────────────────────────────────────────────────

    /**
     * Worker with fewer than [EmbeddingTrainingWorker.MIN_LABELED_ENTRIES] entries returns
     * [androidx.work.ListenableWorker.Result.success] immediately without writing any artifacts.
     */
    @Test
    fun worker_withInsufficientEntries_shortCircuitsWithoutWritingArtifacts() {
        seedLabeledEntries(count = 5)  // below MIN_LABELED_ENTRIES = 30

        val request = buildRequest()
        workManager.enqueueUniquePeriodicWork(
            EmbeddingTrainingWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        testDriver.setAllConstraintsMet(request.id)
        testDriver.setPeriodDelayMet(request.id)

        // For the short-circuit path, doWork() returns in < 100 ms (single DB count).
        // RUNNING state is too brief to observe reliably via polling; a fixed sleep is
        // simpler and sufficient — 5 s is far more than enough on any device.
        Thread.sleep(5_000)

        assertEquals(
            "No checkpoint file must be written when count < ${EmbeddingTrainingWorker.MIN_LABELED_ENTRIES}",
            0,
            weightFiles().size,
        )
        assertNull("Manifest must remain absent after short-circuit", AffectiveManifestStore.read(prefs))
    }

    // ── Reset personalization ─────────────────────────────────────────────────

    /**
     * Simulates the reset action from [com.github.maskedkunisquat.lattice.ui.SettingsViewModel]:
     * deletes all weight files and calls [AffectiveManifestStore.resetAll]. Verifies that
     * no artifacts remain and the warm-start guard is cleared so it re-runs on next launch.
     */
    @Test
    fun resetPersonalization_deletesAllArtifactsAndClearsManifest() {
        // Plant two weight files and a manifest to simulate a post-training state
        context.filesDir.resolve("affective_head_v1_c30.bin").writeBytes(ByteArray(8))
        context.filesDir.resolve("affective_head_v1_c60.bin").writeBytes(ByteArray(8))
        AffectiveManifestStore.write(
            prefs,
            AffectiveManifest(trainedOnCount = 60, headPath = "affective_head_v1_c60.bin"),
        )
        prefs.edit().putBoolean(AffectiveMlpInitializer.PREF_KEY, true).apply()

        // Reset (mirrors SettingsViewModel.resetPersonalization)
        deleteWeightFiles()
        AffectiveManifestStore.resetAll(prefs)

        assertEquals("All weight files must be deleted after reset", 0, weightFiles().size)
        assertNull("Manifest must be absent after reset", AffectiveManifestStore.read(prefs))
        assertFalse(
            "Warm-start guard must be cleared so base layer re-runs on next launch",
            prefs.getBoolean(AffectiveMlpInitializer.PREF_KEY, false),
        )
    }

    // ── Schedule / cancel ─────────────────────────────────────────────────────

    /**
     * [TrainingCoordinator.scheduleIfNeeded] enqueues the unique periodic work.
     * [TrainingCoordinator.cancelAll] cancels it.
     * Re-scheduling after cancellation produces a new ENQUEUED entry.
     *
     * Verified via [WorkManager.getWorkInfosForUniqueWork] as required by the
     * Milestone 3 exit criteria.
     */
    @Test
    fun trainingCoordinator_scheduleAndCancel_reflectedInWorkInfos() {
        val coordinator = TrainingCoordinator()

        coordinator.scheduleIfNeeded(context)
        val afterSchedule = workManager
            .getWorkInfosForUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME)
            .get()
        assertFalse("Work must be enqueued after scheduleIfNeeded", afterSchedule.isEmpty())
        assertTrue(
            "All work entries must be ENQUEUED after scheduleIfNeeded",
            afterSchedule.all { it.state == WorkInfo.State.ENQUEUED },
        )

        coordinator.cancelAll(context)
        val afterCancel = workManager
            .getWorkInfosForUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME)
            .get()
        assertTrue(
            "All work entries must be CANCELLED after cancelAll",
            afterCancel.all { it.state == WorkInfo.State.CANCELLED },
        )

        // Re-schedule after cancel — KEEP policy creates a fresh enqueue alongside the cancelled entry
        coordinator.scheduleIfNeeded(context)
        val afterReschedule = workManager
            .getWorkInfosForUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME)
            .get()
        assertTrue(
            "At least one ENQUEUED entry must exist after re-scheduling",
            afterReschedule.any { it.state == WorkInfo.State.ENQUEUED },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a constraint-free periodic request for use in worker-logic tests.
     *
     * Production constraints (charging + idle + storage) are omitted here because
     * [androidx.work.testing.TestDriver.setAllConstraintsMet] does not reliably
     * satisfy [Constraints.requiresDeviceIdle] in the test framework on API 23+,
     * which prevents the worker from ever entering RUNNING state. The correct
     * production constraint configuration is exercised by the separate
     * [trainingCoordinator_scheduleAndCancel_reflectedInWorkInfos] test via
     * [TrainingCoordinator.scheduleIfNeeded].
     */
    private fun buildRequest() = PeriodicWorkRequestBuilder<EmbeddingTrainingWorker>(24, TimeUnit.HOURS).build()

    private fun seedLabeledEntries(count: Int) {
        val dao = db.journalDao()
        val baseTime = System.currentTimeMillis()
        // Block on the suspend insert via runBlocking — the in-memory DB supports it.
        runBlocking {
            repeat(count) { i ->
                dao.insertEntry(
                    JournalEntry(
                        id = UUID.randomUUID(),
                        timestamp = baseTime - i * 1_000L,
                        content = "[PERSON_test] entry $i",
                        valence = (i % 5 - 2) * 0.2f,
                        arousal = (i % 3 - 1) * 0.3f,
                        moodLabel = "TENSE",
                        // Real-sized embedding so the worker doesn't skip this entry
                        embedding = FloatArray(AffectiveMlp.IN) { 0.01f * (it % 100) },
                        cognitiveDistortions = emptyList(),
                        userValence = (i % 5 - 2) * 0.2f,
                        userArousal = (i % 3 - 1) * 0.3f,
                    )
                )
            }
        }
    }

    /**
     * Polls until [AffectiveManifestStore] contains a manifest or the timeout elapses.
     * The manifest is the last artifact written in a successful training run, making it
     * a reliable completion signal for the happy-path test.
     */
    private fun awaitManifestWritten(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (AffectiveManifestStore.read(prefs) != null) return
            Thread.sleep(100)
        }
        throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for the training manifest to be written. " +
            "This likely means doWork() did not complete or threw an uncaught exception."
        )
    }

    private fun weightFiles() = context.filesDir.listFiles { f ->
        f.name.startsWith("affective_head_") && f.name.endsWith(".bin")
    } ?: emptyArray()

    private fun deleteWeightFiles() = weightFiles().forEach { it.delete() }
}

// ── Test double ───────────────────────────────────────────────────────────────

/**
 * A [ContextWrapper] that additionally implements [TrainingDependencies], allowing
 * [EmbeddingTrainingWorker] to resolve its [JournalDao] via the standard
 * `applicationContext as TrainingDependencies` cast without requiring [LatticeApplication].
 *
 * The worker sets `applicationContext = appContext` from its constructor parameter, so
 * passing this wrapper via the custom [WorkerFactory] is sufficient.
 */
private class TrainingTestContext(
    base: Context,
    private val dao: JournalDao,
) : ContextWrapper(base), TrainingDependencies {
    override val journalDao: JournalDao get() = dao
}
