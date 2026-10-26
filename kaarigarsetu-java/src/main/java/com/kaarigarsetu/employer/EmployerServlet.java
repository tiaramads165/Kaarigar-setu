package com.kaarigarsetu.employer;

import com.google.gson.JsonObject;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.web.BaseServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/** POST /api/employer/register (public) | POST /api/employer/erase (DPDP deletion, own account). */
@WebServlet(name = "EmployerServlet", urlPatterns = {"/api/employer/*"})
public final class EmployerServlet extends BaseServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String action = String.join("/", segments(request));
        if (action.equals("erase")) {
            var caller = user(request);
            if (caller.role() != com.kaarigarsetu.security.Role.EMPLOYER) throw new com.kaarigarsetu.exception.ForbiddenException("Not allowed");
            app().erasure.eraseEmployer(caller.id(), string(readJson(request), "confirm"));
            request.getSession().invalidate();
            json(response, 200, Map.of("erased", true));
            return;
        }
        if (!action.equals("register")) throw new NotFoundException("Not found");
        throttle(request, "register:employer", 20, java.time.Duration.ofMinutes(10));
        JsonObject body = readJson(request);
        Employer employer = app().employers.register(string(body, "name"), string(body, "phone"), bool(body, "consent"));
        json(response, 201, Map.of("registered", true, "employerId", employer.employerId()));
    }
}
