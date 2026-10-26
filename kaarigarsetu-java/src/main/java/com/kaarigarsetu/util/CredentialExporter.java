package com.kaarigarsetu.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Exports a signed credential without relying on web or servlet APIs. */
public class CredentialExporter {
    public Path exportJson(Path destination, String signedJson) throws IOException {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(signedJson, "signedJson must not be null");
        createParentDirectories(destination);
        return Files.writeString(destination, signedJson, StandardCharsets.UTF_8);
    }

    /** A deliberately small, standards-compliant text PDF for local credential sharing. */
    public Path exportPdf(Path destination, String signedJson) throws IOException {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(signedJson, "signedJson must not be null");
        createParentDirectories(destination);
        String escaped = signedJson.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        String stream = "BT /F1 10 Tf 50 750 Td (" + escaped + ") Tj ET";
        String pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>endobj\n"
                + "4 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n"
                + "5 0 obj<</Length " + stream.getBytes(StandardCharsets.UTF_8).length + ">>stream\n"
                + stream + "\nendstream\nendobj\ntrailer<</Root 1 0 R>>\n%%EOF\n";
        return Files.writeString(destination, pdf, StandardCharsets.UTF_8);
    }

    private void createParentDirectories(Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
