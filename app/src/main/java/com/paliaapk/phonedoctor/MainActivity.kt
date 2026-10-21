package com.paliaapk.phonedoctor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val Mint = Color(0xFF07B879)
private val DeepGreen = Color(0xFF064E3B)
private val PaleMint = Color(0xFFE8FFF5)
private val Page = Color(0xFFF6FFFB)
private val Secondary = Color(0xFF607D78)
private val Border = Color(0xFFE0F2EB)

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { PhoneDoctorApp(this) }
 }
}

private data class DeviceSnapshot(
 val manufacturer: String, val model: String, val android: String, val sdk: Int,
 val ramTotal: Long, val ramAvailable: Long, val storageTotal: Long, val storageFree: Long,
 val batteryPercent: Int?, val batteryTempC: Float?, val batteryHealth: String, val charging: Boolean,
 val network: String
) {
 val displayName get() = listOf(manufacturer, model).filter { it.isNotBlank() }.distinct().joinToString(" ")
 val storageUsedPercent get() = if (storageTotal > 0) ((storageTotal-storageFree)*100/storageTotal).toInt() else 0
 val ramUsedPercent get() = if (ramTotal > 0) ((ramTotal-ramAvailable)*100/ramTotal).toInt() else 0
}

private fun readDevice(context: Context): DeviceSnapshot {
 val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
 val mem = ActivityManager.MemoryInfo()
 am.getMemoryInfo(mem)
 val stat = StatFs(Environment.getDataDirectory().absolutePath)
 val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
 val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
 val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
 val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.div(10f)
 val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
 val health = when (healthCode) {
  BatteryManager.BATTERY_HEALTH_GOOD -> "अच्छी"
  BatteryManager.BATTERY_HEALTH_OVERHEAT -> "बहुत गर्म"
  BatteryManager.BATTERY_HEALTH_DEAD -> "बैटरी खराब"
  BatteryManager.BATTERY_HEALTH_COLD -> "बहुत ठंडी"
  BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "वोल्टेज चेतावनी"
  BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "सिस्टम ने खराबी बताई"
  else -> "उपलब्ध नहीं"
 }
 val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
 val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
 val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
 val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
 val network = when {
  caps == null -> "कनेक्शन नहीं"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "मोबाइल डेटा"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
  else -> "कनेक्टेड"
 }
 return DeviceSnapshot(
  Build.MANUFACTURER.orEmpty().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
  Build.MODEL.orEmpty(), Build.VERSION.RELEASE ?: "अज्ञात", Build.VERSION.SDK_INT,
  mem.totalMem/(1024L*1024L), mem.availMem/(1024L*1024L),
  stat.totalBytes/(1024L*1024L), stat.availableBytes/(1024L*1024L),
  battery, temp, health, charging, network
 )
}

private data class Finding(val title: String, val detail: String, val severity: String, val fix: String, val action: String)
private fun findings(s: DeviceSnapshot): List<Finding> = buildList {
 if (s.storageUsedPercent >= 90) add(Finding("स्टोरेज लगभग भर गया है", "${s.storageUsedPercent}% स्टोरेज उपयोग में है।", "ध्यान दें", "Storage Settings में बड़ी या अनचाही फाइलें जाँचें।", "storage"))
 if (s.ramUsedPercent >= 90) add(Finding("RAM उपयोग अधिक है", "${s.ramUsedPercent}% RAM उपयोग में दिखाई दे रही है।", "ध्यान दें", "Memory आँकड़ा बदलता रहता है; हाल के ऐप्स बंद करें और फोन की जरूरत हो तो restart करें।", "memory"))
 if (s.batteryPercent != null && s.batteryPercent <= 15) add(Finding("बैटरी कम है", "बैटरी ${s.batteryPercent}% है।", "कम बैटरी", "चार्जर लगाएँ या Battery Saver चालू करें।", "battery"))
 if (s.batteryTempC != null && s.batteryTempC >= 45f) add(Finding("बैटरी तापमान अधिक", "बैटरी तापमान ${String.format(Locale.getDefault(), "%.1f", s.batteryTempC)}°C है।", "गर्मी", "चार्जिंग रोकें, गेम/भारी ऐप बंद करें और फोन को ठंडी जगह रखें। फ्रिज में न रखें।", "battery"))
 if (s.batteryHealth == "बहुत गर्म" || s.batteryHealth == "बैटरी खराब" || s.batteryHealth == "वोल्टेज चेतावनी" || s.batteryHealth == "सिस्टम ने खराबी बताई") add(Finding("बैटरी हेल्थ चेतावनी", "Android battery status: ${s.batteryHealth}", "जाँच जरूरी", "फोन का आधिकारिक service center जाँचें।", "battery"))
 if (isEmpty()) add(Finding("कोई गंभीर संसाधन चेतावनी नहीं", "मौजूदा स्कैन में निर्धारित RAM, storage या battery threshold पार नहीं हुआ। यह पूरे फोन का hardware या malware प्रमाणपत्र नहीं है।", "सामान्य", "समय-समय पर फिर स्कैन करें और ऐप/सिस्टम अपडेट रखें।", ""))
 add(Finding("ऐप अनुमतियाँ जाँचें", "Android ऐप्स की अनुमति सूची को OS और संस्करण सीमित करते हैं; इस ऐप ने सभी ऐप्स की permissions का दावा नहीं किया है।", "मैनुअल जाँच", "Permission Manager में camera, microphone, location और contacts की अनुमतियाँ देखें।", "permissions"))
 add(Finding("सिस्टम अपडेट जाँचें", "यह ऐप update server से उपलब्ध अपडेट का दावा नहीं करता।", "मैनुअल जाँच", "Settings में System update खोलकर उपलब्ध अपडेट देखें।", "update"))
}

private fun openSystemPage(context: Context, page: String) {
 val intent = when(page) {
  "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
  "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
  "permissions" -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
  "update" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
  "memory" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
  "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
  "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
  else -> Intent(Settings.ACTION_SETTINGS)
 }
 try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
 catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun PhoneDoctorApp(context: Context) {
 var showSplash by remember { mutableStateOf(true) }
 LaunchedEffect(Unit) { delay(1500); showSplash = false }
 if (showSplash) { BrandingSplash(); return }
 var selected by remember { mutableIntStateOf(0) }
 var refreshKey by remember { mutableIntStateOf(0) }
 var lastScan by remember { mutableStateOf("अभी स्कैन नहीं हुआ") }
 val snapshot = remember(refreshKey) { readDevice(context) }
 val issues = remember(snapshot) { findings(snapshot) }
 MaterialTheme(colorScheme = lightColorScheme(primary=Mint, onPrimary=Color.White, background=Page, surface=Color.White, onSurface=DeepGreen, secondary=Secondary)) {
  Scaffold(containerColor=Page, bottomBar={
   NavigationBar(containerColor=Color.White, contentColor=DeepGreen) {
    val labels=listOf("होम","स्कैन","टूल्स","रिपोर्ट","सेटिंग्स")
    val icons=listOf(Icons.Default.PhoneAndroid,Icons.Default.HealthAndSafety,Icons.Default.Build,Icons.Default.Assessment,Icons.Default.Settings)
    labels.forEachIndexed { i,label -> NavigationBarItem(selected=selected==i,onClick={
     selected=i
     if(i==1){ refreshKey++; lastScan="स्कैन पूरा हुआ • अभी अपडेट किया" }
    },icon={Icon(icons[i],label)},label={Text(label,fontSize=11.sp)}) }
   }
  }) { padding ->
   LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Header(onRefresh={refreshKey++;lastScan="स्कैन पूरा हुआ"}) }
    when(selected) {
     0 -> {
      item { DeviceHero(snapshot,onScan={refreshKey++;lastScan="स्कैन पूरा हुआ"}) }
      item { DeviceMetrics(snapshot) }
      item { Row(verticalAlignment=Alignment.CenterVertically){Text("फोन की स्थिति",fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen,modifier=Modifier.weight(1f));Text(lastScan,fontSize=11.sp,color=Secondary)} }
      items(issues.take(4)) { IssueCard(it,context) }
      item { ActionCard("पूरी जाँच देखें","RAM, स्टोरेज, बैटरी और मैनुअल सुरक्षा जाँच",Icons.Default.HealthAndSafety){selected=1;refreshKey++;lastScan="स्कैन पूरा हुआ"} }
     }
     1 -> {
      item { PageHeading("फोन स्कैन","वर्तमान सिस्टम आँकड़े दोबारा पढ़ें") }
      item {
       Card(colors=CardDefaults.cardColors(containerColor=PaleMint),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Border)) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
         Icon(Icons.Default.CheckCircle,null,tint=Mint,modifier=Modifier.size(28.dp))
         Spacer(Modifier.width(10.dp))
         Column(Modifier.weight(1f)) {
          Text(lastScan, fontWeight=FontWeight.Bold, color=DeepGreen, fontSize=14.sp)
          Text("डिवाइस के मौजूदा आँकड़े पढ़कर परिणाम नीचे दिखाए गए हैं।", color=Secondary, fontSize=12.sp)
         }
         Button(onClick={refreshKey++;lastScan="स्कैन पूरा हुआ • अभी अपडेट किया"},colors=ButtonDefaults.buttonColors(containerColor=Mint)) {
          Icon(Icons.Default.Refresh,null); Spacer(Modifier.width(4.dp)); Text("फिर स्कैन")
         }
        }
       }
      }
      item { DeviceHero(snapshot,onScan={selected=1;refreshKey++;lastScan="स्कैन पूरा हुआ • अभी अपडेट किया"}) }
      item { DeviceMetrics(snapshot) }
      item { Text("स्कैन परिणाम",fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen) }
      items(issues) { IssueCard(it,context) }
      item { Text("सीमा: सामान्य Android ऐप बैटरी, RAM और स्टोरेज जैसे कुछ आँकड़े पढ़ सकता है; सभी हार्डवेयर, malware या दूसरे ऐप्स की निजी जानकारी की पूरी जाँच की गारंटी नहीं दे सकता।",fontSize=12.sp,color=Secondary) }
     }
     2 -> {
      item { PageHeading("सिस्टम टूल्स","सुरक्षित Android Settings shortcuts") }
      item { ActionCard("Storage Settings","स्टोरेज स्पेस और उपयोग देखें",Icons.Default.Storage){openSystemPage(context,"storage")} }
      item { ActionCard("Battery Settings","Battery Saver और बैटरी विकल्प",Icons.Default.BatteryFull){openSystemPage(context,"battery")} }
      item { ActionCard("App permissions","Privacy और Permission Manager",Icons.Default.Security){openSystemPage(context,"permissions")} }
      item { ActionCard("Installed apps","ऐप जानकारी, force stop और uninstall विकल्प",Icons.Default.Apps){openSystemPage(context,"apps")} }
      item { ActionCard("Wi-Fi Settings","Wi-Fi और नेटवर्क सेटिंग्स",Icons.Default.Wifi){openSystemPage(context,"wifi")} }
      item { ActionCard("System update","डिवाइस जानकारी / सिस्टम अपडेट तक जाएँ",Icons.Default.SystemUpdate){openSystemPage(context,"update")} }
     }
     3 -> {
      item { PageHeading("स्कैन रिपोर्ट","इस समय डिवाइस से पढ़ी गई जानकारी") }
      item { DeviceMetrics(snapshot) }
      item { ReportRow("डिवाइस",snapshot.displayName) }
      item { ReportRow("Android","Version ${snapshot.android} • API ${snapshot.sdk}") }
      item { ReportRow("RAM", "${formatMb(snapshot.ramTotal-snapshot.ramAvailable)} उपयोग / ${formatMb(snapshot.ramTotal)} कुल (${snapshot.ramUsedPercent}%)") }
      item { ReportRow("स्टोरेज","${formatMb(snapshot.storageTotal-snapshot.storageFree)} उपयोग / ${formatMb(snapshot.storageTotal)} कुल (${snapshot.storageUsedPercent}%)") }
      item { ReportRow("बैटरी"," ${snapshot.batteryPercent?.let{"$it%"} ?: "उपलब्ध नहीं"} • ${snapshot.batteryHealth}${snapshot.batteryTempC?.let{" • $it°C"} ?: ""}") }
      item { ReportRow("चार्जिंग",if(snapshot.charging)"हाँ" else "नहीं / स्थिति उपलब्ध नहीं") }
      item { ReportRow("नेटवर्क",snapshot.network) }
      item { Button(onClick={refreshKey++;lastScan="स्कैन पूरा हुआ"},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text("रिपोर्ट रीफ़्रेश करें")} }
     }
     else -> {
      item { PageHeading("सेटिंग्स","Phone Doctor और सिस्टम विकल्प") }
      item { ActionCard("Android Settings","मुख्य सिस्टम सेटिंग्स खोलें",Icons.Default.Settings){openSystemPage(context,"settings")} }
      item { ActionCard("App permissions","Privacy और Permission Manager खोलें",Icons.Default.Security){openSystemPage(context,"permissions")} }
      item { ActionCard("App info","Phone Doctor की Android app info",Icons.Default.Info){try{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:"+context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){openSystemPage(context,"settings")}} }
      item { ReportRow("ऐप संस्करण","1.0.0") }
      item { ReportRow("Package","com.paliaapk.phonedoctor") }
      item { ReportRow("ब्रांड","By PaliaAPK HUB") }
      item { ReportRow("डेवलपर","Developer By shanpalia") }
     }
    }
    item { Spacer(Modifier.height(8.dp)) }
   }
  }
 }
}

@Composable private fun Header(onRefresh:()->Unit) {
 Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
  Box(Modifier.size(52.dp).background(PaleMint,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center){Icon(Icons.Default.HealthAndSafety,null,tint=Mint,modifier=Modifier.size(36.dp))}
  Spacer(Modifier.width(12.dp))
  Column(Modifier.weight(1f)) {
   Text("Phone Doctor",fontSize=23.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen)
   Text("By PaliaAPK HUB",fontSize=13.sp,color=Mint,fontWeight=FontWeight.Bold)
   Text("Developer By shanpalia",fontSize=11.sp,color=Secondary)
  }
  IconButton(onClick=onRefresh){Icon(Icons.Default.Refresh,"रिफ्रेश",tint=Mint)}
 }
}
@Composable private fun BrandingSplash() {
 Surface(Modifier.fillMaxSize(),color=Page) {
  Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
   Box(Modifier.size(116.dp).background(PaleMint,RoundedCornerShape(32.dp)),contentAlignment=Alignment.Center){Icon(Icons.Default.HealthAndSafety,null,tint=Mint,modifier=Modifier.size(82.dp))}
   Spacer(Modifier.height(26.dp));Text("Phone Doctor",fontSize=32.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen)
   Spacer(Modifier.height(6.dp));Text("By PaliaAPK HUB",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Mint)
   Spacer(Modifier.height(8.dp));Text("आपके फोन की सेहत, अब आपकी हथेली में",fontSize=14.sp,color=Secondary)
   Spacer(Modifier.height(42.dp));LinearProgressIndicator(Modifier.fillMaxWidth(.58f),color=Mint,trackColor=Border)
   Spacer(Modifier.height(42.dp));Text("Developer By shanpalia",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=DeepGreen)
  }
 }
}
@Composable private fun PageHeading(title:String,subtitle:String){Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen);Text(subtitle,fontSize=13.sp,color=Secondary)}}
@Composable private fun DeviceHero(s:DeviceSnapshot,onScan:()->Unit) {
 Card(colors=CardDefaults.cardColors(containerColor=DeepGreen),shape=RoundedCornerShape(24.dp)) {
  Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
   Text("आपका Android फोन",color=Color(0xFFD2F5E7),fontSize=13.sp)
   Row(verticalAlignment=Alignment.CenterVertically) {
    Icon(Icons.Default.PhoneAndroid,null,tint=Color(0xFF62E6B2),modifier=Modifier.size(38.dp));Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)){Text(s.displayName.ifBlank{"Android डिवाइस"},color=Color.White,fontWeight=FontWeight.Bold,fontSize=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text("Android ${s.android} • API ${s.sdk}",color=Color(0xFFD2F5E7),fontSize=12.sp)}
   }
   Row(verticalAlignment=Alignment.CenterVertically) {
    Text(if(s.batteryPercent==null)"बैटरी डेटा उपलब्ध नहीं" else "बैटरी ${s.batteryPercent}%"+if(s.charging)" • चार्ज हो रहा है" else "",color=Color.White,fontSize=13.sp,modifier=Modifier.weight(1f))
    Button(onClick=onScan,colors=ButtonDefaults.buttonColors(containerColor=Mint)){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(5.dp));Text("स्कैन")}
   }
  }
 }
}
@Composable private fun DeviceMetrics(s:DeviceSnapshot) {
 Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Text("डिवाइस जानकारी",fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen)
  Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){MetricCard("RAM","${formatMb(s.ramTotal-s.ramAvailable)} / ${formatMb(s.ramTotal)}",Icons.Default.Memory,Modifier.weight(1f),"${s.ramUsedPercent}% अनुमानित उपयोग");MetricCard("स्टोरेज","${formatMb(s.storageTotal-s.storageFree)} / ${formatMb(s.storageTotal)}",Icons.Default.Storage,Modifier.weight(1f),"${s.storageUsedPercent}% उपयोग")}
  Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){MetricCard("बैटरी",s.batteryPercent?.let{"$it%"}?:"N/A",Icons.Default.BatteryFull,Modifier.weight(1f),s.batteryHealth);MetricCard("नेटवर्क",s.network,Icons.Default.Wifi,Modifier.weight(1f),"वर्तमान कनेक्शन")}
  if(s.batteryTempC!=null) Text("बैटरी तापमान: ${String.format(Locale.getDefault(),"%.1f",s.batteryTempC)}°C",fontSize=12.sp,color=Secondary)
 }
}
@Composable private fun MetricCard(title:String,value:String,icon:ImageVector,modifier:Modifier,caption:String) {
 Card(modifier,shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)) {
  Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Icon(icon,null,tint=Mint,modifier=Modifier.size(25.dp));Text(title,color=Secondary,fontSize=12.sp);Text(value,color=DeepGreen,fontWeight=FontWeight.Bold,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text(caption,color=Secondary,fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis)}
 }
}
@Composable private fun IssueCard(f:Finding,context:Context) {
 Card(shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)) {
  Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
   Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(38.dp).background(PaleMint,CircleShape),contentAlignment=Alignment.Center){Icon(if(f.severity=="ध्यान दें"||f.severity=="गर्मी")Icons.Default.Warning else Icons.Default.HealthAndSafety,null,tint=Mint)};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(f.title,fontWeight=FontWeight.Bold,color=DeepGreen,fontSize=14.sp);Text(f.severity,color=Mint,fontSize=11.sp)}}
   Text(f.detail,color=Secondary,fontSize=12.sp)
   Text("सुझाव: ${f.fix}",color=DeepGreen,fontSize=12.sp)
   if(f.action.isNotBlank()) OutlinedButton(onClick={openSystemPage(context,f.action)},modifier=Modifier.fillMaxWidth(),border=BorderStroke(1.dp,Border)){Text("संबंधित Android Settings खोलें",color=Mint)}
  }
 }
}
@Composable private fun ActionCard(title:String,detail:String,icon:ImageVector,onClick:()->Unit) {
 Card(onClick=onClick,shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)) {
  Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(42.dp).background(PaleMint,RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center){Icon(icon,null,tint=Mint)};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Bold,color=DeepGreen);Text(detail,fontSize=12.sp,color=Secondary)};Icon(Icons.Default.ChevronRight,null,tint=Mint)}
 }
}
@Composable private fun ReportRow(label:String,value:String) {
 Card(shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Text(label,color=Secondary,fontSize=13.sp,modifier=Modifier.weight(.38f));Text(value,color=DeepGreen,fontSize=13.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(.62f))}}
}
private fun formatMb(mb:Long):String=if(mb>=1024)String.format(Locale.getDefault(),"%.1f GB",mb/1024.0) else "$mb MB"
