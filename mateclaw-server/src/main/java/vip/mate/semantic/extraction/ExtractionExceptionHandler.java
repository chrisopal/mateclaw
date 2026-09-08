package vip.mate.semantic.extraction;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import vip.mate.common.result.R;
import vip.mate.semantic.application.extraction.ExtractionException;
import vip.mate.semantic.web.SemanticExceptionHandler;

@RestControllerAdvice(assignableTypes=ExtractionController.class)
@Order(-101)
public class ExtractionExceptionHandler extends SemanticExceptionHandler {
    @ExceptionHandler(ExtractionException.class)
    public ResponseEntity<R<Object>> application(ExtractionException e){return safe(e.status(),e.code());}
    @Override public ResponseEntity<R<Object>> unexpected(Exception e){return safe(500,"EXTRACTION_FAILED");}
    private ResponseEntity<R<Object>> safe(int status,String code){R<Object> body=R.fail(status,code);body.setData(Map.of("code",code,"fieldErrors",List.of(),"traceId",UUID.randomUUID().toString()));return ResponseEntity.status(status).body(body);}
}
