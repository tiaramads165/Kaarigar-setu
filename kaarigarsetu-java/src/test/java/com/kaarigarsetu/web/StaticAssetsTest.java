package com.kaarigarsetu.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page that points at a script or stylesheet that does not exist fails silently in the browser (the button simply does nothing, or the page
 * shows a generic error). This once cost a rebuilt copy its audio converter, so every /static/... reference in a JSP must exist on disk.
 */
class StaticAssetsTest {
    private static final Path WEBAPP = Path.of("src/main/webapp");
    private static final Pattern REFERENCE = Pattern.compile("/static/[A-Za-z0-9_./-]+\\.(?:js|css|png|svg|ico|woff2?)");

    @Test
    void everyStaticFileReferencedByAPageExists() throws IOException {
        List<String> missing = new ArrayList<>();
        int references = 0;
        try (Stream<Path> pages = Files.walk(WEBAPP.resolve("WEB-INF/jsp"))) {
            for (Path page : pages.filter(p -> p.toString().endsWith(".jsp")).toList()) {
                Matcher matcher = REFERENCE.matcher(Files.readString(page, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    references++;
                    if (!Files.isRegularFile(WEBAPP.resolve(matcher.group().substring(1)))) missing.add(page.getFileName() + " -> " + matcher.group());
                }
            }
        }
        assertTrue(references >= 10, "the scan should have found the pages' scripts and styles, found " + references);
        assertEquals(List.of(), missing, "pages reference files that do not exist");
    }

    @Test
    void theAssessmentPageCanConvertItsRecording() throws IOException {
        String assessment = Files.readString(WEBAPP.resolve("static/js/assessment.js"), StandardCharsets.UTF_8);
        String converter = Files.readString(WEBAPP.resolve("static/js/wav.js"), StandardCharsets.UTF_8);
        assertTrue(assessment.contains("window.Wav.fromBlob"), "the page converts recordings through Wav.fromBlob");
        assertTrue(converter.contains("window.Wav") && converter.contains("fromBlob"), "wav.js must define Wav.fromBlob");
        assertFalse(assessment.isBlank());
    }

    @Test
    void theMicrophoneIsOnlyRequestedOnASecurePage() throws IOException {
        String assessment = Files.readString(WEBAPP.resolve("static/js/assessment.js"), StandardCharsets.UTF_8);
        assertTrue(assessment.contains("isSecureContext"), "an insecure page must get a clear message instead of a generic failure");
    }
}
