package sc;

public record ActionResult(boolean success, String reason) {
    public static ActionResult ok() { return new ActionResult(true, null); }
    public static ActionResult fail(String reason) { return new ActionResult(false, reason); }
}
