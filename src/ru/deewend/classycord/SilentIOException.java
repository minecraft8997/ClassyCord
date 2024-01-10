package ru.deewend.classycord;

/*
 * Stacktraces are not generated for this
 * exception, thus it should be faster a bit.
 */
public class SilentIOException extends Throwable {
    public SilentIOException(String message) {
        super(message, null, false, false);
    }
}
