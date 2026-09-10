package vip.mate.semantic.core.reasoning;

/** Port for bounded semantic reasoning; implementations may use an isolated engine process. */
public interface ReasoningPort {
    ReasoningResult reason(ReasoningRequest request);
}
