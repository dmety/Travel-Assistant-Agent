package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.ticket.TicketService;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import com.travelassistant.server.domain.ticket.TicketChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购票辅助接口（V2 票务 Agent）。
 *
 * <p>只读，不涉及下单和支付。与景点查询一致对外开放，便于未登录时先看购票信息；
 * 真正的方案编辑仍然需要登录。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

  private final TicketService service;

  public TicketController(TicketService service) {
    this.service = service;
  }

  /** 按景点 id 或名称查询购票辅助信息。 */
  @GetMapping
  public TicketAdvice lookup(@RequestParam(required = false) String attractionId,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String city) {
    return service.lookup(attractionId, name, city);
  }

  /** 当前收录的官方入口清单，以及配置本身是否可用。 */
  @GetMapping("/channels")
  public Map<String, Object> channels() {
    Map<String, Object> body = new LinkedHashMap<>();
    List<TicketChannel> all = service.configuredChannels();
    body.put("count", all.size());
    body.put("channels", all);
    body.put("verificationNote",
        "入口为人工维护的白名单。本系统不抓取、不推断、不生成链接；"
            + "未收录的景点会明确提示自行前往官方渠道。");
    service.configurationProblem().ifPresent(problem -> body.put("problem", problem));
    return body;
  }
}
