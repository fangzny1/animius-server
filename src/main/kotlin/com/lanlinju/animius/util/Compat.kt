package com.lanlinju.animius.util

import com.lanlinju.animius.data.remote.parse.AnimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * JVM 版偏好存储：一个 JSON 文件顶替 Android SharedPreferences。
 * 解析器只用 getString / putString / getBoolean / remove 这几个方法。
 */
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun remove(key: String)
}

object SettingsStore {
    lateinit var file: Path
    private val lock = ReentrantReadWriteLock()
    private val json = Json { prettyPrint = true }

    private fun load(): MutableMap<String, String> {
        val f = file.toFile()
        if (!f.exists()) return mutableMapOf()
        return try {
            val obj = json.parseToJsonElement(f.readText()).jsonObject
            obj.entries.associate { it.key to it.value.jsonPrimitive.content }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun save(map: Map<String, String>) {
        val obj = JsonObject(map.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
        val f = file.toFile()
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(JsonObject.serializer(), obj))
    }

    fun get(key: String, def: String? = null): String? = lock.read {
        load()[key] ?: def
    }

    fun put(key: String, value: String) = lock.write {
        val m = load()
        m[key] = value
        save(m)
    }

    fun remove(key: String) = lock.write {
        val m = load()
        m.remove(key)
        save(m)
    }

    fun asMap(): Map<String, String> = lock.read { load().toMap() }
}

object FilePreferences : SharedPreferences {
    override fun getString(key: String, defValue: String?): String? = SettingsStore.get(key) ?: defValue
    override fun putString(key: String, value: String) = SettingsStore.put(key, value)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = SettingsStore.get(key)?.toBooleanStrictOrNull() ?: defValue
    override fun remove(key: String) = SettingsStore.remove(key)
}

val AnimeSource.preferences: SharedPreferences
    get() = FilePreferences

fun AnimeSource.getDefaultDomain(): String {
    return FilePreferences.getString(KEY_SOURCE_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN
}

suspend fun AnimeSource.getDocument(url: String): Document {
    val source = DownloadManager.getHtml(url)
    return Jsoup.parse(source)
}

/**
 * 先Base64解码数据，然后再AES解密
 */
fun AnimeSource.decryptData(data: String, key: String, iv: String): String {
    val bytes = Base64.getDecoder().decode(data.toByteArray())
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val keySpec = SecretKeySpec(key.toByteArray(), "AES")
    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv.toByteArray()))
    return cipher.doFinal(bytes).decodeToString()
}

fun <T> T.log(tag: String = "Debug", prefix: String = ""): T {
    val prefixStr = if (prefix.isEmpty()) "" else "[$prefix] "
    println("[$tag] $prefixStr$this")
    return this
}
