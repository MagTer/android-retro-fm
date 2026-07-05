package com.retrofm.android.di

import com.retrofm.android.data.api.RetroFmApi
import com.retrofm.android.data.config.RetroFmConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit

object NetworkModule {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(RetroFmConfig.API_BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    val retroFmApi: RetroFmApi by lazy {
        retrofit.create(RetroFmApi::class.java)
    }
}
