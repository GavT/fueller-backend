package uk.co.fueller.app.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import uk.co.fueller.app.BuildConfig
import java.util.concurrent.TimeUnit

interface FuelApi {

    @GET("/api/search")
    suspend fun search(
        @Query("postcode") postcode: String,
        @Query("radius") radius: Double = 5.0
    ): SearchResponse

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun create(): FuelApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BuildConfig.BACKEND_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(FuelApi::class.java)
        }
    }
}
