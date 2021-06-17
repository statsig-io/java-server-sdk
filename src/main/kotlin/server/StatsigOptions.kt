package server

enum class Tier {
    PRODUCTION,
    STAGING,
    DEVELOPMENT,
}

private const val TIER_KEY: String = "tier"

/**
 * An object of properties for initializing the sdk with advanced options
 * @property api the api endpoint to use for initialization and logging
 * @property initTimeoutMs the amount of time to wait for an initialize() response from the server
 * NOTE: gates/configs will still be fetched in the background if this time is exceeded, but the
 * callback to initialize will fire after, at most, the time specified
 */
class StatsigOptions(
    var api: String = "https://api.statsig.com/v1",
    var initTimeoutMs: Long = 3000L,
) {
    private var environment : MutableMap<String, String>? = null;

    fun setTier(tier : Tier) {
        setEnvironmentParameter(TIER_KEY, tier.toString().lowercase())
    }

    fun setEnvironmentParameter(key: String, value: String){
        if (environment == null) {
            environment = mutableMapOf(key to value)
            return
        }
        environment!![key] = value
    }

    fun getEnvironment(): MutableMap<String, String>? {
        return environment
    }
}
