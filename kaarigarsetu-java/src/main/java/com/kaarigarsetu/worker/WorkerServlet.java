package com.kaarigarsetu.worker;

import com.google.gson.JsonObject;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.web.BaseServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/** POST /api/worker/register (public) | POST /api/worker/erase (DPDP deletion, own account) | GET /api/worker/me/passport | GET /api/worker/{id}/passport (own id only). */
@WebServlet(name = "WorkerServlet", urlPatterns = {"/api/worker/*"})
public final class WorkerServlet extends BaseServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String action = String.join("/", segments(request));
        if (action.equals("erase")) {
            app().erasure.eraseWorker(user(request).id(), string(readJson(request), "confirm"));
            request.getSession().invalidate();   // the account no longer exists
            json(response, 200, Map.of("erased", true));
            return;
        }
        String[] parts = segments(request);
        if (parts.length == 3 && parts[0].equals("confirmations")) {           // POST /api/worker/confirmations/{id}/accept | dispute
            long worker = user(request).id(), id = number(parts[1]);
            JsonObject body = readJson(request);
            switch (parts[2]) {
                case "accept" -> {
                    app().confirmations.accept(worker, id);
                    json(response, 200, Map.of("accepted", true));
                }
                case "dispute" -> {
                    app().confirmations.dispute(worker, id, string(body, "reason"));
                    json(response, 200, Map.of("disputed", true));
                }
                default -> throw new NotFoundException("Not found");
            }
            return;
        }
        if (!action.equals("register")) throw new NotFoundException("Not found");
        // Registration answers "already registered" (409), which tells a caller whether a number is on the platform, and creates rows: bound both.
        throttle(request, "register:worker", 20, java.time.Duration.ofMinutes(10));
        JsonObject body = readJson(request);
        Worker worker = app().workers.register(string(body, "name"), string(body, "phone"), string(body, "trade"),
                string(body, "language"), bool(body, "consent"));
        json(response, 201, Map.of("registered", true, "workerId", worker.workerId()));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] parts = segments(request);
        if (parts.length == 2 && parts[0].equals("me") && parts[1].equals("confirmations")) {       // GET /api/worker/me/confirmations
            json(response, 200, Map.of("confirmations", app().confirmations.list(user(request).id()).stream().map(WorkerServlet::row).toList()));
            return;
        }
        if (parts.length != 2 || !parts[1].equals("passport")) throw new NotFoundException("Not found");
        long own = user(request).id();
        long requested = parts[0].equals("me") ? own : number(parts[0]);
        if (requested != own) throw new NotFoundException("Not found");   // never reveal that another worker exists
        json(response, 200, app().workers.passport(own));
    }

    /** One confirmation for the worker's screen. LinkedHashMap, not Map.of: several of these values are legitimately null. */
    private static Map<String, Object> row(com.kaarigarsetu.attestation.ConfirmationResponseService.View view) {
        var c = view.confirmation();
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", c.attestationId());
        row.put("employerName", c.employerName());
        row.put("jobRole", c.jobRole());
        row.put("workDate", c.workDate() == null ? null : c.workDate().toString());
        row.put("ratingQuality", c.ratingQuality());
        row.put("ratingPunctuality", c.ratingPunctuality());
        row.put("ratingConduct", c.ratingConduct());
        row.put("wouldHireAgain", c.wouldHireAgain());
        row.put("response", c.workerResponse() == null ? "NONE" : c.workerResponse());
        row.put("disputeReason", c.disputeReason());
        row.put("disputeDecision", c.disputeDecision());
        row.put("heldOut", c.heldOut());
        row.put("canRespond", view.canRespond());
        row.put("daysLeftToDispute", view.daysLeftToDispute());
        return row;
    }
}
