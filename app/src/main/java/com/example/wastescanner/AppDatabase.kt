package com.example.wastescanner

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import java.util.UUID

/**
 * Faza 1 planu - zmiany względem MVP klasyfikatora odpadów:
 *  - id: Long -> String (UUID). Poprzednio id = System.currentTimeMillis(), co przy dwóch
 *    zapisach w tej samej milisekundzie nadpisywało wpis (OnConflictStrategy.REPLACE).
 *  - label/confidence (pojedyncza etykieta + pewność) zastąpione przez pełny `report`
 *    (IngredientSafetyReport), bo teraz wynikiem skanu jest lista składników, nie jedna klasa.
 *    Etykieta do wyświetlenia na liście historii jest wyliczana z report.overallRisk
 *    w warstwie UI (patrz ScanHistoryScreen.kt), nie duplikowana jako osobna kolumna.
 */
@Entity(tableName = "history_table")
data class HistoryItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val dateString: String,
    val imagePath: String?,
    val report: IngredientSafetyReport
)

class Converters {
    @TypeConverter
    fun fromIngredientSafetyReport(report: IngredientSafetyReport): String {
        return Gson().toJson(report)
    }

    @TypeConverter
    fun toIngredientSafetyReport(json: String): IngredientSafetyReport {
        return Gson().fromJson(json, IngredientSafetyReport::class.java)
    }
}


@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_table ORDER BY dateString DESC")
    suspend fun getAllHistory(): List<HistoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: HistoryItem)

    @Delete
    suspend fun deleteItem(item: HistoryItem)
}


// Wersja podbita 1 -> 2 z powodu zmiany schematu (nowy kształt HistoryItem).
// fallbackToDestructiveMigration() jest świadomie stosowany na etapie developmentu/pracy
// inżynierskiej - przy realnym wdrożeniu produkcyjnym należałoby zamiast tego napisać
// jawną Migration(1, 2), żeby nie tracić danych użytkowników przy aktualizacji.
@Database(entities = [HistoryItem::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "waste_scanner_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}