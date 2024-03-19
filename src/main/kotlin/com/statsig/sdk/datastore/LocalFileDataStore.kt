package com.statsig.sdk.datastore

import java.io.File
import java.util.*

/**
 * LocalFileDataStore class implements IDataStore interface to provide data storage operations using local files.
 */
class LocalFileDataStore() : IDataStore() {

    var workingDirectory: String = "/tmp/statsig/"
    var filePath: String = ""

    init {
        workingDirectory = resolvePath(workingDirectory)
        if (!File(workingDirectory).exists()) {
            File(workingDirectory).mkdirs() // Ensure the working directory exists
        }
    }

    override fun get(key: String): String? {
        val path = "$workingDirectory${Base64.getEncoder().encodeToString(filePath.toByteArray())}"
        return try {
            File(path).readText()
        } catch (e: Exception) {
            null
        }
    }

    override fun set(key: String, value: String) {
        val path = "$workingDirectory${Base64.getEncoder().encodeToString(filePath.toByteArray())}"
        File(path).writeText(value)
    }

    override fun shutdown() {
        // No explicit shutdown operations needed for this class
    }

    internal fun resolvePath(path: String): String {
        var resolvedPath = path
        if (!resolvedPath.endsWith("/")) {
            resolvedPath += "/"
        }
        if (!resolvedPath.startsWith("/")) {
            resolvedPath = File("").absolutePath + "/" + resolvedPath
        }
        return resolvedPath
    }
}
