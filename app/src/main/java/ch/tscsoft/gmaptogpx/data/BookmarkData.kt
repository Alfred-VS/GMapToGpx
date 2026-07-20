package ch.tscsoft.gmaptogpx.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders")
data class BookmarkFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkFolder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("folderId")]
)
data class BookmarkRoute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val gpxContent: String,
    val folderId: Long? = null,
    val distanceMeters: Double = 0.0,
    val elevationGain: Int = 0,
    val elevationLoss: Int = 0,
    val totalTimeSeconds: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<BookmarkFolder>>

    @Query("SELECT * FROM bookmarks WHERE folderId IS NULL ORDER BY createdAt DESC")
    fun getRootBookmarks(): Flow<List<BookmarkRoute>>

    @Query("SELECT * FROM bookmarks WHERE folderId = :folderId ORDER BY createdAt DESC")
    fun getBookmarksInFolder(folderId: Long): Flow<List<BookmarkRoute>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: BookmarkFolder): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkRoute): Long

    @Delete
    suspend fun deleteFolder(folder: BookmarkFolder)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkRoute)
    
    @Update
    suspend fun updateFolder(folder: BookmarkFolder)

    @Update
    suspend fun updateBookmark(bookmark: BookmarkRoute)
}

@Database(entities = [BookmarkFolder::class, BookmarkRoute::class], version = 1, exportSchema = false)
abstract class BookmarkDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: BookmarkDatabase? = null

        fun getDatabase(context: android.content.Context): BookmarkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BookmarkDatabase::class.java,
                    "bookmark_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
