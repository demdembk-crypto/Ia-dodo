package com.dembux.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import org.json.JSONObject
import java.text.Normalizer

/**
 * Actions sur le téléphone demandées à Dembux.
 * Le cerveau ajoute à la fin de sa réponse une ligne [[ACTION {...}]] que l'app exécute.
 * Rien n'est envoyé ou appelé sans que l'utilisateur voie l'écran : les SMS et appels
 * s'ouvrent pré-remplis, c'est lui qui appuie sur Envoyer / Appeler.
 */
object Actions {

    /** Consigne envoyée au cerveau (le serveur la limite à 600 caractères au total). */
    const val CONSIGNE =
        "Tu peux agir sur le téléphone. Si on te demande une action, réponds une phrase courte puis ajoute à la toute fin une seule ligne : " +
            "[[ACTION {json}]]. Formats : {\"t\":\"ouvrir\",\"app\":\"nom\"} {\"t\":\"appeler\",\"nom\":\"contact\",\"num\":\"\"} " +
            "{\"t\":\"sms\",\"nom\":\"\",\"num\":\"\",\"texte\":\"\"} {\"t\":\"alarme\",\"h\":7,\"m\":30} {\"t\":\"minuteur\",\"s\":300} " +
            "{\"t\":\"itineraire\",\"lieu\":\"\"} {\"t\":\"musique\",\"q\":\"\"} {\"t\":\"web\",\"q\":\"\"} {\"t\":\"whatsapp\",\"texte\":\"\"}. " +
            "Jamais d'action non demandée."

    private val motif = Regex("\\[\\[\\s*ACTION\\s*(\\{.*?\\})\\s*\\]\\]", RegexOption.DOT_MATCHES_ALL)

    /** Sépare le texte à afficher de l'action éventuelle. */
    fun extraire(reponse: String): Pair<String, JSONObject?> {
        val m = motif.find(reponse) ?: return reponse.trim() to null
        val json = try { JSONObject(m.groupValues[1]) } catch (e: Exception) { null }
        return reponse.replace(m.value, "").trim() to json
    }

    /** Action en attente d'une autorisation (contacts). */
    var enAttente: JSONObject? = null

    /** Exécute l'action. Renvoie un message d'erreur lisible, ou null si tout va bien. */
    fun executer(a: Activity, act: JSONObject, demanderContacts: () -> Unit): String? {
        return try {
            when (act.optString("t")) {
                "ouvrir" -> ouvrirAppli(a, act.optString("app"))
                "appeler" -> {
                    val num = numero(a, act, demanderContacts) ?: return attenteOuInconnu(act)
                    a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))); null
                }
                "sms" -> {
                    val num = numero(a, act, demanderContacts) ?: return attenteOuInconnu(act)
                    a.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)))
                        .putExtra("sms_body", act.optString("texte"))); null
                }
                "alarme" -> {
                    a.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, act.optInt("h", 7))
                        .putExtra(AlarmClock.EXTRA_MINUTES, act.optInt("m", 0))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Dembux")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)); null
                }
                "minuteur" -> {
                    a.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, act.optInt("s", 60).coerceIn(1, 86400))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Dembux")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)); null
                }
                "itineraire" -> {
                    val lieu = act.optString("lieu")
                    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(lieu)))
                    try { a.startActivity(nav) } catch (e: Exception) {
                        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(lieu))))
                    }; null
                }
                "musique" -> {
                    a.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(act.optString("q"))))); null
                }
                "web" -> {
                    a.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(act.optString("q"))))); null
                }
                "whatsapp" -> {
                    a.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://wa.me/?text=" + Uri.encode(act.optString("texte"))))); null
                }
                else -> null
            }
        } catch (e: Exception) {
            "Je n'ai pas pu le faire sur ton téléphone."
        }
    }

    private fun attenteOuInconnu(act: JSONObject): String? =
        if (enAttente === act) null else "Je ne trouve pas ce contact dans ton téléphone."

    private fun numero(a: Activity, act: JSONObject, demanderContacts: () -> Unit): String? {
        val num = act.optString("num").filter { it.isDigit() || it == '+' }
        if (num.length >= 3) return num
        val nom = act.optString("nom").trim()
        if (nom.isEmpty()) return null
        if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            enAttente = act
            demanderContacts()
            return null
        }
        val cible = simple(nom)
        a.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            var partiel: String? = null
            while (c.moveToNext()) {
                val n = simple(c.getString(0) ?: continue)
                val tel = c.getString(1) ?: continue
                if (n == cible) return tel
                if (partiel == null && (n.contains(cible) || cible.contains(n))) partiel = tel
            }
            return partiel
        }
        return null
    }

    private fun ouvrirAppli(a: Activity, nom: String): String? {
        val cible = simple(nom)
        if (cible.isEmpty()) return "Dis-moi quelle appli ouvrir."
        val pm = a.packageManager
        val lanceurs = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val trouve = lanceurs.firstOrNull { simple(it.loadLabel(pm).toString()) == cible }
            ?: lanceurs.firstOrNull { simple(it.loadLabel(pm).toString()).contains(cible) }
            ?: lanceurs.firstOrNull { cible.contains(simple(it.loadLabel(pm).toString())) && simple(it.loadLabel(pm).toString()).length >= 3 }
            ?: return "Je ne trouve pas l'appli « $nom » sur ton téléphone."
        val i = pm.getLaunchIntentForPackage(trouve.activityInfo.packageName)
            ?: return "Je ne peux pas ouvrir « $nom »."
        a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    private fun simple(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
