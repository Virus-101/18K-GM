package ma.goldmaroc18k.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Dernier cours valide de l'or, conservé séparément du taux de change. */
@Entity(tableName = "gold_quote")
data class GoldQuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fetchedAtMs: Long,
    val sourceTsMs: Long,
    val priceUsdPerOz: String,
    val isStale: Boolean,
)

@Entity(tableName = "fx_quote")
data class FxQuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fetchedAtMs: Long,
    val sourceTsMs: Long,
    val nextUpdateMs: Long?,
    val usdMad: String,
)

/** Une ligne par actualisation réussie des DEUX sources. Points enregistrés par l'application (aucun historique importé). */
@Entity(tableName = "price_history")
data class PriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fetchedAtMs: Long,
    val goldSourceTsMs: Long,
    val fxSourceTsMs: Long,
    val xauUsd: String,
    val usdMad: String,
    val price24k: String,
    val price18k: String,
)

@Dao
interface PriceDao {
    @Query("SELECT * FROM gold_quote ORDER BY sourceTsMs DESC, id DESC LIMIT 1")
    fun latestGold(): Flow<GoldQuoteEntity?>

    @Query("SELECT * FROM fx_quote ORDER BY sourceTsMs DESC, id DESC LIMIT 1")
    fun latestFx(): Flow<FxQuoteEntity?>

    @Query("SELECT * FROM gold_quote ORDER BY sourceTsMs DESC, id DESC LIMIT 1")
    suspend fun latestGoldOnce(): GoldQuoteEntity?

    @Query("SELECT * FROM fx_quote ORDER BY sourceTsMs DESC, id DESC LIMIT 1")
    suspend fun latestFxOnce(): FxQuoteEntity?

    @Insert suspend fun insertGold(e: GoldQuoteEntity): Long
    @Insert suspend fun insertFx(e: FxQuoteEntity): Long
    @Insert suspend fun insertHistory(e: PriceHistoryEntity): Long

    @Query("UPDATE gold_quote SET fetchedAtMs = :ms WHERE id = :id")
    suspend fun touchGold(id: Long, ms: Long)

    @Query("UPDATE fx_quote SET fetchedAtMs = :ms WHERE id = :id")
    suspend fun touchFx(id: Long, ms: Long)

    @Query("SELECT * FROM price_history WHERE fetchedAtMs >= :since ORDER BY fetchedAtMs ASC")
    fun historySince(since: Long): Flow<List<PriceHistoryEntity>>
}

@Database(entities = [GoldQuoteEntity::class, FxQuoteEntity::class, PriceHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PriceDao
}
