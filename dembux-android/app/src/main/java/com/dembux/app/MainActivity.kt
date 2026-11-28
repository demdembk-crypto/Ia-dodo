package com.dembux.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_ECOUTER = "ecouter"
        private const val PERM_MICRO = 1
    }

    private val prefs by lazy { getSharedPreferences("dembux", MODE_PRIVATE) }
    private var token: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply() }

    private val chat = mutableListOf<Message>()
    private var tts: TextToSpeech? = null
    private var ttsPret = false
    private var reco: SpeechRecognizer? = null
    private var enEcoute = false
    private var enAttente = false
    private var ecouterAuDemarrage = false

    private lateinit var fil: LinearLayout
    private lateinit var defile: ScrollView
    private lateinit var statut: TextView
    private lateinit var micro: ImageButton
    private lateinit var saisie: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        ecouterAuDemarrage = intent?.getBooleanExtra(EXTRA_ECOUTER, false) == true
        if (token == null) ecranCode() else ecranPrincipal()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ECOUTER, false) && token != null && ::micro.isInitialized) { montrer(true); ecouter() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.FRANCE
            ttsPret = true
        }
    }

    override fun onDestroy() {
        reco?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    /* ---------------- Écran du code d'accès ---------------- */

    private fun ecranCode(erreur: String? = null) {
        val col = colonne().apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)) }
        col.addView(texte("Dembux", 34f, true, R.color.accent).apply { gravity = Gravity.CENTER })
        col.addView(texte("Tape ton code d'accès", 16f, false, R.color.gris).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(24))
        })
        val champ = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(getColor(R.color.texte)); setHintTextColor(getColor(R.color.gris))
            hint = "Code"; gravity = Gravity.CENTER; textSize = 20f
            background = getDrawable(R.drawable.carte); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(champ, LinearLayout.LayoutParams(-1, -2))
        val err = texte(erreur ?: "", 14f, false, R.color.ecoute).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        val bouton = Button(this).apply { text = "Entrer"; textSize = 18f }
        col.addView(err)
        col.addView(bouton, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })
        setContentView(col)

        fun valider() {
            val code = champ.text.toString().trim()
            if (code.isEmpty()) return
            bouton.isEnabled = false; err.text = ""
            Thread {
                try {
                    val t = Api.connexion(code)
                    runOnUiThread { token = t; ecranPrincipal() }
                } catch (e: Exception) {
                    runOnUiThread { bouton.isEnabled = true; err.text = e.message ?: "Erreur" }
                }
            }.start()
        }
        bouton.setOnClickListener { valider() }
        champ.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { valider(); true } else false }
    }

    /* ---------------- Écran principal ---------------- */

    private fun ecranPrincipal() {
        val racine = colonne().apply { setPadding(dp(16), dp(16), dp(16), dp(12)) }

        val entete = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        entete.addView(texte("Dembux", 26f, true, R.color.accent), LinearLayout.LayoutParams(0, -2, 1f))
        entete.addView(texte("Se déconnecter", 13f, false, R.color.gris).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                token = null; chat.clear()
                WebStorage.getInstance().deleteAllData()
                web?.destroy(); web = null
                ecranCode()
            }
        })
        racine.addView(entete)

        fil = colonne()
        defile = ScrollView(this).apply { addView(fil); isFillViewport = true }
        racine.addView(defile, LinearLayout.LayoutParams(-1, 0, 1f))

        if (chat.isEmpty()) {
            fil.addView(texte(
                "Appuie sur le micro et pose ta question : le quinté du jour, un cheval, la météo du terrain…",
                15f, false, R.color.gris
            ).apply { setPadding(dp(4), dp(16), dp(4), dp(16)) })
        } else chat.forEach { bulle(it) }

        statut = texte("Appuie et parle", 15f, false, R.color.gris).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        racine.addView(statut)

        micro = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = getDrawable(R.drawable.rond)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(30), dp(30), dp(30), dp(30))
            contentDescription = "Parler à Dembux"
            setOnClickListener { if (enEcoute) reco?.stopListening() else ecouter() }
        }
        val cadre = FrameLayout(this)
        cadre.addView(micro, FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER))
        racine.addView(cadre, LinearLayout.LayoutParams(-1, dp(120)))

        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        saisie = EditText(this).apply {
            hint = "Ou écris ici…"; textSize = 16f
            setTextColor(getColor(R.color.texte)); setHintTextColor(getColor(R.color.gris))
            background = getDrawable(R.drawable.carte); setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { envoyerSaisie(); true } else false }
        }
        ligne.addView(saisie, LinearLayout.LayoutParams(0, -2, 1f))
        ligne.addView(Button(this).apply { text = "Envoyer"; setOnClickListener { envoyerSaisie() } },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        racine.addView(ligne)

        // --- Onglet « Quinté & Suivi » : toute l'interface du site, déjà connectée ---
        web = creerWeb()

        val contenu = FrameLayout(this)
        contenu.addView(web, FrameLayout.LayoutParams(-1, -1))
        contenu.addView(racine, FrameLayout.LayoutParams(-1, -1))
        vueParler = racine

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.carte))
        }
        ongletSite = ongletBouton("Quinté & Suivi") { montrer(false) }
        ongletVoix = ongletBouton("🎙 Parler") { montrer(true) }
        barre.addView(ongletSite, LinearLayout.LayoutParams(0, dp(56), 1f))
        barre.addView(ongletVoix, LinearLayout.LayoutParams(0, dp(56), 1f))

        val ecran = colonne()
        ecran.addView(contenu, LinearLayout.LayoutParams(-1, 0, 1f))
        ecran.addView(barre, LinearLayout.LayoutParams(-1, -2))
        setContentView(ecran)

        val voixDabord = ecouterAuDemarrage || prefs.getBoolean("ongletVoix", false)
        montrer(voixDabord)

        if (ecouterAuDemarrage) { ecouterAuDemarrage = false; micro.post { ecouter() } }
    }

    private var web: WebView? = null
    private var vueParler: View? = null
    private var ongletSite: TextView? = null
    private var ongletVoix: TextView? = null

    private fun ongletBouton(titre: String, action: () -> Unit) = texte(titre, 15f, true, R.color.gris).apply {
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }

    private fun montrer(voix: Boolean) {
        vueParler?.visibility = if (voix) View.VISIBLE else View.GONE
        web?.visibility = if (voix) View.GONE else View.VISIBLE
        ongletVoix?.setTextColor(getColor(if (voix) R.color.accent else R.color.gris))
        ongletSite?.setTextColor(getColor(if (voix) R.color.gris else R.color.accent))
        prefs.edit().putBoolean("ongletVoix", voix).apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun creerWeb(): WebView = WebView(this).apply {
        setBackgroundColor(getColor(R.color.fond))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Donne au site le même accès que l'app : pas besoin de retaper le code.
                val t = token ?: return
                if (!t.matches(Regex("[0-9a-f.]+"))) return
                view.evaluateJavascript(
                    "(function(){var t=JSON.stringify('$t');" +
                        "if(localStorage.getItem('as_token')!==t){localStorage.setItem('as_token',t);location.reload();}})()",
                    null
                )
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // Les liens extérieurs (sources) s'ouvrent dans le navigateur.
                if (req.url.host == Uri.parse(BuildConfig.SERVEUR).host) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                return true
            }
        }
        loadUrl(BuildConfig.SERVEUR)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.visibility == View.VISIBLE && w.canGoBack()) w.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun envoyerSaisie() {
        val t = saisie.text.toString().trim()
        if (t.isEmpty()) return
        saisie.setText("")
        envoyer(t)
    }

    /* ---------------- Voix ---------------- */

    private fun ecouter() {
        if (enAttente) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_MICRO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statut.text = "La reconnaissance vocale n'est pas disponible. Installe ou active l'appli Google."
            return
        }
        tts?.stop()
        if (reco == null) {
            reco = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(ecouteur) }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        enEcoute = true
        majMicro()
        statut.text = "Je t'écoute…"
        reco?.startListening(i)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_MICRO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) ecouter()
            else statut.text = "Autorise le micro pour parler à Dembux (Réglages > Applis > Dembux)."
        }
    }

    private val ecouteur = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val s = 1f + (rmsdB.coerceIn(0f, 10f) / 40f)
            micro.scaleX = s; micro.scaleY = s
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { statut.text = "…" }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { statut.text = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            finEcoute()
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (t.isNullOrEmpty()) statut.text = "Je n'ai rien entendu. Réessaie."
            else envoyer(t)
        }
        override fun onError(error: Int) {
            finEcoute()
            statut.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai rien entendu. Réessaie."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Pas de réseau pour la reconnaissance vocale."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorise le micro pour parler à Dembux."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Le micro est occupé, réessaie."
                else -> "Souci avec le micro (code $error). Réessaie."
            }
        }
    }

    private fun finEcoute() {
        enEcoute = false
        micro.scaleX = 1f; micro.scaleY = 1f
        majMicro()
    }

    private fun majMicro() {
        micro.background.setTint(getColor(if (enEcoute) R.color.ecoute else R.color.accent))
    }

    private fun parler(t: String) {
        if (!ttsPret) return
        val propre = t.replace(Regex("[*#_`>]"), "").replace(Regex("\\s+"), " ").trim()
        tts?.speak(propre, TextToSpeech.QUEUE_FLUSH, null, "dembux")
    }

    /* ---------------- Conversation ---------------- */

    private fun envoyer(question: String) {
        val t = token ?: return ecranCode()
        if (chat.isEmpty()) fil.removeAllViews()
        val m = Message("user", question)
        chat.add(m); bulle(m)
        enAttente = true
        statut.text = "Dembux réfléchit…"
        Thread {
            try {
                val rep = Api.discuter(t, chat, prefs.getString("consigne", "") ?: "")
                runOnUiThread {
                    enAttente = false
                    val r = Message("assistant", rep)
                    chat.add(r); bulle(r)
                    statut.text = "Appuie et parle"
                    parler(rep)
                }
            } catch (e: ErreurDembux) {
                runOnUiThread {
                    enAttente = false
                    chat.removeAt(chat.lastIndex)
                    if (e.nonAutorise) { token = null; ecranCode(e.message) }
                    else { statut.text = e.message; parler(e.message ?: "") }
                }
            } catch (e: Exception) {
                runOnUiThread { enAttente = false; chat.removeAt(chat.lastIndex); statut.text = "Erreur : ${e.message}" }
            }
        }.start()
    }

    private fun bulle(m: Message) {
        val moi = m.role == "user"
        val v = texte(m.content, 16f, false, if (moi) R.color.fond else R.color.texte).apply {
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = getDrawable(R.drawable.carte)?.mutate()?.also { if (moi) it.setTint(getColor(R.color.accent)) }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            gravity = if (moi) Gravity.END else Gravity.START
            if (moi) marginStart = dp(48) else marginEnd = dp(24)
        }
        fil.addView(v, lp)
        defile.post { defile.fullScroll(View.FOCUS_DOWN) }
    }

    /* ---------------- Petits outils ---------------- */

    private fun colonne() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun texte(t: String, taille: Float, gras: Boolean, couleur: Int) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, taille)
        setTextColor(getColor(couleur))
        if (gras) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
