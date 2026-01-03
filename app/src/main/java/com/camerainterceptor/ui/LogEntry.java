package com.camerainterceptor.ui;

import java.util.Objects;

/**
 * Model class representing a single log entry.
 * Immutable data class for use with RecyclerView DiffUtil.
 */
public class LogEntry {

    public enum Level {
        ERROR("ERROR"),
        WARN("WARN"),
        INFO("INFO"),
        DEBUG("DEBUG"),
        VERBOSE("VERBOSE"),
        UNKNOWN("");

        private final String tag;

        Level(String tag) {
            this.tag = tag;
        }

        public String getTag() {
            return tag;
        }

        public static Level fromString(String levelStr) {
            if (levelStr == null) return UNKNOWN;
            String upper = levelStr.toUpperCase().trim();
            for (Level level : values()) {
                if (level.tag.equals(upper) || upper.startsWith(level.tag)) {
                    return level;
                }
            }
            // Also check single letters
            if (upper.equals("E")) return ERROR;
            if (upper.equals("W")) return WARN;
            if (upper.equals("I")) return INFO;
            if (upper.equals("D")) return DEBUG;
            if (upper.equals("V")) return VERBOSE;
            return UNKNOWN;
        }
    }

    private final long id;
    private final String timestamp;
    private final Level level;
    private final String tag;
    private final String message;
    private final String rawLine;

    public LogEntry(long id, String timestamp, Level level, String tag, String message, String rawLine) {
        this.id = id;
        this.timestamp = timestamp;
        this.level = level;
        this.tag = tag;
        this.message = message;
        this.rawLine = rawLine;
    }

    public long getId() {
        return id;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public Level getLevel() {
        return level;
    }

    public String getTag() {
        return tag;
    }

    public String getMessage() {
        return message;
    }

    public String getRawLine() {
        return rawLine;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogEntry logEntry = (LogEntry) o;
        return id == logEntry.id &&
                Objects.equals(timestamp, logEntry.timestamp) &&
                level == logEntry.level &&
                Objects.equals(tag, logEntry.tag) &&
                Objects.equals(message, logEntry.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, timestamp, level, tag, message);
    }

    /**
     * Parse a log line into a LogEntry object.
     * Expected format: "TIMESTAMP [LEVEL] TAG: MESSAGE" or "TIMESTAMP LEVEL: MESSAGE"
     * Falls back to raw line if parsing fails.
     */
    public static LogEntry parse(long id, String line) {
        if (line == null || line.isEmpty()) {
            return new LogEntry(id, "", Level.UNKNOWN, "", "", line != null ? line : "");
        }

        String timestamp = "";
        Level level = Level.UNKNOWN;
        String tag = "";
        String message = line;

        try {
            // Try to extract timestamp (format: YYYY-MM-DD HH:MM:SS or similar)
            int firstSpace = line.indexOf(' ');
            if (firstSpace > 0) {
                // Check if starts with date-like pattern
                String potentialDate = line.substring(0, Math.min(19, line.length()));
                if (potentialDate.matches("^\\d{4}-\\d{2}-\\d{2}.*") || 
                    potentialDate.matches("^\\d{2}:\\d{2}:\\d{2}.*")) {
                    // Find end of timestamp (usually after second space for date+time)
                    int secondSpace = line.indexOf(' ', firstSpace + 1);
                    if (secondSpace > 0 && secondSpace < 25) {
                        timestamp = line.substring(0, secondSpace).trim();
                        message = line.substring(secondSpace + 1).trim();
                    } else {
                        timestamp = line.substring(0, firstSpace).trim();
                        message = line.substring(firstSpace + 1).trim();
                    }
                }
            }

            // Try to extract level [LEVEL] or LEVEL:
            String remaining = message;
            
            // Check for [LEVEL] format
            if (remaining.startsWith("[")) {
                int closeBracket = remaining.indexOf(']');
                if (closeBracket > 0 && closeBracket < 10) {
                    String levelStr = remaining.substring(1, closeBracket);
                    level = Level.fromString(levelStr);
                    remaining = remaining.substring(closeBracket + 1).trim();
                }
            } else {
                // Check for LEVEL: format or LEVEL/ format (logcat style)
                String[] levelPrefixes = {"ERROR:", "WARN:", "INFO:", "DEBUG:", "VERBOSE:",
                                          "E:", "W:", "I:", "D:", "V:",
                                          "ERROR/", "WARN/", "INFO/", "DEBUG/", "VERBOSE/",
                                          "E/", "W/", "I/", "D/", "V/"};
                for (String prefix : levelPrefixes) {
                    if (remaining.toUpperCase().startsWith(prefix)) {
                        level = Level.fromString(prefix.replace(":", "").replace("/", ""));
                        remaining = remaining.substring(prefix.length()).trim();
                        break;
                    }
                }
            }

            // Try to extract tag (format: TAG: message)
            int colonIndex = remaining.indexOf(':');
            if (colonIndex > 0 && colonIndex < 50) {
                String potentialTag = remaining.substring(0, colonIndex).trim();
                // Tags usually don't contain spaces
                if (!potentialTag.contains(" ")) {
                    tag = potentialTag;
                    remaining = remaining.substring(colonIndex + 1).trim();
                }
            }

            message = remaining;

        } catch (Exception e) {
            // If parsing fails, keep the raw line
            message = line;
        }

        return new LogEntry(id, timestamp, level, tag, message, line);
    }
}
