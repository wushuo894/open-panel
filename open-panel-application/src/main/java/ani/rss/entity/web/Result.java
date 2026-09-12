package ani.rss.entity.web;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Getter
@Accessors(chain = true)
public class Result<T> implements Serializable {
    private final int code;
    private final String message;
    private final T data;
    private final long t;

    public Result(int code, String message, T data, long t) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.t = t;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data, System.currentTimeMillis());
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static Result<Void> error(int code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }
}
