package com.example.mycctv

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.util.Calendar
import java.util.TimeZone

/**
 * Minimal ONVIF (Profile S style) server: enough for an NVR to add the phone by IP.
 *
 * - Runs on its OWN port (must be different from the RTSP port).
 * - Answers: GetSystemDateAndTime, GetDeviceInformation, GetCapabilities, GetServices,
 *   GetScopes, GetProfiles, GetProfile, GetStreamUri, GetVideoSources,
 *   GetVideoSourceConfigurations, GetVideoEncoderConfigurations.
 * - Does NOT check usernames/passwords yet, and has no auto-discovery (WS-Discovery).
 * - Every request is logged as "ONVIF ... action=Xxx" so we can see what the NVR asks for.
 */
class OnvifServer(
    port: Int,
    private val rtspPort: Int,
    private val rtspPath: String = "/live",
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 25,
    private val bitrateKbps: Int = 1200
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val body = readBody(session)
            val action = actionName(body)
            val hostHeader = session.headers["host"] ?: "0.0.0.0:$listeningPort"
            val hostIp = hostHeader.substringBefore(':')
            Log.d(TAG, "ONVIF ${session.method} ${session.uri} action=$action")

            val inner = handle(action, hostHeader, hostIp, body)
            if (inner != null) {
                soap(inner, Response.Status.OK)
            } else {
                Log.w(TAG, "Unsupported ONVIF action: $action")
                soap(fault(), Response.Status.INTERNAL_ERROR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ONVIF error: ${e.message}", e)
            soap(fault(), Response.Status.INTERNAL_ERROR)
        }
    }

    // ------------------------------------------------------------------ request handling

    private fun readBody(session: IHTTPSession): String {
        if (session.method != Method.POST) return ""
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    /** Finds the first element inside <Body>, e.g. "GetProfiles". */
    private fun actionName(body: String): String {
        val regex = Regex("<(?:[A-Za-z0-9_]+:)?Body[^>]*>\\s*<(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)")
        return regex.find(body)?.groupValues?.get(1) ?: ""
    }

    private fun handle(action: String, hostHeader: String, hostIp: String, body: String): String? = when (action) {
        "GetSystemDateAndTime" -> dateTime()
        "GetDeviceInformation" -> deviceInfo()
        "GetCapabilities" -> capabilities(hostHeader)
        "GetServices" -> services(hostHeader)
        "GetScopes" -> scopes()
        "GetProfiles" ->
            "<trt:GetProfilesResponse>${profile("trt:Profiles", "profile_1", "MainStream")}${profile("trt:Profiles", "profile_2", "SubStream")}</trt:GetProfilesResponse>"
        "GetProfile" -> {
            val wantsSub = body.contains("profile_2")
            val p = if (wantsSub) profile("trt:Profile", "profile_2", "SubStream")
            else profile("trt:Profile", "profile_1", "MainStream")
            "<trt:GetProfileResponse>$p</trt:GetProfileResponse>"
        }
        "GetStreamUri" -> streamUri(hostIp)
        "GetVideoSources" ->
            """<trt:GetVideoSourcesResponse><trt:VideoSources token="vs_1">
<tt:Framerate>$fps</tt:Framerate>
<tt:Resolution><tt:Width>$width</tt:Width><tt:Height>$height</tt:Height></tt:Resolution>
</trt:VideoSources></trt:GetVideoSourcesResponse>"""
        "GetVideoSourceConfigurations" ->
            "<trt:GetVideoSourceConfigurationsResponse>${sourceConfig("trt:Configurations")}</trt:GetVideoSourceConfigurationsResponse>"
        "GetVideoEncoderConfigurations" ->
            "<trt:GetVideoEncoderConfigurationsResponse>${encoderConfig("trt:Configurations")}</trt:GetVideoEncoderConfigurationsResponse>"
        else -> null
    }

    // ------------------------------------------------------------------ responses

    private fun dateTime(): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return """<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime>
<tt:DateTimeType>Manual</tt:DateTimeType>
<tt:DaylightSavings>false</tt:DaylightSavings>
<tt:TimeZone><tt:TZ>UTC0</tt:TZ></tt:TimeZone>
<tt:UTCDateTime>
<tt:Time><tt:Hour>${c.get(Calendar.HOUR_OF_DAY)}</tt:Hour><tt:Minute>${c.get(Calendar.MINUTE)}</tt:Minute><tt:Second>${c.get(Calendar.SECOND)}</tt:Second></tt:Time>
<tt:Date><tt:Year>${c.get(Calendar.YEAR)}</tt:Year><tt:Month>${c.get(Calendar.MONTH) + 1}</tt:Month><tt:Day>${c.get(Calendar.DAY_OF_MONTH)}</tt:Day></tt:Date>
</tt:UTCDateTime>
</tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"""
    }

    private fun deviceInfo(): String =
        """<tds:GetDeviceInformationResponse>
<tds:Manufacturer>AndroidCam</tds:Manufacturer>
<tds:Model>PhoneCamera</tds:Model>
<tds:FirmwareVersion>1.0</tds:FirmwareVersion>
<tds:SerialNumber>PHONE0001</tds:SerialNumber>
<tds:HardwareId>android</tds:HardwareId>
</tds:GetDeviceInformationResponse>"""

    private fun capabilities(hostHeader: String): String =
        """<tds:GetCapabilitiesResponse><tds:Capabilities>
<tt:Device><tt:XAddr>http://$hostHeader/onvif/device_service</tt:XAddr></tt:Device>
<tt:Media><tt:XAddr>http://$hostHeader/onvif/media_service</tt:XAddr>
<tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities>
</tt:Media>
</tds:Capabilities></tds:GetCapabilitiesResponse>"""

    private fun services(hostHeader: String): String =
        """<tds:GetServicesResponse>
<tds:Service><tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace><tds:XAddr>http://$hostHeader/onvif/device_service</tds:XAddr><tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version></tds:Service>
<tds:Service><tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace><tds:XAddr>http://$hostHeader/onvif/media_service</tds:XAddr><tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version></tds:Service>
</tds:GetServicesResponse>"""

    private fun scopes(): String =
        """<tds:GetScopesResponse>
<tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/Profile/Streaming</tt:ScopeItem></tds:Scopes>
<tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/type/video_encoder</tt:ScopeItem></tds:Scopes>
<tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/name/PhoneCamera</tt:ScopeItem></tds:Scopes>
</tds:GetScopesResponse>"""

    private fun streamUri(hostIp: String): String =
        """<trt:GetStreamUriResponse><trt:MediaUri>
<tt:Uri>rtsp://$hostIp:$rtspPort$rtspPath</tt:Uri>
<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
<tt:Timeout>PT0S</tt:Timeout>
</trt:MediaUri></trt:GetStreamUriResponse>"""

    private fun sourceConfig(element: String): String =
        """<$element token="vsc_1">
<tt:Name>VideoSourceConfig</tt:Name>
<tt:UseCount>1</tt:UseCount>
<tt:SourceToken>vs_1</tt:SourceToken>
<tt:Bounds x="0" y="0" width="$width" height="$height"/>
</$element>"""

    private fun encoderConfig(element: String): String =
        """<$element token="vec_1">
<tt:Name>H264Config</tt:Name>
<tt:UseCount>1</tt:UseCount>
<tt:Encoding>H264</tt:Encoding>
<tt:Resolution><tt:Width>$width</tt:Width><tt:Height>$height</tt:Height></tt:Resolution>
<tt:Quality>5</tt:Quality>
<tt:RateControl><tt:FrameRateLimit>$fps</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>$bitrateKbps</tt:BitrateLimit></tt:RateControl>
<tt:H264><tt:GovLength>$fps</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264>
<tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address><tt:Port>0</tt:Port><tt:TTL>1</tt:TTL><tt:AutoStart>false</tt:AutoStart></tt:Multicast>
<tt:SessionTimeout>PT60S</tt:SessionTimeout>
</$element>"""

    private fun profile(element: String, token: String, name: String): String =
        """<$element token="$token" fixed="true">
<tt:Name>$name</tt:Name>
${sourceConfig("tt:VideoSourceConfiguration")}
${encoderConfig("tt:VideoEncoderConfiguration")}
</$element>"""

    private fun fault(): String =
        """<s:Fault>
<s:Code><s:Value>s:Sender</s:Value><s:Subcode><s:Value>ter:ActionNotSupported</s:Value></s:Subcode></s:Code>
<s:Reason><s:Text xml:lang="en">Action not supported</s:Text></s:Reason>
</s:Fault>"""

    private fun envelope(inner: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
 xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
 xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
 xmlns:tt="http://www.onvif.org/ver10/schema"
 xmlns:ter="http://www.onvif.org/ver10/error">
<s:Body>$inner</s:Body>
</s:Envelope>"""

    private fun soap(inner: String, status: Response.IStatus): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/soap+xml; charset=utf-8", envelope(inner))

    companion object {
        private const val TAG = "OnvifServer"
    }
}

/*
 ======================================================================
 1) app/build.gradle.kts  ->  dependencies { ... }
 ======================================================================
     implementation("org.nanohttpd:nanohttpd:2.3.1")

 ======================================================================
 2) StreamingService.kt  ->  changes
 ======================================================================

 import fi.iki.elonen.NanoHTTPD            // add to the imports

 private var onvifServer: OnvifServer? = null   // add next to the other fields

 // REPLACE the two placeholder functions with these:
 private fun startOnvifServer() {
     if (onvifServer != null) return
     try {
         val server = OnvifServer(port = ONVIF_PORT, rtspPort = currentPort, rtspPath = "/live")
         server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
         onvifServer = server
         Log.d(TAG, "ONVIF server started on port $ONVIF_PORT")
     } catch (e: Exception) {
         Log.e(TAG, "Could not start ONVIF server: ${e.message}", e)
     }
 }

 private fun stopOnvifServer() {
     onvifServer?.stop()
     onvifServer = null
 }

 // In the companion object, CHANGE/ADD:
 const val DEFAULT_PORT = 8554     // RTSP  (was 8080: it must not clash with ONVIF)
 const val ONVIF_PORT = 8080       // ONVIF (HTTP)

 // Also call stopOnvifServer() inside onDestroy() so the port is freed.
*/