package com.laert.qrscanner;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ResultParser {

    enum Type { WIFI, PHONE, SMS, EMAIL, GEO, CONTACT, EVENT, URL, TEXT }

    enum Warning { HTTP, USERINFO, SHORTENER, IP_ADDRESS, LOOKALIKE }

    static final class Parsed {
        Type type = Type.TEXT;
        final Map<String, String> fields = new LinkedHashMap<>();
        final List<Warning> warnings = new ArrayList<>();
        long beginMillis = -1;
        long endMillis = -1;
        boolean allDay;

        String get(String key) {
            String v = fields.get(key);
            return v == null ? "" : v;
        }

        boolean has(String key) {
            return !get(key).isEmpty();
        }
    }

    private ResultParser() {
    }

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");
    private static final Pattern GEO = Pattern.compile(
            "^(-?\\d{1,3}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)(?:,-?\\d+(?:\\.\\d+)?)?(?:[;?].*)?$");
    private static final Pattern HTTP_URL = Pattern.compile("(?i)^https?://[^\\s/?#]+\\S*$");
    private static final Pattern BARE_DOMAIN = Pattern.compile(
            "(?i)^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+([a-z]{2,})(?::\\d{1,5})?(?:[/?#]\\S*)?$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern ICAL_DATE = Pattern.compile(
            "^(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})?(Z)?)?$");

    private static final Set<String> COMMON_TLDS = new HashSet<>(Arrays.asList(
            "com", "org", "net", "edu", "gov", "io", "co", "app", "dev", "me", "info", "biz",
            "xyz", "online", "site", "tech", "store", "shop", "blog", "cloud", "ai",
            "al", "de", "uk", "fr", "it", "us", "ca", "eu", "ru", "cn", "jp", "in", "br",
            "au", "nl", "es", "ch", "se", "no", "pl", "tv", "gr", "tr", "mk", "xk", "rs", "at"));

    private static final Set<String> SHORTENERS = new HashSet<>(Arrays.asList(
            "bit.ly", "t.co", "tinyurl.com", "goo.gl", "ow.ly", "is.gd", "buff.ly", "rebrand.ly",
            "cutt.ly", "shorturl.at", "tiny.cc", "rb.gy", "bl.ink", "t.ly", "lnkd.in", "qrco.de",
            "v.gd", "s.id"));

    static Parsed parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        Parsed p = new Parsed();
        boolean ok;

        if (lower.startsWith("wifi:")) {
            ok = parseWifi(s, p);
        } else if (lower.startsWith("tel:")) {
            ok = parsePhone(s, p);
        } else if (startsWithAny(lower, "sms:", "smsto:", "mms:", "mmsto:")) {
            ok = parseSms(s, lower, p);
        } else if (lower.startsWith("mailto:") || lower.startsWith("matmsg:")
                || EMAIL.matcher(s).matches()) {
            ok = parseEmail(s, lower, p);
        } else if (lower.startsWith("geo:")) {
            ok = parseGeo(s, p);
        } else if (lower.startsWith("mecard:") || lower.contains("begin:vcard")) {
            ok = parseContact(s, lower, p);
        } else if (lower.contains("begin:vevent")) {
            ok = parseEvent(s, p);
        } else {
            ok = parseUrl(s, p);
        }
        return ok ? p : new Parsed();
    }


    private static boolean parseWifi(String s, Parsed p) {
        List<String[]> kv = keyValues(s.substring(5));
        String ssid = unquote(first(kv, "S"));
        if (ssid.isEmpty()) return false;
        String security = first(kv, "T");
        String password = unquote(first(kv, "P"));
        boolean hidden = "true".equalsIgnoreCase(first(kv, "H"));

        p.type = Type.WIFI;
        p.fields.put("ssid", ssid);
        p.fields.put("security", security);
        p.fields.put("password", password);
        p.fields.put("hidden", hidden ? "true" : "false");

        return true;
    }


    private static boolean parsePhone(String s, Parsed p) {
        String number = percentDecode(s.substring(4).trim());
        if (!hasDigit(number)) return false;
        p.type = Type.PHONE;
        p.fields.put("number", number);
        return true;
    }

    private static boolean parseSms(String s, String lower, Parsed p) {
        String rest = s.substring(s.indexOf(':') + 1);
        String number;
        String body = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            number = rest.substring(0, q);
            body = queryParam(rest.substring(q + 1), "body");
        } else {
            int c = rest.indexOf(':');
            if (c >= 0) {
                number = rest.substring(0, c);
                body = rest.substring(c + 1);
            } else {
                number = rest;
            }
        }
        number = percentDecode(number.trim());
        if (!hasDigit(number)) return false;

        p.type = Type.SMS;
        p.fields.put("number", number);
        p.fields.put("body", body);
        return true;
    }


    private static boolean parseEmail(String s, String lower, Parsed p) {
        String to;
        String subject = "";
        String body = "";

        if (lower.startsWith("matmsg:")) {
            List<String[]> kv = keyValues(s.substring(7));
            to = first(kv, "TO");
            subject = first(kv, "SUB");
            body = first(kv, "BODY");
        } else if (lower.startsWith("mailto:")) {
            String rest = s.substring(7);
            int q = rest.indexOf('?');
            to = percentDecode(q < 0 ? rest : rest.substring(0, q));
            if (q >= 0) {
                String query = rest.substring(q + 1);
                subject = queryParam(query, "subject");
                body = queryParam(query, "body");
            }
        } else {
            to = s;
        }
        to = to.trim();
        if (to.indexOf('@') < 0) return false;

        p.type = Type.EMAIL;
        p.fields.put("to", to);
        p.fields.put("subject", subject);
        p.fields.put("body", body);
        return true;
    }


    private static boolean parseGeo(String s, Parsed p) {
        Matcher m = GEO.matcher(s.substring(4).trim());
        if (!m.matches()) return false;
        try {
            double lat = Double.parseDouble(m.group(1));
            double lon = Double.parseDouble(m.group(2));
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        p.type = Type.GEO;
        p.fields.put("lat", m.group(1));
        p.fields.put("lon", m.group(2));
        return true;
    }


    private static boolean parseContact(String s, String lower, Parsed p) {
        Map<String, String> f = p.fields;
        if (lower.startsWith("mecard:")) {
            List<String[]> kv = keyValues(s.substring(7));
            String n = first(kv, "N");
            int comma = n.indexOf(',');
            String name = comma >= 0
                    ? (n.substring(comma + 1).trim() + " " + n.substring(0, comma).trim()).trim()
                    : n.trim();
            f.put("name", name);
            List<String> phones = all(kv, "TEL");
            List<String> emails = all(kv, "EMAIL");
            f.put("phone", phones.size() > 0 ? phones.get(0) : "");
            f.put("phone2", phones.size() > 1 ? phones.get(1) : "");
            f.put("email", emails.size() > 0 ? emails.get(0) : "");
            f.put("email2", emails.size() > 1 ? emails.get(1) : "");
            f.put("org", first(kv, "ORG"));
            f.put("title", "");
            f.put("address", joinNonEmpty(first(kv, "ADR").split(","), ", "));
            f.put("url", first(kv, "URL"));
            f.put("note", first(kv, "NOTE"));
        } else {
            String fn = "";
            String structuredName = "";
            List<String> phones = new ArrayList<>();
            List<String> emails = new ArrayList<>();
            String org = "";
            String title = "";
            String address = "";
            String url = "";
            String note = "";
            for (String line : unfold(s)) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).split(";")[0].trim().toUpperCase(Locale.ROOT);
                int dot = key.indexOf('.');
                if (dot >= 0) key = key.substring(dot + 1);
                String value = line.substring(colon + 1);
                switch (key) {
                    case "FN":
                        if (fn.isEmpty()) fn = vcUnescape(value).trim();
                        break;
                    case "N": {
                        String[] parts = value.split(";", -1);
                        String family = parts.length > 0 ? vcUnescape(parts[0]) : "";
                        String given = parts.length > 1 ? vcUnescape(parts[1]) : "";
                        if (structuredName.isEmpty()) {
                            structuredName = (given.trim() + " " + family.trim()).trim();
                        }
                        break;
                    }
                    case "TEL":
                        phones.add(vcUnescape(value).trim());
                        break;
                    case "EMAIL":
                        emails.add(vcUnescape(value).trim());
                        break;
                    case "ORG":
                        if (org.isEmpty()) org = vcUnescape(value.split(";", -1)[0]).trim();
                        break;
                    case "TITLE":
                        if (title.isEmpty()) title = vcUnescape(value).trim();
                        break;
                    case "ADR": {
                        if (address.isEmpty()) {
                            String[] parts = value.split(";", -1);
                            List<String> keep = new ArrayList<>();
                            for (int i = 2; i < parts.length; i++) keep.add(vcUnescape(parts[i]));
                            address = joinNonEmpty(keep.toArray(new String[0]), ", ");
                        }
                        break;
                    }
                    case "URL":
                        if (url.isEmpty()) url = vcUnescape(value).trim();
                        break;
                    case "NOTE":
                        if (note.isEmpty()) note = vcUnescape(value).trim();
                        break;
                    default:
                        break;
                }
            }
            f.put("name", fn.isEmpty() ? structuredName : fn);
            f.put("phone", phones.size() > 0 ? phones.get(0) : "");
            f.put("phone2", phones.size() > 1 ? phones.get(1) : "");
            f.put("email", emails.size() > 0 ? emails.get(0) : "");
            f.put("email2", emails.size() > 1 ? emails.get(1) : "");
            f.put("org", org);
            f.put("title", title);
            f.put("address", address);
            f.put("url", url);
            f.put("note", note);
        }
        if (!p.has("name") && !p.has("phone") && !p.has("email")) return false;

        p.type = Type.CONTACT;
        return true;
    }


    private static boolean parseEvent(String s, Parsed p) {
        boolean inside = false;
        String title = "";
        String location = "";
        String description = "";
        long[] start = null;
        long[] end = null;

        for (String line : unfold(s)) {
            String upper = line.trim().toUpperCase(Locale.ROOT);
            if (upper.equals("BEGIN:VEVENT")) {
                inside = true;
                continue;
            }
            if (upper.equals("END:VEVENT")) break;
            if (!inside) continue;

            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            int semi = head.indexOf(';');
            String key = (semi < 0 ? head : head.substring(0, semi)).trim().toUpperCase(Locale.ROOT);
            String params = semi < 0 ? "" : head.substring(semi + 1);
            switch (key) {
                case "SUMMARY":
                    title = vcUnescape(value).trim();
                    break;
                case "LOCATION":
                    location = vcUnescape(value).trim();
                    break;
                case "DESCRIPTION":
                    description = vcUnescape(value).trim();
                    break;
                case "DTSTART":
                    start = icalDate(value, params);
                    break;
                case "DTEND":
                    end = icalDate(value, params);
                    break;
                default:
                    break;
            }
        }
        if (title.isEmpty() && start == null) return false;

        p.type = Type.EVENT;
        p.fields.put("title", title);
        p.fields.put("location", location);
        p.fields.put("description", description);

        final long hour = 3600_000L;
        final long day = 24 * hour;
        if (start != null) {
            p.allDay = start[1] == 1;
            p.beginMillis = start[0];
            if (p.allDay) {
                p.endMillis = end != null ? Math.max(start[0], end[0] - day) : start[0];
            } else {
                p.endMillis = end != null ? end[0] : start[0] + hour;
            }
        }

        return true;
    }

    private static long[] icalDate(String value, String params) {
        Matcher m = ICAL_DATE.matcher(value.trim());
        if (!m.matches()) return null;
        boolean dateOnly = m.group(4) == null;
        boolean utc = m.group(7) != null;
        TimeZone tz = TimeZone.getTimeZone("UTC");
        if (!dateOnly && !utc) {
            tz = TimeZone.getDefault();
            Matcher t = Pattern.compile("(?i)TZID=([^;:]+)").matcher(params);
            if (t.find()) {
                String id = t.group(1).replace("\"", "");
                if (Arrays.asList(TimeZone.getAvailableIDs()).contains(id)) {
                    tz = TimeZone.getTimeZone(id);
                }
            }
        }
        try {
            Calendar c = Calendar.getInstance(tz, Locale.ROOT);
            c.clear();
            c.setLenient(false);
            c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                    Integer.parseInt(m.group(3)),
                    dateOnly ? 0 : Integer.parseInt(m.group(4)),
                    dateOnly ? 0 : Integer.parseInt(m.group(5)),
                    (dateOnly || m.group(6) == null) ? 0 : Integer.parseInt(m.group(6)));
            return new long[]{c.getTimeInMillis(), dateOnly ? 1 : 0};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }


    private static boolean parseUrl(String s, Parsed p) {
        if (s.isEmpty() || s.matches("(?s).*\\s.*")) return false;

        String url;
        boolean explicitScheme = HTTP_URL.matcher(s).matches();
        if (explicitScheme) {
            url = s;
        } else {
            Matcher m = BARE_DOMAIN.matcher(s);
            if (!m.matches()) return false;
            String tld = m.group(1).toLowerCase(Locale.ROOT);
            if (!s.toLowerCase(Locale.ROOT).startsWith("www.") && !COMMON_TLDS.contains(tld)) {
                return false;
            }
            url = "https://" + s;
        }

        boolean http = url.regionMatches(true, 0, "http://", 0, 7);
        String afterScheme = url.substring(http ? 7 : 8);
        int cut = indexOfAny(afterScheme, "/?#");
        String authority = cut < 0 ? afterScheme : afterScheme.substring(0, cut);

        boolean userInfo = false;
        String host = authority;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userInfo = true;
            host = authority.substring(at + 1);
        }
        boolean ipv6 = host.startsWith("[");
        if (!ipv6) {
            int colon = host.lastIndexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
        }
        host = host.toLowerCase(Locale.ROOT);

        p.type = Type.URL;
        p.fields.put("url", url);
        p.fields.put("host", host);

        if (explicitScheme && http) {
            p.warnings.add(Warning.HTTP);
        }
        if (userInfo) {
            p.warnings.add(Warning.USERINFO);
        }
        String bareHost = host.startsWith("www.") ? host.substring(4) : host;
        if (SHORTENERS.contains(host) || SHORTENERS.contains(bareHost)) {
            p.warnings.add(Warning.SHORTENER);
        }
        if (ipv6 || IPV4.matcher(host).matches()) {
            p.warnings.add(Warning.IP_ADDRESS);
        }
        boolean nonAscii = false;
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) > 127) {
                nonAscii = true;
                break;
            }
        }
        if (nonAscii || host.contains("xn--")) {
            p.warnings.add(Warning.LOOKALIKE);
        }
        return true;
    }


    private static boolean startsWithAny(String s, String... prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int idx = s.indexOf(chars.charAt(i));
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        return best;
    }

    private static String joinNonEmpty(String[] parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(t);
        }
        return sb.toString();
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static List<String[]> keyValues(String body) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                cur.append(body.charAt(++i));
            } else if (c == ';') {
                tokens.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());

        List<String[]> out = new ArrayList<>();
        for (String t : tokens) {
            int idx = t.indexOf(':');
            if (idx <= 0) continue;
            out.add(new String[]{t.substring(0, idx).trim().toUpperCase(Locale.ROOT),
                    t.substring(idx + 1)});
        }
        return out;
    }

    private static String first(List<String[]> kv, String key) {
        for (String[] e : kv) {
            if (e[0].equals(key)) return e[1];
        }
        return "";
    }

    private static List<String> all(List<String[]> kv, String key) {
        List<String> out = new ArrayList<>();
        for (String[] e : kv) {
            if (e[0].equals(key) && !e[1].trim().isEmpty()) out.add(e[1].trim());
        }
        return out;
    }

    private static List<String> unfold(String s) {
        String joined = s.replaceAll("\r?\n[ \t]", "");
        String[] lines = joined.split("\r?\n");
        List<String> out = new ArrayList<>();
        Collections.addAll(out, lines);
        return out;
    }

    private static String vcUnescape(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(n == 'n' || n == 'N' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String percentDecode(String v) {
        try {
            return URLDecoder.decode(v.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return v;
        }
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (pair.substring(0, eq).equalsIgnoreCase(name)) {
                return percentDecode(pair.substring(eq + 1));
            }
        }
        return "";
    }
}
