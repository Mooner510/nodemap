package kr.mooner510.events

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kr.mooner510.data.EventType
import kr.mooner510.data.NodeMapRepository
import kr.mooner510.data.TimelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallLogObserver(private val context:Context,private val repository:NodeMapRepository,private val scope:CoroutineScope){private val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){scope.launch{sync()}}};private val linker=RecordingLinker(context)
 fun start(){if(!hasPermission())return;context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI,true,observer);scope.launch{initializeBaselineIfNeeded()}};fun stop(){runCatching{context.contentResolver.unregisterContentObserver(observer)}}
 suspend fun sync(){if(!hasPermission())return;val state=repository.state("last_call_id")?:run{initializeBaselineIfNeeded();return};val last=state.toLongOrNull()?:0;val c=context.contentResolver.query(CallLog.Calls.CONTENT_URI,arrayOf(CallLog.Calls._ID,CallLog.Calls.NUMBER,CallLog.Calls.CACHED_NAME,CallLog.Calls.DATE,CallLog.Calls.DURATION,CallLog.Calls.TYPE),"${CallLog.Calls._ID} > ?",arrayOf(last.toString()),"${CallLog.Calls._ID} ASC")?:return;var newest=last;c.use{r->while(r.moveToNext()){val id=r.getLong(0);newest=maxOf(newest,id);val number=r.getString(1).orEmpty();val cached=r.getString(2).orEmpty();val ts=r.getLong(3);val duration=r.getLong(4);val type=r.getInt(5);val direction=callTypeLabel(type);val display=cached.ifBlank{ContactResolver.displayName(context,number).orEmpty()}.ifBlank{number}.ifBlank{"알 수 없음"};val p=repository.nearestTrackPoint(ts);val e=TimelineEvent(timestamp=ts,type=EventType.PHONE_CALL,latitude=p?.latitude,longitude=p?.longitude,title="$direction 통화 · $display",body=formatDuration(duration),source="android.provider.CallLog",metadata=mapOf("providerId" to id.toString(),"number" to number,"contactName" to cached,"direction" to direction,"callType" to type.toString(),"durationSeconds" to duration.toString()).filterValues{it.isNotBlank()});repository.insertEvent(e);scope.launch{attachRecording(e.id,ts,duration)}}};if(newest>last)repository.setState("last_call_id",newest.toString())}
 private suspend fun attachRecording(eventId:String,start:Long,duration:Long){for(wait in longArrayOf(3000,20000,60000)){delay(wait);val uri=linker.findLikelyRecording(start,duration)?:continue;if(repository.attachmentsForEvent(eventId).none{it.kind=="call_recording"&&it.externalUri==uri.toString()})repository.addExternalAttachment(eventId,"call_recording",linker.mimeType(uri),uri.toString());return}}
 private suspend fun initializeBaselineIfNeeded(){if(repository.state("last_call_id")!=null||!hasPermission())return;val max=context.contentResolver.query(CallLog.Calls.CONTENT_URI,arrayOf("MAX(${CallLog.Calls._ID})"),null,null,null)?.use{if(it.moveToFirst())it.getLong(0) else 0}?:0;repository.setState("last_call_id",max.toString())}
 private fun hasPermission()=ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED
 private fun callTypeLabel(t:Int)=when(t){CallLog.Calls.INCOMING_TYPE->"수신";CallLog.Calls.OUTGOING_TYPE->"발신";CallLog.Calls.MISSED_TYPE->"부재중";CallLog.Calls.REJECTED_TYPE->"거절";CallLog.Calls.BLOCKED_TYPE->"차단";else->"전화"};private fun formatDuration(s:Long)=if(s>=60)"${s/60}분 ${s%60}초" else "${s}초"}
