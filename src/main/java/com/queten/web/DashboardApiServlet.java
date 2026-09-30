package com.queten.web;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.queten.config.AppConfig;
import com.queten.dashboard.DashboardService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** GET /api/dashboard?from=yyyy-MM-dd&to=yyyy-MM-dd&productCode=... */
@WebServlet("/api/dashboard")
public class DashboardApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(DashboardApiServlet.class.getName());
    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_DAYS = 366;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private transient DashboardService service;
    private transient ZoneId zone;

    @Override
    public void init() {
        AppConfig config = AppConfig.get();
        service = new DashboardService(config);
        zone = config.zone();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");

        try {
            LocalDate to = parseDate(req.getParameter("to"), LocalDate.now(zone), "to");
            LocalDate from = parseDate(req.getParameter("from"), to.minusDays(DEFAULT_DAYS - 1L), "from");
            if (from.isAfter(to)) {
                throw new IllegalArgumentException("from이 to보다 늦습니다.");
            }
            if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
                throw new IllegalArgumentException("조회 기간은 최대 " + MAX_DAYS + "일입니다.");
            }
            String productCode = req.getParameter("productCode");
            if (productCode != null && productCode.isBlank()) {
                productCode = null;
            }

            GSON.toJson(service.build(from, to, productCode == null ? null : productCode.trim()), resp.getWriter());
        } catch (IllegalArgumentException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "대시보드 조회 실패", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static LocalDate parseDate(String value, LocalDate defaultValue, String name) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " 형식이 잘못됐습니다 (yyyy-MM-dd): " + value);
        }
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        GSON.toJson(Map.of("error", message == null ? "알 수 없는 오류" : message), resp.getWriter());
    }
}
