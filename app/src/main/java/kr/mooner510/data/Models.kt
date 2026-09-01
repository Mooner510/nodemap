package kr.mooner510.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class EventType { PIN_MANUAL, PIN_ROUTINE, PHONE_CALL, SMS, MMS, NOTIFICATION, SYSTEM }

data class TrackPoint(val id:Long=0,val timestamp:Long,val latitude:Double,val longitude:Double,val accuracyMeters:Float,val altitudeMeters:Double?=null,val speedMps:Float?=null,val bearingDegrees:Float?=null,val provider:String?=null,val isMock:Boolean=false) {
    fun toJson()=JSONObject().apply{put("latitude",latitude);put("longitude",longitude);put("accuracyMeters",accuracyMeters.toDouble());altitudeMeters?.let{put("altitudeMeters",it)};speedMps?.let{put("speedMps",it.toDouble())};bearingDegrees?.let{put("bearingDegrees",it.toDouble())};provider?.let{put("provider",it)};put("isMock",isMock)}
    companion object { fun fromJson(id:Long,timestamp:Long,json:JSONObject)=TrackPoint(id,timestamp,json.getDouble("latitude"),json.getDouble("longitude"),json.optDouble("accuracyMeters",9999.0).toFloat(),json.optDoubleOrNull("altitudeMeters"),json.optDoubleOrNull("speedMps")?.toFloat(),json.optDoubleOrNull("bearingDegrees")?.toFloat(),json.optStringOrNull("provider"),json.optBoolean("isMock",false)) }
}

data class TimelineEvent(val id:String=UUID.randomUUID().toString(),val timestamp:Long,val type:EventType,val latitude:Double?=null,val longitude:Double?=null,val title:String,val body:String?=null,val source:String?=null,val metadata:Map<String,String> = emptyMap(),val attachmentIds:List<String> = emptyList()) {
    fun toJson()=JSONObject().apply{latitude?.let{put("latitude",it)};longitude?.let{put("longitude",it)};put("title",title);body?.let{put("body",it)};source?.let{put("source",it)};put("metadata",JSONObject(metadata));put("attachmentIds",JSONArray(attachmentIds))}
    companion object { fun fromJson(id:String,timestamp:Long,type:EventType,json:JSONObject):TimelineEvent { val m=json.optJSONObject("metadata")?:JSONObject(); val metadata=buildMap{m.keys().forEach{put(it,m.optString(it))}}; val a=json.optJSONArray("attachmentIds")?:JSONArray(); return TimelineEvent(id,timestamp,type,json.optDoubleOrNull("latitude"),json.optDoubleOrNull("longitude"),json.optString("title",type.name),json.optStringOrNull("body"),json.optStringOrNull("source"),metadata,buildList{for(i in 0 until a.length())add(a.getString(i))}) } }
}

data class NotificationRule(val id:String=UUID.randomUUID().toString(),val name:String,val packageNames:Set<String>,val includeRegex:String="",val excludeRegex:String="",val enabled:Boolean=true) {
    fun toJson()=JSONObject().apply{put("name",name);put("packageNames",JSONArray(packageNames.toList()));put("includeRegex",includeRegex);put("excludeRegex",excludeRegex);put("enabled",enabled)}
    companion object { fun fromJson(id:String,json:JSONObject):NotificationRule { val p=json.optJSONArray("packageNames")?:JSONArray(); return NotificationRule(id,json.optString("name","규칙"),buildSet{for(i in 0 until p.length())add(p.getString(i))},json.optString("includeRegex",""),json.optString("excludeRegex",""),json.optBoolean("enabled",true)) } }
}

data class PinTemplate(val id:String=UUID.randomUUID().toString(),val name:String,val text:String,val enabled:Boolean=true) {
    fun toJson()=JSONObject().apply{put("name",name);put("text",text);put("enabled",enabled)}
    companion object { fun fromJson(id:String,json:JSONObject)=PinTemplate(id,json.optString("name","압정"),json.optString("text",""),json.optBoolean("enabled",true)) }
}

data class AttachmentRecord(val id:String,val eventId:String,val kind:String,val mimeType:String?,val encryptedPath:String,val createdAt:Long,val externalUri:String?=null)
data class PlaceLabel(val displayName:String,val address:String?) { fun toJson()=JSONObject().apply{put("displayName",displayName);address?.let{put("address",it)}}; companion object { fun fromJson(json:JSONObject)=PlaceLabel(json.optString("displayName",""),json.optStringOrNull("address")) } }

fun dayKey(timestamp:Long,zoneId:ZoneId=ZoneId.systemDefault()):String=Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().format(DateTimeFormatter.ISO_DATE)
private fun JSONObject.optDoubleOrNull(key:String):Double?=if(!has(key)||isNull(key))null else optDouble(key).takeUnless{it.isNaN()}
private fun JSONObject.optStringOrNull(key:String):String?=if(!has(key)||isNull(key))null else optString(key).takeIf{it.isNotBlank()}
