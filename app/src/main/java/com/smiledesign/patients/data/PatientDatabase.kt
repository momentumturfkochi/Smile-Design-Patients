package com.smiledesign.patients.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "patients")
data class PatientRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val opNumber: String = "",
    val name: String = "",
    val phone: String = "",
    val place: String = "",
    val age: String = "",
    val sex: String = "",
    val visitDate: String = "",
    val treatment: String = ""
)

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients ORDER BY id DESC")
    fun getAllFlow(): Flow<List<PatientRecord>>

    @Query("SELECT * FROM patients ORDER BY id DESC")
    suspend fun getAllOnce(): List<PatientRecord>

    @Query("""
        SELECT * FROM patients 
        WHERE name LIKE '%' || :q || '%' 
           OR phone LIKE '%' || :q || '%' 
           OR opNumber LIKE '%' || :q || '%' 
           OR place LIKE '%' || :q || '%' 
           OR treatment LIKE '%' || :q || '%'
        ORDER BY id DESC
    """)
    fun searchFlow(q: String): Flow<List<PatientRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<PatientRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PatientRecord)

    @Delete
    suspend fun delete(record: PatientRecord)
}

@Database(entities = [PatientRecord::class], version = 1, exportSchema = false)
abstract class PatientDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao

    companion object {
        @Volatile private var INSTANCE: PatientDatabase? = null

        fun getInstance(context: Context): PatientDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PatientDatabase::class.java,
                    "smile_design_patients.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
