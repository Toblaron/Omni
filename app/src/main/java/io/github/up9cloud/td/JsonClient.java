package io.github.up9cloud.td;

/**
 * JNI entry points exported by libtdjson.so (TDLib JSON interface, prebuilt by
 * github.com/up9cloud/android-libtdjson). The package and method names must match the
 * library's Java_io_github_up9cloud_td_JsonClient_* symbols. Loading is done by
 * {@link com.omni.inbox.Telegram} so a missing library degrades gracefully.
 */
public final class JsonClient {
    private JsonClient() {}

    public static native int td_create_client_id();
    public static native void td_send(int client_id, String request);
    public static native String td_receive(double timeout);
    public static native String td_execute(String request);
}
