package com.temporaryalarm.app

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

// ─────────────────────────────────────────────────────────────
//  DATA MODEL - Single alarm only
// ─────────────────────────────────────────────────────────────

data class AlarmData(
    val id: Int,
    val hour: Int = 8,
    val minute: Int = 0,
    val isAm: Boolean = true,
    val useBt: Boolean = true,
    val useVibration: Boolean = true,
    val isActive: Boolean = false,
    val ringtoneUri: String? = null,
    val ringtoneName: String = "Default"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("isAm", isAm)
        put("useBt", useBt)
        put("useVibration", useVibration)
        put("isActive", isActive)
        put("ringtoneUri", ringtoneUri)
        put("ringtoneName", ringtoneName)
    }

    fun displayTime(): String {
        val h = if (hour == 0) 12 else hour
        return "%d:%02d %s".format(h, minute, if (isAm) "AM" else "PM")
    }

    companion object {
        fun fromJson(j: JSONObject) = AlarmData(
            id = j.getInt("id"),
            hour = j.optInt("hour", 8),
            minute = j.optInt("minute", 0),
            isAm = j.optBoolean("isAm", true),
            useBt = j.optBoolean("useBt", true),
            useVibration = j.optBoolean("useVibration", true),
            isActive = j.optBoolean("isActive", false),
            ringtoneUri = j.optString("ringtoneUri", null),
            ringtoneName = j.optString("ringtoneName", "Default")
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  STORAGE - Just one alarm
// ─────────────────────────────────────────────────────────────

object AlarmStore {
    private const val PREF = "tmp_alarm_prefs"
    private const val KEY = "alarm_json"

    fun load(ctx: Context): AlarmData? {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try {
            AlarmData.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, alarm: AlarmData?) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, alarm?.toJson()?.toString())
            .apply()
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM SCHEDULER - Single alarm only
// ─────────────────────────────────────────────────────────────

object AlarmScheduler {
    const val ACTION_ALARM_TRIGGERED = "com.temporaryalarm.app.ALARM_TRIGGERED"
    const val ACTION_ALARM_STOPPED = "com.temporaryalarm.app.ALARM_STOPPED"
    const val RING_DURATION = 2 * 60 * 1000L // 2 minutes

    fun schedule(ctx: Context, alarm: AlarmData) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Cancel any existing alarm
        cancel(ctx)
        
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGERED
            putExtra("alarm_id", alarm.id)
            putExtra("use_bt", alarm.useBt)
            putExtra("use_vib", alarm.useVibration)
            putExtra("ringtone_uri", alarm.ringtoneUri)
        }
        
        val pi = PendingIntent.getBroadcast(
            ctx, alarm.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Convert to 24-hour format
        val h24 = when {
            alarm.isAm && alarm.hour == 12 -> 0
            !alarm.isAm && alarm.hour != 12 -> alarm.hour + 12
            else -> alarm.hour
        }
        
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h24)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
    }
    
    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pi?.let { am.cancel(it) }
    }
    
    fun stopAlarm(ctx: Context) {
        cancel(ctx)
        AlarmStore.save(ctx, null)
        
        val stopIntent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_STOPPED
        }
        ctx.sendBroadcast(stopIntent)
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM SERVICE - Plays sound and vibrates
// ─────────────────────────────────────────────────────────────

class AlarmRingingService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var vibrationThread: Thread? = null
    private var isRinging = false
    
    companion object {
        var isServiceRunning = false
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmScheduler.ACTION_ALARM_TRIGGERED -> {
                val useVib = intent.getBooleanExtra("use_vib", true)
                val useBt = intent.getBooleanExtra("use_bt", true)
                val ringtoneUri = intent.getStringExtra("ringtone_uri")
                
                startRinging(ringtoneUri, useBt, useVib)
                createRingingNotification()
                
                // Auto-stop after 2 minutes
                Handler(Looper.getMainLooper()).postDelayed({
                    stopSelf()
                }, AlarmScheduler.RING_DURATION)
            }
            AlarmScheduler.ACTION_ALARM_STOPPED -> {
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    private fun startRinging(ringtoneUri: String?, useBt: Boolean, useVib: Boolean) {
        try {
            val uri = if (ringtoneUri != null) Uri.parse(ringtoneUri) 
                else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@AlarmRingingService, uri)
                isLooping = true
                prepare()
                start()
            }
            
            if (useVib) {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                        .defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                
                isRinging = true
                vibrationThread = Thread {
                    val pattern = longArrayOf(0, 1000, 1000)
                    while (isRinging) {
                        vibrator?.vibrate(
                            VibrationEffect.createWaveform(pattern, 0)
                        )
                        Thread.sleep(2000)
                    }
                }.apply { start() }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun createRingingNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "alarm_ringing_channel"
            val nm = getSystemService(NotificationManager::class.java)
            
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Alarm Ringing", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
            
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            
            val stopIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_ALARM_STOPPED
            }
            val stopPendingIntent = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            
            val notification = Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ Alarm Ringing")
                .setContentText("Tap to stop")
                .setPriority(Notification.PRIORITY_HIGH)
                .setFullScreenIntent(pendingIntent, true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setOngoing(true)
                .build()
            
            startForeground(1, notification)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRinging = false
        isServiceRunning = false
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaPlayer = null
        vibrationThread?.interrupt()
        vibrator?.cancel()
        stopForeground(true)
    }
}

// ─────────────────────────────────────────────────────────────
//  BROADCAST RECEIVER
// ─────────────────────────────────────────────────────────────

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_ALARM_TRIGGERED -> {
                val useBt = intent.getBooleanExtra("use_bt", true)
                val useVib = intent.getBooleanExtra("use_vib", true)
                val ringtoneUri = intent.getStringExtra("ringtone_uri")
                
                val serviceIntent = Intent(ctx, AlarmRingingService::class.java).apply {
                    action = AlarmScheduler.ACTION_ALARM_TRIGGERED
                    putExtra("use_bt", useBt)
                    putExtra("use_vib", useVib)
                    putExtra("ringtone_uri", ringtoneUri)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(serviceIntent)
                } else {
                    ctx.startService(serviceIntent)
                }
            }
            
            AlarmScheduler.ACTION_ALARM_STOPPED -> {
                val stopIntent = Intent(ctx, AlarmRingingService::class.java).apply {
                    action = AlarmScheduler.ACTION_ALARM_STOPPED
                }
                ctx.stopService(stopIntent)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  THEME
// ─────────────────────────────────────────────────────────────

private val BgDark = Color(0xFF1C1C1E)
private val Surface1 = Color(0xFF2C2C2E)
private val Surface2 = Color(0xFF3A3A3C)
private val AccentBright = Color(0xFFAEAEB2)
private val TextPrimary = Color(0xFFEEEEF0)
private val TextSecondary = Color(0xFF636366)
private val ActiveGreen = Color(0xFF30D158)

// ─────────────────────────────────────────────────────────────
//  MAIN ACTIVITY
// ─────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        setContent { TemporaryAlarmApp() }
    }
}

// ─────────────────────────────────────────────────────────────
//  MAIN UI
// ─────────────────────────────────────────────────────────────

@Composable
fun TemporaryAlarmApp() {
    val ctx = LocalContext.current
    var alarm by remember { mutableStateOf(AlarmStore.load(ctx)) }
    var showEditor by remember { mutableStateOf(false) }
    val isRinging = AlarmRingingService.isServiceRunning

    fun refresh() { alarm = AlarmStore.load(ctx) }

    Box(Modifier.fillMaxSize().background(BgDark)) {

        Column(Modifier.fillMaxSize()) {

            // Header
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Temporary Alarm", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (alarm != null) "Alarm set for ${alarm?.displayTime()}" else "No alarm set",
                        color = TextSecondary, fontSize = 14.sp
                    )
                }
                if (alarm == null) {
                    IconButton(onClick = { showEditor = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Set Alarm", tint = AccentBright)
                    }
                }
            }

            Divider(color = Surface2, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))

            if (alarm == null) {
                // Empty state
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⏰", fontSize = 64.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("No alarm set", color = TextSecondary, fontSize = 18.sp)
                        Text("Tap + to set one", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                // Active alarm display
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(32.dp))
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Surface1),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                alarm!!.displayTime(),
                                color = if (isRinging) ActiveGreen else TextPrimary,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Spacer(Modifier.height(16.dp))
                            
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (alarm!!.useBt) {
                                    Text("🎧", fontSize = 24.sp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (alarm!!.useVibration) {
                                    Text("📳", fontSize = 24.sp)
                                }
                            }
                            
                            Spacer(Modifier.height(8.dp))
                            Text(alarm!!.ringtoneName, color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                    
                    Spacer(Modifier.height(24.dp))
                    
                    if (isRinging) {
                        Button(
                            onClick = {
                                AlarmScheduler.stopAlarm(ctx)
                                refresh()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("STOP RINGING", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                AlarmScheduler.cancel(ctx)
                                AlarmStore.save(ctx, null)
                                refresh()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Surface2),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("CANCEL ALARM", color = TextPrimary, fontSize = 18.sp)
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    OutlinedButton(
                        onClick = { showEditor = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Edit Alarm")
                    }
                }
            }
        }

        // Editor
        if (showEditor) {
            AlarmEditor(
                existing = alarm,
                onSave = { newAlarm ->
                    AlarmStore.save(ctx, newAlarm)
                    if (newAlarm.isActive) {
                        AlarmScheduler.schedule(ctx, newAlarm)
                    }
                    refresh()
                    showEditor = false
                },
                onDismiss = { showEditor = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  RINGTONE PICKER
// ─────────────────────────────────────────────────────────────

@Composable
fun RingtonePicker(
    selectedUri: String?,
    selectedName: String,
    onRingtoneSelected: (String?, String) -> Unit
) {
    val ctx = LocalContext.current
    var ringtoneName by remember { mutableStateOf(selectedName) }
    
    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            val name = RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) ?: "Selected"
            onRingtoneSelected(uri.toString(), name)
            ringtoneName = name
        }
    }
    
    Column {
        Text("Sound", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface2, RoundedCornerShape(8.dp))
                .clickable {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Sound")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            if (selectedUri != null) Uri.parse(selectedUri) else null)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    }
                    ringtoneLauncher.launch(intent)
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎵", fontSize = 20.sp, color = AccentBright)
            Spacer(Modifier.width(12.dp))
            Text(ringtoneName, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AccentBright)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM EDITOR
// ─────────────────────────────────────────────────────────────

@Composable
fun AlarmEditor(
    existing: AlarmData?,
    onSave: (AlarmData) -> Unit,
    onDismiss: () -> Unit
) {
    var hour by remember { mutableStateOf(existing?.hour ?: 8) }
    var minute by remember { mutableStateOf(existing?.minute ?: 0) }
    var isAm by remember { mutableStateOf(existing?.isAm ?: true) }
    var useBt by remember { mutableStateOf(existing?.useBt ?: true) }
    var useVib by remember { mutableStateOf(existing?.useVibration ?: true) }
    var ringtoneUri by remember { mutableStateOf(existing?.ringtoneUri) }
    var ringtoneName by remember { mutableStateOf(existing?.ringtoneName ?: "Default") }

    Box(
        Modifier.fillMaxSize().background(Color(0xBB000000)).clickable { onDismiss() }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {

                Text(
                    if (existing != null) "Edit Alarm" else "New Alarm",
                    color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(20.dp))

                // Time picker
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    NumberPicker("Hour", hour, 1, 12) { hour = it }
                    Text(":", color = TextPrimary, fontSize = 28.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    NumberPicker("Min", minute, 0, 59) { minute = it }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        AmPmBtn("AM", isAm) { isAm = true }
                        Spacer(Modifier.height(6.dp))
                        AmPmBtn("PM", !isAm) { isAm = false }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Ringtone picker
                RingtonePicker(
                    selectedUri = ringtoneUri,
                    selectedName = ringtoneName,
                    onRingtoneSelected = { uri, name ->
                        ringtoneUri = uri
                        ringtoneName = name
                    }
                )

                Spacer(Modifier.height(16.dp))

                // Output options
                Text("Output", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useBt,
                        onCheckedChange = { useBt = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentBright,
                            uncheckedColor = TextSecondary
                        )
                    )
                    Text("🎧 BT / Earphones", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useVib,
                        onCheckedChange = { useVib = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentBright,
                            uncheckedColor = TextSecondary
                        )
                    )
                    Text("📳 Vibration", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(20.dp))

                // Save button
                Button(
                    onClick = {
                        onSave(
                            AlarmData(
                                id = existing?.id ?: 1,
                                hour = hour,
                                minute = minute,
                                isAm = isAm,
                                useBt = useBt,
                                useVibration = useVib,
                                isActive = true,
                                ringtoneUri = ringtoneUri,
                                ringtoneName = ringtoneName
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = useBt || useVib,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface2,
                        disabledContainerColor = Surface2.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Set Alarm", color = TextPrimary, fontSize = 15.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  UI HELPERS
// ─────────────────────────────────────────────────────────────

@Composable
fun AmPmBtn(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Surface2 else BgDark,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(52.dp).height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) TextPrimary else TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
fun NumberPicker(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 10.sp)
        IconButton(onClick = { onValue(if (value < max) value + 1 else min) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = AccentBright)
        }
        Text("%02d".format(value), color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        IconButton(onClick = { onValue(if (value > min) value - 1 else max) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = AccentBright)
        }
    }
}
