package com.statsig.sdk

internal class SDKConfigs() {
    private var flags: Map<String, Boolean> = mapOf()
    private var configs: Map<String, Any> = mapOf()

    fun setFlags(flags: Map<String, Boolean>) {
        this.flags = flags
    }
    fun setConfigs(configs: Map<String, Any>) {
        this.configs = configs
    }
    fun getFlag(flag: String, default: Boolean = false): Boolean {
        return flags.getOrDefault(flag, default)
    }

    fun getConfigNumValue(config: String): Number? {
        return configs[config] as? Number
    }

    fun getConfigsStrValue(config: String): String? {
        return configs[config] as? String
    }
}
