package edu.campusloop.common;
/** Adapted from the reference ResultVo; HTTP status and code now agree. */
public record ResultVo<T>(int code, String msg, T data) {
    public static <T> ResultVo<T> success(T data) { return new ResultVo<>(200, "成功", data); }
    public static ResultVo<Void> error(int code, String message) { return new ResultVo<>(code, message, null); }
}
