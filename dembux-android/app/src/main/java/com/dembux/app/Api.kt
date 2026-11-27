package com.dembux.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Erreur lisible pour l'utilisateur. `nonAutorise` = il faut retaper le code. */
class ErreurDembux(message: String, val nonAutorise: Boolean = false) : Exception(message)

data class Message(val role: String, val content: String)

/** Appels au serveur Dembux (même API que la page web). À appeler hors du fil principal. */
object Api {
    private val base = BuildConfig.SERVEUR

    fun connexion(code: String): String {
        val r = post("/api/login", JSONObject().put("code", code), null)
        return r.optString("token").ifEmpty { throw ErreurDembux("Réponse inattendue du serveur.") }
    }

    fun discuter(token: String, messages: List<Message>, consigne: String): String {
        val arr = JSONArray()
        messages.takeLast(12).forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val corps = JSONObject().put("messages", arr).put("consigne", consigne)
        val r = post("/api/chat", corps, token)
        return r.optString("reply").ifEmpty { throw ErreurDembux("Dembux n'a rien répondu.") }
    }

    private fun post(chemin: String, corps: JSONObject, token: String?): JSONObject {
        val c = try {
            URL(base + chemin).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw ErreurDembux("Impossible de joindre Dembux.")
        }
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(corps.toString().toByteArray()) }
            val code = c.responseCode
            val texte = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(texte) } catch (e: Exception) { JSONObject() }
            if (code in 200..299) return json
            val msg = json.optString("error").ifEmpty { "Erreur du serveur ($code)." }
            when (code) {
                401 -> throw ErreurDembux(if (chemin == "/api/login") msg else "Session expirée, retape ton code.", nonAutorise = chemin != "/api/login")
                429 -> throw ErreurDembux("Dembux est très sollicité, attends une minute.")
                else -> throw ErreurDembux(msg)
            }
        } catch (e: ErreurDembux) {
            throw e
        } catch (e: IOException) {
            throw ErreurDembux("Pas de connexion internet ou serveur injoignable.")
        } finally {
            c.disconnect()
        }
    }
}
