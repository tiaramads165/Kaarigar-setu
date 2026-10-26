package com.kaarigarsetu.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level guard for the standing rule "PreparedStatement only, no string-built SQL": a variable can never be spliced
 * into SQL text in production code. Compile-time-constant concatenation of literals (to wrap a long statement) is allowed.
 */
class NoStringBuiltSqlTest {
    private static final Pattern SQL_ASSIGNMENT = Pattern.compile("String\\s+sql\\s*=\\s*(.*?);", Pattern.DOTALL);
    private static final Pattern PREPARE = Pattern.compile("prepareStatement\\(");
    private static final Pattern LITERALS = Pattern.compile("\"\"\"(.|\\n)*?\"\"\"|\"(\\\\.|[^\"\\\\])*\"");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** What is left of an expression once string literals and '+' are removed: empty means "literals only". */
    private static String residue(String expression) {
        return LITERALS.matcher(expression).replaceAll("").replace("+", "").replaceAll("\\s+", "");
    }

    /** The text of a call's first argument: read up to the first top-level comma or closing bracket, skipping over string literals (which may contain either). */
    static String firstArgument(String text, int start) {
        int depth = 0;
        int i = start;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (text.startsWith("\"\"\"", i)) {
                i = text.indexOf("\"\"\"", i + 3) + 3;
                continue;
            }
            if (ch == '"') {
                i++;
                while (i < text.length() && text.charAt(i) != '"') i += text.charAt(i) == '\\' ? 2 : 1;
                i++;
                continue;
            }
            if (ch == '(') depth++;
            if (ch == ')' || ch == ',') {
                if (depth == 0) break;
                if (ch == ')') depth--;
            }
            i++;
        }
        return text.substring(start, Math.min(i, text.length()));
    }

    @Test
    void noStatementObjectsAreEverUsed() throws IOException {
        for (Path file : sources()) {
            String text = Files.readString(file);
            assertFalse(text.contains(".createStatement("), file + " uses java.sql.Statement; use PreparedStatement");
            assertFalse(Pattern.compile("\\.execute(Query|Update)?\\(\\s*\"").matcher(text).find() && text.contains("java.sql.Statement ") ,
                    file + " executes a raw SQL string");
        }
    }

    @Test
    void everySqlAssignmentAndPrepareCallIsBuiltFromLiteralsOnly() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path file : sources()) {
            String text = Files.readString(file);
            Matcher assignment = SQL_ASSIGNMENT.matcher(text);
            while (assignment.find()) {
                checked++;
                if (!residue(assignment.group(1)).isEmpty()) offenders.add(file + ": String sql = " + assignment.group(1).strip());
            }
            Matcher prepare = PREPARE.matcher(text);
            while (prepare.find()) {
                String argument = firstArgument(text, prepare.end()).strip();
                checked++;
                boolean ok = argument.equals("sql") || argument.equals("retire") || argument.equals("insert") || residue(argument).isEmpty();
                if (!ok) offenders.add(file + ": prepareStatement(" + argument + ")");
            }
        }
        assertTrue(checked > 40, "expected to inspect many SQL statements, saw " + checked);
        assertEquals(List.of(), offenders);
    }

    @Test
    void theScannerReadsWholeStringLiteralsEvenWhenTheyContainCommasAndBrackets() {
        String code = "c.prepareStatement(\"UPDATE t SET a = ?, b = (SELECT 1)\" + \" WHERE id = ?\", Statement.RETURN_GENERATED_KEYS)";
        String argument = firstArgument(code, code.indexOf('(') + 1);
        assertEquals("\"UPDATE t SET a = ?, b = (SELECT 1)\" + \" WHERE id = ?\"", argument);
        assertTrue(residue(argument).isEmpty());
        String evil = "c.prepareStatement(\"SELECT * FROM t WHERE a = '\" + name + \"'\")";
        assertFalse(residue(firstArgument(evil, evil.indexOf('(') + 1)).isEmpty());
    }

    @Test
    void theGuardWouldCatchARealViolation() {
        assertFalse(residue("\"SELECT * FROM t WHERE a = '\" + name + \"'\"").isEmpty());
        assertTrue(residue("\"UPDATE t SET a = 1 \" + \"WHERE id = ?\"").isEmpty());
    }
}
