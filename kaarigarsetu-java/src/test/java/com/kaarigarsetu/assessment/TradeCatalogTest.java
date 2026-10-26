package com.kaarigarsetu.assessment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TradeCatalogTest {
    private final TradeCatalog catalog = TradeCatalog.load();

    @Test
    void theShippedCatalogOffersSixTradesWithBothNames() {
        assertEquals(Set.of("electrician", "plumber", "carpenter", "mason", "welder", "painter"),
                catalog.all().stream().map(TradeCatalog.Trade::key).collect(Collectors.toSet()));
        assertTrue(catalog.all().stream().allMatch(t -> !t.name().isBlank() && !t.nameHi().isBlank()));
    }

    @Test
    void lookupIsCaseAndWhitespaceTolerantAndRefusesUnknownTrades() {
        assertEquals("Welder", catalog.find("  WELDER ").orElseThrow().name());
        for (String bad : new String[]{null, "", "astronaut", "../etc/passwd", "welder; DROP TABLE workers"}) assertTrue(catalog.find(bad).isEmpty(), String.valueOf(bad));
    }

    @Test
    void aMalformedCatalogIsRejected() {
        TradeCatalog.Trade ok = new TradeCatalog.Trade("welder", "Welder", "वेल्डर");
        assertThrows(IllegalArgumentException.class, () -> new TradeCatalog(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TradeCatalog(List.of(ok, ok)));
        assertThrows(IllegalArgumentException.class, () -> new TradeCatalog(List.of(new TradeCatalog.Trade("Bad Key!", "X", "Y"))));
        assertThrows(IllegalArgumentException.class, () -> new TradeCatalog(List.of(new TradeCatalog.Trade("welder", "Welder", " "))));
    }

    @Test
    void everyOfferedTradeHasAScorerClass_andAMissingOneIsAStartupError() {
        List<AssessmentService> all = List.of(new ElectricianAssessmentService(), new PlumberAssessmentService(), new CarpenterAssessmentService(),
                new MasonAssessmentService(), new WelderAssessmentService(), new PainterAssessmentService());
        assertDoesNotThrow(() -> catalog.requireScorersFor(all));
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> catalog.requireScorersFor(all.subList(0, 5)));
        assertTrue(missing.getMessage().contains("painter"));
    }

    /** The three places a trade lives (catalog, ML rubric file, scorer class) must agree, or a worker could register for a trade that cannot be assessed. */
    @Test
    void theCatalogAndTheMlServiceRubricFilesListExactlyTheSameTrades() throws IOException {
        Path rubrics = Path.of("..", "kaarigarsetu-ml-service", "app", "models", "rubrics");
        Set<String> files;
        try (Stream<Path> stream = Files.list(rubrics)) {
            files = stream.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json") && !n.startsWith("_"))
                    .map(n -> n.substring(0, n.length() - 5)).collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(files, catalog.all().stream().map(TradeCatalog.Trade::key).collect(Collectors.toCollection(TreeSet::new)));
    }

    @Test
    void theNewTradesScoreAndGiveTheirOwnFeedbackThroughTheSharedTemplate() {
        AssessmentScoringDispatcher dispatcher = new AssessmentScoringDispatcher(List.of(new CarpenterAssessmentService(), new MasonAssessmentService(),
                new WelderAssessmentService(), new PainterAssessmentService()));
        List<BigDecimal> high = java.util.stream.Stream.of("90", "80", "100", "70", "85", "90", "80", "100", "70", "85").map(BigDecimal::new).toList();
        List<BigDecimal> low = java.util.stream.Stream.of("10", "0", "30", "20", "40", "10", "0", "30", "20", "40").map(BigDecimal::new).toList();
        for (String trade : List.of("Carpenter", "Mason", "Welder", "Painter")) {
            AssessmentResult strong = dispatcher.summarize(trade, high), weak = dispatcher.summarize(trade, low);
            assertEquals(trade, strong.trade());
            assertEquals(0, new BigDecimal("85.00").compareTo(strong.score()));
            assertTrue(strong.feedback().startsWith("Strong"), strong.feedback());
            assertTrue(weak.feedback().startsWith("Review"), weak.feedback());
            assertNotEquals(strong.feedback(), weak.feedback());
        }
        assertThrows(IllegalArgumentException.class, () -> dispatcher.summarize("Electrician", high), "a trade without a scorer is refused");
    }
}
