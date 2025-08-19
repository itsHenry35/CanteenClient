package com.itshenry.canteenclient.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private var baseUrl: String? = null
    private var retrofit: Retrofit? = null

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun setBaseUrl(url: String) {
        // 确保URL以斜杠结尾
        baseUrl = if (url.endsWith("/")) url else "$url/"
        // 重置retrofit实例，下次获取apiService时会重新创建
        retrofit = null
    }

    val apiService: ApiService
        get() {
            checkNotNull(baseUrl) { "API端点未设置，请先设置API端点" } // uh正常来说应该不会，但还是写着以防万一吧

            if (retrofit == null) {
                retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl!!)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            }
            return retrofit!!.create(ApiService::class.java)
        }
}