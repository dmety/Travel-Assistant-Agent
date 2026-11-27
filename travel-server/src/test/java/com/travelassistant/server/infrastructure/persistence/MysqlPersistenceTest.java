package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.collaboration.ChangeEntry;
import com.travelassistant.server.domain.decision.Vote;
import com.travelassistant.server.domain.decision.VoteChoice;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** H2 只验证适配器行为，真实 MySQL 验证须显式提供专用空测试库。 */
class MysqlPersistenceTest {
  HikariDataSource ds;
  JdbcTemplate jdbc;
  TransactionTemplate tx;
  ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  MysqlPersistenceConfiguration config = new MysqlPersistenceConfiguration();
  @TempDir Path directory;

  @BeforeEach void setup() {
    ds = new HikariDataSource();
    ds.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    for (String table : JdbcRecordTable.TABLES) {
      jdbc.execute("CREATE TABLE travel_" + table + " (id VARCHAR(191) PRIMARY KEY, scope_id VARCHAR(191), "
          + "ref_id VARCHAR(191), actor VARCHAR(191), payload LONGTEXT NOT NULL"
          + (table.equals("votes") ? ", UNIQUE(ref_id, actor)" : "") + ")");
    }
    jdbc.execute("CREATE TABLE travel_import_history (version INT PRIMARY KEY)");
  }
  @AfterEach void close() { ds.close(); }

  @Test void accountsAreUniqueAndReadableByNewAdapter() throws Exception {
    var accounts = config.mysqlAccounts(jdbc, mapper);
    accounts.init();
    assertThat(accounts.createIfAbsent(new Account("Alice", "test-only", List.of()))).isTrue();
    assertThat(accounts.createIfAbsent(new Account("Alice", "replacement", List.of()))).isFalse();
    assertThat(config.mysqlAccounts(jdbc, mapper).findByUsername("Alice").orElseThrow().passwordHash()).isEqualTo("test-only");
    assertThat(accounts.findByUsername("alice")).isEmpty();
  }

  @Test void itineraryUpsertAndDelete() {
    var store = config.mysqlItineraries(jdbc, mapper);
    var value = Itinerary.create("alice", "北京旅行", null);
    store.save(value); store.save(value);
    assertThat(config.mysqlItineraries(jdbc, mapper).findById(value.id())).contains(value);
    assertThat(store.findAll()).hasSize(1);
    store.delete(value.id());
    assertThat(store.findById(value.id())).isEmpty();
  }

  @Test void votesReplaceAndPurgeOnlyTargetItinerary() {
    var store = config.mysqlDecisions(jdbc, mapper, tx);
    var vote = Vote.create("trip", "candidate", "alice", VoteChoice.SUPPORT);
    store.saveVote(vote);
    store.saveVote(vote.withChoice(VoteChoice.OPPOSE));
    store.saveVote(Vote.create("other", "other-candidate", "alice", VoteChoice.SUPPORT));
    assertThat(store.findVotes("trip")).hasSize(1);
    assertThat(store.findVote("candidate", "alice").orElseThrow().choice()).isEqualTo(VoteChoice.OPPOSE);
    store.deleteCandidate("trip", "candidate");
    assertThat(store.findVotes("trip")).isEmpty();
    assertThat(store.findVotes("other")).hasSize(1);
    store.purge("other");
    assertThat(store.findVotes("other")).isEmpty();
  }

  @Test void changeRetentionAndVersionTieBreak() {
    var store = config.mysqlCollaboration(jdbc, mapper, tx);
    Instant now = Instant.now();
    for (int i = 0; i < 502; i++) store.addChange(ChangeEntry.of("trip", "alice", "PLAN", "test", i, now));
    assertThat(store.findChanges("trip", 1000)).hasSize(500);
    assertThat(store.findChanges("trip", 1).getFirst().version()).isEqualTo(501);
    store.purge("trip");
    assertThat(store.findChanges("trip", 50)).isEmpty();
  }

  @Test void importIsAtomicAndNotRepeated() throws Exception {
    mapper.writeValue(directory.resolve("accounts.json").toFile(), List.of(new Account("imported", "test-only", List.of())));
    config.importJson(jdbc, mapper, directory.toString(), tx);
    assertThat(config.mysqlAccounts(jdbc, mapper).exists("imported")).isTrue();
    jdbc.update("DELETE FROM travel_accounts");
    config.importJson(jdbc, mapper, directory.toString(), tx);
    assertThat(config.mysqlAccounts(jdbc, mapper).exists("imported")).isFalse();
    assertThat(directory.resolve("accounts.json")).exists();
  }

  @Test void failedImportRollsBackAllTables() throws Exception {
    mapper.writeValue(directory.resolve("accounts.json").toFile(), List.of(new Account("imported", "test-only", List.of())));
    Files.writeString(directory.resolve("votes.json"), "[{\"id\":\"duplicate\"},{\"id\":\"duplicate\"}]");
    assertThatThrownBy(() -> config.importJson(jdbc, mapper, directory.toString(), tx)).isInstanceOf(RuntimeException.class);
    for (String table : JdbcRecordTable.TABLES)
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM travel_" + table, Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM travel_import_history", Long.class)).isZero();
  }

  @Test void nonemptyImportTargetIsNeverOverwritten() {
    config.mysqlAccounts(jdbc, mapper).createIfAbsent(new Account("existing", "test-only", List.of()));
    assertThatThrownBy(() -> config.importJson(jdbc, mapper, directory.toString(), tx)).isInstanceOf(IllegalStateException.class);
    assertThat(config.mysqlAccounts(jdbc, mapper).exists("existing")).isTrue();
  }

  @Test void missingImportSourceDoesNotMarkCompleted() {
    assertThatThrownBy(() -> config.importJson(jdbc, mapper, directory.toString(), tx)).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM travel_import_history", Long.class)).isZero();
  }

  @Test void invitationBatchRollsBackOnFailure() {
    var store = config.mysqlInvitations(jdbc, mapper, tx);
    var invitation = com.travelassistant.server.domain.invitation.Invitation.create("trip", "alice", "bob", java.util.Set.of());
    store.save(invitation);
    assertThat(store.findByItinerary("trip")).containsExactly(invitation);
    var batch = java.util.Arrays.asList(invitation.accept(), null);
    assertThatThrownBy(() -> store.saveAll(batch)).isInstanceOf(RuntimeException.class);
    assertThat(store.findById(invitation.id()).orElseThrow().isPending()).isTrue();
  }

  @Test void commentsAndOrdersAreScoped() {
    var comments = config.mysqlCollaboration(jdbc, mapper, tx);
    var comment = com.travelassistant.server.domain.collaboration.Comment.create("trip", "alice", "中文留言");
    comments.addComment(comment);
    assertThat(comments.findComments("trip")).containsExactly(comment);
    assertThat(comments.findComments("other")).isEmpty();
    var orders = config.mysqlOrders(jdbc, mapper);
    Instant now = Instant.now();
    var order = new com.travelassistant.server.domain.ticket.DemoTicketOrder("order", "alice", "attraction", "景点", "demo", "演示",
        1, new java.math.BigDecimal("10.50"), "CREATED", "UNPAID", "NONE", null, null, null, now, now.plusSeconds(600), now, "演示订单");
    orders.save(order); orders.save(order);
    assertThat(orders.find("order")).contains(order);
    assertThat(orders.findByOwner("alice")).containsExactly(order);
    assertThat(orders.findByOwner("bob")).isEmpty();
  }

  @Test void defaultMysqlConfigurationLoadsCredentialsAndPoolSettings() throws Exception {
    try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(MysqlPersistenceConfiguration.class)
        .web(org.springframework.boot.WebApplicationType.NONE).lazyInitialization(true)
        .run("--spring.profiles.active=config-verification")) {
      var pool = context.getBean(HikariDataSource.class);
      assertThat(context.getEnvironment().getProperty("travel.persistence")).isEqualTo("mysql");
      assertThat(pool.getUsername()).isNotNull();
      assertThat(pool.getPassword()).isNotNull();
      assertThat(pool.getJdbcUrl()).startsWith("jdbc:mysql://localhost:3306/travel_assistant");
      assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
      assertThat(pool.isRunning()).isFalse(); // 配置验证不连接实际业务数据库。
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
  void realMysqlSchemaAndRoundTrip() {
    // URL 必须指向专用空测试库；不借用应用凭据或业务库。
    try (var real = config.mysqlDataSource(System.getenv("MYSQL_TEST_URL"),
        System.getenv("MYSQL_TEST_USER"), System.getenv("MYSQL_TEST_PASSWORD"))) {
      var realJdbc = config.mysqlJdbc(real, mapper, directory.toString(), false);
      var accounts = config.mysqlAccounts(realJdbc, mapper);
      String name = "test-" + UUID.randomUUID();
      try {
        assertThat(accounts.createIfAbsent(new Account(name, "test-only", List.of()))).isTrue();
        assertThat(accounts.findByUsername(name)).isPresent();
        assertThat(accounts.createIfAbsent(new Account(name, "test-only", List.of()))).isFalse();
      } finally { realJdbc.update("DELETE FROM travel_accounts WHERE id = ?", name); }
    }
  }
}

