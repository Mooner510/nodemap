package kr.mooner510.events

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlin.math.abs

class RecordingLinker(private val context:Context){
 fun findLikelyRecording(callStartMs:Long,durationSeconds:Long):Uri?{if(!hasAudioPermission())return null;val end=callStartMs+durationSeconds*1000L;val projection=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.DISPLAY_NAME,MediaStore.Audio.Media.DATE_ADDED,MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.RELATIVE_PATH);val candidates=mutableListOf<Pair<Uri,Long>>();runCatching{context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,projection,"${MediaStore.Audio.Media.DATE_ADDED} BETWEEN ? AND ?",arrayOf(((callStartMs-120000)/1000).toString(),((end+180000)/1000).toString()),"${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use{c->while(c.moveToNext()){val name=c.getString(1).orEmpty();val path=c.getString(4).orEmpty();if(!(path.contains("Recordings/Call",true)||path.contains("Call",true)||name.contains("통화",true)||name.contains("call",true)))continue;val score=abs(c.getLong(2)*1000-end)+abs(c.getLong(3)-durationSeconds*1000L)*2;candidates+=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,c.getLong(0)) to score}}};return candidates.minByOrNull{it.second}?.first}
 fun mimeType(uri:Uri)=context.contentResolver.getType(uri)
 private fun hasAudioPermission():Boolean{val p=if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE;return ContextCompat.checkSelfPermission(context,p)==PackageManager.PERMISSION_GRANTED}
}
