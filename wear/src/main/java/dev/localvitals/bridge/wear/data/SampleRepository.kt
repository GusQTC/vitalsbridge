package dev.localvitals.bridge.wear.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.health.services.client.data.DataType
import kotlinx.coroutines.flow.Flow

/** A single continuous heart-rate record. Health Services gives BPM per sample. */
@Entity(tableName = "heart_rate")
data class HeartRateSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bpm: Int,
    val timestampMs: Long,
    /** true if this was stored for the first time during a query-latency flush */
    val backfilled: Boolean = false,
    val syncedToPhone: Boolean = false
)

/** Step count over a [startMs, endMs) window, as delivered by Health Services. */
@Entity(tableName = "steps")
data class StepsSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stepsValue: Int,
    val startMs: Long,
    val endMs: Long,
    val syncedToPhone: Boolean = false
)

@Dao
interface SampleDao {
    @Insert
    suspend fun insertHeartRate(samples: List<HeartRateSample>)

    @Insert
    suspend fun insertSteps(samples: List<StepsSample>)

    @Query("SELECT COUNT(*) FROM heart_rate")
    fun heartRateCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM steps")
    fun stepsCount(): Flow<Int>

    @Query("SELECT * FROM heart_rate WHERE syncedToPhone = 0 ORDER BY timestampMs ASC LIMIT 500")
    suspend fun pendingHeartRate(): List<HeartRateSample>

    @Query("SELECT * FROM steps WHERE syncedToPhone = 0 ORDER BY endMs ASC LIMIT 200")
    suspend fun pendingSteps(): List<StepsSample>

    @Query("UPDATE heart_rate SET syncedToPhone = 1 WHERE syncedToPhone = 0 AND timestampMs <= :upTo")
    suspend fun markHeartRateSyncedUpTo(upTo: Long)

    @Query("UPDATE steps SET syncedToPhone = 1 WHERE syncedToPhone = 0 AND endMs <= :upTo")
    suspend fun markStepsSyncedUpTo(upTo: Long)

    @Query("UPDATE heart_rate SET syncedToPhone = 1 WHERE id IN (:ids)")
    suspend fun markHeartRateSynced(ids: List<Long>)

    @Query("UPDATE steps SET syncedToPhone = 1 WHERE id IN (:ids)")
    suspend fun markStepsSynced(ids: List<Long>)

    @Query("SELECT * FROM heart_rate ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latestHeartRate(): HeartRateSample?

    @Query("DELETE FROM heart_rate WHERE syncedToPhone = 1")
    suspend fun pruneSyncedHeartRate()

    @Query("DELETE FROM steps WHERE syncedToPhone = 1")
    suspend fun pruneSyncedSteps()
}

@Database(
    entities = [HeartRateSample::class, StepsSample::class],
    version = 1,
    exportSchema = false
)
abstract class SampleDb : RoomDatabase() {
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile private var instance: SampleDb? = null
        fun get(context: Context): SampleDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, SampleDb::class.java, "vitalsbridge.db"
            ).build().also { instance = it }
        }
    }
}

/** Repository storing passive-monitoring data points into the local Room DB. */
class SampleRepository private constructor(context: Context) {
    private val dao = SampleDb.get(context).sampleDao()
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var registered: Boolean
        get() = prefs.getBoolean("registered", false)
        set(value) = prefs.edit().putBoolean("registered", value).apply()

    suspend fun storeDataPoints(dataPoints: androidx.health.services.client.data.DataPointContainer) {
        val bootInstant = java.time.Instant.ofEpochMilli(
            System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        )
        val hr = dataPoints.getData(DataType.HEART_RATE_BPM)
        if (!hr.isNullOrEmpty()) {
            val rows = hr.map { p ->
                HeartRateSample(
                    bpm = p.value.toInt(),
                    timestampMs = p.getTimeInstant(bootInstant).toEpochMilli()
                )
            }
            dao.insertHeartRate(rows)
        }
        // STEPS_DAILY is a delta type: each IntervalDataPoint carries the step
        // count for its own [start, end) window. Skip empty flushes so the DB
        // only holds meaningful windows.
        val steps = dataPoints.getData(DataType.STEPS_DAILY)
        if (!steps.isNullOrEmpty()) {
            val rows = steps.mapNotNull { p ->
                val value = p.value ?: return@mapNotNull null
                if (value <= 0L) return@mapNotNull null
                StepsSample(
                    stepsValue = value.toInt(),
                    startMs = bootInstant.plus(p.startDurationFromBoot).toEpochMilli(),
                    endMs = bootInstant.plus(p.endDurationFromBoot).toEpochMilli()
                )
            }
            if (rows.isNotEmpty()) dao.insertSteps(rows)
        }
    }

    fun markUnregistered() { registered = false }
    fun markRegistered() { registered = true }
    fun heartRateCount() = dao.heartRateCount()
    fun stepsCount() = dao.stepsCount()
    suspend fun pendingHeartRate() = dao.pendingHeartRate()
    suspend fun pendingSteps() = dao.pendingSteps()
    suspend fun markHeartRateSynced(ids: List<Long>) = dao.markHeartRateSynced(ids)
    suspend fun markStepsSynced(ids: List<Long>) = dao.markStepsSynced(ids)
    suspend fun markPendingSyncedUpTo(upTo: Long) {
        dao.markHeartRateSyncedUpTo(upTo)
        dao.markStepsSyncedUpTo(upTo)
    }
    var lastPushedAt: Long
        get() = prefs.getLong("lastPushedAt", 0L)
        set(value) = prefs.edit().putLong("lastPushedAt", value).apply()
    suspend fun latestHeartRate() = dao.latestHeartRate()

    companion object {
        @Volatile private var instance: SampleRepository? = null
        fun get(context: Context): SampleRepository = instance ?: synchronized(this) {
            instance ?: SampleRepository(context.applicationContext).also { instance = it }
        }
    }
}
