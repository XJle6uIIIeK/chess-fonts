package com.n3k0.schedule;

public final class NotificationRenderer {
    public static final class Rendered {
        public final String title;
        public final String body;

        Rendered(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    public Rendered renderClass(
            ScheduleEvent event,
            String kind,
            int offsetMinutes
    ) {
        return renderClass(
                event,
                kind,
                offsetMinutes,
                false
        );
    }

    public Rendered renderClass(
            ScheduleEvent event,
            String kind,
            int offsetMinutes,
            boolean merged
    ) {
        String title;

        if (NotificationScheduler.KIND_LONG_BREAK.equals(kind)) {
            title = merged
                    ? "Большая перемена • следующая пара"
                    : "Большая перемена — следующая пара";
        } else if (NotificationScheduler.KIND_REPEAT.equals(kind)) {
            if (merged) {
                title = "Повтор • следующая пара";
            } else {
                title = offsetMinutes == 0
                        ? "Пара начинается сейчас"
                        : "Повтор: через " +
                        NotificationPrefs.formatDuration(offsetMinutes);
            }
        } else {
            title = offsetMinutes == 0
                    ? "Следующая пара начинается сейчас"
                    : "Следующая пара через " +
                    NotificationPrefs.formatDuration(offsetMinutes);
        }

        StringBuilder body = new StringBuilder();
        body.append(event.cleanSubject());

        if (!event.room.isBlank()) {
            body.append(" • каб. ")
                    .append(event.room);
        }

        body.append("\n")
                .append(event.pairNumber)
                .append("-я пара • ")
                .append(event.start)
                .append("–")
                .append(event.end);

        if (!event.teacher.isBlank()) {
            body.append("\n")
                    .append(event.teacher);
        }


        return new Rendered(
                title,
                body.toString()
        );
    }
}
