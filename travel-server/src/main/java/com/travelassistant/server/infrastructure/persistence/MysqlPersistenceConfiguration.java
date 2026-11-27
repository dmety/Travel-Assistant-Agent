package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.invitation.Invitation;
import com.travelassistant.server.domain.collaboration.Comment;
import com.travelassistant.server.domain.collaboration.ChangeEntry;
import com.travelassistant.server.domain.decision.Candidate;
import com.travelassistant.server.domain.decision.Vote;
import com.travelassistant.server.domain.ticket.DemoTicketOrder;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL 模式不加载业务 JSON、不建立进程内业务副本。保留现有服务接口，仍按单实例部署。 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties
@ConditionalOnProperty(name = "travel.persistence", havingValue = "mysql")
public class MysqlPersistenceConfiguration {
  @Bean(destroyMethod = "close")
  @org.springframework.boot.context.properties.ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource mysqlDataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String username,
      @Value("${spring.datasource.password}") String password) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(url); ds.setUsername(username); ds.setPassword(password);
    return ds;
  }

  @Bean
  JdbcTemplate mysqlJdbc(HikariDataSource ds, ObjectMapper mapper,
      @Value("${travel.data-directory}") String directory,
      @Value("${travel.mysql.import-json:false}") boolean importJson) {
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    for (String table : JdbcRecordTable.TABLES) {
      jdbc.execute("CREATE TABLE IF NOT EXISTS travel_" + table + " ("
          + "id VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY, "
          + "scope_id VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin, "
          + "ref_id VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin, "
          + "actor VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin, payload JSON NOT NULL, "
          + "INDEX ix_scope (scope_id), INDEX ix_actor (actor)"
          + (table.equals("votes") ? ", UNIQUE KEY uq_vote (ref_id, actor)" : "")
          + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
    jdbc.execute("CREATE TABLE IF NOT EXISTS travel_import_history (version INT PRIMARY KEY) ENGINE=InnoDB");
    if (importJson) importJson(jdbc, mapper, directory, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    return jdbc;
  }

  /** 显式一次性导入；目标必须为空，所有业务表与完成标记在同一事务提交。 */
  void importJson(JdbcTemplate jdbc, ObjectMapper mapper, String directory, TransactionTemplate tx) {
    tx.executeWithoutResult(status -> {
      if (!jdbc.queryForList("SELECT version FROM travel_import_history WHERE version = 1").isEmpty()) return;
      for (String table : JdbcRecordTable.TABLES) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM travel_" + table, Long.class) != 0)
          throw new IllegalStateException("JSON import requires empty business tables; existing data was not changed");
      }
      if (JdbcRecordTable.TABLES.stream().noneMatch(table -> java.nio.file.Files.isRegularFile(
          java.nio.file.Path.of(directory, table + ".json"))))
        throw new IllegalStateException("No business JSON files found; check TRAVEL_DATA_DIR");
      for (String table : JdbcRecordTable.TABLES) {
        java.nio.file.Path file = java.nio.file.Path.of(directory, table + ".json");
        if (!java.nio.file.Files.exists(file)) continue;
        try {
          var data = mapper.readTree(file.toFile());
          if (!data.isArray()) throw new IllegalStateException("Expected JSON array: " + table);
          var rows = new JdbcRecordTable<>(jdbc, mapper, com.fasterxml.jackson.databind.JsonNode.class, table);
          for (var item : data) {
            String id = item.path(table.equals("accounts") ? "username" : "id").asText();
            if (id.isBlank()) throw new IllegalStateException("Missing record identifier: " + table);
            rows.insert(id, text(item, "itineraryId"), text(item, "candidateId"),
                text(item, table.equals("votes") ? "voter" : "owner"), item);
          }
        } catch (java.io.IOException e) {
          // 不把源文件内容（可能包含账号和行程）带入异常日志。
          throw new IllegalStateException("Cannot read JSON import: " + table);
        }
      }
      jdbc.update("INSERT INTO travel_import_history (version) VALUES (1)");
    });
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode node, String name) {
    return node.hasNonNull(name) ? node.get(name).asText() : null;
  }

  @Bean
  TransactionTemplate mysqlTransactions(HikariDataSource ds) {
    return new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  @Bean
  AccountStore mysqlAccounts(JdbcTemplate jdbc, ObjectMapper mapper) {
    var rows = new JdbcRecordTable<>(jdbc, mapper, Account.class, "accounts");
    return new AccountStore(mapper, ".") {
      @Override public void init() {}
      @Override public Optional<Account> findByUsername(String name) { return rows.find(name); }
      @Override public boolean exists(String name) { return rows.find(name).isPresent(); }
      @Override public boolean createIfAbsent(Account a) {
        try { rows.insert(a.username(), null, null, null, a); return true; }
        catch (DuplicateKeyException e) { return false; }
      }
    };
  }

  @Bean
  ItineraryStore mysqlItineraries(JdbcTemplate jdbc, ObjectMapper mapper) {
    var rows = new JdbcRecordTable<>(jdbc, mapper, Itinerary.class, "itineraries");
    return new ItineraryStore(mapper, ".") {
      @Override public void init() {}
      @Override public Optional<Itinerary> findById(String id) { return rows.find(id); }
      @Override public List<Itinerary> findAll() { return rows.all(); }
      @Override public void save(Itinerary value) { rows.save(value.id(), null, null, null, value); }
      @Override public void delete(String id) { rows.delete("id = ?", id); }
    };
  }

  @Bean
  InvitationStore mysqlInvitations(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate tx) {
    var rows = new JdbcRecordTable<>(jdbc, mapper, Invitation.class, "invitations");
    return new InvitationStore(mapper, ".") {
      @Override public void init() {}
      @Override public List<Invitation> findAll() { return rows.all(); }
      @Override public Optional<Invitation> findById(String id) { return rows.find(id); }
      @Override public List<Invitation> findByItinerary(String id) { return rows.query("scope_id = ?", id); }
      @Override public Invitation save(Invitation v) { rows.save(v.id(), v.itineraryId(), null, null, v); return v; }
      @Override public void saveAll(List<Invitation> changed) {
        if (changed != null) tx.executeWithoutResult(status -> changed.forEach(this::save));
      }
    };
  }

  @Bean
  DemoTicketOrderStore mysqlOrders(JdbcTemplate jdbc, ObjectMapper mapper) {
    var rows = new JdbcRecordTable<>(jdbc, mapper, DemoTicketOrder.class, "demo_ticket_orders");
    return new DemoTicketOrderStore(mapper, ".") {
      @Override public void init() {}
      @Override public Optional<DemoTicketOrder> find(String id) { return rows.find(id); }
      @Override public List<DemoTicketOrder> findByOwner(String owner) { return rows.query("actor = ?", owner); }
      @Override public void save(DemoTicketOrder v) { rows.save(v.id(), null, null, v.owner(), v); }
    };
  }

  @Bean
  CollaborationStore mysqlCollaboration(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate tx) {
    var comments = new JdbcRecordTable<>(jdbc, mapper, Comment.class, "comments");
    var changes = new JdbcRecordTable<>(jdbc, mapper, ChangeEntry.class, "changelog");
    Comparator<ChangeEntry> order = Comparator.comparing(ChangeEntry::at).thenComparingLong(ChangeEntry::version);
    return new CollaborationStore(mapper, ".") {
      @Override public void init() {}
      @Override public List<Comment> findComments(String id) {
        return comments.query("scope_id = ?", id).stream().sorted(Comparator.comparing(Comment::createdAt)).toList();
      }
      @Override public Comment addComment(Comment v) { comments.insert(v.id(), v.itineraryId(), null, null, v); return v; }
      @Override public List<ChangeEntry> findChanges(String id, int limit) {
        return changes.query("scope_id = ?", id).stream().sorted(order.reversed()).limit(limit <= 0 ? 50 : limit).toList();
      }
      @Override public synchronized void addChange(ChangeEntry v) {
        tx.executeWithoutResult(status -> {
          changes.insert(v.id(), v.itineraryId(), null, null, v);
          var all = changes.query("scope_id = ?", v.itineraryId()).stream().sorted(order).toList();
          all.stream().limit(Math.max(0, all.size() - MAX_CHANGES_PER_ITINERARY)).forEach(c -> changes.delete("id = ?", c.id()));
        });
      }
      @Override public void purge(String id) {
        tx.executeWithoutResult(status -> { comments.delete("scope_id = ?", id); changes.delete("scope_id = ?", id); });
      }
    };
  }

  @Bean
  DecisionStore mysqlDecisions(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate tx) {
    var candidates = new JdbcRecordTable<>(jdbc, mapper, Candidate.class, "candidates");
    var votes = new JdbcRecordTable<>(jdbc, mapper, Vote.class, "votes");
    return new DecisionStore(mapper, ".") {
      @Override public void init() {}
      @Override public List<Candidate> findCandidates(String id) {
        return candidates.query("scope_id = ?", id).stream().sorted(Comparator.comparing(Candidate::createdAt)).toList();
      }
      @Override public Optional<Candidate> findCandidate(String id, String candidate) {
        return candidates.query("scope_id = ? AND id = ?", id, candidate).stream().findFirst();
      }
      @Override public Candidate addCandidate(Candidate v) { candidates.insert(v.id(), v.itineraryId(), null, null, v); return v; }
      @Override public void deleteCandidate(String id, String candidate) {
        tx.executeWithoutResult(status -> { candidates.delete("scope_id = ? AND id = ?", id, candidate); votes.delete("scope_id = ? AND ref_id = ?", id, candidate); });
      }
      @Override public List<Vote> findVotes(String id) {
        return votes.query("scope_id = ?", id).stream().sorted(Comparator.comparing(Vote::votedAt)).toList();
      }
      @Override public List<Vote> findVotes(String id, String candidate) {
        return votes.query("scope_id = ? AND ref_id = ?", id, candidate).stream().sorted(Comparator.comparing(Vote::votedAt)).toList();
      }
      @Override public Optional<Vote> findVote(String candidate, String voter) {
        return votes.query("ref_id = ? AND actor = ?", candidate, voter).stream().findFirst();
      }
      @Override public Vote saveVote(Vote v) { votes.save(v.id(), v.itineraryId(), v.candidateId(), v.voter(), v); return v; }
      @Override public boolean removeVote(String candidate, String voter) { return votes.delete("ref_id = ? AND actor = ?", candidate, voter) > 0; }
      @Override public void purge(String id) {
        tx.executeWithoutResult(status -> { candidates.delete("scope_id = ?", id); votes.delete("scope_id = ?", id); });
      }
    };
  }
}
