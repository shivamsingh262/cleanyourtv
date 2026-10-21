package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Photos screensaver's set (files/photos, the same folder tools/photos.sh fills), managed from the owner's
 * Telegram bot (TelegramNotes): a photo sent to the bot is added; "/photos" sends the set back as numbered albums of
 * ten; "/remove 12 15" moves photos to a bin beside it, and "/restore 12" brings one back. Numbers are the ones in the
 * file names (photo-0012-20231202.jpg), so they stay put as photos come and go. A caption with a date ("Dec 2023",
 * "2 Dec 2023", "2023-12-02") dates a sent photo for On this day; without one it is dated the day it was sent.
 */
final class TelegramPhotos {

    private static final Pattern NAME = Pattern.compile("^photo-(\\d+)(?:-(\\d{6,8}))?\\.(jpe?g|png|webp)$",
            Pattern.CASE_INSENSITIVE);
    private static final int PAGE = 10, TIMEOUT_MS = 30_000;
    private static final long MAX_BYTES = 15 * 1024 * 1024;

    private final String token;
    private final File dir, bin;

    TelegramPhotos(String token, File dir) {
        this.token = token;
        this.dir = dir;
        this.bin = new File(dir.getParentFile(), "photos-removed");
    }

    /** A photo message (or an image sent as a file): saved under the next number. The reply to send back. */
    String add(JSONObject m) {
        try {
            String fileId = null;
            JSONArray sizes = m.optJSONArray("photo");
            if (sizes != null && sizes.length() > 0) {
                fileId = sizes.getJSONObject(sizes.length() - 1).getString("file_id");   // the largest
            } else {
                JSONObject doc = m.optJSONObject("document");
                if (doc != null && doc.optString("mime_type").startsWith("image/")) fileId = doc.getString("file_id");
            }
            if (fileId == null) return null;
            JSONObject f = new JSONObject(get("getFile?file_id=" + URLEncoder.encode(fileId, "UTF-8")));
            if (!f.optBoolean("ok")) return "Telegram didn't hand over that file. Try sending it again.";
            JSONObject file = f.getJSONObject("result");
            if (file.optLong("file_size") > MAX_BYTES) return "That one is over 15 MB; send it as a photo instead.";
            String path = file.getString("file_path");
            String ext = path.toLowerCase(Locale.ROOT).endsWith(".png") ? "png"
                    : path.toLowerCase(Locale.ROOT).endsWith(".webp") ? "webp" : "jpg";
            LocalDate date = captionDate(m.optString("caption", ""));
            if (date == null) date = Instant.ofEpochSecond(m.optLong("date")).atZone(ZoneId.systemDefault()).toLocalDate();
            int number = nextNumber();
            if (!dir.isDirectory() && !dir.mkdirs()) return "Couldn't make the photos folder on the TV.";
            File out = new File(dir, String.format(Locale.ROOT, "photo-%04d-%04d%02d%02d.%s", number,
                    date.getYear(), date.getMonthValue(), date.getDayOfMonth(), ext));
            File part = new File(dir, "." + out.getName() + ".part");
            download("https://api.telegram.org/file/bot" + token + "/" + path, part);
            if (!part.renameTo(out)) {
                part.delete();
                return "Couldn't save it on the TV.";
            }
            return "Added as #" + number + " · " + monthYear(date) + ". It joins the screensaver from its next round.";
        } catch (Exception e) {
            return "Couldn't fetch that photo. Try again in a minute.";
        }
    }

    /** "/photos [page]": a page of the set as an album (newest first), then a line on what to send next. */
    void list(long chat, String arg) throws Exception {
        List<File> all = sorted(dir);
        if (all.isEmpty()) {
            send(chat, "No photos on the TV yet. Send some here.");
            return;
        }
        int pages = (all.size() + PAGE - 1) / PAGE, page = 1;
        try {
            page = Math.max(1, Math.min(pages, Integer.parseInt(arg.trim())));
        } catch (NumberFormatException ignored) {
            // No number: the first page.
        }
        List<File> slice = all.subList((page - 1) * PAGE, Math.min(all.size(), page * PAGE));
        sendAlbum(chat, slice);
        send(chat, "Photos " + ((page - 1) * PAGE + 1) + "–" + ((page - 1) * PAGE + slice.size()) + " of "
                + all.size() + (page < pages ? ". /photos " + (page + 1) + " for the next ten." : ".")
                + "\n/remove 12 15 takes them out; /removed lists the bin.");
    }

    /** "/remove 12 15": into the bin. */
    String remove(String args) {
        List<Integer> wanted = numbers(args);
        if (wanted.isEmpty()) return "Send /photos, then /remove with their numbers, e.g. /remove 12 15";
        if (!bin.isDirectory()) bin.mkdirs();
        StringBuilder done = new StringBuilder(), missing = new StringBuilder();
        for (int n : wanted) {
            File f = byNumber(dir, n);
            if (f != null && f.renameTo(new File(bin, f.getName()))) done.append(" #").append(n);
            else missing.append(" #").append(n);
        }
        return (done.length() > 0 ? "Removed" + done + ". /restore brings one back." : "")
                + (missing.length() > 0 ? (done.length() > 0 ? "\n" : "") + "Not in the set:" + missing : "")
                + "\n" + sorted(dir).size() + " photos in the screensaver.";
    }

    /** "/restore 12": out of the bin. */
    String restore(String args) {
        List<Integer> wanted = numbers(args);
        if (wanted.isEmpty()) return "Send /removed, then /restore with a number, e.g. /restore 12";
        StringBuilder done = new StringBuilder(), missing = new StringBuilder();
        for (int n : wanted) {
            File f = byNumber(bin, n);
            if (f != null && f.renameTo(new File(dir, f.getName()))) done.append(" #").append(n);
            else missing.append(" #").append(n);
        }
        return (done.length() > 0 ? "Back in the screensaver:" + done : "")
                + (missing.length() > 0 ? (done.length() > 0 ? "\n" : "") + "Not in the bin:" + missing : "");
    }

    /** "/removed": the bin as an album. */
    void removed(long chat) throws Exception {
        List<File> all = sorted(bin);
        if (all.isEmpty()) {
            send(chat, "The bin is empty.");
            return;
        }
        sendAlbum(chat, all.subList(0, Math.min(PAGE, all.size())));
        send(chat, all.size() + " in the bin. /restore 12 puts one back.");
    }

    // ---- files -----------------------------------------------------------------------------------

    /** Newest number first. */
    private static List<File> sorted(File folder) {
        List<File> out = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) if (number(f) > 0) out.add(f);
        out.sort((a, b) -> Integer.compare(number(b), number(a)));
        return out;
    }

    private static int number(File f) {
        Matcher m = NAME.matcher(f.getName());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static File byNumber(File folder, int n) {
        for (File f : sorted(folder)) if (number(f) == n) return f;
        return null;
    }

    private int nextNumber() {
        int max = 0;
        for (File folder : new File[]{dir, bin}) for (File f : sorted(folder)) max = Math.max(max, number(f));
        return max + 1;
    }

    private static List<Integer> numbers(String args) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(args);
        while (m.find() && out.size() < 50) out.add(Integer.parseInt(m.group()));
        return out;
    }

    private static String caption(File f) {
        Matcher m = NAME.matcher(f.getName());
        String c = "#" + Integer.parseInt(m.matches() ? m.group(1) : "0");
        if (m.matches() && m.group(2) != null) {
            String d = m.group(2);
            int month = Integer.parseInt(d.substring(4, 6));
            if (month >= 1 && month <= 12) c += " · " + new DateFormatSymbols(Locale.ENGLISH).getShortMonths()[month - 1]
                    + " " + d.substring(0, 4);
        }
        return c;
    }

    private static String monthYear(LocalDate d) {
        return new DateFormatSymbols(Locale.ENGLISH).getShortMonths()[d.getMonthValue() - 1] + " " + d.getYear();
    }

    /** "Dec 2023", "2 Dec 2023", "December 2, 2023", "2023-12-02", "2/12/2023": the date a caption gives, or null. */
    static LocalDate captionDate(String caption) {
        String c = caption.trim();
        Matcher m = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(c);
        if (m.find()) return date(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        m = Pattern.compile("(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4})").matcher(c);
        if (m.find()) return date(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        String[] months = new DateFormatSymbols(Locale.ENGLISH).getShortMonths();
        for (int i = 0; i < 12; i++) {
            String mon = months[i].toLowerCase(Locale.ROOT);
            m = Pattern.compile("(?i)(?:(\\d{1,2})(?:st|nd|rd|th)?\\s+)?" + mon + "[a-z]*\\.?\\s*(?:(\\d{1,2})(?:st|nd|rd|th)?,?\\s+)?(\\d{4})")
                    .matcher(c);
            if (m.find()) {
                String day = m.group(1) != null ? m.group(1) : m.group(2);
                return date(Integer.parseInt(m.group(3)), i + 1, day == null ? 1 : Integer.parseInt(day));
            }
        }
        return null;
    }

    private static LocalDate date(int y, int mo, int d) {
        try {
            return LocalDate.of(y, mo, d);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Telegram --------------------------------------------------------------------------------

    private void send(long chat, String text) throws Exception {
        get("sendMessage?chat_id=" + chat + "&text=" + URLEncoder.encode(text, "UTF-8"));
    }

    /** sendMediaGroup with the files uploaded in one multipart request, each captioned with its number. */
    private void sendAlbum(long chat, List<File> files) throws Exception {
        if (files.size() == 1) {
            multipart("sendPhoto", chat, files, "{}", true);
            return;
        }
        JSONArray media = new JSONArray();
        for (int i = 0; i < files.size(); i++) {
            media.put(new JSONObject().put("type", "photo").put("media", "attach://p" + i).put("caption", caption(files.get(i))));
        }
        multipart("sendMediaGroup", chat, files, media.toString(), false);
    }

    private void multipart(String method, long chat, List<File> files, String media, boolean single) throws Exception {
        String boundary = "----tvhome" + System.nanoTime();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/" + method)
                .openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS * 2);
            c.setDoOutput(true);
            c.setChunkedStreamingMode(64 * 1024);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = c.getOutputStream()) {
                field(out, boundary, "chat_id", String.valueOf(chat));
                if (single) field(out, boundary, "caption", caption(files.get(0)));
                else field(out, boundary, "media", media);
                for (int i = 0; i < files.size(); i++) {
                    File f = files.get(i);
                    String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + (single ? "photo" : "p" + i)
                            + "\"; filename=\"" + f.getName() + "\"\r\nContent-Type: image/jpeg\r\n\r\n";
                    out.write(head.getBytes(StandardCharsets.UTF_8));
                    try (InputStream in = new java.io.FileInputStream(f)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static void field(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private String get(String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/" + method)
                .openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
                if (in == null) return "{}";
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String address, File to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new java.io.IOException("too large");
                    out.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            to.delete();
            throw e;
        } finally {
            c.disconnect();
        }
    }
}
