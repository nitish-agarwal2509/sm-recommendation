package supermoney.recommendation.api.controller.dto;

/**
 * Error response body for 4xx responses.
 */
public class ErrorResponse {

    private String userId;
    private String surface;
    private String error;
    private String message;

    public ErrorResponse() {}

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
    }

    public ErrorResponse(String userId, String surface, String error, String message) {
        this.userId = userId;
        this.surface = surface;
        this.error = error;
        this.message = message;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSurface() { return surface; }
    public void setSurface(String surface) { this.surface = surface; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
