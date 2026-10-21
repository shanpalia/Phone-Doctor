package com.paliaapk.phonedoctor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.hardware.SensorManager
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
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

private fun hardwareChecks(context: Context): List<Pair<String, String>> {
 val pm = context.packageManager
 val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
 val sensors = sensorManager.getSensorList(android.hardware.Sensor.TYPE_ALL)
 fun status(feature: String) = if (pm.hasSystemFeature(feature)) "उपलब्ध" else "डिवाइस में नहीं मिला"
 return listOf(
  "कैमरा हार्डवेयर" to status(PackageManager.FEATURE_CAMERA_ANY),
  "फ्लैश" to status(PackageManager.FEATURE_CAMERA_FLASH),
  "माइक्रोफोन" to status(PackageManager.FEATURE_MICROPHONE),
  "टच/मोशन सेंसर" to if (sensors.isNotEmpty()) "${sensors.size} सेंसर Android को रिपोर्ट हुए" else "कोई सेंसर रिपोर्ट नहीं हुआ",
  "फिंगरप्रिंट सेंसर" to status(PackageManager.FEATURE_FINGERPRINT),
  "GPS / लोकेशन हार्डवेयर" to status(PackageManager.FEATURE_LOCATION_GPS)
 )
}

private fun openSecuritySettings(context: Context) {
 try {
  context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
 } catch (_: Exception) {
  openSystemPage(context, "settings")
 }
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
private data class PermissionItem(val permission: String, val title: String, val reason: String)

@Composable
private fun PermissionSetupPage(item: PermissionItem?, onAllow: (String) -> Unit, onSkip: () -> Unit, onFinish: () -> Unit) {
 Surface(modifier=Modifier.fillMaxSize(), color=Page) {
  Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
   Image(painterResource(R.drawable.ic_phone_doctor),null,modifier=Modifier.size(92.dp))
   Spacer(Modifier.height(18.dp))
   Text("Phone Doctor Permissions",fontSize=24.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen)
   Spacer(Modifier.height(8.dp))
   Text("हर अनुमति अलग से समझकर Allow करें। अनुमति देना वैकल्पिक है; मना करने पर ऐप खुल सकता है, संबंधित जाँच उपलब्ध नहीं होगी।",fontSize=14.sp,color=Secondary)
   Spacer(Modifier.height(24.dp))
   if(item==null) {
    Text("अनुमति सेटअप पूरा हुआ",fontSize=18.sp,fontWeight=FontWeight.Bold,color=DeepGreen)
    Button(onClick=onFinish,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)){Text("Phone Doctor खोलें")}
   } else {
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Border)) {
     Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
      Text(item.title,fontSize=18.sp,fontWeight=FontWeight.Bold,color=DeepGreen)
      Text(item.reason,fontSize=14.sp,color=Secondary)
      Text("अनुमति ${item.title.lowercase()} जाँच सुविधा के लिए है। ऐप आपकी अनुमति के बिना इसे इस्तेमाल नहीं कर सकता।",fontSize=12.sp,color=Secondary)
     }
    }
    Spacer(Modifier.height(14.dp))
    Button(onClick={onAllow(item.permission)},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)){Text("अनुमति दें")}
    TextButton(onClick=onSkip,modifier=Modifier.fillMaxWidth()){Text("अभी छोड़ें")}
    Text("आप बाद में Android Settings से अनुमति बदल सकते हैं।",fontSize=12.sp,color=Secondary)
   }
  }
 }
}

private fun PhoneDoctorApp(context: Context) {
 var showSplash by remember { mutableStateOf(true) }
 LaunchedEffect(Unit) { delay(1500); showSplash = false }
 if (showSplash) { BrandingSplash(); return }
 val permissionItems = remember {
  buildList {
   add(PermissionItem(Manifest.permission.CAMERA,"कैमरा","कैमरा हार्डवेयर जाँच के लिए।"))
   add(PermissionItem(Manifest.permission.RECORD_AUDIO,"माइक्रोफोन","माइक्रोफोन की वैकल्पिक कार्यात्मक जाँच के लिए।"))
   add(PermissionItem(Manifest.permission.ACCESS_FINE_LOCATION,"लोकेशन / GPS","GPS की वैकल्पिक जाँच के लिए; स्थान ऐप के सर्वर पर नहीं भेजा जाता।"))
   if (Build.VERSION.SDK_INT >= 33) add(PermissionItem(Manifest.permission.POST_NOTIFICATIONS,"नोटिफिकेशन","स्कैन/ऐप अपडेट के नोटिफिकेशन दिखाने के लिए।"))
  }
 }
 var permissionIndex by remember { mutableIntStateOf(0) }
 var showPermissionSetup by remember { mutableStateOf(true) }
 val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
  permissionIndex = (permissionIndex + 1).coerceAtMost(permissionItems.size)
  if(permissionIndex >= permissionItems.size) showPermissionSetup = false
 }
 LaunchedEffect(permissionIndex, showPermissionSetup) {
  if(showPermissionSetup && permissionIndex < permissionItems.size) {
   val permission=permissionItems[permissionIndex].permission
   if(context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED) {
    permissionIndex++
    if(permissionIndex >= permissionItems.size) showPermissionSetup=false
   }
  }
 }
 if(showPermissionSetup) {
  PermissionSetupPage(
   item=permissionItems.getOrNull(permissionIndex),
   onAllow={ permissionLauncher.launch(it) },
   onSkip={
    permissionIndex++
    if(permissionIndex >= permissionItems.size) showPermissionSetup=false
   },
   onFinish={showPermissionSetup=false}
  )
  return
 }
 var selected by remember { mutableIntStateOf(0) }
 var refreshKey by remember { mutableIntStateOf(0) }
 var lastScan by remember { mutableStateOf("अभी स्कैन नहीं हुआ") }
 var isScanning by remember { mutableStateOf(false) }
 var scanProgress by remember { mutableIntStateOf(0) }
 var versionStatus by remember { mutableStateOf("अपडेट जाँचने के लिए बटन दबाएँ") }
 var latestVersion by remember { mutableStateOf<String?>(null) }
 var updateUrl by remember { mutableStateOf<String?>(null) }
 var versionChecking by remember { mutableStateOf(false) }
 val scope = rememberCoroutineScope()
 fun startDeviceScan() {
  if (isScanning) return
  isScanning = true
  scanProgress = 0
  lastScan = "डिवाइस की जाँच शुरू हो रही है…"
  scope.launch {
   val stages = listOf("बैटरी की जानकारी पढ़ रहे हैं…", "RAM की उपलब्धता पढ़ रहे हैं…", "स्टोरेज का उपयोग जाँच रहे हैं…", "नेटवर्क और Android जानकारी अपडेट कर रहे हैं…")
   stages.forEachIndexed { index, stage ->
    lastScan = stage
    delay(500)
    refreshKey++
    scanProgress = (index + 1) * 25
   }
   lastScan = "स्कैन पूरा • डिवाइस आँकड़े अपडेट हुए"
   isScanning = false
  }
 }
 fun checkWebsiteVersion() {
  if (versionChecking) return
  versionChecking = true
  versionStatus = "www.paliaapkhub.com से संस्करण जाँचा जा रहा है…"
  scope.launch {
   val result = withContext(Dispatchers.IO) {
    try {
     val connection = (URL("https://www.paliaapkhub.com/phone-doctor-version.json").openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 10000; readTimeout = 10000; setRequestProperty("Cache-Control", "no-cache") }
     try {
      if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP " + connection.responseCode)
      val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
      val versionName = json.optString("versionName", "")
      val versionCode = json.optInt("versionCode", -1)
      val download = json.optString("downloadUrl", "")
      if (versionName.isBlank() || versionCode < 1 || download.isBlank()) throw IllegalStateException("Version file में आवश्यक fields नहीं हैं")
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      val installedCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
      if (versionCode > installedCode) Triple("UPDATE", versionName, download) else Triple("CURRENT", versionName, download)
     } finally { connection.disconnect() }
    } catch (e: Exception) { Triple("ERROR", "", e.message ?: "कनेक्शन उपलब्ध नहीं") }
   }
   when (result.first) {
    "UPDATE" -> { latestVersion = result.second; updateUrl = result.third; versionStatus = "नया संस्करण " + result.second + " उपलब्ध है। Installed version " + (context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.1") + " है।" }
    "CURRENT" -> { latestVersion = result.second; updateUrl = null; versionStatus = "आपका ऐप Up to date है • नवीनतम " + result.second }
    else -> versionStatus = "वर्ज़न verify नहीं हो पाया। इंटरनेट और वेबसाइट की version file जाँचें। (" + result.third + ")"
   }
   versionChecking = false
  }
 }
 val snapshot = remember(refreshKey) { readDevice(context) }
 val issues = remember(snapshot) { findings(snapshot) }
 MaterialTheme(colorScheme = lightColorScheme(primary=Mint, onPrimary=Color.White, background=Page, surface=Color.White, onSurface=DeepGreen, secondary=Secondary)) {
  Scaffold(containerColor=Page, bottomBar={
   NavigationBar(containerColor=Color.White, contentColor=DeepGreen) {
    val labels=listOf("होम","स्कैन","टूल्स","रिपोर्ट","सेटिंग्स")
    val icons=listOf(Icons.Default.PhoneAndroid,Icons.Default.HealthAndSafety,Icons.Default.Build,Icons.Default.Assessment,Icons.Default.Settings)
    labels.forEachIndexed { i,label -> NavigationBarItem(selected=selected==i,onClick={
     selected=i
     if(i==1){ startDeviceScan() }
    },icon={Icon(icons[i],label)},label={Text(label,fontSize=11.sp)}) }
   }
  }) { padding ->
   LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Header(onRefresh={startDeviceScan()}) }
    when(selected) {
     0 -> {
      item { DeviceHero(snapshot,onScan={selected=1;startDeviceScan()}) }
      item { DeviceMetrics(snapshot) }
      item { Row(verticalAlignment=Alignment.CenterVertically){Text("फोन की स्थिति",fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen,modifier=Modifier.weight(1f));Text(lastScan,fontSize=11.sp,color=Secondary)} }
      items(issues.take(4)) { IssueCard(it,context) }
      item { ActionCard("पूरी जाँच देखें","RAM, स्टोरेज, बैटरी और मैनुअल सुरक्षा जाँच",Icons.Default.HealthAndSafety){selected=1;startDeviceScan()} }
     }
     1 -> {
      item { PageHeading("फोन स्कैन","हार्डवेयर, बैटरी, RAM, स्टोरेज और सुरक्षा जाँच") }
      item {
       Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Border)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
         Text("हार्डवेयर जाँच",fontWeight=FontWeight.Bold,color=DeepGreen,fontSize=16.sp)
         Text("Android द्वारा रिपोर्ट किए गए hardware features और sensors की वास्तविक जाँच। इससे कैमरा/माइक का पूरा कार्यात्मक परीक्षण नहीं होता।",fontSize=12.sp,color=Secondary)
         hardwareChecks(context).forEach { (name, result) ->
          Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
           Icon(Icons.Default.Memory,null,tint=Mint,modifier=Modifier.size(18.dp))
           Spacer(Modifier.width(8.dp))
           Text(name,modifier=Modifier.weight(1f),fontSize=13.sp,color=DeepGreen)
           Text(result,fontSize=12.sp,color=Secondary)
          }
         }
        }
       }
      }
      item {
       Card(colors=CardDefaults.cardColors(containerColor=PaleMint),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Border)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
         Row(verticalAlignment=Alignment.CenterVertically) {
          Icon(Icons.Default.Security,null,tint=Mint,modifier=Modifier.size(26.dp))
          Spacer(Modifier.width(8.dp))
          Column {
           Text("Malware / वायरस सुरक्षा",fontWeight=FontWeight.Bold,color=DeepGreen,fontSize=16.sp)
           Text("Google Play Protect या फोन की सुरक्षा सेटिंग में स्कैन खोलें।",fontSize=12.sp,color=Secondary)
          }
         }
         Text("Android ऐप को दूसरे ऐप्स की निजी फाइलों और पूरे फोन को अपने-आप वायरस स्कैन करने की अनुमति नहीं देता। इसलिए यहाँ नकली 'साफ़' परिणाम नहीं दिखाया जाता।",fontSize=12.sp,color=Secondary)
         Button(onClick={openSecuritySettings(context)},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)) {
          Icon(Icons.Default.Security,null); Spacer(Modifier.width(8.dp)); Text("फोन की Security Settings खोलें")
         }
        }
       }
      }
      item {
       Card(colors=CardDefaults.cardColors(containerColor=PaleMint),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Border)) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
         Icon(Icons.Default.CheckCircle,null,tint=Mint,modifier=Modifier.size(28.dp))
         Spacer(Modifier.width(10.dp))
         Column(Modifier.weight(1f)) {
          Text(lastScan, fontWeight=FontWeight.Bold, color=DeepGreen, fontSize=14.sp)
          Text(if(isScanning) "Android से वास्तविक उपलब्ध डिवाइस आँकड़े पढ़े जा रहे हैं।" else "स्कैन आपके फोन के उपलब्ध सिस्टम आँकड़े पढ़ता है; यह malware या हर hardware की पूर्ण जाँच नहीं है।", color=Secondary, fontSize=12.sp)
          if(isScanning) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress={scanProgress/100f}, modifier=Modifier.fillMaxWidth(), color=Mint, trackColor=Border); Text("$scanProgress% • कृपया प्रतीक्षा करें",fontSize=11.sp,color=Secondary) }
         }
         Button(onClick={startDeviceScan()},enabled=!isScanning,colors=ButtonDefaults.buttonColors(containerColor=Mint)) {
          if(isScanning) CircularProgressIndicator(modifier=Modifier.size(18.dp),strokeWidth=2.dp,color=Color.White) else Icon(Icons.Default.Refresh,null)
          Spacer(Modifier.width(4.dp)); Text(if(isScanning) "जाँच जारी…" else "स्कैन शुरू करें")
         }
        }
       }
      }
      item { DeviceHero(snapshot,onScan={selected=1;startDeviceScan()}) }
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
      item { Button(onClick={startDeviceScan()},enabled=!isScanning,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text(if(isScanning) "जाँच जारी है…" else "रिपोर्ट रीफ़्रेश / स्कैन करें")} }
     }
     else -> {
      item { PageHeading("सेटिंग्स","Phone Doctor और सिस्टम विकल्प") }
      item { ActionCard("Android Settings","मुख्य सिस्टम सेटिंग्स खोलें",Icons.Default.Settings){openSystemPage(context,"settings")} }
      item { ActionCard("App permissions","Privacy और Permission Manager खोलें",Icons.Default.Security){openSystemPage(context,"permissions")} }
      item { ActionCard("App info","Phone Doctor की Android app info",Icons.Default.Info){try{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:"+context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){openSystemPage(context,"settings")}} }
      item { ReportRow("ऐप संस्करण",(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.1")) }
      item { ReportRow("Package","com.paliaapk.phonedoctor") }
      item { ReportRow("ब्रांड","By PaliaAPK HUB") }
      item { ReportRow("डेवलपर","Developer By shanpalia") }
      item {
       Card(shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
         Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.SystemUpdate,null,tint=Mint,modifier=Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("Check APK System Version",fontSize=16.sp,fontWeight=FontWeight.Bold,color=DeepGreen); Text("www.paliaapkhub.com से नवीनतम संस्करण verify करें",fontSize=12.sp,color=Secondary) } }
         Text(versionStatus,fontSize=13.sp,color=if(versionStatus.startsWith("नया संस्करण")) Mint else DeepGreen)
         Button(onClick={checkWebsiteVersion()},enabled=!versionChecking,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)) { if(versionChecking) CircularProgressIndicator(modifier=Modifier.size(18.dp),strokeWidth=2.dp,color=Color.White) else Icon(Icons.Default.Refresh,null); Spacer(Modifier.width(8.dp)); Text(if(versionChecking)"जाँच जारी है…" else "Check Version") }
         if(latestVersion!=null && updateUrl!=null) Button(onClick={try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(updateUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){}},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=DeepGreen)) { Icon(Icons.Default.Download,null); Spacer(Modifier.width(8.dp)); Text("नया APK देखें / डाउनलोड करें") }
         Text("Website version file: /phone-doctor-version.json",fontSize=11.sp,color=Secondary)
        }
       }
      }
     }
    }
    item { Spacer(Modifier.height(8.dp)) }
   }
  }
 }
}

@Composable private fun Header(onRefresh:()->Unit) {
 Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
  Box(Modifier.size(52.dp).background(PaleMint,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center){Image(painterResource(R.drawable.ic_phone_doctor),contentDescription="Phone Doctor icon",modifier=Modifier.size(48.dp))}
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
   Box(Modifier.size(116.dp).background(PaleMint,RoundedCornerShape(32.dp)),contentAlignment=Alignment.Center){Image(painterResource(R.drawable.ic_phone_doctor),contentDescription="Phone Doctor icon",modifier=Modifier.size(108.dp))}
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
    Image(painterResource(R.drawable.ic_phone_doctor),contentDescription="Phone Doctor",modifier=Modifier.size(44.dp));Spacer(Modifier.width(12.dp))
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
