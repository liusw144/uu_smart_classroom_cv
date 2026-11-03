package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {"classroom.queue.school-limit=300", "classroom.queue.global-limit=300"})
class SchedulerCapacityIT extends EdgeFlowIT {
  @org.springframework.beans.factory.annotation.Autowired
  cn.uusmart.classroom.infrastructure.pipeline.JdbcPipelineRepository pipeline;

  @Test
  void expiredLeaseOnDeadNodeIsReassignedWithoutOldCallbackAuthority() throws Exception {
    register();
    String id = enqueue();
    data(node("POST", "/claim", null, NODE_TOKEN, "it-node-a", null));
    String oldLease =
        jdbc.queryForObject("SELECT lease_id FROM detection_job WHERE id=?", String.class, id);
    jdbc.update(
        "UPDATE detection_job SET lease_until=TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(6)) WHERE id=?",
        id);
    jdbc.update("UPDATE edge_node SET enabled=false WHERE id='it-node-a'");
    jdbc.update(
        "INSERT INTO edge_node(id,tenant_id,name,token_hash,last_seen_at) SELECT 'replacement',tenant_id,name,token_hash,CURRENT_TIMESTAMP(6) FROM edge_node WHERE id='it-node-a'");
    pipeline.recoverAssignments();
    assertEquals(
        "replacement",
        jdbc.queryForObject("SELECT node_id FROM detection_job WHERE id=?", String.class, id));
    var next = data(node("POST", "/claim", null, NODE_TOKEN, "replacement", null));
    assertEquals(id, next.path("job_id").asText());
    assertNotEquals(oldLease, next.path("lease_id").asText());
    assertEquals(
        401,
        node("POST", "/jobs/" + id + "/heartbeat", null, NODE_TOKEN, "it-node-a", oldLease)
            .statusCode());
  }

  String body(String id) throws Exception {
    var job = data(call("GET", "/detections/" + id, null, teacher));
    return "{\"task_id\":"
        + job.path("task_id").asLong()
        + ",\"asset_id\":\""
        + job.path("asset_id").asText()
        + "\",\"model_id\":"
        + job.path("model_id").asLong()
        + "}";
  }

  @Test
  void burst300JobsIsBoundedWithoutLostOrExtraSuccess() throws Exception {
    register();
    jdbc.update("UPDATE edge_node SET max_pending=300 WHERE id='it-node-a'");
    String input = body(enqueue());
    var pool = Executors.newFixedThreadPool(32);
    var requests = new ArrayList<Future<Integer>>();
    try {
      for (int i = 0; i < 300; i++)
        requests.add(pool.submit(() -> call("POST", "/detections", input, teacher).statusCode()));
      var results = new ArrayList<Integer>();
      for (var request : requests) results.add(request.get(120, TimeUnit.SECONDS));
      assertEquals(299, results.stream().filter(s -> s == 200).count());
      assertEquals(1, results.stream().filter(s -> s == 429).count());
      assertEquals(
          300,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM detection_job WHERE tenant_id=101", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void assignedBacklogBalancesAcrossLiveNodesAndRejectsOverflow() throws Exception {
    register();
    jdbc.update(
        "INSERT INTO edge_node(id,tenant_id,name,token_hash,last_seen_at,max_pending) SELECT 'it-node-a2',tenant_id,name,token_hash,last_seen_at,2 FROM edge_node WHERE id='it-node-a'");
    jdbc.update("UPDATE edge_node SET max_pending=2 WHERE id='it-node-a'");
    String input = body(enqueue());
    for (int i = 0; i < 3; i++)
      assertEquals(200, call("POST", "/detections", input, teacher).statusCode());
    assertEquals(429, call("POST", "/detections", input, teacher).statusCode());
    assertEquals(
        List.of(2, 2),
        jdbc.query(
            "SELECT COUNT(*) n FROM detection_job WHERE tenant_id=101 GROUP BY node_id ORDER BY node_id",
            (row, n) -> row.getInt(1)));
  }
}
