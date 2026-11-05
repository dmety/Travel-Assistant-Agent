package com.travelassistant.server.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.account.AccountService;
import com.travelassistant.server.application.account.DuplicateAccountException;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountRegistrationAtomicityTest {
  @TempDir Path temp;

  @Test
  void concurrentRegistrationCannotOverwriteTheWinningPassword() throws Exception {
    AccountStore store = new AccountStore(new ObjectMapper(), temp.toString());
    store.init();
    CyclicBarrier encoding = new CyclicBarrier(2);
    PasswordEncoder encoder = new PasswordEncoder() {
      public String encode(CharSequence raw) {
        try { encoding.await(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return "hash:" + raw;
      }
      public boolean matches(CharSequence raw, String hash) { return hash.equals("hash:" + raw); }
    };
    AccountService service = new AccountService(store, encoder);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> register(service, "password1"));
      var second = executor.submit(() -> register(service, "password2"));
      String a = first.get(10, TimeUnit.SECONDS);
      String b = second.get(10, TimeUnit.SECONDS);
      assertThat(java.util.List.of(a, b)).contains("duplicate");
      String winner = a.equals("duplicate") ? b : a;
      assertThat(store.findByUsername("sameuser").orElseThrow().passwordHash()).isEqualTo(winner);
      AccountStore reloaded = new AccountStore(new ObjectMapper(), temp.toString());
      reloaded.init();
      assertThat(reloaded.findByUsername("sameuser").orElseThrow().passwordHash()).isEqualTo(winner);
    }
  }

  private String register(AccountService service, String password) {
    try { return service.register("sameuser", password).passwordHash(); }
    catch (DuplicateAccountException e) { return "duplicate"; }
  }

  @Test
  void failedRegistrationDoesNotCreateALoginableAccount() throws Exception {
    Path directory = temp.resolve("data");
    AccountStore store = new AccountStore(new ObjectMapper(), directory.toString());
    store.init();
    Files.delete(directory.resolve("accounts.json"));
    Files.delete(directory);
    Files.createFile(directory); // A file in place of the directory reliably blocks writes on Windows too.
    AccountService service = new AccountService(store,
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());
    assertThatThrownBy(() -> service.register("faileduser", "Password123"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.exists("faileduser")).isFalse();
    Files.delete(directory);
    Files.createDirectory(directory);
    service.register("faileduser", "Password123");
    assertThat(store.exists("faileduser")).isTrue();
  }
}
