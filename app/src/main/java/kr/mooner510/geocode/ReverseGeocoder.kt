package kr.mooner510.geocode

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kr.mooner510.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

class ReverseGeocoder(private val context:Context,private val repository:NodeMapRepository,private val crypto:CryptoManager,private val preferences:PreferencesStore){suspend fun resolve(latitude:Double,longitude:Double):PlaceLabel?{if(!preferences.current().reverseGeocodingEnabled)return null;val key=cacheKey(latitude,longitude);repository.getGeocodeCache(key)?.let{return it};if(!Geocoder.isPresent())return null;val a=query(latitude,longitude)?:return null;val label=PlaceLabel(listOfNotNull(a.featureName,a.thoroughfare,a.subLocality,a.locality).firstOrNull{it.isNotBlank()}?:a.getAddressLine(0).orEmpty(),a.getAddressLine(0));repository.putGeocodeCache(key,label);return label}
 private suspend fun query(lat:Double,lon:Double):Address?=withContext(Dispatchers.IO){val g=Geocoder(context,Locale.KOREAN);suspendCancellableCoroutine{c->g.getFromLocation(lat,lon,1,object:Geocoder.GeocodeListener{override fun onGeocode(addresses:MutableList<Address>){if(c.isActive)c.resume(addresses.firstOrNull())};override fun onError(errorMessage:String?){if(c.isActive)c.resume(null)}})}}
 private fun cacheKey(lat:Double,lon:Double)=crypto.keyedHash("%.4f,%.4f".format(Locale.US,lat,lon))}
