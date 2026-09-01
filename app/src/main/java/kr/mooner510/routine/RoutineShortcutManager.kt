package kr.mooner510.routine

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import kr.mooner510.data.NodeMapRepository

class RoutineShortcutManager(private val context:Context,private val repository:NodeMapRepository){suspend fun refresh(){val m=context.getSystemService(ShortcutManager::class.java);val shortcuts=repository.pinTemplates().filter{it.enabled}.take(m.maxShortcutCountPerActivity.coerceAtLeast(1)).map{t->ShortcutInfo.Builder(context,"pin_${t.id}").setShortLabel(t.name.take(20)).setLongLabel("NodeMap 압정: ${t.name}").setIcon(Icon.createWithResource(context,android.R.drawable.ic_menu_mylocation)).setIntent(Intent(context,RoutineActionActivity::class.java).apply{action=ACTION_PIN_TEMPLATE;putExtra(EXTRA_TEMPLATE_ID,t.id)}).setLongLived(true).build()};m.dynamicShortcuts=shortcuts} companion object{const val ACTION_PIN_TEMPLATE="kr.mooner510.action.PIN_TEMPLATE";const val EXTRA_TEMPLATE_ID="template_id"}}
