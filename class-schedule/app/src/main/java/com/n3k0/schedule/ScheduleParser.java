package com.n3k0.schedule;

import android.graphics.Path;
import android.graphics.PointF;

import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Geometry-aware parser for the monthly table layout used by the supplied schedule.
 * It deliberately works with positioned PDF words instead of flattening the whole
 * table into plain text. Merged lecture cells are reconstructed from the horizontal
 * center of their text and the room column of the last participating group.
 */
public final class ScheduleParser {
    private static final Pattern GROUP_PATTERN = Pattern.compile("(?=.*\\p{L})(?=.*\\d)[\\p{L}\\d-]+-[\\p{L}\\d-]+", Pattern.UNICODE_CASE);
    private static final Pattern INITIALS_PATTERN = Pattern.compile(".*[А-ЯЁA-Z]\\.[А-ЯЁA-Z]\\..*");
    private static final Pattern ROOM_TOKEN = Pattern.compile("^(?:\\d{1,3}|Зал|№\\s*\\d+|№\\d+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SPECIAL_TIME = Pattern.compile("(\\d{1,2})[.:](\\d{2})\\s*[-–—]\\s*(\\d{1,2})[.:](\\d{2})");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.ROOT);

    public static final class ParseResult {
        public final List<ScheduleEvent> events;
        public final List<String> groups;
        public final List<String> warnings;
        public final List<String> diagnostics;
        public final int pageCount;

        ParseResult(List<ScheduleEvent> events, List<String> groups, List<String> warnings,
                    List<String> diagnostics, int pageCount) {
            this.events = events;
            this.groups = groups;
            this.warnings = warnings;
            this.diagnostics = diagnostics;
            this.pageCount = pageCount;
        }
    }

    private static final class WordBox {
        final String text;
        final float x0;
        final float x1;
        final float y;
        final int page;

        WordBox(String text, float x0, float x1, float y, int page) {
            this.text = text;
            this.x0 = x0;
            this.x1 = x1;
            this.y = y;
            this.page = page;
        }

        float centerX() { return (x0 + x1) * 0.5f; }

        WordBox globalY() {
            return new WordBox(text, x0, x1, y + page * 1000f, page);
        }
    }

    private static final class WordStripper extends PDFTextStripper {
        final List<WordBox> words = new ArrayList<>();
        private final int pageIndex;

        WordStripper(int pageIndex) throws IOException {
            super();
            this.pageIndex = pageIndex;
            setSortByPosition(true);
            setSuppressDuplicateOverlappingText(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions != null && !positions.isEmpty()) {
                StringBuilder token = new StringBuilder();
                float x0 = Float.MAX_VALUE;
                float x1 = -Float.MAX_VALUE;
                float ySum = 0f;
                int count = 0;
                TextPosition previous = null;

                for (TextPosition p : positions) {
                    String unicode = p.getUnicode();
                    if (unicode == null) unicode = "";
                    float px0 = p.getXDirAdj();
                    float px1 = px0 + p.getWidthDirAdj();

                    boolean whitespace = unicode.trim().isEmpty();
                    float gap = previous == null ? 0f
                            : px0 - (previous.getXDirAdj() + previous.getWidthDirAdj());
                    float gapThreshold = previous == null ? Float.MAX_VALUE
                            : Math.max(1.2f, previous.getWidthDirAdj() * 0.65f);
                    boolean geometryBreak = previous != null && gap > gapThreshold;

                    if ((whitespace || geometryBreak) && token.length() > 0) {
                        words.add(new WordBox(token.toString().trim(), x0, x1, ySum / Math.max(1, count), pageIndex));
                        token.setLength(0);
                        x0 = Float.MAX_VALUE;
                        x1 = -Float.MAX_VALUE;
                        ySum = 0f;
                        count = 0;
                    }

                    if (!whitespace) {
                        token.append(unicode);
                        x0 = Math.min(x0, px0);
                        x1 = Math.max(x1, px1);
                        ySum += p.getYDirAdj();
                        count++;
                    }
                    previous = p;
                }

                if (token.length() > 0) {
                    words.add(new WordBox(token.toString().trim(), x0, x1, ySum / Math.max(1, count), pageIndex));
                }
            } else {
                String clean = text == null ? "" : text.trim();
                if (!clean.isEmpty()) {
                    words.add(new WordBox(clean, 0f, 0f, 0f, pageIndex));
                }
            }
            super.writeString(text, positions);
        }
    }

    private static final class VerticalSegment {
        final float x;
        final float top;
        final float bottom;

        VerticalSegment(float x, float top, float bottom) {
            this.x = x;
            this.top = Math.min(top, bottom);
            this.bottom = Math.max(top, bottom);
        }

        boolean crosses(float y) {
            return y >= top - 0.8f && y <= bottom + 0.8f;
        }
    }

    private static final class GridExtractor extends PDFGraphicsStreamEngine {
        final List<VerticalSegment> verticals = new ArrayList<>();
        private final float pageHeight;
        private PointF current = new PointF();

        GridExtractor(PDPage page) {
            super(page);
            pageHeight = page.getMediaBox().getHeight();
        }

        private void record(float x1, float y1, float x2, float y2) {
            if (Math.abs(x1 - x2) > 1.0f || Math.abs(y1 - y2) < 3.0f) return;
            float top1 = pageHeight - y1;
            float top2 = pageHeight - y2;
            verticals.add(new VerticalSegment((x1 + x2) * 0.5f, top1, top2));
        }

        @Override
        public void appendRectangle(PointF p0, PointF p1, PointF p2, PointF p3) {
            record(p0.x, p0.y, p1.x, p1.y);
            record(p1.x, p1.y, p2.x, p2.y);
            record(p2.x, p2.y, p3.x, p3.y);
            record(p3.x, p3.y, p0.x, p0.y);
            current = new PointF(p0.x, p0.y);
        }

        @Override public void drawImage(PDImage pdImage) {}
        @Override public void clip(Path.FillType windingRule) {}

        @Override
        public void moveTo(float x, float y) {
            current = new PointF(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            record(current.x, current.y, x, y);
            current = new PointF(x, y);
        }

        @Override public PointF getCurrentPoint() { return current; }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            current = new PointF(x3, y3);
        }

        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(Path.FillType windingRule) {}
        @Override public void fillAndStrokePath(Path.FillType windingRule) {}
        @Override public void shadingFill(COSName shadingName) {}
    }

    private static final class PageData {
        final int pageIndex;
        final float width;
        final float height;
        final List<WordBox> words;
        final List<VerticalSegment> verticals;
        List<Anchor> anchors = new ArrayList<>();

        PageData(int pageIndex, float width, float height, List<WordBox> words,
                 List<VerticalSegment> verticals) {
            this.pageIndex = pageIndex;
            this.width = width;
            this.height = height;
            this.words = words;
            this.verticals = verticals;
        }
    }

    private static final class Anchor {
        final int pair;
        final float y;
        Anchor(int pair, float y) { this.pair = pair; this.y = y; }
    }

    private static final class GroupInfo {
        final String name;
        final float center;
        GroupInfo(String name, float center) { this.name = name; this.center = center; }
    }

    private static final class LineCluster {
        float y;
        final List<WordBox> words = new ArrayList<>();
        LineCluster(float y) { this.y = y; }
    }

    private static final class Block {
        float center;
        final List<Float> centers = new ArrayList<>();
        final List<TextLine> lines = new ArrayList<>();
        Block(float center) { this.center = center; centers.add(center); }
        void add(float c, float y, String text) {
            centers.add(c);
            float sum = 0f;
            for (float v : centers) sum += v;
            center = sum / centers.size();
            lines.add(new TextLine(y, text));
        }
    }

    private static final class TextLine {
        final float y;
        final String text;
        TextLine(float y, String text) { this.y = y; this.text = text; }
    }

    private static final class RoomBlock {
        final int endGroupHint;
        final String text;
        final float center;
        final float y;
        final int page;

        RoomBlock(int endGroupHint, String text, float center, float y, int page) {
            this.endGroupHint = endGroupHint;
            this.text = text;
            this.center = center;
            this.y = y;
            this.page = page;
        }
    }

    private static final class CellRange {
        final int startGroup;
        final int endGroup;
        final float left;
        final float right;

        CellRange(int startGroup, int endGroup, float left, float right) {
            this.startGroup = startGroup;
            this.endGroup = endGroup;
            this.left = left;
            this.right = right;
        }

        float center() { return (left + right) * 0.5f; }
    }

    private static final class TimedLine {
        final float center;
        final String text;
        TimedLine(float center, String text) { this.center = center; this.text = text; }
    }

    private static final class EventDraft {
        final Block block;
        final int startGroup;
        final int endGroup;
        final String room;
        String specialTime;

        EventDraft(Block block, int startGroup, int endGroup, String room) {
            this.block = block;
            this.startGroup = startGroup;
            this.endGroup = endGroup;
            this.room = room;
        }
    }

    private static final class ParsedText {
        final String subject;
        final String teacher;
        final LocalTime customStart;
        final LocalTime customEnd;

        ParsedText(String subject, String teacher, LocalTime customStart, LocalTime customEnd) {
            this.subject = subject;
            this.teacher = teacher;
            this.customStart = customStart;
            this.customEnd = customEnd;
        }
    }

    private static final class RowKey {
        final LocalDate date;
        final int pair;
        RowKey(LocalDate date, int pair) { this.date = date; this.pair = pair; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof RowKey)) return false;
            RowKey r = (RowKey) o;
            return pair == r.pair && date.equals(r.date);
        }
        @Override public int hashCode() { return date.hashCode() * 31 + pair; }
    }

    public ParseResult parse(InputStream input) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try (PDDocument document = PDDocument.load(input)) {
            if (document.getNumberOfPages() == 0) throw new IOException("PDF не содержит страниц");
            diagnostics.add("Страниц: " + document.getNumberOfPages());

            List<PageData> pages = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                WordStripper stripper = new WordStripper(i);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                stripper.getText(document);
                PDPage pdPage = document.getPage(i);
                float width = pdPage.getMediaBox().getWidth();
                float height = pdPage.getMediaBox().getHeight();
                GridExtractor gridExtractor = new GridExtractor(pdPage);
                gridExtractor.processPage(pdPage);
                pages.add(new PageData(
                        i, width, height, new ArrayList<>(stripper.words),
                        new ArrayList<>(gridExtractor.verticals)
                ));
            }

            diagnostics.add("Текстовых фрагментов на первой странице: " + pages.get(0).words.size());

            Header header = detectHeader(pages.get(0));
            if (header.groups.size() < 2) {
                throw new IOException("Не удалось найти колонки групп в PDF");
            }

            List<String> headerGroupNames = new ArrayList<>();
            for (GroupInfo g : header.groups) headerGroupNames.add(g.name);
            diagnostics.add("Найденные группы: " + String.join(", ", headerGroupNames));

            for (PageData page : pages) {
                page.anchors = detectAnchors(page, header);
                diagnostics.add("Страница " + (page.pageIndex + 1) + ": строк пар = " + page.anchors.size());
            }

            Map<RowKey, List<WordBox>> rows = collectRows(pages, header, warnings);
            List<ScheduleEvent> events = new ArrayList<>();
            Set<String> dedupe = new HashSet<>();

            for (Map.Entry<RowKey, List<WordBox>> entry : rows.entrySet()) {
                RowKey key = entry.getKey();
                List<EventDraft> drafts = parseRow(entry.getValue(), header, pages);
                for (EventDraft draft : drafts) {
                    List<TextLine> sortedLines = new ArrayList<>(draft.block.lines);
                    sortedLines.sort(Comparator.comparingDouble(a -> a.y));
                    List<String> lineTexts = new ArrayList<>();
                    for (TextLine line : sortedLines) lineTexts.add(line.text);
                    ParsedText parsed = parseText(lineTexts);
                    if (parsed.subject.isBlank()) continue;

                    LocalTime[] standard = standardTimes(key.pair);
                    LocalTime start = standard[0];
                    LocalTime end = standard[1];
                    if (draft.specialTime != null) {
                        LocalTime[] custom = parseTimeRange(draft.specialTime);
                        if (custom != null) { start = custom[0]; end = custom[1]; }
                    }
                    if (parsed.customStart != null && parsed.customEnd != null) {
                        start = parsed.customStart;
                        end = parsed.customEnd;
                    }

                    List<String> groups = new ArrayList<>();
                    for (int g = draft.startGroup; g <= draft.endGroup; g++) groups.add(header.groups.get(g).name);
                    int sourcePage = entry.getValue().isEmpty() ? 0 : entry.getValue().get(0).page + 1;
                    ScheduleEvent event = new ScheduleEvent(key.date, key.pair, start, end,
                            parsed.subject, parsed.teacher, cleanupRoom(draft.room), groups, sourcePage);
                    if (dedupe.add(event.stableKey())) events.add(event);
                }
            }

            events.sort(Comparator.comparing(ScheduleEvent::startDateTime).thenComparing(e -> e.subject));
            List<String> groupNames = new ArrayList<>();
            for (GroupInfo g : header.groups) groupNames.add(g.name);

            if (events.isEmpty()) throw new IOException("Таблица найдена, но занятия распознать не удалось");
            diagnostics.add("Распознано занятий: " + events.size());
            diagnostics.add("Предупреждений восстановления: " + warnings.size());
            return new ParseResult(events, groupNames, warnings, diagnostics, document.getNumberOfPages());
        }
    }

    private static final class Header {
        final List<GroupInfo> groups;
        final List<Float> roomCenters;
        final float medianSpacing;
        final float groupAreaLeft;

        Header(List<GroupInfo> groups, List<Float> roomCenters, float medianSpacing, float groupAreaLeft) {
            this.groups = groups;
            this.roomCenters = roomCenters;
            this.medianSpacing = medianSpacing;
            this.groupAreaLeft = groupAreaLeft;
        }
    }

    private Header detectHeader(PageData page) throws IOException {
        List<GroupInfo> groups = new ArrayList<>();

        // 1) Normal path: after positioned tokenization each group is its own word box.
        for (WordBox w : page.words) {
            String normalized = normalizeGroupCandidate(w.text);
            if (looksLikeGroup(normalized)) {
                groups.add(new GroupInfo(normalized, w.centerX()));
            }
        }

        // 2) Fallback: some PDF producers split a group into several adjacent text runs.
        // Rebuild every visual line and test short adjacent sequences.
        if (groups.size() < 2) {
            List<LineCluster> headerLines = new ArrayList<>();
            for (WordBox w : page.words) {
                if (w.y > page.words.stream().map(x -> x.y).min(Float::compare).orElse(0f) + 260f) continue;
                LineCluster line = null;
                for (LineCluster candidate : headerLines) {
                    if (Math.abs(candidate.y - w.y) < 3.5f) {
                        line = candidate;
                        break;
                    }
                }
                if (line == null) {
                    line = new LineCluster(w.y);
                    headerLines.add(line);
                }
                line.words.add(w);
            }

            for (LineCluster line : headerLines) {
                line.words.sort(Comparator.comparingDouble(w -> w.x0));
                for (int i = 0; i < line.words.size(); i++) {
                    StringBuilder joined = new StringBuilder();
                    float sx0 = line.words.get(i).x0;
                    float sx1 = line.words.get(i).x1;
                    for (int j = i; j < Math.min(line.words.size(), i + 5); j++) {
                        WordBox part = line.words.get(j);
                        if (j > i) {
                            WordBox prev = line.words.get(j - 1);
                            float gap = part.x0 - prev.x1;
                            if (gap > page.width * 0.025f) break;
                        }
                        joined.append(part.text);
                        sx1 = part.x1;
                        String normalized = normalizeGroupCandidate(joined.toString());
                        if (looksLikeGroup(normalized)) {
                            groups.add(new GroupInfo(normalized, (sx0 + sx1) * 0.5f));
                            break;
                        }
                    }
                }
            }
        }

        groups.sort(Comparator.comparingDouble(g -> g.center));
        LinkedHashMap<String, GroupInfo> unique = new LinkedHashMap<>();
        for (GroupInfo g : groups) {
            GroupInfo old = unique.get(g.name);
            if (old == null || g.center < old.center) unique.put(g.name, g);
        }
        groups = new ArrayList<>(unique.values());
        groups.sort(Comparator.comparingDouble(g -> g.center));

        if (groups.size() < 2) {
            StringBuilder diagnostic = new StringBuilder();
            page.words.stream()
                    .sorted(Comparator.comparingDouble((WordBox w) -> w.y).thenComparingDouble(w -> w.x0))
                    .limit(80)
                    .forEach(w -> diagnostic.append(w.text).append(' '));
            throw new IOException("Не удалось распознать группы в заголовке. Фрагмент: "
                    + diagnostic.toString().replaceAll("\\s+", " ").trim());
        }

        List<Float> spacings = new ArrayList<>();
        for (int i = 1; i < groups.size(); i++) {
            float spacing = groups.get(i).center - groups.get(i - 1).center;
            if (spacing > 1f) spacings.add(spacing);
        }
        float medianSpacing = median(spacings);
        float groupAreaLeft = groups.get(0).center - medianSpacing * 0.48f;

        List<Float> audWords = new ArrayList<>();
        for (WordBox w : page.words) {
            String lower = w.text.toLowerCase(Locale.ROOT).replace(" ", "");
            if ((lower.startsWith("ауд") || lower.equals("ауд.")) && w.centerX() > groupAreaLeft) {
                audWords.add(w.centerX());
            }
        }
        Collections.sort(audWords);

        List<Float> roomCenters = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            float gc = groups.get(i).center;
            float rightLimit = i + 1 < groups.size()
                    ? (gc + groups.get(i + 1).center) * 0.5f + medianSpacing * 0.14f
                    : gc + medianSpacing * 0.75f;
            Float best = null;
            float bestDist = Float.MAX_VALUE;
            for (float a : audWords) {
                if (a > gc && a < rightLimit) {
                    float d = a - gc;
                    if (d < bestDist) {
                        best = a;
                        bestDist = d;
                    }
                }
            }
            roomCenters.add(best != null ? best : gc + medianSpacing * 0.50f);
        }
        return new Header(groups, roomCenters, medianSpacing, groupAreaLeft);
    }

    private String normalizeGroupCandidate(String raw) {
        if (raw == null) return "";
        return raw
                .replace('–', '-')
                .replace('—', '-')
                .replace('−', '-')
                .replaceAll("\\s+", "")
                .replaceAll("[^\\p{L}\\d-]", "")
                .trim();
    }

    private boolean looksLikeGroup(String value) {
        if (value == null || value.length() < 7 || value.length() > 40) return false;
        if (!value.matches(".*\\p{L}.*") || !value.matches(".*\\d.*")) return false;
        int hyphens = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '-') hyphens++;
        if (hyphens < 2) return false;
        return value.matches("\\d{1,3}-[\\p{L}\\d]+-?\\d*")
                || value.matches("\\d{1,3}-[\\p{L}\\d]+\\d{2}-\\d+")
                || GROUP_PATTERN.matcher(value).matches();
    }

    private List<Anchor> detectAnchors(PageData page, Header header) {
        List<Anchor> anchors = new ArrayList<>();

        // The old implementation used 5.5%..9% of the page width. On this
        // timetable that range overlaps BOTH the "Занятие №" column and the
        // neighbouring "Урок №" column, so lesson numbers 1..5 were sometimes
        // mistaken for pair numbers. That corrupts row boundaries and can shift
        // whole days.
        //
        // Derive the pair-number column from the left edge of the actual group
        // table instead. In this layout the technical columns are:
        // [day][pair][lesson][time] | [groups...].
        float xMin = header.groupAreaLeft * 0.30f;
        float xMax = header.groupAreaLeft * 0.50f;

        for (WordBox w : page.words) {
            float x = w.centerX();
            if (x < xMin || x > xMax) continue;
            if (!w.text.matches("[1-5]")) continue;
            anchors.add(new Anchor(Integer.parseInt(w.text), w.y));
        }

        anchors.sort(Comparator.comparingDouble(a -> a.y));

        // Collapse accidental duplicate text runs at the same visual row.
        List<Anchor> clean = new ArrayList<>();
        for (Anchor a : anchors) {
            boolean duplicate = false;
            for (Anchor old : clean) {
                if (old.pair == a.pair && Math.abs(old.y - a.y) < 2.0f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) clean.add(a);
        }
        return clean;
    }

    private Map<RowKey, List<WordBox>> collectRows(List<PageData> pages, Header header, List<String> warnings) {
        Map<RowKey, List<WordBox>> rows = new LinkedHashMap<>();
        LocalDate carryDate = null;

        for (PageData page : pages) {
            if (page.anchors.isEmpty()) continue;
            LocalDate carryBeforePage = carryDate;
            List<LocalDate> explicitDates = extractDates(page.words);
            List<List<Integer>> dayGroups = groupAnchorIndexes(page.anchors);
            Map<Integer, LocalDate> anchorDates = new HashMap<>();
            int dateIndex = 0;

            for (int dayIndex = 0; dayIndex < dayGroups.size(); dayIndex++) {
                List<Integer> indexes = dayGroups.get(dayIndex);
                int firstPair = page.anchors.get(indexes.get(0)).pair;
                LocalDate assigned;
                if (dayIndex == 0 && firstPair != 1 && carryDate != null) {
                    assigned = carryDate;
                } else if (dateIndex < explicitDates.size()) {
                    assigned = explicitDates.get(dateIndex++);
                } else if (carryDate != null) {
                    assigned = carryDate.plusDays(1);
                    warnings.add("Стр. " + (page.pageIndex + 1) + ": дата восстановлена как " + SHORT_DATE.format(assigned));
                } else {
                    continue;
                }
                for (int idx : indexes) anchorDates.put(idx, assigned);
                carryDate = assigned;
            }

            List<Float> ys = new ArrayList<>();
            for (Anchor a : page.anchors) ys.add(a.y);
            List<float[]> bounds = new ArrayList<>();
            for (int i = 0; i < ys.size(); i++) {
                float y = ys.get(i);
                float top;
                float bottom;
                if (i > 0) top = (ys.get(i - 1) + y) * 0.5f;
                else if (ys.size() > 1) top = y - (ys.get(1) - y) * 0.5f;
                else top = y - 20f;
                if (i < ys.size() - 1) bottom = (y + ys.get(i + 1)) * 0.5f;
                else if (i > 0) bottom = y + (y - ys.get(i - 1)) * 0.5f;
                else bottom = y + 20f;
                bounds.add(new float[]{top, bottom});
            }

            for (int i = 0; i < page.anchors.size(); i++) {
                LocalDate date = anchorDates.get(i);
                if (date == null) continue;
                RowKey key = new RowKey(date, page.anchors.get(i).pair);
                List<WordBox> dest = rows.computeIfAbsent(key, k -> new ArrayList<>());
                float top = bounds.get(i)[0];
                float bottom = bounds.get(i)[1];
                for (WordBox w : page.words) {
                    if (w.x0 < header.groupAreaLeft) continue;
                    if (w.y >= top && w.y < bottom) dest.add(w.globalY());
                }
            }

            int firstPair = page.anchors.get(0).pair;
            float firstTop = bounds.get(0)[0];
            if (carryBeforePage != null && firstPair > 1) {
                RowKey previous = new RowKey(carryBeforePage, firstPair - 1);
                List<WordBox> dest = rows.computeIfAbsent(previous, k -> new ArrayList<>());
                for (WordBox w : page.words) {
                    if (w.x0 >= header.groupAreaLeft && w.y < firstTop - 0.5f) dest.add(w.globalY());
                }
            }
        }
        return rows;
    }

    private List<LocalDate> extractDates(List<WordBox> words) {
        LinkedHashSet<LocalDate> unique = new LinkedHashSet<>();
        for (WordBox w : words) {
            LocalDate d = normalizeDateWord(w.text);
            if (d != null) unique.add(d);
        }

        // IMPORTANT: the date labels in this timetable are rotated vertically.
        // PDFBox's YDirAdj for rotated text does not preserve top-to-bottom day
        // order reliably; on page 7 it can expose 25.09 before 23.09, which
        // shifts the entire day's timetable by two days.
        //
        // This is a monthly chronological timetable, so the only stable source
        // of ordering is the date value itself. Sort dates chronologically,
        // then map them to consecutive day blocks. Continuations at page starts
        // are still handled separately via carryDate.
        List<LocalDate> out = new ArrayList<>(unique);
        Collections.sort(out);
        return out;
    }

    private LocalDate normalizeDateWord(String source) {
        if (source == null) return null;
        String s = source.trim();
        String[] candidates = new String[]{s, new StringBuilder(s).reverse().toString()};
        for (String candidate : candidates) {
            if (!candidate.matches("\\d{2}\\.\\d{2}\\.\\d{2}")) continue;
            String[] p = candidate.split("\\.");
            try {
                int day = Integer.parseInt(p[0]);
                int month = Integer.parseInt(p[1]);
                int year = Integer.parseInt(p[2]);
                if (year < 20 || year > 99) continue;
                return LocalDate.of(2000 + year, month, day);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private List<List<Integer>> groupAnchorIndexes(List<Anchor> anchors) {
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        Integer previous = null;
        for (int i = 0; i < anchors.size(); i++) {
            int pair = anchors.get(i).pair;
            if (previous != null && pair <= previous) {
                out.add(current);
                current = new ArrayList<>();
            }
            current.add(i);
            previous = pair;
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    private List<EventDraft> parseRow(List<WordBox> rowWords, Header header, List<PageData> pages) {
        float roomTolerance = header.medianSpacing * 0.18f;
        float horizontalGap = header.medianSpacing * 0.205f;
        float blockMerge = header.medianSpacing * 0.165f;
        float roomMatch = header.medianSpacing * 0.37f;
        float fallbackMatch = header.medianSpacing * 0.27f;

        Map<Integer, List<WordBox>> roomWords = new HashMap<>();
        List<WordBox> content = new ArrayList<>();
        for (WordBox w : rowWords) {
            int nearestRoom = nearestIndex(header.roomCenters, w.centerX());
            if (nearestRoom >= 0 && ROOM_TOKEN.matcher(w.text.trim()).matches()
                    && Math.abs(header.roomCenters.get(nearestRoom) - w.centerX()) < roomTolerance) {
                roomWords.computeIfAbsent(nearestRoom, k -> new ArrayList<>()).add(w);
            } else {
                content.add(w);
            }
        }

        List<LineCluster> lines = new ArrayList<>();
        content.sort(Comparator.comparingDouble((WordBox w) -> w.y).thenComparingDouble(w -> w.x0));
        for (WordBox w : content) {
            LineCluster target = null;
            for (LineCluster line : lines) {
                if (Math.abs(line.y - w.y) < 3.0f) {
                    target = line;
                    break;
                }
            }
            if (target == null) {
                target = new LineCluster(w.y);
                lines.add(target);
            }
            target.words.add(w);
            float sum = 0f;
            for (WordBox item : target.words) sum += item.y;
            target.y = sum / target.words.size();
        }
        lines.sort(Comparator.comparingDouble(l -> l.y));

        List<TextCluster> textClusters = new ArrayList<>();
        for (LineCluster line : lines) {
            line.words.sort(Comparator.comparingDouble(w -> w.x0));
            List<WordBox> current = new ArrayList<>();
            WordBox previous = null;
            for (WordBox w : line.words) {
                if (previous != null && w.x0 - previous.x1 > horizontalGap) {
                    if (!current.isEmpty()) textClusters.add(toTextCluster(line.y, current));
                    current = new ArrayList<>();
                }
                current.add(w);
                previous = w;
            }
            if (!current.isEmpty()) textClusters.add(toTextCluster(line.y, current));
        }
        textClusters.sort(Comparator.comparingDouble((TextCluster tc) -> tc.y)
                .thenComparingDouble(tc -> tc.center));

        List<TimedLine> timedLines = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        for (TextCluster cluster : textClusters) {
            String compact = cluster.text.replace(" ", "");
            if (SPECIAL_TIME.matcher(compact).matches()) {
                timedLines.add(new TimedLine(cluster.center, cluster.text));
                continue;
            }

            Block nearest = null;
            float best = Float.MAX_VALUE;
            for (Block block : blocks) {
                float d = Math.abs(block.center - cluster.center);
                if (d < best) {
                    best = d;
                    nearest = block;
                }
            }
            if (nearest != null && best < blockMerge) {
                nearest.add(cluster.center, cluster.y, cluster.text);
            } else {
                Block block = new Block(cluster.center);
                block.lines.add(new TextLine(cluster.y, cluster.text));
                blocks.add(block);
            }
        }

        List<RoomBlock> rooms = new ArrayList<>();
        for (Map.Entry<Integer, List<WordBox>> e : roomWords.entrySet()) {
            List<WordBox> ws = e.getValue();
            ws.sort(Comparator.comparingDouble((WordBox w) -> w.y).thenComparingDouble(w -> w.x0));
            StringBuilder sb = new StringBuilder();
            float xSum = 0f;
            float ySum = 0f;
            for (WordBox w : ws) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(w.text);
                xSum += w.centerX();
                ySum += w.y;
            }
            WordBox first = ws.get(0);
            rooms.add(new RoomBlock(
                    e.getKey(),
                    sb.toString(),
                    xSum / ws.size(),
                    ySum / ws.size(),
                    first.page
            ));
        }
        rooms.sort(Comparator.comparingInt(r -> r.endGroupHint));

        List<EventDraft> result = new ArrayList<>();
        List<Block> unassigned = new ArrayList<>(blocks);

        // Primary path: use the actual vertical table borders at the Y of the
        // auditorium cell. The subject cell immediately to the left defines
        // exactly which group columns the lesson spans.
        for (RoomBlock room : rooms) {
            CellRange cell = subjectCellBeforeRoom(room, header, pages);
            Block bestBlock = null;

            if (cell != null) {
                float bestScore = Float.MAX_VALUE;
                for (Block block : unassigned) {
                    float score = Math.abs(block.center - cell.center());
                    if (block.center < cell.left - 4f || block.center > cell.right + 4f) {
                        score += header.medianSpacing;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        bestBlock = block;
                    }
                }
                if (bestBlock != null) {
                    unassigned.remove(bestBlock);
                    result.add(new EventDraft(
                            bestBlock, cell.startGroup, cell.endGroup, room.text
                    ));
                    continue;
                }
            }

            // Fallback for malformed PDFs where drawing commands are absent.
            float bestScore = Float.MAX_VALUE;
            int bestStart = -1;
            for (Block block : unassigned) {
                for (int startGroup = 0; startGroup <= room.endGroupHint; startGroup++) {
                    float expected = spanCenter(header.groups, startGroup, room.endGroupHint);
                    float score = Math.abs(expected - block.center);
                    if (score < bestScore) {
                        bestScore = score;
                        bestBlock = block;
                        bestStart = startGroup;
                    }
                }
            }
            if (bestBlock != null && bestStart >= 0 && bestScore < roomMatch) {
                unassigned.remove(bestBlock);
                result.add(new EventDraft(
                        bestBlock, bestStart, room.endGroupHint, room.text
                ));
            }
        }

        // Cells without an auditorium (e.g. "День самостоятельной работы" or
        // "Разговоры о важном") are also assigned by the real table interval.
        for (Block block : unassigned) {
            CellRange cell = cellForBlock(block, header, pages);
            if (cell != null) {
                result.add(new EventDraft(
                        block, cell.startGroup, cell.endGroup, ""
                ));
                continue;
            }

            int bestStart = -1;
            int bestEnd = -1;
            int bestLength = Integer.MAX_VALUE;
            float bestScore = Float.MAX_VALUE;
            for (int startGroup = 0; startGroup < header.groups.size(); startGroup++) {
                for (int endGroup = startGroup; endGroup < header.groups.size(); endGroup++) {
                    float score = Math.abs(spanCenter(header.groups, startGroup, endGroup) - block.center);
                    int length = endGroup - startGroup;
                    if (score < bestScore - 0.01f ||
                            (Math.abs(score - bestScore) < 0.01f && length < bestLength)) {
                        bestScore = score;
                        bestLength = length;
                        bestStart = startGroup;
                        bestEnd = endGroup;
                    }
                }
            }
            if (bestStart >= 0 && bestScore < fallbackMatch) {
                result.add(new EventDraft(block, bestStart, bestEnd, ""));
            }
        }

        for (EventDraft draft : result) {
            float expected = spanCenter(header.groups, draft.startGroup, draft.endGroup);
            float best = Float.MAX_VALUE;
            TimedLine chosen = null;
            for (TimedLine line : timedLines) {
                float d = Math.abs(line.center - expected);
                if (d < best) {
                    best = d;
                    chosen = line;
                }
            }
            if (chosen != null && best < roomMatch) draft.specialTime = chosen.text;
        }
        return result;
    }

    private CellRange subjectCellBeforeRoom(RoomBlock room, Header header, List<PageData> pages) {
        List<Float> boundaries = boundariesAt(room.page, room.y, header, pages);
        int roomInterval = intervalAt(boundaries, room.center);
        if (roomInterval <= 0) return null;

        // There can be very narrow duplicated border strips between two cells.
        // Walk left until we reach an interval that actually contains one or
        // more group centers.
        for (int i = roomInterval - 1; i >= 0; i--) {
            CellRange range = groupsInside(boundaries.get(i), boundaries.get(i + 1), header);
            if (range != null) return range;
        }
        return null;
    }

    private CellRange cellForBlock(Block block, Header header, List<PageData> pages) {
        if (block.lines.isEmpty()) return null;
        float y = block.lines.get(0).y;
        int page = pageFromGlobalY(y);
        List<Float> boundaries = boundariesAt(page, y, header, pages);
        int interval = intervalAt(boundaries, block.center);
        if (interval < 0) return null;

        CellRange direct = groupsInside(boundaries.get(interval), boundaries.get(interval + 1), header);
        if (direct != null) return direct;

        // If the text center happens to fall in a tiny border strip, inspect
        // the adjacent interval and choose the closest real subject cell.
        CellRange left = interval > 0
                ? groupsInside(boundaries.get(interval - 1), boundaries.get(interval), header)
                : null;
        CellRange right = interval + 2 < boundaries.size()
                ? groupsInside(boundaries.get(interval + 1), boundaries.get(interval + 2), header)
                : null;
        if (left == null) return right;
        if (right == null) return left;
        return Math.abs(block.center - left.center()) <= Math.abs(block.center - right.center())
                ? left : right;
    }

    private List<Float> boundariesAt(int pageIndex, float globalY, Header header, List<PageData> pages) {
        if (pageIndex < 0 || pageIndex >= pages.size()) return Collections.emptyList();
        PageData page = pages.get(pageIndex);
        float localY = globalY - pageIndex * 1000f;

        float minX = header.groupAreaLeft - header.medianSpacing * 0.12f;
        float maxX = header.roomCenters.get(header.roomCenters.size() - 1)
                + header.medianSpacing * 0.20f;

        List<Float> xs = new ArrayList<>();
        for (VerticalSegment segment : page.verticals) {
            if (!segment.crosses(localY)) continue;
            if (segment.x < minX || segment.x > maxX) continue;
            xs.add(segment.x);
        }
        if (xs.size() < 2) return Collections.emptyList();

        Collections.sort(xs);
        List<Float> clustered = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (float x : xs) {
            if (clustered.isEmpty() || x - clustered.get(clustered.size() - 1) > 2.0f) {
                clustered.add(x);
                counts.add(1);
            } else {
                int last = clustered.size() - 1;
                int count = counts.get(last);
                clustered.set(last, (clustered.get(last) * count + x) / (count + 1));
                counts.set(last, count + 1);
            }
        }
        return clustered;
    }

    private int intervalAt(List<Float> boundaries, float x) {
        if (boundaries.size() < 2) return -1;
        for (int i = 0; i < boundaries.size() - 1; i++) {
            if (x >= boundaries.get(i) - 1.2f && x <= boundaries.get(i + 1) + 1.2f) {
                return i;
            }
        }
        return -1;
    }

    private CellRange groupsInside(float left, float right, Header header) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < header.groups.size(); i++) {
            float center = header.groups.get(i).center;
            if (center > left + 1.0f && center < right - 1.0f) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return null;
        return new CellRange(first, last, left, right);
    }

    private int pageFromGlobalY(float y) {
        if (y < 0f) return 0;
        return Math.max(0, (int) (y / 1000f));
    }

    private static final class TextCluster {
        final float y;
        final float center;
        final String text;
        TextCluster(float y, float center, String text) { this.y = y; this.center = center; this.text = text; }
    }

    private TextCluster toTextCluster(float y, List<WordBox> words) {
        float x0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        StringBuilder sb = new StringBuilder();
        for (WordBox w : words) {
            x0 = Math.min(x0, w.x0);
            x1 = Math.max(x1, w.x1);
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.text);
        }
        return new TextCluster(y, (x0 + x1) * 0.5f, sb.toString());
    }

    private ParsedText parseText(List<String> lines) {
        if (lines.isEmpty()) return new ParsedText("", "", null, null);
        String teacher = "";
        if (INITIALS_PATTERN.matcher(lines.get(lines.size() - 1)).matches()) {
            teacher = lines.remove(lines.size() - 1).trim();
        }
        String subject = String.join(" ", lines).replaceAll("\\s+", " ").trim();
        LocalTime customStart = null;
        LocalTime customEnd = null;
        Matcher matcher = SPECIAL_TIME.matcher(subject);
        if (matcher.find()) {
            customStart = LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            customEnd = LocalTime.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
            subject = (subject.substring(0, matcher.start()) + " " + subject.substring(matcher.end())).replaceAll("\\s+", " ").trim();
        }
        return new ParsedText(subject, teacher, customStart, customEnd);
    }

    private LocalTime[] parseTimeRange(String text) {
        if (text == null) return null;
        Matcher m = SPECIAL_TIME.matcher(text.replace(" ", ""));
        if (!m.find()) return null;
        try {
            return new LocalTime[]{
                    LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))),
                    LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)))
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalTime[] standardTimes(int pair) {
        switch (pair) {
            case 1: return new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(10, 30)};
            case 2: return new LocalTime[]{LocalTime.of(10, 40), LocalTime.of(12, 10)};
            case 3: return new LocalTime[]{LocalTime.of(12, 50), LocalTime.of(14, 20)};
            case 4: return new LocalTime[]{LocalTime.of(14, 30), LocalTime.of(16, 0)};
            default: return new LocalTime[]{LocalTime.of(16, 10), LocalTime.of(17, 40)};
        }
    }

    private static int nearestIndex(List<Float> values, float x) {
        if (values.isEmpty()) return -1;
        int best = 0;
        float distance = Math.abs(values.get(0) - x);
        for (int i = 1; i < values.size(); i++) {
            float d = Math.abs(values.get(i) - x);
            if (d < distance) { distance = d; best = i; }
        }
        return best;
    }

    private static float spanCenter(List<GroupInfo> groups, int start, int end) {
        float sum = 0f;
        for (int i = start; i <= end; i++) sum += groups.get(i).center;
        return sum / (end - start + 1);
    }

    private static float median(List<Float> values) {
        if (values.isEmpty()) return 100f;
        List<Float> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int mid = copy.size() / 2;
        if (copy.size() % 2 == 1) return copy.get(mid);
        return (copy.get(mid - 1) + copy.get(mid)) * 0.5f;
    }

    private static String cleanupRoom(String room) {
        if (room == null) return "";
        return room.replaceAll("\\s+", " ").trim();
    }
}
