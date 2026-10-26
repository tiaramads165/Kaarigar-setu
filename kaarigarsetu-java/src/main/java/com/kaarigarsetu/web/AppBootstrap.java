package com.kaarigarsetu.web;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.DatabaseConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.annotation.WebListener;

import java.util.EnumSet;

/** Starts the application: validates configuration (fail fast), wires services, hardens the session cookie. */
@WebListener
public final class AppBootstrap implements ServletContextListener {
    private static final System.Logger LOG = System.getLogger("ks.web");

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        AppSettings settings = AppSettings.fromEnvironment();
        try {
            context.setAttribute(AppContext.ATTRIBUTE, AppContext.create(settings, new DatabaseConfig()));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Startup failed: {0}", failure.getMessage());
            throw failure;
        }
        context.setSessionTimeout(30);
        context.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.COOKIE));
        SessionCookieConfig cookie = context.getSessionCookieConfig();
        cookie.setName("KSSID");
        cookie.setHttpOnly(true);
        cookie.setSecure(settings.cookieSecure());
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Strict");
        LOG.log(System.Logger.Level.INFO, "Kaarigar Setu started (env={0}, secureCookie={1}, ml={2})",
                settings.appEnv(), settings.cookieSecure(), settings.mlServiceUrl());
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        Object app = event.getServletContext().getAttribute(AppContext.ATTRIBUTE);
        if (app instanceof AppContext context) context.close();
    }
}
