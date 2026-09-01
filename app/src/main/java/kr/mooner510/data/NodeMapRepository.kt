package kr.mooner510.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

class NodeMapRepository(private val db:NodeMapDb,private val crypto:CryptoManager,val attachmentStore:AttachmentStore){
    private val _changes=MutableSharedFlow<Unit>(extraBufferCapacity=64); val changes=_changes.asSharedFlow()
    suspend fun insertTrackPoint(point:TrackPoint):Long=withContext(Dispatchers.IO){val v=ContentValues().apply{put("timestamp",point.timestamp);put("day_key",dayKey(point.timestamp));put("payload",crypto.encrypt(point.toJson().toString().toByteArray()))};db.writableDatabase.insertOrThrow("track_points",null,v).also{_changes.tryEmit(Unit)}}
    suspend fun trackPointsForDay(date:LocalDate):List<TrackPoint>=withContext(Dispatchers.IO){db.readableDatabase.query("track_points",arrayOf("id","timestamp","payload"),"day_key = ?",arrayOf(date.toString()),null,null,"timestamp ASC").let{c->db.run{c.mapRows{decodeTrackPoint(it.getLong(0),it.getLong(1),it.getBlob(2))}}}}
    suspend fun allTrackPoints():List<TrackPoint>=withContext(Dispatchers.IO){db.readableDatabase.query("track_points",arrayOf("id","timestamp","payload"),null,null,null,null,"timestamp ASC").let{c->db.run{c.mapRows{decodeTrackPoint(it.getLong(0),it.getLong(1),it.getBlob(2))}}}}
    suspend fun latestTrackPoint():TrackPoint?=withContext(Dispatchers.IO){db.readableDatabase.query("track_points",arrayOf("id","timestamp","payload"),null,null,null,null,"timestamp DESC","1").use{if(!it.moveToFirst())null else decodeTrackPoint(it.getLong(0),it.getLong(1),it.getBlob(2))}}
    suspend fun nearestTrackPoint(timestamp:Long,maxDeltaMs:Long=5*60_000L):TrackPoint?=withContext(Dispatchers.IO){db.readableDatabase.rawQuery("SELECT id,timestamp,payload FROM track_points WHERE timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp - ?) ASC LIMIT 1",arrayOf((timestamp-maxDeltaMs).toString(),(timestamp+maxDeltaMs).toString(),timestamp.toString())).use{if(!it.moveToFirst())null else decodeTrackPoint(it.getLong(0),it.getLong(1),it.getBlob(2))}}
    suspend fun insertEvent(event:TimelineEvent)=withContext(Dispatchers.IO){val v=ContentValues().apply{put("id",event.id);put("timestamp",event.timestamp);put("day_key",dayKey(event.timestamp));put("type",event.type.name);put("payload",crypto.encrypt(event.toJson().toString().toByteArray()))};db.writableDatabase.insertWithOnConflict("events",null,v,SQLiteDatabase.CONFLICT_REPLACE);_changes.tryEmit(Unit)}
    suspend fun updateEvent(event:TimelineEvent)=insertEvent(event)
    suspend fun eventsForDay(date:LocalDate):List<TimelineEvent>=withContext(Dispatchers.IO){queryEvents("day_key = ?",arrayOf(date.toString()))}
    suspend fun allEvents():List<TimelineEvent>=withContext(Dispatchers.IO){queryEvents(null,null)}
    suspend fun getEvent(id:String):TimelineEvent?=withContext(Dispatchers.IO){queryEvents("id = ?",arrayOf(id)).firstOrNull()}
    suspend fun addEncryptedAttachment(eventId:String,kind:String,mimeType:String?,content:java.io.InputStream,attachmentId:String=UUID.randomUUID().toString()):AttachmentRecord=withContext(Dispatchers.IO){val r=AttachmentRecord(attachmentId,eventId,kind,mimeType,attachmentStore.put(content,attachmentId),System.currentTimeMillis());insertAttachmentRecord(r);r}
    suspend fun addExternalAttachment(eventId:String,kind:String,mimeType:String?,uri:String,attachmentId:String=UUID.randomUUID().toString()):AttachmentRecord=withContext(Dispatchers.IO){val r=AttachmentRecord(attachmentId,eventId,kind,mimeType,"",System.currentTimeMillis(),uri);insertAttachmentRecord(r);r}
    suspend fun attachmentsForEvent(eventId:String):List<AttachmentRecord>=withContext(Dispatchers.IO){queryAttachments("event_id = ?",arrayOf(eventId))}
    suspend fun allAttachments():List<AttachmentRecord>=withContext(Dispatchers.IO){queryAttachments(null,null)}
    suspend fun insertAttachmentRecord(r:AttachmentRecord)=withContext(Dispatchers.IO){val v=ContentValues().apply{put("id",r.id);put("event_id",r.eventId);put("kind",r.kind);put("mime_type",r.mimeType);put("encrypted_path",r.encryptedPath);put("external_uri",r.externalUri);put("created_at",r.createdAt)};db.writableDatabase.insertWithOnConflict("attachments",null,v,SQLiteDatabase.CONFLICT_REPLACE);_changes.tryEmit(Unit)}
    suspend fun notificationRules():List<NotificationRule>=withContext(Dispatchers.IO){db.readableDatabase.query("notification_rules",arrayOf("id","payload"),null,null,null,null,"created_at ASC").let{c->db.run{c.mapRows{NotificationRule.fromJson(it.getString(0),decryptJson(it.getBlob(1)))}}}}
    suspend fun upsertNotificationRule(rule:NotificationRule)=withContext(Dispatchers.IO){val v=ContentValues().apply{put("id",rule.id);put("created_at",System.currentTimeMillis());put("payload",crypto.encrypt(rule.toJson().toString().toByteArray()))};db.writableDatabase.insertWithOnConflict("notification_rules",null,v,SQLiteDatabase.CONFLICT_REPLACE);_changes.tryEmit(Unit)}
    suspend fun deleteNotificationRule(id:String)=withContext(Dispatchers.IO){db.writableDatabase.delete("notification_rules","id = ?",arrayOf(id));_changes.tryEmit(Unit)}
    suspend fun matchingNotificationRules(packageName:String,searchableText:String)=notificationRules().filter{r->if(!r.enabled||packageName !in r.packageNames)return@filter false;val excluded=r.excludeRegex.takeIf{it.isNotBlank()}?.let{runCatching{Regex(it).containsMatchIn(searchableText)}.getOrDefault(false)}?:false;if(excluded)return@filter false;val include=r.includeRegex.takeIf{it.isNotBlank()}?:return@filter true;runCatching{Regex(include).containsMatchIn(searchableText)}.getOrDefault(false)}
    suspend fun pinTemplates():List<PinTemplate>=withContext(Dispatchers.IO){db.readableDatabase.query("pin_templates",arrayOf("id","payload"),null,null,null,null,"created_at ASC").let{c->db.run{c.mapRows{PinTemplate.fromJson(it.getString(0),decryptJson(it.getBlob(1)))}}}}
    suspend fun getPinTemplate(id:String)=pinTemplates().firstOrNull{it.id==id}
    suspend fun upsertPinTemplate(t:PinTemplate)=withContext(Dispatchers.IO){val v=ContentValues().apply{put("id",t.id);put("created_at",System.currentTimeMillis());put("payload",crypto.encrypt(t.toJson().toString().toByteArray()))};db.writableDatabase.insertWithOnConflict("pin_templates",null,v,SQLiteDatabase.CONFLICT_REPLACE);_changes.tryEmit(Unit)}
    suspend fun deletePinTemplate(id:String)=withContext(Dispatchers.IO){db.writableDatabase.delete("pin_templates","id = ?",arrayOf(id));_changes.tryEmit(Unit)}
    suspend fun getGeocodeCache(key:String):PlaceLabel?=withContext(Dispatchers.IO){db.readableDatabase.query("geocode_cache",arrayOf("payload"),"cache_key = ?",arrayOf(key),null,null,null,"1").use{if(!it.moveToFirst())null else PlaceLabel.fromJson(decryptJson(it.getBlob(0)))}}
    suspend fun putGeocodeCache(key:String,p:PlaceLabel)=withContext(Dispatchers.IO){val v=ContentValues().apply{put("cache_key",key);put("updated_at",System.currentTimeMillis());put("payload",crypto.encrypt(p.toJson().toString().toByteArray()))};db.writableDatabase.insertWithOnConflict("geocode_cache",null,v,SQLiteDatabase.CONFLICT_REPLACE)}
    suspend fun state(key:String):String?=withContext(Dispatchers.IO){db.readableDatabase.query("state",arrayOf("value"),"key = ?",arrayOf(key),null,null,null,"1").use{if(it.moveToFirst())it.getString(0) else null}}
    suspend fun setState(key:String,value:String)=withContext(Dispatchers.IO){val v=ContentValues().apply{put("key",key);put("value",value)};db.writableDatabase.insertWithOnConflict("state",null,v,SQLiteDatabase.CONFLICT_REPLACE)}
    suspend fun clearForRestore()=withContext(Dispatchers.IO){val w=db.writableDatabase;w.beginTransaction();try{listOf("attachments","events","track_points","notification_rules","pin_templates","geocode_cache","state").forEach{w.delete(it,null,null)};w.setTransactionSuccessful()}finally{w.endTransaction()};_changes.tryEmit(Unit)}
    private fun queryEvents(selection:String?,args:Array<String>?):List<TimelineEvent>{val c=db.readableDatabase.query("events",arrayOf("id","timestamp","type","payload"),selection,args,null,null,"timestamp ASC");return db.run{c.mapRows{r->val id=r.getString(0);TimelineEvent.fromJson(id,r.getLong(1),EventType.valueOf(r.getString(2)),decryptJson(r.getBlob(3))).copy(attachmentIds=queryAttachments("event_id = ?",arrayOf(id)).map{it.id})}}}
    private fun queryAttachments(selection:String?,args:Array<String>?):List<AttachmentRecord>{val c=db.readableDatabase.query("attachments",arrayOf("id","event_id","kind","mime_type","encrypted_path","created_at","external_uri"),selection,args,null,null,"created_at ASC");return db.run{c.mapRows{AttachmentRecord(it.getString(0),it.getString(1),it.getString(2),it.strOrNull(3),it.getString(4),it.getLong(5),it.strOrNull(6))}}}
    private fun decodeTrackPoint(id:Long,timestamp:Long,payload:ByteArray)=TrackPoint.fromJson(id,timestamp,decryptJson(payload))
    private fun decryptJson(payload:ByteArray)=JSONObject(crypto.decrypt(payload).toString(Charsets.UTF_8))
}
private fun android.database.Cursor.strOrNull(i:Int):String?=if(isNull(i))null else getString(i)
