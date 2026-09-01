package kr.mooner510.events

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactResolver{fun displayName(context:Context,address:String?):String?{if(address.isNullOrBlank()||ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)return null;return runCatching{val uri=Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,Uri.encode(address));context.contentResolver.query(uri,arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0) else null}}.getOrNull()}}
