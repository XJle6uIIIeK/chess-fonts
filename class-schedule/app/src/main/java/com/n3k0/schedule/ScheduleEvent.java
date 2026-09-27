package com.n3k0.schedule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ScheduleEvent {
    public final LocalDate date;
    public final int pairNumber;
    public final LocalTime start;
    public final LocalTime end;
    public final String subject;
    public final String teacher;
    public final String room;
    public final List<String> groups;
    public final int sourcePage;

    public ScheduleEvent(LocalDate date, int pairNumber, LocalTime start, LocalTime end,
                         String subject, String teacher, String room,
                         List<String> groups, int sourcePage) {
        this.date = date;
        this.pairNumber = pairNumber;
        this.start = start;
        this.end = end;
        this.subject = subject == null ? "" : subject.trim();
        this.teacher = teacher == null ? "" : teacher.trim();
        this.room = room == null ? "" : room.trim();
        this.groups = new ArrayList<>(groups);
        this.sourcePage = sourcePage;
    }

    public LocalDateTime startDateTime() {
        return LocalDateTime.of(date, start);
    }

    public LocalDateTime endDateTime() {
        return LocalDateTime.of(date, end);
    }

    public boolean belongsTo(String group) {
        if (group == null || group.isBlank()) return false;
        for (String item : groups) {
            if (item.equalsIgnoreCase(group.trim())) return true;
        }
        return false;
    }

    public String cleanSubject() {
        return subject.replace("(л)", "").replace("(с)", "").replaceAll("\\s+", " ").trim();
    }

    public String typeLabel() {
        if (subject.contains("(л)")) return "Лекция";
        if (subject.contains("(с)")) return "Семинар";
        return "";
    }

    public String stableKey() {
        return date + "|" + pairNumber + "|" + start + "|" + subject + "|" + room + "|" + String.join(",", groups);
    }

    public int requestCode() {
        return 100_000 + Math.floorMod(stableKey().hashCode(), 800_000);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("date", date.toString());
        o.put("pair", pairNumber);
        o.put("start", start.toString());
        o.put("end", end.toString());
        o.put("subject", subject);
        o.put("teacher", teacher);
        o.put("room", room);
        o.put("page", sourcePage);
        JSONArray arr = new JSONArray();
        for (String g : groups) arr.put(g);
        o.put("groups", arr);
        return o;
    }

    public static ScheduleEvent fromJson(JSONObject o) throws JSONException {
        JSONArray arr = o.getJSONArray("groups");
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) groups.add(arr.getString(i));
        return new ScheduleEvent(
                LocalDate.parse(o.getString("date")),
                o.getInt("pair"),
                LocalTime.parse(o.getString("start")),
                LocalTime.parse(o.getString("end")),
                o.optString("subject"),
                o.optString("teacher"),
                o.optString("room"),
                groups,
                o.optInt("page", 0)
        );
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ScheduleEvent)) return false;
        return stableKey().equals(((ScheduleEvent) obj).stableKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(stableKey());
    }

    public String compactLine() {
        String base = String.format(Locale.getDefault(), "%d. %s–%s  %s", pairNumber, start, end, cleanSubject());
        if (!room.isBlank()) base += "  •  каб. " + room;
        return base;
    }
}
