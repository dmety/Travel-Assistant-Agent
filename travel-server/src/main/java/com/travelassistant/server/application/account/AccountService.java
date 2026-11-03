package com.travelassistant.server.application.account;

import com.travelassistant.server.infrastructure.persistence.Account;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 注册用例：校验输入、BCrypt 哈希密码、落盘到服务端 JSON。 */
@Service
public class AccountService {
  private static final String USERNAME_PATTERN = "^[A-Za-z0-9_]{3,32}$";

  private final AccountStore store;
  private final PasswordEncoder passwordEncoder;

  public AccountService(AccountStore store, PasswordEncoder passwordEncoder) {
    this.store = store;
    this.passwordEncoder = passwordEncoder;
  }

  public Account register(String username, String rawPassword) {
    validate(username, rawPassword);
    if (store.exists(username)) {
      throw new DuplicateAccountException(username);
    }
    String passwordHash = passwordEncoder.encode(rawPassword);
    Account account = new Account(username, passwordHash, List.of("ROLE_USER"));
    store.save(account);
    return account;
  }

  private void validate(String username, String rawPassword) {
    if (username == null || !username.matches(USERNAME_PATTERN)) {
      throw new InvalidAccountException("用户名需为 3-32 位字母、数字或下划线");
    }
    if (rawPassword == null || rawPassword.length() < 8) {
      throw new InvalidAccountException("密码至少 8 位");
    }
  }
}
