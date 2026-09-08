package vip.mate.semantic.application.extraction;

/** Safe application error: messages contain no source text or provider response. */
public class ExtractionException extends RuntimeException {
    private final String code;
    private final int status;
    public ExtractionException(int status,String code){super(code);this.code=code;this.status=status;}
    public String code(){return code;}
    public int status(){return status;}
}
