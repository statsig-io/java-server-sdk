package server

import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody


class StatsigNetwork(private val sdkKey: String, private val options: StatsigOptions) {
    val json: MediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient: OkHttpClient

    init {
        var clientBuilder = OkHttpClient.Builder()

        clientBuilder.addInterceptor(Interceptor {
            var original = it.request()
            var request = original.newBuilder()
                    .header("STATSIG-API-KEY", sdkKey)
                    .method(original.method, original.body)
                    .build()
            it.proceed(request)
        })
        httpClient = clientBuilder.build()
    }

    suspend fun checkGate(user: StatsigUser, gateName: String): APIFeatureGate {
        val body: RequestBody = "{\"gateName\":\"gateName\"}".toRequestBody(json)

        val request: Request = Request.Builder()
                .url(options.api + "/check_gate")
                .post(body)
                .build()
        httpClient.newCall(request).execute().use { response ->
            return Gson().fromJson(response.body?.charStream(), APIFeatureGate::class.java)
        }
    }

    suspend fun getConfig(user: StatsigUser, configName: String): APIDynamicConfig {
        val body: RequestBody = "{\"configName\":\"configName\"}".toRequestBody(json)

        val request: Request = Request.Builder()
                .url(options.api + "/get_config")
                .post(body)
                .build()
        httpClient.newCall(request).execute().use { response ->
            return Gson().fromJson(response.body?.charStream(), APIDynamicConfig::class.java)
        }
    }

    suspend fun downloadConfigSpecs(): APIDownloadedConfigs {
        val body: RequestBody = "{}".toRequestBody(json)

        val request: Request = Request.Builder()
                .url(options.api + "/download_config_specs")
                .post(body)
                .build()
        httpClient.newCall(request).execute().use { response ->
            return Gson().fromJson(response.body?.charStream(), APIDownloadedConfigs::class.java)
        }
    }
}
