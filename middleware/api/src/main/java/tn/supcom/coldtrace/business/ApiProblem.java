package tn.supcom.coldtrace.business;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

public class ApiProblem extends RuntimeException {
    final int status;
    final String code;
    public ApiProblem(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public static ApiProblem invalid(String message) { return new ApiProblem(400, "validation_error", message); }
    public static ApiProblem missing(String entity) { return new ApiProblem(404, "not_found", entity + " not found"); }
    public static ApiProblem conflict(String message) { return new ApiProblem(409, "conflict", message); }
    @Provider
    public static class Mapper implements ExceptionMapper<ApiProblem> {
        public Response toResponse(ApiProblem e) {
            return Response.status(e.status).entity(Map.of("error", e.getMessage(), "code", e.code)).type("application/json").build();
        }
    }
}
