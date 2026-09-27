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
import java.time.DayOfWeek;
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

    private static final class HorizontalSegment {
        final float y;
        final float left;
        final float right;

        HorizontalSegment(float y, float left, float right) {
            this.y = y;
            this.left = Math.min(left, right);
            this.right = Math.max(left, right);
        }

        boolean crossesX(float x) {
            return x >= left - 0.8f && x <= right + 0.8f;
        }
    }

    private static final class GridExtractor extends PDFGraphicsStreamEngine {
        final List<VerticalSegment> verticals = new ArrayList<>();
        final List<HorizontalSegment> horizontals = new ArrayList<>();
        private final float pageHeight;
        private PointF current = new PointF();

        GridExtractor(PDPage page) {
            super(page);
            pageHeight = page.getMediaBox().getHeight();
        }

        private void record(float x1, float y1, float x2, float y2) {
            float dx = Math.abs(x1 - x2);
            float dy = Math.abs(y1 - y2);

            if (dx <= 1.0f && dy >= 3.0f) {
                float top1 = pageHeight - y1;
                float top2 = pageHeight - y2;
                verticals.add(new VerticalSegment(
                        (x1 + x2) * 0.5f,
                        top1,
                        top2
                ));
                return;
            }

            if (dy <= 1.0f && dx >= 3.0f) {
                float top = pageHeight - ((y1 + y2) * 0.5f);
                horizontals.add(new HorizontalSegment(
                        top,
                        x1,
                        x2
                ));
            }
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
        final List<HorizontalSegment> horizontals;
        List<PairRow> rows = new ArrayList<>();

        PageData(
                int pageIndex,
                float width,
                float height,
                List<WordBox> words,
                List<VerticalSegment> verticals,
                List<HorizontalSegment> horizontals
        ) {
            this.pageIndex = pageIndex;
            this.width = width;
            this.height = height;
            this.words = words;
            this.verticals = verticals;
            this.horizontals = horizontals;
        }
    }

    /**
     * One real table row for a numbered pair. top/bottom are taken from
     * horizontal grid lines crossing the pair-number column, not guessed from
     * the midpoint between text anchors. This is the key invariant preventing
     * content from the next day leaking into the previous pair.
     */
    private static final class PairRow {
        final int pair;
        final float anchorY;
        final float top;
        final float bottom;
        final boolean gridBounded;

        PairRow(
                int pair,
                float anchorY,
                float top,
                float bottom,
                boolean gridBounded
        ) {
            this.pair = pair;
            this.anchorY = anchorY;
            this.top = top;
            this.bottom = bottom;
            this.gridBounded = gridBounded;
        }
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

    private static final class PairSlice {
        final int pair;
        final int pageIndex;
        final List<WordBox> words = new ArrayList<>();

        PairSlice(int pair, int pageIndex) {
            this.pair = pair;
            this.pageIndex = pageIndex;
        }
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
                        i,
                        width,
                        height,
                        new ArrayList<>(stripper.words),
                        new ArrayList<>(gridExtractor.verticals),
                        new ArrayList<>(gridExtractor.horizontals)
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
                page.rows = detectPairRows(page, header);
                int exactRows = 0;
                for (PairRow row : page.rows) {
                    if (row.gridBounded) exactRows++;
                }
                diagnostics.add(
                        "Страница " + (page.pageIndex + 1) +
                                ": строк пар = " + page.rows.size() +
                                ", по сетке = " + exactRows
                );
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
        final float pairColumnLeft;
        final float pairColumnRight;

        Header(
                List<GroupInfo> groups,
                List<Float> roomCenters,
                float medianSpacing,
                float groupAreaLeft,
                float pairColumnLeft,
                float pairColumnRight
        ) {
            this.groups = groups;
            this.roomCenters = roomCenters;
            this.medianSpacing = medianSpacing;
            this.groupAreaLeft = groupAreaLeft;
            this.pairColumnLeft = pairColumnLeft;
            this.pairColumnRight = pairColumnRight;
        }

        float pairColumnCenter() {
            return (pairColumnLeft + pairColumnRight) * 0.5f;
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
        float roughPairMin = groupAreaLeft * 0.30f;
        float roughPairMax = groupAreaLeft * 0.50f;
        List<Float> pairCenters = new ArrayList<>();

        for (WordBox word : page.words) {
            if (!word.text.matches("[1-5]")) continue;
            float center = word.centerX();
            if (center >= roughPairMin && center <= roughPairMax) {
                pairCenters.add(center);
            }
        }

        float pairCenter = pairCenters.isEmpty()
                ? (roughPairMin + roughPairMax) * 0.5f
                : median(pairCenters);

        float pairLeft = nearestVerticalLeft(page.verticals, pairCenter);
        float pairRight = nearestVerticalRight(page.verticals, pairCenter);

        if (Float.isNaN(pairLeft) || Float.isNaN(pairRight) ||
                pairRight - pairLeft < 4f) {
            pairLeft = roughPairMin;
            pairRight = roughPairMax;
        }

        return new Header(
                groups,
                roomCenters,
                medianSpacing,
                groupAreaLeft,
                pairLeft,
                pairRight
        );
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

    private List<PairRow> detectPairRows(
            PageData page,
            Header header
    ) {
        List<PairRow> candidates = new ArrayList<>();
        float pairCenter = header.pairColumnCenter();
        List<Float> horizontalBounds =
                horizontalBoundariesAtX(page, pairCenter);

        for (WordBox word : page.words) {
            float center = word.centerX();

            if (center < header.pairColumnLeft - 1.5f ||
                    center > header.pairColumnRight + 1.5f) {
                continue;
            }

            if (!word.text.matches("[1-5]")) continue;

            float top = Float.NaN;
            float bottom = Float.NaN;

            for (float boundary : horizontalBounds) {
                if (boundary <= word.y + 0.8f) {
                    top = boundary;
                } else {
                    bottom = boundary;
                    break;
                }
            }

            boolean exact =
                    !Float.isNaN(top) &&
                            !Float.isNaN(bottom) &&
                            bottom - top >= 6f;

            candidates.add(
                    new PairRow(
                            Integer.parseInt(word.text),
                            word.y,
                            top,
                            bottom,
                            exact
                    )
            );
        }

        candidates.sort(
                Comparator.comparingDouble(row -> row.anchorY)
        );

        List<PairRow> clean = new ArrayList<>();

        for (PairRow candidate : candidates) {
            boolean duplicate = false;

            for (PairRow old : clean) {
                boolean sameBand =
                        candidate.gridBounded &&
                                old.gridBounded &&
                                Math.abs(candidate.top - old.top) < 1.5f &&
                                Math.abs(candidate.bottom - old.bottom) < 1.5f;

                boolean sameAnchor =
                        old.pair == candidate.pair &&
                                Math.abs(old.anchorY - candidate.anchorY) < 2.0f;

                if (sameBand || sameAnchor) {
                    duplicate = true;
                    break;
                }
            }

            if (!duplicate) clean.add(candidate);
        }

        // Grid extraction is the primary path. If one row in an unusual PDF
        // has no usable horizontal borders, bound only that row by neighbouring
        // anchors. This fallback can no longer affect rows that have real lines.
        List<PairRow> resolved = new ArrayList<>();

        for (int i = 0; i < clean.size(); i++) {
            PairRow row = clean.get(i);

            if (row.gridBounded) {
                resolved.add(row);
                continue;
            }

            float y = row.anchorY;
            float top;
            float bottom;

            if (i > 0) {
                top = (clean.get(i - 1).anchorY + y) * 0.5f;
            } else if (clean.size() > 1) {
                top = y - (clean.get(1).anchorY - y) * 0.5f;
            } else {
                top = y - 20f;
            }

            if (i < clean.size() - 1) {
                bottom = (y + clean.get(i + 1).anchorY) * 0.5f;
            } else if (i > 0) {
                bottom = y + (y - clean.get(i - 1).anchorY) * 0.5f;
            } else {
                bottom = y + 20f;
            }

            resolved.add(
                    new PairRow(
                            row.pair,
                            row.anchorY,
                            top,
                            bottom,
                            false
                    )
            );
        }

        return resolved;
    }

    private List<Float> horizontalBoundariesAtX(
            PageData page,
            float x
    ) {
        List<Float> values = new ArrayList<>();

        for (HorizontalSegment segment : page.horizontals) {
            if (!segment.crossesX(x)) continue;
            if (segment.right - segment.left < 6f) continue;
            values.add(segment.y);
        }

        Collections.sort(values);

        List<Float> clustered = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();

        for (float value : values) {
            if (clustered.isEmpty() ||
                    value - clustered.get(clustered.size() - 1) > 1.4f) {
                clustered.add(value);
                counts.add(1);
            } else {
                int last = clustered.size() - 1;
                int count = counts.get(last);
                clustered.set(
                        last,
                        (clustered.get(last) * count + value) / (count + 1)
                );
                counts.set(last, count + 1);
            }
        }

        return clustered;
    }

    private float nearestVerticalLeft(
            List<VerticalSegment> segments,
            float x
    ) {
        float best = Float.NaN;
        float bestDistance = Float.MAX_VALUE;

        for (VerticalSegment segment : segments) {
            if (segment.bottom - segment.top < 8f) continue;
            if (segment.x >= x) continue;

            float distance = x - segment.x;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = segment.x;
            }
        }

        return best;
    }

    private float nearestVerticalRight(
            List<VerticalSegment> segments,
            float x
    ) {
        float best = Float.NaN;
        float bestDistance = Float.MAX_VALUE;

        for (VerticalSegment segment : segments) {
            if (segment.bottom - segment.top < 8f) continue;
            if (segment.x <= x) continue;

            float distance = segment.x - x;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = segment.x;
            }
        }

        return best;
    }

    private Map<RowKey, List<WordBox>> collectRows(
            List<PageData> pages,
            Header header,
            List<String> warnings
    ) throws IOException {
        List<PairSlice> pairSlices =
                new ArrayList<>();

        // Phase 1: reconstruct physical pair rows globally across every PDF
        // page. Page breaks are a rendering detail and are deliberately removed
        // before dates are assigned.
        for (PageData page : pages) {
            if (page.rows.isEmpty()) continue;

            PairRow firstRow =
                    page.rows.get(0);

            if (!pairSlices.isEmpty()) {
                List<WordBox> continuation =
                        new ArrayList<>();

                for (WordBox word : page.words) {
                    if (word.x0 <
                            header.groupAreaLeft - 1.0f) {
                        continue;
                    }

                    if (word.y <
                            firstRow.top - 0.4f) {
                        continuation.add(
                                word.globalY()
                        );
                    }
                }

                if (!continuation.isEmpty()) {
                    PairSlice previous =
                            pairSlices.get(
                                    pairSlices.size() - 1
                            );

                    // Do not absorb a repeated table header into a lesson.
                    boolean containsGroupHeader = false;
                    for (WordBox word : continuation) {
                        if (looksLikeGroup(
                                normalizeGroupCandidate(
                                        word.text
                                )
                        )) {
                            containsGroupHeader = true;
                            break;
                        }
                    }

                    if (!containsGroupHeader) {
                        previous.words.addAll(
                                continuation
                        );
                    }
                }
            }

            for (PairRow row : page.rows) {
                PairSlice slice =
                        new PairSlice(
                                row.pair,
                                page.pageIndex
                        );

                for (WordBox word : page.words) {
                    if (word.x0 <
                            header.groupAreaLeft - 1.0f) {
                        continue;
                    }

                    if (word.y >= row.top - 0.4f &&
                            word.y < row.bottom - 0.4f) {
                        slice.words.add(
                                word.globalY()
                        );
                    }
                }

                pairSlices.add(slice);
            }
        }

        if (pairSlices.isEmpty()) {
            throw new IOException(
                    "Не удалось восстановить строки пар по сетке таблицы"
            );
        }

        // Phase 2: pair-number resets define day blocks globally. This works
        // even when the date label or half of a lesson is split between pages.
        List<List<PairSlice>> dayBlocks =
                new ArrayList<>();

        List<PairSlice> current =
                new ArrayList<>();

        Integer previousPair = null;

        for (PairSlice slice : pairSlices) {
            if (previousPair != null &&
                    slice.pair <= previousPair) {
                if (!current.isEmpty()) {
                    dayBlocks.add(current);
                }
                current =
                        new ArrayList<>();
            }

            current.add(slice);
            previousPair = slice.pair;
        }

        if (!current.isEmpty()) {
            dayBlocks.add(current);
        }

        // Phase 3: resolve the calendar only after the physical table is known.
        // Rotated date labels may disappear at page breaks (03.09, 11.09 and
        // 22.09 in the supplied PDF), therefore page-local date assignment is
        // fundamentally unsafe.
        LinkedHashSet<LocalDate> explicitSet =
                new LinkedHashSet<>();

        for (PageData page : pages) {
            explicitSet.addAll(
                    extractDates(page.words)
            );
        }

        List<LocalDate> explicitDates =
                new ArrayList<>(explicitSet);
        Collections.sort(explicitDates);

        List<LocalDate> resolvedDates =
                resolveDayBlockDates(
                        explicitDates,
                        dayBlocks.size(),
                        warnings
                );

        if (resolvedDates.size() !=
                dayBlocks.size()) {
            throw new IOException(
                    "Количество дней таблицы (" +
                            dayBlocks.size() +
                            ") не совпало с восстановленным календарём (" +
                            resolvedDates.size() +
                            ")"
            );
        }

        Map<RowKey, List<WordBox>> rows =
                new LinkedHashMap<>();

        for (int dayIndex = 0;
             dayIndex < dayBlocks.size();
             dayIndex++) {
            LocalDate date =
                    resolvedDates.get(dayIndex);

            List<PairSlice> block =
                    dayBlocks.get(dayIndex);

            Set<Integer> seenPairs =
                    new LinkedHashSet<>();

            for (PairSlice slice : block) {
                if (!seenPairs.add(slice.pair)) {
                    warnings.add(
                            "Дата " +
                                    SHORT_DATE.format(date) +
                                    ": номер пары " +
                                    slice.pair +
                                    " встретился повторно."
                    );
                }

                RowKey key =
                        new RowKey(
                                date,
                                slice.pair
                        );

                rows.computeIfAbsent(
                                key,
                                ignored ->
                                        new ArrayList<>()
                        )
                        .addAll(slice.words);
            }
        }

        return rows;
    }

    private List<LocalDate> resolveDayBlockDates(
            List<LocalDate> explicitDates,
            int dayBlockCount,
            List<String> warnings
    ) throws IOException {
        if (explicitDates.isEmpty()) {
            throw new IOException(
                    "В таблице не удалось найти ни одной явной даты"
            );
        }

        if (explicitDates.size() ==
                dayBlockCount) {
            return new ArrayList<>(
                    explicitDates
            );
        }

        LocalDate start =
                explicitDates.get(0);
        LocalDate end =
                explicitDates.get(
                        explicitDates.size() - 1
                );

        Set<DayOfWeek> explicitWeekdays =
                new LinkedHashSet<>();

        for (LocalDate date : explicitDates) {
            explicitWeekdays.add(
                    date.getDayOfWeek()
            );
        }

        List<DayOfWeek> absentWeekdays =
                new ArrayList<>();

        for (DayOfWeek day :
                DayOfWeek.values()) {
            if (!explicitWeekdays.contains(day)) {
                absentWeekdays.add(day);
            }
        }

        List<List<LocalDate>> candidates =
                new ArrayList<>();

        int subsetCount =
                1 << absentWeekdays.size();

        for (int mask = 0;
             mask < subsetCount;
             mask++) {
            Set<DayOfWeek> excluded =
                    new LinkedHashSet<>();

            for (int i = 0;
                 i < absentWeekdays.size();
                 i++) {
                if ((mask & (1 << i)) != 0) {
                    excluded.add(
                            absentWeekdays.get(i)
                    );
                }
            }

            List<LocalDate> candidate =
                    new ArrayList<>();

            LocalDate cursor = start;

            while (!cursor.isAfter(end)) {
                if (!excluded.contains(
                        cursor.getDayOfWeek()
                )) {
                    candidate.add(cursor);
                }

                cursor =
                        cursor.plusDays(1);
            }

            if (candidate.size() !=
                    dayBlockCount) {
                continue;
            }

            if (!candidate.containsAll(
                    explicitDates
            )) {
                continue;
            }

            candidates.add(candidate);
        }

        if (candidates.size() != 1) {
            throw new IOException(
                    "Не удалось однозначно сопоставить " +
                            dayBlockCount +
                            " блоков дней с явными датами " +
                            SHORT_DATE.format(start) +
                            "–" +
                            SHORT_DATE.format(end) +
                            ". Найдено вариантов: " +
                            candidates.size()
            );
        }

        List<LocalDate> resolved =
                candidates.get(0);

        Set<LocalDate> explicit =
                new HashSet<>(explicitDates);

        for (LocalDate date : resolved) {
            if (!explicit.contains(date)) {
                warnings.add(
                        "Дата " +
                                SHORT_DATE.format(date) +
                                " восстановлена по глобальной последовательности строк таблицы."
                );
            }
        }

        return resolved;
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

    private List<List<Integer>> groupPairRowIndexes(
            List<PairRow> rows
    ) {
        List<List<Integer>> result =
                new ArrayList<>();

        List<Integer> current =
                new ArrayList<>();

        Integer previousPair = null;

        for (int i = 0; i < rows.size(); i++) {
            int pair = rows.get(i).pair;

            if (previousPair != null &&
                    pair <= previousPair) {
                if (!current.isEmpty()) {
                    result.add(current);
                }
                current = new ArrayList<>();
            }

            current.add(i);
            previousPair = pair;
        }

        if (!current.isEmpty()) {
            result.add(current);
        }

        return result;
    }

    private List<EventDraft> parseRow(
            List<WordBox> rowWords,
            Header header,
            List<PageData> pages
    ) {
        List<EventDraft> exact =
                parseRowFromGrid(rowWords, header, pages);

        if (!exact.isEmpty()) {
            return exact;
        }

        // Compatibility fallback for foreign PDFs that contain positioned text
        // but omit the table drawing commands. The supplied college timetable
        // uses the exact-grid path above.
        return parseRowHeuristic(
                rowWords,
                header,
                pages
        );
    }

    /**
     * Parses a pair strictly as table cells. Words are never associated by
     * "nearest subject" distance: every text line is first placed into the
     * vertical interval that physically contains it. Missing internal borders
     * naturally represent merged lectures spanning several group columns.
     */
    private List<EventDraft> parseRowFromGrid(
            List<WordBox> rowWords,
            Header header,
            List<PageData> pages
    ) {
        if (rowWords.isEmpty()) {
            return Collections.emptyList();
        }

        float roomTolerance =
                header.medianSpacing * 0.20f;

        Map<Integer, List<WordBox>> roomWords =
                new HashMap<>();

        List<WordBox> content =
                new ArrayList<>();

        for (WordBox word : rowWords) {
            int nearestRoom =
                    nearestIndex(
                            header.roomCenters,
                            word.centerX()
                    );

            boolean roomToken =
                    nearestRoom >= 0 &&
                            ROOM_TOKEN
                                    .matcher(
                                            word.text.trim()
                                    )
                                    .matches() &&
                            Math.abs(
                                    header.roomCenters
                                            .get(nearestRoom) -
                                            word.centerX()
                            ) < roomTolerance;

            if (roomToken) {
                roomWords
                        .computeIfAbsent(
                                nearestRoom,
                                ignored ->
                                        new ArrayList<>()
                        )
                        .add(word);
            } else {
                content.add(word);
            }
        }

        content.sort(
                Comparator
                        .comparingDouble(
                                (WordBox word) ->
                                        word.y
                        )
                        .thenComparingDouble(
                                word -> word.x0
                        )
        );

        List<LineCluster> lines =
                new ArrayList<>();

        for (WordBox word : content) {
            LineCluster line = null;

            for (LineCluster candidate : lines) {
                if (Math.abs(
                        candidate.y -
                                word.y
                ) < 3.0f) {
                    line = candidate;
                    break;
                }
            }

            if (line == null) {
                line =
                        new LineCluster(word.y);
                lines.add(line);
            }

            line.words.add(word);

            float sum = 0f;
            for (WordBox item : line.words) {
                sum += item.y;
            }
            line.y =
                    sum / line.words.size();
        }

        lines.sort(
                Comparator.comparingDouble(
                        line -> line.y
                )
        );

        Map<String, Block> blocks =
                new LinkedHashMap<>();
        Map<String, CellRange> ranges =
                new LinkedHashMap<>();

        for (LineCluster line : lines) {
            line.words.sort(
                    Comparator.comparingDouble(
                            word -> word.x0
                    )
            );

            Map<String, List<WordBox>> wordsByCell =
                    new LinkedHashMap<>();
            Map<String, CellRange> lineRanges =
                    new LinkedHashMap<>();

            for (WordBox word : line.words) {
                CellRange range =
                        cellForPosition(
                                word.page,
                                word.y,
                                word.centerX(),
                                header,
                                pages
                        );

                if (range == null) continue;

                String key =
                        range.startGroup +
                                ":" +
                                range.endGroup;

                wordsByCell
                        .computeIfAbsent(
                                key,
                                ignored ->
                                        new ArrayList<>()
                        )
                        .add(word);

                lineRanges.put(key, range);
            }

            for (Map.Entry<String, List<WordBox>> entry :
                    wordsByCell.entrySet()) {
                List<WordBox> words =
                        entry.getValue();

                words.sort(
                        Comparator.comparingDouble(
                                word -> word.x0
                        )
                );

                StringBuilder text =
                        new StringBuilder();

                for (WordBox word : words) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(word.text);
                }

                String clean =
                        text.toString()
                                .replaceAll(
                                        "\\s+",
                                        " "
                                )
                                .trim();

                if (clean.isEmpty()) continue;

                CellRange range =
                        lineRanges.get(
                                entry.getKey()
                        );

                Block block =
                        blocks.get(
                                entry.getKey()
                        );

                if (block == null) {
                    block =
                            new Block(
                                    range.center()
                            );
                    blocks.put(
                            entry.getKey(),
                            block
                    );
                    ranges.put(
                            entry.getKey(),
                            range
                    );
                }

                block.add(
                        range.center(),
                        line.y,
                        clean
                );
            }
        }

        if (blocks.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Integer, String> rooms =
                new HashMap<>();

        for (Map.Entry<Integer, List<WordBox>> entry :
                roomWords.entrySet()) {
            List<WordBox> words =
                    entry.getValue();

            words.sort(
                    Comparator
                            .comparingDouble(
                                    (WordBox word) ->
                                            word.y
                            )
                            .thenComparingDouble(
                                    word ->
                                            word.x0
                            )
            );

            StringBuilder room =
                    new StringBuilder();

            for (WordBox word : words) {
                if (room.length() > 0) {
                    room.append(' ');
                }
                room.append(word.text);
            }

            rooms.put(
                    entry.getKey(),
                    room.toString()
                            .replaceAll(
                                    "\\s+",
                                    " "
                            )
                            .trim()
            );
        }

        List<EventDraft> result =
                new ArrayList<>();

        for (Map.Entry<String, Block> entry :
                blocks.entrySet()) {
            CellRange range =
                    ranges.get(entry.getKey());

            if (range == null) continue;

            String room =
                    rooms.getOrDefault(
                            range.endGroup,
                            ""
                    );

            result.add(
                    new EventDraft(
                            entry.getValue(),
                            range.startGroup,
                            range.endGroup,
                            room
                    )
            );
        }

        result.sort(
                Comparator.comparingInt(
                        draft ->
                                draft.startGroup
                )
        );

        return result;
    }

    private CellRange cellForPosition(
            int pageIndex,
            float globalY,
            float x,
            Header header,
            List<PageData> pages
    ) {
        List<Float> boundaries =
                boundariesAt(
                        pageIndex,
                        globalY,
                        header,
                        pages
                );

        int interval =
                intervalAt(
                        boundaries,
                        x
                );

        if (interval < 0) return null;

        CellRange direct =
                groupsInside(
                        boundaries.get(interval),
                        boundaries.get(interval + 1),
                        header
                );

        if (direct != null) {
            return direct;
        }

        CellRange left =
                interval > 0
                        ? groupsInside(
                        boundaries.get(interval - 1),
                        boundaries.get(interval),
                        header
                )
                        : null;

        CellRange right =
                interval + 2 <
                        boundaries.size()
                        ? groupsInside(
                        boundaries.get(interval + 1),
                        boundaries.get(interval + 2),
                        header
                )
                        : null;

        if (left == null) return right;
        if (right == null) return left;

        return Math.abs(
                x - left.center()
        ) <=
                Math.abs(
                        x - right.center()
                )
                ? left
                : right;
    }

    private List<EventDraft> parseRowHeuristic(List<WordBox> rowWords, Header header, List<PageData> pages) {
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
