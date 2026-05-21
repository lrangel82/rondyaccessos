package com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.utils

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

object S3XmlParser {
    fun parseS3XmlForMatchingKey(xmlBody: String, regex: Regex): List<String> {
        val keys = mutableListOf<String>()
        val parser = Xml.newPullParser()
        try {
            parser.setInput(StringReader(xmlBody))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "Key") {
                    parser.next()
                    val keyText = parser.text ?: ""
                    if (regex.matches(keyText)) {
                        keys.add(keyText)
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return keys
    }
}