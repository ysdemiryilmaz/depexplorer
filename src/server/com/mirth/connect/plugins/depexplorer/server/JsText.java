package com.mirth.connect.plugins.depexplorer.server;

/** Small helpers for JavaScript source text. */
final class JsText {

    private JsText() {}

    /**
     * Returns a copy of the code of the same length in which comments (and, if asked, the contents of string
     * literals) are replaced by spaces; line breaks are kept so positions and line numbers stay valid.
     */
    static String blank(String s, boolean strings) {
        char[] c = s.toCharArray();
        int n = c.length;
        int i = 0;
        while (i < n) {
            char ch = c[i];
            if (ch == '/' && i + 1 < n && c[i + 1] == '/') {
                while (i < n && c[i] != '\n') {
                    c[i++] = ' ';
                }
            } else if (ch == '/' && i + 1 < n && c[i + 1] == '*') {
                c[i] = ' ';
                c[i + 1] = ' ';
                i += 2;
                while (i < n && !(c[i] == '*' && i + 1 < n && c[i + 1] == '/')) {
                    if (c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    c[i] = ' ';
                    if (i + 1 < n) {
                        c[i + 1] = ' ';
                    }
                    i += 2;
                }
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                char q = ch;
                i++;
                while (i < n && c[i] != q) {
                    if (c[i] == '\n' && q != '`') {
                        break; // unterminated string: stop at the end of the line
                    }
                    if (c[i] == '\\' && i + 1 < n) {
                        if (strings) {
                            c[i] = ' ';
                            if (c[i + 1] != '\n') {
                                c[i + 1] = ' ';
                            }
                        }
                        i += 2;
                        continue;
                    }
                    if (strings && c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(c);
    }

    /** 1-based line number of a position. */
    static int lineOf(String s, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The (trimmed, shortened) text of a 1-based line. */
    static String line(String s, int line) {
        if (s == null) {
            return "";
        }
        int start = 0;
        for (int l = 1; l < line; l++) {
            int nl = s.indexOf('\n', start);
            if (nl < 0) {
                return "";
            }
            start = nl + 1;
        }
        int end = s.indexOf('\n', start);
        String t = s.substring(start, end < 0 ? s.length() : end).trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
