package kr.mooner510.tracking

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kr.mooner510.appGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action!=Intent.ACTION_BOOT_COMPLETED&&intent.action!=Intent.ACTION_MY_PACKAGE_REPLACED)return;val pending=goAsync();CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{val s=context.appGraph.preferences.current();val loc=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val bg=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED;if(s.trackingEnabled&&s.autoStartAfterBoot&&loc&&bg)runCatching{TrackingService.start(context)}}finally{pending.finish()}}}}
