package server

class ServerDriver(private val serverSecret: String, private val options: StatsigOptions = StatsigOptions()) {
    private val network: StatsigNetwork
    private var configEvaluator: Evaluator
    private var initialized: Boolean = false

    init {
        if (serverSecret.isEmpty() || !serverSecret.startsWith("secret-")) {
            throw IllegalArgumentException("Statsig Server SDKs must be initialized with a secret key")
        }
        network = StatsigNetwork(serverSecret, options)
        configEvaluator = Evaluator()
    }

    suspend fun initialize() {
        initialized = true
        val downloadedConfigs = network.downloadConfigSpecs()
        configEvaluator.setDownloadedConfigs(downloadedConfigs)
    }

    suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling checkGate")
        }
        val result = configEvaluator.checkGate(user, gateName)
        if (result.fetchFromServer) {
            val networkResult = network.checkGate(user, gateName)
            return networkResult.value
        }
        return result.booleanValue
    }

    suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling checkGate")
        }
        val result = configEvaluator.getConfig(user, dynamicConfigName)
        if (result.fetchFromServer) {
            val networkResult = network.getConfig(user, dynamicConfigName)
            return DynamicConfig(Config(networkResult.name, networkResult.value, networkResult.ruleID))
        }
        return DynamicConfig(Config(dynamicConfigName, result.jsonValue as Map<String, Any>, result.ruleID))
    }
}
