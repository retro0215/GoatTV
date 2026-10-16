package android.util;

/**
 * Test-only stand-in for the framework enum (same constants), so JVM unit tests can run the real
 * Xtream parsers instead of android.jar's stubs. Never packaged into the app. See JsonReader here.
 */
public enum JsonToken {
    BEGIN_ARRAY,
    END_ARRAY,
    BEGIN_OBJECT,
    END_OBJECT,
    NAME,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    END_DOCUMENT
}
