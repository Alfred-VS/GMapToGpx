package ch.tscsoft.gmaptogpx.di

import android.content.Context
import ch.tscsoft.gmaptogpx.data.BookmarkDao
import ch.tscsoft.gmaptogpx.data.BookmarkDatabase
import ch.tscsoft.gmaptogpx.data.BookmarkRepository
import ch.tscsoft.gmaptogpx.data.MapDownloadManager
import ch.tscsoft.gmaptogpx.data.TileCacheInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBookmarkDatabase(@ApplicationContext context: Context): BookmarkDatabase {
        return BookmarkDatabase.getDatabase(context)
    }

    @Provides
    fun provideBookmarkDao(database: BookmarkDatabase): BookmarkDao {
        return database.bookmarkDao()
    }

    @Provides
    @Singleton
    fun provideBookmarkRepository(bookmarkDao: BookmarkDao): BookmarkRepository {
        return BookmarkRepository(bookmarkDao)
    }

    @Provides
    @Singleton
    fun provideTileCacheInterceptor(@ApplicationContext context: Context): TileCacheInterceptor {
        return TileCacheInterceptor(context)
    }

    @Provides
    @Singleton
    fun provideMapDownloadManager(
        @ApplicationContext context: Context,
        interceptor: TileCacheInterceptor
    ): MapDownloadManager {
        return MapDownloadManager(context, interceptor)
    }
}
