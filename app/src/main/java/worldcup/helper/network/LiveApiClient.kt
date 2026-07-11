package worldcup.helper.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import worldcup.helper.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Tab A 的 API 客户端单例
 *
 * 创建 3 个 Retrofit 实例，分别对应 3 套 API：
 *   football-data.org  (FREE_PLUS_LIVESCORES, 10次/分)
 *   api-sports.io      (Pro, 7500次/天)
 *   BDL GOAT           ($39.99/月, 600次/分)
 *
 * 用法:
 *   val score = LiveApiClient.footballData.getMatches(status = "LIVE")
 *   val events = LiveApiClient.apiSports.getFixtureEvents(fixtureId = 1539016)
 */
object LiveApiClient {

    // ========================================================================
    // 公共 OkHttpClient（带日志和超时）
    // ========================================================================

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    // ========================================================================
    // 1. football-data.org  (免费, 10次/分)
    // ========================================================================

    val footballData: FootballDataApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.football-data.org/")
            .client(okHttpClient.newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .addHeader("X-Auth-Token", BuildConfig.FOOTBALL_DATA_API_KEY)
                        .build())
                }
                .build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FootballDataApi::class.java)
    }

    // ========================================================================
    // 2. api-sports.io  (Pro Key, 7500次/天)
    // ========================================================================

    val apiSports: ApiSportsApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://v3.football.api-sports.io/")
            .client(okHttpClient.newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .addHeader("x-apisports-key", BuildConfig.API_SPORTS_KEY)
                        .build())
                }
                .build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiSportsApi::class.java)
    }

    // ========================================================================
    // 3. BDL GOAT  ($39.99/月, 600次/分)
    // ========================================================================

    val bdlApi: BalldontlieApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.balldontlie.io/")
            .client(okHttpClient.newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .addHeader("Authorization", BuildConfig.BALLDONTLIE_API_KEY)
                        .build())
                }
                .build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BalldontlieApi::class.java)
    }
}
