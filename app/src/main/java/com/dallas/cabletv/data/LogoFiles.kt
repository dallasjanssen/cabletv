package com.dallas.cabletv.data

import java.io.File

private val LOGO_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

/**
 * Logo pictures kept in a folder the viewer chose. The file name is compared with [logoMatchKey],
 * so "BBC One.png" and "bbc-one.jpg" are the same channel. A missing or unreadable folder returns
 * nothing; it must not crash the guide.
 */
object LogoFiles {
    private val lock = Any()
    private var cachedFolder = ""
    private var cachedStamp = Long.MIN_VALUE
    private var cachedReady = false
    private var cached: Map<String, File> = emptyMap()

    fun find(folderPath: String, channelName: String): File? {
        val want = logoMatchKey(channelName)
        if (want.isEmpty() || folderPath.isBlank()) return null
        val index = try {
            indexOf(folderPath.trim())
        } catch (e: Exception) {
            null
        } ?: return null
        return index[want]
    }

    private fun indexOf(folderPath: String): Map<String, File>? {
        val dir = File(folderPath)
        val stamp = try {
            if (!dir.isDirectory) return null
            dir.lastModified()
        } catch (e: Exception) {
            return null
        }
        synchronized(lock) {
            if (cachedReady && cachedFolder == folderPath && cachedStamp == stamp) return cached
            val files = try {
                dir.listFiles()
            } catch (e: Exception) {
                return null
            } ?: return null
            val map = HashMap<String, File>()
            for (file in files) {
                try {
                    if (!file.isFile) continue
                    val ext = file.extension.lowercase()
                    if (ext !in LOGO_EXTENSIONS) continue
                    val key = logoMatchKey(file.nameWithoutExtension)
                    if (key.isNotEmpty() && key !in map) map[key] = file
                } catch (e: Exception) {
                    // One bad name is skipped. The rest of the folder can still match.
                }
            }
            cachedFolder = folderPath
            cachedStamp = stamp
            cached = map
            cachedReady = true
            return map
        }
    }
}
