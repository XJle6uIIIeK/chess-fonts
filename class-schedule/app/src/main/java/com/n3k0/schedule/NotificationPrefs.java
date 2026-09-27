package com.n3k0.schedule;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

public final class NotificationPrefs {
    private NotificationPrefs() {}

    public static final String KEY_MIGRATED = "notifications_v2_migrated";

    public static final String KEY_NEXT_ENABLED = "next_class_enabled";
    public static final String KEY_NEXT_OFFSET = "next_class_offset_minutes";

    public static final String KEY_REPEAT_ENABLED = "repeat_enabled";
    public static final String KEY_REPEAT_OFFSET = "repeat_offset_minutes";

    public static final String KEY_LONG_BREAK_ENABLED = "long_break_enabled";
    public static final String KEY_LONG_BREAK_MODE = "long_break_mode";
    public static final String KEY_LONG_BREAK_OFFSET = "long_break_offset_minutes";

    public static final String KEY_TOMORROW_ENABLED = "tomorrow_enabled";
    public static final String KEY_TOMORROW_HOUR = "tomorrow_hour";
    public static final String KEY_TOMORROW_MINUTE = "tomorrow_minute";

    public static final String MODE_BEFORE_NEXT = "before_next";
    public static final String MODE_AFTER_START = "after_start";

    public static final class Config {
        public final boolean nextEnabled;
        public final int nextOffsetMinutes;

        public final boolean repeatEnabled;
        public final int repeatOffsetMinutes;

        public final boolean longBreakEnabled;
        public final String longBreakMode;
        public final int longBreakOffsetMinutes;

        public final boolean tomorrowEnabled;
        public final int tomorrowHour;
        public final int tomorrowMinute;

        Config(
                boolean nextEnabled,
                int nextOffsetMinutes,
                boolean repeatEnabled,
                int repeatOffsetMinutes,
                boolean longBreakEnabled,
                String longBreakMode,
                int longBreakOffsetMinutes,
                boolean tomorrowEnabled,
                int tomorrowHour,
                int tomorrowMinute
        ) {
            this.nextEnabled = nextEnabled;
            this.nextOffsetMinutes = nextOffsetMinutes;
            this.repeatEnabled = repeatEnabled;
            this.repeatOffsetMinutes = repeatOffsetMinutes;
            this.longBreakEnabled = longBreakEnabled;
            this.longBreakMode = longBreakMode;
            this.longBreakOffsetMinutes = longBreakOffsetMinutes;
            this.tomorrowEnabled = tomorrowEnabled;
            this.tomorrowHour = tomorrowHour;
            this.tomorrowMinute = tomorrowMinute;
        }
    }

    public static Config load(Context context) {
        SharedPreferences prefs = NotificationScheduler.settings(context);
        migrateIfNeeded(prefs);

        return new Config(
                prefs.getBoolean(KEY_NEXT_ENABLED, true),
                clamp(prefs.getInt(KEY_NEXT_OFFSET, 30), 0, 24 * 60),
                prefs.getBoolean(KEY_REPEAT_ENABLED, true),
                clamp(prefs.getInt(KEY_REPEAT_OFFSET, 10), 0, 24 * 60),
                prefs.getBoolean(KEY_LONG_BREAK_ENABLED, false),
                normalizeMode(prefs.getString(KEY_LONG_BREAK_MODE, MODE_BEFORE_NEXT)),
                clamp(prefs.getInt(KEY_LONG_BREAK_OFFSET, 10), 0, 40),
                prefs.getBoolean(KEY_TOMORROW_ENABLED, true),
                clamp(prefs.getInt(KEY_TOMORROW_HOUR, 20), 0, 23),
                clamp(prefs.getInt(KEY_TOMORROW_MINUTE, 0), 0, 59)
        );
    }

    private static void migrateIfNeeded(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return;

        boolean oldRepeat = prefs.getBoolean(NotificationScheduler.KEY_REMINDER_10, true);
        boolean oldTomorrow = prefs.getBoolean(NotificationScheduler.KEY_TOMORROW_20, true);

        prefs.edit()
                .putBoolean(KEY_NEXT_ENABLED, true)
                .putInt(KEY_NEXT_OFFSET, 30)
                .putBoolean(KEY_REPEAT_ENABLED, oldRepeat)
                .putInt(KEY_REPEAT_OFFSET, 10)
                .putBoolean(KEY_LONG_BREAK_ENABLED, false)
                .putString(KEY_LONG_BREAK_MODE, MODE_BEFORE_NEXT)
                .putInt(KEY_LONG_BREAK_OFFSET, 10)
                .putBoolean(KEY_TOMORROW_ENABLED, oldTomorrow)
                .putInt(KEY_TOMORROW_HOUR, 20)
                .putInt(KEY_TOMORROW_MINUTE, 0)
                .putBoolean(KEY_MIGRATED, true)
                .apply();
    }

    public static String validate(Config config) {
        if (config == null) return "Настройки уведомлений не загружены.";

        if (config.nextEnabled && config.repeatEnabled) {
            if (config.repeatOffsetMinutes == config.nextOffsetMinutes) {
                return "Основное уведомление и повтор не могут иметь одинаковое время.";
            }
            if (config.repeatOffsetMinutes > config.nextOffsetMinutes) {
                return "Повтор должен приходить позже основного уведомления.";
            }
        }

        if (config.longBreakOffsetMinutes < 0 || config.longBreakOffsetMinutes > 40) {
            return "Настройка большой перемены должна быть в диапазоне 0–40 минут.";
        }

        return "";
    }

    public static boolean isValid(Config config) {
        return validate(config).isEmpty();
    }

    public static String formatDuration(int totalMinutes) {
        int value = Math.max(0, totalMinutes);
        int hours = value / 60;
        int minutes = value % 60;

        if (hours == 0) {
            return minutes + " " + minuteWord(minutes);
        }
        if (minutes == 0) {
            return hours + " " + hourWord(hours);
        }
        return hours + " " + hourWord(hours) + " " + minutes + " " + minuteWord(minutes);
    }

    public static String formatClock(int hour, int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    public static String longBreakSummary(Config config) {
        if (MODE_AFTER_START.equals(config.longBreakMode)) {
            if (config.longBreakOffsetMinutes == 0) {
                return "В начале большой перемены";
            }
            return "Через " + formatDuration(config.longBreakOffsetMinutes) + " после начала";
        }
        if (config.longBreakOffsetMinutes == 0) {
            return "В момент начала следующей пары";
        }
        return "За " + formatDuration(config.longBreakOffsetMinutes) + " до следующей пары";
    }

    private static String normalizeMode(String mode) {
        return MODE_AFTER_START.equals(mode) ? MODE_AFTER_START : MODE_BEFORE_NEXT;
    }

    private static String minuteWord(int n) {
        int a = Math.abs(n) % 100;
        int b = a % 10;
        if (a >= 11 && a <= 14) return "минут";
        if (b == 1) return "минуту";
        if (b >= 2 && b <= 4) return "минуты";
        return "минут";
    }

    private static String hourWord(int n) {
        int a = Math.abs(n) % 100;
        int b = a % 10;
        if (a >= 11 && a <= 14) return "часов";
        if (b == 1) return "час";
        if (b >= 2 && b <= 4) return "часа";
        return "часов";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
