package vip.mate.bidding;

public class BiddingApiException extends RuntimeException {
    private final int status;
    private final String code;
    public BiddingApiException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
}
