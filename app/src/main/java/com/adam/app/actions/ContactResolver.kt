package com.adam.app.actions

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

data class ContactInfo(
    val name: String,
    val phoneNumber: String
)

class ContactResolver(private val context: Context) {

    companion object {
        private const val TAG = "ContactResolver"
    }

    private var cachedContacts: List<ContactInfo>? = null
    private var lastCacheTime = 0L
    private val cacheValidityMs = 5 * 60 * 1000L // 5 minutes

    fun findContact(spokenName: String): ContactInfo? {
        val contacts = getContacts()
        val normalized = spokenName.lowercase().trim()

        // Exact match first
        contacts.find { it.name.lowercase() == normalized }?.let { return it }

        // Contains match
        contacts.find { it.name.lowercase().contains(normalized) }?.let { return it }
        contacts.find { normalized.contains(it.name.lowercase()) }?.let { return it }

        // Fuzzy match using Levenshtein distance
        val bestMatch = contacts
            .map { it to levenshteinDistance(it.name.lowercase(), normalized) }
            .filter { it.second <= 3 } // Max edit distance of 3
            .minByOrNull { it.second }

        return bestMatch?.first
    }

    fun getContactNames(): List<String> {
        return getContacts().map { it.name }
    }

    fun getContacts(): List<ContactInfo> {
        val now = System.currentTimeMillis()
        if (cachedContacts != null && now - lastCacheTime < cacheValidityMs) {
            return cachedContacts!!
        }

        val contacts = mutableListOf<ContactInfo>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val seen = mutableSetOf<String>()
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    val phone = it.getString(phoneIdx) ?: continue
                    // Deduplicate by name (keep first number)
                    if (seen.add(name.lowercase())) {
                        contacts.add(ContactInfo(name, phone))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
        }

        cachedContacts = contacts
        lastCacheTime = now
        Log.d(TAG, "Loaded ${contacts.size} contacts")
        return contacts
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
                }
            }
        }
        return dp[m][n]
    }
}
