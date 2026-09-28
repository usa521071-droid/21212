package org.json

/** JVM test fixture implementing the subset of org.json used by the app.
 * Actual Android builds use platform org.json, NOT this file. It really round-trips JSON.
 */
class JSONObject {
    internal val map: MutableMap<String, Any?>
    constructor() { map = linkedMapOf() }
    constructor(raw: String) { map = (Parser(raw).parse() as? JSONObject)?.map ?: error("Expected JSON object") }
    fun put(key: String, value: Any?): JSONObject { map[key] = value; return this }
    fun has(key: String): Boolean = key in map
    fun optString(key: String, fallback: String = ""): String = map[key]?.toString() ?: fallback
    fun optInt(key: String, fallback: Int = 0): Int = (map[key] as? Number)?.toInt() ?: map[key]?.toString()?.toIntOrNull() ?: fallback
    fun optLong(key: String, fallback: Long = 0): Long = (map[key] as? Number)?.toLong() ?: fallback
    fun optDouble(key: String, fallback: Double = 0.0): Double = (map[key] as? Number)?.toDouble() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = map[key] as? Boolean ?: fallback
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun getJSONObject(key: String): JSONObject = map[key] as JSONObject
    override fun toString(): String = map.entries.joinToString(",", "{", "}") { escape(it.key) + ":" + write(it.value) }
}
class JSONArray {
    internal val list = mutableListOf<Any?>()
    constructor()
    fun put(value: Any?): JSONArray { list.add(value); return this }
    fun length(): Int = list.size
    fun optString(index: Int, fallback: String = ""): String = list.getOrNull(index)?.toString() ?: fallback
    fun getJSONObject(index: Int): JSONObject = list[index] as JSONObject
    override fun toString(): String = list.joinToString(",", "[", "]") { write(it) }
}
private fun escape(s: String): String = buildString {
    append('"'); for (c in s) when(c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if(c < ' ') append("\\u%04x".format(c.code)) else append(c) }; append('"')
}
private fun write(v: Any?): String = when(v) { null -> "null"; is String -> escape(v); else -> v.toString() }
private class Parser(val s: String) {
    var p=0
    private fun ws() { while(p < s.length && s[p].isWhitespace()) p++ }
    fun parse(): Any? { ws(); val result=value(); ws(); require(p==s.length); return result }
    private fun value(): Any? {
        ws(); require(p<s.length)
        return when(s[p]) {
            '{' -> { p++; val o=JSONObject(); ws(); if(s[p]=='}') { p++; o } else { while(true) { ws(); val k=string(); ws(); require(s[p++]==':'); o.put(k,value()); ws(); if(s[p++]=='}') break; require(s[p-1]==',') }; o } }
            '[' -> { p++; val a=JSONArray(); ws(); if(s[p]==']') { p++; a } else { while(true) { a.put(value()); ws(); if(s[p++]==']') break; require(s[p-1]==',') }; a } }
            '"' -> string()
            't' -> { require(s.startsWith("true",p)); p+=4; true }
            'f' -> { require(s.startsWith("false",p)); p+=5; false }
            'n' -> { require(s.startsWith("null",p)); p+=4; null }
            else -> { val a=p; while(p<s.length && s[p] in "-+0123456789.eE") p++; require(p>a); val n=s.substring(a,p); n.toLongOrNull() ?: n.toDouble() }
        }
    }
    private fun string(): String { require(s[p++]=='"'); return buildString { while(p<s.length) { val c=s[p++]; if(c=='"') return@buildString; if(c!='\\') append(c) else { val e=s[p++]; append(when(e) { 'n'->'\n'; 'r'->'\r'; 't'->'\t'; 'b'->'\b'; 'f'->'\u000c'; 'u'->s.substring(p,p+4).toInt(16).toChar().also { p+=4 }; else->e }) } }; error("Unclosed JSON string") } }
}
