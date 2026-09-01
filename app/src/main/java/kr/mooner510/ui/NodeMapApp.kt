package kr.mooner510.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable fun NodeMapApp(){MaterialTheme{var tab by remember{mutableIntStateOf(0)};Scaffold(modifier=Modifier.fillMaxSize(),bottomBar={NavigationBar{NavigationBarItem(tab==0,{tab=0},{Text("⌖")},{Text("타임랩스")});NavigationBarItem(tab==1,{tab=1},{Text("⌁")},{Text("압정")});NavigationBarItem(tab==2,{tab=2},{Text("⚙")},{Text("설정")})}}){padding->Box(Modifier.padding(padding).fillMaxSize()){when(tab){0->TimelineScreen();1->PinsAndRulesScreen();else->SettingsScreen()}}}}}
