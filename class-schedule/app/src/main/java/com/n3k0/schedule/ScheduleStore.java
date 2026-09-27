package com.n3k0.schedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ScheduleStore {
    private static final String PREF = "schedule_store";

    private static final String KEY_ACTIVE_EVENTS = "active_events";
    private static final String KEY_ACTIVE_GROUPS = "active_groups";
    private static final String KEY_ACTIVE_WARNINGS = "active_warnings";
    private static final String KEY_ACTIVE_DIAGNOSTICS = "active_diagnostics";
    private static final String KEY_ACTIVE_VALIDATION = "active_validation";
    private static final String KEY_ACTIVE_PAGE_COUNT = "active_page_count";
    private static final String KEY_ACTIVE_IMPORTED_AT = "active_imported_at";

    private static final String KEY_PENDING_EVENTS = "pending_events";
    private static final String KEY_PENDING_GROUPS = "pending_groups";
    private static final String KEY_PENDING_WARNINGS = "pending_warnings";
    private static final String KEY_PENDING_DIAGNOSTICS = "pending_diagnostics";
    private static final String KEY_PENDING_PAGE_COUNT = "pending_page_count";
    private static final String KEY_PENDING_VALIDATION = "pending_validation";
    private static final String KEY_PENDING_IMPORTED_AT = "pending_imported_at";
    private static final String KEY_PENDING_SELECTED_GROUP = "pending_selected_group";

    private static final String KEY_LAST_ERROR = "last_import_error";

    private ScheduleStore() {}

    private static SharedPreferences prefs(Context context) {
        ScheduleMigrationManager.ensureMigrated(context);
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void savePending(
            Context context,
            ScheduleParser.ParseResult result,
            ScheduleValidator.ValidationResult validation
    ) {
        JSONArray events = new JSONArray();
        JSONArray groups = new JSONArray();
        JSONArray warnings = new JSONArray();
        JSONArray diagnostics = new JSONArray();
        JSONArray validationIssues = new JSONArray();

        try {
            for (ScheduleEvent event : result.events) events.put(event.toJson());
            for (String group : result.groups) groups.put(group);
            for (String warning : result.warnings) warnings.put(warning);
            for (String diagnostic : result.diagnostics) diagnostics.put(diagnostic);
            for (ScheduleValidationIssue issue : validation.issues) {
                validationIssues.put(issue.toJson());
            }
        } catch (Exception ignored) {
        }

        prefs(context).edit()
                .putString(KEY_PENDING_EVENTS, events.toString())
                .putString(KEY_PENDING_GROUPS, groups.toString())
                .putString(KEY_PENDING_WARNINGS, warnings.toString())
                .putString(KEY_PENDING_DIAGNOSTICS, diagnostics.toString())
                .putString(KEY_PENDING_VALIDATION, validationIssues.toString())
                .putInt(KEY_PENDING_PAGE_COUNT, result.pageCount)
                .putLong(KEY_PENDING_IMPORTED_AT, System.currentTimeMillis())
                .remove(KEY_PENDING_SELECTED_GROUP)
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    public static void reprocessPending(Context context) {
        if (!hasPending(context)) return;

        String selectedGroup =
                loadPendingSelectedGroup(context).trim();

        ScheduleParser.ParseResult source =
                new ScheduleParser.ParseResult(
                        loadPendingEvents(context),
                        loadPendingGroups(context),
                        loadPendingWarnings(context),
                        loadPendingDiagnostics(context),
                        loadPendingPageCount(context)
                );

        ScheduleNormalizer.Result normalized =
                new ScheduleNormalizer().normalize(source);

        ScheduleValidator.ValidationResult validation =
                new ScheduleValidator().validate(
                        normalized.parseResult
                );

        savePending(
                context,
                normalized.parseResult,
                validation
        );

        if (!selectedGroup.isEmpty()) {
            for (String group :
                    normalized.parseResult.groups) {
                if (group.equalsIgnoreCase(selectedGroup)) {
                    setPendingSelectedGroup(
                            context,
                            group
                    );
                    break;
                }
            }
        }
    }

    public static boolean hasPending(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.contains(KEY_PENDING_EVENTS)
                && !"[]".equals(prefs.getString(KEY_PENDING_EVENTS, "[]"));
    }

    public static List<ScheduleEvent> loadPendingEvents(Context context) {
        return loadEventsFromKey(context, KEY_PENDING_EVENTS);
    }

    public static List<String> loadPendingGroups(Context context) {
        return loadStringsFromKey(context, KEY_PENDING_GROUPS);
    }

    public static List<String> loadPendingWarnings(Context context) {
        return loadStringsFromKey(context, KEY_PENDING_WARNINGS);
    }

    public static List<String> loadPendingDiagnostics(Context context) {
        return loadStringsFromKey(context, KEY_PENDING_DIAGNOSTICS);
    }

    public static List<ScheduleValidationIssue> loadPendingValidationIssues(Context context) {
        List<ScheduleValidationIssue> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY_PENDING_VALIDATION, "[]");

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                out.add(ScheduleValidationIssue.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }

        return out;
    }

    public static int loadPendingPageCount(Context context) {
        return prefs(context).getInt(KEY_PENDING_PAGE_COUNT, 0);
    }

    public static long loadPendingImportedAt(Context context) {
        return prefs(context).getLong(KEY_PENDING_IMPORTED_AT, 0L);
    }

    public static void setPendingSelectedGroup(Context context, String group) {
        prefs(context).edit()
                .putString(KEY_PENDING_SELECTED_GROUP, group == null ? "" : group.trim())
                .apply();
    }

    public static String loadPendingSelectedGroup(Context context) {
        return prefs(context).getString(KEY_PENDING_SELECTED_GROUP, "");
    }

    public static boolean pendingCanActivate(Context context) {
        for (ScheduleValidationIssue issue : loadPendingValidationIssues(context)) {
            if (issue.severity == ScheduleValidationIssue.Severity.ERROR) {
                return false;
            }
        }
        return hasPending(context);
    }

    public static void activatePending(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!hasPending(context)) return;

        String events = prefs.getString(KEY_PENDING_EVENTS, "[]");
        String groups = prefs.getString(KEY_PENDING_GROUPS, "[]");
        String warnings = prefs.getString(KEY_PENDING_WARNINGS, "[]");
        String diagnostics = prefs.getString(KEY_PENDING_DIAGNOSTICS, "[]");
        String validation = prefs.getString(KEY_PENDING_VALIDATION, "[]");
        int pageCount = prefs.getInt(KEY_PENDING_PAGE_COUNT, 0);
        long importedAt = prefs.getLong(KEY_PENDING_IMPORTED_AT, System.currentTimeMillis());

        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_ACTIVE_EVENTS, events)
                .putString(KEY_ACTIVE_GROUPS, groups)
                .putString(KEY_ACTIVE_WARNINGS, warnings)
                .putString(KEY_ACTIVE_DIAGNOSTICS, diagnostics)
                .putString(KEY_ACTIVE_VALIDATION, validation)
                .putInt(KEY_ACTIVE_PAGE_COUNT, pageCount)
                .putLong(KEY_ACTIVE_IMPORTED_AT, importedAt);

        clearPending(editor);
        editor.apply();
    }

    public static void discardPending(Context context) {
        SharedPreferences.Editor editor = prefs(context).edit();
        clearPending(editor);
        editor.apply();
    }

    private static void clearPending(SharedPreferences.Editor editor) {
        editor.remove(KEY_PENDING_EVENTS);
        editor.remove(KEY_PENDING_GROUPS);
        editor.remove(KEY_PENDING_WARNINGS);
        editor.remove(KEY_PENDING_DIAGNOSTICS);
        editor.remove(KEY_PENDING_VALIDATION);
        editor.remove(KEY_PENDING_PAGE_COUNT);
        editor.remove(KEY_PENDING_IMPORTED_AT);
        editor.remove(KEY_PENDING_SELECTED_GROUP);
    }

    public static List<ScheduleEvent> loadEvents(Context context) {
        return loadEventsFromKey(context, KEY_ACTIVE_EVENTS);
    }

    public static List<String> loadGroups(Context context) {
        return loadStringsFromKey(context, KEY_ACTIVE_GROUPS);
    }

    public static List<String> loadWarnings(Context context) {
        return loadStringsFromKey(context, KEY_ACTIVE_WARNINGS);
    }

    public static List<String> loadDiagnostics(Context context) {
        return loadStringsFromKey(context, KEY_ACTIVE_DIAGNOSTICS);
    }

    public static List<ScheduleValidationIssue> loadActiveValidationIssues(Context context) {
        List<ScheduleValidationIssue> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY_ACTIVE_VALIDATION, "[]");

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                out.add(ScheduleValidationIssue.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }

        return out;
    }

    public static int loadPageCount(Context context) {
        return prefs(context).getInt(KEY_ACTIVE_PAGE_COUNT, 0);
    }

    public static long loadActiveImportedAt(Context context) {
        return prefs(context).getLong(KEY_ACTIVE_IMPORTED_AT, 0L);
    }

    public static void saveImportError(Context context, String error) {
        prefs(context).edit()
                .putString(KEY_LAST_ERROR, error == null ? "" : error)
                .apply();
    }

    public static String loadLastImportError(Context context) {
        return prefs(context).getString(KEY_LAST_ERROR, "");
    }

    public static List<ScheduleEvent> forDateAndGroup(
            Context context,
            LocalDate date,
            String group
    ) {
        List<ScheduleEvent> out = new ArrayList<>();

        for (ScheduleEvent event : loadEvents(context)) {
            if (event.date.equals(date) && event.belongsTo(group)) {
                out.add(event);
            }
        }

        out.sort(Comparator.comparing(event -> event.start));
        return out;
    }

    public static List<ScheduleEvent> pendingForDateAndGroup(
            Context context,
            LocalDate date,
            String group
    ) {
        List<ScheduleEvent> out = new ArrayList<>();

        for (ScheduleEvent event : loadPendingEvents(context)) {
            if (event.date.equals(date) && event.belongsTo(group)) {
                out.add(event);
            }
        }

        out.sort(Comparator.comparing(event -> event.start));
        return out;
    }

    public static boolean hasData(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.contains(KEY_ACTIVE_EVENTS)
                && !"[]".equals(prefs.getString(KEY_ACTIVE_EVENTS, "[]"));
    }

    private static List<ScheduleEvent> loadEventsFromKey(Context context, String key) {
        List<ScheduleEvent> out = new ArrayList<>();
        String raw = prefs(context).getString(key, "[]");

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                out.add(ScheduleEvent.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }

        out.sort(Comparator.comparing(ScheduleEvent::startDateTime));
        return out;
    }

    private static List<String> loadStringsFromKey(Context context, String key) {
        List<String> out = new ArrayList<>();
        String raw = prefs(context).getString(key, "[]");

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                out.add(array.getString(i));
            }
        } catch (Exception ignored) {
        }

        return out;
    }
}
