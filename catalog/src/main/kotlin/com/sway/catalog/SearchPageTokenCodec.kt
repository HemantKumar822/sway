package com.sway.catalog

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.util.Base64

/**
 * Opaque page continuation codec — AR-2, FR-2, story 3.2.
 *
 * Extractor pagination uses [Page] (url / id / ids / cookies / body) per
 * [org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage.getNextPage]. The
 * port [com.sway.core.model.PagedResult.nextPageToken] is an opaque String;
 * this codec encodes/decodes it as URL-safe Base64 JSON so callers never
 * construct Page strings manually.
 *
 * Tokens are opaque; consumers pass back whatever [encode] produced on the
 * previous page. Blank / malformed tokens decode to null (caller maps to Parse).
 */
internal object SearchPageTokenCodec {

    fun encode(page: Page): String {
        val obj = JSONObject()
        page.url?.let { obj.put("u", it) }
        page.id?.let { obj.put("i", it) }
        val ids = page.ids
        if (ids != null && ids.isNotEmpty()) {
            obj.put("ids", JSONArray(ids))
        }
        val cookies = page.cookies
        if (cookies != null && cookies.isNotEmpty()) {
            val c = JSONObject()
            cookies.forEach { (k, v) -> c.put(k, v) }
            obj.put("c", c)
        }
        page.body?.let { bytes ->
            obj.put("b", Base64.getEncoder().encodeToString(bytes))
        }
        val json = obj.toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    fun decode(token: String?): Page? {
        if (token.isNullOrBlank()) return null
        return try {
            val json = String(Base64.getUrlDecoder().decode(token.trim()), Charsets.UTF_8)
            val obj = JSONObject(json)
            val url = if (obj.has("u")) obj.getString("u") else null
            val id = if (obj.has("i")) obj.getString("i") else null
            val ids: List<String>? = if (obj.has("ids")) {
                val arr = obj.getJSONArray("ids")
                (0 until arr.length()).map { arr.getString(it) }
            } else null
            val cookies: Map<String, String>? = if (obj.has("c")) {
                val c = obj.getJSONObject("c")
                val map = mutableMapOf<String, String>()
                val keys = c.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = c.getString(k)
                }
                map
            } else null
            val body: ByteArray? = if (obj.has("b")) {
                Base64.getDecoder().decode(obj.getString("b"))
            } else null

            // Choose constructor that matches populated fields.
            when {
                url != null || id != null -> Page(url, id, ids, cookies, body)
                ids != null -> Page(ids, cookies)
                body != null -> Page("", body)
                else -> null
            }?.takeIf { Page.isValid(it) }
        } catch (e: Exception) {
            CatalogLog.w("decode page token failed: ${e.javaClass.simpleName} ${e.message?.take(120)} token=${token.take(80)}")
            null
        }
    }
}
