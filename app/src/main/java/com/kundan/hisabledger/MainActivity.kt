package com.kundan.hisabledger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private data class Tx(
    val id: Long,
    var type: String,
    var amount: Double,
    var dateTime: Long,
    var note: String
)

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hisab", MODE_PRIVATE) }
    private val txs = mutableListOf<Tx>()
    private lateinit var dashboard: LinearLayout
    private var filterType = "ALL"

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { writeText(it, csvText()) } }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { writeText(it, backupJson()) } }
    private val pdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let { writePdf(it, pendingPdfStart, pendingPdfEnd, pendingPdfTitle) } }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { readText(it) { s -> restoreJson(s) } } }
    private var pendingPdfStart = 0L
    private var pendingPdfEnd = Long.MAX_VALUE
    private var pendingPdfTitle = "Hisab Ledger Report"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarAppearance()
        load()
        if (prefs.getBoolean("auto_backup", true)) scheduleAutoBackup()
        when {
            prefs.getBoolean("biometric_enabled", false) && canUseBiometric() -> showBiometricLock()
            prefs.getString("pin", "")!!.isNotEmpty() -> showPinDialog()
            else -> buildUi()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::dashboard.isInitialized) applySystemBarAppearance()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            setBackgroundColor(if (isDark()) Color.rgb(15,23,42) else Color.rgb(248,250,252))
        }
        scroll.addView(dashboard)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            dashboard.setPadding(dp(16), bars.top + dp(14), dp(16), bars.bottom + dp(24))
            insets
        }
        ViewCompat.requestApplyInsets(scroll)

        dashboard.addView(tv("Hisab Ledger", 28f, true))
        dashboard.addView(tv("Withdrawal − Deposit", 14f, false).apply { setTextColor(if (isDark()) Color.LTGRAY else Color.DKGRAY) })
        addSpace(10)
        val summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dashboard.addView(summary)
        addSummaryCards(summary)

        addSpace(12)
        dashboard.addView(button("＋ Add Transaction").apply { setOnClickListener { showAddDialog(null) } })
        addSpace(8)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val historyBtn = button("History")
        val reportBtn = button("Reports")
        row.addView(historyBtn, weightLp()); row.addView(reportBtn, weightLp()); dashboard.addView(row)
        historyBtn.setOnClickListener { showHistoryDialog() }
        reportBtn.setOnClickListener { showReportsDialog() }

        addSpace(8)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val backup = button("Backup"); val restore = button("Restore")
        tools.addView(backup, weightLp()); tools.addView(restore, weightLp()); dashboard.addView(tools)
        backup.setOnClickListener { backupLauncher.launch("hisab_backup_${dateKey()}.json") }
        restore.setOnClickListener { restoreLauncher.launch(arrayOf("application/json", "text/plain")) }

        addSpace(8)
        dashboard.addView(button("⚙ Settings").apply { setOnClickListener { showSettings() } })
    }

    private fun addSummaryCards(parent: LinearLayout) {
        parent.removeAllViews()
        val month = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); zeroTime() }
        val dayEnd = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1); zeroTime() }
        val monthEnd = Calendar.getInstance().apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, 1); zeroTime() }
        parent.addView(sectionTitle("TODAY")); parent.addView(cardRow(stats(todayStart(), dayEnd.timeInMillis)))
        parent.addView(sectionTitle("THIS MONTH")); parent.addView(cardRow(stats(month.timeInMillis, monthEnd.timeInMillis)))
        parent.addView(sectionTitle("ALL TIME")); parent.addView(cardRow(stats(0L, Long.MAX_VALUE)))
    }

    private fun stats(start: Long, end: Long): DoubleArray {
        var w = 0.0; var d = 0.0; var wc = 0; var dc = 0
        txs.filter { it.dateTime >= start && it.dateTime < end }.forEach {
            if (it.type == "WITHDRAWAL") { w += it.amount; wc++ } else { d += it.amount; dc++ }
        }
        return doubleArrayOf(w, d, w - d, wc.toDouble(), dc.toDouble())
    }

    private fun cardRow(s: DoubleArray): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(metricCard("Withdrawal", s[0], s[3].toInt(), Color.rgb(22,163,74)), weightLp())
        row.addView(metricCard("Deposit", s[1], s[4].toInt(), Color.rgb(220,38,38)), weightLp())
        row.addView(metricCard("Balance", s[2], 0, Color.rgb(37,99,235)), weightLp())
        return row
    }

    private fun metricCard(name: String, amount: Double, count: Int, color: Int): View {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = dp(2).toFloat(); setContentPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tv(name, 13f, true).apply { setTextColor(color) })
        box.addView(tv("₹${fmt(amount)}", 18f, true))
        box.addView(tv(if (count > 0) "$count entries" else "", 11f, false).apply { setTextColor(Color.GRAY) })
        card.addView(box); return card
    }

    private fun showAddDialog(existing: Tx?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4)) }
        val type = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Withdrawal", "Deposit")) }
        if (existing != null) type.setSelection(if (existing.type == "WITHDRAWAL") 0 else 1)
        val amount = EditText(this).apply { hint = "Amount (₹)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(if (existing != null) fmt(existing.amount) else "") }
        val note = EditText(this).apply { hint = "Remark / Note"; setText(existing?.note ?: "") }
        val date = EditText(this).apply { isFocusable = false; hint = "Date" }
        val time = EditText(this).apply { isFocusable = false; hint = "Time" }
        val cal = Calendar.getInstance(); if (existing != null) cal.timeInMillis = existing.dateTime
        date.setText(SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(cal.time)); time.setText(SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time))
        date.setOnClickListener { DatePickerDialog(this, { _, y, m, d -> cal.set(y,m,d); date.setText(SimpleDateFormat("dd-MM-yyyy",Locale.getDefault()).format(cal.time)) }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show() }
        time.setOnClickListener { TimePickerDialog(this, { _, h, mi -> cal.set(Calendar.HOUR_OF_DAY,h); cal.set(Calendar.MINUTE,mi); time.setText(SimpleDateFormat("hh:mm a",Locale.getDefault()).format(cal.time)) }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show() }
        box.addView(type); box.addView(amount); box.addView(date); box.addView(time); box.addView(note)
        val dlg = MaterialAlertDialogBuilder(this).setTitle(if (existing == null) "Add Transaction" else "Edit Transaction").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dlg.setOnShowListener { dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = amount.text.toString().toDoubleOrNull(); if (a == null || a <= 0) { amount.error = "Enter valid amount"; return@setOnClickListener }
            val t = if (type.selectedItemPosition == 0) "WITHDRAWAL" else "DEPOSIT"
            val tx = existing ?: Tx(System.currentTimeMillis(), t, a, cal.timeInMillis, note.text.toString())
            tx.type=t; tx.amount=a; tx.dateTime=cal.timeInMillis; tx.note=note.text.toString(); if (existing == null) txs.add(tx)
            save(); dlg.dismiss(); refresh()
        } }
        dlg.show()
    }

    private fun showHistoryDialog() {
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(4),dp(12),dp(4)) }
        val filters = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val list = RecyclerView(this).apply { layoutManager=LinearLayoutManager(this@MainActivity) }
        val adapter = TxAdapter(); list.adapter=adapter
        val search=EditText(this).apply{hint="Search remark or amount"}
        val all=button("All"); val w=button("Withdrawal"); val d=button("Deposit")
        filters.addView(all,weightLp()); filters.addView(w,weightLp()); filters.addView(d,weightLp())
        box.addView(search); box.addView(filters); box.addView(list,LinearLayout.LayoutParams(-1,dp(420)))
        val dlg=MaterialAlertDialogBuilder(this).setTitle("Transaction History").setView(box).setNegativeButton("Close",null).setNeutralButton("Export CSV"){_,_->exportLauncher.launch("hisab_${dateKey()}.csv")}.create()
        all.setOnClickListener{filterType="ALL";adapter.notifyDataSetChanged()}; w.setOnClickListener{filterType="WITHDRAWAL";adapter.notifyDataSetChanged()}; d.setOnClickListener{filterType="DEPOSIT";adapter.notifyDataSetChanged()}
        search.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){adapter.query=s?.toString().orEmpty();adapter.notifyDataSetChanged()};override fun afterTextChanged(s:android.text.Editable?){}})
        dlg.show()
    }

    private fun showReportsDialog() {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        box.addView(button("📅 Custom Date Report").apply{setOnClickListener{showCustomDateReport()}})
        box.addView(button("🗓 Monthly Calendar View").apply{setOnClickListener{showCalendarView()}})
        box.addView(button("📄 PDF Report – This Month").apply{setOnClickListener{preparePdfForMonth()}})
        box.addView(button("📄 PDF Report – All Time").apply{setOnClickListener{preparePdfForAll()}})
        val month=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);zeroTime()};val end=Calendar.getInstance().apply{add(Calendar.MONTH,1);set(Calendar.DAY_OF_MONTH,1);zeroTime()}
        val m=stats(month.timeInMillis,end.timeInMillis);val all=stats(0,Long.MAX_VALUE)
        box.addView(tv("\nTHIS MONTH\nWithdrawal: ₹${fmt(m[0])} (${m[3].toInt()})\nDeposit: ₹${fmt(m[1])} (${m[4].toInt()})\nBalance: ₹${fmt(m[2])}\n\nALL TIME\nWithdrawal: ₹${fmt(all[0])}\nDeposit: ₹${fmt(all[1])}\nBalance: ₹${fmt(all[2])}",14f,false))
        MaterialAlertDialogBuilder(this).setTitle("Reports").setView(box).setPositiveButton("Close",null).show()
    }

    private fun showCustomDateReport() {
        pickDate("From date") { start -> pickDate("To date", start) { end ->
            val s=Calendar.getInstance().apply{timeInMillis=start;zeroTime()}.timeInMillis
            val e=Calendar.getInstance().apply{timeInMillis=end;add(Calendar.DAY_OF_MONTH,1);zeroTime()}.timeInMillis
            if(e<=s){Toast.makeText(this,"To date must be after From date",Toast.LENGTH_SHORT).show();return@pickDate}
            val x=stats(s,e)
            val title="${dateOnly(s)} to ${dateOnly(e-1)}"
            MaterialAlertDialogBuilder(this).setTitle("Custom Date Report").setMessage("$title\n\nWithdrawal: ₹${fmt(x[0])}\nWithdrawal count: ${x[3].toInt()}\n\nDeposit: ₹${fmt(x[1])}\nDeposit count: ${x[4].toInt()}\n\nBalance: ₹${fmt(x[2])}")
                .setNegativeButton("Close",null).setPositiveButton("PDF"){_,_->pendingPdfStart=s;pendingPdfEnd=e;pendingPdfTitle="Custom Report $title";pdfLauncher.launch("hisab_custom_${dateKey()}.pdf")}.show()
        }}
    }

    private fun showCalendarView() {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0)}
        val calView=CalendarView(this);val detail=TextView(this).apply{setPadding(dp(8),dp(10),dp(8),dp(10));textSize=14f}
        box.addView(calView);box.addView(detail)
        fun update(ms:Long){val c=Calendar.getInstance().apply{timeInMillis=ms;zeroTime()};val end=Calendar.getInstance().apply{timeInMillis=c.timeInMillis;add(Calendar.DAY_OF_MONTH,1)};val x=stats(c.timeInMillis,end.timeInMillis);val rows=txs.filter{it.dateTime>=c.timeInMillis&&it.dateTime<end.timeInMillis}.sortedByDescending{it.dateTime};val sb=StringBuilder("${dateOnly(c.timeInMillis)}\nWithdrawal ₹${fmt(x[0])} (${x[3].toInt()})\nDeposit ₹${fmt(x[1])} (${x[4].toInt()})\nBalance ₹${fmt(x[2])}\n\n");rows.forEach{sb.append(if(it.type=="WITHDRAWAL")"🟢 " else "🔴 ").append("₹${fmt(it.amount)}  ").append(SimpleDateFormat("hh:mm a",Locale.getDefault()).format(Date(it.dateTime))).append(if(it.note.isNotBlank())"  ${it.note}" else "").append("\n")};detail.text=sb.toString()}
        update(System.currentTimeMillis());calView.setOnDateChangeListener{_,y,m,d->val c=Calendar.getInstance().apply{set(y,m,d);zeroTime()};update(c.timeInMillis)}
        MaterialAlertDialogBuilder(this).setTitle("Monthly Calendar View").setView(box).setPositiveButton("Close",null).show()
    }

    private fun pickDate(title:String, initial:Long=System.currentTimeMillis(), done:(Long)->Unit){val c=Calendar.getInstance().apply{timeInMillis=initial};DatePickerDialog(this,{_,y,m,d->c.set(y,m,d);c.zeroTime();done(c.timeInMillis)},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).apply{setTitle(title);show()}}

    private fun preparePdfForMonth(){val s=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);zeroTime()};val e=Calendar.getInstance().apply{add(Calendar.MONTH,1);set(Calendar.DAY_OF_MONTH,1);zeroTime()};pendingPdfStart=s.timeInMillis;pendingPdfEnd=e.timeInMillis;pendingPdfTitle="Monthly Report ${SimpleDateFormat("MMMM yyyy",Locale.getDefault()).format(s.time)}";pdfLauncher.launch("hisab_month_${dateKey()}.pdf")}
    private fun preparePdfForAll(){pendingPdfStart=0;pendingPdfEnd=Long.MAX_VALUE;pendingPdfTitle="All Time Report";pdfLauncher.launch("hisab_all_${dateKey()}.pdf")}

    private fun writePdf(uri:Uri,start:Long,end:Long,title:String){try{val doc=PdfDocument();val pageW=595;val pageH=842;var pageNo=1;var page=doc.startPage(PdfDocument.PageInfo.Builder(pageW,pageH,pageNo).create());var canvas=page.canvas;val paint=Paint(Paint.ANTI_ALIAS_FLAG);paint.textSize=18f;paint.color=Color.BLACK;canvas.drawText(title,32f,42f,paint);paint.textSize=11f;var y=68f;val x=stats(start,end);canvas.drawText("Withdrawal: ₹${fmt(x[0])} (${x[3].toInt()})",32f,y,paint);y+=18;canvas.drawText("Deposit: ₹${fmt(x[1])} (${x[4].toInt()})",32f,y,paint);y+=18;canvas.drawText("Balance (Withdrawal − Deposit): ₹${fmt(x[2])}",32f,y,paint);y+=28;paint.textSize=10f;canvas.drawText("Date/Time",32f,y,paint);canvas.drawText("Type",150f,y,paint);canvas.drawText("Amount",275f,y,paint);canvas.drawText("Remark",365f,y,paint);y+=16;txs.filter{it.dateTime>=start&&it.dateTime<end}.sortedBy{it.dateTime}.forEach{t->if(y>810){doc.finishPage(page);pageNo++;page=doc.startPage(PdfDocument.PageInfo.Builder(pageW,pageH,pageNo).create());canvas=page.canvas;y=35f};canvas.drawText(SimpleDateFormat("dd-MM-yyyy HH:mm",Locale.getDefault()).format(Date(t.dateTime)),32f,y,paint);canvas.drawText(t.type,150f,y,paint);canvas.drawText("₹${fmt(t.amount)}",275f,y,paint);canvas.drawText(t.note.take(28),365f,y,paint);y+=16};doc.finishPage(page);contentResolver.openOutputStream(uri)?.use{doc.writeTo(it)};doc.close();Toast.makeText(this,"PDF saved",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this,"PDF export failed",Toast.LENGTH_LONG).show()}}

    private fun showSettings(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0)}
        val dark=Switch(this).apply{text="Dark Mode";isChecked=isDark()};val auto=Switch(this).apply{text="Auto Backup (daily)";isChecked=prefs.getBoolean("auto_backup",true)};val bio=Switch(this).apply{text="Fingerprint / Biometric Lock";isChecked=prefs.getBoolean("biometric_enabled",false);isEnabled=canUseBiometric()};val pin=button("Set / Change PIN");val clear=button("Delete All Data")
        box.addView(dark);box.addView(auto);box.addView(bio);box.addView(pin);box.addView(clear)
        if(!canUseBiometric())box.addView(tv("Biometric is not available on this device.",12f,false).apply{setTextColor(Color.GRAY)})
        val dlg=MaterialAlertDialogBuilder(this).setTitle("Settings").setView(box).setNegativeButton("Close",null).create()
        dark.setOnCheckedChangeListener{_,checked->AppCompatDelegate.setDefaultNightMode(if(checked)AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO);window.decorView.post{applySystemBarAppearance()}}
        auto.setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean("auto_backup",checked).apply();if(checked)scheduleAutoBackup()else WorkManager.getInstance(this).cancelUniqueWork("hisab_auto_backup")}
        bio.setOnCheckedChangeListener{_,checked->if(checked && prefs.getString("pin","").isNullOrBlank()){bio.isChecked=false;Toast.makeText(this,"First set an App PIN, then enable biometric lock",Toast.LENGTH_LONG).show();setPin()}else prefs.edit().putBoolean("biometric_enabled",checked).apply()}
        pin.setOnClickListener{dlg.dismiss();setPin()}
        clear.setOnClickListener{MaterialAlertDialogBuilder(this).setTitle("Delete all transactions?").setMessage("This cannot be undone unless you have a backup.").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->txs.clear();save();refresh()}.show()}
        dlg.show()
    }

    private fun scheduleAutoBackup(){val request=PeriodicWorkRequestBuilder<AutoBackupWorker>(24,TimeUnit.HOURS).build();WorkManager.getInstance(this).enqueueUniquePeriodicWork("hisab_auto_backup",ExistingPeriodicWorkPolicy.KEEP,request)}
    private fun canUseBiometric():Boolean=BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS
    private fun showBiometricLock(){val executor=ContextCompat.getMainExecutor(this);val prompt=BiometricPrompt(this,executor,object:BiometricPrompt.AuthenticationCallback(){override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){buildUi()};override fun onAuthenticationError(errorCode:Int,errString:CharSequence){if(errorCode!=BiometricPrompt.ERROR_NEGATIVE_BUTTON)showPinDialog()};override fun onAuthenticationFailed(){Toast.makeText(this@MainActivity,"Fingerprint not recognized",Toast.LENGTH_SHORT).show()}});val info=BiometricPrompt.PromptInfo.Builder().setTitle("Hisab Ledger").setSubtitle("Unlock with fingerprint").setNegativeButtonText("Use App PIN").build();prompt.authenticate(info)}
    private fun setPin(){val e=EditText(this).apply{hint="4-8 digit PIN";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD};MaterialAlertDialogBuilder(this).setTitle("App PIN").setView(e).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->val p=e.text.toString();if(p.length in 4..8)prefs.edit().putString("pin",p).apply()else Toast.makeText(this,"PIN must be 4-8 digits",Toast.LENGTH_SHORT).show()}.show()}
    private fun showPinDialog(){val e=EditText(this).apply{hint="Enter PIN";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD};MaterialAlertDialogBuilder(this).setTitle("Hisab Ledger Locked").setView(e).setCancelable(false).setPositiveButton("Unlock"){_,_->if(e.text.toString()==prefs.getString("pin",""))buildUi()else{Toast.makeText(this,"Wrong PIN",Toast.LENGTH_SHORT).show();showPinDialog()}}.show()}

    private fun refresh(){if(::dashboard.isInitialized)buildUi()}
    private fun load(){txs.clear();val arr=JSONArray(prefs.getString("data","[]"));for(i in 0 until arr.length()){val o=arr.getJSONObject(i);txs.add(Tx(o.getLong("id"),o.getString("type"),o.getDouble("amount"),o.getLong("dateTime"),o.optString("note","")))}}
    private fun save(){val arr=JSONArray();txs.forEach{t->arr.put(JSONObject().apply{put("id",t.id);put("type",t.type);put("amount",t.amount);put("dateTime",t.dateTime);put("note",t.note)})};prefs.edit().putString("data",arr.toString()).apply()}
    private fun backupJson():String=JSONObject().apply{put("app","Hisab Ledger");put("version",2);put("transactions",JSONArray().also{a->txs.forEach{t->a.put(JSONObject().apply{put("id",t.id);put("type",t.type);put("amount",t.amount);put("dateTime",t.dateTime);put("note",t.note)})}})}.toString(2)
    private fun restoreJson(s:String){try{val arr=JSONObject(s).getJSONArray("transactions");txs.clear();for(i in 0 until arr.length()){val o=arr.getJSONObject(i);txs.add(Tx(o.getLong("id"),o.getString("type"),o.getDouble("amount"),o.getLong("dateTime"),o.optString("note","")))};save();refresh();Toast.makeText(this,"Backup restored",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this,"Invalid backup file",Toast.LENGTH_LONG).show()}}
    private fun csvText():String{val sb=StringBuilder("ID,Type,Amount,Date,Time,Remark\n");val df=SimpleDateFormat("dd-MM-yyyy,hh:mm a",Locale.getDefault());txs.sortedByDescending{it.dateTime}.forEach{t->val p=df.format(Date(t.dateTime)).split(",");sb.append("${t.id},${t.type},${t.amount},${p[0]},${p[1]},\"${t.note.replace("\"","\"\"")}\"\n")};return sb.toString()}
    private fun writeText(uri:Uri,s:String){contentResolver.openOutputStream(uri)?.use{it.write(s.toByteArray())};Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show()}
    private fun readText(uri:Uri,done:(String)->Unit){contentResolver.openInputStream(uri)?.bufferedReader()?.use{done(it.readText())}}
    private fun todayStart():Long=Calendar.getInstance().apply{zeroTime()}.timeInMillis
    private fun dateKey()=SimpleDateFormat("yyyyMMdd_HHmm",Locale.getDefault()).format(Date())
    private fun dateOnly(ms:Long)=SimpleDateFormat("dd-MM-yyyy",Locale.getDefault()).format(Date(ms))
    private fun Calendar.zeroTime(){set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}
    private fun fmt(v:Double)=String.format(Locale.US,"%.2f",v)
    private fun isDark():Boolean{val mode=resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK;return mode==android.content.res.Configuration.UI_MODE_NIGHT_YES}
    private fun applySystemBarAppearance(){val dark=isDark();val bg=if(dark)Color.rgb(15,23,42)else Color.rgb(248,250,252);window.statusBarColor=bg;window.navigationBarColor=bg;WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun tv(s:String,size:Float,bold:Boolean)=TextView(this).apply{text=s;textSize=size;if(bold)setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,dp(3),0,dp(3))}
    private fun sectionTitle(s:String)=tv(s,13f,true).apply{setPadding(dp(4),dp(12),0,dp(3))}
    private fun button(s:String)=MaterialButton(this).apply{text=s;isAllCaps=false;minHeight=dp(48);setPadding(dp(8),0,dp(8),0)}
    private fun weightLp()=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{setMargins(dp(3),dp(3),dp(3),dp(3))}
    private fun addSpace(h:Int){dashboard.addView(Space(this),LinearLayout.LayoutParams(1,dp(h)))}

    inner class TxAdapter:RecyclerView.Adapter<TxAdapter.VH>(){
        var query="";inner class VH(val box:LinearLayout):RecyclerView.ViewHolder(box)
        override fun onCreateViewHolder(p:android.view.ViewGroup,v:Int):VH=VH(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8))})
        override fun getItemCount()=filtered().size
        override fun onBindViewHolder(h:VH,pos:Int){val t=filtered()[pos];h.box.removeAllViews();val color=if(t.type=="WITHDRAWAL")Color.rgb(22,163,74)else Color.rgb(220,38,38);h.box.addView(tv("${t.type}   ₹${fmt(t.amount)}",16f,true).apply{setTextColor(color)});h.box.addView(tv(SimpleDateFormat("dd-MM-yyyy  hh:mm a",Locale.getDefault()).format(Date(t.dateTime)),12f,false));if(t.note.isNotBlank())h.box.addView(tv(t.note,13f,false));val r=LinearLayout(this@MainActivity).apply{gravity=Gravity.END};val edit=button("Edit");val del=button("Delete");r.addView(edit);r.addView(del);h.box.addView(r);edit.setOnClickListener{showAddDialog(t)};del.setOnClickListener{MaterialAlertDialogBuilder(this@MainActivity).setTitle("Delete transaction?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->txs.remove(t);save();notifyDataSetChanged();refresh()}.show()}}
        private fun filtered()=txs.asSequence().filter{filterType=="ALL"||it.type==filterType}.filter{query.isBlank()||it.note.contains(query,true)||fmt(it.amount).contains(query)}.sortedByDescending{it.dateTime}.toList()
    }
}
