package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class PresalesDtos {
  private PresalesDtos() {}

  public record Create(
      String name,
      String customer,
      String ownerId,
      String agentId,
      String industry,
      String goal,
      Integer expectedVersion,
      String operationId) {}

  public record Command(
      Integer expectedVersion, String operationId, String action, ObjectNode payload) {}

  public record Page(List<ObjectNode> items, long total, int page, int pageSize) {}
}
