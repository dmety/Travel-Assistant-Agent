package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.ticket.TicketAssistantService;
import com.travelassistant.server.application.ticket.TicketCatalog;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
public class TicketAssistantController {
  private final TicketAssistantService service;
  private final TicketCatalog catalog;
  public TicketAssistantController(TicketAssistantService service, TicketCatalog catalog) {
    this.service=service; this.catalog=catalog;
  }
  @PostMapping("/api/agent/tickets/advice")
  public TicketAssistantService.Result advice(@AuthenticationPrincipal UserDetails user,
      @RequestBody TicketAssistantService.Request request) {
    return service.advise(request,user==null?null:user.getUsername());
  }
  @GetMapping("/api/tickets/catalog")
  public List<TicketCatalog.Site> catalog() { return catalog.all(); }
}
