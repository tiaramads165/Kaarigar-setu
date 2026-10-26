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

/** OWASP A03/XSS guard: user data is only ever rendered through c:out, and browser code never uses an HTML sink. */
class XssGuardTest {
    private static final Path WEBAPP = Path.of("src", "main", "webapp");
    private static final Pattern EL = Pattern.compile("\\$\\{[^}]*}");
    private static final List<String> UNSAFE_JS = List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function(", "setTimeout(\"", "setInterval(\"");

    private static List<Path> files(String folder, String extension) throws IOException {
        try (Stream<Path> stream = Files.walk(WEBAPP.resolve(folder))) {
            return stream.filter(p -> p.toString().endsWith(extension)).toList();
        }
    }

    /** True when the EL expression at this position is wrapped by an encoding tag or sits in a control attribute (test/items). */
    private static boolean safeContext(String line, int start) {
        String before = line.substring(0, start);
        return before.matches("(?s).*<c:out\\s+value=[\"']$") || before.matches("(?s).*\\b(test|items|var|begin|end|step)=[\"']$")
                || before.matches("(?s).*<c:set\\s+[^>]*value=[\"']$");
    }

    @Test
    void everyElExpressionInEveryJspIsEncodedOrAControlExpression() throws IOException {
        List<String> offenders = new ArrayList<>();
        int expressions = 0;
        for (Path jsp : files("WEB-INF/jsp", ".jsp")) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(jsp)) {
                lineNumber++;
                Matcher m = EL.matcher(line);
                while (m.find()) {
                    expressions++;
                    if (!safeContext(line, m.start())) offenders.add(jsp.getFileName() + ":" + lineNumber + " " + m.group());
                }
            }
        }
        assertTrue(expressions > 20, "expected to inspect many EL expressions, saw " + expressions);
        assertEquals(List.of(), offenders, "raw EL output of possibly user-controlled data");
    }

    @Test
    void noJspUsesScriptletsOrRawOutputTags() throws IOException {
        for (Path jsp : files("WEB-INF/jsp", ".jsp")) {
            String text = Files.readString(jsp);
            assertFalse(text.contains("<%=") || text.contains("<% "), jsp + " contains a scriptlet");
            assertFalse(text.contains("escapeXml=\"false\""), jsp + " disables escaping");
            assertFalse(Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>").matcher(text).find(), jsp + " has an inline script (CSP forbids it)");
            assertFalse(Pattern.compile("\\son[a-z]+=\"").matcher(text).find(), jsp + " has an inline event handler");
        }
    }

    @Test
    void browserCodeNeverWritesHtmlOrEvaluatesStrings() throws IOException {
        for (Path js : files("static/js", ".js")) {
            String text = Files.readString(js).replaceAll("(?s)/\\*.*?\\*/", "");   // comments may mention the banned names
            for (String sink : UNSAFE_JS) assertFalse(text.contains(sink), js.getFileName() + " uses " + sink);
        }
    }

    @Test
    void theGuardWouldCatchARealViolation() {
        assertFalse(safeContext("<p>${user.name}</p>", 3));
        assertTrue(safeContext("<c:out value=\"${user.name}\"/>", 14));
        assertTrue(safeContext("<c:out value='${user.name}'/>", 14));
        assertFalse(safeContext("<a href=\"${user.site}\">", 9), "an attribute value is not encoded just because it is quoted");
        assertTrue(safeContext("<c:if test=\"${empty x}\">", 12));
    }
}
