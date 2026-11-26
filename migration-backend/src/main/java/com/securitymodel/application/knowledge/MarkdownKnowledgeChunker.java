package com.securitymodel.application.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Markdown chunker. Section headings and 1-based line ranges are preserved
 * so an answer can cite a stable location even after the file is reformatted.
 */
public final class MarkdownKnowledgeChunker {

    private static final Pattern HEADING = Pattern.compile("^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    private MarkdownKnowledgeChunker() {
    }

    public record Draft(String title, String sectionPath, String content, int startLine, int endLine) {
    }

    public static List<Draft> chunk(String markdown, String fallbackTitle, int maxChars) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        int safeMax = Math.max(120, maxChars);
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> headings = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        int chunkStart = -1;
        int chunkEnd = -1;
        String title = fallbackTitle == null || fallbackTitle.isBlank() ? "未命名文档" : fallbackTitle;
        List<Draft> result = new ArrayList<>();
        boolean titleSeen = false;

        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = lines[index];
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                int level = matcher.group(1).length();
                String text = matcher.group(2).trim();
                flush(result, title, headings, buffer, chunkStart, chunkEnd);
                buffer.clear();
                chunkStart = -1;
                chunkEnd = -1;
                if (level == 1 && !titleSeen) {
                    title = text;
                    titleSeen = true;
                }
                while (!headings.isEmpty() && headingLevel(headings.get(headings.size() - 1)) >= level) {
                    headings.remove(headings.size() - 1);
                }
                headings.add(level + ":" + text);
                continue;
            }
            if (line.isBlank() && buffer.isEmpty()) {
                continue;
            }
            if (chunkStart < 0) {
                chunkStart = lineNumber;
            }
            if (currentLength(buffer) + line.length() + 1 > safeMax && !buffer.isEmpty()) {
                flush(result, title, headings, buffer, chunkStart, Math.max(chunkStart, lineNumber - 1));
                buffer.clear();
                chunkStart = lineNumber;
            }
            if (!line.isBlank()) {
                chunkEnd = lineNumber;
            }
            if (line.length() > safeMax) {
                for (int from = 0; from < line.length(); from += safeMax) {
                    if (!buffer.isEmpty() && currentLength(buffer) + safeMax > safeMax) {
                        flush(result, title, headings, buffer, chunkStart, lineNumber);
                        buffer.clear();
                        chunkStart = lineNumber;
                    }
                    buffer.add(line.substring(from, Math.min(line.length(), from + safeMax)));
                }
            } else {
                buffer.add(line);
            }
        }
        flush(result, title, headings, buffer, chunkStart, chunkEnd);
        return result;
    }

    private static void flush(List<Draft> result, String title, List<String> headings,
                              List<String> buffer, int startLine, int endLine) {
        if (buffer.isEmpty() || startLine < 0) {
            return;
        }
        String content = String.join("\n", buffer).trim();
        if (content.isEmpty()) {
            return;
        }
        result.add(new Draft(title, sectionPath(headings), content, startLine, Math.max(startLine, endLine)));
    }

    private static int currentLength(List<String> buffer) {
        return buffer.stream().mapToInt(String::length).sum() + Math.max(0, buffer.size() - 1);
    }

    private static String sectionPath(List<String> headings) {
        List<String> values = new ArrayList<>();
        for (String heading : headings) {
            values.add(heading.substring(heading.indexOf(':') + 1));
        }
        return String.join(" > ", values);
    }

    private static int headingLevel(String heading) {
        return Integer.parseInt(heading.substring(0, heading.indexOf(':')));
    }
}
