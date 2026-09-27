package com.n3k0.schedule;

import org.json.JSONException;
import org.json.JSONObject;

public final class ScheduleValidationIssue {
    public enum Severity {
        ERROR,
        WARNING
    }

    public final Severity severity;
    public final String code;
    public final String message;
    public final int sourcePage;

    public ScheduleValidationIssue(Severity severity, String code, String message) {
        this(severity, code, message, -1);
    }

    public ScheduleValidationIssue(Severity severity, String code, String message, int sourcePage) {
        this.severity = severity;
        this.code = code == null ? "" : code;
        this.message = message == null ? "" : message;
        this.sourcePage = sourcePage;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("severity", severity.name());
        object.put("code", code);
        object.put("message", message);
        object.put("page", sourcePage);
        return object;
    }

    public static ScheduleValidationIssue fromJson(JSONObject object) {
        Severity severity;
        try {
            severity = Severity.valueOf(object.optString("severity", "WARNING"));
        } catch (Exception ignored) {
            severity = Severity.WARNING;
        }

        return new ScheduleValidationIssue(
                severity,
                object.optString("code", ""),
                object.optString("message", ""),
                object.optInt("page", -1)
        );
    }
}
