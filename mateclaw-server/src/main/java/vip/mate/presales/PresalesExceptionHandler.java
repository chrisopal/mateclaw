package vip.mate.presales;

import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.semantic.web.SemanticApiException;

@RestControllerAdvice(basePackages = "vip.mate.presales")
@Order(-101)
public class PresalesExceptionHandler {
  @ExceptionHandler(SemanticApiException.class)
  public ResponseEntity<R<Object>> api(SemanticApiException e) {
    return error(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  public ResponseEntity<R<Object>> duplicate(Exception e) {
    return error(409, "OPERATION_CONFLICT", "Concurrent operation; retry with same operationId");
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.ServletRequestBindingException.class
  })
  public ResponseEntity<R<Object>> invalid(Exception e) {
    return error(400, "INVALID_REQUEST", "Malformed request");
  }

  private ResponseEntity<R<Object>> error(int status, String code, String message) {
    R<Object> r = R.fail(status, message);
    r.setData(Map.of("code", code));
    return ResponseEntity.status(status).body(r);
  }
}
