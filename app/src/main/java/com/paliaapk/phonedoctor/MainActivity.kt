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
  BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
  BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
  BatteryManager.BATTERY_HEALTH_DEAD -> "Battery failure"
  BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
  BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Voltage warning"
  BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "System-reported failure"
  else -> "Not available"
 }
 val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
 val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
 val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
 val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
 val network = when {
  caps == null -> "No connection"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
  else -> "Connected"
 }
 return DeviceSnapshot(
  Build.MANUFACTURER.orEmpty().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
  Build.MODEL.orEmpty(), Build.VERSION.RELEASE ?: "Unknown", Build.VERSION.SDK_INT,
  mem.totalMem/(1024L*1024L), mem.availMem/(1024L*1024L),
  stat.totalBytes/(1024L*1024L), stat.availableBytes/(1024L*1024L),
  battery, temp, health, charging, network
 )
}

private data class Finding(val title: String, val detail: String, val severity: String, val fix: String, val action: String)
private fun findings(s: DeviceSnapshot): List<Finding> = buildList {
 if (s.storageUsedPercent >= 90) add(Finding("Storage Almost Full", "${s.storageUsedPercent}% of storage is in use.", "Attention", "Review large or unwanted files in Storage Settings.", "storage"))
 if (s.ramUsedPercent >= 90) add(Finding("High RAM Usage", "${s.ramUsedPercent}% RAM usage is reported.", "Attention", "Memory usage changes over time. Close recent apps or restart the phone if needed.", "memory"))
 if (s.batteryPercent != null && s.batteryPercent <= 15) add(Finding("Low Battery", "Battery is at ${s.batteryPercent}%.", "Low battery", "Connect a charger or enable Battery Saver.", "battery"))
 if (s.batteryTempC != null && s.batteryTempC >= 45f) add(Finding("High Battery Temperature", "Battery temperature is ${String.format(Locale.getDefault(), "%.1f", s.batteryTempC)}°C.", "Heat", "Stop charging, close demanding apps, and move the phone to a cool place. Do not refrigerate it.", "battery"))
 if (s.batteryHealth == "Overheating" || s.batteryHealth == "Battery failure" || s.batteryHealth == "Voltage warning" || s.batteryHealth == "System-reported failure") add(Finding("Battery Health Warning", "Android battery status: ${s.batteryHealth}", "Check required", "Contact the phone manufacturer’s service center.", "battery"))
 if (isEmpty()) add(Finding("No Critical Resource Alerts", "Current RAM, storage and battery thresholds were not exceeded. This is not a full hardware or malware certification.", "Normal", "Run checks periodically and keep apps and Android updated.", ""))
 add(Finding("Review App Permissions", "Android limits which app permissions can be inspected. This app does not claim to audit every app.", "Manual check", "Review camera, microphone, location and contacts in Permission Manager.", "permissions"))
 add(Finding("Check System Updates", "This app does not claim to detect available system updates automatically.", "Manual check", "Open System Update in Android Settings.", "update"))
}

private fun hardwareChecks(context: Context): List<Pair<String, String>> {
 val pm = context.packageManager
 val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
 val sensors = sensorManager.getSensorList(android.hardware.Sensor.TYPE_ALL)
 fun status(feature: String) = if (pm.hasSystemFeature(feature)) "Available" else "Not detected"
 return listOf(
  "Camera Hardware" to status(PackageManager.FEATURE_CAMERA_ANY),
  "Flash" to status(PackageManager.FEATURE_CAMERA_FLASH),
  "Microphone" to status(PackageManager.FEATURE_MICROPHONE),
  "Motion Sensors" to if (sensors.isNotEmpty()) "${sensors.size} sensors detected" else "No sensors reported",
  "Fingerprint Sensor" to status(PackageManager.FEATURE_FINGERPRINT),
  "GPS / Location Hardware" to status(PackageManager.FEATURE_LOCATION_GPS)
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

private data class PermissionItem(val permission: String)

@Composable
private fun PhoneDoctorApp(context: Context) {
 var showSplash by remember { mutableStateOf(true) }
 LaunchedEffect(Unit) { delay(1500); showSplash = false }
 if (showSplash) { BrandingSplash(); return }

 val permissionItems = remember {
  buildList {
   add(PermissionItem(Manifest.permission.CAMERA))
   add(PermissionItem(Manifest.permission.RECORD_AUDIO))
   add(PermissionItem(Manifest.permission.ACCESS_FINE_LOCATION))
   if (Build.VERSION.SDK_INT >= 33) add(PermissionItem(Manifest.permission.POST_NOTIFICATIONS))
  }
 }
 var permissionIndex by remember { mutableIntStateOf(0) }
 val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
  permissionIndex = (permissionIndex + 1).coerceAtMost(permissionItems.size)
 }
 LaunchedEffect(permissionIndex, showSplash) {
  if (!showSplash && permissionIndex < permissionItems.size) {
   val permission = permissionItems[permissionIndex].permission
   if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
    permissionIndex++
   } else {
    permissionLauncher.launch(permission)
   }
  }
 }
 var selected by remember { mutableIntStateOf(0) }
 var refreshKey by remember { mutableIntStateOf(0) }
 var lastScan by remember { mutableStateOf("Not scanned yet") }
 var isScanning by remember { mutableStateOf(false) }
 var scanProgress by remember { mutableIntStateOf(0) }
 var reportVisibleCount by remember { mutableIntStateOf(0) }
 var hardwareVisibleCount by remember { mutableIntStateOf(0) }
 var selectedHardware by remember { mutableStateOf<Pair<String, String>?>(null) }
 var versionStatus by remember { mutableStateOf("Tap Check Version to look for updates") }
 var latestVersion by remember { mutableStateOf<String?>(null) }
 var updateUrl by remember { mutableStateOf<String?>(null) }
 var versionChecking by remember { mutableStateOf(false) }
 val scope = rememberCoroutineScope()
 fun startDeviceScan() {
  if (isScanning) return
  isScanning = true
  scanProgress = 0
  reportVisibleCount = 0
  hardwareVisibleCount = 0
  lastScan = "Starting device diagnostics…"
  scope.launch {
   val stages = listOf("Reading battery information…", "Checking available RAM…", "Checking storage usage…", "Refreshing network and Android information…")
   stages.forEachIndexed { index, stage ->
    lastScan = stage
    delay(500)
    refreshKey++
    scanProgress = (index + 1) * 15
   }
   val hardware = hardwareChecks(context)
   hardware.forEachIndexed { index, check ->
    lastScan = "Checking ${check.first}…"
    delay(450)
    hardwareVisibleCount = index + 1
    scanProgress = 60 + ((index + 1) * 25 / hardware.size.coerceAtLeast(1))
   }
   lastScan = "Generating diagnostic report…"
   for (count in 1..8) {
    delay(250)
    reportVisibleCount = count
    scanProgress = 85 + (count * 15 / 8)
   }
   lastScan = "Scan complete • Report generated"
   isScanning = false
  }
 }
 fun checkWebsiteVersion() {
  if (versionChecking) return
  versionChecking = true
  latestVersion = null
  updateUrl = null
  versionStatus = "Checking PaliaAPK HUB for updates…"
  scope.launch {
   val result = withContext(Dispatchers.IO) {
    try {
     val connection = (URL("https://paliaapkhub.com/updates/updates.json").openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 10000
      readTimeout = 10000
      useCaches = false
      setRequestProperty("Cache-Control", "no-cache")
      setRequestProperty("Accept", "application/json")
     }
     try {
      if (connection.responseCode !in 200..299) throw IllegalStateException("Update website returned HTTP " + connection.responseCode)
      val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
      val apps = root.optJSONArray("apps") ?: throw IllegalStateException("Website update list is missing")
      var app: JSONObject? = null
      for (i in 0 until apps.length()) {
       val candidate = apps.optJSONObject(i) ?: continue
       if (candidate.optString("package_name").trim() == context.packageName) {
        app = candidate
        break
       }
      }
      val entry = app ?: throw IllegalStateException("Phone Doctor update is not published on the website yet")
      val latest = entry.optString("latest_version").trim()
      val download = entry.optString("download_url").trim()
      if (latest.isBlank() || download.isBlank()) throw IllegalStateException("Phone Doctor version or download link is missing")
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      val current = info.versionName ?: "1.0.1"
      fun compareVersions(a: String, b: String): Int {
       val av = a.removePrefix("v").split(".", "-", "_").map { it.toIntOrNull() ?: 0 }
       val bv = b.removePrefix("v").split(".", "-", "_").map { it.toIntOrNull() ?: 0 }
       for (i in 0 until maxOf(av.size, bv.size)) {
        val left = av.getOrElse(i) { 0 }
        val right = bv.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
       }
       return 0
      }
      if (compareVersions(latest, current) > 0) Triple("UPDATE", latest, download)
      else Triple("CURRENT", latest, "")
     } finally { connection.disconnect() }
    } catch (e: Exception) { Triple("ERROR", "", e.message ?: "Connection unavailable") }
   }
   when (result.first) {
    "UPDATE" -> {
     latestVersion = result.second
     updateUrl = result.third
     versionStatus = "New version " + result.second + " is available. Installed version: " + (context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.1") + "."
    }
    "CURRENT" -> {
     latestVersion = null
     updateUrl = null
     versionStatus = "Your app is up to date • Latest version " + result.second
    }
    else -> {
     latestVersion = null
     updateUrl = null
     versionStatus = "Update check failed: " + result.third
    }
   }
   versionChecking = false
  }
 }
 val snapshot = remember(refreshKey) { readDevice(context) }
 val issues = remember(snapshot) { findings(snapshot) }
 MaterialTheme(colorScheme = lightColorScheme(primary=Mint, onPrimary=Color.White, background=Page, surface=Color.White, onSurface=DeepGreen, secondary=Secondary)) {
  Scaffold(containerColor=Page, bottomBar={
   NavigationBar(containerColor=Color.White, contentColor=DeepGreen) {
    val labels=listOf("Home","Scan","Tools","Reports","Settings")
    val icons=listOf(Icons.Default.PhoneAndroid,Icons.Default.HealthAndSafety,Icons.Default.Build,Icons.Default.Assessment,Icons.Default.Settings)
    labels.forEachIndexed { i,label -> NavigationBarItem(selected=selected==i,onClick={
     selected=i
     
    },icon={Icon(icons[i],label)},label={Text(label,fontSize=11.sp)}) }
   }
  }) { padding ->
   LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Header(onRefresh={startDeviceScan()}) }
    when(selected) {
     0 -> {
      item { DeviceHero(snapshot,onScan=null) }
      item { DeviceMetrics(snapshot) }
      item { Text("Device information updates from Android system readings.",fontSize=12.sp,color=Secondary) }
     }
     1 -> {
      item { PageHeading("Device Scan","Start a scan to generate a live report") }
      item {
       Card(colors=CardDefaults.cardColors(containerColor=PaleMint),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Border)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
         Row(verticalAlignment=Alignment.CenterVertically) {
          Icon(Icons.Default.FactCheck,null,tint=Mint,modifier=Modifier.size(28.dp))
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
           Text(if(isScanning) "Scanning device…" else "Scan Progress",fontWeight=FontWeight.Bold,color=DeepGreen,fontSize=16.sp)
           Text(lastScan,color=Secondary,fontSize=12.sp)
          }
         }
         LinearProgressIndicator(progress={scanProgress/100f},modifier=Modifier.fillMaxWidth(),color=Mint,trackColor=Border)
         Text("$scanProgress% complete",fontSize=12.sp,color=Secondary)
         Button(onClick={startDeviceScan()},enabled=!isScanning,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)) {
          if(isScanning) CircularProgressIndicator(modifier=Modifier.size(18.dp),strokeWidth=2.dp,color=Color.White) else Icon(Icons.Default.Refresh,null)
          Spacer(Modifier.width(8.dp)); Text(if(isScanning) "Scanning…" else "Start Scan")
         }
        }
       }
      }
      item { Text("Hardware Diagnostics",fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen) }
      item { Text("Checks appear one by one after you start the scan.",fontSize=12.sp,color=Secondary) }
      if(hardwareVisibleCount>0) {
       items(hardwareChecks(context).take(hardwareVisibleCount)) { check ->
        val isOk = check.second == "Available" || (check.first == "Motion Sensors" && check.second != "No sensors reported")
        Card(shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)) {
         Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
          Icon(if(isOk) Icons.Default.CheckCircle else Icons.Default.Error,null,tint=if(isOk) Color(0xFF16A34A) else Color(0xFFDC2626),modifier=Modifier.size(22.dp))
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
           Text(check.first,fontWeight=FontWeight.SemiBold,color=DeepGreen,fontSize=13.sp)
           Text(check.second,fontSize=12.sp,color=if(isOk) Color(0xFF16A34A) else Color(0xFFDC2626))
          }
         }
        }
       }
      }
      if(reportVisibleCount>0) {
       item { Text("Device Checks",fontSize=18.sp,fontWeight=FontWeight.Bold,color=DeepGreen) }
       items(issues.take(reportVisibleCount)) { issue -> IssueCard(issue,context) }
       item {
        Card(colors=CardDefaults.cardColors(containerColor=PaleMint),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Border)) {
         Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
          Text("Malware & Virus Protection",fontWeight=FontWeight.Bold,color=DeepGreen,fontSize=16.sp)
          Text("For malware scanning, use Google Play Protect or Android Security Settings. This app cannot scan other apps’ private files.",fontSize=12.sp,color=Secondary)
          Button(onClick={openSecuritySettings(context)},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)) {
           Icon(Icons.Default.Security,null); Spacer(Modifier.width(8.dp)); Text("Open Security Settings")
          }
         }
        }
       }
       item { Text("Device metrics are refreshed during the scan. This is not a full malware scan or complete functional test of every component.",fontSize=12.sp,color=Secondary) }
      }
     }
     2 -> {
      item { PageHeading("System Tools","Branded shortcuts to Android system settings") }
      item { ActionCard("Storage Settings","Review storage space and usage",Icons.Default.Storage){openSystemPage(context,"storage")} }
      item { ActionCard("Battery Settings","Battery Saver and battery options",Icons.Default.BatteryFull){openSystemPage(context,"battery")} }
      item { ActionCard("App Permissions","Privacy and Permission Manager",Icons.Default.Security){openSystemPage(context,"permissions")} }
      item { ActionCard("Installed Apps","App info, force stop and uninstall options",Icons.Default.Apps){openSystemPage(context,"apps")} }
      item { ActionCard("Wi-Fi Settings","Wi-Fi and network settings",Icons.Default.Wifi){openSystemPage(context,"wifi")} }
      item { ActionCard("System Update","Open device information and system update",Icons.Default.SystemUpdate){openSystemPage(context,"update")} }
     }
     3 -> {
      item { PageHeading("Hardware Report","Tap any component to view its status and diagnostic limits") }
      items(hardwareChecks(context)) { check ->
       val isOk = check.second == "Available" || (check.first == "Motion Sensors" && check.second != "No sensors reported")
       Card(onClick={selectedHardware=check},shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
         Icon(if(isOk) Icons.Default.CheckCircle else Icons.Default.Error,null,tint=if(isOk) Color(0xFF16A34A) else Color(0xFFDC2626),modifier=Modifier.size(28.dp))
         Spacer(Modifier.width(12.dp))
         Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
          Text(check.first,fontWeight=FontWeight.Bold,color=DeepGreen,fontSize=14.sp)
          Text(check.second,fontSize=12.sp,color=if(isOk) Color(0xFF16A34A) else Color(0xFFDC2626))
         }
         Icon(Icons.Default.ChevronRight,null,tint=Secondary)
        }
       }
      }
      item {
       Card(colors=CardDefaults.cardColors(containerColor=PaleMint),shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Border)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
         Text("Diagnostic note",fontWeight=FontWeight.Bold,color=DeepGreen)
         Text("Green means Android reports the feature as available. Red means it was not detected. This check does not prove the component works physically; tap a row for details.",fontSize=12.sp,color=Secondary)
        }
       }
      }
     }
     else -> {
      item { PageHeading("Phone Doctor Settings","App information and system options") }
      item { ActionCard("Android Settings","Open main device settings",Icons.Default.Settings){openSystemPage(context,"settings")} }
      item { ActionCard("App Permissions","Manage privacy and permissions",Icons.Default.Security){openSystemPage(context,"permissions")} }
      item { ActionCard("App Info","Open Phone Doctor app details",Icons.Default.Info){try{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:"+context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){openSystemPage(context,"settings")}} }
      item {
       Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=DeepGreen)) {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
         Box(Modifier.size(58.dp).background(Color.White.copy(alpha=.12f),RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) { Image(painterResource(R.drawable.ic_phone_doctor),contentDescription="Phone Doctor",modifier=Modifier.size(52.dp)) }
         Spacer(Modifier.width(13.dp))
         Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
          Text("PHONE DOCTOR",fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
          Text("By PaliaAPK HUB",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Color(0xFF6EE7B7))
          Text("Developer By shanpalia",fontSize=12.sp,color=Color.White.copy(alpha=.78f))
         }
        }
       }
      }
      item {
       Card(shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
         Row(verticalAlignment=Alignment.CenterVertically) {
          Box(Modifier.size(42.dp).background(PaleMint,RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center) {
           Icon(Icons.Default.Info,null,tint=Mint,modifier=Modifier.size(24.dp))
          }
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
           Text("App Version",fontSize=12.sp,color=Secondary)
           Text("v" + (context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.1"),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen)
           Text("Phone Doctor for Android",fontSize=12.sp,color=Secondary)
          }
         }
        }
       }
      }
      item {
       Card(onClick={try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://paliaapkhub.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){}},shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Border)) {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
         Box(Modifier.size(44.dp).background(PaleMint,RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center) {
          Icon(Icons.Default.Apps,null,tint=Mint,modifier=Modifier.size(25.dp))
         }
         Spacer(Modifier.width(12.dp))
         Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
          Text("PaliaAPK HUB",fontSize=15.sp,fontWeight=FontWeight.Bold,color=DeepGreen)
          Text("More Games & Apps",fontSize=12.sp,color=Secondary)
          Text("Visit official website",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Mint)
         }
         Icon(Icons.Default.OpenInNew,null,tint=Mint)
        }
       }
      }
      item {
       Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=PaleMint),border=BorderStroke(1.dp,Border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
         Row(verticalAlignment=Alignment.CenterVertically) {
          Box(Modifier.size(42.dp).background(Color.White,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(Icons.Default.SystemUpdate,null,tint=Mint,modifier=Modifier.size(26.dp))}
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) { Text("PaliaAPK HUB",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen); Text("Check the latest app version",fontSize=12.sp,color=Secondary) }
         }
         Row(verticalAlignment=Alignment.CenterVertically) {
          Icon(if(versionChecking) Icons.Default.Sync else if(versionStatus.startsWith("New version")) Icons.Default.SystemUpdate else Icons.Default.VerifiedUser,null,tint=Mint)
          Spacer(Modifier.width(8.dp))
          Text(versionStatus,fontSize=13.sp,color=DeepGreen,modifier=Modifier.weight(1f))
         }
         Button(onClick={checkWebsiteVersion()},enabled=!versionChecking,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=Mint)) {
          if(versionChecking) CircularProgressIndicator(modifier=Modifier.size(18.dp),strokeWidth=2.dp,color=Color.White) else Icon(Icons.Default.Refresh,null)
          Spacer(Modifier.width(8.dp)); Text(if(versionChecking)"Checking Website…" else "Check for Updates")
         }
         if(latestVersion!=null && updateUrl!=null) Button(onClick={try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(updateUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){}},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=DeepGreen)) { Icon(Icons.Default.Download,null); Spacer(Modifier.width(8.dp)); Text("Download Latest APK") }
        }
       }
      }
     }
    }
    item { Spacer(Modifier.height(8.dp)) }
   }
  }
 }
 if(selectedHardware != null) {
  val item = selectedHardware!!
  val isOk = item.second == "Available" || (item.first == "Motion Sensors" && item.second != "No sensors reported")
  AlertDialog(onDismissRequest={selectedHardware=null},icon={Icon(if(isOk) Icons.Default.CheckCircle else Icons.Default.Error,null,tint=if(isOk) Color(0xFF16A34A) else Color(0xFFDC2626))},title={Text(item.first,color=DeepGreen)},text={Text("Status: ${item.second}\n\n${if(isOk) "Android reports this hardware feature as available. This is an availability check, not a full physical function test." else "Android did not report this feature. It may be unsupported or unavailable on this device; verify the device specifications before treating this as a fault."}",color=Secondary)},confirmButton={TextButton(onClick={selectedHardware=null}){Text("Close")}})
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
  IconButton(onClick=onRefresh){Icon(Icons.Default.Refresh,"Refresh",tint=Mint)}
 }
}
@Composable private fun BrandingSplash() {
 Surface(Modifier.fillMaxSize(),color=Page) {
  Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
   Box(Modifier.size(116.dp).background(PaleMint,RoundedCornerShape(32.dp)),contentAlignment=Alignment.Center){Image(painterResource(R.drawable.ic_phone_doctor),contentDescription="Phone Doctor icon",modifier=Modifier.size(108.dp))}
   Spacer(Modifier.height(26.dp));Text("Phone Doctor",fontSize=32.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen)
   Spacer(Modifier.height(6.dp));Text("By PaliaAPK HUB",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Mint)
   Spacer(Modifier.height(8.dp));Text("Smart diagnostics for your Android device",fontSize=14.sp,color=Secondary)
   Spacer(Modifier.height(42.dp));LinearProgressIndicator(Modifier.fillMaxWidth(.58f),color=Mint,trackColor=Border)
   Spacer(Modifier.height(42.dp));Text("Developer By shanpalia",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=DeepGreen)
  }
 }
}
@Composable private fun PageHeading(title:String,subtitle:String){Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen);Text(subtitle,fontSize=13.sp,color=Secondary)}}
@Composable private fun DeviceHero(s:DeviceSnapshot,onScan:(()->Unit)? = null) {
 Card(colors=CardDefaults.cardColors(containerColor=DeepGreen),shape=RoundedCornerShape(24.dp)) {
  Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
   Text("YOUR ANDROID PHONE",color=Color(0xFFD2F5E7),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
   Row(verticalAlignment=Alignment.CenterVertically) {
    Image(painterResource(R.drawable.ic_phone_doctor),contentDescription="Phone Doctor",modifier=Modifier.size(44.dp));Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)){Text(s.displayName.ifBlank{"Android Device"},color=Color.White,fontWeight=FontWeight.Bold,fontSize=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text("Android ${s.android} • API ${s.sdk}",color=Color(0xFFD2F5E7),fontSize=12.sp)}
   }
   Row(verticalAlignment=Alignment.CenterVertically) {
    Text(if(s.batteryPercent==null)"Battery data unavailable" else "Battery ${s.batteryPercent}%"+if(s.charging)" • Charging" else "",color=Color.White,fontSize=13.sp,modifier=Modifier.weight(1f))
    if(onScan != null) Button(onClick=onScan,colors=ButtonDefaults.buttonColors(containerColor=Mint)){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(5.dp));Text("Scan")}
   }
  }
 }
}
@Composable private fun DeviceMetrics(s:DeviceSnapshot) {
 Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Text("Device Information",fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=DeepGreen)
  Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){MetricCard("RAM","${formatMb(s.ramTotal-s.ramAvailable)} / ${formatMb(s.ramTotal)}",Icons.Default.Memory,Modifier.weight(1f),"${s.ramUsedPercent}% estimated usage");MetricCard("Storage","${formatMb(s.storageTotal-s.storageFree)} / ${formatMb(s.storageTotal)}",Icons.Default.Storage,Modifier.weight(1f),"${s.storageUsedPercent}% used")}
  Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){MetricCard("Battery",s.batteryPercent?.let{"$it%"}?:"N/A",Icons.Default.BatteryFull,Modifier.weight(1f),s.batteryHealth);MetricCard("Network",s.network,Icons.Default.Wifi,Modifier.weight(1f),"Current connection")}
  if(s.batteryTempC!=null) Text("Battery temperature: ${String.format(Locale.getDefault(),"%.1f",s.batteryTempC)}°C",fontSize=12.sp,color=Secondary)
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
   Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(38.dp).background(PaleMint,CircleShape),contentAlignment=Alignment.Center){Icon(if(f.severity=="Attention"||f.severity=="Heat")Icons.Default.Warning else Icons.Default.HealthAndSafety,null,tint=Mint)};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(f.title,fontWeight=FontWeight.Bold,color=DeepGreen,fontSize=14.sp);Text(f.severity,color=Mint,fontSize=11.sp)}}
   Text(f.detail,color=Secondary,fontSize=12.sp)
   Text("Tip: ${f.fix}",color=DeepGreen,fontSize=12.sp)
   if(f.action.isNotBlank()) OutlinedButton(onClick={openSystemPage(context,f.action)},modifier=Modifier.fillMaxWidth(),border=BorderStroke(1.dp,Border)){Text("Open Related Android Settings",color=Mint)}
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
