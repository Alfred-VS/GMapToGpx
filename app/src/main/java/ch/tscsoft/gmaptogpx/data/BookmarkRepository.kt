package ch.tscsoft.gmaptogpx.data

import kotlinx.coroutines.flow.Flow

class BookmarkRepository(private val bookmarkDao: BookmarkDao) {
    val allFolders: Flow<List<BookmarkFolder>> = bookmarkDao.getAllFolders()
    val rootBookmarks: Flow<List<BookmarkRoute>> = bookmarkDao.getRootBookmarks()

    fun getBookmarksInFolder(folderId: Long): Flow<List<BookmarkRoute>> {
        return bookmarkDao.getBookmarksInFolder(folderId)
    }

    suspend fun insertFolder(name: String, parentId: Long? = null) {
        bookmarkDao.insertFolder(BookmarkFolder(name = name, parentId = parentId))
    }

    suspend fun insertBookmark(
        title: String,
        gpxContent: String,
        folderId: Long? = null,
        distanceMeters: Double = 0.0,
        elevationGain: Int = 0,
        elevationLoss: Int = 0,
        totalTimeSeconds: Int = 0
    ) {
        bookmarkDao.insertBookmark(
            BookmarkRoute(
                title = title,
                gpxContent = gpxContent,
                folderId = folderId,
                distanceMeters = distanceMeters,
                elevationGain = elevationGain,
                elevationLoss = elevationLoss,
                totalTimeSeconds = totalTimeSeconds
            )
        )
    }

    suspend fun deleteFolder(folder: BookmarkFolder) {
        bookmarkDao.deleteFolder(folder)
    }

    suspend fun deleteBookmark(bookmark: BookmarkRoute) {
        bookmarkDao.deleteBookmark(bookmark)
    }

    suspend fun updateFolder(folder: BookmarkFolder) {
        bookmarkDao.updateFolder(folder)
    }

    suspend fun updateBookmark(bookmark: BookmarkRoute) {
        bookmarkDao.updateBookmark(bookmark)
    }
}
