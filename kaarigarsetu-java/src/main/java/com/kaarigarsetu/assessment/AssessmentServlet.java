package com.kaarigarsetu.assessment;

import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.web.BaseServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * POST /api/assessment/start | POST /api/assessment/submit-answer?attempt=..&question=N (raw WAV body)
 * | GET /api/assessment/result?attempt=.. | GET /api/assessment/challenge?attempt=..&question=N (the spoken code for the next question)
 * Worker role only; the attempt token is server-signed.
 */
@WebServlet(name = "AssessmentServlet", urlPatterns = {"/api/assessment/*"})
public class AssessmentServlet extends BaseServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long workerId = user(request).id();
        switch (String.join("/", segments(request))) {
            case "start" -> json(response, 201, app().assessments.start(workerId));
            case "submit-answer" -> {
                String token = request.getParameter("attempt");
                int question = parseQuestion(request.getParameter("question"));
                if (token == null) throw new ValidationException("attempt is required");
                int max = app().settings.maxAudioBytes();
                if (request.getContentLengthLong() > max) throw new ValidationException("Audio is too large");
                byte[] audio = request.getInputStream().readNBytes(max + 1);   // hard cap before buffering anything more
                if (audio.length > max) throw new ValidationException("Audio is too large");
                json(response, 201, app().assessments.submitAnswer(workerId, token, question, audio, request.getParameter("challenge")));
            }
            default -> throw new NotFoundException("Not found");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = String.join("/", segments(request));
        if (path.equals("challenge")) {
            String attempt = request.getParameter("attempt");
            if (attempt == null) throw new ValidationException("attempt is required");
            AssessmentPipelineService.Challenge challenge = app().assessments.challenge(user(request).id(), attempt, parseQuestion(request.getParameter("question")));
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("required", challenge.required());
            if (challenge.required()) {
                body.put("code", challenge.code());
                body.put("token", challenge.token());
                body.put("minutes", challenge.minutes());
            }
            json(response, 200, body);
            return;
        }
        if (!path.equals("result")) throw new NotFoundException("Not found");
        String token = request.getParameter("attempt");
        if (token == null) throw new ValidationException("attempt is required");
        List<Map<String, Object>> rows = app().assessments.result(user(request).id(), token).stream()
                .map(a -> Map.<String, Object>of("questionNo", a.questionNo(), "score", a.score(), "feedback", a.feedback() == null ? "" : a.feedback(),
                        "needsReview", a.needsReview()))
                .toList();
        json(response, 200, Map.of("answers", rows));
    }

    private static int parseQuestion(String text) {
        try {
            int n = Integer.parseInt(text);
            if (n < 1 || n > 20) throw new ValidationException("question out of range");
            return n;
        } catch (NumberFormatException malformed) {
            throw new ValidationException("question is required");
        }
    }
}
