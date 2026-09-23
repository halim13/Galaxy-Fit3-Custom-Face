package com.galaxyfit3.customface.di

import android.content.Context
import com.galaxyfit3.core.delivery.Fit3DirectInstaller
import com.galaxyfit3.customface.data.ProjectStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideProjectStore(@ApplicationContext context: Context): ProjectStore =
        ProjectStore(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    @Provides
    @Singleton
    fun provideFit3DirectInstaller(@ApplicationContext context: Context): Fit3DirectInstaller =
        Fit3DirectInstaller(context)
}