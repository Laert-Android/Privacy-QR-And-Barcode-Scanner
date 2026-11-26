package com.laert.qrscanner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns a batch list into something a spreadsheet understands: a real Excel file (.xlsx), CSV,
 * or tab-separated text for pasting. No Android classes and no libraries, so it can be tested on
 * a plain JVM.
 *
 * Scanned content is untrusted. In CSV and pasted text a value starting with "=" can run as a
 * formula when opened in a spreadsheet, so those are neutralized. The .xlsx file stores every
 * code as plain text, which also keeps long numbers and leading zeros (EAN, UPC) intact.
 */
final class SpreadsheetExport {

    private SpreadsheetExport() {
    }

    private static final Pattern SAFE_SIGNED_NUMBER = Pattern.compile("^[+-][0-9 ().,\\-]*$");

    // ------------------------------------------------------------------ shared

    static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(millis));
    }

    /**
     * Prefixes values that a spreadsheet could treat as a formula with an apostrophe. Plain
     * phone-style numbers such as +355691234567 are left alone.
     */
    static String neutralize(String value) {
        if (value.isEmpty()) return value;
        char c = value.charAt(0);
        boolean risky = c == '=' || c == '@' || c == '\t' || c == '\r' || c == '\n';
        if (!risky && (c == '+' || c == '-')) {
            risky = !SAFE_SIGNED_NUMBER.matcher(value).matches();
        }
        return risky ? "'" + value : value;
    }

    // ------------------------------------------------------------------ CSV

    /** RFC 4180 CSV, CRLF rows, every field quoted, starting with a BOM so Excel reads UTF-8. */
    static String toCsv(String[] header, List<BatchList.Entry> entries) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        csvRow(sb, header);
        for (BatchList.Entry e : entries) {
            csvRow(sb, neutralize(e.content), e.format, String.valueOf(e.count),
                    formatTime(e.firstScannedMillis));
        }
        return sb.toString();
    }

    private static void csvRow(StringBuilder sb, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(fields[i].replace("\"", "\"\"")).append('"');
        }
        sb.append("\r\n");
    }

    // ------------------------------------------------------------------ pasted text

    /** "code<TAB>quantity" per line: pastes into a spreadsheet as two columns. */
    static String toClipboardText(List<BatchList.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (BatchList.Entry e : entries) {
            String flat = e.content.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
            sb.append(neutralize(flat)).append('\t').append(e.count).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ XLSX

    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String NS_DOC_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    static byte[] toXlsx(String[] header, List<BatchList.Entry> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", XML_HEAD
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", XML_HEAD
                    + "<Relationships xmlns=\"" + NS_REL + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + NS_DOC_REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/workbook.xml", XML_HEAD
                    + "<workbook xmlns=\"" + NS_MAIN + "\" xmlns:r=\"" + NS_DOC_REL + "\">"
                    + "<sheets><sheet name=\"Scans\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                    + "</workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", XML_HEAD
                    + "<Relationships xmlns=\"" + NS_REL + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + NS_DOC_REL + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"" + NS_DOC_REL + "/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            // Two cell styles: 0 = normal, 1 = bold (used for the header row).
            put(zip, "xl/styles.xml", XML_HEAD
                    + "<styleSheet xmlns=\"" + NS_MAIN + "\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
                    + "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                    + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                    + "</styleSheet>");

            StringBuilder sheet = new StringBuilder(XML_HEAD);
            sheet.append("<worksheet xmlns=\"").append(NS_MAIN).append("\">")
                    .append("<cols>")
                    .append("<col min=\"1\" max=\"1\" width=\"48\" customWidth=\"1\"/>")
                    .append("<col min=\"2\" max=\"2\" width=\"14\" customWidth=\"1\"/>")
                    .append("<col min=\"3\" max=\"3\" width=\"10\" customWidth=\"1\"/>")
                    .append("<col min=\"4\" max=\"4\" width=\"20\" customWidth=\"1\"/>")
                    .append("</cols><sheetData>");
            sheet.append("<row r=\"1\">");
            for (int i = 0; i < header.length; i++) {
                textCell(sheet, columnLetter(i) + "1", header[i], 1);
            }
            sheet.append("</row>");
            int r = 2;
            for (BatchList.Entry e : entries) {
                sheet.append("<row r=\"").append(r).append("\">");
                // Inline strings are always text: never evaluated, no lost zeros, no 5.9E+12.
                textCell(sheet, "A" + r, e.content, 0);
                textCell(sheet, "B" + r, e.format, 0);
                sheet.append("<c r=\"C").append(r).append("\"><v>").append(e.count).append("</v></c>");
                textCell(sheet, "D" + r, formatTime(e.firstScannedMillis), 0);
                sheet.append("</row>");
                r++;
            }
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return bytes.toByteArray();
    }

    private static void textCell(StringBuilder sb, String ref, String text, int style) {
        sb.append("<c r=\"").append(ref).append('"');
        if (style != 0) sb.append(" s=\"").append(style).append('"');
        sb.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(xmlText(text)).append("</t></is></c>");
    }

    private static String columnLetter(int index) {
        return String.valueOf((char) ('A' + index));
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Escapes text for an XML element. Control characters (barcodes can contain them, GS1 uses
     * the group separator) are illegal in XML 1.0, so they are shown as their visible
     * "control picture" symbol instead of corrupting the file.
     */
    static String xmlText(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '&') sb.append("&amp;");
            else if (cp == '<') sb.append("&lt;");
            else if (cp == '>') sb.append("&gt;");
            else if (cp == 0x9 || cp == 0xA || cp == 0xD) sb.append((char) cp);
            else if (cp < 0x20) sb.append((char) (0x2400 + cp));
            else if (cp == 0xFFFE || cp == 0xFFFF || (cp >= 0xD800 && cp <= 0xDFFF)) sb.append('\uFFFD');
            else sb.appendCodePoint(cp);
        }
        return sb.toString();
    }
}
