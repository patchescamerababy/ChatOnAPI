package utils;

public class BearerTokenGeneratorNative {
    static {
        System.loadLibrary("Bearer_token_generator");
    }

    public static native String[] GetBearer(String jsonBody);
}