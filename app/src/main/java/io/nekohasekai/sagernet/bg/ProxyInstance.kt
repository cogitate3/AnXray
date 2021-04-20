package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.SystemClock
import cn.hutool.json.JSONObject
import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.PluginManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.gson.gson
import io.nekohasekai.sagernet.fmt.v2ray.V2rayConfig
import io.nekohasekai.sagernet.fmt.v2ray.buildV2rayConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File
import java.io.IOException
import java.util.*

class ProxyInstance(val profile: ProxyEntity) {

    lateinit var v2rayPoint: V2RayPoint
    lateinit var config: V2rayConfig
    lateinit var base: BaseService.Interface

    val bind get() = if (DataStore.allowAccess) "0.0.0.0" else "127.0.0.1"

    fun init(service: BaseService.Interface) {
        base = service
        v2rayPoint = Libv2ray.newV2RayPoint(SagerSupportClass(if (service is VpnService)
            service else null), false)
        v2rayPoint.domainName =
            profile.requireBean().serverAddress + ":" + profile.requireBean().serverPort
        config = buildV2rayConfig(profile, bind, DataStore.socks5Port)
        v2rayPoint.configureFileContent = gson.toJson(config).also {
            Logs.d(it)
        }
    }

    var cacheFiles = LinkedList<File>()

    fun start() {
        if (profile.useExternalShadowsocks()) {
            val bean = profile.requireSS()
            val port = DataStore.socks5Port + 10

            val proxyConfig = JSONObject().also {
                it["server"] = bean.serverAddress
                it["server_port"] = bean.serverPort
                it["method"] = bean.method
                it["password"] = bean.password
                it["local_address"] = "127.0.0.1"
                it["local_port"] = port
                it["local_udp_address"] = "127.0.0.1"
                it["local_udp_port"] = port
                it["mode"] = "tcp_and_udp"
            }

            if (bean.plugin.isNotBlank()) {
                val pluginConfiguration = PluginConfiguration(bean.plugin ?: "")
                PluginManager.init(pluginConfiguration)?.let { (path, opts, isV2) ->
                    proxyConfig["plugin"] = path
                    proxyConfig["plugin_args"] = opts.toString()
                }
            }

            Logs.d(proxyConfig.toStringPretty())

            val context =
                if (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked)
                    SagerNet.application else SagerNet.deviceStorage
            val configFile =
                File(context.noBackupFilesDir,
                    "shadowsocks_" + SystemClock.elapsedRealtime() + ".json")
            configFile.writeText(proxyConfig.toString())
            cacheFiles.add(configFile)

            val commands = mutableListOf(
                File(SagerNet.application.applicationInfo.nativeLibraryDir,
                    Executable.SS_LOCAL).absolutePath,
                "-c", configFile.absolutePath
            )

            base.data.processes!!.start(commands)
        }

        v2rayPoint.runLoop(DataStore.preferIpv6)
    }

    fun stop() {
        v2rayPoint.stopLoop()
    }

    fun printStats() {
        val tags = config.outbounds.map { outbound -> outbound.tag.takeIf { !it.isNullOrBlank() } }
        for (tag in tags) {
            val uplink = v2rayPoint.queryStats(tag, "uplink")
            val downlink = v2rayPoint.queryStats(tag, "downlink")
            println("$tag >> uplink $uplink / downlink $downlink")
        }
    }

    fun stats(direct: String): Long {
        if (!::v2rayPoint.isInitialized) {
            return 0L
        }
        return v2rayPoint.queryStats("out", direct)
    }

    val uplink
        get() = stats("uplink").also {
            uplinkTotal += it
        }

    val downlink
        get() = stats("downlink").also {
            downlinkTotal += it
        }

    var uplinkTotal = 0L
    var downlinkTotal = 0L

    fun persistStats() {
        try {
            uplink
            downlink
            profile.tx += uplinkTotal
            profile.rx += downlinkTotal
            SagerDatabase.proxyDao.updateProxy(profile)
        } catch (e: IOException) {
            /*  if (!DataStore.directBootAware) throw e*/ // we should only reach here because we're in direct boot
        }
    }

    fun shutdown(coroutineScope: CoroutineScope) {
        cacheFiles.removeAll { it.delete(); true }
    }

    private class SagerSupportClass(val service: VpnService?) : V2RayVPNServiceSupportsSet {

        override fun onEmitStatus(p0: Long, status: String): Long {
            Logs.i("onEmitStatus $status")
            return 0L
        }

        override fun prepare(): Long {
            return 0L
        }

        override fun protect(l: Long): Boolean {
            return (service ?: return true).protect(l.toInt())
        }

        override fun setup(p0: String?): Long {
            return 0
        }

        override fun shutdown(): Long {
            return 0
        }
    }


}