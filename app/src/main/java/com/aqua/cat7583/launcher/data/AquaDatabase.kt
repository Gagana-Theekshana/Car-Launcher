package com.aqua.cat7583.launcher.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val distanceKm: Float,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Int,
    val fuelUsedLiters: Float,
    val tripCostLkr: Float,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double
)

@Entity(tableName = "fuel_logs")
data class FuelLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long,
    val odometerKm: Int,
    val litersAdded: Float,
    val pricePerLiterLkr: Float,
    val totalCostLkr: Float,
    val isFullTank: Boolean,
    val calculatedKmPerLiter: Float
)

@Entity(tableName = "door_audit_logs")
data class DoorAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long,
    val doorName: String,      // FL, FR, RL, RR, TRUNK, HOOD
    val action: String,        // OPENED / CLOSED
    val latitude: Double,
    val longitude: Double,
    val speedAtEventKmh: Int
)

@Entity(tableName = "maintenance_reminders")
data class MaintenanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serviceItem: String,
    val lastServiceOdoKm: Int,
    val intervalKm: Int,
    val nextDueOdoKm: Int
)

@Dao
interface TelemetryDao {

    // =========================================================================
    // 1. TRIPS
    // =========================================================================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity): Long

    @Query("SELECT * FROM trips ORDER BY startTimeMillis DESC LIMIT :limit")
    fun observeRecentTrips(limit: Int = 50): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startTimeMillis DESC LIMIT :limit")
    suspend fun getRecentTrips(limit: Int = 50): List<TripEntity>

    @Query("SELECT COALESCE(SUM(distanceKm), 0.0) FROM trips")
    fun observeTotalLoggedDistanceKm(): Flow<Float>

    @Query("SELECT COALESCE(SUM(tripCostLkr), 0.0) FROM trips")
    fun observeTotalLoggedTripCostLkr(): Flow<Float>

    @Query("DELETE FROM trips")
    suspend fun clearAllTrips()

    // =========================================================================
    // 2. FUEL LOGS
    // =========================================================================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFuelLog(fuelLog: FuelLogEntity): Long

    @Query("SELECT * FROM fuel_logs ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeFuelLogs(limit: Int = 50): Flow<List<FuelLogEntity>>

    @Query("SELECT * FROM fuel_logs ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun getLatestFuelLog(): FuelLogEntity?

    @Query("SELECT COALESCE(AVG(calculatedKmPerLiter), 22.8) FROM fuel_logs WHERE calculatedKmPerLiter > 0")
    fun observeLifetimeAvgKmPerLiter(): Flow<Float>

    // =========================================================================
    // 3. DOOR OPENING SECURITY AUDIT LOGS
    // =========================================================================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDoorAudit(event: DoorAuditEntity): Long

    @Query("SELECT * FROM door_audit_logs ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeDoorAuditLogs(limit: Int = 100): Flow<List<DoorAuditEntity>>

    @Query("SELECT * FROM door_audit_logs ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun getDoorAuditLogs(limit: Int = 100): List<DoorAuditEntity>

    @Query("DELETE FROM door_audit_logs")
    suspend fun clearDoorAuditLogs()

    // =========================================================================
    // 4. TOYOTA AQUA (NHP10) MAINTENANCE REMINDERS
    // =========================================================================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMaintenanceItems(items: List<MaintenanceEntity>)

    @Update
    suspend fun updateMaintenanceItem(item: MaintenanceEntity)

    @Query("SELECT * FROM maintenance_reminders ORDER BY nextDueOdoKm ASC")
    fun observeMaintenanceItems(): Flow<List<MaintenanceEntity>>

    @Query("SELECT * FROM maintenance_reminders ORDER BY nextDueOdoKm ASC")
    suspend fun getMaintenanceItems(): List<MaintenanceEntity>

    @Query("SELECT COUNT(*) FROM maintenance_reminders")
    suspend fun getMaintenanceCount(): Int
}

@Database(
    entities = [
        TripEntity::class,
        FuelLogEntity::class,
        DoorAuditEntity::class,
        MaintenanceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AquaDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao

    companion object {
        private const val DATABASE_NAME = "aqua_telemetry.db"

        @Volatile
        private var INSTANCE: AquaDatabase? = null

        private val dbSeedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getInstance(context: Context): AquaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { db ->
                    INSTANCE = db
                    seedDefaultToyotaAquaMaintenanceIfNeeded(db)
                }
            }
        }

        private fun buildDatabase(appContext: Context): AquaDatabase {
            return Room.databaseBuilder(
                appContext,
                AquaDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        INSTANCE?.let { seedDefaultToyotaAquaMaintenanceIfNeeded(it) }
                    }
                })
                .build()
        }

        private fun seedDefaultToyotaAquaMaintenanceIfNeeded(database: AquaDatabase) {
            dbSeedScope.launch {
                val dao = database.telemetryDao()
                if (dao.getMaintenanceCount() == 0) {
                    val baseOdo = 95000
                    val defaultItems = listOf(
                        MaintenanceEntity(
                            serviceItem = "Engine Oil 0W-20",
                            lastServiceOdoKm = baseOdo,
                            intervalKm = 5000,
                            nextDueOdoKm = baseOdo + 5000
                        ),
                        MaintenanceEntity(
                            serviceItem = "HV Battery Fan Filter Clean",
                            lastServiceOdoKm = baseOdo,
                            intervalKm = 10000,
                            nextDueOdoKm = baseOdo + 10000
                        ),
                        MaintenanceEntity(
                            serviceItem = "Brake Pads",
                            lastServiceOdoKm = baseOdo - 5000,
                            intervalKm = 30000,
                            nextDueOdoKm = baseOdo + 25000
                        ),
                        MaintenanceEntity(
                            serviceItem = "ATF WS Transmission Fluid",
                            lastServiceOdoKm = baseOdo - 10000,
                            intervalKm = 40000,
                            nextDueOdoKm = baseOdo + 30000
                        ),
                        MaintenanceEntity(
                            serviceItem = "Hybrid Inverter Coolant",
                            lastServiceOdoKm = baseOdo - 15000,
                            intervalKm = 50000,
                            nextDueOdoKm = baseOdo + 35000
                        )
                    )
                    dao.insertMaintenanceItems(defaultItems)
                }
            }
        }
    }
}

