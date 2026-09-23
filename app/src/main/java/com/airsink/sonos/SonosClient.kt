package com.airsink.sonos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class SonosTrack(
    val title: String?,
    val artist: String?,
    val album: String?,
    val artUrl: String?,
)

/** UPnP/SOAP control for one Sonos player (port 1400). */
class SonosClient(val host: String) {

    private enum class Service(val path: String, val urn: String) {
        RENDERING("/MediaRenderer/RenderingControl/Control", "RenderingControl"),
        GROUP_RENDERING("/MediaRenderer/GroupRenderingControl/Control", "GroupRenderingControl"),
        AV_TRANSPORT("/MediaRenderer/AVTransport/Control", "AVTransport"),
        TOPOLOGY("/ZoneGroupTopology/Control", "ZoneGroupTopology"),
        DEVICE_PROPERTIES("/DeviceProperties/Control", "DeviceProperties"),
    }

    // ---- Rendering -------------------------------------------------------------------

    suspend fun getVolume() = rendering("GetVolume", "Channel" to "Master").int("CurrentVolume")
    suspend fun setVolume(v: Int) { rendering("SetVolume", "Channel" to "Master", "DesiredVolume" to v.coerceIn(0, 100).toString()) }
    suspend fun getMute() = rendering("GetMute", "Channel" to "Master").tag("CurrentMute") == "1"
    suspend fun setMute(m: Boolean) { rendering("SetMute", "Channel" to "Master", "DesiredMute" to m.bit()) }
    suspend fun getBass() = rendering("GetBass").int("CurrentBass")
    suspend fun setBass(v: Int) { rendering("SetBass", "DesiredBass" to v.coerceIn(-10, 10).toString()) }
    suspend fun getTreble() = rendering("GetTreble").int("CurrentTreble")
    suspend fun setTreble(v: Int) { rendering("SetTreble", "DesiredTreble" to v.coerceIn(-10, 10).toString()) }
    suspend fun getLoudness() = rendering("GetLoudness", "Channel" to "Master").tag("CurrentLoudness") == "1"
    suspend fun setLoudness(on: Boolean) { rendering("SetLoudness", "Channel" to "Master", "DesiredLoudness" to on.bit()) }

    /** Soundbar-only settings. Returns null when the speaker doesn't support them. */
    suspend fun getNightMode() = getEq("NightMode")
    suspend fun setNightMode(on: Boolean) = setEq("NightMode", on)
    suspend fun getSpeechEnhancement() = getEq("DialogLevel")
    suspend fun setSpeechEnhancement(on: Boolean) = setEq("DialogLevel", on)

    private suspend fun getEq(type: String): Boolean? =
        runCatching { rendering("GetEQ", "EQType" to type).tag("CurrentValue") == "1" }.getOrNull()

    private suspend fun setEq(type: String, on: Boolean) {
        rendering("SetEQ", "EQType" to type, "DesiredValue" to on.bit())
    }

    suspend fun getGroupVolume() = soap(Service.GROUP_RENDERING, "GetGroupVolume", "InstanceID" to "0").int("CurrentVolume")
    suspend fun setGroupVolume(v: Int) {
        soap(Service.GROUP_RENDERING, "SetGroupVolume", "InstanceID" to "0", "DesiredVolume" to v.coerceIn(0, 100).toString())
    }

    // ---- Transport -------------------------------------------------------------------

    suspend fun play() { transport("Play", "Speed" to "1") }
    suspend fun pause() { transport("Pause") }
    suspend fun stop() { transport("Stop") }
    suspend fun next() { transport("Next") }
    suspend fun previous() { transport("Previous") }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED or TRANSITIONING. */
    suspend fun transportState(): String? = transport("GetTransportInfo").tag("CurrentTransportState")

    suspend fun currentTrack(): SonosTrack? {
        val res = transport("GetPositionInfo")
        val meta = res.tag("TrackMetaData")?.let(::unescape) ?: return null
        if (meta == "NOT_IMPLEMENTED" || meta.isBlank()) return null
        val art = didl(meta, "upnp:albumArtURI")?.let { if (it.startsWith("/")) "http://$host:1400$it" else it }
        return SonosTrack(
            title = didl(meta, "dc:title") ?: didl(meta, "r:streamContent"),
            artist = didl(meta, "dc:creator"),
            album = didl(meta, "upnp:album"),
            artUrl = art,
        )
    }

    suspend fun setUri(uri: String, title: String) {
        val meta = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="R:0/0/0" parentID="R:0/0" restricted="true"><dc:title>${escape(title)}</dc:title><upnp:class>object.item.audioItem.audioBroadcast</upnp:class><desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON65031_</desc></item></DIDL-Lite>"""
        transport("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to meta)
    }

    // ---- Grouping --------------------------------------------------------------------

    suspend fun joinGroup(coordinatorUuid: String) {
        transport("SetAVTransportURI", "CurrentURI" to "x-rincon:$coordinatorUuid", "CurrentURIMetaData" to "")
    }

    suspend fun leaveGroup() { transport("BecomeCoordinatorOfStandaloneGroup") }

    suspend fun zoneGroupState(): String? =
        soap(Service.TOPOLOGY, "GetZoneGroupState").tag("ZoneGroupState")?.let(::unescape)

    // ---- Device ----------------------------------------------------------------------

    suspend fun getStatusLight() = soap(Service.DEVICE_PROPERTIES, "GetLEDState").tag("CurrentLEDState") == "On"
    suspend fun setStatusLight(on: Boolean) {
        soap(Service.DEVICE_PROPERTIES, "SetLEDState", "DesiredLEDState" to if (on) "On" else "Off")
    }
    suspend fun getButtonLock() = soap(Service.DEVICE_PROPERTIES, "GetButtonLockState").tag("CurrentButtonLockState") == "On"
    suspend fun setButtonLock(on: Boolean) {
        soap(Service.DEVICE_PROPERTIES, "SetButtonLockState", "DesiredButtonLockState" to if (on) "On" else "Off")
    }

    // ---- SOAP plumbing ---------------------------------------------------------------

    private suspend fun rendering(action: String, vararg args: Pair<String, String>) =
        soap(Service.RENDERING, action, "InstanceID" to "0", *args)

    private suspend fun transport(action: String, vararg args: Pair<String, String>) =
        soap(Service.AV_TRANSPORT, action, "InstanceID" to "0", *args)

    private suspend fun soap(service: Service, action: String, vararg args: Pair<String, String>): String =
        withContext(Dispatchers.IO) {
            val urn = "urn:schemas-upnp-org:service:${service.urn}:1"
            val body = buildString {
                append("""<?xml version="1.0" encoding="utf-8"?>""")
                append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
                append("""<u:$action xmlns:u="$urn">""")
                args.forEach { (k, v) -> append("<$k>${escape(v)}</$k>") }
                append("</u:$action></s:Body></s:Envelope>")
            }.toByteArray()
            val conn = URL("http://$host:1400${service.path}").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPACTION", "\"$urn#$action\"")
                conn.outputStream.use { it.write(body) }
                if (conn.responseCode !in 200..299) {
                    throw SonosException("$action failed (${conn.responseCode})")
                }
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        private fun String.tag(name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(this)?.groupValues?.get(1)

        private fun String.int(name: String): Int = tag(name)?.trim()?.toIntOrNull() ?: 0

        private fun didl(xml: String, tag: String): String? =
            Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                ?.let(::unescape)?.takeIf { it.isNotBlank() }

        private fun Boolean.bit() = if (this) "1" else "0"
    }
}

class SonosException(message: String) : Exception(message)
