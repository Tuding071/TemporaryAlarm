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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
//  DATA MODEL
// ─────────────────────────────────────────────────────────────

enum class AlarmMode { TIME, TIMER }

data class AlarmItem(
    val id: Int,
    val mode: AlarmMode,
    val hour: Int       = 8,
    val minute: Int     = 0,
    val isAm: Boolean   = true,
    val timerH: Int     = 0,
    val timerM: Int     = 0,
    val timerS: Int     = 30,
    val useBt: Boolean      = true,
    val useVibration: Boolean = true,
    val isPinned: Boolean   = false,
    val isActive: Boolean   = false,
    val attempts: Int = 1,
    val currentAttempt: Int = 0,
    val ringtoneUri: String? = null,
    val ringtoneName: String = "Default"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("mode", mode.name)
        put("hour", hour); put("minute", minute); put("isAm", isAm)
        put("timerH", timerH); put("timerM", timerM); put("timerS", timerS)
        put("useBt", useBt); put("useVibration", useVibration)
        put("isPinned", isPinned); put("isActive", isActive)
        put("attempts", attempts); put("currentAttempt", currentAttempt)
        put("ringtoneUri", ringtoneUri); put("ringtoneName", ringtoneName)
    }

    fun displayTime(): String = when (mode) {
        AlarmMode.TIME -> {
            val h = if (hour == 0) 12 else hour
            "%d:%02d %s".format(h, minute, if (isAm) "AM" else "PM")
        }
        AlarmMode.TIMER -> "%02dh %02dm %02ds".format(timerH, timerM, timerS)
    }

    fun modeLabel(): String = if (mode == AlarmMode.TIME) "Time Alarm" else "Timer"

    fun outputLabel(): String = buildString {
        if (useBt) append("🎧 BT")
        if (useBt && useVibration) append(" + ")
        if (useVibration) append("📳 Vib")
        append(" · ${attempts} attempt${if (attempts > 1) "s" else ""}")
        if (currentAttempt > 0) append(" (${currentAttempt}/${attempts})")
    }

    fun ringtoneLabel(): String = ringtoneName

    companion object {
        fun fromJson(j: JSONObject) = AlarmItem(
            id   = j.getInt("id"),
            mode = AlarmMode.valueOf(j.getString("mode")),
            hour = j.optInt("hour", 8), minute = j.optInt("minute", 0),
            isAm = j.optBoolean("isAm", true),
            timerH = j.optInt("timerH", 0), timerM = j.optInt("timerM", 0),
            timerS = j.optInt("timerS", 30),
            useBt        = j.optBoolean("useBt", true),
            useVibration = j.optBoolean("useVibration", true),
            isPinned     = j.optBoolean("isPinned", false),
            isActive     = j.optBoolean("isActive", false),
            attempts     = j.optInt("attempts", 1),
            currentAttempt = j.optInt("currentAttempt", 0),
            ringtoneUri  = j.optString("ringtoneUri", null),
            ringtoneName = j.optString("ringtoneName", "Default")
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  STORAGE
// ─────────────────────────────────────────────────────────────

object AlarmStore {
    private const val PREF = "tmp_alarm_prefs"
    private const val KEY  = "alarms_json"

    fun load(ctx: Context): MutableList<AlarmItem> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length())
            .map { AlarmItem.fromJson(arr.getJSONObject(it)) }
            .toMutableList()
    }

    fun save(ctx: Context, alarms: List<AlarmItem>) {
        val arr = JSONArray().also { a -> alarms.forEach { a.put(it.toJson()) } }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM SCHEDULER
// ─────────────────────────────────────────────────────────────

object AlarmScheduler {
    const val CHANNEL_SOUND  = "tmp_alarm_sound"
    const val CHANNEL_SILENT = "tmp_alarm_silent"
    const val ACTION_ALARM_TRIGGERED = "com.temporaryalarm.app.ALARM_TRIGGERED"
    const val ACTION_ATTEMPT_FINISHED = "com.temporaryalarm.app.ATTEMPT_FINISHED"
    const val ACTION_ALARM_STOPPED = "com.temporaryalarm.app.ALARM_STOPPED"
    
    const val RING_DURATION = 2 * 60 * 1000L
    private const val PAUSE_DURATION = 2 * 60 * 1000L

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Using MEDIA channel - this follows media volume and routes to BT
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)  // Use MEDIA instead of ALARM
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOUND, "Temporary Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(soundUri, attrs)
                enableVibration(false)
                setBypassDnd(true)
            }
        )
        
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "Temporary Alarm Silent", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
            }
        )
    }

    fun schedule(ctx: Context, alarm: AlarmItem) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancel(ctx, alarm.id)
        scheduleAttempt(ctx, alarm, 0)
    }
    
    private fun scheduleAttempt(ctx: Context, alarm: AlarmItem, attemptNumber: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGERED
            putExtra("alarm_id", alarm.id)
            putExtra("use_bt", alarm.useBt)
            putExtra("use_vib", alarm.useVibration)
            putExtra("attempt", attemptNumber + 1)
            putExtra("total_attempts", alarm.attempts)
            putExtra("ringtone_uri", alarm.ringtoneUri)
        }
        
        val pi = PendingIntent.getBroadcast(
            ctx, alarm.id * 100 + attemptNumber, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerMs: Long = when (alarm.mode) {
            AlarmMode.TIME -> {
                val h24 = when {
                    alarm.isAm && alarm.hour == 12 -> 0
                    !alarm.isAm && alarm.hour != 12 -> alarm.hour + 12
                    else -> alarm.hour
                }
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h24)
                    set(Calendar.MINUTE, alarm.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis())
                        add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
            }
            AlarmMode.TIMER ->
                System.currentTimeMillis() +
                        alarm.timerH * 3_600_000L +
                        alarm.timerM * 60_000L +
                        alarm.timerS * 1_000L
        }

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
    }
    
    fun scheduleNextAttempt(ctx: Context, alarm: AlarmItem, currentAttempt: Int) {
        if (currentAttempt < alarm.attempts) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val intent = Intent(ctx, AlarmReceiver::class.java).apply {
                action = ACTION_ALARM_TRIGGERED
                putExtra("alarm_id", alarm.id)
                putExtra("use_bt", alarm.useBt)
                putExtra("use_vib", alarm.useVibration)
                putExtra("attempt", currentAttempt + 1)
                putExtra("total_attempts", alarm.attempts)
                putExtra("ringtone_uri", alarm.ringtoneUri)
            }
            
            val pi = PendingIntent.getBroadcast(
                ctx, alarm.id * 100 + currentAttempt, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + PAUSE_DURATION,
                pi
            )
        } else {
            val updated = alarm.copy(isActive = false, currentAttempt = 0)
            val alarms = AlarmStore.load(ctx)
            AlarmStore.save(ctx, alarms.map { if (it.id == alarm.id) updated else it })
        }
    }
    
    fun stopAlarm(ctx: Context, alarmId: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0..10) {
            val pi = PendingIntent.getBroadcast(
                ctx, alarmId * 100 + i, 
                Intent(ctx, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pi?.let { am.cancel(it) }
        }
        
        val alarms = AlarmStore.load(ctx)
        AlarmStore.save(ctx, alarms.map { 
            if (it.id == alarmId) it.copy(isActive = false, currentAttempt = 0) else it 
        })
        
        val stopIntent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_STOPPED
            putExtra("alarm_id", alarmId)
        }
        ctx.sendBroadcast(stopIntent)
    }

    fun cancel(ctx: Context, alarmId: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0..10) {
            val pi = PendingIntent.getBroadcast(
                ctx, alarmId * 100 + i, 
                Intent(ctx, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pi?.let { am.cancel(it) }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM SERVICE - Uses MediaPlayer in background
// ─────────────────────────────────────────────────────────────

class AlarmRingingService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var vibrator: Vibrator? = null
    private var vibrationThread: Thread? = null
    private var isRinging = false
    private var alarmId = -1
    
    companion object {
        var currentRingingAlarmId = -1
            private set
        
        fun isRinging(alarmId: Int): Boolean = currentRingingAlarmId == alarmId
    }
    
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmScheduler.ACTION_ALARM_TRIGGERED -> {
                alarmId = intent.getIntExtra("alarm_id", -1)
                val useVib = intent.getBooleanExtra("use_vib", true)
                val useBt = intent.getBooleanExtra("use_bt", true)
                val ringtoneUri = intent.getStringExtra("ringtone_uri")
                val attempt = intent.getIntExtra("attempt", 1)
                val totalAttempts = intent.getIntExtra("total_attempts", 1)
                
                currentRingingAlarmId = alarmId
                startRinging(ringtoneUri, useBt, useVib)
                
                createRingingNotification(attempt, totalAttempts)
                
                // Auto-stop after 2 minutes
                Handler(Looper.getMainLooper()).postDelayed({
                    if (currentRingingAlarmId == alarmId) {
                        stopSelf()
                    }
                }, AlarmScheduler.RING_DURATION)
            }
            AlarmScheduler.ACTION_ALARM_STOPPED -> {
                val stoppedAlarmId = intent.getIntExtra("alarm_id", -1)
                if (stoppedAlarmId == alarmId || stoppedAlarmId == currentRingingAlarmId) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }
    
    private fun startRinging(ringtoneUri: String?, useBt: Boolean, useVib: Boolean) {
        try {
            // Force audio to route through media channel
            if (useBt) {
                // This will automatically route to connected BT device
                audioManager?.mode = AudioManager.MODE_NORMAL
                audioManager?.isSpeakerphoneOn = false
            }
            
            val uri = if (ringtoneUri != null) Uri.parse(ringtoneUri) 
                else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)  // MEDIA usage routes to BT
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
                    while (isRinging && currentRingingAlarmId == alarmId) {
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
    
    private fun createRingingNotification(attempt: Int, totalAttempts: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "alarm_ringing_channel"
            val nm = getSystemService(NotificationManager::class.java)
            
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Ringing Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(null, null)
                        enableVibration(false)
                        setBypassDnd(true)
                    }
                )
            }
            
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            
            val stopIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_ALARM_STOPPED
                putExtra("alarm_id", alarmId)
            }
            val stopPendingIntent = PendingIntent.getBroadcast(this, alarmId, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            
            val notification = Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ Temporary Alarm")
                .setContentText("Attempt $attempt of $totalAttempts")
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setFullScreenIntent(pendingIntent, true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setOngoing(true)
                .build()
            
            startForeground(1000 + alarmId, notification)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRinging = false
        currentRingingAlarmId = -1
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaPlayer = null
        vibrationThread?.interrupt()
        vibrator?.cancel()
        audioManager?.mode = AudioManager.MODE_NORMAL
        
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
                val alarmId = intent.getIntExtra("alarm_id", -1)
                val useBt = intent.getBooleanExtra("use_bt", true)
                val useVib = intent.getBooleanExtra("use_vib", true)
                val attempt = intent.getIntExtra("attempt", 1)
                val totalAttempts = intent.getIntExtra("total_attempts", 1)
                val ringtoneUri = intent.getStringExtra("ringtone_uri")
                
                if (alarmId != -1) {
                    val alarms = AlarmStore.load(ctx)
                    AlarmStore.save(ctx, alarms.map {
                        if (it.id == alarmId) it.copy(currentAttempt = attempt) else it
                    })
                    
                    val serviceIntent = Intent(ctx, AlarmRingingService::class.java).apply {
                        action = AlarmScheduler.ACTION_ALARM_TRIGGERED
                        putExtra("alarm_id", alarmId)
                        putExtra("use_bt", useBt)
                        putExtra("use_vib", useVib)
                        putExtra("attempt", attempt)
                        putExtra("total_attempts", totalAttempts)
                        putExtra("ringtone_uri", ringtoneUri)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(serviceIntent)
                    } else {
                        ctx.startService(serviceIntent)
                    }
                    
                    if (attempt < totalAttempts) {
                        val alarm = alarms.find { it.id == alarmId }
                        alarm?.let {
                            AlarmScheduler.scheduleNextAttempt(ctx, it, attempt)
                        }
                    }
                }
            }
            
            AlarmScheduler.ACTION_ALARM_STOPPED -> {
                val alarmId = intent.getIntExtra("alarm_id", -1)
                if (alarmId != -1) {
                    val stopIntent = Intent(ctx, AlarmRingingService::class.java).apply {
                        action = AlarmScheduler.ACTION_ALARM_STOPPED
                        putExtra("alarm_id", alarmId)
                    }
                    ctx.stopService(stopIntent)
                    AlarmScheduler.stopAlarm(ctx, alarmId)
                }
            }
            
            Intent.ACTION_BOOT_COMPLETED -> {
                val alarms = AlarmStore.load(ctx)
                alarms.filter { it.isActive }.forEach { alarm ->
                    AlarmScheduler.schedule(ctx, alarm)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  THEME
// ─────────────────────────────────────────────────────────────

private val BgDark       = Color(0xFF1C1C1E)
private val Surface1     = Color(0xFF2C2C2E)
private val Surface2     = Color(0xFF3A3A3C)
private val Accent       = Color(0xFF8E8E93)
private val AccentBright = Color(0xFFAEAEB2)
private val TextPrimary  = Color(0xFFEEEEF0)
private val TextSecondary= Color(0xFF636366)
private val ActiveGreen  = Color(0xFF30D158)
private val DangerRed    = Color(0xFFFF453A)

// ─────────────────────────────────────────────────────────────
//  MAIN ACTIVITY
// ─────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlarmScheduler.createChannels(this)

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
//  ROOT COMPOSABLE
// ─────────────────────────────────────────────────────────────

@Composable
fun TemporaryAlarmApp() {
    val ctx = LocalContext.current
    var alarms    by remember { mutableStateOf(AlarmStore.load(ctx)) }
    var showEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AlarmItem?>(null) }

    fun refresh() { alarms = AlarmStore.load(ctx) }

    Box(Modifier.fillMaxSize().background(BgDark)) {

        Column(Modifier.fillMaxSize()) {

            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 32.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Temporary Alarm", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${alarms.count { it.isActive }} active · ${alarms.count { it.isPinned }} pinned",
                        color = TextSecondary, fontSize = 12.sp
                    )
                }
                IconButton(onClick = { editTarget = null; showEditor = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Alarm", tint = AccentBright)
                }
            }

            Divider(color = Surface2, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))

            if (alarms.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⏰", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No alarms yet", color = TextSecondary, fontSize = 16.sp)
                        Text("Tap  +  to add one", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            } else {
                val sorted = alarms.sortedWith(
                    compareByDescending<AlarmItem> { it.isPinned }
                        .thenByDescending { it.isActive }
                )
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    items(sorted, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            onToggle = {
                                val updated = alarms.map {
                                    if (it.id == alarm.id) {
                                        val toggled = it.copy(isActive = !it.isActive, currentAttempt = 0)
                                        if (toggled.isActive) AlarmScheduler.schedule(ctx, toggled)
                                        else AlarmScheduler.cancel(ctx, it.id)
                                        toggled
                                    } else it
                                }
                                AlarmStore.save(ctx, updated); refresh()
                            },
                            onEdit = { editTarget = alarm; showEditor = true },
                            onPin = {
                                val updated = alarms.map {
                                    if (it.id == alarm.id) it.copy(isPinned = !it.isPinned) else it
                                }
                                AlarmStore.save(ctx, updated); refresh()
                            },
                            onDelete = {
                                AlarmScheduler.cancel(ctx, alarm.id)
                                AlarmStore.save(ctx, alarms.filter { it.id != alarm.id })
                                refresh()
                            },
                            onStop = {
                                AlarmScheduler.stopAlarm(ctx, alarm.id)
                                refresh()
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        if (showEditor) {
            AlarmEditor(
                existing = editTarget,
                onSave = { alarm ->
                    val updated = if (editTarget != null)
                        alarms.map { if (it.id == alarm.id) alarm else it }
                    else alarms + alarm
                    if (alarm.isActive) AlarmScheduler.schedule(ctx, alarm)
                    AlarmStore.save(ctx, updated); refresh()
                    showEditor = false
                },
                onDismiss = { showEditor = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun AlarmCard(
    alarm: AlarmItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onStop: () -> Unit
) {
    val isCurrentlyRinging = AlarmRingingService.isRinging(alarm.id)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyRinging) ActiveGreen.copy(alpha = 0.2f) else Surface1
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (alarm.isPinned) {
                            Icon(
                                Icons.Default.Star, contentDescription = null,
                                tint = AccentBright, modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            alarm.displayTime(),
                            color = if (alarm.isActive) ActiveGreen else TextPrimary,
                            fontSize = 30.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        "${alarm.modeLabel()} · ${alarm.ringtoneLabel()}",
                        color = TextSecondary, fontSize = 12.sp
                    )
                }

                if (isCurrentlyRinging) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("STOP", color = Color.White)
                    }
                } else {
                    Switch(
                        checked = alarm.isActive,
                        onCheckedChange = { onToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor  = Color.White,
                            checkedTrackColor  = ActiveGreen,
                            uncheckedThumbColor = Accent,
                            uncheckedTrackColor = Surface2
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Divider(color = Surface2, thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(alarm.outputLabel(), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))

                SmallIconButton(icon = Icons.Default.Star, tint = if (alarm.isPinned) AccentBright else Surface2, onClick = onPin)
                SmallIconButton(icon = Icons.Default.Edit, tint = Accent, onClick = onEdit)
                SmallIconButton(icon = Icons.Default.Delete, tint = DangerRed.copy(alpha = 0.7f), onClick = onDelete)
            }
        }
    }
}

@Composable
fun SmallIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
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
            val name = RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) ?: "Selected Ringtone"
            onRingtoneSelected(uri.toString(), name)
            ringtoneName = name
        }
    }
    
    Column {
        Text("Ringtone", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface2, RoundedCornerShape(8.dp))
                .clickable {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Tone")
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
            Text(
                ringtoneName,
                color = TextPrimary,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Accent)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM EDITOR
// ─────────────────────────────────────────────────────────────

@Composable
fun AlarmEditor(
    existing: AlarmItem?,
    onSave: (AlarmItem) -> Unit,
    onDismiss: () -> Unit
) {
    var mode   by remember { mutableStateOf(existing?.mode ?: AlarmMode.TIME) }
    var hour   by remember { mutableStateOf(existing?.hour ?: 8) }
    var minute by remember { mutableStateOf(existing?.minute ?: 0) }
    var isAm   by remember { mutableStateOf(existing?.isAm ?: true) }
    var timerH by remember { mutableStateOf(existing?.timerH ?: 0) }
    var timerM by remember { mutableStateOf(existing?.timerM ?: 0) }
    var timerS by remember { mutableStateOf(existing?.timerS ?: 30) }
    var useBt  by remember { mutableStateOf(existing?.useBt ?: true) }
    var useVib by remember { mutableStateOf(existing?.useVibration ?: true) }
    var attempts by remember { mutableStateOf(existing?.attempts ?: 1) }
    var ringtoneUri by remember { mutableStateOf(existing?.ringtoneUri) }
    var ringtoneName by remember { mutableStateOf(existing?.ringtoneName ?: "Default") }

    val atLeastOne = useBt || useVib

    Box(
        Modifier.fillMaxSize().background(Color(0xBB000000))
            .clickable { onDismiss() }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp, 20.dp, 20.dp, 32.dp)) {

                Text(
                    if (existing != null) "Edit Alarm" else "New Alarm",
                    color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(18.dp))

                Row(Modifier.fillMaxWidth()) {
                    ModeTab("Time Alarm", mode == AlarmMode.TIME) { mode = AlarmMode.TIME }
                    Spacer(Modifier.width(8.dp))
                    ModeTab("Timer",      mode == AlarmMode.TIMER) { mode = AlarmMode.TIMER }
                }
                Spacer(Modifier.height(20.dp))

                if (mode == AlarmMode.TIME) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        NumberPicker("Hour",  hour,   1, 12) { hour   = it }
                        Text(":", color = TextPrimary, fontSize = 28.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        NumberPicker("Min",   minute, 0, 59) { minute = it }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            AmPmBtn("AM",  isAm)  { isAm = true }
                            Spacer(Modifier.height(6.dp))
                            AmPmBtn("PM", !isAm) { isAm = false }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        NumberPicker("Hours", timerH, 0, 23) { timerH = it }
                        Spacer(Modifier.width(10.dp))
                        NumberPicker("Min",   timerM, 0, 59) { timerM = it }
                        Spacer(Modifier.width(10.dp))
                        NumberPicker("Sec",   timerS, 0, 59) { timerS = it }
                    }
                }
                Spacer(Modifier.height(22.dp))

                RingtonePicker(
                    selectedUri = ringtoneUri,
                    selectedName = ringtoneName,
                    onRingtoneSelected = { uri, name ->
                        ringtoneUri = uri
                        ringtoneName = name
                    }
                )
                Spacer(Modifier.height(16.dp))

                Text("Attempts (1-10)", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (attempts > 1) attempts-- },
                        enabled = attempts > 1
                    ) {
                        Text("−", fontSize = 30.sp, color = AccentBright)
                    }
                    
                    Text(
                        attempts.toString(),
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(50.dp)
                    )
                    
                    IconButton(
                        onClick = { if (attempts < 10) attempts++ },
                        enabled = attempts < 10
                    ) {
                        Text("+", fontSize = 30.sp, color = AccentBright)
                    }
                }
                Spacer(Modifier.height(16.dp))

                Text("Output", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutputCheckRow("🎧  BT / Earphones", useBt, enabled = !(useBt && !useVib)) {
                    if (it || useVib) useBt = it
                }
                OutputCheckRow("📳  Vibration", useVib, enabled = !(!useBt && useVib)) {
                    if (it || useBt) useVib = it
                }
                if (!atLeastOne) {
                    Text("Select at least one output", color = DangerRed, fontSize = 11.sp)
                }

                Spacer(Modifier.height(22.dp))

                Button(
                    onClick = {
                        val id = existing?.id ?: System.currentTimeMillis().toInt()
                        onSave(
                            AlarmItem(
                                id = id, mode = mode,
                                hour = hour, minute = minute, isAm = isAm,
                                timerH = timerH, timerM = timerM, timerS = timerS,
                                useBt = useBt, useVibration = useVib,
                                isPinned = existing?.isPinned ?: false,
                                isActive = true,
                                attempts = attempts,
                                ringtoneUri = ringtoneUri,
                                ringtoneName = ringtoneName
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = atLeastOne,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface2,
                        disabledContainerColor = Surface2.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save & Activate", color = TextPrimary, fontSize = 15.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  UI HELPERS
// ─────────────────────────────────────────────────────────────

@Composable
fun RowScope.ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(40.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Surface2 else BgDark
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(label, color = if (selected) TextPrimary else TextSecondary, fontSize = 13.sp)
    }
}

@Composable
fun AmPmBtn(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Surface2 else BgDark,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(52.dp).height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) TextPrimary else TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun NumberPicker(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 10.sp)
        IconButton(
            onClick = { onValue(if (value < max) value + 1 else min) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = AccentBright)
        }
        Text(
            "%02d".format(value),
            color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium
        )
        IconButton(
            onClick = { onValue(if (value > min) value - 1 else max) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = AccentBright)
        }
    }
}

@Composable
fun OutputCheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { if (enabled || it) onChecked(it) },
            enabled = enabled || !checked,
            colors = CheckboxDefaults.colors(
                checkedColor = AccentBright,
                checkmarkColor = BgDark,
                uncheckedColor = Accent
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled || checked) TextPrimary else TextSecondary, fontSize = 14.sp)
    }
}
