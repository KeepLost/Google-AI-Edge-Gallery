package com.google.ai.edge.gallery.customtasks.smartsurveillance

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object SurveillanceDataModule {
  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): SurveillanceDatabase {
    return Room.databaseBuilder(context, SurveillanceDatabase::class.java, "surveillance.db").build()
  }

  @Provides fun provideRuleDao(database: SurveillanceDatabase): SurveillanceRuleDao = database.ruleDao()
}
