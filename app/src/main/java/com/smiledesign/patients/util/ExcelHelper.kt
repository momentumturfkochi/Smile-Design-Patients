package com.smiledesign.patients.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.smiledesign.patients.data.PatientRecord
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

object ExcelHelper {

    fun importFile(context: Context, uri: Uri): List<PatientRecord> {
        val fileName = getFileName(context, uri).lowercase()
        val rows = if (fileName.endsWith(".csv")) {
            readCsvRows(context, uri)
        } else {
            readXlsxRows(context, uri)
        }
        return mapRowsToPatients(rows)
    }

    fun exportCsv(context: Context, uri: Uri, records: List<PatientRecord>) {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
            out.appendLine("OP,Name,Phone,Place,Age,Sex,Date,Treatment")
            for (r in records) {
                val cols = listOf(r.opNumber, r.name, r.phone, r.place, r.age, r.sex, r.visitDate, r.treatment)
                    .map { "\"${it.replace("\"", "\"\"")}\"" }
                out.appendLine(cols.joinToString(","))
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx) ?: ""
        }
        return uri.lastPathSegment ?: ""
    }

    private fun readCsvRows(context: Context, uri: Uri): List<List<String>> {
        val result = mutableListOf<List<String>>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).forEachLine { line ->
                if (line.isNotBlank()) {
                    result.add(parseCsvLine(line))
                }
            }
        }
        return result
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells.add(sb.toString().trim()); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        cells.add(sb.toString().trim())
        return cells
    }

    private fun readXlsxRows(context: Context, uri: Uri): List<List<String>> {
        var sharedStringsBytes: ByteArray? = null
        var sheetBytes: ByteArray? = null

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "xl/sharedStrings.xml" -> sharedStringsBytes = zis.readBytes()
                        "xl/worksheets/sheet1.xml" -> sheetBytes = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val sharedStrings = sharedStringsBytes?.let { parseSharedStrings(it) } ?: emptyList()
        val sheet = sheetBytes ?: return emptyList()
        return parseSheetXml(sheet, sharedStrings)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType
        var inSi = false
        val current = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        inSi = true
                        current.clear()
                    } else if (inSi && parser.name == "t") {
                        current.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        list.add(current.toString())
                        inSi = false
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseSheetXml(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var event = parser.eventType
        var currentRow = mutableMapOf<Int, String>()
        var currentCol = 0
        var cellType: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableMapOf()
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r") ?: ""
                        currentCol = colIndexFromRef(ref)
                        cellType = parser.getAttributeValue(null, "t")
                    }
                    "v" -> {
                        val raw = parser.nextText().trim()
                        val value = if (cellType == "s") {
                            raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: raw
                        } else {
                            cleanNumber(raw)
                        }
                        currentRow[currentCol] = value
                    }
                    "t" -> {
                        if (cellType == "inlineStr") {
                            currentRow[currentCol] = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "row" && currentRow.isNotEmpty()) {
                        val maxCol = currentRow.keys.maxOrNull() ?: 0
                        val rowList = (0..maxCol).map { currentRow[it] ?: "" }
                        if (rowList.any { it.isNotBlank() }) rows.add(rowList)
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun colIndexFromRef(ref: String): Int {
        var index = 0
        for (ch in ref) {
            if (ch.isLetter()) {
                index = index * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else break
        }
        return (index - 1).coerceAtLeast(0)
    }

    private fun cleanNumber(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        return if (d == d.toLong().toDouble()) d.toLong().toString() else raw
    }

    private fun formatExcelDateIfNeeded(value: String): String {
        val serial = value.toLongOrNull() ?: return value
        return if (serial in 30000..60000) {
            LocalDate.of(1899, 12, 30).plusDays(serial)
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        } else {
            value
        }
    }

    private fun mapRowsToPatients(rows: List<List<String>>): List<PatientRecord> {
        if (rows.isEmpty()) return emptyList()

        val firstRow = rows.first().map { it.trim().lowercase() }
        val hasHeader = firstRow.any { it.contains("name") || it == "op" || it.contains("phone") }

        var opIdx = 0; var nameIdx = 1; var phoneIdx = 2; var placeIdx = 3
        var ageIdx = 4; var sexIdx = 5; var dateIdx = 6; var treatIdx = 7

        if (hasHeader) {
            firstRow.forEachIndexed { idx, col ->
                when {
                    col == "op" || col.contains("op ") || col.contains("op.") -> opIdx = idx
                    col.contains("name") -> nameIdx = idx
                    col.contains("phone") || col.contains("mobile") || col.contains("contact") -> phoneIdx = idx
                    col.contains("place") || col.contains("address") -> placeIdx = idx
                    col == "age" -> ageIdx = idx
                    col == "sex" || col == "gender" -> sexIdx = idx
                    col.contains("date") -> dateIdx = idx
                    col.contains("treat") || col.contains("note") -> treatIdx = idx
                }
            }
        }

        val dataRows = if (hasHeader) rows.drop(1) else rows
        return dataRows.mapNotNull { row ->
            fun col(i: Int) = row.getOrNull(i)?.trim() ?: ""
            val name = col(nameIdx)
            val op = col(opIdx)
            val phone = col(phoneIdx)
            if (name.isBlank() && op.isBlank() && phone.isBlank()) return@mapNotNull null

            PatientRecord(
                opNumber = op,
                name = name,
                phone = phone,
                place = col(placeIdx),
                age = col(ageIdx),
                sex = col(sexIdx),
                visitDate = formatExcelDateIfNeeded(col(dateIdx)),
                treatment = col(treatIdx)
            )
        }
    }
}
