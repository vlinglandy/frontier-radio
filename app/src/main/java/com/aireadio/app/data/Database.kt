package com.aireadio.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RadioDao {
    @Query("SELECT * FROM articles ORDER BY publishedAt DESC") fun observeArticles(): Flow<List<Article>>
    @Query("SELECT * FROM articles ORDER BY publishedAt DESC") suspend fun articles(): List<Article>
    @Query("SELECT * FROM articles WHERE id = :id") suspend fun article(id: String): Article?
    @Query("SELECT * FROM articles WHERE contentHash = :hash LIMIT 1") suspend fun byHash(hash: String): Article?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(article: Article): Long
    @Update suspend fun update(article: Article)
    @Query("UPDATE articles SET heat = :heat, comments = :comments, publishedAt = :publishedAt, dateEstimated = :estimated WHERE id = :id")
    suspend fun metadata(id: String, heat: Long?, comments: Int?, publishedAt: Long, estimated: Boolean)
    @Query("UPDATE articles SET body = :body, summaryOnly = :summaryOnly WHERE id = :id")
    suspend fun body(id: String, body: String, summaryOnly: Boolean)
    @Query("UPDATE articles SET spokenText = :text, scriptVersion = :version, error = '' WHERE id = :id")
    suspend fun script(id: String, text: String, version: Int)
    @Query("UPDATE articles SET error = :message WHERE id = :id") suspend fun error(id: String, message: String)
    @Query("UPDATE articles SET positionMs = :position WHERE id = :id") suspend fun progress(id: String, position: Long)
    @Query("UPDATE articles SET playCount = playCount + 1, lastHeardAt = :now, positionMs = 0 WHERE id = :id") suspend fun heard(id: String, now: Long)
    @Query("SELECT * FROM source_status ORDER BY name") fun observeSources(): Flow<List<SourceStatus>>
    @Query("SELECT * FROM source_status WHERE id = :id") suspend fun sourceStatus(id: String): SourceStatus?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun sourceStatus(status: SourceStatus)
    @Query("DELETE FROM articles WHERE publishedAt < :cutoff AND id != :protectedId") suspend fun prune(cutoff: Long, protectedId: String)
}
@Database(entities = [Article::class, SourceStatus::class], version = 2, exportSchema = false)
abstract class RadioDatabase : RoomDatabase() { abstract fun dao(): RadioDao }
