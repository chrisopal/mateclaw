package vip.mate.delivery;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;

@RestControllerAdvice(basePackages = "vip.mate.delivery")
@Order(-101)
public class DeliveryExceptionHandler {
    public record ErrorData(String error) {}

    @ExceptionHandler(DeliveryRejected.class)
    public ResponseEntity<R<ErrorData>> api(DeliveryRejected error) {
        return response(error.status(), error.code());
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<R<ErrorData>> duplicate(Exception error) {
        return response(409, "OPERATION_CONFLICT");
    }

    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.ServletRequestBindingException.class
    })
    public ResponseEntity<R<ErrorData>> invalid(Exception error) {
        return response(400, "INVALID_REQUEST");
    }

    private ResponseEntity<R<ErrorData>> response(int status, String code) {
        R<ErrorData> body = R.fail(status, code);
        body.setData(new ErrorData(code));
        return ResponseEntity.status(status).body(body);
    }
}
