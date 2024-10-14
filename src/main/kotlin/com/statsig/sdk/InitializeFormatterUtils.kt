package com.statsig.sdk

/**
 * internal utils class for generate intialize response
 * for both client and on device eval client sdk
 */
internal object InitializeFormatterUtils {
    fun configSpecIsForThisTargetApp(clientSDKKey: String?, specStore: SpecStore, configSpec: APIConfig): Boolean {
        if (clientSDKKey == null) {
            // no client key provided, send me everything
            return true
        }
        var targetAppID = specStore.getAppIDFromKey(clientSDKKey)
        if (targetAppID == null) {
            // no target app id for the given SDK key, send me everything
            return true
        }
        if (configSpec.targetAppIDs == null) {
            // no target app id associated with this config
            // if the key does have a target app id it's not for this app
            return false
        }

        return configSpec.targetAppIDs.contains(targetAppID)
    }
}
