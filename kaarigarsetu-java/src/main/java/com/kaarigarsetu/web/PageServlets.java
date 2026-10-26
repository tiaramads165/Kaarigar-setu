package com.kaarigarsetu.web;

import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.security.AuthenticatedUser;
import com.kaarigarsetu.security.Role;
import com.kaarigarsetu.worker.WorkerService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-rendered pages. Servlets prepare request-scoped data (as Maps, which Jakarta EL 5 on Tomcat 10.1 can read)
 * and forward to JSPs under WEB-INF, so no JSP is reachable directly and no business logic lives in a JSP.
 */
public final class PageServlets {
    private PageServlets() {}

    static final Map<String, String> AREA_LABELS = Map.of(
            "tools", "Tools \u00b7 \u0914\u091c\u093c\u093e\u0930", "materials", "Materials \u00b7 \u0938\u093e\u092e\u0917\u094d\u0930\u0940",
            "experience", "Experience \u00b7 \u0905\u0928\u0941\u092d\u0935", "knowledge", "Knowledge \u00b7 \u091c\u094d\u091e\u093e\u0928",
            "troubleshooting", "Troubleshooting \u00b7 \u0916\u0930\u093e\u092c\u0940 \u0922\u0942\u0901\u0922\u0928\u093e",
            "planning", "Planning and estimating \u00b7 \u092f\u094b\u091c\u0928\u093e \u0914\u0930 \u0905\u0928\u0941\u092e\u093e\u0928",
            "customer", "Customer handling \u00b7 \u0917\u094d\u0930\u093e\u0939\u0915 \u0938\u0947 \u092c\u093e\u0924");
    static final Map<String, String> BAND_LABELS = Map.of(
            "PASS", "Pass \u00b7 \u0909\u0924\u094d\u0924\u0940\u0930\u094d\u0923", "BORDERLINE", "Borderline \u00b7 \u0938\u0940\u092e\u093e \u092a\u0930",
            "NEEDS_REVIEW", "Needs review \u00b7 \u092a\u0941\u0928\u0903 \u091c\u093e\u0901\u091a");

    static final Map<String, String> TRUST_BAND_LABELS = Map.of(
            "EXCELLENT", "Excellent \u00b7 \u0909\u0924\u094d\u0915\u0943\u0937\u094d\u091f", "GOOD", "Good \u00b7 \u0905\u091a\u094d\u091b\u093e",
            "FAIR", "Fair \u00b7 \u0920\u0940\u0915", "BUILDING", "Building \u00b7 \u092c\u0928 \u0930\u0939\u093e \u0939\u0948",
            "NO_HISTORY", "No history yet \u00b7 \u0905\u092d\u0940 \u0915\u094b\u0908 \u0907\u0924\u093f\u0939\u093e\u0938 \u0928\u0939\u0940\u0902");

    static void render(HttpServletRequest request, HttpServletResponse response, String view) throws ServletException, IOException {
        request.setAttribute("csrfToken", Sessions.csrfToken(request.getSession(true)));
        request.getRequestDispatcher("/WEB-INF/jsp/" + view + ".jsp").forward(request, response);
    }

    @WebServlet(name = "IndexServlet", urlPatterns = {""})
    public static final class Index extends BaseServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            Optional<AuthenticatedUser> user = Sessions.current(request);
            if (user.isPresent() && user.get().role() == Role.WORKER) { response.sendRedirect(request.getContextPath() + "/worker-dashboard"); return; }
            if (user.isPresent() && user.get().role() == Role.EMPLOYER) { response.sendRedirect(request.getContextPath() + "/employer-dashboard"); return; }
            request.setAttribute("trades", app().trades.all().stream().map(t -> Map.<String, Object>of("key", t.key(), "name", t.name(), "nameHi", t.nameHi())).toList());
            render(request, response, "index");
        }
    }

    /** The dev-only list of seeded demo accounts with one-click sign-in. Not found unless DEMO_LOGIN is enabled (see AppSettings). */
    @WebServlet(name = "DemoPageServlet", urlPatterns = {"/demo"})
    public static final class DemoPage extends BaseServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            if (!app().settings.demoLoginEnabled() || !app().settings.appEnv().equals("dev")) throw new NotFoundException("Not found");
            request.setAttribute("workers", app().daos.workers().findDemoAccounts().stream().map(w -> Map.<String, Object>of(
                    "id", w.workerId(), "name", w.name(), "trade", w.trade(), "skill", w.skillScore(), "trust", w.trustScore(),
                    "trustBand", app().daos.workers().findTrustProfile(w.workerId()).map(t -> t.band() == null ? "" : t.band()).orElse(""))).toList());
            request.setAttribute("employers", app().daos.employers().findDemoAccounts().stream().map(e -> Map.<String, Object>of(
                    "id", e.employerId(), "name", e.name(), "verified", e.verified())).toList());
            render(request, response, "demo");
        }
    }

    @WebServlet(name = "WorkerDashboardServlet", urlPatterns = {"/worker-dashboard"})
    public static final class WorkerDashboard extends BaseServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            long workerId = user(request).id();
            WorkerService.Passport passport = app().workers.passport(workerId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", passport.name());
            view.put("trade", passport.trade());
            view.put("language", passport.language());
            view.put("skillScore", passport.skillScore());
            view.put("trustScore", passport.trustScore());
            view.put("verifiedEmployerCount", passport.verifiedEmployerCount());
            view.put("trustBand", passport.trustBand());
            view.put("trustBandLabel", passport.trustBand() == null ? null : TRUST_BAND_LABELS.getOrDefault(passport.trustBand(), passport.trustBand()));
            view.put("trust", passport.trustReport());
            view.put("skillBand", passport.skillBand());
            view.put("skillBandLabel", passport.skillBand() == null ? null : BAND_LABELS.getOrDefault(passport.skillBand(), passport.skillBand()));
            view.put("nearBoundary", passport.nearBoundary());
            view.put("areas", passport.skillAreas().entrySet().stream().map(e -> Map.<String, Object>of("label", AREA_LABELS.getOrDefault(e.getKey(), e.getKey()), "score", e.getValue())).toList());
            view.put("credentialRef", passport.latestCredentialRef());
            view.put("credentialRevoked", passport.latestCredentialRevoked());
            view.put("answers", passport.latestAssessment().stream().map(a -> Map.<String, Object>of(
                    "questionNo", a.questionNo(), "score", a.score(), "needsReview", a.needsReview())).toList());
            request.setAttribute("passport", view);
            request.setAttribute("attestations", app().attestations.forWorker(workerId).stream().map(PageServlets::attestationRow).toList());
            render(request, response, "worker-dashboard");
        }
    }

    @WebServlet(name = "AssessmentPageServlet", urlPatterns = {"/assessment"})
    public static final class AssessmentPage extends BaseServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            render(request, response, "assessment");
        }
    }

    @WebServlet(name = "EmployerDashboardServlet", urlPatterns = {"/employer-dashboard"})
    public static final class EmployerDashboard extends BaseServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            request.setAttribute("attestations", app().attestations.forEmployer(user(request).id()).stream().map(PageServlets::attestationRow).toList());
            render(request, response, "employer-dashboard");
        }
    }

    /** Public credential verification page (the QR code target): re-checks everything server-side. */
    @WebServlet(name = "VerifyPageServlet", urlPatterns = {"/verify/*"})
    public static final class VerifyPage extends BaseServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            String[] parts = segments(request);
            if (parts.length != 1) throw new NotFoundException("Not found");
            throttle(request, "verify", 120, java.time.Duration.ofMinutes(1));
            UUID ref;
            try {
                ref = UUID.fromString(parts[0]);
            } catch (IllegalArgumentException malformed) {
                response.setStatus(404);
                request.setAttribute("state", "NOT_FOUND");
                render(request, response, "verify");
                return;
            }
            CredentialService.Verification result = app().credentials.verify(ref);
            request.setAttribute("state", result.state().name());
            request.setAttribute("valid", result.valid());
            request.setAttribute("credential", result.summary());
            request.setAttribute("ref", ref.toString());
            var live = app().credentials.presentationStatus(ref, request.getParameter("p"));
            request.setAttribute("presentation", live.state().name());
            request.setAttribute("presentationAge", live.age().toSeconds());
            if (result.state() == CredentialService.State.NOT_FOUND) response.setStatus(404);
            render(request, response, "verify");
        }
    }

    private static Map<String, Object> attestationRow(AttestationDetail a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", a.attestationId());
        row.put("jobRole", a.jobRole());
        row.put("status", a.status());
        row.put("workDate", a.workDate());
        row.put("wouldHireAgain", a.wouldHireAgain());
        row.put("createdAt", a.createdAt());
        return row;
    }
}
