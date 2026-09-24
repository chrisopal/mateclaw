package vip.mate.bidding;

import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vip.mate.common.result.R;

@RestControllerAdvice(basePackageClasses = BiddingController.class)
@Order(-101)
public class BiddingExceptionHandler {
    @ExceptionHandler(BiddingApiException.class)
    public ResponseEntity<R<Object>> api(BiddingApiException e) {
        R<Object> body=R.fail(e.status(),e.getMessage()); body.setData(Map.of("code",e.code()));
        return ResponseEntity.status(e.status()).body(body);
    }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<R<Object>> duplicate(Exception e) { return error(409,"OPERATION_CONFLICT","Concurrent operation conflict"); }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<R<Object>> malformed(Exception e) { return error(400,"INVALID_REQUEST","Malformed request"); }
    private ResponseEntity<R<Object>> error(int status,String code,String message) {
        R<Object> body=R.fail(status,message); body.setData(Map.of("code",code)); return ResponseEntity.status(status).body(body);
    }
}
