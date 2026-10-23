package com.example.lianxu.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.lianxu.data.dao.GoalPhaseDao
import com.example.lianxu.data.dao.MeasurementDao
import com.example.lianxu.data.dao.ProfileDao
import com.example.lianxu.data.dao.CollaborationDao
import com.example.lianxu.data.dao.RecordDao
import com.example.lianxu.data.entity.*

@Database(
    entities = [
        Profile::class,
        GoalPhase::class,
        KnowledgeNote::class,
        TrainingSession::class,
        IntakeEntry::class,
        Measurement::class,
        Turn::class,
        Board::class,
        Card::class, DraftEntity::class, AttachmentEntity::class, InputBundleEntity::class,
        TaskEntity::class, BoardSnapshotEntity::class, CalculationEntity::class,
        AnalysisEntity::class, DayPlanEntity::class, NutritionPolicyEntity::class, MutationReceiptEntity::class, EntitySnapshotEntity::class
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collaborationDao(): CollaborationDao
    abstract fun recordDao(): RecordDao
    abstract fun profileDao(): ProfileDao
    abstract fun goalPhaseDao(): GoalPhaseDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lianxu_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
