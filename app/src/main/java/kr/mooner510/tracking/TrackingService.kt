package kr.mooner510.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kr.mooner510.MainActivity
import kr.mooner510.R
import kr.mooner510.appGraph
import kr.mooner510.data.TrackPoint
import kr.mooner510.data.TrackingPreset
import kr.mooner510.events.CallLogObserver
import kr.mooner510.events.MessageObserver
import kotlinx.coroutines.*
import java.util.concurrent.Executor
import kotlin.math.max

class TrackingService:Service(),LocationListener{
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val graph get()=appGraph;private lateinit var lm:LocationManager;private lateinit var messages:MessageObserver;private lateinit var calls:CallLogObserver;private var current:RequestProfile?=null;private var stationary=false;private var last:Location?=null;private var lastMove=0L;private var settingsJob:Job?=null;private val executor=Executor{c->scope.launch{c.run()}}
 override fun onCreate(){super.onCreate();createChannel();lm=getSystemService(LocationManager::class.java);messages=MessageObserver(this,graph.repository,scope);calls=CallLogObserver(this,graph.repository,scope);messages.start();calls.start()}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{ServiceCompat.startForeground(this,510,notification("위치 권한을 확인하는 중"),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);settingsJob?.cancel();settingsJob=scope.launch{graph.preferences.settings.collect{s->if(!s.trackingEnabled){stopSelf();return@collect};profileFor(s.trackingPreset,stationary).takeIf{it!=current}?.let{requestUpdates(it)}}};return START_STICKY}
 override fun onLocationChanged(location:Location){val now=System.currentTimeMillis();updateMovement(location,now);last?.let{if(location.time-it.time<1500&&location.distanceTo(it)<1.5f&&location.accuracy>=it.accuracy)return};last=location;scope.launch{graph.repository.insertTrackPoint(TrackPoint(timestamp=location.time.takeIf{it>0}?:now,latitude=location.latitude,longitude=location.longitude,accuracyMeters=location.accuracy,altitudeMeters=location.altitude.takeIf{location.hasAltitude()},speedMps=location.speed.takeIf{location.hasSpeed()},bearingDegrees=location.bearing.takeIf{location.hasBearing()},provider=location.provider,isMock=location.isMock));updateNotification("정확도 ±${max(1,location.accuracy.toInt())}m · 방금 기록")}}
 override fun onProviderDisabled(provider:String){updateNotification("위치 공급자 꺼짐: $provider")}
 override fun onDestroy(){runCatching{lm.removeUpdates(this)};messages.stop();calls.stop();scope.cancel();super.onDestroy()};override fun onBind(intent:Intent?):IBinder?=null
 private suspend fun requestUpdates(p:RequestProfile){if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){updateNotification("위치 권한 필요");return};runCatching{lm.removeUpdates(this)};val req=LocationRequest.Builder(p.interval).setMinUpdateIntervalMillis(p.fastest).setMinUpdateDistanceMeters(p.distance).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build();val provider=when{lm.allProviders.contains(LocationManager.FUSED_PROVIDER)->LocationManager.FUSED_PROVIDER;lm.isProviderEnabled(LocationManager.GPS_PROVIDER)->LocationManager.GPS_PROVIDER;else->LocationManager.NETWORK_PROVIDER};runCatching{lm.requestLocationUpdates(provider,req,executor,this);if(provider==LocationManager.GPS_PROVIDER&&lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,req,executor,this);current=p}.onFailure{updateNotification("위치 기록 시작 실패: ${it.javaClass.simpleName}")}}
 private fun updateMovement(location:Location,now:Long){val moved=(location.hasSpeed()&&location.speed>=.8f)||(last!=null&&location.distanceTo(last!!)>=12f);if(moved)lastMove=now;val next=lastMove>0&&now-lastMove>=180000;if(next!=stationary){stationary=next;scope.launch{requestUpdates(profileFor(graph.preferences.current().trackingPreset,stationary))}}}
 private fun profileFor(p:TrackingPreset,s:Boolean)=when(p){TrackingPreset.PRECISE->if(s)RequestProfile(20000,5000,5f)else RequestProfile(3000,1000,2f);TrackingPreset.BALANCED->if(s)RequestProfile(45000,10000,15f)else RequestProfile(8000,2000,5f);TrackingPreset.BATTERY->if(s)RequestProfile(180000,60000,50f)else RequestProfile(30000,10000,15f)}
 private fun createChannel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("nodemap_tracking",getString(R.string.tracking_notification_channel),NotificationManager.IMPORTANCE_LOW).apply{setShowBadge(false)})};private fun updateNotification(t:String){getSystemService(NotificationManager::class.java).notify(510,notification(t))};private fun notification(t:String):Notification{val pi=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);return NotificationCompat.Builder(this,"nodemap_tracking").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(getString(R.string.tracking_notification_title)).setContentText(t).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build()}
 private data class RequestProfile(val interval:Long,val fastest:Long,val distance:Float)
 companion object{fun start(context:android.content.Context){androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,TrackingService::class.java))}}
}
