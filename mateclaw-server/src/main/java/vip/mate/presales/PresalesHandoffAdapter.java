package vip.mate.presales;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vip.mate.presales.api.PresalesHandoffReader;

@Component
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesHandoffAdapter implements PresalesHandoffReader {
    private final PresalesService presales;
    private final ObjectMapper json;

    public PresalesHandoffAdapter(PresalesService presales, ObjectMapper json) {
        this.presales = presales;
        this.json = json;
    }

    @Override
    public Handoff read(String workspaceId, String projectId, String releaseId) {
        try {
            return Handoff.from(
                    projectId,
                    releaseId,
                    json.writeValueAsString(
                            presales.handoffForDelivery(workspaceId, projectId, releaseId)));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Published handoff cannot be encoded", invalid);
        }
    }
}
