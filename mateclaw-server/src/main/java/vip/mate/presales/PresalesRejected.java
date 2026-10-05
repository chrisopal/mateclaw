package vip.mate.presales;

/** Domain rejection shared by pure presales rules; the service owns external error adaptation. */
final class PresalesRejected extends RuntimeException {
    private final int status;
    private final String code;

    PresalesRejected(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
