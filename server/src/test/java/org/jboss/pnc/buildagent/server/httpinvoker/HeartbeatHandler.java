package org.jboss.pnc.buildagent.server.httpinvoker;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class HeartbeatHandler extends HttpServlet {

    private final AtomicInteger counter;

    public HeartbeatHandler(AtomicInteger counter) {
        this.counter = counter;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        counter.incrementAndGet();
    }
}
