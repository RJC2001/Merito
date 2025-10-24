package com.rjc.merito.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rjc.merito.R
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object RetrofitProvider {

    /**
     * Create Retrofit instance.
     * @param context used to read access key and cache dir.
     * @param forceNetwork when true the request will bypass cache and go to network.
     */
    fun create(context: Context, forceNetwork: Boolean = false): Retrofit {
        val accessKey = context.getString(R.string.unsplash_access_key)

        val authInterceptor = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("Authorization", "Client-ID $accessKey")
                .build()
            chain.proceed(req)
        }

        val cacheSize = 20L * 1024 * 1024
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, cacheSize)

        val maxAgeSec = 600
        val networkInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            response.newBuilder()
                .header("Cache-Control", "public, max-age=$maxAgeSec")
                .removeHeader("Pragma")
                .build()
        }

        val offlineInterceptor = Interceptor { chain ->
            var request = chain.request()
            val networkAvailable = isNetworkAvailable(context)

            if (!networkAvailable) {
                val maxStale = 60 * 60 * 24 * 7
                request = request.newBuilder()
                    .header("Cache-Control", "public, only-if-cached, max-stale=$maxStale")
                    .build()
            } else if (forceNetwork) {
                request = request.newBuilder()
                    .header("Cache-Control", "no-cache")
                    .build()
            }
            chain.proceed(request)
        }

        val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

        val client = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(offlineInterceptor)
            .addNetworkInterceptor(networkInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(logger)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.unsplash.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val nc = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nc) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
