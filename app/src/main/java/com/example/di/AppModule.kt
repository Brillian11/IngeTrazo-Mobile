package com.example.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.data.local.AppDatabase
import com.example.data.local.MeasurementDao
import com.example.data.local.PreferencesRepository
import com.example.data.local.RecentFileDao
import com.example.data.parser.DocumentParserRegistry
import com.example.data.parser.GltfParser
import com.example.data.parser.IgzParser
import com.example.data.parser.ObjParser
import com.example.data.parser.SkpParser
import com.example.data.repository.CadRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.ingetrazoDataStore: DataStore<Preferences> by preferencesDataStore(name = "ingetrazo_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ingetrazo_cad.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideRecentFileDao(db: AppDatabase): RecentFileDao = db.recentFileDao()

    @Provides
    fun provideMeasurementDao(db: AppDatabase): MeasurementDao = db.measurementDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.ingetrazoDataStore

    @Provides
    @Singleton
    fun providePreferencesRepository(dataStore: DataStore<Preferences>): PreferencesRepository =
        PreferencesRepository(dataStore)

    @Provides
    @Singleton
    fun provideParserRegistry(): DocumentParserRegistry = DocumentParserRegistry(
        listOf(
            IgzParser(),
            SkpParser(),
            ObjParser(),
            GltfParser()
        )
    )

    @Provides
    @Singleton
    fun provideCadRepository(
        @ApplicationContext context: Context,
        registry: DocumentParserRegistry,
        recentFileDao: RecentFileDao,
        measurementDao: MeasurementDao
    ): CadRepository = CadRepository(context, registry, recentFileDao, measurementDao)
}
