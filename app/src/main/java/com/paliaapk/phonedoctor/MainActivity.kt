package com.paliaapk.phonedoctor

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
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
 val batteryPercent: Int?, val network: String
) {
 val displayName get() = listOf(manufacturer, model).filter { it.isNotBlank() }.distinct().joinToString(" ")
}

private fun readDevice(context: Context): DeviceSnapshot {
 val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
 val mem = ActivityManager.MemoryInfo()
 am.getMemoryInfo(mem)
 val stat = StatFs(Environment.getDataDirectory().absolutePath)
 val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
 val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
 val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
 val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
 val network = when {
  caps == null -> "नेटवर्क कनेक्शन उपलब्ध नहीं"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi कनेक्टेड"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "मोबाइल डेटा कनेक्टेड"
  caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet कनेक्टेड"
  else -> "नेटवर्क कनेक्शन मिला"
 }
 return DeviceSnapshot(
  Build.MANUFACTURER.orEmpty().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
  Build.MODEL.orEmpty(), Build.VERSION.RELEASE ?: "अज्ञात", Build.VERSION.SDK_INT,
  mem.totalMem / (1024L * 1024L), mem.availMem / (1024L * 1024L),
  stat.totalBytes / (1024L * 1024L), stat.availableBytes / (1024L * 1024L), battery, network
 )
}

private data class Finding(val title: String, val detail: String, val severity: String, val fix: String)
private fun findings(s: DeviceSnapshot): List<Finding> = buildList {
 val usedStorage = if (s.storageTotal > 0) ((s.storageTotal - s.storageFree) * 100 / s.storageTotal).toInt() else 0
 val usedRam = if (s.ramTotal > 0) ((s.ramTotal - s.ramAvailable) * 100 / s.ramTotal).toInt() else 0
 if (usedStorage >= 90) add(Finding("स्टोरेज लगभग भर गया है", "$usedStorage% स्टोरेज उपयोग में है।", "ध्यान दें", "Settings में Storage खोलें और केवल अनचाही फाइलें हटाएँ।"))
 if (usedRam >= 90) add(Finding("RAM उपयोग अधिक है", "$usedRam% RAM उपयोग में दिखाई दे रही है।", "ध्यान दें", "Settings में ऐप उपयोग देखें; ज़रूरत न हो तो भारी ऐप बंद करें।"))
 if (s.batteryPercent != null && s.batteryPercent <= 15) add(Finding("बैटरी कम है", "बैटरी ${s.batteryPercent}% है।", "कम बैटरी", "चार्जर लगाएँ या Battery Saver चालू करें।"))
 if (isEmpty()) add(Finding("कोई स्पष्ट संसाधन चेतावनी नहीं", "उपलब्ध आँकड़ों में तय threshold पार नहीं हुआ।", "जानकारी", "समय-समय पर स्कैन दोहराएँ।"))
 add(Finding("ऐप अनुमतियाँ", "हर इंस्टॉल किए गए ऐप की पूरी अनुमति सूची इस स्कैन में स्वतः उपलब्ध नहीं है।", "मैनुअल जाँच", "Settings → Privacy → Permission manager में location, camera और microphone देखें।"))
 add(Finding("सिस्टम अपडेट", "यह ऐप अपडेट की उपलब्धता सीधे सत्यापित नहीं करता।", "मैनुअल जाँच", "Settings → System update में अपडेट जाँचें।"))
}

@Composable
private fun PhoneDoctorApp(context: Context) {
 var showSplash by remember { mutableStateOf(true) }
 LaunchedEffect(Unit) { delay(1800); showSplash = false }
 if (showSplash) { BrandingSplash(); return }
 var selected by remember { mutableIntStateOf(0) }
 var refreshKey by remember { mutableIntStateOf(0) }
 val snapshot = remember(refreshKey) { readDevice(context) }
 val issues = remember(snapshot) { findings(snapshot) }
 MaterialTheme(colorScheme = lightColorScheme(primary = Mint, onPrimary = Color.White, background = Page, surface = Color.White, onSurface = DeepGreen, secondary = Secondary)) {
  Scaffold(containerColor = Page, bottomBar = {
   NavigationBar(containerColor = Color.White, contentColor = DeepGreen) {
    val labels = listOf("होम", "स्कैन", "टूल्स", "रिपोर्ट", "सेटिंग्स")
    val icons = listOf(Icons.Default.PhoneAndroid, Icons.Default.Refresh, Icons.Default.CleaningServices, Icons.Default.BugReport, Icons.Default.Settings)
    labels.forEachIndexed { index, label ->
     NavigationBarItem(selected = selected == index, onClick = { selected = index; if (index == 1) refreshKey++ }, icon = { Icon(icons[index], contentDescription = label) }, label = { Text(label, fontSize = 11.sp) })
    }
   }
  }) { padding ->
   LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    item {
     Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Box(Modifier.size(54.dp).background(PaleMint, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.HealthAndSafety, null, tint = Mint, modifier = Modifier.size(36.dp)) }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
       Text("Phone Doctor", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = DeepGreen)
       Text("By PaliaAPK HUB", fontSize = 13.sp, color = Mint, fontWeight = FontWeight.Bold)
       Text("Developer By shanpalia", fontSize = 11.sp, color = Secondary)
      }
      IconButton(onClick = { refreshKey++ }) { Icon(Icons.Default.Refresh, "रिफ्रेश", tint = Mint) }
     }
    }
    item {
     Card(colors = CardDefaults.cardColors(containerColor = DeepGreen), shape = RoundedCornerShape(24.dp)) {
      Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
       Text("आपके फोन की सेहत, अब आपकी हथेली में", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
       Text("डिवाइस की वास्तविक जानकारी और उपलब्ध सिस्टम संकेत देखें।", color = Color(0xFFD2F5E7), fontSize = 13.sp)
       Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.PhoneAndroid, null, tint = Color(0xFF62E6B2), modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
         Text(snapshot.displayName.ifBlank { "Android डिवाइस" }, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
         Text("Android ${snapshot.android} • API ${snapshot.sdk}", color = Color(0xFFD2F5E7), fontSize = 12.sp)
        }
        Button(onClick = { refreshKey++ }, colors = ButtonDefaults.buttonColors(containerColor = Mint)) { Text("स्कैन करें") }
       }
      }
     }
    }
    item {
     SectionTitle("डिवाइस जानकारी", "वास्तविक डिवाइस API")
     Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      MetricCard("RAM", "${formatMb(snapshot.ramTotal - snapshot.ramAvailable)} / ${formatMb(snapshot.ramTotal)}", Icons.Default.Memory, Modifier.weight(1f), "उपयोग का अनुमान")
      MetricCard("स्टोरेज", "${formatMb(snapshot.storageTotal - snapshot.storageFree)} / ${formatMb(snapshot.storageTotal)}", Icons.Default.Storage, Modifier.weight(1f), "फोन स्टोरेज")
     }
     Spacer(Modifier.height(10.dp))
     Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      MetricCard("बैटरी", snapshot.batteryPercent?.let { "$it%" } ?: "उपलब्ध नहीं", Icons.Default.BatteryFull, Modifier.weight(1f), "BatteryManager")
      MetricCard("नेटवर्क", snapshot.network, Icons.Default.Wifi, Modifier.weight(1f), "कनेक्शन स्थिति")
     }
    }
    item {
     SectionTitle("पाई गई समस्याएँ", "${issues.size} संकेत")
     Text("नीचे केवल मापे गए आँकड़ों या मैनुअल-जाँच की ज़रूरत वाले संकेत हैं। यह malware scan नहीं है।", color = Secondary, fontSize = 12.sp)
    }
    items(issues) { finding -> IssueCard(finding) }
    item {
     Card(colors = CardDefaults.cardColors(containerColor = PaleMint), shape = RoundedCornerShape(18.dp)) {
      Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
       Icon(Icons.Default.Security, null, tint = Mint)
       Spacer(Modifier.width(10.dp))
       Column {
        Text("सुरक्षित सुधार", fontWeight = FontWeight.Bold, color = DeepGreen)
        Text("यह संस्करण कोई फाइल खुद से नहीं हटाता और सेटिंग्स चुपचाप नहीं बदलता। सुधार के लिए सिस्टम Settings और चरण-दर-चरण मार्गदर्शन दिया जाता है।", color = Secondary, fontSize = 12.sp)
       }
      }
     }
    }
   }
  }
 }
}


@Composable
private fun BrandingSplash() {
 Surface(modifier = Modifier.fillMaxSize(), color = Page) {
  Column(
   modifier = Modifier.fillMaxSize().padding(28.dp),
   horizontalAlignment = Alignment.CenterHorizontally,
   verticalArrangement = Arrangement.Center
  ) {
   Box(Modifier.size(116.dp).background(PaleMint, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
    Icon(Icons.Default.HealthAndSafety, null, tint = Mint, modifier = Modifier.size(82.dp))
   }
   Spacer(Modifier.height(26.dp))
   Text("Phone Doctor", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = DeepGreen)
   Spacer(Modifier.height(6.dp))
   Text("By PaliaAPK HUB", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Mint)
   Spacer(Modifier.height(8.dp))
   Text("आपके फोन की सेहत, अब आपकी हथेली में", fontSize = 14.sp, color = Secondary)
   Spacer(Modifier.height(46.dp))
   LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.58f), color = Mint, trackColor = Border)
   Spacer(Modifier.height(42.dp))
   Text("Developer By shanpalia", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DeepGreen)
   Spacer(Modifier.height(7.dp))
   Text("सुरक्षित जाँच • स्पष्ट समाधान", fontSize = 12.sp, color = Secondary)
  }
 }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
 Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
  Text(title, fontWeight = FontWeight.ExtraBold, color = DeepGreen, fontSize = 18.sp)
  Text(subtitle, color = Mint, fontSize = 11.sp)
 }
}
@Composable
private fun MetricCard(title: String, value: String, icon: ImageVector, modifier: Modifier, caption: String) {
 Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Border)) {
  Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
   Icon(icon, null, tint = Mint, modifier = Modifier.size(25.dp))
   Text(title, color = Secondary, fontSize = 12.sp)
   Text(value, color = DeepGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
   Text(caption, color = Secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
 }
}
@Composable
private fun IssueCard(finding: Finding) {
 Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Border)) {
  Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(38.dp).background(PaleMint, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.BugReport, null, tint = Mint) }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
     Text(finding.title, fontWeight = FontWeight.Bold, color = DeepGreen, fontSize = 14.sp)
     Text(finding.severity, color = Mint, fontSize = 11.sp)
    }
   }
   Text(finding.detail, color = Secondary, fontSize = 12.sp)
   Text("क्या करें: ${finding.fix}", color = DeepGreen, fontSize = 12.sp)
  }
 }
}
private fun formatMb(mb: Long): String = if (mb >= 1024) String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0) else "$mb MB"
