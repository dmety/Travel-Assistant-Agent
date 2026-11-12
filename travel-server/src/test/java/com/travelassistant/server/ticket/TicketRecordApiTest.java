package com.travelassistant.server.ticket;

import com.travelassistant.server.V2ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TicketRecordApiTest extends V2ApiTestBase {
  @Test void registrationPermissionsVersionExportAndCompatibility() throws Exception {
    MockHttpSession owner=registerAndLogin(),stranger=registerAndLogin();
    String id=createItinerary(owner,"购票登记","北京"),base="/api/itineraries/"+id;
    JsonNode trip=read(mvc.perform(post(base+"/activities").session(owner).param("version","1")
        .contentType("application/json").content("{\"date\":\"2026-11-21\",\"title\":\"八达岭长城\"}"))
        .andExpect(status().isOk()).andReturn());
    String activity=trip.at("/dailySchedules/0/activities/0/id").asText();
    String endpoint=base+"/activities/"+activity+"/ticket-record";
    String record="{\"attractionId\":\"amap-B000A45467\",\"product\":\"成人门票\",\"visitDate\":\"2026-11-21\",\"travelers\":2,\"paidTotal\":70,\"source\":\"PLATFORM_VERIFIED\"}";
    mvc.perform(put(endpoint).param("version","2").contentType("application/json").content(record)).andExpect(status().isUnauthorized());
    mvc.perform(put(endpoint).session(stranger).param("version","2").contentType("application/json").content(record)).andExpect(status().isForbidden());
    mvc.perform(put(endpoint).session(owner).param("version","1").contentType("application/json").content(record)).andExpect(status().isConflict());
    mvc.perform(put(endpoint).session(owner).param("version","2").contentType("application/json").content(record.replace("2026-11-21","2026-11-22"))).andExpect(status().isBadRequest());
    mvc.perform(put(endpoint).session(owner).param("version","2").contentType("application/json").content(record.replace("70","-1"))).andExpect(status().isBadRequest());
    mvc.perform(put(endpoint.replace(activity,"missing")).session(owner).param("version","2").contentType("application/json").content(record)).andExpect(status().isBadRequest());
    for (String invalid : new String[]{"null", "{}", record.replace("成人门票", " "),
        record.replace("\"travelers\":2", "\"travelers\":0"),
        record.replace("\"travelers\":2", "\"travelers\":101"),
        record.replace("70", "1.123"),
        record.replace("\"product\":\"成人门票\"", "\"product\":\"" + "x".repeat(101) + "\""),
        record.replace("\"product\":\"成人门票\"", "\"session\":\"" + "x".repeat(101) + "\",\"product\":\"成人门票\"")}) {
      mvc.perform(put(endpoint).session(owner).param("version","2").contentType("application/json").content(invalid))
          .andExpect(status().isBadRequest());
    }
    for(int version=2;version<=3;version++) {
      trip=read(mvc.perform(put(endpoint).session(owner).param("version",Integer.toString(version)).contentType("application/json").content(record))
          .andExpect(status().isOk()).andReturn());
      assertThat(trip.at("/dailySchedules/0/activities").size()).isEqualTo(1);
      assertThat(trip.at("/dailySchedules/0/activities/0/ticketRecord/source").asText()).isEqualTo("USER_REPORTED");
      assertThat(trip.get("expenses").size()).isZero();
    }
    String exported=mvc.perform(get(base+"/export").session(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(exported).contains("用户自报已购票／已预约，未经平台核验");
    var old=json.readValue("{\"id\":\"old\",\"title\":\"旧活动\"}",com.travelassistant.server.domain.itinerary.DailyActivity.class);
    assertThat(old.ticketRecord()).isNull();
    // 整表保存不能伪造已核验来源；原样更新应保留登记。
    mvc.perform(put(base+"/daily").session(owner).param("version","4").contentType("application/json").content(json.writeValueAsString(trip.get("dailySchedules"))))
        .andExpect(status().isOk());
    mvc.perform(put(base+"/daily").session(owner).param("version","5").contentType("application/json").content(json.writeValueAsString(trip.get("dailySchedules")).replace("USER_REPORTED","VERIFIED")))
        .andExpect(status().isBadRequest());
    mvc.perform(post(base+"/archive").session(owner)).andExpect(status().isOk());
    mvc.perform(put(endpoint).session(owner).param("version","6").contentType("application/json").content(record)).andExpect(status().isConflict());
  }
  @Test void assistantEndpointsAndPrivateContext() throws Exception {
    // 购票页已并入工作台，旧地址只做 302；票务接口本身仍照常可用。
    mvc.perform(get("/ticket-assistant.html")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/api/tickets/catalog")).andExpect(status().isOk());
    mvc.perform(post("/api/agent/tickets/advice").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    MockHttpSession owner=registerAndLogin(),stranger=registerAndLogin();
    mvc.perform(post("/api/agent/tickets/advice").session(owner).contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.stockVerified").value(false));
    mvc.perform(post("/api/agent/tickets/advice").session(owner).contentType("application/json").content("{\"message\":\"123456789012345678\"}"))
        .andExpect(status().isBadRequest());
    String id=createItinerary(owner,"私有行程","北京");
    String body=json.writeValueAsString(Map.of("itineraryId",id));
    mvc.perform(post("/api/agent/tickets/advice").session(stranger).contentType("application/json").content(body)).andExpect(status().isForbidden());
    mvc.perform(post("/api/agent/tickets/advice").session(owner).contentType("application/json").content(body)).andExpect(status().isOk());
  }
}
