package com.travelassistant.server.interfaces.rest;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelassistant.server.application.budget.BudgetService;
import com.travelassistant.server.application.export.ExportService;
import com.travelassistant.server.application.invitation.InvitationService;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Plan;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 行程控制器的 Web 层切片。
 *
 * <p>这里只验证「HTTP 契约」这一层：参数怎么绑定、缺参和类型错误给什么状态码与错误体、
 * 响应头怎么写、下载文件名怎么编码。业务规则（权限、版本、归档）由集成测试覆盖，
 * 所以四个 service 全部用 mock——切片的意义就是不必为了验证一个响应头拉起整台容器。
 *
 * <p>用 {@code @WebMvcTest} 前，验证「漏传 version 会怎样」必须走一次完整 HTTP：
 * 而这类错误连 service 都进不去，为它付一次 Spring 上下文启动的代价并不划算。
 */
@WebMvcTest(ItineraryController.class)
@DisplayName("行程控制器（Web 切片）")
class ItineraryControllerTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private ItineraryService service;

  @MockitoBean
  private BudgetService budgetService;

  @MockitoBean
  private InvitationService invitationService;

  @MockitoBean
  private ExportService exportService;

  private static Itinerary itineraryAtVersion(long version) {
    return new Itinerary("it-1", "alice", "杭州三天两晚", version, Instant.EPOCH, Instant.EPOCH,
        List.of(), Plan.empty(), List.of(), List.of(), List.of(), List.of(), false, null);
  }

  @Test
  @DisplayName("漏传 version：400 且给出参数名，不触达 service")
  void missingVersionParamIs400() throws Exception {
    mvc.perform(put("/api/itineraries/it-1/plan")
            .with(user("alice")).with(csrf())
            .contentType("application/json")
            .content("{\"destination\":\"杭州\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.parameter").value("version"))
        .andExpect(jsonPath("$.error").value(containsString("version")));

    verifyNoInteractions(service);
  }

  @Test
  @DisplayName("version 不是数字：400 并指出是哪个参数、值是什么，不触达 service")
  void nonNumericVersionIs400() throws Exception {
    mvc.perform(put("/api/itineraries/it-1/plan")
            .param("version", "abc")
            .with(user("alice")).with(csrf())
            .contentType("application/json")
            .content("{\"destination\":\"杭州\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.parameter").value("version"))
        .andExpect(jsonPath("$.error").value(allOf(containsString("version"), containsString("abc"))));

    verifyNoInteractions(service);
  }

  @Test
  @DisplayName("板块读取返回当前版本的 X-Version 头")
  void sectionReadReturnsVersionHeader() throws Exception {
    when(service.get("it-1", "alice")).thenReturn(itineraryAtVersion(7));
    when(service.getPlan("it-1", "alice")).thenReturn(new Plan("杭州", "西湖", "红叶季"));

    mvc.perform(get("/api/itineraries/it-1/plan").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Version", "7"))
        .andExpect(jsonPath("$.destination").value("杭州"));
  }

  @Test
  @DisplayName("导出：Content-Disposition 用 RFC 5987 的 UTF-8 文件名，中文标题不靠平台默认编码")
  void exportFileNameIsUtf8Encoded() throws Exception {
    when(exportService.toText("it-1", "alice")).thenReturn("杭州三天两晚\n西湖—灵隐—西溪");
    when(exportService.fileName("it-1", "alice")).thenReturn("杭州三天两晚.txt");

    mvc.perform(get("/api/itineraries/it-1/export").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition",
            allOf(containsString("attachment"), containsString("filename*=UTF-8''"))))
        .andExpect(content().contentTypeCompatibleWith("text/plain"))
        .andExpect(content().string(containsString("杭州三天两晚")));
  }

  @Test
  @DisplayName("写操作带齐参数时把版本号原样传给 service")
  void updatePassesVersionToService() throws Exception {
    when(service.updatePlan(anyString(), anyString(), any(Plan.class), anyLong()))
        .thenReturn(itineraryAtVersion(8));

    mvc.perform(put("/api/itineraries/it-1/plan")
            .param("version", "7")
            .with(user("alice")).with(csrf())
            .contentType("application/json")
            .content("{\"destination\":\"杭州\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(8));
  }
}
