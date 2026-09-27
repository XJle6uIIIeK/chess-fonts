package com.n3k0.schedule;

import android.content.Context;
import android.content.SharedPreferences;

final class ScheduleMigrationManager {
    static final int CURRENT_SCHEMA = 6;

    private static final String PREF = "schedule_store";

    private ScheduleMigrationManager() {}

    static void ensureMigrated(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int version = prefs.getInt("schema_version", 0);

        if (version >= CURRENT_SCHEMA) return;

        SharedPreferences.Editor editor = prefs.edit();

        // v5 and earlier stored the current schedule directly in unprefixed keys.
        // v6 introduces active_* and pending_* slots. Existing data is promoted
        // to active so an app update does not force a new PDF import.
        if (prefs.contains("events") && !"[]".equals(prefs.getString("events", "[]"))) {
            editor.putString("active_events", prefs.getString("events", "[]"));
            editor.putString("active_groups", prefs.getString("groups", "[]"));
            editor.putString("active_warnings", prefs.getString("warnings", "[]"));
            editor.putString("active_diagnostics", prefs.getString("diagnostics", "[]"));
            editor.putInt("active_page_count", prefs.getInt("page_count", 0));
            editor.putLong("active_imported_at", System.currentTimeMillis());
        }

        editor.remove("events");
        editor.remove("groups");
        editor.remove("warnings");
        editor.remove("diagnostics");
        editor.remove("page_count");

        editor.putInt("schema_version", CURRENT_SCHEMA);
        editor.apply();
    }
}
