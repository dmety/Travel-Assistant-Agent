package com.travelassistant.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TravelClientLoginTest {
  @Test
  void loginOnlyAuthenticatesAndNeverRegistersAnAccount() throws Exception {
    checkLogin(200);
  }

  @Test
  void rejectedLoginNeverFallsBackToRegistration() throws Exception {
    checkLogin(401);
  }

  private void checkLogin(int status) throws Exception {
    AtomicInteger registrations = new AtomicInteger();
    AtomicInteger logins = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/account/register", exchange -> {
      registrations.incrementAndGet();
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
    });
    server.createContext("/api/login", exchange -> {
      logins.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    });
    server.start();
    try (ConsoleUI ui = ConsoleUI.plain()) {
      TravelClient client = new TravelClient("http://127.0.0.1:" + server.getAddress().getPort(), ui);
      if (status == 200) client.loginOnly("ui_probe", "test-only-password");
      else assertThrows(RuntimeException.class, () -> client.loginOnly("ui_probe", "test-only-password"));
      assertEquals(1, logins.get());
      assertEquals(0, registrations.get());
    } finally {
      server.stop(0);
    }
  }
}
