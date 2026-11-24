package be.wallonie.intemperies.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Upsert
    suspend fun upsert(samples: List<SampleEntity>)

    @Query(
        "SELECT * FROM samples WHERE kind = :kind AND stationNo = :stationNo " +
            "AND epochSec >= :fromSec AND epochSec < :toSec ORDER BY epochSec",
    )
    fun observe(kind: String, stationNo: String, fromSec: Long, toSec: Long): Flow<List<SampleEntity>>

    @Query(
        "SELECT * FROM samples WHERE kind = :kind AND stationNo = :stationNo " +
            "AND epochSec >= :fromSec AND epochSec < :toSec ORDER BY epochSec",
    )
    suspend fun get(kind: String, stationNo: String, fromSec: Long, toSec: Long): List<SampleEntity>

    @Query("SELECT MAX(epochSec) FROM samples WHERE kind = :kind AND stationNo = :stationNo")
    suspend fun lastEpoch(kind: String, stationNo: String): Long?

    @Query("SELECT MAX(epochSec) FROM samples WHERE kind = :kind AND stationNo = :stationNo")
    fun observeLastEpoch(kind: String, stationNo: String): Flow<Long?>

    @Query("SELECT MIN(epochSec) FROM samples WHERE kind = :kind AND stationNo = :stationNo")
    suspend fun firstEpoch(kind: String, stationNo: String): Long?

    @Query("DELETE FROM samples WHERE epochSec < :beforeSec")
    suspend fun purgeBefore(beforeSec: Long): Int

    @Query("DELETE FROM samples WHERE NOT (kind || ':' || stationNo) IN (:keep)")
    suspend fun purgeStationsNotIn(keep: List<String>): Int
}

@Dao
interface StationDao {
    @Query("SELECT * FROM stations WHERE kind = :kind")
    suspend fun byKind(kind: String): List<StationEntity>

    @Query("SELECT * FROM stations")
    fun observeAll(): Flow<List<StationEntity>>

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun count(): Int

    @Query("DELETE FROM stations WHERE kind = :kind")
    suspend fun deleteKind(kind: String)

    @Upsert
    suspend fun upsert(stations: List<StationEntity>)

    @Transaction
    suspend fun replaceKind(kind: String, stations: List<StationEntity>) {
        deleteKind(kind)
        upsert(stations)
    }
}

@Database(entities = [SampleEntity::class, StationEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sampleDao(): SampleDao
    abstract fun stationDao(): StationDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "intemperies.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
