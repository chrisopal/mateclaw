package vip.mate.semantic.reasoning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Resource and rollout controls for the isolated reasoning worker. */
@ConfigurationProperties(prefix = "mateclaw.semantic.reasoning")
public class SemanticReasoningProperties {
    private boolean enabled = true;
    private int timeoutSeconds = 30;
    private int memoryMb = 512;
    private int maxConcurrency = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMemoryMb() { return memoryMb; }
    public void setMemoryMb(int memoryMb) { this.memoryMb = memoryMb; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
}
