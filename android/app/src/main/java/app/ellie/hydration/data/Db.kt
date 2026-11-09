package app.ellie.hydration.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "intake")
data class IntakeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val dayKey: String,
    val ml: Int,
)

@Dao
interface IntakeDao {
    @Insert
    suspend fun insert(e: IntakeEntry): Long

    @Query("SELECT COALESCE(SUM(ml), 0) FROM intake WHERE dayKey = :day")
    suspend fun totalFor(day: String): Int

    @Query("SELECT COALESCE(SUM(ml), 0) FROM intake WHERE dayKey = :day")
    fun totalFlow(day: String): Flow<Int>

    @Query("DELETE FROM intake WHERE id = (SELECT MAX(id) FROM intake WHERE dayKey = :day)")
    suspend fun deleteLastFor(day: String): Int

    @Query("DELETE FROM intake WHERE atMillis < :before")
    suspend fun purgeBefore(before: Long): Int

    @Query("DELETE FROM intake")
    suspend fun deleteAll()
}

@Database(entities = [IntakeEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun intake(): IntakeDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Encrypted at rest with SQLCipher; passphrase protected by the Android Keystore. */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: run {
                System.loadLibrary("sqlcipher")
                val factory = SupportOpenHelperFactory(Prefs(context).databasePassphrase())
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ellie.db")
                    .openHelperFactory(factory)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
