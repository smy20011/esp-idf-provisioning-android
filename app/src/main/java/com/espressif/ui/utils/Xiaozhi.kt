package com.espressif.ui.utils

import android.util.Log
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.provisioning.listeners.ResponseListener
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import java.lang.Exception
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@Serializable
data class DeviceInfo(
    val version: Int?,
    val language: String?,
    val flash_size: Long?,
    val minimum_free_heap_size: Long?,
    val mac_address: String?,
    val uuid: String?,
    val chip_model_name: String?,
    val chip_info: ChipInfo?,
    val application: Application?,
    val partition_table: List<Partition>?,
    val ota: Ota?,
    val board: Board?
)

@Serializable
data class ChipInfo(
    val model: Int?,
    val cores: Int?,
    val revision: Int?,
    val features: Int?
)

@Serializable
data class Application(
    val name: String?,
    val version: String?,
    val compile_time: String?,
    val idf_version: String?,
    val elf_sha256: String?
)

@Serializable
data class Partition(
    val label: String?,
    val type: Int?,
    val subtype: Int?,
    val address: Long?,
    val size: Long?
)

@Serializable
data class Ota(
    val label: String?
)

@Serializable
data class Board(
    val type: String?,
    val name: String?,
    val ssid: String?,
    val rssi: Int?,
    val channel: Int?,
    val ip: String?,
    val mac: String?
)

@OptIn(ExperimentalSerializationApi::class)
public class Xiaozhi(val provisionManager: ESPProvisionManager) {
    val json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    suspend fun loadDeviceInfo()= suspendCoroutine { continuation ->
        provisionManager.espDevice.sendDataToCustomEndPoint(
            "boardjson",
            "1234".toByteArray(),
            object : ResponseListener {
                override fun onSuccess(returnData: ByteArray) {
                    val str =
                        String(returnData.sliceArray(0..(returnData.size - 2)), Charsets.UTF_8);
                    continuation.resumeWith(kotlin.runCatching {
                        json.decodeFromString<DeviceInfo>(str)
                    }.map { Pair(it, str) })
                }

                override fun onFailure(e: Exception) {
                    continuation.resumeWithException(e)
                }
            });
    }

    suspend fun registerXiaozhiDevice() {
        val (deviceInfo, json) = loadDeviceInfo()
        val client = HttpClient(CIO)
        val response = client.post {
            contentType(ContentType.Application.Json)
            setBody(json)
            header("Activation-Version", "2")
            header("Device-Id", deviceInfo.mac_address ?: "")
            header("Client-Id", deviceInfo.uuid ?: "")
            val boardName = deviceInfo.board?.name ?: "UNKNOWN_BOARD"
            val appVersion = deviceInfo.application?.version ?: "UNKNOWN_VERSION"
            header("User-Agent", "$boardName/$appVersion")
            header("Accept-Language", "zh-CN")
        }
        Log.e("Xiaozhi", "Response is: ${response.body<String>()}")
    }

    fun registerXiaozhiAsync() = GlobalScope.future { registerXiaozhiDevice() }
}