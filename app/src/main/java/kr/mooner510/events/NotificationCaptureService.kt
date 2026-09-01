package kr.mooner510.events

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kr.mooner510.appGraph
import kr.mooner510.data.EventType
import kr.mooner510.data.TimelineEvent
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class NotificationCaptureService:NotificationListenerService(){private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);override fun onNotificationPosted(sbn:StatusBarNotification){scope.launch{capture(sbn)}};override fun onDestroy(){scope.cancel();super.onDestroy()}
 private suspend fun capture(sbn:StatusBarNotification){val n=sbn.notification?:return;if(sbn.packageName==packageName)return;val e=n.extras?:Bundle.EMPTY;val title=e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty();val text=e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty();val big=e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty();val sub=e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty();val summary=e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty();val searchable=listOf(title,text,big,sub,summary).filter{it.isNotBlank()}.joinToString("\n");val rules=appGraph.repository.matchingNotificationRules(sbn.packageName,searchable);if(rules.isEmpty())return;val time=sbn.postTime.takeIf{it>0}?:System.currentTimeMillis();val point=appGraph.repository.nearestTrackPoint(time);val label=runCatching{packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName,0)).toString()}.getOrDefault(sbn.packageName);val event=TimelineEvent(timestamp=time,type=EventType.NOTIFICATION,latitude=point?.latitude,longitude=point?.longitude,title=title.ifBlank{label},body=big.ifBlank{text}.takeIf{it.isNotBlank()},source=sbn.packageName,metadata=mapOf("appLabel" to label,"notificationKey" to sbn.key,"notificationId" to sbn.id.toString(),"ruleIds" to rules.joinToString(","){it.id},"subText" to sub,"summaryText" to summary).filterValues{it.isNotBlank()});appGraph.repository.insertEvent(event);val candidates=buildList<Pair<String,Any>>{n.smallIcon?.let{add("small_icon" to it)};extractIcon(e,Notification.EXTRA_LARGE_ICON)?.let{add("large_icon" to it)};extractBitmap(e,Notification.EXTRA_PICTURE)?.let{add("picture" to it)}};candidates.forEach{(kind,visual)->runCatching{val bitmap=when(visual){is Bitmap->visual;is Icon->visual.loadDrawable(this@NotificationCaptureService)?.toBitmap();else->null}?:return@runCatching;val bytes=ByteArrayOutputStream().use{out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()};appGraph.repository.addEncryptedAttachment(event.id,kind,"image/png",ByteArrayInputStream(bytes))}}}
 @Suppress("DEPRECATION") private fun extractIcon(b:Bundle,k:String):Icon?=when(val v=b.getParcelable<android.os.Parcelable>(k)){is Icon->v;is Bitmap->Icon.createWithBitmap(v);else->null};@Suppress("DEPRECATION") private fun extractBitmap(b:Bundle,k:String):Bitmap?=when(val v=b.getParcelable<android.os.Parcelable>(k)){is Bitmap->v;is Icon->v.loadDrawable(this)?.toBitmap();else->null};private fun Drawable.toBitmap():Bitmap{if(this is BitmapDrawable&&bitmap!=null)return bitmap;val w=intrinsicWidth.takeIf{it>0}?:96;val h=intrinsicHeight.takeIf{it>0}?:96;return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{b->val c=Canvas(b);setBounds(0,0,c.width,c.height);draw(c)}}}
