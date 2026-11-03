package com.travelassistant.server.infrastructure.security;

import com.travelassistant.server.infrastructure.persistence.Account;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 自定义用户读取：用 JSON 账号仓库替换 Spring Boot 自动生成的默认内存用户。
 * 只要容器里存在 {@link UserDetailsService} bean，Spring Security 就不会再创建那个
 * 启动时打印随机密码的默认 {@code user} 账号。
 */
@Service
public class JsonUserDetailsService implements UserDetailsService {
  private final AccountStore store;

  public JsonUserDetailsService(AccountStore store) {
    this.store = store;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    Account account = store.findByUsername(username)
        .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
    return User.withUsername(account.username())
        .password(account.passwordHash())
        .authorities(AuthorityUtils.createAuthorityList(account.roles().toArray(new String[0])))
        .build();
  }
}
