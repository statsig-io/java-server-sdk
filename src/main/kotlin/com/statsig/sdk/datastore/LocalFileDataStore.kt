package com.statsig.sdk.datastore

import com.statsig.sdk.StatsigOptions
import java.io.File

/**
 * The LocalFileDataStore class serves the specific purpose of implementing the IDataStore interface,
 * focusing on facilitating data storage operations through the use of local file systems.
 * This is usually for testing purpose.
 *
 * This class is compatibility with multi-instance setups. By allowing
 * each instance to be configured with its own 'LocalFileDataStore'
 * and a unique filePath, it ensures that operations performed by one instance are completely
 * isolated from those of another.
 *
 * @property filePath specific the full path to the local file intended for data storage.
 *                    Assigning a unique filePath to each instance if you have multi-instances.
 *                    Ensure the fileName is in JSON format for compatibility and error prevention.
 * @property autoUpdate Indicates whether the local data store should automatically update when changes are detected in the
 *                      data file. Default is `false`, meaning automatic updates are disabled unless explicitly enabled.
 */
class LocalFileDataStore @JvmOverloads constructor(
    var filePath: String,
    var autoUpdate: Boolean = false,
) : IDataStore() {
    private lateinit var options: StatsigOptions

    override var dataStoreKey: String
        get() = filePath
        set(value) {
            filePath = value
        }

    init {
        filePath = resolvePath(filePath)
        ensureWorkingDirectoryExists()
    }

    override fun shouldPollForUpdates(): Boolean {
        return autoUpdate
    }

    private fun ensureWorkingDirectoryExists() {
        val file = File(filePath)
        if (!file.exists()) {
            try {
                val parentDirectory = file.parentFile
                if (parentDirectory != null && !parentDirectory.exists()) {
                    parentDirectory.mkdirs()
                }

                file.createNewFile()
            } catch (e: Exception) {
                options.customLogger.error("An error occurred while creating the file: ${e.message}")
            }
        }
    }

    override fun get(key: String): String? {
        if (key != dataStoreKey) {
            options.customLogger.warn("Please provide the correct file path.")
        }

        return try {
            File(key).readText()
        } catch (e: Exception) {
            null
        }
    }

    override fun set(key: String, value: String) {
        if (key != dataStoreKey) {
            options.customLogger.warn("Please provide the correct file path.")
        }

        File(key).writeText(value)
    }

    override fun shutdown() {}

    private fun resolvePath(path: String): String {
        var resolvedPath = path
        if (!resolvedPath.startsWith("/")) {
            resolvedPath = File("").absolutePath + "/" + resolvedPath
        }
        return resolvedPath
    }

    override fun setStatsigOptions(options: StatsigOptions) {
        this.options = options
    }
}
